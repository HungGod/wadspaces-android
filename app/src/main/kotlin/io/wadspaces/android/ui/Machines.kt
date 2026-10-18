package io.wadspaces.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.wadspaces.android.AppModel
import io.wadspaces.android.MachineView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MachinesScreen(model: AppModel) {
    val list by model.machines.collectAsState()
    var code by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // The heartbeats again every 30 s while this shows (and the app's in front).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                model.refreshMachines()
                delay(30_000)
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            PageHead("Machines", "Your WadSpaces machines. Over your tailnet, a machine that trusts this phone builds, opens and streams wadspaces for it.")
        }
        if (list.isEmpty()) {
            item { Text("No machines linked yet.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(list, key = { it.machine.id }) { MachineCard(it) { model.hello(it.machine) } }
        item {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                OutlinedButton(onClick = {
                    error = null
                    scope.launch {
                        try {
                            code = model.linkCode("A new machine")
                        } catch (e: Exception) {
                            error = e.message
                        }
                    }
                }) { Text("Link a machine") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }
    code?.let { c ->
        AlertDialog(
            onDismissRequest = { code = null },
            confirmButton = { TextButton(onClick = { code = null }) { Text("Done") } },
            title = { Text("Link a machine") },
            text = {
                Column {
                    Text("On the machine, sign in to WadSpaces and enter this code within 15 minutes:")
                    SelectionContainer {
                        Text(c, Modifier.padding(top = 16.dp), fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
        )
    }
}

@Composable
private fun MachineCard(v: MachineView, ask: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (v.ready) "on" else if (v.machine.online) "busy" else "off")
                Spacer(Modifier.width(10.dp))
                Text(v.machine.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (v.machine.online && v.machine.target != null && !v.ready) {
                    TextButton(onClick = ask) { Text("Ask again") }
                }
            }
            Text(v.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            v.machine.target?.let { t ->
                Text("${t.host} · port ${t.port}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
