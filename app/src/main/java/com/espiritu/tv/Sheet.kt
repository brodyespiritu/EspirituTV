package com.espiritu.tv

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The canvas "App options" window: a floating panel on the right with a title and remote-friendly rows. */
class Sheet(private val activity: Activity, title: String, subtitle: String? = null, headerIcon: Bitmap? = null) {
    private val dialog = Dialog(activity, R.style.Espiritu_Sheet)
    private val inflater = LayoutInflater.from(activity)
    private val list: LinearLayout

    init {
        // Sizes come from the canvas (1920-wide px), not dp: TVs report odd densities.
        val root = inflater.inflate(R.layout.sheet, null)
        root.setPadding(px(32), px(32), px(32), px(32))
        (root as ViewGroup).getChildAt(0).setPadding(px(24), px(36), px(24), px(24))
        root.findViewById<TextView>(R.id.sheetTitle).apply { text = title; typeface = Fonts.semibold; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, pxf(40)) }
        root.findViewById<TextView>(R.id.sheetSubtitle).apply {
            text = subtitle
            typeface = Fonts.regular
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, pxf(21))
            visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        root.findViewById<ImageView>(R.id.sheetIcon).apply {
            setImageBitmap(headerIcon)
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { width = px(96); height = px(96) }
            visibility = if (headerIcon == null) View.GONE else View.VISIBLE
        }
        list = root.findViewById(R.id.sheetList)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(px(560), ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.5f)
        }
    }

    fun section(text: String) {
        val v = inflater.inflate(R.layout.sheet_section, list, false) as TextView
        v.text = text
        v.typeface = Fonts.medium
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, pxf(15))
        list.addView(v)
    }

    /** A paragraph (the show summary in More info). */
    fun text(body: String) {
        val v = inflater.inflate(R.layout.sheet_section, list, false) as TextView
        v.text = body
        v.isAllCaps = false
        v.letterSpacing = 0f
        v.typeface = Fonts.regular
        v.setTextColor(C.DIM)
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, pxf(22))
        list.addView(v)
    }

    /**
     * Adds a row. [tint] = false keeps the icon's own colors (app icons, swatches).
     * [checked] = null hides the check mark slot entirely.
     */
    fun option(
        icon: Drawable?,
        text: String,
        tint: Boolean = true,
        checked: Boolean? = null,
        dismiss: Boolean = true,
        onClick: (View) -> Unit,
    ): View {
        val row = inflater.inflate(R.layout.sheet_row, list, false)
        row.minimumHeight = px(68)
        row.setPadding(px(18), 0, px(18), 0)
        row.findViewById<ImageView>(R.id.rowIcon).apply {
            setImageDrawable(icon)
            if (!tint) imageTintList = null
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { width = px(28); height = px(28) }
            visibility = if (icon == null) View.GONE else View.VISIBLE
        }
        row.findViewById<TextView>(R.id.rowText).apply {
            this.text = text
            typeface = Fonts.medium
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, pxf(23))
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { marginStart = px(18) }
        }
        row.findViewById<ImageView>(R.id.rowCheck).layoutParams = (row.findViewById<ImageView>(R.id.rowCheck).layoutParams as LinearLayout.LayoutParams).apply { width = px(24); height = px(24) }
        setChecked(row, checked)
        row.setOnClickListener {
            if (dismiss) dialog.dismiss()
            onClick(it)
        }
        list.addView(row)
        return row
    }

    fun option(iconRes: Int, text: String, onClick: (View) -> Unit) =
        option(activity.getDrawable(iconRes), text, onClick = onClick)

    fun setChecked(row: View, checked: Boolean?) {
        row.findViewById<View>(R.id.rowCheck).visibility = when (checked) {
            true -> View.VISIBLE
            false -> View.INVISIBLE
            null -> View.GONE
        }
    }

    fun empty(text: String) {
        val v = inflater.inflate(R.layout.sheet_section, list, false) as TextView
        v.text = text
        v.isAllCaps = false
        v.letterSpacing = 0f
        v.typeface = Fonts.regular
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, pxf(21))
        list.addView(v)
    }

    fun show() {
        dialog.show()
        for (i in 0 until list.childCount) {
            val c = list.getChildAt(i)
            if (c.isFocusable) { c.requestFocus(); break }
        }
    }

    fun dismiss() = dialog.dismiss()

    fun setOnDismiss(block: () -> Unit) = dialog.setOnDismissListener { block() }
}
