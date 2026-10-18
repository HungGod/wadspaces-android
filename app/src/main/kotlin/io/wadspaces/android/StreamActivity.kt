package io.wadspaces.android

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.util.Base64
import android.view.SurfaceView
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.wadspaces.core.remote.Screen
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * A wadspace streamed into the app: its sign-in link (from the machine,
 * over the tailnet) in a full-screen WebView that turns with the phone as
 * its auto-rotate setting allows. Two small buttons in WadSpaces' colours
 * (drag them out of the way): the dots have Keyboard, Resolution, Refresh,
 * Back to WadSpaces (the stream keeps running) and Stop streaming, and Back
 * opens them too; the hand (lime while it's on, as Photoshop's hand tool)
 * moves the view instead of the desktop (drag, pinch, double-tap to fit)
 * until it's tapped again. Selkies' own keyboard button is given the same
 * colours.
 *
 * The remote desktop keeps a size of its own, not the phone's: by default
 * the machine's screen, its pixels and its scaling, so its windows are laid
 * out as they are there. The stream is fitted to the phone, and zoomed in
 * the page itself (its container scaled), so a closer look is drawn from the
 * stream's own pixels and taps land where they look to.
 *
 * The video isn't decoded in the WebView: its hardware path kept every frame
 * (a moto g15: about 4 MB a frame, a gigabyte in half a minute of scrolling,
 * until Android killed the keyboard, Tailscale and the app). The page hands
 * each H.264 chunk to the phone's own decoder ([NativeVideo]), which draws
 * it on a SurfaceView under the see-through page, laid where the page shows
 * the stream. A WebView that can't hand chunks over decodes on the page, in
 * software.
 *
 * The keyboard goes over the stream: the page is neither resized nor moved
 * (a resize would also give the remote desktop a new size). A page that
 * comes back 5xx is tried again by itself, and a page whose process stops
 * (the phone short of memory) is opened again rather than taking the app
 * with it.
 */
