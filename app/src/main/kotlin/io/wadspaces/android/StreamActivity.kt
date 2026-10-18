package io.wadspaces.android

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
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
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlin.math.abs

/**
 * A wadspace streamed into the app: its sign-in link (from the machine,
 * over the tailnet) in a full-screen WebView that turns with the phone (the
 * remote desktop takes the new shape). A small button (drag it out of the
 * way) has Keyboard, Refresh, Lock rotation, Back to WadSpaces (the stream
 * keeps running) and Stop streaming; Back opens it too.
 *
 * Selkies decodes in software here: the WebView kept every frame its
 * hardware decoder made (a moto g15: about 4 MB a frame, a gigabyte in
 * half a minute of scrolling, until Android killed the keyboard, Tailscale
 * and the app), and software decoding keeps none.
 *
 * The keyboard doesn't resize the page: Selkies sends the machine a new
 * desktop size on every resize, so each keyboard opening and closing would
 * re-size the remote desktop and its video. The keyboard's space is taken
 * here instead, and the stream slid up just enough to keep the place last
 * tapped above it. A page that comes back 5xx is tried again by itself, and
 * a page whose process stops (the phone short of memory) is opened again
 * rather than taking the app with it.
 */
class StreamActivity : ComponentActivity() {
    companion object {
        const val URL = "url"
        const val TITLE = "title"
        /** The results: stop the stream; back to the app's home. */
        const val STOP = "stop"
        const val HOME = "home"
        private const val RETRIES = 10
        /** A page process that stops more often than this isn't reopened by itself. */
        private const val REOPENS = 2
        /** Selkies' hidden input the phone's keyboard types into. */
        private const val ASSIST = "keyboard-input-assist"
        /**
         * Selkies' own "decode in software" setting (keyed by the page,
         * holding the browser's user agent), set before its script reads it.
         */
        private const val SOFTWARE_DECODE =
            "try{localStorage.setItem((location.origin+location.pathname).replace(/[^a-zA-Z0-9._-]/g,'_')" +
                "+'_prefer_software_decode',navigator.userAgent)}catch(e){}"
    }

    private lateinit var root: FrameLayout
    private lateinit var note: TextView
    private var web: WebView? = null
    private val main = Handler(Looper.getMainLooper())
    private var url = ""
    private var retries = 0
    private var reopens = 0
    /** Where the stream was last touched (the page's own y), and the keyboard's height. */
    private var touchY = 0f
    private var ime = 0
    private var rotationLocked = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        url = intent.getStringExtra(URL).orEmpty()
        title = intent.getStringExtra(TITLE) ?: "WadSpaces"
        // Only a stream's own https page.
        if (!url.startsWith("https://")) {
            finish()
            return
        }
        CookieManager.getInstance().setAcceptCookie(true)
        root = FrameLayout(this)
        note = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
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
        root.addView(makeWeb(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(note, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(menu, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(22), dp(22), 0) })
        setContentView(root)

        // The keyboard: its space taken here (the page never hears of it),
        // and the stream slid up to keep the place tapped above it.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val shown = insets.isVisible(WindowInsetsCompat.Type.ime())
            val h = if (shown) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            if (ime > 0 && h == 0) releaseInput()
            ime = h
            pan()
            WindowInsetsCompat.CONSUMED
        }
        // Turned around: the menu button kept on the screen.
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            menu.translationX = menu.translationX.coerceIn(-menu.left.toFloat(), (root.width - menu.right).toFloat())
            menu.translationY = menu.translationY.coerceIn(-menu.top.toFloat(), (root.height - menu.bottom).toFloat())
        }

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

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun makeWeb(): WebView {
        val host = Uri.parse(url).host
        val w = WebView(this).apply {
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

                // The page's process stopped (killed for memory, or crashed):
                // the app carries on, and the stream opens again.
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (view !== web) return true
                    root.removeView(view)
                    view.destroy()
                    web = null
                    reopens++
                    val why = if (detail.didCrash()) "it crashed" else "the phone ran short of memory"
                    if (reopens > REOPENS) {
                        say("The stream's page stopped again ($why). Close other apps, then Refresh from the menu.")
                    } else {
                        say("The stream's page stopped ($why): opening it again…")
                        main.postDelayed({ reopen() }, 1_500)
                    }
                    return true
                }
            }
            // Where the stream's touched last: the place to keep above the keyboard.
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) touchY = e.y
                false
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, SOFTWARE_DECODE, setOf("https://$host"))
            }
            loadUrl(this@StreamActivity.url)
        }
        web = w
        return w
    }

    /** The page again, in a new WebView (after its process stopped, or from Refresh). */
    private fun reopen() {
        if (isFinishing) return
        web?.let {
            root.removeView(it)
            it.destroy()
        }
        root.addView(makeWeb(), 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        pan()
    }

    /** The stream slid up so the place last tapped stays above the keyboard (no keyboard: back down). */
    private fun pan() {
        val w = web ?: return
        val visible = root.height - ime
        w.translationY = if (ime == 0) 0f else (visible - dp(56) - touchY).coerceIn(-ime.toFloat(), 0f)
    }

    /**
     * The keyboard went (its own close arrow too): Selkies' input let go, so
     * the next tap focuses it again and the keyboard comes back.
     */
    private fun releaseInput() {
        web?.evaluateJavascript(
            "(function(){var e=document.activeElement;if(e&&(e.tagName==='INPUT'||e.tagName==='TEXTAREA'))e.blur();})()",
            null,
        )
    }

    /** The keyboard up, typing into the stream. */
    private fun showKeyboard() {
        val w = web ?: return
        w.evaluateJavascript(
            "(function(){var e=document.getElementById('$ASSIST');if(!e)return false;e.blur();e.focus();return true;})()",
        ) {
            w.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(w, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun showMenu() {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(
                arrayOf(
                    "Keyboard",
                    "Refresh",
                    if (rotationLocked) "Unlock rotation" else "Lock rotation",
                    "Back to WadSpaces (keeps streaming)",
                    "Stop streaming",
                ),
            ) { _, which ->
                when (which) {
                    0 -> showKeyboard()
                    1 -> refresh()
                    2 -> lockRotation(!rotationLocked)
                    3 -> done(stop = false, home = true)
                    4 -> done(stop = true, home = false)
                }
            }
            .setNegativeButton("Stay", null)
            .show()
    }

    /** Held the way it's turned now (lying down), or following the phone again. */
    private fun lockRotation(lock: Boolean) {
        rotationLocked = lock
        requestedOrientation =
            if (lock) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    private fun refresh() {
        retries = 0
        reopens = 0
        note.visibility = View.GONE
        val w = web
        if (w == null) reopen() else w.reload()
    }

    private fun say(text: String) {
        note.text = text
        note.visibility = View.VISIBLE
    }

    /** The page didn't come (the stream not answering for a moment): tried again shortly. */
    private fun notAnswering(why: String) {
        if (retries >= RETRIES) {
            say("The stream isn't answering ($why). Refresh from the menu, or go back.")
            return
        }
        retries++
        note.tag = "retrying"
        say("The stream isn't answering yet ($why): trying again…")
        main.postDelayed({ if (!isFinishing) web?.reload() }, 3_000)
    }

    private fun done(stop: Boolean, home: Boolean) {
        setResult(RESULT_OK, Intent().putExtra(STOP, stop).putExtra(HOME, home))
        finish()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        web?.destroy()
        web = null
        super.onDestroy()
    }
}
