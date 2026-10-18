package io.wadspaces.android

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
import android.view.ScaleGestureDetector
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * A wadspace streamed into the app: its sign-in link (from the machine,
 * over the tailnet) in a full-screen WebView that turns with the phone as
 * its auto-rotate setting allows. Two small buttons (drag them out of the
 * way): ⋯ has Keyboard, Resolution, Refresh, Back to WadSpaces (the stream
 * keeps running) and Stop streaming, and Back opens it too; 🔍 moves the
 * view instead of the desktop (drag, pinch, double-tap to fit) until it's
 * tapped again.
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

        /** "1500x1000@2.0" back to a screen. */
        fun screenOf(s: String?): Screen? {
            val m = Regex("""^(\d+)x(\d+)@([\d.]+)$""").matchEntire(s ?: return null) ?: return null
            val (w, h, scale) = m.destructured
            return Screen(w.toInt(), h.toInt(), scale.toDoubleOrNull() ?: 1.0).takeIf { it.width > 0 && it.height > 0 }
        }

        fun screenText(s: Screen) = "${s.width}x${s.height}@${s.scale}"

        /**
         * The view's pan and zoom, kept in the page: commands of a zoom
         * about a point and a move (CSS pixels), the stream kept on the
         * screen (centred while it's smaller than it).
         */
        private const val VIEW = """
window.__wadView = window.__wadView || (function () {
  var z = 1, tx = 0, ty = 0;
  function box() {
    var ids = ['videoStream', 'videoWorkerCanvas', 'videoCanvas'];
    for (var i = 0; i < ids.length; i++) {
      var e = document.getElementById(ids[i]);
      if (!e) continue;
      var r = e.getBoundingClientRect();
      if (r.width > 0 && r.height > 0) return { x: (r.left - tx) / z, y: (r.top - ty) / z, w: r.width / z, h: r.height / z };
    }
    return null;
  }
  function keep(t, x0, w, v) {
    return w * z <= v ? (v - w * z) / 2 - x0 * z : Math.min(-x0 * z, Math.max(v - (x0 + w) * z, t));
  }
  return function (c) {
    var b = box(), k = document.querySelector('.video-container');
    if (!b || !k) return z;
    if (c.reset) { z = 1; tx = 0; ty = 0; }
    if (c.f) {
      var n = Math.min(8, Math.max(1, z * c.f));
      tx = c.x - (c.x - tx) * n / z; ty = c.y - (c.y - ty) * n / z; z = n;
    }
    tx += c.dx || 0; ty += c.dy || 0;
    tx = keep(tx, b.x, b.w, innerWidth); ty = keep(ty, b.y, b.h, innerHeight);
    k.style.transformOrigin = '0 0';
    k.style.transform = z === 1 && Math.abs(tx) < 0.5 && Math.abs(ty) < 0.5 ? '' : 'translate(' + tx + 'px,' + ty + 'px) scale(' + z + ')';
    return z;
  };
})();"""
    }

    /** A size for the remote desktop: its pixels and Selkies' DPI (width 0: the phone's own, as it turns). */
    private data class Resolution(val id: String, val label: String, val width: Int = 0, val height: Int = 0, val dpi: Int = 144)

    private lateinit var root: FrameLayout
    private lateinit var note: TextView
    private lateinit var controls: LinearLayout
    private lateinit var zoom: TextView
    /** Over the stream while the view is moved: it takes the touches. */
    private lateinit var adjustLayer: View
    private var web: WebView? = null
    /** The phone's decoder and the view it draws into, under the page (none: the page decodes). */
    private var video: NativeVideo? = null
    private lateinit var videoView: SurfaceView
    /** Where the page shows the stream: x, y, width, height in CSS pixels. */
    private var videoBox: FloatArray? = null
    private val main = Handler(Looper.getMainLooper())
    private var url = ""
    private var retries = 0
    private var reopens = 0
    /** The keyboard's up (its going lets Selkies' input go). */
    private var imeShown = false
    private var screen: Screen? = null
    private lateinit var resolution: Resolution
    private var adjusting = false
    /** A pan and zoom not yet sent to the page (one a frame). */
    private var pendingF = 1f
    private var pendingX = 0f
    private var pendingY = 0f
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var flushing = false

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
        val menu = button("⋯", "Stream menu")
        zoom = button("🔍", "Move the view").apply { textSize = 18f }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(menu, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(zoom, LinearLayout.LayoutParams(dp(44), dp(44)).apply { topMargin = dp(10) })
        }
        adjustLayer = View(this).apply { visibility = View.GONE }
        if (canDecodeNatively()) {
            val v = NativeVideo(::askKeyframe)
            video = v
            videoView = SurfaceView(this).apply { holder.addCallback(v) }
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
            if (r - l != or_ - ol || b - t != ob - ot) main.postDelayed({ view("{}") }, 800)
        }
        dragOrTap(menu) { showMenu() }
        dragOrTap(zoom) { adjust(!adjusting) }
        viewGestures()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = showMenu()
        })
    }

    private fun button(glyph: String, what: String) = TextView(this).apply {
        text = glyph
        textSize = 22f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        alpha = 0.55f
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(170, 20, 13, 34))
        }
        contentDescription = what
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
                    v.alpha = 0.9f
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
                    v.alpha = if (v === zoom && adjusting) 0.95f else 0.55f
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
            if (video != null) {
                // See-through: the phone's video is under it.
                setBackgroundColor(Color.TRANSPARENT)
                WebViewCompat.addWebMessageListener(this, "wadVideo", setOf("https://$host")) { _, message, _, _, _ ->
                    onVideoMessage(message)
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
     * 1.5x); then the view's pan and zoom.
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
            "$decode$size}catch(e){}$VIEW" + (if (video != null) nativeVideoJs else "")
    }

    private val nativeVideoJs by lazy { assets.open("native-video.js").bufferedReader().use { it.readText() } }

    /** The phone's decoder is used when this WebView can hand binary messages to the app. */
    private fun canDecodeNatively() =
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)

    /** The page's messages about its video: chunks (binary, a key flag first), its decoder's set-up, and where it's shown. */
    private fun onVideoMessage(message: WebMessageCompat) {
        val v = video ?: return
        if (message.type == WebMessageCompat.TYPE_ARRAY_BUFFER) {
            val b = message.arrayBuffer
            if (b.size > 1) v.chunk(b[0].toInt() == 1, b.copyOfRange(1, b.size))
            return
        }
        val m = try {
            JSONObject(message.data ?: return)
        } catch (_: Exception) {
            return
        }
        when (m.optString("type")) {
            "configure" -> v.configure(
                m.optInt("width"),
                m.optInt("height"),
                m.optString("description").takeIf { it.isNotEmpty() && it != "null" }?.let { Base64.decode(it, Base64.DEFAULT) },
            )
            "reset" -> v.reset()
            "box" -> {
                videoBox = floatArrayOf(m.optDouble("x").toFloat(), m.optDouble("y").toFloat(), m.optDouble("w").toFloat(), m.optDouble("h").toFloat())
                placeVideo()
            }
        }
    }

    /** The phone's video laid where the page shows the stream. */
    private fun placeVideo() {
        val b = videoBox ?: return
        val d = resources.displayMetrics.density
        val lp = videoView.layoutParams as FrameLayout.LayoutParams
        val w = max(1, (b[2] * d).roundToInt())
        val h = max(1, (b[3] * d).roundToInt())
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            videoView.layoutParams = lp
        }
        videoView.translationX = b[0] * d
        videoView.translationY = b[1] * d
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
        zoom.alpha = if (on) 0.95f else 0.55f
        (zoom.background as GradientDrawable).setColor(if (on) Color.rgb(124, 77, 255) else Color.argb(170, 20, 13, 34))
        if (on) {
            note.tag = "hint"
            say("Drag to move, pinch to zoom, double-tap to fit. Tap 🔍 again to use the desktop.")
            main.postDelayed({ if (note.tag == "hint") note.visibility = View.GONE }, 3_500)
        } else if (note.tag == "hint") {
            note.visibility = View.GONE
        }
    }

    /** While adjusting: one finger or two move the view, a pinch zooms it about its middle, a double tap fits it. */
    @SuppressLint("ClickableViewAccessibility")
    private fun viewGestures() {
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                pendingF *= d.scaleFactor
                pendingX = d.focusX
                pendingY = d.focusY
                flushSoon()
                return true
            }
        })
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                view("{reset:true}")
                return true
            }
        })
        var lastX = 0f
        var lastY = 0f
        adjustLayer.setOnTouchListener { _, e ->
            pinch.onTouchEvent(e)
            taps.onTouchEvent(e)
            // The fingers' middle, leaving out one being lifted.
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
            if (n > 0) {
                x /= n
                y /= n
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    lastX = x
                    lastY = y
                }
                MotionEvent.ACTION_MOVE -> {
                    pendingDx += x - lastX
                    pendingDy += y - lastY
                    lastX = x
                    lastY = y
                    flushSoon()
                }
            }
            true
        }
    }

    /** The pan and zoom so far, to the page on the next frame (screen pixels to CSS pixels). */
    private fun flushSoon() {
        if (flushing) return
        flushing = true
        root.postOnAnimation {
            flushing = false
            val d = resources.displayMetrics.density
            val about = if (pendingF != 1f) "f:$pendingF,x:${pendingX / d},y:${pendingY / d}," else ""
            view("{${about}dx:${pendingDx / d},dy:${pendingDy / d}}")
            pendingF = 1f
            pendingDx = 0f
            pendingDy = 0f
        }
    }

    private fun view(cmd: String) {
        web?.evaluateJavascript("window.__wadView&&window.__wadView($cmd)", null)
    }

    /** The page again, in a new WebView (after its process stopped, or from Refresh). */
    private fun reopen() {
        if (isFinishing) return
        web?.let {
            root.removeView(it)
            it.destroy()
        }
        video?.reset()
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
