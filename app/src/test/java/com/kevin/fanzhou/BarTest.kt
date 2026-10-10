package com.kevin.fanzhou

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land-xxhdpi")
class BarTest {
    fun dump(v: View, d: Int = 0) {
        val name = try { v.resources.getResourceEntryName(v.id) } catch (e: Exception) { "-" }
        println("  ".repeat(d) + "${v.javaClass.simpleName} $name vis=${v.visibility} ${v.left},${v.top} ${v.width}x${v.height} ty=${v.translationY} a=${v.alpha}")
        if (v is ViewGroup && d < 6) for (i in 0 until v.childCount) dump(v.getChildAt(i), d + 1)
    }
    @Test fun bar() {
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(act)
        val pv = PlayerView(act).apply { useController = true; setShowNextButton(true); setShowPreviousButton(true); setShowFastForwardButton(true); setShowRewindButton(true); setShowSubtitleButton(true) }
        root.addView(pv, FrameLayout.LayoutParams(-1, -1))
        act.setContentView(root)
        pv.player = ExoPlayer.Builder(act).build()
        pv.showController()
        repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
        dump(root)
    }
}
