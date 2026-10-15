package com.kevin.fanzhou.player

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import com.kevin.fanzhou.R

/**
 * 防误触锁定：全屏透明拦截层，吃掉所有触摸事件与手势。
 * 屏幕边缘显示锁图标，支持自动淡出（3秒无操作隐藏，点击屏幕重新唤起），
 * 点按锁图标直接解锁，与主流播放器交互保持一致。
 */
object ScreenLock {
    private var overlayRef: java.lang.ref.WeakReference<View>? = null
    private var activityRef: java.lang.ref.WeakReference<Activity>? = null
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null

    fun isLocked(activity: Activity? = null): Boolean {
        val v = overlayRef?.get() ?: return false
        if (activity != null && activityRef?.get() !== activity) return false
        return true
    }

    fun lock(activity: Activity, onUnlock: (() -> Unit)? = null) {
        unlock()
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val dm = activity.resources.displayMetrics

        val size = (50 * dm.density).toInt()
        val margin = (20 * dm.density).toInt()
        val pad = (12 * dm.density).toInt()

        lateinit var icon: ImageView

        val hide = Runnable {
            icon.animate().alpha(0f).setDuration(300).start()
        }
        hideRunnable = hide

        val scheduleHide = {
            handler.removeCallbacks(hide)
            handler.postDelayed(hide, 3000)
        }

        val touch = object : FrameLayout(activity) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    if (icon.alpha < 0.9f) {
                        icon.animate().alpha(1f).setDuration(200).start()
                    }
                    scheduleHide()
                }
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            isClickable = true
            isFocusable = true
            setOnTouchListener { _, _ -> true }
        }

        icon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_lock)
            setBackgroundResource(R.drawable.lock_fab_bg)
            setPadding(pad, pad, pad, pad)
            contentDescription = "已锁定，点按解锁"
            elevation = (8 * dm.density)
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = margin
            }
            setOnClickListener {
                unlock()
                Toast.makeText(activity, "已解锁", Toast.LENGTH_SHORT).show()
                onUnlock?.invoke()
            }
        }

        touch.addView(icon)
        root.addView(touch)
        overlayRef = java.lang.ref.WeakReference(touch)
        activityRef = java.lang.ref.WeakReference(activity)

        Toast.makeText(activity, "已锁定屏幕", Toast.LENGTH_SHORT).show()
        scheduleHide()
    }

    fun unlock() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = null
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
