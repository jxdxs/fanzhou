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

    @Test
    fun testSeriesSpecificSkip() {
        val seriesKey = "cloud:test_folder_123"
        Repo.store.setIntroSkipForSeries(seriesKey, 45)
        Repo.store.setOutroSkipForSeries(seriesKey, 60)

        val controller = PlayerSkipController()
        controller.onIndexChanged(0, 0L)

        var seekTarget = -1L
        var skippedFrom = -1L
        var skippedTo = -1L

        controller.checkAutoSkip(
            posMs = 5000L,
            durMs = 1_000_000L,
            currentIndex = 0,
            queueSize = 2,
            seriesKey = seriesKey,
            onSeek = { seekTarget = it },
            onNextEpisode = {},
            showHint = {},
            onIntroSkipped = { from, to ->
                skippedFrom = from
                skippedTo = to
            }
        )

        assertEquals(45_000L, seekTarget)
        assertEquals(5000L, skippedFrom)
        assertEquals(45_000L, skippedTo)

        // Test outro with series override (dur 1_000_000 - 60_000 = 940_000)
        var nextCalled = false
        controller.checkAutoSkip(
            posMs = 945_000L,
            durMs = 1_000_000L,
            currentIndex = 0,
            queueSize = 2,
            seriesKey = seriesKey,
            onSeek = {},
            onNextEpisode = { nextCalled = true },
            showHint = {}
        )
        assertTrue(nextCalled)
    }

    @Test
    fun testChapterBasedSkip() {
        val controller = PlayerSkipController()
        controller.chapters = listOf(
            com.kevin.fanzhou.player.PlayerChapter(0L, "Prologue"),
            com.kevin.fanzhou.player.PlayerChapter(15_000L, "OP: Theme Song"),
            com.kevin.fanzhou.player.PlayerChapter(105_000L, "Episode Part 1"),
            com.kevin.fanzhou.player.PlayerChapter(920_000L, "ED: Ending Credits"),
        )
        controller.onIndexChanged(0, 0L)
        // Reset chapters since onIndexChanged clears them for a new item
        controller.chapters = listOf(
            com.kevin.fanzhou.player.PlayerChapter(0L, "Prologue"),
            com.kevin.fanzhou.player.PlayerChapter(15_000L, "OP: Theme Song"),
            com.kevin.fanzhou.player.PlayerChapter(105_000L, "Episode Part 1"),
            com.kevin.fanzhou.player.PlayerChapter(920_000L, "ED: Ending Credits"),
        )

        var seekTarget = -1L
        var hintShown = ""
        var skippedFrom = -1L

        // Auto skip intro according to OP chapter end (105_000L)
        controller.checkAutoSkip(
            posMs = 10_000L,
            durMs = 1_000_000L,
            currentIndex = 0,
            queueSize = 2,
            onSeek = { seekTarget = it },
            onNextEpisode = {},
            showHint = { hintShown = it },
            onIntroSkipped = { from, _ -> skippedFrom = from }
        )

        assertEquals(105_000L, seekTarget)
        assertEquals("已按章节跳过片头", hintShown)
        assertEquals(10_000L, skippedFrom)

        // Auto skip outro according to ED chapter start (920_000L)
        var nextCalled = false
        controller.checkAutoSkip(
            posMs = 925_000L,
            durMs = 1_000_000L,
            currentIndex = 0,
            queueSize = 2,
            onSeek = {},
            onNextEpisode = { nextCalled = true },
            showHint = { hintShown = it }
        )

        assertTrue(nextCalled)
        assertEquals("已按章节跳过片尾，播放下一集", hintShown)
    }

    @Test
    fun testPlayItemSeriesKey() {
        val cloudItem = com.kevin.fanzhou.data.PlayItem(
            title = "Episode 01",
            url = "http://example.com/play/1.mp4",
            cloudFolderKey = "folder_abc"
        )
        assertEquals("cloud:folder_abc", cloudItem.seriesKey)

        val jfItem = com.kevin.fanzhou.data.PlayItem(
            title = "Episode 01",
            url = "http://jf.local/stream",
            sourceId = "jf_srv",
            jfItemId = "jf_item_1"
        )
        assertEquals("jf:jf_srv:Episode 01", jfItem.seriesKey)

        val webDavItem = com.kevin.fanzhou.data.PlayItem(
            title = "Episode 01",
            url = "http://dav.local/Anime/SousouNoFrieren/EP01.mkv?token=123",
            sourceId = "dav_1"
        )
        assertEquals("dir:dav_1:http://dav.local/Anime/SousouNoFrieren", webDavItem.seriesKey)
    }
}
