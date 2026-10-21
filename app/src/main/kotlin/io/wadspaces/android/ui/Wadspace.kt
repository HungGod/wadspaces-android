package io.wadspaces.android.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.wadspaces.android.AppModel
import io.wadspaces.android.LibState
import io.wadspaces.android.StreamActivity
import io.wadspaces.core.board.Layers
import io.wadspaces.core.board.V4
import io.wadspaces.core.firebase.HttpRequest
import io.wadspaces.core.get
import io.wadspaces.core.remote.Machine
import io.wadspaces.core.remote.RemoteFailure
import io.wadspaces.core.remote.RemoteWadspace
import io.wadspaces.core.remote.StreamShare
import io.wadspaces.core.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The machine says its stream is up: routed, and its sidecar answering. */
private val RemoteWadspace.streamUp get() = streamed && live && phase == "ready"

/**
 * A wadspace: build it on one of your machines (its log as it goes), open
 * it there, or stream it to this phone. A stream is started, then opened
 * only once it's ready end to end: the machine says it's up, and its link
 * answers from here (its login asked for, not Cloudflare's error page).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WadspaceScreen(model: AppModel, id: String, startOn: String?, buildFirst: Boolean, back: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by model.library.collectAsState()
    val machines by model.machines.collectAsState()
    val runs by model.runs.collectAsState()
    val lib = (state as? LibState.Ready)?.lib ?: return Centered("Your library isn't here yet.")
    val w = lib.wadspaces.firstOrNull { it.str("id") == id } ?: lib.v4.firstOrNull { it.str("id") == id } ?: return Centered("That wadspace is gone.")
    // A version 4 wadspace lives on one machine.
    val v4 = V4.isV4(w)
    val home = w.str("machine")
    val ready = machines.filter { it.ready && it.machine.online && (!v4 || it.machine.id == home) }.map { it.machine }
    var picked by remember { mutableStateOf(startOn) }
    val machine: Machine? = ready.firstOrNull { it.id == picked } ?: ready.firstOrNull()
    var here by remember(machine?.id) { mutableStateOf<RemoteWadspace?>(null) }
    // The stream: its link (once started or found), and whether it answers from here.
    var share by remember(machine?.id) { mutableStateOf<StreamShare?>(null) }
    var starting by remember(machine?.id) { mutableStateOf(false) }
    var linkOk by remember(machine?.id) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var takeover by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val run = runs[id]?.takeIf { it.machine == machine?.id || (v4 && it.machine == home) }
    val name = w["customization"].str("name").ifEmpty { "Untitled" }
    val notReady = if (v4) "Its customization needs a name.".takeIf { w["customization"].str("name").isBlank() } else Layers.notReady(w, lib.lib())
    val streamUp = here?.streamUp == true
    val openable = streamUp && linkOk && share != null

    val stream = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val m = machine ?: return@rememberLauncherForActivityResult
        if (r.data?.getBooleanExtra(StreamActivity.STOP, false) == true) {
            share = null
            linkOk = false
            scope.launch { runCatching { model.g.remote.stopStream(m.target!!, id) } }
        }
        if (r.data?.getBooleanExtra(StreamActivity.HOME, false) == true) back()
    }

    // Built on a machine straight after saving (the builder's "Save and build").
    LaunchedEffect(machine?.id) {
        val m = machine ?: return@LaunchedEffect
        if (buildFirst && !asked && m.id == startOn) {
            asked = true
            model.build(id, m)
        }
    }

    // What the machine has of it: every 5 s (2 s while a stream comes up),
    // only while the app is in front (not while a stream's open over it).
    LaunchedEffect(machine?.id, run?.status) {
        val t = machine?.target ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                here = runCatching { model.g.remote.wadspaces(t).firstOrNull { it.id == id } }.getOrNull()
                val waiting = starting || (here?.streamed == true && !linkOk)
                if (here?.streamed != true && !starting) {
                    share = null
                    linkOk = false
                }
                delay(if (waiting) 2_000 else 5_000)
            }
        }
    }

    // Up on the machine: its link (a stream from before gives the one it has),
    // then asked from here until it answers with its login.
    LaunchedEffect(streamUp, machine?.id) {
        val t = machine?.target ?: return@LaunchedEffect
        if (!streamUp) return@LaunchedEffect
        try {
            val s = share ?: model.g.remote.stream(t, id, false).also { share = it }
            for (i in 0 until 60) {
                val status = runCatching { model.g.http.send(HttpRequest("GET", s.link, timeoutMs = 10_000)).status }.getOrDefault(0)
                if (status == 401 || status in 200..399) {
                    linkOk = true
                    break
                }
                delay(2_000)
            }
            if (!linkOk) error = "The stream is up on ${machine.name}, but its link doesn't answer from here yet."
        } catch (e: Exception) {
            error = e.message
        }
        starting = false
    }

    fun startStream(take: Boolean) {
        val m = machine ?: return
        val t = m.target ?: return
        error = null
        starting = true
        linkOk = false
        scope.launch {
            try {
                share = model.g.remote.stream(t, id, take)
                // Given up after three minutes with nothing.
                delay(180_000)
                if (starting) {
                    starting = false
                    error = "The stream didn't come up on ${m.name}: try again."
                }
            } catch (e: RemoteFailure) {
                starting = false
                if (e.status == 409 && !take) takeover = true else error = e.message
            } catch (e: Exception) {
                starting = false
                error = e.message
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TextButton(onClick = back, Modifier.padding(start = 8.dp, top = 4.dp)) { Text("‹ Wadspaces") }
        Wallpaper(w["customization"]["wallpaper"], model.g.catalog, Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(horizontal = 16.dp))
        Column(Modifier.padding(20.dp)) {
            Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            w["customization"].str("description").takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(8.dp))
            Text(madeOf(w, lib, model), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))

            if (notReady != null) {
                Text(notReady, color = MaterialTheme.colorScheme.error)
                return@Column
            }
            if (ready.isEmpty() && v4) {
                val on = machines.firstOrNull { it.machine.id == home }
                Text(
                    "${on?.machine?.name ?: "Its machine"} isn't reachable from this phone (${on?.status ?: "not seen"}). It builds there anyway: its relay runs the build when it's on.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { model.buildOnItsMachine(id) }, Modifier.fillMaxWidth(), enabled = run == null || run.finished) {
                    Text("Build on ${on?.machine?.name ?: "its machine"}")
                }
                runs[id]?.let { r -> BuildLog(r.status, r.progress, r.lines, r.error) }
                return@Column
            }
            if (ready.isEmpty()) {
                Text(
                    "None of your machines trusts this phone yet, or none is up. On the machine: WadSpaces → Machines → Tailnet, then allow this phone.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Text("On", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ready.forEach { m -> FilterChip(m.id == machine?.id, { picked = m.id }, { Text(m.name) }) }
            }
            Spacer(Modifier.height(8.dp))
            val m = machine ?: return@Column
            val h = here
            val building = run != null && !run.finished || h?.building != null
            val streaming = starting || h?.streamed == true
            Text(
                when {
                    building -> "Building on ${m.name}…"
                    h == null -> "Not on ${m.name} yet: build it there."
                    !h.built -> "On ${m.name}, not built yet."
                    openable -> "Streaming from ${m.name}: ready to open."
                    streaming -> "Starting its stream on ${m.name}…"
                    h.onScreen -> "Open on ${m.name}'s screen."
                    h.container == "running" -> "Running on ${m.name}."
                    else -> "Built on ${m.name}."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            val built = h?.built == true && !building
            if (!streaming) {
                Button(onClick = { startStream(false) }, Modifier.fillMaxWidth(), enabled = built) { Text("Stream to this phone") }
            } else {
                Button(
                    onClick = {
                        val s = share ?: return@Button
                        stream.launch(
                            Intent(ctx, StreamActivity::class.java)
                                .putExtra(StreamActivity.URL, s.openLink.ifEmpty { s.link })
                                .putExtra(StreamActivity.TITLE, name)
                                .putExtra(StreamActivity.MACHINE, m.id)
                                .putExtra(StreamActivity.MACHINE_NAME, m.name)
                                .putExtra(StreamActivity.SCREEN, m.screen?.let(StreamActivity::screenText)),
                        )
                    },
                    Modifier.fillMaxWidth(),
                    enabled = openable,
                ) {
                    if (!openable) {
                        CircularProgressIndicator(Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Starting the stream…")
                    } else {
                        Text("Open stream")
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    error = null
                    starting = false
                    share = null
                    linkOk = false
                    scope.launch { runCatching { model.g.remote.stopStream(m.target!!, id) }.onFailure { error = it.message } }
                }, Modifier.fillMaxWidth()) { Text("Stop streaming") }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { model.build(id, m) }, Modifier.fillMaxWidth(), enabled = !building && !streaming) {
                Text(if (h?.built == true) "Build again on ${m.name}" else "Build on ${m.name}")
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = built && !streaming, onClick = {
                    error = null
                    scope.launch { runCatching { model.g.remote.open(m.target!!, id) }.onFailure { error = it.message } }
                }, modifier = Modifier.weight(1f)) { Text("Open on ${m.name}") }
                OutlinedButton(enabled = h?.container == "running" && !streaming, onClick = {
                    error = null
                    scope.launch { runCatching { model.g.remote.stop(m.target!!, id) }.onFailure { error = it.message } }
                }, modifier = Modifier.weight(1f)) { Text("Stop") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
            run?.let { r -> BuildLog(r.status, r.progress, r.lines, r.error) }
        }
    }

    if (takeover) {
        AlertDialog(
            onDismissRequest = { takeover = false },
            title = { Text("It's open on ${machine?.name}") },
            text = { Text("Streaming it takes it off that screen. Whoever's there loses it until it's opened again.") },
            confirmButton = { TextButton(onClick = { takeover = false; startStream(true) }) { Text("Take it off the screen") } },
            dismissButton = { TextButton(onClick = { takeover = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BuildLog(status: String, progress: Double, lines: List<String>, error: String?) {
    Spacer(Modifier.height(20.dp))
    Text(
        when (status) {
            "done" -> "Built"
            "error" -> "The build failed"
            "cancelled" -> "Cancelled"
            "queued" -> "Waiting for another build"
            "sent" -> "Sent to its machine"
            "starting" -> "Starting…"
            else -> "Building"
        },
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    if (status !in setOf("done", "error", "cancelled", "sent")) {
        LinearProgressIndicator(progress = { progress.toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(vertical = 8.dp))
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (lines.isNotEmpty()) {
        val scroll = rememberScrollState()
        LaunchedEffect(lines.size) { scroll.animateScrollTo(scroll.maxValue) }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest).verticalScroll(scroll).padding(10.dp),
        ) {
            Text(
                lines.joinToString("\n"),
                Modifier.horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                softWrap = false,
            )
        }
    }
}
