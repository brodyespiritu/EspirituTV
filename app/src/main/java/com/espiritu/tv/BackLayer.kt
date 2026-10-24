package com.espiritu.tv

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.SurfaceControlViewHost
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * The art behind Home: two stills crossfading, the one on screen drifting slowly closer the whole time it's up
 * (the Apple TV screensaver move), so the background is always alive without any video.
 *
 * On Android 11+ it lives in its own surface: a SurfaceView behind the Home window hosting the stills through
 * SurfaceControlViewHost. The drift and the fades update only that layer, which the TV's compositor blends in
 * hardware; the Home window (cards, banner, text) never redraws for them.
 *
 * The surface goes away whenever another app is in front. Android 11 doesn't hang the hosted stills back onto a
 * re-created surface, so instead of re-using it the layer is rebuilt from scratch (new SurfaceView, new host,
 * new stage) and attached only once the new surface exists. [onReady] / [onLost] let Home cover the gap with its
 * placeholder, so the screen is never black. Older Android, or a TV that refuses the surface: the stills sit in
 * the Home window and hold still.
 */
class BackLayer(ctx: Context) : FrameLayout(ctx) {
    private var stage = FrameLayout(ctx)
    private var back = ImageView(ctx)
    private var front = ImageView(ctx)
    private var surface: SurfaceView? = null
    private var host: SurfaceControlViewHost? = null
    private var embedded = false
    private var visible = false
    private var shown: Bitmap? = null
    private var turn = 0
    private var driftAnim: AnimatorSet? = null

    /** The still is on screen (Home can stop painting its placeholder). Called again after every rebuild. */
    var onReady: (() -> Unit)? = null
    /** The art is gone until the next [onReady] (Home should cover with its placeholder). */
    var onLost: (() -> Unit)? = null

    /** Slow drift on the still that's up (only where it runs on its own surface). */
    var motion = true
        set(v) {
            field = v
            if (v) drift(front)
            else { driftAnim?.cancel(); front.scaleX = 1f; front.scaleY = 1f; front.translationX = 0f }
        }

    init {
        buildStage()
        if (Build.VERSION.SDK_INT >= 30) newSurface() else inWindow()
    }

    private fun buildStage() {
        stage = FrameLayout(context).apply { setBackgroundColor(0xFF06060F.toInt()) }
        back = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        front = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        stage.addView(back, LayoutParams(MATCH, MATCH))
        stage.addView(front, LayoutParams(MATCH, MATCH))
        shown?.let { front.setImageBitmap(it) }
    }

    private fun newSurface() {
        val sv = SurfaceView(context)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { if (surface === sv) attachHost(sv) }
            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                if (surface !== sv) return
                visible = false
                onLost?.invoke()
                // Rebuild for the next time Home shows (see the class comment).
                post { if (surface === sv) rebuild() }
            }
        })
        addView(sv, 0, LayoutParams(MATCH, MATCH))
        surface = sv
    }

    private fun rebuild() {
        driftAnim?.cancel()
        try { host?.release() } catch (t: Throwable) {}
        host = null
        surface?.let { removeView(it) }
        surface = null
        buildStage()
        newSurface()
    }

    /** The surface exists: host the stills in it. On failure, fall back to drawing them in the Home window. */
    private fun attachHost(sv: SurfaceView) {
        if (host != null || Build.VERSION.SDK_INT < 30) return
        try {
            val token = sv.hostToken ?: throw IllegalStateException("no host token")
            val h = SurfaceControlViewHost(context, sv.display ?: throw IllegalStateException("no display"), token)
            h.setView(stage, D.screenW, D.screenH)
            sv.setChildSurfacePackage(h.surfacePackage ?: throw IllegalStateException("no surface package"))
            host = h
            embedded = true
            visible = true
            drift(front)
            ready()
        } catch (t: Throwable) {
            Log.w("EspirituTV", "background surface unavailable, drawing in-window: $t")
            try { removeView(sv) } catch (e: Throwable) {}
            surface = null
            buildStage()
            inWindow()
        }
    }

    private fun inWindow() {
        embedded = false
        visible = true
        if (stage.parent == null) addView(stage, LayoutParams(MATCH, MATCH))
        ready()
    }

    private fun ready() {
        if (!visible || shown == null) return
        // One frame for the art to reach the screen before Home stops covering it.
        postDelayed({ if (visible) onReady?.invoke() }, 100)
    }

    /** Crossfades to [frame] (a composed, screen-sized still) over 900 ms; the first one just appears. */
    fun show(frame: Bitmap) {
        if (frame === shown) return
        val first = shown == null
        shown = frame
        turn++
        if (first) {
            front.setImageBitmap(frame)
            drift(front)
            ready()
            return
        }
        // The outgoing still keeps its place in the drift while the new one fades in over it.
        driftAnim?.cancel()
        back.animate().cancel()
        back.setImageDrawable(front.drawable)
        back.scaleX = front.scaleX; back.scaleY = front.scaleY
        back.pivotX = front.pivotX; back.pivotY = front.pivotY
        back.translationX = front.translationX
        back.alpha = 1f
        front.animate().cancel()
        front.setImageBitmap(frame)
        front.alpha = 0f
        front.scaleX = 1f; front.scaleY = 1f; front.translationX = 0f
        val b = back
        front.animate().alpha(1f).setDuration(900).withEndAction { b.setImageDrawable(null) }.start()
        drift(front)
    }

    /** Home went away: finish any fade and stop moving. */
    fun pause() {
        driftAnim?.cancel()
        back.animate().cancel()
        back.setImageDrawable(null)
        front.animate().cancel()
        front.alpha = 1f
    }

    fun resume() { if (shown != null) drift(front) }

    /** Drops the separate surface (the activity is going away). */
    fun release() {
        pause()
        try { host?.release() } catch (t: Throwable) {}
        host = null
    }

    /**
     * About 7% closer over 16 s, alternately drifting a little right and left. Scale and drift run on their own
     * animator so the 900 ms fade (alpha, on the view's animator) isn't cut short.
     */
    private fun drift(v: ImageView) {
        driftAnim?.cancel()
        if (!embedded || !motion || shown == null) return
        val dir = if (turn % 2 == 0) 1f else -1f
        v.pivotX = D.screenW * (if (dir > 0) 0.62f else 0.38f)
        v.pivotY = D.screenH * 0.45f
        driftAnim = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(v, View.SCALE_X, v.scaleX, 1.07f),
                ObjectAnimator.ofFloat(v, View.SCALE_Y, v.scaleY, 1.07f),
                ObjectAnimator.ofFloat(v, View.TRANSLATION_X, v.translationX, dir * pxf(24)),
            )
            duration = 16_000
            interpolator = LinearInterpolator()
            start()
        }
    }
}
