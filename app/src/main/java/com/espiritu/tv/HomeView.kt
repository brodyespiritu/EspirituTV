package com.espiritu.tv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * The Home board from the canvas, at 1920 × 1080 canvas px:
 * art filling the screen under three scrims, the wordmark top-left, the avatar top-right (with the
 * "Update available" chip beside it when there is one), the weekly banner hanging from the top edge,
 * the hero block at bottom-left and the dock at the bottom.
 */
class HomeView(ctx: Context, dockHost: Dock.Host) : FrameLayout(ctx) {
    /** The trailer, *under* the stills: it's revealed by fading the still away, with the scrims laid over it. */
    val trailer = TrailerView(ctx)
    private val artBack = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val artFront = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val trailerScrim = ImageView(ctx).apply { visibility = View.GONE }
    private var mixAnim: ValueAnimator? = null
    private var shown: Bitmap? = null
    val avatar = Avatar(ctx, 60)
    /** A 40 px glass pill like the hero's "Top 10 today" chip, scaled from its right edge so focus never covers the avatar. */
    val update = PillButton(ctx, primary = false, iconRes = R.drawable.ic_download, text = "Update available",
        height = 40, textSize = 18, pad = 18, iconSize = 18, anchorEnd = true)
    val banner = BannerView(ctx)
    val hero = HeroView(ctx)
    val dock = Dock(ctx, dockHost)
    private val credit = ctx.label(16, Fonts.medium, C.white(0.55f), "Art: TMDB")
    private var artCredit = "Art: TMDB"
    private val dots = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    init {
        noClip()
        // Only until the first composed frame arrives; after that the art is the only full-screen layer.
        background = sceneBackground()
        addView(trailer, LayoutParams(MATCH, MATCH))
        addView(artBack, LayoutParams(MATCH, MATCH))
        addView(artFront, LayoutParams(MATCH, MATCH))
        addView(trailerScrim, LayoutParams(MATCH, MATCH))

        val wordmark = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        wordmark.addView(ctx.label(38, Fonts.semibold, text = "Espiritu").apply { letterSpacing = -0.02f })
        wordmark.addView(ctx.label(38, Fonts.light, text = "TV").apply { letterSpacing = -0.02f })
        addView(wordmark, LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(px(120), px(64), 0, 0) })

