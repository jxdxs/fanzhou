package com.kevin.fanzhou.player

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.kevin.fanzhou.R

/**
 * 紧凑的竖向文字菜单，替代又宽又高的 AlertDialog 列表（比如字幕菜单）。
 * 贴在 [anchor] 正上方、右对齐弹出；点外面或选一项后消失。
 */
object TextMenu {
    data class Item(val label: String, val onClick: () -> Unit)

    fun show(anchor: View, items: List<Item>, maxHeightDp: Int = 340) {
        if (items.isEmpty()) return
        val ctx = anchor.context
        val dm = ctx.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        // 先声明 popup：下面的点击监听 lambda 要引用它，Kotlin 不允许 lambda 引用后声明的 val。
        val popup = PopupWindow(ctx).apply {
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val ripple = TypedValue().also {
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId
        items.forEach { item ->
            val tv = TextView(ctx).apply {
                text = item.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Color.WHITE)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setBackgroundResource(ripple)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            tv.setOnClickListener { popup.dismiss(); item.onClick() }
            list.addView(tv)
        }
        val scroll = ScrollView(ctx).apply { addView(list) }
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.menu_panel_bg)
            addView(scroll)
        }
        popup.contentView = panel
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(dp(280), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(dp(maxHeightDp), View.MeasureSpec.AT_MOST),
        )
        val w = panel.measuredWidth
        val h = panel.measuredHeight
        // 在按钮正上方弹出，右边缘与按钮右边缘对齐
        popup.showAsDropDown(anchor, anchor.width - w, -(anchor.height + h + dp(8)))
    }
}
