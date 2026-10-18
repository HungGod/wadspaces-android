package io.wadspaces.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.wadspaces.core.board.Sync
import io.wadspaces.core.builder.Builder
import io.wadspaces.core.builder.Customization
import io.wadspaces.core.builder.ExtensionsPick
import io.wadspaces.core.builder.TemplatePick
import io.wadspaces.core.firebase.Session
import io.wadspaces.core.firebase.Values
import io.wadspaces.core.library.Library
import io.wadspaces.core.remote.Machine
import io.wadspaces.core.remote.RemoteFailure
import io.wadspaces.core.remote.RemoteHello
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** The library as far as it's read. */
sealed interface LibState {
    data object Loading : LibState
    data class Ready(val lib: Library) : LibState
    data class Failed(val why: String) : LibState
}

/** A wadspace being built on a machine, as the phone follows it. */
data class BuildRun(
    val machine: String,
    val buildId: String? = null,
    /** "starting", "queued", "building", "done", "error", "cancelled". */
    val status: String = "starting",
    val progress: Double = 0.0,
    val lines: List<String> = emptyList(),
    val error: String? = null,
) {
    val finished get() = status in setOf("done", "error", "cancelled")
}

/** One of the owner's machines, and how this phone gets on with it. */
data class MachineView(
    val machine: Machine,
    val hello: RemoteHello? = null,
    /** Asking it now. */
    val checking: Boolean = false,
    /** Why it can't be used from here (null: it can, or it's being asked). */
    val problem: String? = null,
) {
    val ready get() = hello?.trust == "trusted"

    /** What to say about it. */
    val status: String
        get() = when {
            !machine.online -> "Offline"
            machine.target == null -> when (machine.tailnet) {
                "needsLogin", "off" -> "Not on your tailnet: join it there (Machines → Tailnet)"
                "loggingIn" -> "Joining the tailnet: approve it"
                "needsApproval" -> "Waiting for the tailnet's admin"
                else -> "Not on your tailnet"
            }
            checking && hello == null -> "Asking…"
            problem != null -> problem
            hello?.trust == "asked" -> "Allow this phone on ${machine.name} (it's asking there)"
            ready -> "Ready"
            else -> "…"
        }
}

class AppModel(val g: Graph) : ViewModel() {
    private val _session = MutableStateFlow(g.auth.session)
    val session: StateFlow<Session?> = _session

    private val _library = MutableStateFlow<LibState>(LibState.Loading)
    val library: StateFlow<LibState> = _library

    private val _machines = MutableStateFlow<List<MachineView>>(emptyList())
    val machines: StateFlow<List<MachineView>> = _machines

    private val _runs = MutableStateFlow<Map<String, BuildRun>>(emptyMap())
    /** Builds asked for from here, by wadspace. */
    val runs: StateFlow<Map<String, BuildRun>> = _runs

    init {
        if (_session.value != null) refresh()
    }

    private fun run(id: String, f: (BuildRun?) -> BuildRun) {
        _runs.value = _runs.value + (id to f(_runs.value[id]))
    }

    /**
     * Builds a wadspace on a machine (installed there from the account
     * first), following its log here until it's done.
     */
    fun build(id: String, m: Machine) {
        val t = m.target ?: return
        run(id) { BuildRun(m.id) }
        viewModelScope.launch {
            try {
                val b = g.remote.build(t, id)
                val warn = b.warnings.map { "! $it" }
                val buildId = b.buildId
                if (buildId == null) {
                    run(id) { BuildRun(m.id, status = "done", progress = 1.0, lines = warn + "Built already: it's up to date there.") }
                    return@launch
                }
                run(id) { BuildRun(m.id, buildId, "queued", lines = warn) }
                var since = 0L
                while (true) {
                    val log = g.remote.buildLog(t, buildId, since)
                    since = log.from + log.lines.size
                    run(id) { r ->
                        (r ?: BuildRun(m.id, buildId)).copy(
                            status = log.build.status,
                            progress = log.build.progress,
                            lines = ((r?.lines ?: emptyList()) + log.lines).takeLast(400),
                            error = log.build.error,
                        )
                    }
                    if (log.build.finished) break
                    delay(1_000)
                }
            } catch (e: Exception) {
                run(id) { r -> (r ?: BuildRun(m.id)).copy(status = "error", error = e.message ?: "The build didn't start.") }
            }
        }
    }

