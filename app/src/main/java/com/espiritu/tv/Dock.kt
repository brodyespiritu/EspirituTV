package com.espiritu.tv

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The dock from the canvas: a frosted pill holding 96 px squircle app tiles, a divider, then glass tiles for
 * All apps, Inputs and Settings. The focused tile is unmistakable: it grows to 1.5× from its bottom edge,
 * gets a white ring and a glowing halo, and its name floats above it, while the other tiles dim a little.
 */
class Dock(ctx: Context, private val host: Host) : FrameLayout(ctx) {

    interface Host {
        fun openApp(app: AppEntry)
        fun showAppOptions(app: AppEntry)
        fun showAllApps()
        fun showInputs()
        fun showSettings()
        /** Focus moved onto an app tile (null for the glass tiles). */
        fun dockFocused(app: AppEntry?)
        fun dockReordered(pkgs: List<String>)
    }

    private val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
    private val tip: TextView = ctx.glassChip("", 48, 21, 22).apply { visibility = View.INVISIBLE }
    private val halo = ImageView(ctx).apply { visibility = View.INVISIBLE; setImageBitmap(Halo.glow(px(TILE * 2.6f), px(TILE * FOCUS))) }
    private val ringArt = Halo.ring(px(TILE * FOCUS), pxf(4f))
    private val items = ArrayList<Item>()
    private var apps: List<AppEntry> = emptyList()
    private var running: String? = null
    private var moving: Item? = null

    /** The app tile focus was last on, so Down from the hero returns to the same place. */
    var lastFocused: View? = null; private set

