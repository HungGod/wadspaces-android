package io.wadspaces.android.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.wadspaces.android.AppModel
import io.wadspaces.android.R
import java.security.SecureRandom
import kotlinx.coroutines.launch

/** Where the sign-in page sends the GitHub token (never really loaded). */
private const val SIGNED_IN_HOST = "signed-in.client.invalid"

/**
 * Signing in: the account's own sign-in page (machine-signin.html, as the
 * WadSpaces Client uses it) in a WebView, GitHub's sign-in in it, and its
 * last address caught before it's loaded: the token, for Firebase.
 */
@Composable
fun SignInScreen(model: AppModel) {
    var web by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    if (!model.g.configured) {
        Centered("This build has no account settings: run scripts/sync-wadspaces.sh and build again.")
        return
    }
    if (web) {
        val nonce = remember { SecureRandom().let { r -> (1..16).map { "%02x".format(r.nextInt(256)) }.joinToString("") } }
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TextButton(onClick = { web = false }, Modifier.padding(4.dp)) { Text("Cancel") }
            SignInWeb(model.g.config.signInPage(nonce, "read:user")) { uri ->
                web = false
                if (uri.getQueryParameter("n") != nonce) {
                    error = "That sign-in wasn't this one: try again."
                    return@SignInWeb
                }
                val token = uri.getQueryParameter("token").orEmpty()
                busy = true
                scope.launch {
                    try {
                        model.signIn(token)
                    } catch (e: Exception) {
                        error = e.message
                    }
                    busy = false
                }
            }
        }
        return
    }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(160.dp))
        Text("WadSpaces", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Make wadspaces, build them on your machines and stream them here, over your tailnet.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        if (busy) {
            CircularProgressIndicator()
        } else {
            Button(onClick = { error = null; web = true }, Modifier.fillMaxWidth()) { Text("Sign in with GitHub") }
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SignInWeb(url: String, done: (Uri) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val u = request.url
                        if (u.host != SIGNED_IN_HOST) return false
                        // Signed in: nothing of GitHub's kept in the WebView.
                        CookieManager.getInstance().removeAllCookies(null)
                        WebStorage.getInstance().deleteAllData()
                        done(u)
                        return true
                    }
                }
                loadUrl(url)
            }
        },
    )
}

@Composable
fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
