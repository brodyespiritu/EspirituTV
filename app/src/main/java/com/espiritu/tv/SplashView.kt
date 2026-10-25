package com.espiritu.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ComposeShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
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
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The loading screen, after Apple Music's "Made by Sound": the EspirituTV wordmark, heavy and solid white, seen
 * through a dark liquid surface.
 *
 * The screen starts black and still, with only the faint reflection of a studio light lying on the surface. A drop
 * lands on the wordmark with a glint; the impact sends out a train of ripples, and the rebound two more, each
 * weaker, losing strength as they spread. Light glints off each crest in a bright spot on the side facing it, with
 * a faint sheen the rest of the way round, unevenly, as on a real surface, and the reflection on the surface wobbles
 * as the ripples cross it. Under the water, the wordmark rises into view out of focus and sharpens as the surface
 * calms; the passing ripples bend and shade it the way water does what's under it, and, like water, they bend red,
 * green and blue light by different amounts: the wordmark is drawn once per colour and added back together, each
 * colour pushed its own distance, so the waves split it into fringes of colour (and the crests into thin rainbows)
 * that close up into clean white as the surface settles. While Home is still loading, gentler drops land elsewhere.
 *
 * Cheap enough for the TV: everything is rendered once, off the main thread. Each frame works out the ripples'
 * slope once at each point of a 96 × 36 mesh over the wordmark and a 40 × 22 one over the screen, warps the
 * pictures with them (drawBitmapMesh, the GPU does the rest), and draws each ring as one radial gradient, only over
 * the band where the ripples are. [dismiss] waits for the ripples on screen to settle, then fades everything away.
 */