    init {
        noClip()
        row.noClip()
        row.setPadding(px(18), px(14), px(18), px(14))
        row.background = Glass(row, radius = pxf(38))
        addView(halo, LayoutParams(px(TILE * 2.6f), px(TILE * 2.6f)))
        addView(row, LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM))
        addView(tip, LayoutParams(WRAP, px(48)))
    }

    val firstTile: View? get() = items.firstOrNull()?.view

    fun setApps(apps: List<AppEntry>, running: String?) {
        this.apps = apps
        this.running = running
        rebuild()
    }

    fun setRunning(pkg: String?) {
        running = pkg
        items.forEach { it.dot.gone(it.app == null || it.app.pkg != pkg) }
    }

    private fun rebuild() {
        val focusedPkg = (row.focusedChild as? Item)?.app?.pkg
        row.removeAllViews()
        items.clear()
        for (a in apps) addItem(Item(context, a))
        row.addView(View(context).apply { setBackgroundColor(C.white(0.22f)) },
            LinearLayout.LayoutParams(px(2), px(60)).apply { setMargins(px(4), 0, px(4), px(18)) })
        addItem(Item(context, null, R.drawable.ic_grid_fill, "All apps") { host.showAllApps() })
        addItem(Item(context, null, R.drawable.ic_tv_fill, "Inputs") { host.showInputs() })
        addItem(Item(context, null, R.drawable.ic_gear_fill, "Settings") { host.showSettings() })
        items.forEach { it.dot.gone(it.app == null || it.app.pkg != running) }
        focusedPkg?.let { p -> items.firstOrNull { it.app?.pkg == p }?.view?.requestFocus() }
    }

    private fun addItem(item: Item) {
        val lp = LinearLayout.LayoutParams(px(TILE), px(TILE))
        if (items.isNotEmpty()) lp.marginStart = px(16)
        row.addView(item.view, lp)
        items += item
    }

    private fun relayout(focused: Item?) {
        for (it in items) {
            val on = it === focused
            val scale = if (on) FOCUS else 1f
            it.view.animate().scaleX(scale).scaleY(scale).translationY(-(scale - 1f) * 46f * D.k)
                .alpha(if (focused == null || on) 1f else 0.7f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
            it.ring.gone(!on)
        }
        if (focused == null) {
            tip.visibility = View.INVISIBLE
            halo.animate().alpha(0f).setDuration(120).withEndAction { halo.visibility = View.INVISIBLE }.start()
            return
        }
        tip.text = if (moving != null) "◀ ▶ to move · OK when done" else focused.name
        tip.visibility = View.VISIBLE
        tip.post {
            val v = focused.view
            val lift = ((FOCUS - 1f) * 46f * D.k) + v.height * (FOCUS - 1f)
            val cx = row.left + v.left + v.width / 2f
            tip.x = cx - tip.width / 2f
            tip.y = row.top + v.top - lift - px(22) - tip.height
            // The halo sits behind the grown tile: same centre, same lift.
            val grownCy = row.top + v.top + v.height - (FOCUS - 1f) * 46f * D.k - v.height * FOCUS / 2f
            halo.x = cx - halo.width / 2f
            halo.y = grownCy - halo.height / 2f
            halo.visibility = View.VISIBLE
            halo.animate().alpha(1f).setDuration(180).start()
        }
    }

    /** Swaps the moving tile with its neighbour; returns false when the key wasn't ours. */
    private fun moveKey(item: Item, key: Int): Boolean {
        val m = moving ?: return false
        if (m !== item) return false
        val i = items.indexOf(item)
        val j = when (key) { KeyEvent.KEYCODE_DPAD_LEFT -> i - 1; KeyEvent.KEYCODE_DPAD_RIGHT -> i + 1; else -> return false }
        if (j < 0 || j >= apps.size) return true
        val list = apps.toMutableList()
        java.util.Collections.swap(list, i, j)
        apps = list
        rebuild()
        moving = items[j]
        items[j].view.requestFocus()
        return true
    }

    fun startMove(pkg: String): Boolean {
        val item = items.firstOrNull { it.app?.pkg == pkg } ?: return false
        moving = item
        item.view.requestFocus()
        relayout(item)
        return true
    }

    fun finishMove() {
        if (moving == null) return
        moving = null
        host.dockReordered(apps.map { it.pkg })
        relayout(row.focusedChild as? Item)
    }

    /** One tile: a pre-rendered squircle bitmap (brand tile or app icon), or a glass squircle with a system icon. */
    private inner class Item(ctx: Context, val app: AppEntry?, iconRes: Int = 0, val name: String = app?.label ?: "", val action: (() -> Unit)? = null) {
        val view = FrameLayout(ctx)
        val dot = View(ctx)
        val ring = ImageView(ctx)

        init {
            view.ownFocus()
            view.noClip()
            val img = ImageView(ctx)
            if (app != null) {
                img.setImageBitmap(app.tile)
                img.scaleType = ImageView.ScaleType.FIT_XY
            } else {
                img.setImageResource(iconRes)
                img.setPadding(px(28), px(28), px(28), px(28))
                img.scaleType = ImageView.ScaleType.FIT_CENTER
                view.background = Glass(view, squircle = true, top = 0.24f, bottom = 0.16f)
            }
            view.addView(img, LayoutParams(MATCH, MATCH))
            ring.setImageBitmap(ringArt)
            ring.scaleType = ImageView.ScaleType.FIT_XY
            ring.visibility = View.GONE
            view.addView(ring, LayoutParams(MATCH, MATCH))
            dot.background = pill(C.white(0.9f), pxf(3))
            dot.visibility = View.GONE
            view.addView(dot, LayoutParams(px(6), px(6), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = -px(10) })
            view.setOnFocusChangeListener { _, f ->
                if (f) { lastFocused = view; host.dockFocused(app); relayout(this) }
                // The row still reports this child as focused while it's losing focus, so check after the move.
                else view.post { if (row.focusedChild == null) relayout(null) }
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

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Each tile grows from its bottom centre.
        items.forEach { it.view.pivotX = it.view.width / 2f; it.view.pivotY = it.view.height.toFloat() }
    }

    private companion object {
        const val TILE = 96
        const val FOCUS = 1.5f
    }
}
