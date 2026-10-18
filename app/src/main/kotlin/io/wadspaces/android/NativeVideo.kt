package io.wadspaces.android

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The stream's video, decoded by the phone's own H.264 decoder (MediaCodec)
 * straight onto a SurfaceView under the page. The page (Selkies) keeps the
 * connection and hands each encoded chunk over; nothing is decoded in the
 * WebView, so no frame is held there (its hardware path kept every one).
 *
 * The decoder owns a fixed set of output buffers and each one is shown as
 * it comes, so memory stays flat. Set up as Moonlight does for low latency:
 * Android's low-latency mode, MediaTek's `vdec-lowlatency`, realtime
 * priority. A backlog, an error or a lost surface starts again from the
 * next keyframe, which [keyframe] asks the machine for.
 */
class NativeVideo(private val keyframe: () -> Unit) : SurfaceHolder.Callback {
    companion object {
        private const val TAG = "NativeVideo"
        private val START = byteArrayOf(0, 0, 0, 1)
        /** Chunks waiting for the decoder beyond this: dropped, and a keyframe asked for. */
        private const val BACKLOG = 8
        /** At most one keyframe request this often. */
        private const val KEYFRAME_EVERY_MS = 500L
    }

    private class Chunk(val key: Boolean, val data: ByteArray)

    private val thread = HandlerThread("video").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var holder: SurfaceHolder? = null
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    /** The stream's size, as the page configured its decoder. */
    private var width = 0
    private var height = 0
    /** NAL length size when the page sends AVCC (it had a description); 0: Annex B. */
    private var lengthSize = 0
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private val inputs = ArrayDeque<Int>()
    private val pending = ArrayDeque<Chunk>()
    private var waitingForKey = true
    private var pts = 0L
    private var askedAt = 0L

    /** The page configured its decoder: the stream's size, and its avcC when it sends AVCC. */
    fun configure(width: Int, height: Int, description: ByteArray?) = handler.post {
        lengthSize = description?.takeIf { it.size > 5 }?.let { (it[4].toInt() and 3) + 1 } ?: 0
        if (width != this.width || height != this.height) {
            Log.i(TAG, "the stream is ${width}x$height")
            this.width = width
            this.height = height
            stop()
            fixSize()
        }
    }

    /** One encoded chunk (an access unit), as the page received it. */
    fun chunk(key: Boolean, data: ByteArray) = handler.post { onChunk(key, data) }

    /** The page's decoder was reset or closed: start again from a keyframe. */
    fun reset() = handler.post {
        if (codec != null) Log.i(TAG, "the page reset its decoder")
        stop()
    }

    fun release() {
        onThread { stop() }
        thread.quitSafely()
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        onThread {
            holder = h
            surface = h.surface
            fixSize()
            waitingForKey = true
            askForKeyframe()
        }
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) {}

    // The decoder lets go of the surface before it's gone.
    override fun surfaceDestroyed(h: SurfaceHolder) {
        onThread {
            stop()
            surface = null
            holder = null
        }
    }

