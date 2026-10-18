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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
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
import io.wadspaces.android.AppModel
import io.wadspaces.android.LibState
import io.wadspaces.android.StreamActivity
import io.wadspaces.core.board.Layers
import io.wadspaces.core.get
import io.wadspaces.core.remote.Machine
import io.wadspaces.core.remote.RemoteFailure
import io.wadspaces.core.remote.RemoteWadspace
import io.wadspaces.core.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A wadspace: build it on one of your machines (its log as it goes), open
 * it there, or stream it to this phone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WadspaceScreen(model: AppModel, id: String, back: () -> Unit) {
    val ctx = LocalContext.current
    val state by model.library.collectAsState()
    val machines by model.machines.collectAsState()
    val runs by model.runs.collectAsState()
    val lib = (state as? LibState.Ready)?.lib ?: return Centered("Your library isn't here yet.")
    val w = lib.wadspaces.firstOrNull { it.str("id") == id } ?: return Centered("That wadspace is gone.")
    val ready = machines.filter { it.ready && it.machine.online }.map { it.machine }
    var picked by remember { mutableStateOf<String?>(null) }
    val machine: Machine? = ready.firstOrNull { it.id == picked } ?: ready.firstOrNull()
    var here by remember(machine?.id) { mutableStateOf<RemoteWadspace?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var takeover by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val run = runs[id]?.takeIf { it.machine == machine?.id }
    val name = w["customization"].str("name").ifEmpty { "Untitled" }
    val notReady = Layers.notReady(w, lib.lib())

    val stream = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        // Back from the stream: stopped if asked to.
        val m = machine ?: return@rememberLauncherForActivityResult
        if (r.data?.getBooleanExtra(StreamActivity.STOP, false) == true) {
            scope.launch { runCatching { model.g.remote.stopStream(m.target!!, id) } }
        }
    }

    // What the machine has of it, read now and every few seconds.
    LaunchedEffect(machine?.id, run?.status) {
        val t = machine?.target ?: return@LaunchedEffect
        while (true) {
            here = runCatching { model.g.remote.wadspaces(t).firstOrNull { it.id == id } }.getOrNull()
            delay(5_000)
        }
    }

    fun startStream(take: Boolean) {
        val m = machine ?: return
        val t = m.target ?: return
        error = null
        busy = "Starting the stream on ${m.name}…"
        scope.launch {
            try {
                val share = model.g.remote.stream(t, id, take)
                // Up once the machine says the stream is live (its sidecar answering).
                for (i in 0 until 120) {
                    val now = runCatching { model.g.remote.wadspaces(t).firstOrNull { it.id == id } }.getOrNull()
                    if (now?.live == true) break
                    delay(1_000)
                }
                busy = null
                stream.launch(
                    Intent(ctx, StreamActivity::class.java)
                        .putExtra(StreamActivity.URL, share.openLink.ifEmpty { share.link })
                        .putExtra(StreamActivity.TITLE, name),
                )
            } catch (e: RemoteFailure) {
                busy = null
                if (e.status == 409 && !take) takeover = true else error = e.message
            } catch (e: Exception) {
                busy = null
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
            val building = run != null && !run.finished || here?.building != null
            Text(
                when {
                    building -> "Building on ${m.name}…"
                    here == null -> "Not on ${m.name} yet: build it there."
                    !here!!.built -> "On ${m.name}, not built yet."
                    here!!.live -> "Streamed from ${m.name}."
                    here!!.onScreen -> "Open on ${m.name}'s screen."
                    here!!.container == "running" -> "Running on ${m.name}."
                    else -> "Built on ${m.name}."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            val built = here?.built == true && !building
            Button(onClick = { model.build(id, m) }, Modifier.fillMaxWidth(), enabled = !building && busy == null) {
                Text(if (here?.built == true) "Build again on ${m.name}" else "Build on ${m.name}")
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { startStream(false) }, Modifier.fillMaxWidth(), enabled = built && busy == null) { Text("Stream to this phone") }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = built && busy == null, onClick = {
                    error = null
                    scope.launch { runCatching { model.g.remote.open(m.target!!, id) }.onFailure { error = it.message } }
                }, modifier = Modifier.weight(1f)) { Text("Open on ${m.name}") }
                OutlinedButton(enabled = here?.container == "running" && busy == null, onClick = {
                    error = null
                    scope.launch { runCatching { model.g.remote.stop(m.target!!, id) }.onFailure { error = it.message } }
                }, modifier = Modifier.weight(1f)) { Text("Stop") }
            }
            busy?.let {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(it)
                }
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
            "starting" -> "Starting…"
            else -> "Building"
        },
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    if (status !in setOf("done", "error", "cancelled")) {
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
