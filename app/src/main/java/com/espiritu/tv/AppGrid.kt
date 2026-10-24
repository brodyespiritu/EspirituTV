package com.espiritu.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The apps on Home, as cards five to a row (Apple TV style): your pinned apps first, then everything else,
 * then Inputs and Settings. The focused card grows 1.1× with a white ring and a soft glow, and its name shows
 * beneath it; the others hold still. [Host.rowFocused] tells Home which row is in focus so it can scroll.
 */
class AppGrid(ctx: Context, private val host: Host) : LinearLayout(ctx) {

    interface Host {
        fun openApp(app: AppEntry)
        fun showAppOptions(app: AppEntry)
        fun showInputs()
        fun showSettings()
        /** Focus moved onto an app card (null for Inputs / Settings). */
        fun appFocused(app: AppEntry?)
        /** The pinned apps were reordered (move mode). */
        fun appsReordered(pkgs: List<String>)
        /** Focus moved to row [row] (0 = the row under the hero). */
        fun rowFocused(row: Int)
    }

    private val cards = ArrayList<Card>()
    private var pinned: List<AppEntry> = emptyList()
    private var rest: List<AppEntry> = emptyList()
    private var running: String? = null
    private var moving: Card? = null
    private val glow: Bitmap by lazy { cardGlow(px(CARD_W), px(CARD_H), pxf(RADIUS)) }
    private val ringArt: Bitmap by lazy { cardRing() }

    /** The card focus was last on, so coming back down from the hero lands in the same place. */
    var lastFocused: View? = null; private set
    var focusedRow = 0; private set

    val firstCard: View? get() = cards.firstOrNull()?.view

    init {
        orientation = VERTICAL
        noClip()
    }

    fun setApps(pinned: List<AppEntry>, rest: List<AppEntry>) {
        this.pinned = pinned
        this.rest = rest
        rebuild()
    }

    /** A small dot under the app whose show is in Continue watching. */
    fun setRunning(pkg: String?) {
        running = pkg
        cards.forEach { it.dot.gone(it.app == null || it.app.pkg != pkg) }
    }

    private fun rebuild() {
        val focusedPkg = cards.firstOrNull { it.view.hasFocus() }?.app?.pkg
        removeAllViews()
        cards.clear()
        val all = ArrayList<Card>()
        pinned.forEach { all += Card(context, it) }
        rest.forEach { all += Card(context, it) }
        all += Card(context, null, R.drawable.ic_tv_fill, "Inputs") { host.showInputs() }
        all += Card(context, null, R.drawable.ic_gear_fill, "Settings") { host.showSettings() }
        var row: LinearLayout? = null
        all.forEachIndexed { i, c ->
            if (i % COLS == 0) {
                row = LinearLayout(context).apply { orientation = HORIZONTAL; noClip() }
                addView(row, LayoutParams(MATCH, px(ROW_H)))
            }
            row!!.addView(c.view, LayoutParams(px(CARD_W), px(ROW_H)).apply { if (i % COLS > 0) marginStart = px(GAP) })
            c.index = i
            cards += c
        }
        cards.forEach { it.dot.gone(it.app == null || it.app.pkg != running) }
        focusedPkg?.let { p -> cards.firstOrNull { it.app?.pkg == p }?.view?.requestFocus() }
    }

    fun startMove(pkg: String): Boolean {
        val c = cards.firstOrNull { it.app?.pkg == pkg && it.index < pinned.size } ?: return false
        moving = c
        c.view.requestFocus()
        c.label.text = "◀ ▶ to move · OK when done"
        return true
    }

    fun finishMove() {
        val m = moving ?: return
        moving = null
        m.label.text = m.name
        host.appsReordered(pinned.map { it.pkg })
    }

    /** Swaps the moving card with its neighbour among the pinned apps; false when the key wasn't ours. */
    private fun moveKey(c: Card, key: Int): Boolean {
        if (moving !== c) return false
        val i = c.index
        val j = when (key) { KeyEvent.KEYCODE_DPAD_LEFT -> i - 1; KeyEvent.KEYCODE_DPAD_RIGHT -> i + 1; else -> return true }
        if (j < 0 || j >= pinned.size) return true
        val list = pinned.toMutableList()
        java.util.Collections.swap(list, i, j)
        pinned = list
        rebuild()
        val moved = cards[j]
        moving = moved
        moved.view.requestFocus()
        moved.label.text = "◀ ▶ to move · OK when done"
        return true
    }

