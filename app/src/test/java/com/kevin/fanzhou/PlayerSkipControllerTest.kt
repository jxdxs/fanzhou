package com.kevin.fanzhou

import com.kevin.fanzhou.data.Repo
import com.kevin.fanzhou.player.PlayerSkipController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlayerSkipControllerTest {

    @Before
    fun setup() {
        Repo.init(RuntimeEnvironment.getApplication())
        Repo.store.introSkipSec = 90
        Repo.store.outroSkipSec = 90
    }

    @Test
    fun testAutoSkipIntro() {
        val controller = PlayerSkipController()
        controller.onIndexChanged(0, 0L)

        var seekTarget = -1L
        var hintShown = ""

        // Position is 10s, video duration is 1000s (> 90 + 30s)
        controller.checkAutoSkip(
            posMs = 10_000L,
            durMs = 1_000_000L,
            currentIndex = 0,
            queueSize = 2,
            onSeek = { seekTarget = it },
            onNextEpisode = {},
            showHint = { hintShown = it }
        )

        assertEquals(90_000L, seekTarget)
        assertEquals("已跳过片头", hintShown)
        assertEquals(0, controller.introSkippedIndex)

        // Calling again should not seek again
        seekTarget = -1L
        controller.checkAutoSkip(
            posMs = 90_500L,
            durMs = 1_000_000L,
            currentIndex = 0,
            queueSize = 2,
            onSeek = { seekTarget = it },
            onNextEpisode = {},
            showHint = {}
        )
        assertEquals(-1L, seekTarget)
    }

    @Test
    fun testAutoSkipOutro() {
        val controller = PlayerSkipController()
        controller.onIndexChanged(0, 0L)
        controller.introSkippedIndex = 0 // simulate intro already skipped

        var nextCalled = false
        var hintShown = ""

        // Duration 1000s, outroTarget = 910s (910_000ms)
        controller.checkAutoSkip(
            posMs = 915_000L,
            durMs = 1_000_000L,
            currentIndex = 0,
            queueSize = 2,
            onSeek = {},
            onNextEpisode = { nextCalled = true },
            showHint = { hintShown = it }
        )

        assertTrue(nextCalled)
        assertEquals("已跳过片尾，播放下一集", hintShown)
        assertEquals(0, controller.outroSkippedIndex)
    }
}
