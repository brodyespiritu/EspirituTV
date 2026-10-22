package com.espiritu.tv

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.SurfaceControlViewHost
import android.view.SurfaceView
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * A trailer behind the Home screen, played through YouTube's embedded player.
 *
 * Speed: on Android 11+ the player lives in its own surface, a SurfaceView behind the Home window that hosts the
 * WebView through SurfaceControlViewHost. Video frames then update only that layer, which the TV's compositor
 * blends in hardware; the Home window (dock, banner, text) is never redrawn for them. The WebView is laid out at
 * ⅔ size and scaled up, so YouTube streams ~720p and the player composites under half the pixels.
 * Older Android: the WebView sits inside the Home window, as before.
 *
 * It sits *under* the still: Home reveals it by fading the still away, so this view never animates itself.
 * The WebView exists only while trailers are wanted (it costs real memory on a TV).
 */
class TrailerView(ctx: Context) : FrameLayout(ctx) {

    interface Listener {
        fun onTrailerPlaying()
        fun onTrailerEnded()
    }

    var listener: Listener? = null
    var playing = false; private set
    /** Play with sound (fades in over 1.5 s, out over 0.6 s). */
    var sound = true
    private var web: WebView? = null
    private var surface: SurfaceView? = null
    private var host: SurfaceControlViewHost? = null
    private var embedded = Build.VERSION.SDK_INT >= 30
    private var current: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val timeout = Runnable { if (!playing) { stop(); listener?.onTrailerEnded() } }

    init {
        visibility = View.GONE
        setBackgroundColor(0)
    }

    /**
     * Starts loading [videoId] (a YouTube id) underneath the still. [Listener.onTrailerPlaying] fires once frames move.
     * [cover] oversizes the player so a letterboxed or pillarboxed picture still fills the screen (see Trailers.coverScale).
     */
    fun play(videoId: String, cover: Float = 1f) {
        val w = web ?: create() ?: return
        current = videoId
        playing = false
        visibility = View.VISIBLE
        w.loadDataWithBaseURL("https://espiritu.tv/", html(videoId, cover, sound), "text/html", "utf-8", null)
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 15_000)
    }

    /** Fades the sound out (Home is fading the still back in over the same 600 ms). */
    fun fadeOutSound() { web?.evaluateJavascript("window.fadeOut&&fadeOut()", null) }

    fun stop() {
        main.removeCallbacks(timeout)
        visibility = View.GONE
        playing = false
        current = null
        web?.loadUrl("about:blank")
    }

    /** Drops the player entirely (Home in the background, or nobody at the remote). */
    fun release() {
        stop()
        web?.destroy()
        web = null
        try { host?.release() } catch (t: Throwable) {}
        host = null
        surface = null
        removeAllViews()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView? {
        val w = WebView(context)
        w.settings.javaScriptEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.settings.domStorageEnabled = true
        w.setBackgroundColor(0xFF000000.toInt())
        w.isFocusable = false
        w.webChromeClient = WebChromeClient()
        w.addJavascriptInterface(Bridge(), "ETV")
        // ⅔ size, scaled back up: about 720p from YouTube, under half the pixels to composite.
        val lw = D.screenW * 2 / 3
        val lh = D.screenH * 2 / 3
        w.pivotX = 0f
        w.pivotY = 0f
        w.scaleX = D.screenW.toFloat() / lw
        w.scaleY = D.screenH.toFloat() / lh
        if (embedded && !embed(w, lw, lh)) embedded = false
        if (!embedded) addView(w, LayoutParams(lw, lh))
        web = w
        return w
    }

    /** Puts [w] in its own surface behind the Home window. False when the TV won't allow it (then it goes in-window). */
    private fun embed(w: WebView, lw: Int, lh: Int): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        return try {
            val sv = SurfaceView(context)
            addView(sv, LayoutParams(MATCH, MATCH))
            val token = sv.hostToken ?: rootView?.windowToken ?: throw IllegalStateException("not attached yet")
            val disp: Display = display ?: (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
            val h = SurfaceControlViewHost(context, disp, token)
            val root = FrameLayout(context).apply { setBackgroundColor(0xFF000000.toInt()); addView(w, LayoutParams(lw, lh)) }
            h.setView(root, D.screenW, D.screenH)
            sv.setChildSurfacePackage(h.surfacePackage ?: throw IllegalStateException("no surface package"))
            surface = sv
            host = h
            true
        } catch (t: Throwable) {
            Log.w("EspirituTV", "trailer surface unavailable, playing in-window: $t")
            removeAllViews()
            surface = null
            host = null
            false
        }
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
    private fun html(id: String, cover: Float, withSound: Boolean): String {
        val s = maxOf(1.2f, cover)
        val size = "%.1f".format(java.util.Locale.US, s * 100)
        val off = "%.2f".format(java.util.Locale.US, (s - 1f) / 2f * 100)
        val onReady = if (withSound)
            "p=e.target;p.unMute();p.setVolume(0);p.playVideo();var v=0,f=setInterval(function(){v+=6;p.setVolume(Math.min(v,80));if(v>=80)clearInterval(f);},110);"
        else "p=e.target;p.mute();p.playVideo();"
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;background:#000;overflow:hidden;width:100%;height:100%}#p,iframe{position:absolute;left:-$off%;top:-$off%;width:$size%;height:$size%;border:0}</style></head>
<body><div id="p"></div><script>
var p=null;
function fadeOut(){if(!p)return;var v=p.getVolume?p.getVolume():0,f=setInterval(function(){v-=14;if(v<=0){v=0;clearInterval(f);}p.setVolume(v);},100);}
var t=document.createElement('script');t.src='https://www.youtube.com/iframe_api';document.head.appendChild(t);
function onYouTubeIframeAPIReady(){new YT.Player('p',{videoId:'$id',playerVars:{autoplay:1,mute:${if (withSound) 0 else 1},controls:0,rel:0,modestbranding:1,playsinline:1,iv_load_policy:3,disablekb:1,fs:0,cc_load_policy:0,origin:'https://espiritu.tv'},
events:{onReady:function(e){$onReady},onStateChange:function(e){if(e.data==1)ETV.state('playing');if(e.data==0)ETV.state('ended');},onError:function(){ETV.state('error');}}});}
</script></body></html>"""
    }
}
