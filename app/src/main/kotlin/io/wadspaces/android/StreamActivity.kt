package io.wadspaces.android

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs

/**
 * A wadspace streamed into the app: its sign-in link (from the machine,
 * over the tailnet) in a full-screen WebView, sideways. A small button
 * (drag it out of the way) has Refresh, Back to WadSpaces (the stream keeps
 * running) and Stop streaming; Back asks the same. A page that comes back
 * 5xx (the stream not answering for a moment) is tried again by itself.
 */
class StreamActivity : ComponentActivity() {
    companion object {
        const val URL = "url"
        const val TITLE = "title"
        /** The results: stop the stream; back to the app's home. */
        const val STOP = "stop"
        const val HOME = "home"
        private const val RETRIES = 10
    }

    private lateinit var web: WebView
    private lateinit var note: TextView
    private val main = Handler(Looper.getMainLooper())
    private var retries = 0

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
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
        val host = Uri.parse(url).host
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
                    request.url.host != host

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame && response.statusCode >= 500) notAnswering("${response.statusCode}")
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) notAnswering(error.description?.toString() ?: "no answer")
                }

                override fun onPageFinished(view: WebView, url: String) {
                    // Loaded fine: no note, and a fresh count of tries.
                    if (note.tag != "retrying") {
                        note.visibility = View.GONE
                        retries = 0
                    }
                    note.tag = null
                }
            }
            loadUrl(url)
        }
        note = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(32, 20, 32, 20)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.argb(200, 20, 13, 34))
            }
            visibility = View.GONE
        }
        val menu = TextView(this).apply {
            text = "⋯"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            alpha = 0.55f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(170, 20, 13, 34))
            }
            contentDescription = "Stream menu"
        }
        val size = (44 * resources.displayMetrics.density).toInt()
        val root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(note, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(menu, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply { setMargins(0, size / 2, size / 2, 0) })
        setContentView(root)

        // The menu button: a tap opens it; a drag moves it out of the way.
        var downX = 0f
        var downY = 0f
        var dragged = false
        menu.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX - v.translationX
                    downY = e.rawY - v.translationY
                    dragged = false
                    v.alpha = 0.9f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dragged || abs(dx - v.translationX) > 12 || abs(dy - v.translationY) > 12) {
                        dragged = true
                        v.translationX = dx
                        v.translationY = dy
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 0.55f
                    if (!dragged && e.actionMasked == MotionEvent.ACTION_UP) showMenu()
                }
            }
            true
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = showMenu()
        })
    }

    private fun showMenu() {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(arrayOf("Refresh", "Back to WadSpaces (keeps streaming)", "Stop streaming")) { _, which ->
                when (which) {
                    0 -> refresh()
                    1 -> done(stop = false, home = true)
                    2 -> done(stop = true, home = false)
                }
            }
            .setNegativeButton("Stay", null)
            .show()
    }

    private fun refresh() {
        retries = 0
        note.visibility = View.GONE
        web.reload()
    }

    /** The page didn't come (the stream not answering for a moment): tried again shortly. */
    private fun notAnswering(why: String) {
        if (retries >= RETRIES) {
            note.text = "The stream isn't answering ($why). Refresh from the menu, or go back."
            note.visibility = View.VISIBLE
            return
        }
        retries++
        note.text = "The stream isn't answering yet ($why): trying again…"
        note.tag = "retrying"
        note.visibility = View.VISIBLE
        main.postDelayed({ if (!isFinishing) web.reload() }, 3_000)
    }

    private fun done(stop: Boolean, home: Boolean) {
        setResult(RESULT_OK, Intent().putExtra(STOP, stop).putExtra(HOME, home))
        finish()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