class StreamActivity : ComponentActivity() {
    companion object {
        const val URL = "url"
        const val TITLE = "title"
        /** The machine streaming it: its id (the resolution picked is kept per machine) and name. */
        const val MACHINE = "machine"
        const val MACHINE_NAME = "machineName"
        /** Its screen, "<width>x<height>@<scale>" (the logical size, as its heartbeat has it). */
        const val SCREEN = "screen"
        /** The results: stop the stream; back to the app's home. */
        const val STOP = "stop"
        const val HOME = "home"
        private const val RETRIES = 10
        /** A page process that stops more often than this isn't reopened by itself. */
        private const val REOPENS = 2
        /** Selkies' hidden input the phone's keyboard types into. */
        private const val ASSIST = "keyboard-input-assist"

        /** WadSpaces' colours (the app's dark theme, ui/Theme.kt), and a resting button's opacity. */
        private val FG = 0xFFF5F2FA.toInt()
        private val SURFACE = 0xD91B132D.toInt()
        private val OUTLINE = 0x2EF0EAFA
        private val ACCENT = 0xFFC6FF1F.toInt()
        private val ON_ACCENT = 0xFF0A0614.toInt()
        private const val IDLE = 0.85f
        /** Around the stream: WadSpaces' background and edge, dark or light as the phone is. */
        private val BG_DARK = 0xFF0A0614.toInt()
        private val BG_LIGHT = 0xFFF7F5FB.toInt()
        private val EDGE_DARK = 0x2EF0EAFA
        private val EDGE_LIGHT = 0x2E0A0614

        /**
         * Selkies' keyboard button in the same colours as the app's: round,
         * a night surface, a faint edge and an off-white keyboard (Material
         * Icons' keyboard, Apache License 2.0). It keeps its place and use.
         */
        private val KEYBOARD_BUTTON = """
            .virtual-keyboard-button {
              width: 44px !important; height: 44px !important; border-radius: 50% !important;
              border: 1px solid rgba(240, 234, 250, 0.18) !important; box-shadow: none !important; opacity: 0.85;
              background: rgba(27, 19, 45, 0.85) url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath fill='%23F5F2FA' d='M20,5H4c-1.1,0 -1.99,0.9 -1.99,2L2,17c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zM11,8h2v2h-2V8zM11,11h2v2h-2v-2zM8,8h2v2H8V8zM8,11h2v2H8v-2zM7,13H5v-2h2v2zM7,10H5V8h2v2zM16,17H8v-2h8v2zM16,13h-2v-2h2v2zM16,10h-2V8h2v2zM19,13h-2v-2h2v2zM19,10h-2V8h2v2z'/%3E%3C/svg%3E") center / 24px 24px no-repeat !important;
            }
            .virtual-keyboard-button:active { opacity: 1; }
            .virtual-keyboard-button svg { visibility: hidden !important; }
        """.trimIndent()

        /** A stylesheet added to the page as it starts. */
        private fun styleJs(css: String) =
            "(function(){var s=document.createElement('style');s.textContent=${JSONObject.quote(css)};" +
                "var a=function(){(document.head||document.documentElement).appendChild(s)};" +
                "document.documentElement?a():document.addEventListener('DOMContentLoaded',a)})();"

        /** "1500x1000@2.0" back to a screen. */
        fun screenOf(s: String?): Screen? {
            val m = Regex("""^(\d+)x(\d+)@([\d.]+)$""").matchEntire(s ?: return null) ?: return null
            val (w, h, scale) = m.destructured
            return Screen(w.toInt(), h.toInt(), scale.toDoubleOrNull() ?: 1.0).takeIf { it.width > 0 && it.height > 0 }
        }

        fun screenText(s: Screen) = "${s.width}x${s.height}@${s.scale}"

        /**
         * The view's pan and zoom in the page, as the app sets it: the
         * stream's container scaled and moved (CSS pixels), so taps land
         * where they look to. Where the stream sits unzoomed goes to the app
         * on every change (over `wadPage`), for it to lay its video and keep
         * the view on the stream.
         */
        private const val VIEW = """
window.__wadView = window.__wadView || (function () {
  var z = 1, tx = 0, ty = 0, last = '';
  function report() {
    var bridge = window.wadPage, ids = ['videoStream', 'videoWorkerCanvas', 'videoCanvas'];
    for (var i = 0; bridge && i < ids.length; i++) {
      var e = document.getElementById(ids[i]);
      if (!e) continue;
      var r = e.getBoundingClientRect();
      if (r.width > 0 && r.height > 0) {
        var at = [(r.left - tx) / z, (r.top - ty) / z, r.width / z, r.height / z].map(function (v) { return Math.round(v * 2) / 2; });
        if (at.join(',') !== last) {
          last = at.join(',');
          bridge.postMessage(JSON.stringify({ type: 'box', x: at[0], y: at[1], w: at[2], h: at[3] }));
        }
        break;
      }
    }
    requestAnimationFrame(report);
  }
  requestAnimationFrame(report);
  return function (nz, nx, ny) {
    z = nz; tx = nx; ty = ny;
    var k = document.querySelector('.video-container');
    if (!k) return;
    k.style.transformOrigin = '0 0';
    k.style.transform = z === 1 && Math.abs(tx) < 0.5 && Math.abs(ty) < 0.5 ? '' : 'translate(' + tx + 'px,' + ty + 'px) scale(' + z + ')';
  };
})();"""

        /** The view's zoom, out and in. */
        private const val MIN_ZOOM = 0.25f
        private const val MAX_ZOOM = 8f
        /** How much of the stream (CSS pixels, or half of it) always stays in sight. */
        private const val KEEP = 64f
    }

    /** A size for the remote desktop: its pixels and Selkies' DPI (width 0: the phone's own, as it turns). */
    private data class Resolution(val id: String, val label: String, val width: Int = 0, val height: Int = 0, val dpi: Int = 144)

