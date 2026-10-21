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
        p.shader = LinearGradient(0f, h.toFloat(), 0f, 0f,
            intArrayOf(C.shade(0.92f), C.shade(0.6f), C.shade(0f)), floatArrayOf(0f, 0.24f, 0.54f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(C.shade(0.72f), C.shade(0.32f), C.shade(0f)), floatArrayOf(0f, 0.36f, 0.6f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = LinearGradient(0f, 0f, 0f, pxf(240), C.shade(0.55f), C.shade(0f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), pxf(240), p)
        return out
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
 * Three cheap draws, no filters. [radii] is the usual 8-value corner array; [squircle] uses the icon shape instead.
 * [top]/[bottom] are the sheen's white alphas (the window is .17 → .08, lighter pills .22 → .16).
 */
class Glass(
    private val host: View,
    private val radii: FloatArray = all(0f),
    private val squircle: Boolean = false,
    private val top: Float = 0.17f,
    private val bottom: Float = 0.08f,
) : Drawable() {
    private val path = Path()
    private val rect = RectF()
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
        path.reset()
        if (squircle) {
            Squircle.path(rect.width(), rect.height(), path)
            path.offset(rect.left, rect.top)
        } else {
            path.addRoundRect(rect, radii, Path.Direction.CW)
        }
        sheen.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), C.white(top), C.white(bottom), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val bmp = Backdrop.blurred
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
            canvas.drawPath(path, art)
        } else {
            canvas.drawPath(path, fallback)
        }
        canvas.drawPath(path, sheen)
        canvas.drawPath(path, edge)
    }

    override fun getOutline(outline: Outline) {
        if (squircle) outline.setRoundRect(bounds, bounds.width() * 0.28f)
        else outline.setRoundRect(bounds, radii[0])
        outline.alpha = 1f
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        fun all(r: Float) = FloatArray(8) { r }
        fun bottomOnly(r: Float) = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
    }
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
