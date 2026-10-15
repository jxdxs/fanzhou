package com.kevin.fanzhou

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.ui.PlayerView
import com.kevin.fanzhou.player.PlayerActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class ActTest {
    fun dump(v: View, d: Int = 0) {
        val name = try { v.resources.getResourceEntryName(v.id) } catch (e: Exception) { "-" }
        println("  ".repeat(d) + "${v.javaClass.simpleName} $name vis=${v.visibility} ${v.left},${v.top} ${v.width}x${v.height} ty=${v.translationY} a=${v.alpha}")
        if (v is ViewGroup && d < 5 && v !is androidx.media3.ui.AspectRatioFrameLayout) for (i in 0 until v.childCount) dump(v.getChildAt(i), d + 1)
    }
    fun find(v: View): PlayerView? { if (v is PlayerView) return v; if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }; return null }
    fun run() {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/a.mp4"))
        val ctl = Robolectric.buildActivity(PlayerActivity::class.java, i).setup()
        val act = ctl.get()
        val pv = find(act.window.decorView)!!
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
        pv.showController()
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
        dump(pv)
        val c = PlayerView::class.java.getDeclaredField("controller").apply { isAccessible = true }.get(pv)!!
        val a = c.javaClass.getDeclaredField("playbackSpeedAdapter").apply { isAccessible = true }.get(c)!!
        (pv.findViewById<View>(androidx.media3.ui.R.id.exo_rew_with_amount) as? android.widget.TextView)?.let { println("REW h=${it.height} w=${it.width} pt=${it.paddingTop} pb=${it.paddingBottom} ts=${it.textSize} g=${it.gravity} txt=${it.text}") }
        println("SPEEDS count=${a.javaClass.getDeclaredMethod("getItemCount").apply { isAccessible = true }.invoke(a)}")
    }
    @Test @Config(qualifiers = "w800dp-h360dp-land-xxhdpi") fun land() { println("=== LAND"); run() }
    @Test @Config(qualifiers = "w360dp-h800dp-port-xxhdpi") fun port() { println("=== PORT"); run() }

    @Test @Config(qualifiers = "w800dp-h360dp-land-xxhdpi")
    fun testButtonClick() {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/a.mp4"))
        val ctl = Robolectric.buildActivity(PlayerActivity::class.java, i).setup()
        val act = ctl.get()
        val pv = find(act.window.decorView)!!
        pv.showController()
        val audioBtn = pv.findViewById<View>(R.id.fz_audio)!!
        var clicked = false
        audioBtn.setOnClickListener { clicked = true }
        val loc = IntArray(2)
        audioBtn.getLocationOnScreen(loc)
        val x = loc[0] + audioBtn.width / 2f
        val y = loc[1] + audioBtn.height / 2f
        println("audioBtn pos: $x, $y, w=${audioBtn.width}, h=${audioBtn.height}, vis=${audioBtn.visibility}")
        val now = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val up = android.view.MotionEvent.obtain(now, now + 50, android.view.MotionEvent.ACTION_UP, x, y, 0)
        pv.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)?.setOnTouchListener { v, ev ->
            println("exo_bottom_bar onTouch: action=${ev.actionMasked}")
            false
        }
        pv.findViewById<View>(androidx.media3.ui.R.id.exo_basic_controls)?.setOnTouchListener { v, ev ->
            println("exo_basic_controls onTouch: action=${ev.actionMasked}")
            false
        }
        audioBtn.setOnTouchListener { v, ev ->
            println("audioBtn onTouch: action=${ev.actionMasked}")
            false
        }
        val downHandled = act.window.decorView.dispatchTouchEvent(down)
        val upHandled = act.window.decorView.dispatchTouchEvent(up)
        shadowOf(Looper.getMainLooper()).idle()
        org.junit.Assert.assertTrue("Control button should receive click", clicked)

        // Test blank area tap on bottom bar
        pv.showController()
        val bottomBar = pv.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)!!
        val bLoc = IntArray(2)
        bottomBar.getLocationOnScreen(bLoc)
        val blankX = pv.width / 2f
        val blankY = bLoc[1] + bottomBar.height / 2f
        val down2 = android.view.MotionEvent.obtain(now + 200, now + 200, android.view.MotionEvent.ACTION_DOWN, blankX, blankY, 0)
        val up2 = android.view.MotionEvent.obtain(now + 200, now + 250, android.view.MotionEvent.ACTION_UP, blankX, blankY, 0)
        act.window.decorView.dispatchTouchEvent(down2)
        act.window.decorView.dispatchTouchEvent(up2)
        shadowOf(Looper.getMainLooper()).idle()
        org.junit.Assert.assertTrue("Controller should remain visible after blank tap", pv.isControllerFullyVisible)
    }

    @Test
    fun testAbLoopCrash() {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/a.mp4"))
        val ctl = Robolectric.buildActivity(PlayerActivity::class.java, i).setup()
        val act = ctl.get()
        val pv = find(act.window.decorView)!!
        pv.showController()
        val abBtn = pv.findViewById<View>(R.id.fz_ab)!!
        abBtn.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        // Find "+ 新增" button
        val dialog = act.findViewById<View>(R.id.fz_ab_dialog_tag) as? ViewGroup
        org.junit.Assert.assertNotNull("Dialog should be shown", dialog)

        // Find "+ 新增"
        fun findText(v: View, txt: String): TextView? {
            if (v is TextView && v.text == txt) return v
            if (v is ViewGroup) {
                for (j in 0 until v.childCount) {
                    val res = findText(v.getChildAt(j), txt)
                    if (res != null) return res
                }
            }
            return null
        }

        val addBtn = findText(dialog!!, "+ 新增")
        org.junit.Assert.assertNotNull("+ 新增 button should exist", addBtn)
        addBtn!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        // Test 1: Click "保存"
        val saveBtn = findText(dialog, "保存")
        org.junit.Assert.assertNotNull("保存 button should exist", saveBtn)
        saveBtn!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        // Dialog should still be open with the saved segment
        val segItem = findText(dialog, "片段 1")
        org.junit.Assert.assertNotNull("Saved segment should appear in list", segItem)

        // Test 2: Click the segment row to start loop
        var clickTarget: View? = segItem
        while (clickTarget != null && !clickTarget.hasOnClickListeners()) {
            clickTarget = clickTarget.parent as? View
        }
        clickTarget?.performClick() ?: segItem!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        // Dialog should be dismissed and loop active
        val dialogDismissed = act.findViewById<View>(R.id.fz_ab_dialog_tag)
        org.junit.Assert.assertNull("Dialog should be dismissed after starting loop", dialogDismissed)

        // Test 3: Re-open dialog while looping
        pv.showController()
        abBtn.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog2 = act.findViewById<View>(R.id.fz_ab_dialog_tag) as? ViewGroup
        org.junit.Assert.assertNotNull("Dialog should be shown again with saved segments", dialog2)

        val stopBtn = findText(dialog2!!, "停止循环")
        org.junit.Assert.assertNotNull("停止循环 should exist when looping", stopBtn)

        // Test 4: Delete segment with confirmation
        val delBtn = findText(dialog2, "🗑")
        org.junit.Assert.assertNotNull("Delete button should exist", delBtn)
        delBtn!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val confirmDelBtn = findText(dialog2, "确认删除")
        org.junit.Assert.assertNotNull("确认删除 button should exist", confirmDelBtn)
        confirmDelBtn!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        // Check empty text appears
        val emptyTv = findText(dialog2, "暂无保存的 AB 片段\n点击右上角「+ 新增」设置循环区间")
        org.junit.Assert.assertNotNull("Empty hint should appear after deleting segment", emptyTv)
    }

    @Test
    fun testRecentPlayShortExitNoReset() {
        val key = "url:file:///sdcard/b.mp4"
        val dur = 600_000L // 10 min
        val saved = 300_000L // 5 min
        com.kevin.fanzhou.data.Repo.store.saveProgress(key, saved, dur, false)
        org.junit.Assert.assertEquals(saved, com.kevin.fanzhou.data.Repo.store.position(key))

        // Open video with resumeKey set
        val item = com.kevin.fanzhou.data.PlayItem(url = "file:///sdcard/b.mp4", title = "Video B", startMs = saved, resumeKey = key)
        com.kevin.fanzhou.data.PlayQueue.set(listOf(item), 0)

        val i = Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/b.mp4"))
        val ctl = Robolectric.buildActivity(PlayerActivity::class.java, i).setup()
        val act = ctl.get()
        shadowOf(Looper.getMainLooper()).idle()

        // User does NOT click "继续", and exits after 3 seconds (destroy activity)
        ctl.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()

        // Progress should STILL be 5 minutes (300_000L), NOT reset to 0!
        val preserved = com.kevin.fanzhou.data.Repo.store.position(item.effectiveResumeKey)
        org.junit.Assert.assertEquals("5-minute progress should be preserved after short exit without resume", saved, preserved)
    }
}
