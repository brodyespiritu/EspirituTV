package com.espiritu.tv

import android.app.Activity
import android.app.Dialog
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** The canvas "All apps" board: five columns of 16:9 cards with names, over the dimmed Home. */
class AllApps(private val activity: Activity, private val apps: List<AppEntry>, private val host: Host) {
    interface Host {
        fun openApp(app: AppEntry)
        fun showAppOptions(app: AppEntry)
    }

    private val dialog = Dialog(activity, R.style.Espiritu_Sheet)
    private val grid = RecyclerView(activity)

    init {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; noClip() }
        root.setPadding(px(120), px(64), px(120), 0)
        root.addView(activity.label(56, Fonts.semibold, text = "All apps").apply { letterSpacing = -0.02f })
        root.addView(activity.label(24, Fonts.regular, C.DIM, "${apps.size} apps · hold OK on an app for options"),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = px(10) })
        grid.layoutManager = GridLayoutManager(activity, COLS)
        grid.adapter = Adapter()
        grid.itemAnimator = null
        grid.noClip()
        grid.setPadding(0, px(44), 0, px(72))
        grid.overScrollMode = View.OVER_SCROLL_NEVER
        root.addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.9f)
        }
    }

    fun show() {
        dialog.show()
        grid.post { grid.getChildAt(0)?.requestFocus() }
    }

    fun dismiss() = dialog.dismiss()
    fun setOnDismiss(block: () -> Unit) = dialog.setOnDismissListener { block() }

    private inner class Holder(val cell: FrameLayout, val card: ImageView, val name: android.widget.TextView) : RecyclerView.ViewHolder(cell)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = apps.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val cellW = (D.screenW - 2 * px(120)) / COLS
            val cell = FrameLayout(activity).apply { noClip(); layoutParams = RecyclerView.LayoutParams(cellW, px(162 + 26 + 34 + 40)) }
            val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; noClip(); ownFocus() }
            val card = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, pxf(20)) }
                }
            }
            column.addView(card, LinearLayout.LayoutParams(px(260), px(162)))
            val name = activity.label(24, Fonts.medium, C.DIM).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                maxWidth = px(260)
            }
            column.addView(name, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = px(26) })
            cell.addView(column, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = px(20) })
            column.setOnFocusChangeListener { v, f ->
                v.animate().scaleX(if (f) 1.1f else 1f).scaleY(if (f) 1.1f else 1f).setDuration(160).start()
                card.translationZ = if (f) pxf(20) else 0f
                name.setTextColor(if (f) C.WHITE else C.DIM)
            }
            return Holder(cell, card, name)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val app = apps[position]
            h.card.setImageBitmap(app.card)
            h.name.text = app.label
            val column = h.cell.getChildAt(0)
            column.setOnClickListener { dialog.dismiss(); host.openApp(app) }
            column.setOnLongClickListener { host.showAppOptions(app); true }
            column.setOnKeyListener { _, key, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_MENU) { host.showAppOptions(app); true } else false
            }
        }
    }

    private companion object {
        const val COLS = 5
    }
}
