package com.espiritu.tv

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * A muted trailer behind the Home screen, played through YouTube's embedded player in a WebView that exists
 * only while a trailer is wanted (a WebView costs real memory on a TV, so it's released when idle or in the
 * background). The player is oversized by 12% so YouTube's own title bar and controls fall off the screen.
 */
class TrailerView(ctx: Context) : FrameLayout(ctx) {

    interface Listener {
        fun onTrailerPlaying()
        fun onTrailerEnded()
    }

    var listener: Listener? = null
    var playing = false; private set
    private var web: WebView? = null
    private var current: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val timeout = Runnable { if (!playing) { stop(); listener?.onTrailerEnded() } }

    init {
        alpha = 0f
        visibility = View.GONE
        setBackgroundColor(0)
    }

    /**
     * Starts loading [videoId] (a YouTube id), hidden. [Listener.onTrailerPlaying] fires once frames are moving.
     * [cover] oversizes the player so a letterboxed or pillarboxed picture still fills the screen (see Trailers.coverScale).
     */
    fun play(videoId: String, cover: Float = 1f) {
        val w = web ?: create().also { web = it }
        current = videoId
        playing = false
        alpha = 0f
        visibility = View.VISIBLE
        w.loadDataWithBaseURL("https://espiritu.tv/", html(videoId, cover), "text/html", "utf-8", null)
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 15_000)
    }

    /** Fades the playing trailer in over the still (600 ms, like the canvas). */
    fun reveal(then: () -> Unit = {}) { animate().alpha(1f).setDuration(600).withEndAction { then() }.start() }

    /** Fades it out again, then blanks the player. */
    fun conceal(then: () -> Unit = {}) {
        animate().alpha(0f).setDuration(600).withEndAction { blank(); then() }.start()
    }

    fun stop() {
        main.removeCallbacks(timeout)
        animate().cancel()
        alpha = 0f
        blank()
    }

    /** Drops the WebView entirely (Home in the background, or nobody at the remote). */
    fun release() {
        stop()
        web?.let { removeView(it); it.destroy() }
        web = null
    }

    private fun blank() {
        visibility = View.GONE
        playing = false
        current = null
        web?.loadUrl("about:blank")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView {
        val w = WebView(context)
        w.settings.javaScriptEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.settings.domStorageEnabled = true
        w.setBackgroundColor(0)
        w.isFocusable = false
        w.webChromeClient = WebChromeClient()
        w.addJavascriptInterface(Bridge(), "ETV")
        addView(w, LayoutParams(MATCH, MATCH))
        return w
    }

    private inner class Bridge {
        @JavascriptInterface
        fun state(s: String) {
            main.post {
                if (current == null) return@post
                when (s) {
                    "playing" -> if (!playing) { playing = true; main.removeCallbacks(timeout); listener?.onTrailerPlaying() }
                    "ended", "error" -> { main.removeCallbacks(timeout); listener?.onTrailerEnded() }
                }
            }
        }
    }

    /** The player is at least 20% larger than the screen (YouTube's own bar and captions fall off the edges), more for wide pictures. */
    private fun html(id: String, cover: Float): String {
        val s = maxOf(1.2f, cover)
        val size = "%.1f".format(java.util.Locale.US, s * 100)
        val off = "%.2f".format(java.util.Locale.US, (s - 1f) / 2f * 100)
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;background:#000;overflow:hidden;width:100%;height:100%}#p,iframe{position:absolute;left:-$off%;top:-$off%;width:$size%;height:$size%;border:0}</style></head>
<body><div id="p"></div><script>
var t=document.createElement('script');t.src='https://www.youtube.com/iframe_api';document.head.appendChild(t);
function onYouTubeIframeAPIReady(){new YT.Player('p',{videoId:'$id',playerVars:{autoplay:1,mute:1,controls:0,rel:0,modestbranding:1,playsinline:1,iv_load_policy:3,disablekb:1,fs:0,origin:'https://espiritu.tv'},
events:{onReady:function(e){e.target.mute();e.target.playVideo();},onStateChange:function(e){if(e.data==1)ETV.state('playing');if(e.data==0)ETV.state('ended');},onError:function(){ETV.state('error');}}});}
</script></body></html>"""
    }
}
