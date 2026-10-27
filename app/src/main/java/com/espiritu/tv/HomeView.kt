package com.espiritu.tv

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Home, at 1920 × 1080 canvas px: art filling the screen (on its own layer behind this window, see [BackLayer]),
 * the wordmark top-left, the avatar top-right (with the "Update available" chip beside it when there is one),
 * the hero block above the apps, and the apps as cards five to a row.
 * Moving down into the second row and beyond scrolls the cards up while the hero fades and the art dims,
 * the way Apple TV's home screen does. A loading screen covers everything until the first art is on screen.
 */
class HomeView(ctx: Context, gridHost: AppGrid.Host) : FrameLayout(ctx) {
    val back = BackLayer(ctx).apply {
        onReady = { this@HomeView.background = null; artReady() }
        onLost = { this@HomeView.background = sceneBackground() }
    }
    private val dim = View(ctx).apply { setBackgroundColor(C.shade(0.62f)); alpha = 0f }
    val avatar = Avatar(ctx, 60)
    /** A 40 px glass pill, scaled from its right edge so focus never covers the avatar. */
    val update = PillButton(ctx, primary = false, iconRes = R.drawable.ic_download, text = "Update available",
        height = 40, textSize = 18, pad = 18, iconSize = 18, anchorEnd = true)
    val hero = HeroView(ctx)
    val grid = AppGrid(ctx, gridHost)
    private val credit = ctx.label(16, Fonts.medium, C.white(0.55f), "Art: TMDB")
    private val dots = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val splash = SplashView(ctx)
    private var splashUp = false

    init {
        noClip()
        // Only until the first still arrives; after that this window is see-through where the art layer shows.
        background = sceneBackground()
        addView(back, LayoutParams(MATCH, MATCH))
        addView(dim, LayoutParams(MATCH, MATCH))

        val wordmark = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        wordmark.addView(ctx.label(38, Fonts.semibold, text = "Espiritu").apply { letterSpacing = -0.02f })
        wordmark.addView(ctx.label(38, Fonts.light, text = "TV").apply { letterSpacing = -0.02f })
        addView(wordmark, LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(px(120), px(64), 0, 0) })

        addView(avatar, LayoutParams(px(60), px(60), Gravity.TOP or Gravity.END).apply { setMargins(0, px(64), px(120), 0) })
        addView(update, LayoutParams(WRAP, px(40), Gravity.TOP or Gravity.END).apply { setMargins(0, px(74), px(196), 0) })
        update.visibility = View.GONE
        addView(hero, LayoutParams(px(960), WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(px(120), 0, 0, px(HERO_BOTTOM)) })
        addView(credit, LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, px(120), px(HERO_BOTTOM + 30)) })
        addView(dots, LayoutParams(WRAP, px(8), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, px(120), px(HERO_BOTTOM)) })
        addView(grid, LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(px(120), px(GRID_TOP), 0, 0) })
        credit.gone(true)
    }

    /** The loading screen (cold start only): stays until the first art is on screen, at most 8 s. */
    fun showSplash() {
        if (splash.parent == null) addView(splash, LayoutParams(MATCH, MATCH))
        splash.visibility = View.VISIBLE
        splash.alpha = 1f
        splashUp = true
        postDelayed({ artReady() }, 8_000)
    }

    private fun artReady() {
        if (!splashUp) return
        splashUp = false
        splash.dismiss { removeView(splash) }
    }

    /** Crossfades the background to a composed still (see Backdrop.compose). [creditText] names the art's source; null hides it. */
    fun setArt(frame: Bitmap, creditText: String?) {
        credit.text = creditText ?: ""
        credit.gone(creditText == null)
        // The placeholder gradient stays until the art layer says the still is really on screen.
        back.show(frame)
    }

    /**
     * Row 0 sits under the hero. Deeper rows scroll the cards up so the focused row sits near the top,
     * while the hero and paging fade and the art dims behind the cards.
     */
    fun scrollToRow(row: Int) {
        val deep = row > 0
        val ty = if (!deep) 0f else -pxf(GRID_TOP - 220 + (row - 1) * AppGrid.ROW_H)
        val ease = DecelerateInterpolator(1.5f)
        grid.animate().translationY(ty).setDuration(320).setInterpolator(ease).start()
        for (v in listOf(hero, credit, dots, update)) v.animate().alpha(if (deep) 0f else 1f).setDuration(240).start()
        dim.animate().alpha(if (deep) 1f else 0f).setDuration(320).start()
        hero.descendantFocusability = if (deep) FOCUS_BLOCK_DESCENDANTS else FOCUS_AFTER_DESCENDANTS
    }

    /** Whether a newer version waits (the chip beside the avatar; Settings keeps its row too). */
    var updateAvailable = false
        set(v) { field = v; update.gone(!v) }

    /** Paging dots for the slideshow; count 0 hides them and drops the credit back down. */
    fun setDots(count: Int, index: Int) {
        dots.removeAllViews()
        for (i in 0 until count) {
            val on = i == index
            dots.addView(View(context).apply { background = pill(if (on) C.WHITE else C.white(0.4f), pxf(4)) },
                LinearLayout.LayoutParams(px(if (on) 28 else 8), px(8)).apply { if (i > 0) marginStart = px(10) })
        }
        dots.gone(count == 0)
        (credit.layoutParams as LayoutParams).bottomMargin = px(if (count == 0) HERO_BOTTOM else HERO_BOTTOM + 30)
        credit.requestLayout()
    }

    fun showEmpty() {
        hero.clear()
        setDots(0, 0)
        setArt(Backdrop.emptyArt(), null)
    }

    /** The card to land on when coming down from the hero (row 0, where focus last was). */
    private fun firstRowTarget(): View? = grid.lastFocused?.takeIf { it.isShown && grid.focusedRow == 0 } ?: grid.firstCard

    /** D-pad routing between the regions: update chip / avatar on top, hero buttons, the card rows. */
    override fun focusSearch(focused: View?, direction: Int): View? {
        val heroOn = hero.visibility == View.VISIBLE
        val updateOn = update.visibility == View.VISIBLE
        val inHero = focused != null && isIn(focused, hero)
        val inGrid = focused != null && isIn(focused, grid)
        when (direction) {
            FOCUS_DOWN -> {
                if (inHero) return firstRowTarget() ?: super.focusSearch(focused, direction)
                if (focused === avatar || focused === update) return if (heroOn) hero.primary else firstRowTarget()
            }
            FOCUS_UP -> {
                if (inGrid && grid.focusedRow == 0) return if (heroOn) hero.primary else avatar
                if (inHero) return avatar
            }
            FOCUS_LEFT -> if (focused === avatar && updateOn) return update
            FOCUS_RIGHT -> if (focused === update) return avatar
        }
        return super.focusSearch(focused, direction)
    }

    private fun isIn(v: View, group: View): Boolean {
        var p: View? = v
        while (p != null) { if (p === group) return true; p = p.parent as? View }
        return false
    }

    private companion object {
        /** The first card row's top edge. */
        const val GRID_TOP = 800
        /** The hero's bottom edge sits 96 px above the cards. */
        const val HERO_BOTTOM = 1080 - GRID_TOP + 96
    }
}
