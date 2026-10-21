package com.espiritu.tv

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormatSymbols
import java.util.Calendar

/**
 * The canvas banner hanging from the top edge: "You watch every Tuesday", the show's art with a Tonight badge,
 * its name and when it's on. Only shown when the TV has seen a show watched on the same weekday for weeks.
 */
class BannerView(ctx: Context) : LinearLayout(ctx) {
    private val head = ctx.label(19, Fonts.medium, C.white(0.78f))
    val button = LinearLayout(ctx)
    private val thumb = ImageView(ctx)
    private val badge = ctx.label(15, Fonts.bold, C.BUTTON_INK, "Tonight")
    private val name = ctx.label(25, Fonts.semibold)
    private val time = ctx.label(18, Fonts.regular, C.DIM)
    /** Main thread, after the banner is shown or hidden. */
    var onChange: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        setPadding(px(24), px(26), px(24), px(16))
        background = Glass(this, Glass.bottomOnly(pxf(36)))
        visibility = View.GONE

        val headRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        headRow.addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_calendar) }, LayoutParams(px(22), px(22)).apply { marginEnd = px(10) })
        headRow.addView(head)
        addView(headRow)

        button.ownFocus()
        button.orientation = HORIZONTAL
        button.gravity = Gravity.CENTER_VERTICAL
        button.setPadding(px(8), px(8), px(8), px(8))
        val art = FrameLayout(ctx)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.background = pill(C.white(0.1f), pxf(16), C.white(0.14f))
        art.addView(thumb, FrameLayout.LayoutParams(px(192), px(108)))
        badge.gravity = Gravity.CENTER
        badge.setPadding(px(10), 0, px(10), 0)
        badge.minimumHeight = px(26)
        badge.background = pill(C.WHITE, pxf(13))
        art.addView(badge, FrameLayout.LayoutParams(WRAP, px(26), Gravity.START or Gravity.BOTTOM).apply { setMargins(px(8), 0, 0, px(8)) })
        button.addView(art, LayoutParams(px(192), px(108)))
        val text = LinearLayout(ctx).apply { orientation = VERTICAL }
        name.setLineSpacing(0f, 1.15f)
        name.maxLines = 2
        text.addView(name)
        text.addView(time, LayoutParams(WRAP, WRAP).apply { topMargin = px(8) })
        button.addView(text, LayoutParams(0, WRAP, 1f).apply { marginStart = px(20) })
        button.setOnFocusChangeListener { v, f ->
            v.background = if (f) pill(C.white(0.14f), pxf(22)) else null
            v.animate().scaleX(if (f) 1.03f else 1f).scaleY(if (f) 1.03f else 1f).setDuration(160).start()
        }
        addView(button, LayoutParams(MATCH, WRAP).apply { topMargin = px(8); marginStart = -px(8); marginEnd = -px(8) })

        addView(View(ctx).apply { background = pill(C.white(0.4f), pxf(3)) },
            LayoutParams(px(64), px(6)).apply { topMargin = px(16); gravity = Gravity.CENTER_HORIZONTAL })
    }

    fun set(weekly: Weekly, show: Show?, art: Bitmap?, onClick: () -> Unit) {
        val day = DateFormatSymbols().weekdays[weekly.weekday]
        head.text = "You watch every $day"
        name.text = show?.name ?: weekly.play.showGuess
        val tonight = weekly.daysUntil == 0
        badge.gone(!tonight)
        val at = show?.time?.let(::clock) ?: clock(weekly.minuteOfDay)
        val where = show?.network ?: ""
        time.text = listOf(if (tonight) "Tonight" else day, at, where).filter { it.isNotBlank() }.joinToString(" · ")
        thumb.setImageBitmap(art?.let { AppLoaderArt.roundedCrop(it, px(192), px(108), pxf(16)) })
        button.setOnClickListener { onClick() }
        visibility = View.VISIBLE
        onChange?.invoke()
    }

    fun hide() {
        visibility = View.GONE
        onChange?.invoke()
    }

    private fun clock(hhmm: String): String {
        val parts = hhmm.split(':')
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return ""
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return clock(h * 60 + m)
    }

    private fun clock(minuteOfDay: Int): String {
        val h = minuteOfDay / 60
        val m = minuteOfDay % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        val ampm = if (h < 12) "AM" else "PM"
        return if (m == 0) "$h12 $ampm" else "$h12:${"%02d".format(m)} $ampm"
    }

    companion object {
        fun isToday(weekday: Int) = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == weekday
    }
}
