package com.espiritu.tv

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The text block at the bottom left of Home: who it's from, the show's name, what's known about it, and two buttons.
 * Two layouts, as on the canvas: Continue watching (episode, progress, Resume / More info)
 * and Popular (genre line, summary, Play / Add to Up Next).
 */
class HeroView(ctx: Context) : LinearLayout(ctx) {
    private val mark = ImageView(ctx)
    private val source = ctx.label(24, Fonts.medium, C.white(0.85f))
    private val chip: TextView = ctx.glassChip("New series", 40, 18, 16)
    private val title = ctx.label(88, Fonts.bold).apply {
        letterSpacing = -0.03f
        maxLines = 2
        setLineSpacing(0f, 0.98f)
    }
    /** The show's own title logo (as on the canvas), fitted into 620 × 180 from the bottom-left; the text title is the fallback. */
    private val logo = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY; visibility = View.GONE }
    private val episodeRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val episode = ctx.label(26, Fonts.regular, C.white(0.85f)).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val track = FrameLayout(ctx)
    private val fill = View(ctx)
    private val left = ctx.label(26, Fonts.regular, C.white(0.85f))
    private val meta = ctx.label(26, Fonts.medium, C.white(0.85f))
    private val blurb = ctx.label(24, Fonts.regular, C.white(0.7f)).apply {
        setLineSpacing(0f, 1.4f)
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        maxWidth = px(760)
    }
    private val buttons = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val primary = PillButton(ctx, primary = true, iconRes = R.drawable.ic_play_fill, text = "Play")
    val secondary = PillButton(ctx, primary = false, text = "More info")

    init {
        orientation = VERTICAL
        noClip()
        visibility = View.GONE

        val top = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; noClip() }
        mark.adjustViewBounds = true
        mark.scaleType = ImageView.ScaleType.FIT_START
        top.addView(mark, LayoutParams(WRAP, px(44)).apply { marginEnd = px(16) })
        top.addView(source)
        top.addView(chip, LayoutParams(WRAP, px(40)).apply { marginStart = px(16) })
        addView(top)

        addView(title, LayoutParams(MATCH, WRAP).apply { topMargin = px(26) })
        addView(logo, LayoutParams(px(620), px(180)).apply { topMargin = px(26) })

        // A long episode title gives way; the bar and the minutes keep their size.
        episodeRow.addView(episode, LayoutParams(0, WRAP, 1f).apply { marginEnd = px(22) })
        track.background = pill(C.white(0.28f), pxf(3))
        fill.background = pill(C.WHITE, pxf(3))
        track.addView(fill, FrameLayout.LayoutParams(0, MATCH))
        episodeRow.addView(track, LayoutParams(px(240), px(6)).apply { marginEnd = px(22) })
        left.maxLines = 1
        episodeRow.addView(left)
        addView(episodeRow, LayoutParams(px(900), WRAP).apply { topMargin = px(28) })

        addView(meta, LayoutParams(WRAP, WRAP).apply { topMargin = px(26) })
        addView(blurb, LayoutParams(WRAP, WRAP).apply { topMargin = px(12) })

        buttons.noClip()
        buttons.addView(primary, LayoutParams(WRAP, px(64)))
        buttons.addView(secondary, LayoutParams(WRAP, px(64)).apply { marginStart = px(18) })
        addView(buttons, LayoutParams(WRAP, WRAP).apply { topMargin = px(38) })
    }

    /** The logo next to the source line: a brand mark when we have one, else the app's own tile. */
    private fun setSource(brand: Brand?, tile: Bitmap?, text: String) {
        val lp = mark.layoutParams as LayoutParams
        if (brand != null) {
            mark.setImageResource(brand.heroMark)
            lp.height = px(brand.heroMarkHeight)
            lp.width = WRAP
        } else {
            mark.setImageBitmap(tile)
            lp.height = px(44)
            lp.width = px(44)
        }
        mark.layoutParams = lp
        mark.gone(brand == null && tile == null)
        source.text = text
    }

    /** The title: the show's logo fitted inside 620 × 180 (as on the canvas), or the name in type. */
    private fun setTitle(name: String, logoArt: Bitmap?, hideText: Boolean = false) {
        if (logoArt != null && logoArt.width > 0 && logoArt.height > 0) {
            val k = minOf(pxf(620) / logoArt.width, pxf(180) / logoArt.height)
            logo.layoutParams = (logo.layoutParams as LayoutParams).apply {
                width = (logoArt.width * k).toInt(); height = (logoArt.height * k).toInt()
            }
            logo.setImageBitmap(logoArt)
            logo.contentDescription = name
            logo.visibility = View.VISIBLE
            title.gone(true)
        } else {
            logo.setImageDrawable(null)
            logo.visibility = View.GONE
            title.text = name
            title.gone(hideText)
        }
    }

    fun showContinue(brand: Brand?, tile: Bitmap?, show: Show?, play: Play) {
        setSource(brand, tile, "Continue watching")
        chip.gone(true)
        setTitle(show?.name ?: play.showGuess, null)
        val ep = if (show != null && !show.name.equals(play.title, ignoreCase = true)) play.title else ""
        episode.text = ep
        episode.gone(ep.isEmpty())
        val frac = if (play.duration > 0) (play.position.toFloat() / play.duration).coerceIn(0f, 1f) else 0f
        fill.layoutParams = (fill.layoutParams as FrameLayout.LayoutParams).apply { width = (pxf(240) * frac).toInt() }
        val mins = if (play.duration > 0) ((play.duration - play.position) / 60_000).coerceAtLeast(1) else 0
        left.text = if (mins > 0) "$mins min left" else ""
        track.gone(play.duration <= 0)
        episodeRow.gone(ep.isEmpty() && play.duration <= 0)
        meta.gone(true)
        blurb.gone(true)
        primary.text = "Resume"
        secondary.text = "More info"
        (buttons.layoutParams as LayoutParams).topMargin = px(38)
        visibility = View.VISIBLE
    }

    /** A show from the service's lineup: brand-new series say so ("New on …" and a chip), the rest are "Popular on …". */
    fun showPopular(brand: Brand?, tile: Bitmap?, providerName: String, show: Show, logoArt: Bitmap?, isNew: Boolean, inUpNext: Boolean) {
        setSource(brand, tile, if (isNew) "New on $providerName" else "Popular on $providerName")
        chip.text = "New series"
        chip.gone(!isNew)
        setTitle(show.name, logoArt, hideText = show.titleInArt)
        episodeRow.gone(true)
        val bits = ArrayList<String>()
        bits += show.genres.take(2)
        show.year?.let { bits += it }
        if (show.days.isNotEmpty()) bits += "New episodes " + show.days.joinToString("/") { it + "s" }
        meta.text = bits.joinToString(" · ")
        meta.gone(bits.isEmpty())
        blurb.text = show.summary
        blurb.gone(show.summary.isBlank())
        primary.text = "Play"
        setUpNext(inUpNext)
        (buttons.layoutParams as LayoutParams).topMargin = px(34)
        visibility = View.VISIBLE
    }

    fun setUpNext(saved: Boolean) {
        secondary.text = if (saved) "✓ In Up Next" else "+ Add to Up Next"
    }

    fun clear() { visibility = View.GONE }
}
