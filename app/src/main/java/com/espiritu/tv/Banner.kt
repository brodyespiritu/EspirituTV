package com.espiritu.tv

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import java.text.DateFormatSymbols

/**
 * The banner hanging from the top edge, in one of two modes:
 * - Continue watching: the show left part-way, its episode, a progress bar and the minutes left;
 * - Weekly: "You watch every Tuesday", the show's art with a Tonight badge, and when it's on.
 * A show in progress wins; with neither, the banner isn't there.
 */
class BannerView(ctx: Context) : LinearLayout(ctx) {
    private val headIcon = ImageView(ctx)
    private val head = ctx.label(19, Fonts.medium, C.white(0.78f))
    val button = LinearLayout(ctx)
    private val thumb = ImageView(ctx)
    private val badge = ctx.label(15, Fonts.bold, C.BUTTON_INK, "Tonight")
    private val name = ctx.label(25, Fonts.semibold)
    private val line = ctx.label(18, Fonts.regular, C.DIM)
    private val progressRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val track = FrameLayout(ctx)
    private val fill = View(ctx)
    private val left = ctx.label(16, Fonts.medium, C.white(0.85f))
    /** Main thread, after the banner is shown or hidden. */
    var onChange: (() -> Unit)? = null

    private val art = FrameLayout(ctx)
    private val glassIdle = Glass(this, radius = pxf(36), bottomOnly = true)
    private val glassLit = Glass(this, radius = pxf(36), bottomOnly = true, top = 0.28f, bottom = 0.16f)
    private val ring = android.graphics.drawable.GradientDrawable().apply { setColor(0); cornerRadius = pxf(16); setStroke(px(3), C.WHITE) }

    init {
        orientation = VERTICAL
        setPadding(px(24), px(26), px(24), px(16))
        background = glassIdle
        noClip()
        visibility = View.GONE

        val headRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        headRow.addView(headIcon, LayoutParams(px(22), px(22)).apply { marginEnd = px(10) })
        headRow.addView(head)
        addView(headRow)

        button.ownFocus()
        button.orientation = HORIZONTAL
        button.gravity = Gravity.CENTER_VERTICAL
        button.noClip()
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
        line.maxLines = 1
        line.ellipsize = android.text.TextUtils.TruncateAt.END
        text.addView(line, LayoutParams(MATCH, WRAP).apply { topMargin = px(6) })
        track.background = pill(C.white(0.28f), pxf(2))
        fill.background = pill(C.WHITE, pxf(2))
        track.addView(fill, FrameLayout.LayoutParams(0, MATCH))
        progressRow.addView(track, LayoutParams(0, px(4), 1f).apply { marginEnd = px(12) })
        progressRow.addView(left)
        text.addView(progressRow, LayoutParams(MATCH, WRAP).apply { topMargin = px(10) })
        button.addView(text, LayoutParams(0, WRAP, 1f).apply { marginStart = px(20) })
        // Focus: a white ring on the art and a brighter window. Nothing moves or grows, so nothing gets cut off.
        button.setOnFocusChangeListener { _, f ->
            art.foreground = if (f) ring else null
            background = if (f) glassLit else glassIdle
            line.setTextColor(if (f) C.white(0.85f) else C.DIM)
            head.setTextColor(if (f) C.WHITE else C.white(0.78f))
        }
        addView(button, LayoutParams(MATCH, WRAP).apply { topMargin = px(16) })

        addView(View(ctx).apply { background = pill(C.white(0.4f), pxf(3)) },
            LayoutParams(px(64), px(6)).apply { topMargin = px(16); gravity = Gravity.CENTER_HORIZONTAL })
    }

    /**
     * The show left part-way, and which episode: "Season 3, Episode 7 · Red Sky at Night", with the progress bar
     * when the app says how long it is. An app that sends nothing (Hulu) asks to be told, once: hold OK.
     */
    fun setContinue(play: Play, show: Show?, ep: Catalog.Episode?, art: Bitmap?, appLabel: String, onClick: () -> Unit) {
        headIcon.setImageResource(R.drawable.ic_play_fill)
        head.text = "Continue watching"
        val known = show?.name ?: play.showGuess
        name.text = known.ifBlank { "On $appLabel" }
        // The episode on one line, its name (when known) on the next.
        line.text = when {
            ep != null -> "Season ${ep.season}, Episode ${ep.number}" + (if (ep.name.isNotBlank()) "\n${ep.name}" else "")
            known.isBlank() -> "Hold OK to name it"
            else -> "Hold OK to add the episode"
        }
        line.maxLines = 2
        line.gone(false)
        badge.gone(true)
        val frac = if (play.duration > 0) (play.position.toFloat() / play.duration).coerceIn(0f, 1f) else 0f
        left.text = ""
        left.gone(true)
        track.gone(play.duration <= 0)
        progressRow.gone(play.duration <= 0)
        progressRow.post { fill.layoutParams = (fill.layoutParams as FrameLayout.LayoutParams).apply { width = (track.width * frac).toInt() } }
        show(art, onClick)
    }

    /** The show watched on the same weekday for weeks. */
    fun setWeekly(weekly: Weekly, show: Show?, art: Bitmap?, onClick: () -> Unit) {
        val day = DateFormatSymbols().weekdays[weekly.weekday]
        headIcon.setImageResource(R.drawable.ic_calendar)
        head.text = "You watch every $day"
        name.text = show?.name ?: weekly.play.showGuess
        val tonight = weekly.daysUntil == 0
        badge.gone(!tonight)
        val at = show?.time?.let(::clock) ?: clock(weekly.minuteOfDay)
        val where = show?.network ?: ""
        line.text = listOf(if (tonight) "Tonight" else day, at, where).filter { it.isNotBlank() }.joinToString(" · ")
        line.maxLines = 1
        line.gone(false)
        left.gone(false)
        progressRow.gone(true)
        show(art, onClick)
    }

    private fun show(art: Bitmap?, onClick: () -> Unit) {
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
}
