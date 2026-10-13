package com.kevin.fanzhou.player

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.kevin.fanzhou.R

/**
 * 防误触锁定：一张盖满全屏的透明层，吃掉所有触摸事件（底下的播放器、
 * 手势、控制条都收不到），屏幕上只留一个锁形图标。
 * 点锁图标会先弹确认框，点「退出锁定」才真正解锁。
 */
object ScreenLock {
    private var overlayRef: java.lang.ref.WeakReference<View>? = null
    private var activityRef: java.lang.ref.WeakReference<Activity>? = null

    fun isLocked(activity: Activity? = null): Boolean {
        val v = overlayRef?.get() ?: return false
        if (activity != null && activityRef?.get() !== activity) return false
        return true
    }

    fun lock(activity: Activity, onUnlock: (() -> Unit)? = null) {
        unlock()
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val dm = activity.resources.displayMetrics
        val touch = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            // 吃掉所有触摸，底下的播放器收不到任何手势
            isClickable = true
            isFocusable = true
            setOnTouchListener { _, _ -> true }
        }
        val size = (56 * dm.density).toInt()
        val margin = (16 * dm.density).toInt()
        val pad = (14 * dm.density).toInt()
        val icon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_lock)
            setBackgroundResource(R.drawable.lock_fab_bg)
            setPadding(pad, pad, pad, pad)
            contentDescription = "已锁定，点按解锁"
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = margin
            }
            setOnClickListener {
                // 窄一点的确认框：260dp 宽，不占满屏
                val dlg = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("退出锁定？")
                    .setMessage("确认退出防误触锁定吗？")
                    .setPositiveButton("退出锁定") { d, _ -> d.dismiss(); unlock(); onUnlock?.invoke() }
                    .setNegativeButton("取消", null)
                    .show()
                dlg.window?.setLayout(
                    (260 * activity.resources.displayMetrics.density).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
        }
        touch.addView(icon)
        root.addView(touch)
        overlayRef = java.lang.ref.WeakReference(touch)
        activityRef = java.lang.ref.WeakReference(activity)
    }

    fun unlock() {
        val o = overlayRef?.get()
        if (o != null) {
            (o.parent as? ViewGroup)?.removeView(o)
        }
        overlayRef = null
        activityRef = null
    }

    fun release(activity: Activity) {
        if (activityRef?.get() === activity) {
            unlock()
        }
    }
}
