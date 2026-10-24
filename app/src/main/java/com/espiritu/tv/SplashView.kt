package com.espiritu.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The loading screen, after Apple Music's "Made by Sound": the EspirituTV wordmark, heavy and solid white, seen
 * through a dark liquid surface. The screen starts black and still; a drop lands on the wordmark with a brief flash,
 * rings of ripples spread out across the surface with light catching their crests on the side facing it, and as
 * they pass they bend and shade the letters the way water does what's under it, while the wordmark rises into
 * view. The ripples die away and the letters settle crisp. While Home is still loading, gentler drops land
 * elsewhere on the word.
 *
 * Cheap enough for the TV: the wordmark is rendered once, off the main thread; each frame warps it with a 48 × 18
 * mesh (drawBitmapMesh, the GPU does the rest), and the rings are one radial gradient, scaled. [dismiss] waits for
 * the ripples on screen to settle, then fades everything away.
 */
class SplashView(ctx: Context) : View(ctx) {
    private val size = pxf(128)
    // Inter Bold thickened a touch for "Espiritu" (about Inter Black), semibold for "TV".
    private val heavy = heavyPaint()
    private val semi = semiPaint()
    private val meshPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.DITHER_FLAG)
    private val flashPaint = Paint(Paint.DITHER_FLAG)
    private val bloomPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val mRing = Matrix(); private val mFlash = Matrix(); private val mBloom = Matrix()
    private val verts = FloatArray((MESH_W + 1) * (MESH_H + 1) * 2)
    private val shades = IntArray((MESH_W + 1) * (MESH_H + 1))

    private var pulse: ValueAnimator? = null
    private var pulses = 0
    /** Seconds since the current drop landed (negative before the first one). */
    private var t = -1f
    /** Where the current drop lands: a fraction of the way along the word, and a nudge up or down. */
    private var spot = 0.5f
    private var spotDy = 0f
    private var dropX = 0f
    private var dropY = 0f
    private var strength = 1f
    private var leaving: (() -> Unit)? = null
    private var fading = false

    private val word1 = "Espiritu"
    private val word2 = "TV"

    // Laid out once per size (see [layout]).
    private var x = 0f
    private var baseline = 0f
    private var midY = 0f
    private var w1 = 0f
    private var total = 0f
    private var word: Word? = null
    private var building = 0

    // The ripple pattern, in fractions of the leading ring's radius: crests at these points, the front at 1. Each
    // ring only catches the light on the side facing it (above left), fading round to a faint glint opposite.
    private val surfaceRings = lit(rings(floatArrayOf(0.07f, 0.11f, 0.16f, 0.21f, 0.28f)))
    private val bloomRings = lit(rings(floatArrayOf(0.08f, 0.13f, 0.18f, 0.25f, 0.34f)))
    private val flash = RadialGradient(0f, 0f, 1000f, intArrayOf(C.white(0.55f), C.white(0.18f), 0), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)

    /** The wordmark rendered once: [body] in white, [bloom] an alpha mask of it blurred (the glow into the dark). */
    private class Word(val body: Bitmap, val bloom: Bitmap?, val dx: Float, val dy: Float) {
        fun recycle() { body.recycle(); bloom?.recycle() }
    }

    init { setBackgroundColor(0xFF000000.toInt()) }

    private fun heavyPaint() = textPaint(Fonts.bold, pxf(3.5f))
    private fun semiPaint() = textPaint(Fonts.semibold, pxf(1f))

    /** White text, thickened by [stroke] (a stroke on top of the fill) and spaced so the heavier letters don't touch. */
    private fun textPaint(face: Typeface?, stroke: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face; textSize = size; color = Color.WHITE
        style = Paint.Style.FILL_AND_STROKE; strokeWidth = stroke; strokeJoin = Paint.Join.ROUND
        letterSpacing = -0.025f + stroke / size
    }

    /** Thin bright rings at [CRESTS] (brightest at the front), [alphas] from the innermost out. */
    private fun rings(alphas: FloatArray): RadialGradient {
        val pos = ArrayList<Float>(); val col = ArrayList<Int>()
        pos += 0f; col += 0
        for ((i, c) in CRESTS.withIndex()) {
            pos += c - 0.022f; col += 0
            pos += c; col += C.white(alphas[i])
            pos += c + 0.022f; col += 0
        }
        pos += 1f; col += 0
        return RadialGradient(0f, 0f, 1000f, col.toIntArray(), pos.toFloatArray(), Shader.TileMode.CLAMP)
    }

    /** [rings] kept only where the crests face the light (DST_IN against an angular falloff). */
    private fun lit(rings: Shader): Shader {
        val facing = SweepGradient(0f, 0f, IntArray(13) { i ->
            val toLight = Math.toRadians(i * 30.0 - 225.0)
            C.white((0.18 + 0.82 * (0.5 + 0.5 * cos(toLight))).toFloat())
        }, null)
        return ComposeShader(rings, facing, PorterDuff.Mode.DST_IN)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        drop()
    }

    override fun onDetachedFromWindow() {
        pulse?.cancel()
        building++
        word?.recycle(); word = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w > 0 && h > 0) layout(w, h)
    }

    private fun layout(w: Int, h: Int) {
        w1 = heavy.measureText(word1)
        total = w1 + semi.measureText(word2)
        x = (w - total) / 2f
        baseline = h / 2f + size * 0.35f
        midY = baseline - size * 0.37f

        val token = ++building
        val tw1 = w1; val ttotal = total
        Thread {
            val g = try { render(tw1, ttotal) } catch (t: Throwable) { null }
            post {
                if (token == building && g != null) { word?.recycle(); word = g; invalidate() } else g?.recycle()
            }
        }.start()
    }

    /** The next drop: the first lands in the middle of the word after a moment of stillness, later ones elsewhere. */
    private fun drop() {
        pulse?.cancel()
        val n = pulses++
        val spots = floatArrayOf(0.5f, 0.27f, 0.74f, 0.42f, 0.6f)
        spot = spots[n % spots.size]
        spotDy = if (n % 2 == 0) 0.04f else -0.08f
        strength = if (n == 0) 1f else 0.5f
        pulse = ValueAnimator.ofFloat(0f, PULSE_S).apply {
            duration = (PULSE_S * 1000).toLong()
            startDelay = if (n == 0) 350 else 0
            interpolator = LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
                if (leaving != null && t >= SETTLED_S) fadeOut()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (cancelled || fading) return
                    if (leaving != null) fadeOut() else if (isAttachedToWindow && visibility == VISIBLE) drop()
                }
            })
            start()
        }
    }

    /** Home is ready: once the ripples on screen have settled, fade out, then [then]. */
    fun dismiss(then: () -> Unit = {}) {
        if (visibility != VISIBLE) { then(); return }
        leaving = then
        if (pulse?.isStarted != true || t >= SETTLED_S) fadeOut()
    }

    private fun fadeOut() {
        if (fading) return
        fading = true
        val done = leaving ?: {}
        leaving = null
        animate().alpha(0f).setDuration(420).withEndAction { pulse?.cancel(); visibility = GONE; done() }.start()
    }

    override fun onDraw(c: Canvas) {
        if (t < 0f) return                                   // still water, nothing showing yet
        dropX = x + total * spot
        dropY = midY + size * spotDy
        val first = pulses == 1
        // The first drop raises the wordmark into view; later ones find it already there.
        val reveal = if (first) smooth(t / 1.1f) else 1f
        val amp = strength * exp(-t / 0.95f)                 // how strong the ripples still are
        val front = pxf(520) * t + pxf(30)                   // the leading ring's radius

        val g = word
        if (g == null) {
            heavy.alpha = (255 * reveal).toInt(); semi.alpha = heavy.alpha
            c.drawText(word1, x, baseline, heavy)
            c.drawText(word2, x + w1, baseline, semi)
        } else {
            warp(c, g, front, amp, reveal)
        }

        // Light on the ripples' crests, across the whole surface.
        mRing.setScale(front / 1000f, front / 1000f); mRing.postTranslate(dropX, dropY)
        surfaceRings.setLocalMatrix(mRing)
        ringPaint.shader = surfaceRings
        ringPaint.alpha = (255 * min(1f, amp * 1.6f)).toInt()
        c.drawCircle(dropX, dropY, front, ringPaint)

        // The splash itself: a brief flash where the drop lands.
        if (t < 0.5f) {
            val k = t / 0.5f
            val r = pxf(40) + pxf(300) * smooth(k)
            mFlash.setScale(r / 1000f, r / 1000f); mFlash.postTranslate(dropX, dropY)
            flash.setLocalMatrix(mFlash)
            flashPaint.shader = flash
            flashPaint.alpha = (255 * strength * (1f - k) * (1f - k)).toInt()
            c.drawCircle(dropX, dropY, r, flashPaint)
        }

        // Where a crest crosses the letters, they glow a little into the dark around them.
        g?.bloom?.let { b ->
            mBloom.setScale(front / 1000f, front / 1000f); mBloom.postTranslate(dropX, dropY)
            bloomRings.setLocalMatrix(mBloom)
            bloomPaint.shader = bloomRings
            bloomPaint.alpha = (255 * min(1f, amp * 1.4f) * reveal).toInt()
            c.drawBitmap(b, x + g.dx, baseline + g.dy, bloomPaint)
        }
    }

    /**
     * The wordmark through moving water: every mesh point is pushed along the ripple's slope where it is (that's
     * how a wavy surface shifts what's under it), and shaded darker in the troughs.
     */
    private fun warp(c: Canvas, g: Word, front: Float, amp: Float, reveal: Float) {
        val gx = x + g.dx; val gy = baseline + g.dy
        val bw = g.body.width.toFloat(); val bh = g.body.height.toFloat()
        val push = pxf(18) * amp
        val wave = (2 * PI / WAVELENGTH).toFloat()
        var v = 0; var s = 0
        for (j in 0..MESH_H) for (i in 0..MESH_W) {
            val px = gx + bw * i / MESH_W
            val py = gy + bh * j / MESH_H
            val dx = px - dropX; val dy = py - dropY
            val r = sqrt(dx * dx + dy * dy)
            val f = r / front                                 // where this point is in the ripple pattern
            var ox = 0f; var oy = 0f; var shade = 1f
            if (f > INNER && f < 1f && r > 0.5f) {
                val e = sin(PI.toFloat() * (f - INNER) / (1f - INNER)) * sqrt((f - INNER) / (1f - INNER))
                val phase = wave * (f - CRESTS.last())
                val slope = -sin(phase) * e
                ox = dx / r * slope * push; oy = dy / r * slope * push
                shade = 1f - 0.4f * min(1f, amp) * e * (0.5f - 0.5f * cos(phase))
            }
            verts[v++] = px + ox; verts[v++] = py + oy
            val sv = (255 * shade).toInt().coerceIn(0, 255)
            shades[s++] = (0xFF shl 24) or (sv shl 16) or (sv shl 8) or sv
        }
        meshPaint.alpha = (255 * reveal).toInt()
        c.drawBitmapMesh(g.body, MESH_W, MESH_H, verts, 0, shades, 0, meshPaint)
    }

    private fun smooth(k: Float): Float { val c = k.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }

    /** Any thread: the wordmark in white, with room around it for the ripples to push it about, and its bloom. */
    private fun render(w1: Float, total: Float): Word {
        val pad = (pxf(26) * 3).toInt()
        val w = (total + pad * 2).toInt()
        val h = (size * 1.3f + pad * 2).toInt()
        val by = pad + size
        val body = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(body)
        c.drawText(word1, pad.toFloat(), by, heavyPaint())
        c.drawText(word2, pad + w1, by, semiPaint())
        val bloom = try {
            val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val sc = Canvas(src)
            val blur = BlurMaskFilter(pxf(26), BlurMaskFilter.Blur.NORMAL)
            sc.drawText(word1, pad.toFloat(), by, heavyPaint().apply { maskFilter = blur })
            sc.drawText(word2, pad + w1, by, semiPaint().apply { maskFilter = blur })
            src.extractAlpha().also { src.recycle() }
        } catch (t: Throwable) { null }
        return Word(body, bloom, dx = -pad.toFloat(), dy = -by)
    }

    private companion object {
        const val MESH_W = 48
        const val MESH_H = 18
        /** One drop: the ripples cross the word and die away. */
        const val PULSE_S = 2.6f
        /** By now the ripples have mostly passed and the letters read cleanly, so Home may take over. */
        const val SETTLED_S = 1.5f
        /** The ripple train's crests, as fractions of the leading ring's radius, innermost first. */
        val CRESTS = floatArrayOf(0.695f, 0.765f, 0.835f, 0.905f, 0.975f)
        /** Where the ripple train begins, inside the leading ring; calm water within. */
        const val INNER = 0.66f
        /** The crest spacing, in the same fractions. */
        const val WAVELENGTH = 0.07
    }
}
