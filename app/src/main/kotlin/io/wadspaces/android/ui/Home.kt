package io.wadspaces.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.wadspaces.android.AppModel
import io.wadspaces.android.LibState
import io.wadspaces.core.board.Layers
import io.wadspaces.core.get
import io.wadspaces.core.library.Library
import io.wadspaces.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** What a wadspace is made of, in a line: its template's apps, and its Extensions. */
fun madeOf(w: JsonObject, lib: Library, model: AppModel): String {
    val t = Layers.chainOf(lib.lib(), w.str("template")).lastOrNull()
    val apps = (t?.get("apps") as? JsonArray)?.map { it.toString().trim('"') } ?: emptyList()
    val e = lib.allExtensions.firstOrNull { it.str("id") == w.str("extensions") }
    val parts = listOfNotNull(
        t?.let { "${it.str("name")}: ${model.g.catalog.appNames(apps)}" } ?: "No template yet",
        e?.str("name"),
    )
    return parts.joinToString(" · ")
}

@Composable
fun HomeScreen(model: AppModel, open: (String) -> Unit, create: () -> Unit) {
    val state by model.library.collectAsState()
    when (val s = state) {
        LibState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is LibState.Failed -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.why, color = MaterialTheme.colorScheme.error)
            Button(onClick = model::refresh, Modifier.padding(top = 16.dp)) { Text("Try again") }
        }
        is LibState.Ready -> {
            val list = s.lib.live()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                item { PageHead("Your wadspaces", "Build one on a machine, open it there, or stream it here.") }
                if (list.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No wadspaces yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = create, Modifier.padding(top = 16.dp)) { Text("Make one") }
                        }
                    }
                }
                items(list, key = { it.str("id") }) { w ->
                    WadspaceCard(w, s.lib, model) { open(w.str("id")) }
                }
            }
        }
    }
}

@Composable
fun WadspaceCard(w: JsonObject, lib: Library, model: AppModel, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Wallpaper(w["customization"]["wallpaper"], model.g.catalog, Modifier.fillMaxWidth().height(120.dp).padding(8.dp))
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
            Text(w["customization"].str("name").ifEmpty { "Untitled" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                madeOf(w, lib, model),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
