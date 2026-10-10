package com.kevin.fanzhou.player

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.kevin.fanzhou.R

/**
 * 紧凑的图标按钮菜单，替代又宽又高的文字列表弹窗。
 * 贴在 [anchor]（设置按钮）正上方、右对齐弹出；点外面或选一项后消失。
 */
object IconMenu {
    data class Item(
        val icon: Int,
        val label: String,
        val active: Boolean = false,
        val onClick: () -> Unit,
    )

    fun show(anchor: View, items: List<Item>) {
        if (items.isEmpty()) return
        val ctx = anchor.context
        val dm = ctx.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.menu_panel_bg)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        val popup = PopupWindow(ctx).apply {
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        val ripple = TypedValue().also {
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
        }.resourceId
        items.forEach { item ->
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(2), dp(12), dp(2))
                setBackgroundResource(ripple)
                isClickable = true
                isFocusable = true
            }
            val iv = ImageView(ctx).apply {
                setImageResource(item.icon)
                // 开启中的开关类选项，图标染成强调色
                if (item.active) setColorFilter(0xFFFFC107.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
            }
            val tv = TextView(ctx).apply {
                text = item.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, 0)
                maxLines = 1
            }
            cell.addView(iv)
            cell.addView(tv)
            cell.setOnClickListener { popup.dismiss(); item.onClick() }
            row.addView(cell)
        }
        popup.contentView = row
        row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = row.measuredWidth
        val h = row.measuredHeight
        // 在按钮正上方弹出，右边缘与按钮右边缘对齐
        popup.showAsDropDown(anchor, anchor.width - w, -(anchor.height + h + dp(8)))
    }
}