class SplashView(ctx: Context) : View(ctx) {
    private val size = pxf(128)
    // Inter Bold thickened a touch for "Espiritu" (about Inter Black), semibold for "TV".
    private val heavy = heavyPaint()
    private val semi = semiPaint()
    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private val meshPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { xfermode = add }
    private val surfacePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply { xfermode = add }
    private val ringPaint = Paint(Paint.DITHER_FLAG or Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; xfermode = add }
    private val flashPaint = Paint(Paint.DITHER_FLAG).apply { xfermode = add }
    private val bloomPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = add }
    private val mRing = Matrix(); private val mFlash = Matrix(); private val mBloom = Matrix()
    /** Red, green and blue on their own, for the three passes that add back up to white. */
    private val channels: Array<ColorFilter> = arrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        .map { PorterDuffColorFilter(it, PorterDuff.Mode.MULTIPLY) }.toTypedArray()

    // The wordmark's mesh: the ripple field at each point (see [field]), then one set of vertices per colour plus
    // one for the out-of-focus copy.
    private val wordField = Field(WORD_W, WORD_H)
    private val wordVerts = Array(4) { FloatArray((WORD_W + 1) * (WORD_H + 1) * 2) }
    private val wordShades = Array(4) { IntArray((WORD_W + 1) * (WORD_H + 1)) }
    // The surface's mesh, for the reflection lying on it.
    private val surfaceField = Field(SURF_W, SURF_H)
    private val surfaceVerts = FloatArray((SURF_W + 1) * (SURF_H + 1) * 2)

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
    /** Each ripple train's leading-ring radius (≤ 0 before it starts) and strength, this frame. */
    private val fronts = FloatArray(TRAIN_DELAY.size)
    private val amps = FloatArray(TRAIN_DELAY.size)
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
    private var art: Art? = null
    private var building = 0

    // The ripple pattern, in fractions of the leading ring's radius: crests at [CRESTS], the front at 1. One shader
    // per ring drawn in a frame (the main train once per colour, then the rebounds), so none is shared between draws.
    private val rings = Array(5) { lit(rings(floatArrayOf(0.1f, 0.15f, 0.22f, 0.32f, 0.5f))) }
    private val bloomRings = lit(rings(floatArrayOf(0.08f, 0.13f, 0.18f, 0.25f, 0.34f)))
    private val flash = RadialGradient(0f, 0f, 1000f, intArrayOf(C.white(0.45f), C.white(0.12f), 0), floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)

    /**
     * Everything rendered once: the wordmark sharp ([body]) and out of focus ([soft]), an alpha mask of its glow
     * ([bloom]), and the studio light's reflection on the surface ([surface], small; the mesh stretches it over the screen).
     */
    private class Art(val body: Bitmap, val soft: Bitmap, val bloom: Bitmap?, val surface: Bitmap, val dx: Float, val dy: Float) {
        fun recycle() { body.recycle(); soft.recycle(); bloom?.recycle(); surface.recycle() }
    }

    /** The ripples' sideways push (in pixels, for green light) and trough shading at each point of a mesh, this frame. */
    private class Field(val cols: Int, val rows: Int) {
        val n = (cols + 1) * (rows + 1)
        val px = FloatArray(n); val py = FloatArray(n)        // the point itself
        val ux = FloatArray(n); val uy = FloatArray(n)        // the direction away from the drop
        val fx = FloatArray(n); val fy = FloatArray(n)        // the push
        val shade = FloatArray(n)
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

    /**
     * Bright crests at [CRESTS] (brightest at the front), [alphas] from the innermost out. Each is the light off a
     * wave's leading face: sharp on the outside, falling off softly behind.
     */
    private fun rings(alphas: FloatArray): RadialGradient {
        val pos = ArrayList<Float>(); val col = ArrayList<Int>()
        pos += 0f; col += 0
        for ((i, c) in CRESTS.withIndex()) {
            pos += c - 0.03f; col += 0
            pos += c - 0.009f; col += C.white(alphas[i] * 0.45f)
            pos += c; col += C.white(alphas[i])
            pos += c + 0.008f; col += 0
        }
        pos += 1f; col += 0
        return RadialGradient(0f, 0f, 1000f, col.toIntArray(), pos.toFloatArray(), Shader.TileMode.CLAMP)
    }

    /**
     * [rings] as the light actually catches them: a bright glint where a crest faces the light (above left), a soft
     * sheen either side of it, a faint gleam the rest of the way round, and a little unevenness all along, as a real
     * surface has (DST_IN against an angular falloff).
     */
    private fun lit(rings: Shader): Shader {
        val n = 120
        val facing = SweepGradient(0f, 0f, IntArray(n + 1) { i ->
            val angle = i * 360.0 / n
            val toLight = 0.5 + 0.5 * cos(Math.toRadians(angle - 225.0))
            val glint = 0.08 + 0.42 * toLight * toLight + 0.5 * toLight.pow(24)
            // Fixed, gentle unevenness (the same each time; it wraps round seamlessly).
            val k = Math.toRadians(angle)
            val uneven = 0.8 + 0.2 * (0.5 + 0.5 * sin(5 * k + 1.3) * cos(3 * k + 0.4))
            C.white((glint * uneven).toFloat().coerceIn(0f, 1f))
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
        art?.recycle(); art = null
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
                if (token == building && g != null) { art?.recycle(); art = g; invalidate() } else g?.recycle()
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
            startDelay = if (n == 0) 450 else 0
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

    /**
     * Home is ready: once the ripples on screen have settled, fade out, then [then]. The first drop always plays
     * through, even when Home is ready before it has landed (art already cached).
     */
    fun dismiss(then: () -> Unit = {}) {
        if (visibility != VISIBLE) { then(); return }
        leaving = then
        if (pulses > 0 && t >= SETTLED_S) fadeOut()
    }

    private fun fadeOut() {
        if (fading) return
        fading = true
        val done = leaving ?: {}
        leaving = null
        animate().alpha(0f).setDuration(420).withEndAction { pulse?.cancel(); visibility = GONE; done() }.start()
    }

    override fun onDraw(c: Canvas) {
        val a = art
        // Before the first drop: still water, only the light's reflection on it.
        if (t < 0f) { if (a != null) surface(c, a, still = true); return }
        dropX = x + total * spot
        dropY = midY + size * spotDy
        val first = pulses == 1
        // The first drop raises the wordmark into view, out of focus at first; later ones find it already there.
        val reveal = if (first) smooth(t / 1.1f) else 1f
        for (k in TRAIN_DELAY.indices) {
            val tt = t - TRAIN_DELAY[k]
            if (tt <= 0f) { fronts[k] = 0f; amps[k] = 0f; continue }
            fronts[k] = pxf(520) * tt + pxf(30)
            // Damped as it goes, and thinning as the ring spreads its energy round a longer circle.
            amps[k] = strength * TRAIN_STRENGTH[k] * exp(-tt / 0.95f) / sqrt(1f + fronts[k] / pxf(420))
        }
        val focus = if (first) smooth((t - 0.1f) / 1.0f) else 1f - 0.35f * min(1f, amps[0] * 2f)

        if (a != null) surface(c, a, still = false)
        if (a == null) {
            heavy.alpha = (255 * reveal).toInt(); semi.alpha = heavy.alpha
            c.drawText(word1, x, baseline, heavy)
            c.drawText(word2, x + w1, baseline, semi)
        } else {
            word(c, a, reveal, focus)
        }

        // Light on the crests across the whole surface: the main train split into colours (a thin rainbow on each
        // crest, red outermost, wider while the waves are strong), the rebounds plain.
        var shader = 0
        for (k in TRAIN_DELAY.indices) {
            if (fronts[k] <= 0f) continue
            if (k == 0) for (ch in 0..2) {
                val spread = 1f + RING_SPREAD[ch] * (0.4f + 0.6f * min(1f, amps[0] * 1.5f))
                ring(c, rings[shader++], fronts[k] * spread, amps[k], channels[ch])
            } else ring(c, rings[shader++], fronts[k], amps[k], null)
        }

        // The splash itself: a brief glint of light where the drop lands.
        if (t < 0.45f) {
            val k = t / 0.45f
            val r = pxf(30) + pxf(220) * smooth(k)
            mFlash.setScale(r / 1000f, r / 1000f); mFlash.postTranslate(dropX, dropY)
            flash.setLocalMatrix(mFlash)
            flashPaint.shader = flash
            flashPaint.alpha = (255 * strength * (1f - k) * (1f - k)).toInt()
            c.drawCircle(dropX, dropY, r, flashPaint)
        }

        // Where the main train's crests cross the letters, they glow a little into the dark around them.
        val front = fronts[0]
        if (front > 0f) a?.bloom?.let { b ->
            mBloom.setScale(front / 1000f, front / 1000f); mBloom.postTranslate(dropX, dropY)
            bloomRings.setLocalMatrix(mBloom)
            bloomPaint.shader = bloomRings
            bloomPaint.alpha = (255 * min(1f, amps[0] * 1.5f) * reveal).toInt()
            c.drawBitmap(b, x + a.dx, baseline + a.dy, bloomPaint)
        }
    }

    /** One ring train's crests at radius [front], drawn only over the band the ripples occupy. */
    private fun ring(c: Canvas, shader: Shader, front: Float, amp: Float, colour: ColorFilter?) {
        mRing.setScale(front / 1000f, front / 1000f); mRing.postTranslate(dropX, dropY)
        shader.setLocalMatrix(mRing)
        ringPaint.shader = shader
        ringPaint.colorFilter = colour
        ringPaint.alpha = (255 * min(1f, amp * 1.7f)).toInt()
        val inner = front * (INNER - 0.02f)
        ringPaint.strokeWidth = front - inner
        c.drawCircle(dropX, dropY, (front + inner) / 2f, ringPaint)
    }

    /**
     * The ripples' slope at every point of [f] (a mesh over [x0], [y0], [w] × [h]): the push sideways that a wavy
     * surface gives what's seen through it, and how much darker the troughs make it. Worked out once a frame and
     * shared by every picture drawn with that mesh.
     *
     * Two scales, as on real water: a long swell across each ripple train does the visible bending, and the fine
     * ripples riding on it add texture. Each one's push is kept under what its wavelength can take without the
     * picture folding over itself (which would show as torn, doubled letters).
     */
    private fun field(f: Field, x0: Float, y0: Float, w: Float, h: Float) {
        val tau = (2 * PI).toFloat()
        val span = 1f - INNER
        var i = 0
        for (row in 0..f.rows) for (col in 0..f.cols) {
            val px = x0 + w * col / f.cols
            val py = y0 + h * row / f.rows
            val dx = px - dropX; val dy = py - dropY
            val r = sqrt(dx * dx + dy * dy)
            val ux = if (r > 0.5f) dx / r else 0f
            val uy = if (r > 0.5f) dy / r else 0f
            var push = 0f; var shade = 1f
            for (k in TRAIN_DELAY.indices) {
                val front = fronts[k]
                if (front <= 0f) continue
                val q = r / front                             // where this point is in that train's pattern
                if (q <= INNER || q >= 1f) continue
                val u = (q - INNER) / span
                val e = sin(PI.toFloat() * u) * sqrt(u)
                val swell = min(amps[k] * pxf(26), FOLD * span * front / tau)
                val fine = min(amps[k] * pxf(10), FOLD * WAVELENGTH.toFloat() * front / tau)
                val phase = tau * (q - CRESTS.last()) / WAVELENGTH.toFloat()
                push += -(sin(tau * u) * swell + sin(phase) * fine) * e
                shade *= 1f - 0.4f * min(1f, amps[k]) * e * (0.5f - 0.5f * cos(phase))
            }
            f.px[i] = px; f.py[i] = py; f.ux[i] = ux; f.uy[i] = uy
            f.fx[i] = ux * push; f.fy[i] = uy * push; f.shade[i] = shade
            i++
        }
    }

    /**
     * The wordmark through moving water: an out-of-focus copy fading into a sharp one drawn once per colour, each
     * pushed along the ripples' slope by its own amount (blue furthest, as in water) and, while the water moves,
     * displaced a little outward (red) or inward (blue) from the drop, like light through a lens. The colours add
     * back up to white wherever they line up.
     */
    private fun word(c: Canvas, a: Art, reveal: Float, focus: Float) {
        val f = wordField
        val gx = x + a.dx; val gy = baseline + a.dy
        field(f, gx, gy, a.body.width.toFloat(), a.body.height.toFloat())
        val split = pxf(5) * min(1f, amps[0] * 1.4f)
        for (pass in 0..3) {
            val ch = if (pass == 3) 1 else pass               // the soft copy bends like green, the middle
            val bend = DISPERSION[ch]
            val lens = split * SPLIT[ch]
            val vs = wordVerts[pass]; val ss = wordShades[pass]
            val mask = if (pass == 3) 0xFFFFFF else (0xFF shl (16 - 8 * ch))
            var v = 0
            for (i in 0 until f.n) {
                vs[v++] = f.px[i] + f.fx[i] * bend + f.ux[i] * lens
                vs[v++] = f.py[i] + f.fy[i] * bend + f.uy[i] * lens
                val sv = (255 * f.shade[i]).toInt().coerceIn(0, 255)
                ss[i] = (0xFF shl 24) or ((sv or (sv shl 8) or (sv shl 16)) and mask)
            }
            val sharp = pass < 3
            val alpha = reveal * (if (sharp) focus else (1f - focus) * 0.9f)
            if (alpha <= 0.004f) continue
            meshPaint.alpha = (255 * alpha).toInt()
            c.drawBitmapMesh(if (sharp) a.body else a.soft, WORD_W, WORD_H, vs, 0, ss, 0, meshPaint)
        }
    }

    /** The studio light's faint reflection on the surface; a reflection moves more with the slope than a view through. */
    private fun surface(c: Canvas, a: Art, still: Boolean) {
        val f = surfaceField
        val w = width.toFloat(); val h = height.toFloat()
        if (still) { fronts.fill(0f); amps.fill(0f) }
        field(f, 0f, 0f, w, h)
        val bend = 2.2f
        var v = 0
        for (i in 0 until f.n) {
            surfaceVerts[v++] = f.px[i] + f.fx[i] * bend
            surfaceVerts[v++] = f.py[i] + f.fy[i] * bend
        }
        c.drawBitmapMesh(a.surface, SURF_W, SURF_H, surfaceVerts, 0, null, 0, surfacePaint)
    }

    private fun smooth(k: Float): Float { val c = k.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }

    /**
     * Any thread: the wordmark in white, sharp and out of focus, with room around it for the ripples to push it
     * about; its bloom; and the light's reflection on the surface (a long soft band above the word, a fainter one
     * below), small, as the mesh stretches it over the whole screen.
     */
    private fun render(w1: Float, total: Float): Art {
        val pad = (pxf(26) * 3).toInt()
        val w = (total + pad * 2).toInt()
        val h = (size * 1.3f + pad * 2).toInt()
        val by = pad + size
        fun word(blur: Float): Bitmap {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val mf = if (blur > 0f) BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) else null
            c.drawText(word1, pad.toFloat(), by, heavyPaint().apply { maskFilter = mf })
            c.drawText(word2, pad + w1, by, semiPaint().apply { maskFilter = mf })
            return b
        }
        val body = word(0f)
        val soft = word(pxf(11))
        val bloom = try { word(pxf(26)).let { it.extractAlpha().also { _ -> it.recycle() } } } catch (t: Throwable) { null }

        val sw = 240; val sh = 135
        val surface = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val sc = Canvas(surface)
        fun band(cx: Float, cy: Float, rx: Float, ry: Float, alpha: Float) {
            val p = Paint(Paint.DITHER_FLAG).apply {
                shader = RadialGradient(0f, 0f, 1f, intArrayOf(C.white(alpha), C.white(alpha * 0.35f), 0), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            }
            sc.save(); sc.translate(cx, cy); sc.scale(rx, ry); sc.drawCircle(0f, 0f, 1f, p); sc.restore()
        }
        band(sw * 0.47f, sh * 0.2f, sw * 0.42f, sh * 0.06f, 0.06f)
        band(sw * 0.55f, sh * 0.84f, sw * 0.3f, sh * 0.035f, 0.025f)
        return Art(body, soft, bloom, surface, dx = -pad.toFloat(), dy = -by)
    }

    private companion object {
        const val WORD_W = 96
        const val WORD_H = 36
        const val SURF_W = 40
        const val SURF_H = 22
        /** One drop: the ripples cross the word and die away. */
        const val PULSE_S = 2.6f
        /** By now the ripples have mostly passed and the letters read cleanly, so Home may take over. */
        const val SETTLED_S = 1.6f
        /** The impact's ripples, then the rebound's two: when each starts (s) and how strong it is. */
        val TRAIN_DELAY = floatArrayOf(0f, 0.3f, 0.58f)
        val TRAIN_STRENGTH = floatArrayOf(1f, 0.55f, 0.3f)
        /** How far each colour is bent, red to blue (water bends blue light most). */
        val DISPERSION = floatArrayOf(0.6f, 1f, 1.45f)
        /** The lens-like split while the water moves: red outward, green in place, blue inward. */
        val SPLIT = floatArrayOf(1f, 0f, -1f)
        /** How far each colour's crests sit from green's at full strength, red outermost: a thin rainbow on each crest. */
        val RING_SPREAD = floatArrayOf(0.02f, 0f, -0.02f)
        /** The ripple train's crests, as fractions of the leading ring's radius, innermost first. */
        val CRESTS = floatArrayOf(0.695f, 0.765f, 0.835f, 0.905f, 0.975f)
        /** Where the ripple train begins, inside the leading ring; calm water within. */
        const val INNER = 0.66f
        /** The crest spacing, in the same fractions. */
        const val WAVELENGTH = 0.07
        /** How hard a wave may push for its wavelength: below the fold-over point (1), with room for blue's extra bend. */
        const val FOLD = 0.42f
    }
}
