package com.espiritu.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * The loading screen: black, the EspirituTV wordmark in dim white, and a band of light sweeping across it from
 * left to right (with a thin line beneath catching the same light), over and over until Home is ready.
 * [dismiss] lets the current sweep finish, then fades the whole screen away.
 */
class SplashView(ctx: Context) : View(ctx) {
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.semibold; textSize = pxf(128) }
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.light; textSize = pxf(128) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Matrix()
    private var shader: LinearGradient? = null
    private var band = 0f
    private var sweep: ValueAnimator? = null
    private var leaving: (() -> Unit)? = null

    private val word1 = "Espiritu"
    private val word2 = "TV"

    init {
        setBackgroundColor(0xFF000000.toInt())
        boldPaint.letterSpacing = -0.02f
        lightPaint.letterSpacing = -0.02f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startSweep()
    }

    override fun onDetachedFromWindow() {
        sweep?.cancel()
        super.onDetachedFromWindow()
    }

    private fun startSweep() {
        sweep?.cancel()
        sweep = ValueAnimator.ofFloat(-0.35f, 1.35f).apply {
            duration = 1600
            startDelay = 250
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { band = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    val done = leaving
                    if (done != null) {
                        leaving = null
                        animate().alpha(0f).setDuration(380).withEndAction { visibility = GONE; done() }.start()
                    } else if (isAttachedToWindow && visibility == VISIBLE) startSweep()
                }
            })
            start()
        }
    }

    /** Home is ready: finish the sweep in progress, fade out, then [then]. */
    fun dismiss(then: () -> Unit = {}) {
        if (visibility != VISIBLE) { then(); return }
        // isStarted also covers the short wait before the first sweep, so the light always crosses at least once.
        if (sweep?.isStarted == true) leaving = then
        else animate().alpha(0f).setDuration(380).withEndAction { visibility = GONE; then() }.start()
    }

    override fun onDraw(c: Canvas) {
        val w1 = boldPaint.measureText(word1)
        val w2 = lightPaint.measureText(word2)
        val total = w1 + w2
        val x = (width - total) / 2f
        val baseline = height / 2f + pxf(128) * 0.35f
        val lineY = baseline + pxf(54)

        // Dim wordmark and line.
        boldPaint.shader = null; lightPaint.shader = null
        boldPaint.color = C.white(0.16f); lightPaint.color = C.white(0.16f)
        c.drawText(word1, x, baseline, boldPaint)
        c.drawText(word2, x + w1, baseline, lightPaint)
        linePaint.shader = null
        linePaint.color = C.white(0.08f)
        c.drawRect(x, lineY, x + total, lineY + pxf(2), linePaint)

        // The light: a soft white band, 40% of the wordmark wide, at [band] across it.
        val bw = total * 0.4f
        val s = shader ?: LinearGradient(0f, 0f, bw, 0f,
            intArrayOf(0x00FFFFFF, C.WHITE, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP).also { shader = it }
        shine.setTranslate(x + band * total - bw / 2f, 0f)
        s.setLocalMatrix(shine)
        boldPaint.color = C.WHITE; lightPaint.color = C.WHITE; linePaint.color = C.WHITE
        boldPaint.shader = s; lightPaint.shader = s; linePaint.shader = s
        c.drawText(word1, x, baseline, boldPaint)
        c.drawText(word2, x + w1, baseline, lightPaint)
        c.drawRect(x, lineY, x + total, lineY + pxf(2), linePaint)
    }
}
