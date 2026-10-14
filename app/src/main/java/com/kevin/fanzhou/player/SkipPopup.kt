package com.kevin.fanzhou.player

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.kevin.fanzhou.R

/**
 * 跳过片头/片尾的小弹窗：直接调秒数（即时保存），点跳过执行。
 * 贴在 [anchor] 正上方、右对齐弹出。
 */
object SkipPopup {
    fun show(
        anchor: View,
        title: String,
        initialSec: Int,
        minSec: Int,
        maxSec: Int,
        seriesHint: String? = null,
        initialRememberSeries: Boolean = false,
        onSecChanged: (sec: Int, rememberSeries: Boolean) -> Unit,
        onSkip: (sec: Int) -> Unit,
    ) {
        val ctx = anchor.context
        val dm = ctx.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()
        var sec = initialSec.coerceIn(minSec, maxSec)
        var rememberSeries = initialRememberSeries

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.menu_panel_bg)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val popup = PopupWindow(ctx).apply {
            contentView = panel
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        val ripple = TypedValue().also {
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
        }.resourceId
        panel.addView(TextView(ctx).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        val label = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        }
        var confirmBtn: TextView? = null
        fun refresh() {
            label.text = if (sec <= 0) "关闭" else "$sec 秒"
            confirmBtn?.text = if (sec <= 0) "确定" else "跳过"
        }
        refresh()
        val stepper = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun stepBtn(text: String, onTap: () -> Unit) = TextView(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(6), dp(18), dp(6))
            setBackgroundResource(ripple)
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
        }
        stepper.addView(stepBtn("−") { sec = (sec - 5).coerceAtLeast(minSec); refresh(); onSecChanged(sec, rememberSeries) })
        stepper.addView(label)
        stepper.addView(stepBtn("+") { sec = (sec + 5).coerceAtMost(maxSec); refresh(); onSecChanged(sec, rememberSeries) })
        panel.addView(stepper)

        if (!seriesHint.isNullOrEmpty()) {
            val checkRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(4))
            }
            val cb = android.widget.CheckBox(ctx).apply {
                isChecked = initialRememberSeries
                text = "记住本剧设置"
                setTextColor(Color.LTGRAY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                setOnCheckedChangeListener { _, isChecked ->
                    rememberSeries = isChecked
                    onSecChanged(sec, rememberSeries)
                }
            }
            checkRow.addView(cb)
            panel.addView(checkRow)
        }

        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        fun actionBtn(text: String, color: Int, onTap: () -> Unit) = TextView(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(10), dp(22), dp(10))
            setBackgroundResource(ripple)
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
        }
        confirmBtn = actionBtn(if (sec <= 0) "确定" else "跳过", Color.WHITE) { popup.dismiss(); onSkip(sec) }
        actions.addView(confirmBtn)
        actions.addView(actionBtn("取消", Color.GRAY) { popup.dismiss() })
        panel.addView(actions)

        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = panel.measuredWidth
        val h = panel.measuredHeight
        popup.showAsDropDown(anchor, anchor.width - w, -(anchor.height + h + dp(8)))
    }
}
