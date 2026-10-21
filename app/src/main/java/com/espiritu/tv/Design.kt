package com.espiritu.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Every size in this app is given in canvas pixels: the EspirituTV design is drawn on a 1920 × 1080 board,
 * and [px] maps that onto whatever the TV reports, so the screen matches the canvas exactly.
 */
object D {
    var k = 1f; private set
    var screenW = 1920; private set
    var screenH = 1080; private set

    fun init(ctx: Context) {
        val dm = ctx.resources.displayMetrics
        screenW = max(dm.widthPixels, dm.heightPixels)
        screenH = min(dm.widthPixels, dm.heightPixels)
        k = screenW / 1920f
    }
}

fun px(v: Number): Int = (v.toFloat() * D.k).roundToInt()
fun pxf(v: Number): Float = v.toFloat() * D.k

object Fonts {
    var light: Typeface = Typeface.DEFAULT; private set
    var regular: Typeface = Typeface.DEFAULT; private set
    var medium: Typeface = Typeface.DEFAULT; private set
    var semibold: Typeface = Typeface.DEFAULT_BOLD; private set
    var bold: Typeface = Typeface.DEFAULT_BOLD; private set
    private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        fun f(name: String, fallback: Typeface) =
            try { Typeface.createFromAsset(ctx.assets, "fonts/$name.ttf") } catch (t: Throwable) { fallback }
        light = f("inter_light", Typeface.DEFAULT)
        regular = f("inter_regular", Typeface.DEFAULT)
        medium = f("inter_medium", Typeface.DEFAULT)
        semibold = f("inter_semibold", Typeface.DEFAULT_BOLD)
        bold = f("inter_bold", Typeface.DEFAULT_BOLD)
        loaded = true
    }
}

object C {
    const val WHITE = 0xFFFFFFFF.toInt()
    /** Secondary text: rgba(235,235,245,0.62). */
    val DIM = Color.argb(158, 235, 235, 245)
    val INK = Color.argb(204, 28, 28, 30)
    const val BUTTON_INK = 0xFF111114.toInt()
    const val SCREEN = 0xFF06060F.toInt()
    fun white(alpha: Float) = Color.argb((alpha * 255).roundToInt(), 255, 255, 255)
    fun shade(alpha: Float) = Color.argb((alpha * 255).roundToInt(), 6, 6, 16)
}

fun Context.label(size: Number, face: Typeface, color: Int = C.WHITE, text: CharSequence? = null): TextView =
    TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, pxf(size))
        typeface = face
        setTextColor(color)
        includeFontPadding = false
        if (text != null) this.text = text
    }

fun frame(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, gravity)
fun linear(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

fun View.gone(hide: Boolean) { visibility = if (hide) View.GONE else View.VISIBLE }

fun ViewGroup.noClip(): ViewGroup = apply { clipChildren = false; clipToPadding = false }
