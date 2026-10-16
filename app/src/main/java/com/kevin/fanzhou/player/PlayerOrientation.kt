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
     * Portrait only: spreads the transport buttons across the free width of the bottom bar
     * (bar width minus the right-hand button group), so the row has no dead space on the right.
     * Re-runs whenever the bar's width changes; landscape keeps the fixed sizes set elsewhere.
     */
    fun stretchRow(a: Activity, row: android.view.ViewGroup, right: View?) {
        val bar = row.parent as? View ?: return
        fun apply() {
            (right as? android.view.ViewGroup)?.let { compactRightGroup(a, it, isPortrait(a)) }
            if (!isPortrait(a) || bar.width <= 0) return
            val d = a.resources.displayMetrics.density
            // Free space = from the row's start to where the right-hand group begins.
            val r = right?.takeIf { it.visibility == View.VISIBLE && it.width > 0 }
            val rightEdge = r?.let { it.right - visibleChildrenWidth(it) } ?: bar.width
            val avail = rightEdge - row.left - row.paddingStart - row.paddingEnd - (8 * d).toInt()
            val kids = (0 until row.childCount).map { row.getChildAt(it) }.filter { it.visibility != View.GONE }
            if (avail <= 0 || kids.isEmpty()) return
            // Portrait width is tight: drop the 5dp side margins so the buttons themselves get it.
            for (k in kids) (k.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { if (it.leftMargin != 0 || it.rightMargin != 0) { it.leftMargin = 0; it.rightMargin = 0; it.marginStart = 0; it.marginEnd = 0; k.layoutParams = it } }
            val margins = kids.sumOf { k -> (k.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { it.leftMargin + it.rightMargin } ?: 0 }
            val w = ((avail - margins) / kids.size).coerceAtMost((64 * d).toInt())  // bar is 72dp tall
            val h = w  // keep square: the skip icons are stretched backgrounds and would distort
            var changed = false
            for (k in kids) {
                val targets = listOf(k) + ((k as? android.widget.FrameLayout)?.let { f -> (0 until f.childCount).map { f.getChildAt(it) } } ?: emptyList())
                for (t in targets) {
                    val lp = t.layoutParams ?: continue
                    if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; t.layoutParams = lp; changed = true }
                }
            }
            if (changed) row.requestLayout()
        }
        if (bar.getTag(R_TAG) == null) {
            bar.setTag(R_TAG, true)
            bar.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol) bar.post { apply() } }
            right?.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol || l != ol) bar.post { apply() } }
        }
        bar.post { apply() }
    }
    /**
     * Portrait: tighten the right-hand group (倍速/音轨/选集/AB/字幕/设置) so its free gaps go to
     * 快退/播放/快进 instead. Icon buttons 48dp → 36dp wide (24dp icon kept), 倍速 60dp → 40dp, no
     * side margins. Landscape restores the layout values saved on first use.
     */
    private fun compactRightGroup(a: Activity, g: android.view.ViewGroup, portrait: Boolean) {
        val d = a.resources.displayMetrics.density
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            val lp = c.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: continue
            @Suppress("UNCHECKED_CAST")
            val orig = (c.getTag(R_ORIG) as? IntArray) ?: intArrayOf(lp.width, lp.leftMargin, lp.rightMargin, lp.marginStart, lp.marginEnd,
                c.paddingLeft, c.paddingTop, c.paddingRight, c.paddingBottom, c.minimumWidth, ((c as? android.widget.TextView)?.textSize ?: 0f).toInt()).also { c.setTag(R_ORIG, it) }
            val isText = c is android.widget.TextView && c !is android.widget.Button && c.javaClass.simpleName.let { it == "TextView" || it.endsWith("AppCompatTextView") || it.endsWith("MaterialTextView") }
            if (portrait) {
                lp.leftMargin = 0; lp.rightMargin = 0; lp.marginStart = 0; lp.marginEnd = 0
                if (isText && orig[0] == android.view.ViewGroup.LayoutParams.WRAP_CONTENT) {
                    // 倍速 label: just wide enough for "1.25x"
                    c.minimumWidth = (40 * d).toInt(); (c as android.widget.TextView).minWidth = (40 * d).toInt()
                    c.setPadding((3 * d).toInt(), c.paddingTop, (3 * d).toInt(), c.paddingBottom)
                    c.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                } else if (orig[0] > (36 * d).toInt()) {
                    lp.width = (36 * d).toInt()
                    val vPad = orig[6]
                    val hPad = ((36 * d - (orig[0] - orig[5] - orig[7])) / 2).toInt().coerceAtLeast(0)
                    c.setPadding(hPad, vPad, hPad, orig[8])
                }
            } else {
                lp.width = orig[0]; lp.leftMargin = orig[1]; lp.rightMargin = orig[2]; lp.marginStart = orig[3]; lp.marginEnd = orig[4]
                c.setPadding(orig[5], orig[6], orig[7], orig[8])
                c.minimumWidth = orig[9]
                if (c is android.widget.TextView && orig[10] > 0) { c.minWidth = orig[9]; c.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, orig[10].toFloat()) }
            }
            c.layoutParams = lp
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
