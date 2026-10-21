package io.wadspaces.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.wadspaces.core.board.BMachine
import io.wadspaces.core.board.BWadspace
import io.wadspaces.core.board.Board
import io.wadspaces.core.board.Op
import io.wadspaces.core.board.V4
import io.wadspaces.core.firebase.Session
import io.wadspaces.core.firebase.Values
import io.wadspaces.core.library.Library
import io.wadspaces.core.remote.Machine
import io.wadspaces.core.remote.RemoteFailure
import io.wadspaces.core.remote.RemoteHello
import io.wadspaces.core.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
    /** "starting", "queued", "building", "done", "error", "cancelled"; "sent": a command its relay runs. */
    val status: String = "starting",
    val progress: Double = 0.0,
    val lines: List<String> = emptyList(),
    val error: String? = null,
) {
    val finished get() = status in setOf("done", "error", "cancelled", "sent")
}

/**
 * Design B's board on the phone (core's Board): the account's version 4
 * wadspaces, each edit saved behind it. `rev` changes with every change, so
 * screens draw it again.
 */
data class BoardState(
    val board: Board,
    val rev: Int = 0,
    val saving: Boolean = false,
    /** Why the last save failed, until one goes through. */
    val error: String? = null,
)

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

    private val _board = MutableStateFlow<BoardState?>(null)
    /** Design B's board (null until the library's read). */
    val board: StateFlow<BoardState?> = _board

    /** Design B's documents as last saved (what a save compares to), and the writes out. */
    private var saved: List<BWadspace> = emptyList()
    private var pending = 0

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

    private fun now() = Values.rfc3339(System.currentTimeMillis())

    /** Wadspaces as JSON, in id order (the library's order isn't the board's). */
    private fun byId(ws: List<BWadspace>) = ws.sortedBy { it.id }.map { it.json() }

    /** Design B's board from the library, unless its own writes are out or it has changes not saved. */
    private fun boardFrom(lib: Library) {
        val machines = lib.machines.map { BMachine(it.str("id"), it.str("name").ifEmpty { it.str("hostname") }) }
        val docs = lib.v4.map(BWadspace::of)
        val cur = _board.value
        if (cur != null) {
            cur.board.machines = machines
            cur.board.reserved = lib.reserved
            val unsaved = cur.board.plan(saved, "", "").isNotEmpty()
            if (pending > 0 || unsaved || byId(docs) == byId(saved)) {
                _board.value = cur.copy(rev = cur.rev + 1)
                return
            }
        }
        saved = docs
        _board.value = BoardState(lib.board(machines, now()), (cur?.rev ?: 0) + 1)
    }

    /** Edits of design B's board, made as one (one Undo) and saved: why not, if they couldn't be. */
    fun edit(vararg ops: Op): String? {
        val st = _board.value ?: return "Your library isn't here yet."
        st.board.now = now()
        val why = if (ops.toList() == listOf(Op.Undo)) st.board.apply(Op.Undo, g.packages) else st.board.applyAll(ops.toList(), g.packages)
        if (why != null) return why
        _board.value = st.copy(rev = st.rev + 1)
        saveBoard()
        return null
    }

    /** What changed on the board, written: the library at once, the account behind. */
    fun saveBoard() {
        val st = _board.value ?: return
        val uid = g.auth.uid ?: return
        val plan = st.board.plan(saved, uid, now())
        if (plan.isEmpty()) return
        val docs = plan.map(BWadspace::of)
        val before = saved
        saved = saved.filter { s -> docs.none { it.id == s.id } } + docs
        // The board's as saved: its places, owner and times.
        for (d in docs) st.board.get(d.id)?.let { w ->
            w.order = d.order
            w.owner = d.owner
            w.createdAt = d.createdAt
            w.updatedAt = d.updatedAt
        }
        lib()?.let { l -> _library.value = LibState.Ready(l.copy(v4 = l.v4.filter { v -> plan.none { it.str("id") == v.str("id") } } + plan)) }
        pending++
        _board.value = st.copy(rev = st.rev + 1, saving = true, error = null)
        viewModelScope.launch {
            val r = runCatching { g.account.saveWadspaces(plan) }
            pending--
            if (r.isFailure) saved = before
            val cur = _board.value ?: return@launch
            _board.value = cur.copy(
                rev = cur.rev + 1,
                saving = pending > 0,
                error = r.exceptionOrNull()?.let { "Couldn't save the board: ${it.message}. Your changes are kept here." },
            )
        }
    }

    /** Where a design B wadspace stands on its machine: "building", "built", "update", "never", or "unknown". */
    fun standing(w: BWadspace): String {
        val run = _runs.value[w.id]
        if (run != null && !run.finished) return "building"
        val m = _machines.value.firstOrNull { it.machine.id == w.machine }?.machine ?: return "unknown"
        if (m.images.isEmpty()) return "unknown"
        return V4.state(w.json(), m.images, g.packages)
    }

    /**
     * Builds a design B wadspace where it lives: over the tailnet when its
     * machine trusts this phone, else as a command its relay runs. (Either
     * reads the saved document: after the board's writes.)
     */
    fun buildOnItsMachine(id: String) {
        val w = _board.value?.board?.get(id) ?: return
        viewModelScope.launch {
            var waited = 0
            while (pending > 0 && waited++ < 50) delay(200)
            val mv = _machines.value.firstOrNull { it.machine.id == w.machine }
            if (mv != null && mv.ready && mv.machine.online) {
                build(id, mv.machine)
                return@launch
            }
            val name = mv?.machine?.name ?: "its machine"
            runCatching { g.account.command(w.machine, "build", id) }
                .onSuccess { run(id) { BuildRun(w.machine, status = "sent", lines = listOf("Sent to $name: its relay builds it within a few seconds when it's on.")) } }
                .onFailure { e -> run(id) { BuildRun(w.machine, status = "error", error = e.message ?: "Couldn't ask $name.") } }
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
        _board.value = null
        saved = emptyList()
    }

    /** The library read again, and each machine asked how it is. */
    fun refresh() {
        viewModelScope.launch {
            try {
                val lib = g.account.library()
                _library.value = LibState.Ready(lib)
                machinesFrom(lib)
                boardFrom(lib)
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
                boardFrom(fresh)
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

    /** A code to link a new machine with (WadSpaces asks for it there). */
    suspend fun linkCode(name: String): String = g.account.createEnrollCode(name)

    /** The library now, if it's read. */
    fun lib(): Library? = (_library.value as? LibState.Ready)?.lib
}
