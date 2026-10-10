package com.kevin.fanzhou.player

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/** Compact speed list: only as wide as the speed labels, popped above the 倍速 button. */
object SpeedPopup {
    fun show(anchor: View, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val ctx = anchor.context
        val dp = ctx.resources.displayMetrics.density
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            background = GradientDrawable().apply { setColor(0xE6202020.toInt()); cornerRadius = 12 * dp }
            addView(col)
        }
        val popup = PopupWindow(scroll, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = 8 * dp
        }
        val tv = TypedValue().also { ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
        labels.forEachIndexed { i, s ->
            col.addView(TextView(ctx).apply {
                text = s
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(if (i == selected) 0xFF4FC3F7.toInt() else Color.WHITE)
                if (i == selected) setTypeface(typeface, Typeface.BOLD)
                setPadding((18 * dp).toInt(), (9 * dp).toInt(), (18 * dp).toInt(), (9 * dp).toInt())
                if (tv.resourceId != 0) setBackgroundResource(tv.resourceId)
                setOnClickListener { popup.dismiss(); onPick(i) }
            })
        }
        scroll.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val loc = IntArray(2).also { anchor.getLocationInWindow(it) }
        val maxH = (loc[1] - 8 * dp).toInt().coerceAtLeast((160 * dp).toInt())
        val h = scroll.measuredHeight.coerceAtMost(maxH)
        val w = scroll.measuredWidth
        popup.height = h
        val x = loc[0] + (anchor.width - w) / 2
        val y = (loc[1] - h - 4 * dp).toInt().coerceAtLeast(0)
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        if (selected >= 0) scroll.post { col.getChildAt(selected)?.let { c -> scroll.scrollTo(0, (c.top - h / 2 + c.height / 2).coerceAtLeast(0)) } }
    }
}