    /** One card: the pre-rendered, pre-rounded art, a ring and glow on focus, the name beneath. */
    private inner class Card(ctx: Context, val app: AppEntry?, iconRes: Int = 0, val name: String = app?.label ?: "", val action: (() -> Unit)? = null) {
        val view = FrameLayout(ctx)
        val art = ImageView(ctx)
        val halo = ImageView(ctx)
        val label: TextView = ctx.label(22, Fonts.medium, text = name)
        val dot = View(ctx)
        var index = 0

        init {
            view.ownFocus()
            view.noClip()
            val w = px(CARD_W); val h = px(CARD_H)
            halo.setImageBitmap(glow)
            halo.alpha = 0f
            val pad = (glow.width - w) / 2
            view.addView(halo, FrameLayout.LayoutParams(glow.width, glow.height).apply { leftMargin = -pad; topMargin = -pad })
            art.scaleType = ImageView.ScaleType.FIT_XY
            if (app != null) art.setImageBitmap(app.card)
            else {
                art.setImageResource(iconRes)
                art.scaleType = ImageView.ScaleType.CENTER_INSIDE
                art.setPadding(w / 2 - px(28), h / 2 - px(28), w / 2 - px(28), h / 2 - px(28))
                art.background = Glass(art, radius = pxf(RADIUS), top = 0.22f, bottom = 0.12f)
            }
            view.addView(art, FrameLayout.LayoutParams(w, h))
            // Ring: drawn over the art, only while focused.
            art.foreground = null
            label.gravity = Gravity.CENTER
            label.maxLines = 1
            label.ellipsize = android.text.TextUtils.TruncateAt.END
            label.alpha = 0f
            view.addView(label, FrameLayout.LayoutParams(w + px(60), WRAP).apply { topMargin = h + px(22); leftMargin = -px(30) })
            dot.background = pill(C.white(0.9f), pxf(3))
            dot.visibility = View.GONE
            view.addView(dot, FrameLayout.LayoutParams(px(6), px(6)).apply { leftMargin = w / 2 - px(3); topMargin = h + px(10) })
            view.pivotX = w / 2f
            view.pivotY = h / 2f
            view.setOnFocusChangeListener { _, f ->
                if (f) { lastFocused = view; host.appFocused(app); val r = index / COLS; if (r != focusedRow) { focusedRow = r; host.rowFocused(r) } }
                art.foreground = if (f) BitmapDrawable(view.resources, ringArt) else null
                view.animate().scaleX(if (f) 1.1f else 1f).scaleY(if (f) 1.1f else 1f).setDuration(160).setInterpolator(DecelerateInterpolator()).start()
                halo.animate().alpha(if (f) 1f else 0f).setDuration(160).start()
                label.animate().alpha(if (f) 1f else 0f).setDuration(160).start()
                if (!f && moving === this) finishMove()
            }
            view.setOnClickListener {
                if (moving != null) finishMove()
                else if (app != null) host.openApp(app) else action?.invoke()
            }
            if (app != null) {
                view.setOnLongClickListener { host.showAppOptions(app); true }
                view.setOnKeyListener { _, key, ev ->
                    when {
                        ev.action != KeyEvent.ACTION_DOWN -> false
                        key == KeyEvent.KEYCODE_MENU -> { host.showAppOptions(app); true }
                        else -> moveKey(this, key)
                    }
                }
            }
        }
    }

    /** The 4 px white focus ring in the card's shape, rendered once at the focused (1.1×) size so it stays crisp. */
    private fun cardRing(): Bitmap {
        val w = px(CARD_W * 1.1f)
        val h = w * CARD_H / CARD_W
        val k = w.toFloat() / px(CARD_W)
        val stroke = pxf(4) * k
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rect = RectF(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2)
        Canvas(b).drawPath(Continuous.path(rect, pxf(RADIUS) * k - stroke / 2),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = C.WHITE })
        return b
    }

    companion object {
        const val COLS = 5
        const val CARD_W = AppLoader.CARD_W
        const val CARD_H = AppLoader.CARD_H
        const val GAP = 50
        /** A row: the card, the name beneath, and air before the next row. */
        const val ROW_H = CARD_H + 82
        const val RADIUS = AppLoader.CARD_RADIUS

        /** A soft shadow under and a faint white glow around a card, rendered once (no live shadows on this GPU). */
        fun cardGlow(w: Int, h: Int, r: Float): Bitmap {
            val pad = (h * 0.5f).toInt()
            val b = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val rect = RectF(pad.toFloat(), pad.toFloat(), (pad + w).toFloat(), (pad + h).toFloat())
            c.drawPath(Continuous.path(RectF(rect).apply { offset(0f, h * 0.12f) }, r),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.shade(0.75f); maskFilter = BlurMaskFilter(h * 0.22f, BlurMaskFilter.Blur.NORMAL) })
            c.drawPath(Continuous.path(rect, r),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.white(0.35f); maskFilter = BlurMaskFilter(h * 0.14f, BlurMaskFilter.Blur.OUTER) })
            return b
        }
    }
}
