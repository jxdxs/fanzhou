package com.kevin.fanzhou.player

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
        lateinit var touch: FrameLayout

        var confirmDialog: View? = null

        fun dismissConfirmDialog() {
            confirmDialog?.let { dialog ->
                touch.removeView(dialog)
                confirmDialog = null
            }
        }

        val hide = Runnable {
            if (confirmDialog == null) {
                icon.animate().alpha(0f).setDuration(300).start()
            }
        }
        hideRunnable = hide

        val scheduleHide = {
            handler.removeCallbacks(hide)
            handler.postDelayed(hide, 3000)
        }

        touch = object : FrameLayout(activity) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    val dialog = confirmDialog
                    if (dialog != null) {
                        val rect = android.graphics.Rect()
                        dialog.getGlobalVisibleRect(rect)
                        if (!rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                            dismissConfirmDialog()
                            scheduleHide()
                            return true
                        }
                    } else {
                        if (icon.alpha < 0.9f) {
                            icon.animate().alpha(1f).setDuration(200).start()
                        }
                        scheduleHide()
                    }
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

        fun showConfirmDialog() {
            if (confirmDialog != null) return
            handler.removeCallbacks(hide)
            icon.animate().alpha(1f).setDuration(150).start()

            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = GradientDrawable().apply {
                    setColor(0xE6212121.toInt())
                    cornerRadius = 14 * dm.density
                    setStroke((1 * dm.density).toInt(), 0x55FFFFFF.toInt())
                }
                setPadding((18 * dm.density).toInt(), (14 * dm.density).toInt(), (18 * dm.density).toInt(), (14 * dm.density).toInt())
                elevation = 16 * dm.density

                val title = TextView(activity).apply {
                    text = "是否解除屏幕锁定？"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                }
                addView(title)

                val btnRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    lp.topMargin = (12 * dm.density).toInt()
                    layoutParams = lp
                }

                fun actionBtn(label: String, isPrimary: Boolean, onClick: () -> Unit) = TextView(activity).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding((16 * dm.density).toInt(), (7 * dm.density).toInt(), (16 * dm.density).toInt(), (7 * dm.density).toInt())
                    background = GradientDrawable().apply {
                        cornerRadius = 8 * dm.density
                        setColor(if (isPrimary) 0xFF1976D2.toInt() else 0x33FFFFFF.toInt())
                    }
                    setOnClickListener { onClick() }
                }

                val cancelBtn = actionBtn("取消", false) {
                    dismissConfirmDialog()
                    scheduleHide()
                }
                val unlockBtn = actionBtn("确认解锁", true) {
                    dismissConfirmDialog()
                    unlock()
                    Toast.makeText(activity, "已解锁", Toast.LENGTH_SHORT).show()
                    onUnlock?.invoke()
                }

                btnRow.addView(cancelBtn)
                btnRow.addView(View(activity), LinearLayout.LayoutParams((12 * dm.density).toInt(), 1))
                btnRow.addView(unlockBtn)
                addView(btnRow)
            }

            confirmDialog = card
            touch.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
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
                showConfirmDialog()
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
