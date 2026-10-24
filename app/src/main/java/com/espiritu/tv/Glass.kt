package com.espiritu.tv

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import java.lang.ref.WeakReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** Apple's continuous-corner shape (a superellipse), used for app icons and the avatar. */
object Squircle {
    fun path(w: Float, h: Float, out: Path = Path(), n: Double = 5.0): Path {
        out.reset()
        val a = w / 2.0
        val b = h / 2.0
        val steps = 120
        for (i in 0..steps) {
            val t = 2 * PI * i / steps
            val c = cos(t)
            val s = sin(t)
            val x = (a + a * sign(c) * abs(c).pow(2 / n)).toFloat()
            val y = (b + b * sign(s) * abs(s).pow(2 / n)).toFloat()
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
        out.close()
        return out
    }
}

/**
 * Apple's continuous-corner rounded rectangle, the shape of a tvOS app icon: each corner eases out of the straight
 * edge over 1.53 × the radius instead of meeting a circle abruptly. Only ever drawn into bitmaps (card art, the
 * focus ring and glow), never live, since paths are slow on the TV's GPU.
 */
object Continuous {
    private const val EDGE = 1.52866471f
    // One corner, from the top edge round to the right edge, in radii measured in from that corner (dx, dy).
    private val corner = arrayOf(
        floatArrayOf(1.08849323f, 0f, 0.86840689f, 0f, 0.66993427f, 0.06549600f),
        floatArrayOf(0.63149399f, 0.07491100f),
        floatArrayOf(0.37282392f, 0.16905899f, 0.16906013f, 0.37282401f, 0.07491176f, 0.63149399f),
        floatArrayOf(0.06549600f, 0.66993427f),
        floatArrayOf(0f, 0.86840689f, 0f, 1.08849323f, 0f, EDGE),
    )

    fun path(rect: RectF, radius: Float, out: Path = Path()): Path {
        out.reset()
        val k = minOf(radius, minOf(rect.width(), rect.height()) / 2f / EDGE)
        // The same corner turned to each of the four, clockwise from the top right.
        fun x(c: Int, dx: Float, dy: Float) = when (c) { 0 -> rect.right - dx * k; 1 -> rect.right - dy * k; 2 -> rect.left + dx * k; else -> rect.left + dy * k }
        fun y(c: Int, dx: Float, dy: Float) = when (c) { 0 -> rect.top + dy * k; 1 -> rect.bottom - dx * k; 2 -> rect.bottom - dy * k; else -> rect.top + dx * k }
        for (c in 0..3) {
            if (c == 0) out.moveTo(x(c, EDGE, 0f), y(c, EDGE, 0f)) else out.lineTo(x(c, EDGE, 0f), y(c, EDGE, 0f))
            for (s in corner) {
                if (s.size == 2) out.lineTo(x(c, s[0], s[1]), y(c, s[0], s[1]))
                else out.cubicTo(x(c, s[0], s[1]), y(c, s[0], s[1]), x(c, s[2], s[3]), y(c, s[2], s[3]), x(c, s[4], s[5]), y(c, s[4], s[5]))
            }
        }
        out.close()
        return out
    }

    /** [src] cut to the shape, corners transparent. */
    fun clip(src: Bitmap, radius: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        c.drawPath(path(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), radius), p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        c.drawBitmap(src, 0f, 0f, p)
        return out
    }
}

/**
 * Everything behind the UI is prepared off the main thread into ready-to-draw bitmaps, so a frame on Home
 * is one full-screen bitmap plus the small glass surfaces, no matter how weak the TV's GPU is:
 * - [compose] bakes the show art (or the plain scene) and the three canvas scrims into one screen-sized bitmap;
 * - [blurOf] makes the tiny, pre-shaded blur that every [Glass] surface samples (the backdrop-filter look).
 */
object Backdrop {
    var blurred: Bitmap? = null; private set
    private val surfaces = ArrayList<WeakReference<View>>()
    private var empty: Bitmap? = null

    fun register(v: View) { surfaces += WeakReference(v) }

    /** Main thread: switches every glass surface to [blurredArt] (from [blurOf]). */
    fun apply(blurredArt: Bitmap?) {
        blurred = blurredArt
        invalidateAll()
    }

    private fun invalidateAll() {
        surfaces.removeAll { it.get() == null }
        surfaces.forEach { it.get()?.invalidate() }
    }

    /** The scene with no art, composed once and kept. */
    fun emptyArt(): Bitmap = empty ?: compose(null).also { empty = it }

    /**
     * A screen-sized frame: [art] center-cropped (or the scene gradient), then the canvas scrims:
     * bottom (0.92 → 0.6 at 24% → 0 at 54%), left (0.72 → 0.32 at 36% → 0 at 60%) and a 240 px fade at the top.
     */
    fun compose(art: Bitmap?): Bitmap {
        val w = D.screenW
        val h = D.screenH
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        if (art == null) {
            p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
                intArrayOf(0xFF1E1A3E.toInt(), 0xFF15142D.toInt(), 0xFF0D0D1B.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = null
        } else {
            val scale = max(w.toFloat() / art.width, h.toFloat() / art.height)
            val dw = art.width * scale
            val dh = art.height * scale
            c.drawBitmap(art, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), p)
        }
        drawScrims(c)
        return out
    }