    /** Signed in with the sign-in page's GitHub token. */
    suspend fun signIn(githubToken: String) {
        _session.value = g.auth.signInWithGithub(githubToken)
        refresh()
    }

    fun signOut() {
        g.auth.signOut()
        _session.value = null
        _library.value = LibState.Loading
        _machines.value = emptyList()
    }

    /** The library read again, and each machine asked how it is. */
    fun refresh() {
        viewModelScope.launch {
            try {
                val lib = g.account.library()
                _library.value = LibState.Ready(lib)
                machinesFrom(lib)
            } catch (e: Exception) {
                if (g.auth.session == null) _session.value = null
                if (_library.value !is LibState.Ready) _library.value = LibState.Failed(e.message ?: "Couldn't read your library.")
            }
        }
    }

    /** The machines' heartbeats read again (the library as it is). */
    fun refreshMachines() {
        viewModelScope.launch {
            val lib = (_library.value as? LibState.Ready)?.lib ?: return@launch refresh()
            runCatching { g.account.machines() }.onSuccess { hbs ->
                val fresh = lib.copy(machines = hbs)
                _library.value = LibState.Ready(fresh)
                machinesFrom(fresh)
            }
        }
    }

    private fun machinesFrom(lib: Library) {
        val now = System.currentTimeMillis()
        val old = _machines.value
        val list = lib.machines.map { Machine.of(it, now) }.sortedWith(compareBy({ !it.online }, { it.name.lowercase() }))
        _machines.value = list.map { m -> old.firstOrNull { it.machine.id == m.id }?.copy(machine = m) ?: MachineView(m) }
        list.filter { it.online && it.target != null }.forEach(::hello)
    }

    /** Says hello to a machine: whether it trusts this phone (asking it to, if not). */
    fun hello(m: Machine) {
        val t = m.target ?: return
        update(m.id) { it.copy(checking = true) }
        viewModelScope.launch {
            val r = runCatching { g.remote.hello(t) }
            update(m.id) {
                it.copy(
                    checking = false,
                    hello = r.getOrNull() ?: it.hello,
                    problem = r.exceptionOrNull()?.let { e -> (e as? RemoteFailure)?.message ?: e.message },
                )
            }
        }
    }

    private fun update(id: String, f: (MachineView) -> MachineView) {
        _machines.value = _machines.value.map { if (it.machine.id == id) f(it) else it }
    }

    /**
     * The builder's picks saved to the account as one new wadspace (the
     * board's planNew, one commit): its id.
     */
    suspend fun save(t: TemplatePick, e: ExtensionsPick, c: Customization): String {
        val lib = lib() ?: error("Your library isn't here yet.")
        val uid = g.auth.uid ?: error("Signed out.")
        val id = Builder.uid("stack")
        val stack = Builder.stack(id, t, e, c, lib.lib())
        val plan = Sync.planNew(stack, lib.docs(), uid, Values.rfc3339(System.currentTimeMillis()))
        g.account.save(plan)
        // As saved, before the account is read again.
        _library.value = LibState.Ready(
            lib.copy(
                templates = docsOf(plan, "templates"),
                extensions = docsOf(plan, "extensions"),
                wadspaces = docsOf(plan, "wadspaces"),
            ),
        )
        refresh()
        return id
    }

    private fun docsOf(plan: JsonObject, k: String): List<JsonObject> =
        ((plan["docs"] as JsonObject)[k] as JsonArray).map { it as JsonObject }

    /** A code to link a new machine with (WadSpaces asks for it there). */
    suspend fun linkCode(name: String): String = g.account.createEnrollCode(name)

    /** The library now, if it's read. */
    fun lib(): Library? = (_library.value as? LibState.Ready)?.lib
}