        addView(avatar, LayoutParams(px(60), px(60), Gravity.TOP or Gravity.END).apply { setMargins(0, px(64), px(120), 0) })
        addView(update, LayoutParams(WRAP, px(40), Gravity.TOP or Gravity.END).apply { setMargins(0, px(74), px(196), 0) })
        update.visibility = View.GONE
        banner.onChange = { syncUpdate() }
        addView(banner, LayoutParams(px(460), WRAP, Gravity.TOP or Gravity.START).apply { marginStart = px(1249) })
        addView(hero, LayoutParams(px(960), WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(px(120), 0, 0, px(272)) })
        addView(credit, LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, px(120), px(276)) })
        addView(dots, LayoutParams(WRAP, px(8), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, px(120), px(276)) })
        addView(dock, LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = px(40) })
        credit.gone(true)
    }

    /** Crossfades to a composed frame (see Backdrop.compose). [creditText] names the art's source; null hides it. */
    fun setArt(frame: Bitmap, creditText: String?) {
        artCredit = creditText ?: ""
        if (trailerScrim.visibility != View.VISIBLE) {
            credit.text = artCredit
            credit.gone(creditText == null)
        }
        if (frame === shown) return
        val first = shown == null
        shown = frame
        if (first) {
            // First frame: no fade, and drop the gradient so nothing is painted under the art.
            artFront.setImageBitmap(frame)
            background = null
            return
        }
        artBack.setImageDrawable(artFront.drawable)
        artBack.alpha = 1f
        artFront.setImageBitmap(frame)
        artFront.alpha = 0f
        artFront.visibility = View.VISIBLE
        artFront.animate().alpha(1f).setDuration(420).withEndAction { artBack.setImageDrawable(null) }.start()
    }

    /**
     * The trailer is playing underneath: fade the still away and the scrims in over 600 ms, while every glass
     * surface turns from frosted-still to smoked glass the video plays through. Then the still stops drawing.
     */
    fun revealTrailer() {
        if (trailerScrim.drawable == null) trailerScrim.setImageBitmap(Backdrop.scrimsOnly())
        artBack.animate().cancel()
        artBack.setImageDrawable(null)
        trailerScrim.alpha = 0f
        trailerScrim.visibility = View.VISIBLE
        credit.text = "Trailer: YouTube"
        credit.gone(false)
        artFront.animate().cancel()
        artFront.animate().alpha(0f).setDuration(600).withEndAction { if (trailer.playing) artFront.visibility = View.INVISIBLE }.start()
        trailerScrim.animate().alpha(1f).setDuration(600).start()
        animateMix(1f)
    }

    /** The reverse: the still fades back over the trailer and the glass frosts over again; then [then] (stop the player). */
    fun concealTrailer(then: () -> Unit) {
        artFront.animate().cancel()
        artFront.visibility = View.VISIBLE
        artFront.animate().alpha(1f).setDuration(600).withEndAction {
            trailerScrim.visibility = View.GONE
            credit.text = artCredit
            credit.gone(artCredit.isEmpty())
            then()
        }.start()
        trailerScrim.animate().alpha(0f).setDuration(600).start()
        animateMix(0f)
    }

    /** No trailer, right now (Home going away). */
    fun resetTrailer() {
        mixAnim?.cancel()
        Backdrop.setVideoMix(0f)
        artFront.animate().cancel()
        artFront.alpha = 1f
        artFront.visibility = View.VISIBLE
        trailerScrim.animate().cancel()
        trailerScrim.visibility = View.GONE
        credit.text = artCredit
        credit.gone(artCredit.isEmpty())
    }

    private fun animateMix(to: Float) {
        mixAnim?.cancel()
        mixAnim = ValueAnimator.ofFloat(Backdrop.videoMix, to).apply {
            duration = 600
            addUpdateListener { Backdrop.setVideoMix(it.animatedValue as Float) }
            start()
        }
    }

    /** Whether a newer version waits; the chip shows while the banner isn't using that corner (Settings keeps its row). */
    var updateAvailable = false
        set(v) { field = v; syncUpdate() }

    private fun syncUpdate() { update.gone(!updateAvailable || banner.visibility == View.VISIBLE) }

    /** Paging dots for the popular rotation; count 0 hides them and drops the credit back down. */
    fun setDots(count: Int, index: Int) {
        dots.removeAllViews()
        for (i in 0 until count) {
            val on = i == index
            dots.addView(View(context).apply { background = pill(if (on) C.WHITE else C.white(0.4f), pxf(4)) },
                LinearLayout.LayoutParams(px(if (on) 28 else 8), px(8)).apply { if (i > 0) marginStart = px(10) })
        }
        dots.gone(count == 0)
        (credit.layoutParams as LayoutParams).bottomMargin = px(if (count == 0) 276 else 306)
        credit.requestLayout()
    }

    fun showEmpty() {
        hero.clear()
        setDots(0, 0)
        setArt(Backdrop.emptyArt(), null)
    }

    /** D-pad routing between the four regions: banner / update chip / avatar on top, hero buttons, the dock. */
    override fun focusSearch(focused: View?, direction: Int): View? {
        val heroOn = hero.visibility == View.VISIBLE
        val bannerOn = banner.visibility == View.VISIBLE
        val updateOn = update.visibility == View.VISIBLE
        val inHero = focused != null && isIn(focused, hero)
        val inDock = focused != null && isIn(focused, dock)
        val inBanner = focused != null && isIn(focused, banner)
        val dockTarget = dock.lastFocused?.takeIf { it.isShown } ?: dock.firstTile
        val topTarget = if (bannerOn) banner.button else avatar
        when (direction) {
            FOCUS_DOWN -> {
                if (inHero) return dockTarget ?: super.focusSearch(focused, direction)
                if (inBanner || focused === avatar || focused === update) return if (heroOn) hero.primary else dockTarget
            }
            FOCUS_UP -> {
                if (inDock) return if (heroOn) hero.primary else topTarget
                if (inHero) return topTarget
            }
            FOCUS_LEFT -> if (focused === avatar) {
                if (updateOn) return update
                if (bannerOn) return banner.button
            }
            FOCUS_RIGHT -> if (inBanner || focused === update) return avatar
        }
        return super.focusSearch(focused, direction)
    }

    private fun isIn(v: View, group: View): Boolean {
        var p: View? = v
        while (p != null) { if (p === group) return true; p = p.parent as? View }
        return false
    }
}