    private fun drawScrims(c: Canvas) {
        val w = D.screenW.toFloat()
        val h = D.screenH.toFloat()
        val p = Paint(Paint.DITHER_FLAG)
        p.shader = LinearGradient(0f, h, 0f, 0f,
            intArrayOf(C.shade(0.92f), C.shade(0.6f), C.shade(0f)), floatArrayOf(0f, 0.24f, 0.54f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = LinearGradient(0f, 0f, w, 0f,
            intArrayOf(C.shade(0.72f), C.shade(0.32f), C.shade(0f)), floatArrayOf(0f, 0.36f, 0.6f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = LinearGradient(0f, 0f, 0f, pxf(240), C.shade(0.55f), C.shade(0f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, pxf(240), p)
    }

    /**
     * Any thread: shrinks a composed frame to 160 × 90, box-blurs it three times (≈ a gaussian),
     * then bakes in the glass tint (saturation 1.8, 80% brightness) so drawing it later is a plain texture fetch.
     */
    fun blurOf(frame: Bitmap): Bitmap {
        val w = 160
        val h = 90
        val small = Bitmap.createScaledBitmap(frame, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== frame) small.recycle()
        repeat(3) { boxBlur(px, w, h, 3) }
        val soft = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val tint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                setSaturation(1.8f)
                postConcat(ColorMatrix().apply { setScale(0.8f, 0.8f, 0.8f, 1f) })
            })
        }
        Canvas(out).drawBitmap(soft, 0f, 0f, tint)
        soft.recycle()
        return out
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        pass(px, tmp, w, h, r, horizontal = true)
        pass(tmp, px, w, h, r, horizontal = false)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val c = i.coerceIn(0, len - 1)
                return if (horizontal) src[line * w + c] else src[c * w + line]
            }
            var rs = 0; var gs = 0; var bs = 0
            for (i in -r..r) { val p = at(i); rs += (p shr 16) and 255; gs += (p shr 8) and 255; bs += p and 255 }
            for (i in 0 until len) {
                val o = if (horizontal) line * w + i else i * w + line
                dst[o] = (0xFF shl 24) or ((rs / div) shl 16) or ((gs / div) shl 8) or (bs / div)
                val add = at(i + r + 1); val drop = at(i - r)
                rs += ((add shr 16) and 255) - ((drop shr 16) and 255)
                gs += ((add shr 8) and 255) - ((drop shr 8) and 255)
                bs += (add and 255) - (drop and 255)
            }
        }
    }
}

/**
 * A frosted surface: the pre-shaded blur behind it, a soft white sheen from top to bottom, and a hairline edge.
 * Everything is a rounded rectangle, never a path: on the TCL's GPU driver a path is re-tessellated on the CPU
 * every frame, while a rounded rect is a shader. [radius] is the corner radius; [bottomOnly] squares the top
 * corners (for the banner hanging from the screen edge); [squircle] uses the icon-tile proportion.
 * [top]/[bottom] are the sheen's white alphas (the window is .17 → .08, lighter pills .22 → .16).
 */
class Glass(
    private val host: View,
    private val radius: Float = 0f,
    private val bottomOnly: Boolean = false,
    private val squircle: Boolean = false,
    private val top: Float = 0.17f,
    private val bottom: Float = 0.08f,
    /** Dark glass with nothing behind it (for a window over another app). */
    private val plain: Boolean = false,
) : Drawable() {
    private val rect = RectF()
    private val shape = RectF()
    private var r = radius
    private val matrix = Matrix()
    private val loc = IntArray(2)
    private var shaderFor: Bitmap? = null
    private val art = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallback = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6202028.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = pxf(1.5f)
        color = C.white(0.18f)
    }

    init { Backdrop.register(host) }

    override fun onBoundsChange(b: Rect) {
        rect.set(b)
        r = if (squircle) rect.width() * 0.28f else radius
        shape.set(rect)
        // Square top corners: the rounded rect simply starts above the view, and the draw is clipped to it.
        if (bottomOnly) shape.top -= r
        sheen.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), C.white(top), C.white(bottom), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val clip = bottomOnly
        if (clip) { canvas.save(); canvas.clipRect(rect) }
        val bmp = if (plain) null else Backdrop.blurred
        if (bmp != null) {
            if (shaderFor !== bmp) {
                art.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shaderFor = bmp
            }
            host.getLocationInWindow(loc)
            matrix.setScale(D.screenW.toFloat() / bmp.width, D.screenH.toFloat() / bmp.height)
            // Undo the host's own scale/translation animations so the glass stays pinned to the art behind it.
            matrix.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
            art.shader.setLocalMatrix(matrix)
            canvas.drawRoundRect(shape, r, r, art)
        } else {
            canvas.drawRoundRect(shape, r, r, fallback)
        }
        canvas.drawRoundRect(shape, r, r, sheen)
        canvas.drawRoundRect(shape, r, r, edge)
        if (clip) canvas.restore()
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, r)
        outline.alpha = 1f
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A filled rounded rectangle (pills, chips, badges). */
fun pill(color: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (stroke != 0) setStroke(px(1).coerceAtLeast(1), stroke)
}

/** The canvas "Scene" wallpaper, for the very first frame before the composed scene arrives. */
fun sceneBackground() = GradientDrawable(
    GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF1E1A3E.toInt(), 0xFF15142D.toInt(), 0xFF0D0D1B.toInt())
)