    /** Runs [block] on the decoder's thread and waits for it (a surface's end can't wait for later). */
    private fun onThread(block: () -> Unit) {
        if (Looper.myLooper() == thread.looper) return block()
        val done = CountDownLatch(1)
        handler.post {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        done.await(2, TimeUnit.SECONDS)
    }

    /**
     * The surface's buffers kept at the stream's size, whatever the view's:
     * a zoomed view is scaled by the screen's compositor, not given bigger
     * buffers.
     */
    private fun fixSize() {
        val h = holder ?: return
        val (w, hgt) = width to height
        if (w > 0 && hgt > 0) main.post { h.setFixedSize(w, hgt) }
    }

    private fun onChunk(key: Boolean, raw: ByteArray) {
        val data = if (lengthSize > 0) toAnnexB(raw, lengthSize) else raw
        if (key) {
            // The latest parameter sets start the next decoder; a running one
            // reads them in-band (a new size comes as a new configure).
            val (s, p) = parameterSets(data)
            if (s != null && p != null) {
                sps = s
                pps = p
            }
        }
        if (codec == null) {
            if (!key || surface == null || sps == null) {
                askForKeyframe()
                return
            }
            if (!start()) return
        }
        if (waitingForKey) {
            if (!key) return
            waitingForKey = false
        }
        pending.addLast(Chunk(key, data))
        if (pending.size > BACKLOG) {
            // Behind: what's queued is stale. Catch up at the next keyframe.
            pending.clear()
            waitingForKey = true
            askForKeyframe()
            return
        }
        feed()
    }

    private fun feed() {
        val c = codec ?: return
        while (inputs.isNotEmpty() && pending.isNotEmpty()) {
            val index = inputs.removeFirst()
            val chunk = pending.removeFirst()
            try {
                val buf = c.getInputBuffer(index) ?: continue
                buf.clear()
                buf.put(chunk.data)
                pts += 16_666
                c.queueInputBuffer(index, 0, chunk.data.size, pts, if (chunk.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            } catch (e: Exception) {
                Log.w(TAG, "queueing a chunk", e)
                restart()
                return
            }
        }
    }

    private fun start(): Boolean {
        val out = surface ?: return false
        val s = sps ?: return false
        val p = pps ?: return false
        val w = width.takeIf { it > 0 } ?: 1920
        val h = height.takeIf { it > 0 } ?: 1080
        val plain = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(START + s))
            setByteBuffer("csd-1", ByteBuffer.wrap(START + p))
        }
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(plain)
        // The low-latency settings first; without each, if a decoder refuses them.
        val tries = listOf(
            { f: MediaFormat -> lowLatency(f, vendor = true) },
            { f: MediaFormat -> lowLatency(f, vendor = false) },
            { _: MediaFormat -> },
        )
        for (apply in tries) {
            val c = try {
                if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            } catch (e: Exception) {
                Log.w(TAG, "no H.264 decoder", e)
                return false
            }
            try {
                val f = MediaFormat(plain).also(apply)
                c.setCallback(callbacks, handler)
                c.configure(f, out, null, 0)
                c.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                c.start()
                codec = c
                inputs.clear()
                Log.i(TAG, "${c.name} decoding ${w}x$h")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "${c.name} refused a configuration", e)
                c.release()
            }
        }
        return false
    }

    private fun lowLatency(f: MediaFormat, vendor: Boolean) {
        f.setInteger(MediaFormat.KEY_PRIORITY, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        // MediaTek's decoders take no vendor-named key for it (Moonlight's notes).
        if (vendor) f.setInteger("vdec-lowlatency", 1)
    }

    private val callbacks = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            if (c !== codec) return
            inputs.addLast(index)
            feed()
        }

        // Shown as soon as it's decoded.
        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (c !== codec) return
            try {
                c.releaseOutputBuffer(index, info.size > 0)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "showing a frame", e)
            }
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            if (c !== codec) return
            Log.w(TAG, "decoder error (recoverable ${e.isRecoverable}, transient ${e.isTransient})", e)
            if (!e.isTransient) restart()
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {}
    }

    /** The decoder again, from the next keyframe. */
    private fun restart() {
        stop()
        askForKeyframe()
    }

    private fun stop() {
        codec?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        codec = null
        inputs.clear()
        pending.clear()
        waitingForKey = true
    }

    private fun askForKeyframe() {
        val now = SystemClock.elapsedRealtime()
        if (now - askedAt < KEYFRAME_EVERY_MS) return
        askedAt = now
        keyframe()
    }

    /** The SPS and PPS of an Annex B access unit, without their start codes. */
    private fun parameterSets(au: ByteArray): Pair<ByteArray?, ByteArray?> {
        var s: ByteArray? = null
        var p: ByteArray? = null
        for ((from, to) in nals(au)) {
            when (au[from].toInt() and 0x1F) {
                7 -> s = au.copyOfRange(from, to)
                8 -> p = au.copyOfRange(from, to)
            }
        }
        return s to p
    }

    /** Where each NAL unit of an Annex B buffer starts and ends (start codes left out). */
    private fun nals(b: ByteArray): List<Pair<Int, Int>> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 2 < b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                starts += i + 3
                i += 3
            } else {
                i++
            }
        }
        return starts.mapIndexed { n, from ->
            var to = if (n + 1 < starts.size) starts[n + 1] - 3 else b.size
            // A four-byte start code's leading zero belongs to neither.
            if (n + 1 < starts.size && to > from && b[to - 1].toInt() == 0) to--
            from to to
        }.filter { (from, to) -> to > from }
    }

    /** Length-prefixed (AVCC) NAL units to start-coded (Annex B). */
    private fun toAnnexB(b: ByteArray, size: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size + 16)
        var i = 0
        while (i + size <= b.size) {
            var n = 0
            for (k in 0 until size) n = (n shl 8) or (b[i + k].toInt() and 0xFF)
            i += size
            if (n <= 0 || i + n > b.size) break
            out.write(START)
            out.write(b, i, n)
            i += n
        }
        return out.toByteArray()
    }
}
