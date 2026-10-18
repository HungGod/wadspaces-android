package io.wadspaces.android

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * A wadspace streamed into the app: its sign-in link (from the machine,
 * over the tailnet) in a full-screen WebView, sideways. Back asks whether
 * to stop the stream or leave it running.
 */
class StreamActivity : ComponentActivity() {
    companion object {
        const val URL = "url"
        const val TITLE = "title"
        /** The result: stop the stream. */
        const val STOP = "stop"
    }

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val url = intent.getStringExtra(URL).orEmpty()
        title = intent.getStringExtra(TITLE) ?: "WadSpaces"
        // Only a stream's own https page.
        if (!url.startsWith("https://")) {
            finish()
            return
        }
        CookieManager.getInstance().setAcceptCookie(true)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                // The stream's site stays in here; anything else isn't followed.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.host != android.net.Uri.parse(url).host
            }
            loadUrl(url)
        }
        setContentView(web)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AlertDialog.Builder(this@StreamActivity)
                    .setTitle("Leave the stream?")
                    .setMessage("Stop it, or leave it running to come back to (it stops by itself when nobody's watching).")
                    .setPositiveButton("Stop it") { _, _ -> done(true) }
                    .setNegativeButton("Leave it running") { _, _ -> done(false) }
                    .setNeutralButton("Stay", null)
                    .show()
            }
        })
    }

    private fun done(stop: Boolean) {
        setResult(RESULT_OK, Intent().putExtra(STOP, stop))
        finish()
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
