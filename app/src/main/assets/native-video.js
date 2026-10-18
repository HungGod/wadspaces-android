// The stream page's video goes to the phone's own decoder (the app's
// MediaCodec, drawn under this page): Selkies keeps the connection, input,
// audio and everything else, and its VideoDecoder is this stand-in, which
// hands each encoded chunk to the app over `wadVideo` (an androidx.webkit
// message listener). Selkies still gets a frame per chunk, so its pacing,
// stats and watchdogs carry on: a 2x2 blank the stream's size, shown nowhere.
(function () {
  var bridge = window.wadVideo;
  // Not on the sign-in page (/open): it has no video, and keeps its own background.
  if (!bridge || window.__wadNativeVideo || location.pathname === '/open') return;
  window.__wadNativeVideo = true;

  function send(o) { bridge.postMessage(JSON.stringify(o)); }

  function base64(buf) {
    var b = buf instanceof ArrayBuffer ? new Uint8Array(buf) : new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
    var s = '';
    for (var i = 0; i < b.length; i++) s += String.fromCharCode(b[i]);
    return btoa(s);
  }

  var blank = new Uint8Array(6);

  class NativeVideoDecoder extends EventTarget {
    constructor(init) {
      super();
      this._output = init.output;
      this.state = 'unconfigured';
      this.decodeQueueSize = 0;
      this.ondequeue = null;
      this._w = 2;
      this._h = 2;
    }

    // H.264 only: Selkies then keeps the server on its full-frame H.264.
    static isConfigSupported(config) {
      return Promise.resolve({ supported: /^avc1\./.test(config.codec || ''), config: config });
    }

    configure(config) {
      if (this.state === 'closed') throw new DOMException('The decoder is closed.', 'InvalidStateError');
      this._w = config.codedWidth || 2;
      this._h = config.codedHeight || 2;
      this.state = 'configured';
      send({
        type: 'configure',
        codec: config.codec,
        width: config.codedWidth || 0,
        height: config.codedHeight || 0,
        description: config.description ? base64(config.description) : null,
      });
    }

    decode(chunk) {
      if (this.state !== 'configured') throw new DOMException('The decoder is not configured.', 'InvalidStateError');
      var buf = new ArrayBuffer(chunk.byteLength + 1);
      var bytes = new Uint8Array(buf);
      bytes[0] = chunk.type === 'key' ? 1 : 0;
      chunk.copyTo(bytes.subarray(1));
      bridge.postMessage(buf);
      var self = this, ts = chunk.timestamp;
      Promise.resolve().then(function () {
        if (self.state !== 'configured') return;
        self._output(new VideoFrame(blank, {
          format: 'I420', codedWidth: 2, codedHeight: 2,
          displayWidth: self._w, displayHeight: self._h, timestamp: ts,
        }));
      });
    }

    flush() { return Promise.resolve(); }

    reset() {
      if (this.state === 'closed') throw new DOMException('The decoder is closed.', 'InvalidStateError');
      this.state = 'unconfigured';
      send({ type: 'reset' });
    }

    close() {
      this.state = 'closed';
      send({ type: 'reset' });
    }
  }
  window.VideoDecoder = NativeVideoDecoder;

  // Selkies' video elements stay (they place the stream, and taps are
  // mapped from them) but show nothing: the app's video is under the page.
  function hide() {
    var style = document.createElement('style');
    style.textContent =
      'html, body, #app, #root, .video-container { background: transparent !important; }' +
      '#videoStream, #videoCanvas, #videoWorkerCanvas { opacity: 0 !important; }';
    (document.head || document.documentElement).appendChild(style);
  }
  if (document.documentElement) hide(); else document.addEventListener('DOMContentLoaded', hide);

  // Where the stream is on the page (zoomed and moved too), to the app on
  // every change: its video is laid exactly there.
  var last = '';
  function box() {
    var ids = ['videoStream', 'videoWorkerCanvas', 'videoCanvas'];
    for (var i = 0; i < ids.length; i++) {
      var e = document.getElementById(ids[i]);
      if (!e) continue;
      var r = e.getBoundingClientRect();
      if (r.width > 0 && r.height > 0) {
        var at = [r.left, r.top, r.width, r.height].map(function (v) { return Math.round(v * 2) / 2; });
        var key = at.join(',');
        if (key !== last) {
          last = key;
          send({ type: 'box', x: at[0], y: at[1], w: at[2], h: at[3] });
        }
        break;
      }
    }
    requestAnimationFrame(box);
  }
  requestAnimationFrame(box);
})();
