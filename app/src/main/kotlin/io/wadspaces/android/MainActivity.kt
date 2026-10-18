package io.wadspaces.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import io.wadspaces.android.ui.BuilderScreen
import io.wadspaces.android.ui.HomeScreen
import io.wadspaces.android.ui.MachinesScreen
import io.wadspaces.android.ui.SignInScreen
import io.wadspaces.android.ui.WadTheme
import io.wadspaces.android.ui.WadspaceScreen

/** Where the app is. */
sealed interface Screen {
    data object Home : Screen
    data object Machines : Screen
    data object Builder : Screen
    /** `machine`: on that one to start with; `build`: built there straight away. */
    data class Wadspace(val id: String, val machine: String? = null, val build: Boolean = false) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val fake = intent.getBooleanExtra("fake", false)
        val start = intent.getStringExtra("screen")
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppModel((if (fake) Fake.graph(this@MainActivity) else null) ?: Graph(this@MainActivity)) as T
        }
        setContent {
            WadTheme {
                val model: AppModel = viewModel(factory = factory)
                App(model, start)
            }
        }
    }
}

@Composable
fun App(model: AppModel, start: String?) {
    val session by model.session.collectAsState()
    if (session == null) {
        Box(Modifier.fillMaxSize()) { SignInScreen(model) }
        return
    }
    var screen by remember {
        mutableStateOf(
            when {
                start == "machines" -> Screen.Machines
                start == "builder" -> Screen.Builder
                start?.startsWith("wadspace:") == true -> Screen.Wadspace(start.removePrefix("wadspace:"))
                else -> Screen.Home
            },
        )
    }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(screen == Screen.Home || screen is Screen.Wadspace, { screen = Screen.Home }, { Icon(Icons.Filled.Home, null) }, label = { Text("Wadspaces") })
                NavigationBarItem(screen == Screen.Builder, { screen = Screen.Builder }, { Icon(Icons.Filled.Add, null) }, label = { Text("Builder") })
                NavigationBarItem(screen == Screen.Machines, { screen = Screen.Machines }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Machines") })
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (val s = screen) {
                Screen.Home -> HomeScreen(model, open = { screen = Screen.Wadspace(it) }, create = { screen = Screen.Builder })
                Screen.Machines -> MachinesScreen(model)
                Screen.Builder -> BuilderScreen(model, saved = { id, machine -> screen = Screen.Wadspace(id, machine, machine != null) })
                is Screen.Wadspace -> WadspaceScreen(model, s.id, s.machine, s.build, back = { screen = Screen.Home })
            }
        }
    }
}