    private lateinit var root: FrameLayout
    private lateinit var note: TextView
    private lateinit var controls: LinearLayout
    private lateinit var zoom: ImageView
    /** Over the stream while the view is moved: it takes the touches. */
    private lateinit var adjustLayer: View
    private var web: WebView? = null
    /** The phone's decoder and the view it draws into, under the page (none: the page decodes). */
    private var video: NativeVideo? = null
    private lateinit var videoView: SurfaceView
    /** The stream's edge on the background, a pixel outside it. */
    private lateinit var videoEdge: Edge
    /** Where the page shows the stream unzoomed: x, y, width, height in CSS pixels. */
    private var baseBox: FloatArray? = null
    /** The view's zoom and its move (CSS pixels), as the page's container has them. */
    private var viewZ = 1f
    private var viewX = 0f
    private var viewY = 0f
    private var syncing = false
    private val main = Handler(Looper.getMainLooper())
    private var url = ""
    private var retries = 0
    private var reopens = 0
    /** The keyboard's up (its going lets Selkies' input go). */
    private var imeShown = false
    private var screen: Screen? = null
    private lateinit var resolution: Resolution
    private var adjusting = false

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
        screen = screenOf(intent.getStringExtra(SCREEN))
        val kept = getSharedPreferences("stream", MODE_PRIVATE).getString(resolutionKey(), null)
        resolution = resolutions().let { all -> all.firstOrNull { it.id == kept } ?: all.first() }
        CookieManager.getInstance().setAcceptCookie(true)
        root = FrameLayout(this).apply { setBackgroundColor(pasteboard()) }
        note = TextView(this).apply {
            setTextColor(FG)
            textSize = 14f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(SURFACE)
                setStroke(dp(1), OUTLINE)
            }
            visibility = View.GONE
        }
        val menu = button(R.drawable.ic_stream_more, "Stream menu")
        zoom = button(R.drawable.ic_stream_hand, "Move the view")
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(menu, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(zoom, LinearLayout.LayoutParams(dp(44), dp(44)).apply { topMargin = dp(10) })
        }
        adjustLayer = View(this).apply { visibility = View.GONE }
        if (canDecodeNatively()) {
            val v = NativeVideo(::askKeyframe)
            video = v
            videoEdge = Edge().apply { color(edge()) }
            videoView = SurfaceView(this).apply { holder.addCallback(v) }
            root.addView(videoEdge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            root.addView(videoView, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))
        }
        root.addView(makeWeb(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(adjustLayer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(note, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(controls, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(22), dp(22), 0) })
        setContentView(root)

        // The keyboard goes over the stream: its insets stop here, so the
        // page is neither resized nor moved.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val shown = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeShown && !shown) releaseInput()
            imeShown = shown
            WindowInsetsCompat.CONSUMED
        }
        // Turned around: the buttons kept on the screen, and the view kept
        // on the stream once Selkies has fitted it to the new shape.
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            controls.translationX = controls.translationX.coerceIn(-controls.left.toFloat(), (root.width - controls.right).toFloat())
            controls.translationY = controls.translationY.coerceIn(-controls.top.toFloat(), (root.height - controls.bottom).toFloat())
            if (r - l != or_ - ol || b - t != ob - ot) main.post { setView(viewZ, viewX, viewY) }
        }
        dragOrTap(menu) { showMenu() }
        dragOrTap(zoom) { adjust(!adjusting) }
        viewGestures()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = showMenu()
        })
    }

    /** A round button in WadSpaces' colours: a night surface, a faint edge, an off-white icon. */
    private fun button(icon: Int, what: String) = ImageView(this).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        paint(this, on = false)
        alpha = IDLE
        contentDescription = what
    }

    /** A button's colours: lime with a night icon while it's on. */
    private fun paint(v: ImageView, on: Boolean) {
        (v.background as GradientDrawable).apply {
            setColor(if (on) ACCENT else SURFACE)
            setStroke(dp(1), if (on) ACCENT else OUTLINE)
        }
        v.imageTintList = ColorStateList.valueOf(if (on) ON_ACCENT else FG)
    }

    /** A tap on a button does its thing; a drag moves both buttons out of the way. */
    @SuppressLint("ClickableViewAccessibility")
    private fun dragOrTap(v: View, tap: () -> Unit) {
        var downX = 0f
        var downY = 0f
        var dragged = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX - controls.translationX
                    downY = e.rawY - controls.translationY
                    dragged = false
                    v.alpha = 1f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dragged || abs(dx - controls.translationX) > 12 || abs(dy - controls.translationY) > 12) {
                        dragged = true
                        controls.translationX = dx
                        controls.translationY = dy
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = if (v === zoom && adjusting) 1f else IDLE
                    if (!dragged && e.actionMasked == MotionEvent.ACTION_UP) tap()
                }
            }
            true
        }
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
            // See-through: the phone's video is under it.
            if (video != null) setBackgroundColor(Color.TRANSPARENT)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(this, "wadPage", setOf("https://$host")) { _, message, _, _, _ ->
                    onPageMessage(message)
                }
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, pageStart(resolution), setOf("https://$host"))
            }
            loadUrl(this@StreamActivity.url)
        }
        web = w
        return w
    }

    /**
     * Selkies' own settings, written before its script reads them (keyed by
     * the page): its video to the phone's decoder (full-frame H.264, decoded
     * on the page, where the stand-in decoder takes it), or, where this
     * WebView can't hand chunks over, decoded in software on the page; and
     * the desktop's size and DPI (none: the phone's size as it turns, at
     * 1.5x); then the view's pan and zoom, and Selkies' keyboard button in
     * WadSpaces' colours.
     */
    private fun pageStart(r: Resolution): String {
        val decode = if (video != null) {
            "s('offscreen_worker','false');s('encoder','h264enc');rm('prefer_software_decode');"
        } else {
            "s('prefer_software_decode',navigator.userAgent);"
        }
        val size = if (r.width == 0) {
            "rm('manual_resolution');rm('manual_width');rm('manual_height');s('scaling_dpi','${r.dpi}');"
        } else {
            "s('manual_resolution','true');s('manual_width','${r.width}');s('manual_height','${r.height}');" +
                "s('scaling_dpi','${r.dpi}');s('scaleLocallyManual','true');"
        }
        return "try{var p=(location.origin+location.pathname).replace(/[^a-zA-Z0-9._-]/g,'_')+'_'," +
            "s=function(k,v){localStorage.setItem(p+k,v)},rm=function(k){localStorage.removeItem(p+k)};" +
            "$decode$size}catch(e){}$VIEW" + styleJs(KEYBOARD_BUTTON) + (if (video != null) nativeVideoJs else styleJs(pageBackground()))
    }

    private val nativeVideoJs by lazy { assets.open("native-video.js").bufferedReader().use { it.readText() } }

    /** A page that decodes itself: WadSpaces' background around its stream, and the stream's edge. */
    private fun pageBackground(): String {
        val bg = "#%06X".format(pasteboard() and 0xFFFFFF)
        val e = edge()
        val edge = "rgba(${(e shr 16) and 0xFF}, ${(e shr 8) and 0xFF}, ${e and 0xFF}, ${String.format(Locale.ROOT, "%.2f", ((e ushr 24) and 0xFF) / 255f)})"
        return "html, body, #app, #root, .video-container { background: $bg !important; }" +
            "#videoStream, #videoCanvas, #videoWorkerCanvas { box-shadow: 0 0 0 1px $edge; }"
    }

    /** The phone's decoder is used when this WebView can hand binary messages to the app. */
    private fun canDecodeNatively() =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)

    /**
     * The page's messages: video chunks (binary, a key flag first) and its
     * decoder's set-up, for the phone's decoder; and where the stream sits
     * unzoomed, for the view.
     */
    private fun onPageMessage(message: WebMessageCompat) {
        val v = video
        if (message.type == WebMessageCompat.TYPE_ARRAY_BUFFER) {
            val b = message.arrayBuffer
            if (v != null && b.size > 1) v.chunk(b[0].toInt() == 1, b.copyOfRange(1, b.size))
            return
        }
        val m = try {
            JSONObject(message.data ?: return)
        } catch (_: Exception) {
            return
        }
        when (m.optString("type")) {
            "configure" -> v?.configure(
                m.optInt("width"),
                m.optInt("height"),
                m.optString("description").takeIf { it.isNotEmpty() && it != "null" }?.let { Base64.decode(it, Base64.DEFAULT) },
            )
            "reset" -> v?.reset()
            "box" -> {
                baseBox = floatArrayOf(m.optDouble("x").toFloat(), m.optDouble("y").toFloat(), m.optDouble("w").toFloat(), m.optDouble("h").toFloat())
                setView(viewZ, viewX, viewY)
            }
        }
    }

    /**
     * The phone's video laid where the page shows the stream: sized to it
     * unzoomed (a change of layout only when that changes), then scaled and
     * moved by the view, which the screen's compositor does as it draws.
     */
    private fun placeVideo() {
        if (video == null) return
        val b = baseBox ?: return
        val d = resources.displayMetrics.density
        val lp = videoView.layoutParams as FrameLayout.LayoutParams
        val w = max(1, (b[2] * d).roundToInt())
        val h = max(1, (b[3] * d).roundToInt())
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            videoView.layoutParams = lp
        }
        val left = (viewX + b[0] * viewZ) * d
        val top = (viewY + b[1] * viewZ) * d
        videoView.pivotX = 0f
        videoView.pivotY = 0f
        videoView.scaleX = viewZ
        videoView.scaleY = viewZ
        videoView.translationX = left
        videoView.translationY = top
        videoEdge.around(left, top, w * viewZ, h * viewZ)
    }

    /** The stream's edge, drawn a pixel outside it on the background (no layout as the view moves). */
    private inner class Edge : View(this@StreamActivity) {
        private val paint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
        }
        private val box = android.graphics.RectF()

        fun around(left: Float, top: Float, width: Float, height: Float) {
            val e = paint.strokeWidth / 2
            box.set(left - e, top - e, left + width + e, top + height + e)
            invalidate()
        }

        fun color(c: Int) {
            paint.color = c
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            if (!box.isEmpty) canvas.drawRect(box, paint)
        }
    }

    private fun night() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** What's around the stream: WadSpaces' background, dark or light as the phone is. */
    private fun pasteboard() = if (night()) BG_DARK else BG_LIGHT

    private fun edge() = if (night()) EDGE_DARK else EDGE_LIGHT

    // The phone went dark or light: the background with it (the page's own
    // colours, when it decodes, follow at its next load).
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        root.setBackgroundColor(pasteboard())
        if (video != null) videoEdge.color(edge())
    }

    /** A keyframe from the machine (the phone's decoder starts or catches up on one). */
    private fun askKeyframe() = main.post {
        web?.evaluateJavascript(
            "window.selkiesTransport&&window.selkiesTransport.readyState===1&&window.selkiesTransport.send('REQUEST_KEYFRAME')",
            null,
        )
    }

    private fun resolutionKey() = "resolution:" + (intent.getStringExtra(MACHINE) ?: "")

    /** The sizes offered: the machine's own first (and half of it, when it's scaled up), then the usual ones. */
    private fun resolutions(): List<Resolution> {
        val name = intent.getStringExtra(MACHINE_NAME) ?: "the machine"
        val list = mutableListOf<Resolution>()
        screen?.let { s ->
            val (w, h) = even(s.pixelWidth, s.pixelHeight)
            list += Resolution("machine", "Same as $name: $w × $h", w, h, dpi(s.scale))
            if (s.scale >= 1.5) {
                val (hw, hh) = even(s.pixelWidth / 2, s.pixelHeight / 2)
                list += Resolution("half", "$name's layout, half the pixels: $hw × $hh (lighter)", hw, hh, dpi(s.scale / 2))
            }
        }
        list += Resolution("1080p", "1920 × 1080", 1920, 1080, 96)
        list += Resolution("720p", "1280 × 720", 1280, 720, 96)
        list += Resolution("phone", "Fit this phone's screen")
        return list
    }

    /** Selkies' sizes: even, at most 4080 a side (the shape kept). */
    private fun even(w: Int, h: Int): Pair<Int, Int> {
        val k = min(1.0, 4080.0 / max(w, h))
        return ((w * k).toInt() and 1.inv()) to ((h * k).toInt() and 1.inv())
    }

    /** Selkies' DPI steps: 96 to 288, by 24 (96 is 1x). */
    private fun dpi(scale: Double) = (Math.round(scale * 4) * 24).toInt().coerceIn(96, 288)

    private fun pickResolution() {
        val all = resolutions()
        AlertDialog.Builder(this)
            .setTitle("Resolution")
            .setSingleChoiceItems(
                all.map { r -> r.label.replaceFirstChar { it.uppercase() } }.toTypedArray(),
                all.indexOfFirst { it.id == resolution.id },
            ) { d, which ->
                d.dismiss()
                val r = all[which]
                if (r == resolution) return@setSingleChoiceItems
                resolution = r
                getSharedPreferences("stream", MODE_PRIVATE).edit().putString(resolutionKey(), r.id).apply()
                // Selkies takes its size as it starts: the page again, with the new one.
                reopens = 0
                reopen()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The view moved instead of the desktop, or the desktop back under the finger. */
    private fun adjust(on: Boolean) {
        adjusting = on
        adjustLayer.visibility = if (on) View.VISIBLE else View.GONE
        zoom.alpha = if (on) 1f else IDLE
        paint(zoom, on)
        if (on) {
            note.tag = "hint"
            say("Drag to move, pinch to zoom, double-tap to fit. Tap the hand again to use the desktop.")
            main.postDelayed({ if (note.tag == "hint") note.visibility = View.GONE }, 3_500)
        } else if (note.tag == "hint") {
            note.visibility = View.GONE
        }
    }

    /**
     * While adjusting: the fingers hold the stream where they touch it, so
     * one moves it and two pinch it (the point between them stays under
     * them, out to the last bit of the pinch); a double tap fits it.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun viewGestures() {
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                setView(1f, 0f, 0f)
                return true
            }
        })
        var lastX = 0f
        var lastY = 0f
        var lastSpan = 0f
        adjustLayer.setOnTouchListener { _, e ->
            taps.onTouchEvent(e)
            // The fingers' middle and spread, leaving out one being lifted.
            val lifted = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
            var x = 0f
            var y = 0f
            var n = 0
            for (i in 0 until e.pointerCount) {
                if (i == lifted) continue
                x += e.getX(i)
                y += e.getY(i)
                n++
            }
            if (n == 0) return@setOnTouchListener true
            x /= n
            y /= n
            var span = 0f
            if (n >= 2) {
                for (i in 0 until e.pointerCount) {
                    if (i == lifted) continue
                    span += kotlin.math.hypot(e.getX(i) - x, e.getY(i) - y)
                }
                span /= n
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    lastX = x
                    lastY = y
                    lastSpan = span
                }
                MotionEvent.ACTION_MOVE -> {
                    val d = resources.displayMetrics.density
                    val f = if (span > 0f && lastSpan > 0f) span / lastSpan else 1f
                    val z = (viewZ * f).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    val k = z / viewZ
                    // The stream's point under the fingers' old middle, under their new middle.
                    setView(z, x / d - (lastX / d - viewX) * k, y / d - (lastY / d - viewY) * k)
                    lastX = x
                    lastY = y
                    lastSpan = span
                }
            }
            true
        }
    }

    /**
     * The view zoomed and moved (CSS pixels), a corner of the stream (64 px,
     * or half of it) kept in sight: the phone's video at once, the page's
     * container on the next frame.
     */
    private fun setView(z: Float, x: Float, y: Float) {
        viewZ = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        viewX = x
        viewY = y
        baseBox?.let { b ->
            val d = resources.displayMetrics.density
            val keepX = min(KEEP, b[2] * viewZ / 2)
            val keepY = min(KEEP, b[3] * viewZ / 2)
            viewX = viewX.coerceIn(keepX - (b[0] + b[2]) * viewZ, root.width / d - keepX - b[0] * viewZ)
            viewY = viewY.coerceIn(keepY - (b[1] + b[3]) * viewZ, root.height / d - keepY - b[1] * viewZ)
        }
        placeVideo()
        if (syncing) return
        syncing = true
        root.postOnAnimation {
            syncing = false
            web?.evaluateJavascript("window.__wadView&&window.__wadView($viewZ,$viewX,$viewY)", null)
        }
    }

    /** The page again, in a new WebView (after its process stopped, or from Refresh). */
    private fun reopen() {
        if (isFinishing) return
        web?.let {
            root.removeView(it)
            it.destroy()
        }
        video?.reset()
        // A new page starts unzoomed.
        viewZ = 1f
        viewX = 0f
        viewY = 0f
        baseBox = null
        // Above the phone's video, if there is one.
        val at = if (video != null) root.indexOfChild(videoView) + 1 else 0
        root.addView(makeWeb(), at, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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
            .setItems(arrayOf("Keyboard", "Resolution…", "Refresh", "Back to WadSpaces (keeps streaming)", "Stop streaming")) { _, which ->
                when (which) {
                    0 -> showKeyboard()
                    1 -> pickResolution()
                    2 -> refresh()
                    3 -> done(stop = false, home = true)
                    4 -> done(stop = true, home = false)
                }
            }
            .setNegativeButton("Stay", null)
            .show()
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
        video?.release()
        super.onDestroy()
    }
}
