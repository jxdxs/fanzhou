package com.kevin.fanzhou.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.View
import android.widget.PopupMenu
import com.kevin.fanzhou.data.Repo

/** 播放界面的横屏 / 竖屏 / 自动旋转，两个内核共用。 */
object PlayerOrientation {
    private val modes = listOf("landscape" to "横屏", "portrait" to "竖屏", "auto" to "自动旋转")

    fun apply(a: Activity) {
        a.requestedOrientation = when (Repo.store.playerOrientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "auto" -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    fun label(): String = modes.firstOrNull { it.first == Repo.store.playerOrientation }?.second ?: "横屏"

    /** 点击直接在横屏与竖屏之间切换，不弹菜单。 */
    fun toggle(a: Activity): String {
        val next = if (Repo.store.playerOrientation == "portrait" || isPortrait(a)) "landscape" else "portrait"
        Repo.store.playerOrientation = next
        apply(a)
        return if (next == "portrait") "竖屏" else "横屏"
    }

    fun showMenu(a: Activity, anchor: View, onChanged: (String) -> Unit) {
        val menu = PopupMenu(a, anchor)
        val cur = Repo.store.playerOrientation
        modes.forEachIndexed { i, (key, name) ->
            menu.menu.add(0, i, i, if (key == cur) "✓ $name" else "    $name")
        }
        menu.setOnMenuItemClickListener { item ->
            val (key, name) = modes[item.itemId]
            Repo.store.playerOrientation = key
            apply(a)
            onChanged(name)
            true
        }
        menu.show()
    }

    fun isPortrait(a: Activity) = a.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    /** 竖屏时把底栏的五个按钮缩小一点，避免底栏挤不下。 */
    fun compactButtons(a: Activity, root: View, ids: IntArray) {
        val size = (a.resources.displayMetrics.density * (if (isPortrait(a)) 43 else 53)).toInt()
        val pad = (a.resources.displayMetrics.density * (if (isPortrait(a)) 7 else 9)).toInt()
        for (id in ids) {
            val v = root.findViewById<View>(id) ?: continue
            val lp = v.layoutParams ?: continue
            if (lp.width == size && lp.height == size) continue
            lp.width = size; lp.height = size
            v.layoutParams = lp
            v.setPadding(pad, pad, pad, pad)
        }
    }

    /**
     * Portrait only: every visible bottom-bar button (快退/播放/快进 on the left, 倍速/音轨/选集/AB/
     * 设置 on the right) gets the SAME size, capped at 44dp, and the leftover width is shared out as
     * equal gaps between all of them, so there is no wide dead zone anywhere (2.3.50; 2.3.49 made
     * the left three oversized). Landscape restores the original layout values.
     */
    fun stretchRow(a: Activity, row: android.view.ViewGroup, right: View?) {
        val bar = row.parent as? View ?: return
        fun apply() {
            val rg = right as? android.view.ViewGroup
            if (!isPortrait(a)) {
                rg?.let { restoreGroup(it) }
                for (i in 0 until row.childCount) { val c = row.getChildAt(i); val o = c.getTag(R_ORIG) as? IntArray ?: continue
                    (c.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { if (it.leftMargin != o[1] || it.rightMargin != o[2]) { it.leftMargin = o[1]; it.rightMargin = o[2]; it.marginStart = o[3]; it.marginEnd = o[4]; c.layoutParams = it } } }
                return
            }
            if (bar.width <= 0) return
            val d = a.resources.displayMetrics.density
            val leftKids = (0 until row.childCount).map { row.getChildAt(it) }.filter { it.visibility != View.GONE }
            val rightKids = rg?.let { g -> (0 until g.childCount).map { g.getChildAt(it) }.filter { it.visibility != View.GONE } } ?: emptyList()
            val n = leftKids.size + rightKids.size
            if (n == 0) return
            if (row.paddingStart != 0 || row.paddingEnd != 0) row.setPaddingRelative(0, row.paddingTop, 0, row.paddingBottom)
            rg?.let { if (it.paddingStart != 0 || it.paddingEnd != 0) it.setPaddingRelative(0, it.paddingTop, 0, it.paddingBottom) }
            val avail = bar.width - bar.paddingLeft - bar.paddingRight - (8 * d).toInt()
            val s = minOf((44 * d).toInt(), avail / n)
            val m = ((avail - n * s) / (2 * n)).coerceAtLeast(0)
            var changed = false
            fun setLp(v: View, w: Int, h: Int?, margin: Int?) {
                val lp = v.layoutParams ?: return
                var c = false
                if (lp.width != w) { lp.width = w; c = true }
                if (h != null && lp.height != h) { lp.height = h; c = true }
                if (margin != null) (lp as? android.view.ViewGroup.MarginLayoutParams)?.let {
                    if (it.leftMargin != margin || it.rightMargin != margin || it.marginStart != margin || it.marginEnd != margin) { it.leftMargin = margin; it.rightMargin = margin; it.marginStart = margin; it.marginEnd = margin; c = true }
                }
                if (c) { v.layoutParams = lp; changed = true }
            }
            for (k in leftKids) {
                saveOrig(k)
                setLp(k, s, s, m)
                (k as? android.widget.FrameLayout)?.let { f -> for (i in 0 until f.childCount) setLp(f.getChildAt(i), s, s, null) }
            }
            val glyph = (24 * d).toInt()
            for (c in rightKids) {
                saveOrig(c)
                setLp(c, s, null, m)
                if (c is android.widget.ImageView) {
                    val hp = ((s - glyph) / 2).coerceAtLeast(0)
                    if (c.paddingLeft != hp || c.paddingRight != hp) c.setPadding(hp, c.paddingTop, hp, c.paddingBottom)
                } else if (c is android.widget.TextView) {
                    if (c.minWidth != 0) { c.minWidth = 0; c.minimumWidth = 0 }
                    if (c.paddingLeft != 0 || c.paddingRight != 0) c.setPadding(0, c.paddingTop, 0, c.paddingBottom)
                    if (c.id == com.kevin.fanzhou.R.id.fz_speed || c.id == com.kevin.fanzhou.R.id.mpv_speed) c.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                }
            }
            if (changed) { row.requestLayout(); rg?.requestLayout() }
        }
        if (bar.getTag(R_TAG) == null) {
            bar.setTag(R_TAG, true)
            bar.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol) bar.post { apply() } }
            right?.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol || l != ol) bar.post { apply() } }
        }
        bar.post { apply() }
    }
    private fun saveOrig(c: View) {
        if (c.getTag(R_ORIG) != null) return
        val lp = c.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return
        c.setTag(R_ORIG, intArrayOf(lp.width, lp.leftMargin, lp.rightMargin, lp.marginStart, lp.marginEnd,
            c.paddingLeft, c.paddingTop, c.paddingRight, c.paddingBottom, c.minimumWidth, ((c as? android.widget.TextView)?.textSize ?: 0f).toInt()))
    }
    /** Landscape: put the right-hand group back exactly as the layout defined it. */
    private fun restoreGroup(g: android.view.ViewGroup) {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            val o = c.getTag(R_ORIG) as? IntArray ?: continue
            val lp = c.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: continue
            if (lp.width == o[0] && lp.leftMargin == o[1] && lp.rightMargin == o[2] && c.paddingLeft == o[5]) continue
            lp.width = o[0]; lp.leftMargin = o[1]; lp.rightMargin = o[2]; lp.marginStart = o[3]; lp.marginEnd = o[4]
            c.layoutParams = lp
            c.setPadding(o[5], o[6], o[7], o[8])
            c.minimumWidth = o[9]
            if (c is android.widget.TextView && o[10] > 0) { c.minWidth = o[9]; c.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, o[10].toFloat()) }
        }
    }
    private val R_ORIG = com.kevin.fanzhou.R.id.fz_orig_tag

    /** Width actually used by the visible children of a right-aligned group (Media3 keeps GONE slots). */
    private fun visibleChildrenWidth(v: View): Int {
        val g = v as? android.view.ViewGroup ?: return v.width
        var w = g.paddingStart + g.paddingEnd
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.visibility == View.GONE) continue
            val lp = c.layoutParams as? android.view.ViewGroup.MarginLayoutParams
            w += c.width + (lp?.leftMargin ?: 0) + (lp?.rightMargin ?: 0)
        }
        return w.coerceAtMost(g.width)
    }
    private val R_TAG = com.kevin.fanzhou.R.id.fz_stretch_tag
}
