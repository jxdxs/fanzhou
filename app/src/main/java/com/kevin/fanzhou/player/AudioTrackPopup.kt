package com.kevin.fanzhou.player

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/**
 * 紧凑型音轨选择弹窗：
 * - 紧贴在 [anchor]（音轨按钮）正上方弹出（间隙仅 4dp）；
 * - 宽度根据文字自适应，无多余空白边距；
 * - 高度自适应内容行数，不留任何无用空白区域；
 * - 当前选中音轨以高亮青色及 "✓ " 醒目标识。
 */
object AudioTrackPopup {
    fun show(anchor: View, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        if (labels.isEmpty()) return
        val ctx = anchor.context
        val dp = ctx.resources.displayMetrics.density
        fun toDp(v: Float) = (v * dp).toInt()

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, toDp(4f), 0, toDp(4f))
        }

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            background = GradientDrawable().apply {
                setColor(0xE6202020.toInt())
                cornerRadius = 12f * dp
            }
            addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val popup = PopupWindow(scroll, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = 8f * dp
        }

        val ripple = TypedValue().also { ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }

        labels.forEachIndexed { i, s ->
            val isSel = i == selected
            col.addView(TextView(ctx).apply {
                text = (if (isSel) "✓ " else "   ") + s
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTextColor(if (isSel) 0xFF4FC3F7.toInt() else Color.WHITE)
                if (isSel) setTypeface(typeface, Typeface.BOLD)
                setPadding(toDp(14f), toDp(9f), toDp(14f), toDp(9f))
                if (ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    popup.dismiss()
                    onPick(i)
                }
            })
        }

        scroll.measure(
            View.MeasureSpec.makeMeasureSpec(toDp(320f), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(toDp(260f), View.MeasureSpec.AT_MOST),
        )
        val loc = IntArray(2).also { anchor.getLocationInWindow(it) }
        val screenHCap = (ctx.resources.displayMetrics.heightPixels * 0.55f).toInt()
        val maxH = minOf((loc[1] - toDp(6f)).coerceAtLeast(toDp(100f)), screenHCap)
        val h = scroll.measuredHeight.coerceAtMost(maxH)
        val w = scroll.measuredWidth.coerceAtLeast(toDp(110f))

        popup.width = w
        popup.height = h

        val screenW = ctx.resources.displayMetrics.widthPixels
        var x = loc[0] + (anchor.width - w) / 2
        if (x + w > screenW - toDp(8f)) x = screenW - w - toDp(8f)
        if (x < toDp(8f)) x = toDp(8f)

        val topSpace = loc[1] - h - toDp(4f)
        val y = if (topSpace >= toDp(8f)) topSpace else (loc[1] + anchor.height + toDp(4f))

        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        if (selected >= 0) {
            scroll.post {
                col.getChildAt(selected)?.let { c ->
                    scroll.scrollTo(0, (c.top - h / 2 + c.height / 2).coerceAtLeast(0))
                }
            }
        }
    }
}
