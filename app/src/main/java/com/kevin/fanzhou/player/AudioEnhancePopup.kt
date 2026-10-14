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
import android.widget.TextView
import android.widget.Switch
import com.kevin.fanzhou.data.Repo

/**
 * 紧凑型音频增强与人声弹窗：
 * - 紧贴在 [anchor] 附近弹出；
 * - 零多余空白，自适应紧凑布局；
 * - 支持一键开关夜间人声对白增强；
 * - 支持即时点选音量软放大倍率（100%、125%、150%、200%）。
 */
object AudioEnhancePopup {
    fun show(
        anchor: View,
        onChanged: () -> Unit,
        showHint: (String) -> Unit,
    ) {
        val ctx = anchor.context
        val dp = ctx.resources.displayMetrics.density
        fun toDp(v: Float) = (v * dp).toInt()

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xE6202020.toInt())
                cornerRadius = 14f * dp
                setStroke(toDp(1f), 0x33FFFFFF.toInt())
            }
            setPadding(toDp(16f), toDp(12f), toDp(16f), toDp(12f))
        }

        val popup = PopupWindow(panel, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = 10f * dp
        }

        // Title
        val title = TextView(ctx).apply {
            text = "音频增强与人声"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, toDp(10f))
        }
        panel.addView(title)

        // Row 1: 夜间人声对白增强
        val voiceRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, toDp(4f), 0, toDp(10f))
        }
        val voiceLabel = TextView(ctx).apply {
            text = "夜间人声对白增强"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val voiceSwitch = Switch(ctx).apply {
            isChecked = Repo.store.voiceEnhance
            setOnCheckedChangeListener { _, isChecked ->
                Repo.store.voiceEnhance = isChecked
                onChanged()
                showHint("人声对白增强：" + if (isChecked) "开启" else "关闭")
            }
        }
        voiceRow.addView(voiceLabel)
        voiceRow.addView(voiceSwitch)
        panel.addView(voiceRow)

        // Divider
        val divider = View(ctx).apply {
            setBackgroundColor(0x22FFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, toDp(0.8f)).apply {
                bottomMargin = toDp(10f)
            }
        }
        panel.addView(divider)

        // Row 2: 音量软放大
        val boostTitle = TextView(ctx).apply {
            text = "音量软放大"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, toDp(8f))
        }
        panel.addView(boostTitle)

        // Boost pills
        val boostValues = listOf(1.0f, 1.25f, 1.5f, 2.0f)
        val boostLabels = listOf("100%", "125%", "150%", "200%")
        val currentBoost = Repo.store.volumeBoost

        val pillsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val pillViews = mutableListOf<TextView>()

        fun refreshPills(activeVal: Float) {
            pillViews.forEachIndexed { i, tv ->
                val active = kotlin.math.abs(boostValues[i] - activeVal) < 0.05f
                tv.apply {
                    setTextColor(if (active) Color.BLACK else Color.WHITE)
                    typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    background = GradientDrawable().apply {
                        cornerRadius = 8f * dp
                        if (active) {
                            setColor(0xFF4FC3F7.toInt())
                        } else {
                            setColor(0x33FFFFFF.toInt())
                            setStroke(toDp(1f), 0x22FFFFFF.toInt())
                        }
                    }
                }
            }
        }

        boostValues.forEachIndexed { i, v ->
            val pill = TextView(ctx).apply {
                text = boostLabels[i]
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                gravity = Gravity.CENTER
                setPadding(toDp(12f), toDp(6f), toDp(12f), toDp(6f))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    Repo.store.volumeBoost = v
                    refreshPills(v)
                    onChanged()
                    showHint("音量软放大：${boostLabels[i]}")
                }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) marginStart = toDp(8f)
            }
            pillsRow.addView(pill, lp)
            pillViews.add(pill)
        }
        refreshPills(currentBoost)
        panel.addView(pillsRow)

        // Measure and position near anchor
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(toDp(300f), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(toDp(260f), View.MeasureSpec.AT_MOST)
        )
        val w = panel.measuredWidth
        val h = panel.measuredHeight

        val loc = IntArray(2).also { anchor.getLocationInWindow(it) }
        val screenW = ctx.resources.displayMetrics.widthPixels
        var x = loc[0] + (anchor.width - w) / 2
        if (x + w > screenW - toDp(8f)) x = screenW - w - toDp(8f)
        if (x < toDp(8f)) x = toDp(8f)

        val topSpace = loc[1] - h - toDp(4f)
        val y = if (topSpace >= toDp(8f)) topSpace else (loc[1] + anchor.height + toDp(4f))

        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }
}
