package com.espiritu.tv

import android.content.Context
import android.graphics.Outline
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Turns off the stock focus glow; our views draw their own focus. */
fun View.ownFocus() {
    isFocusable = true
    isFocusableInTouchMode = false
    if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
}

/**
 * The canvas pill button: white with ink text when [primary] (or focused), frosted glass otherwise.
 * 64 px tall, 34 px side padding, 24 px text by default; focus scales it 1.1× from its left edge
 * ([anchorEnd]: its right edge, for pills that sit against the screen's right margin) and lifts it.
 */
class PillButton(
    ctx: Context,
    private val primary: Boolean,
    iconRes: Int = 0,
    text: String = "",
    height: Int = 64,
    textSize: Int = 24,
    pad: Int = 34,
    iconSize: Int = 26,
    private val anchorEnd: Boolean = false,
) : LinearLayout(ctx) {
    private val icon = ImageView(ctx)
    private val label = ctx.label(textSize, Fonts.semibold)
    private val white = pill(C.WHITE, pxf(height / 2f))
    private val glass = Glass(this, radius = pxf(height / 2f), top = 0.24f, bottom = 0.16f)

    var text: String
        get() = label.text.toString()
        set(v) { label.text = v }

    init {
        ownFocus()
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(pad), 0, px(pad), 0)
        minimumHeight = px(height)
        clipToOutline = false
        icon.layoutParams = LayoutParams(px(iconSize), px(iconSize)).apply { marginEnd = px(if (iconSize < 22) 8 else 12) }
        icon.gone(iconRes == 0)
        if (iconRes != 0) icon.setImageResource(iconRes)
        addView(icon)
        label.text = text
        addView(label)
        pivotX = 0f
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, v.height / 2f); o.alpha = 0.6f }
        }
        setOnFocusChangeListener { _, f -> style(f) }
        style(false)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        pivotY = h / 2f
        pivotX = if (anchorEnd) w.toFloat() else 0f
    }

    private fun style(focused: Boolean) {
        val solid = primary || focused
        background = if (solid) white else glass
        label.setTextColor(if (solid) C.BUTTON_INK else C.WHITE)
        label.typeface = if (solid) Fonts.semibold else Fonts.medium
        icon.imageTintList = android.content.res.ColorStateList.valueOf(if (solid) C.BUTTON_INK else C.WHITE)
        // An unfocused primary sits back a little, so the focused button reads clearly from across the room.
        // No elevation shadow: this class of GPU re-tessellates shadows every frame of the animation.
        alpha = if (!focused && primary) 0.82f else 1f
        animate().scaleX(if (focused) 1.1f else 1f).scaleY(if (focused) 1.1f else 1f)
            .setDuration(160).setInterpolator(DecelerateInterpolator()).start()
    }
}

/** A small frosted label (the "Top 10 today" chip, the dock tooltip). */
fun Context.glassChip(text: String, height: Int, textSize: Int, padding: Int): TextView =
    label(textSize, Fonts.semibold, text = text).apply {
        gravity = Gravity.CENTER
        setPadding(px(padding), 0, px(padding), 0)
        minimumHeight = px(height)
        background = Glass(this, radius = pxf(height / 2f), top = 0.24f, bottom = 0.14f)
    }

/** The squircle avatar: a pastel tile with the profile's initial, linking to Settings. */
class Avatar(ctx: Context, size: Int) : FrameLayout(ctx) {
    private val letter = ctx.label(size * 0.44f, Fonts.semibold, C.INK)
    private val tile = ImageView(ctx)

    init {
        ownFocus()
        val s = px(size)
        tile.setImageBitmap(AppLoaderArt.pastelSquircle(s))
        addView(tile, LayoutParams(s, s))
        letter.gravity = Gravity.CENTER
        addView(letter, LayoutParams(s, s))
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, v.width * 0.28f); o.alpha = 0.6f }
        }
        setOnFocusChangeListener { _, f ->
            animate().scaleX(if (f) 1.14f else 1f).scaleY(if (f) 1.14f else 1f).setDuration(160).start()
        }
    }

    fun setName(name: String) { letter.text = name.trim().take(1).uppercase() }
}

/** Little bitmap helpers that don't belong to app loading. */
object AppLoaderArt {
    fun pastelSquircle(size: Int): android.graphics.Bitmap {
        val b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, size.toFloat(), 0xFFF7CDB6.toInt(), 0xFFEBA486.toInt(), android.graphics.Shader.TileMode.CLAMP)
        }
        c.drawPath(Squircle.path(size.toFloat(), size.toFloat()), p)
        return b
    }

    fun roundedCrop(src: android.graphics.Bitmap, w: Int, h: Int, radius: Float): android.graphics.Bitmap {
        val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        c.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, p)
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        // center-crop
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = (w / scale).toInt().coerceAtMost(src.width)
        val sh = (h / scale).toInt().coerceAtMost(src.height)
        val srcRect = Rect((src.width - sw) / 2, (src.height - sh) / 2, (src.width + sw) / 2, (src.height + sh) / 2)
        c.drawBitmap(src, srcRect, Rect(0, 0, w, h), p)
        return out
    }
}
