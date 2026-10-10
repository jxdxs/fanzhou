package com.kevin.fanzhou

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
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
}
