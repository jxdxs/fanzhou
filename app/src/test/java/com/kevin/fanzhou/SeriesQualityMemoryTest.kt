package com.kevin.fanzhou

import com.kevin.fanzhou.cloud.Resolved
import com.kevin.fanzhou.cloud.CloudFile
import com.kevin.fanzhou.data.PlayItem
import com.kevin.fanzhou.data.Repo
import com.kevin.fanzhou.player.PlayerCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SeriesQualityMemoryTest {

    @Before
    fun setup() {
        Repo.init(RuntimeEnvironment.getApplication())
    }

    @Test
    fun testStoreQualityForSeries() {
        val seriesCloudKey = "cloud:pan115:folder_episodes_123"
        val seriesJfKey = "jf:home_jellyfin:Breaking Bad"

        // Initially null
        assertNull(Repo.store.qualityForSeries(seriesCloudKey))
        assertNull(Repo.store.qualityForSeries(seriesJfKey))

        // Save quality preference
        Repo.store.setQualityForSeries(seriesCloudKey, "高清 720P")
        Repo.store.setQualityForSeries(seriesJfKey, "超清 1080P · 10Mbps")

        assertEquals("高清 720P", Repo.store.qualityForSeries(seriesCloudKey))
        assertEquals("超清 1080P · 10Mbps", Repo.store.qualityForSeries(seriesJfKey))

        // Reset to original (clears setting)
        Repo.store.setQualityForSeries(seriesCloudKey, null)
        assertNull(Repo.store.qualityForSeries(seriesCloudKey))

        // Backward compatibility with quality_folder_
        Repo.store.setQualityForFolder("folder_legacy_999", "流畅 480P")
        assertEquals("流畅 480P", Repo.store.qualityForSeries("folder_legacy_999"))
    }

    @Test
    fun testMatchQualityExact() {
        val qs = listOf(
            "超清 1080P" to Resolved("http://cdn/1080p.mp4", emptyMap(), "video/mp4"),
            "高清 720P" to Resolved("http://cdn/720p.mp4", emptyMap(), "video/mp4"),
            "标清 480P" to Resolved("http://cdn/480p.mp4", emptyMap(), "video/mp4"),
        )

        val hit = PlayerCore.matchQuality(qs, "高清 720P")
        assertNotNull(hit)
        assertEquals("高清 720P", hit!!.first)
        assertEquals("http://cdn/720p.mp4", hit.second.url)
    }

    @Test
    fun testMatchQualityFuzzyResolution() {
        val qs = listOf(
            "1080P 高码率" to Resolved("http://cdn/1080p.mp4", emptyMap(), "video/mp4"),
            "720P 极速" to Resolved("http://cdn/720p.mp4", emptyMap(), "video/mp4"),
        )

        // Target has different wording but same resolution "1080P"
        val hit1080 = PlayerCore.matchQuality(qs, "超清 1080P · 10Mbps")
        assertNotNull(hit1080)
        assertEquals("1080P 高码率", hit1080!!.first)

        val hit720 = PlayerCore.matchQuality(qs, "高清 720P")
        assertNotNull(hit720)
        assertEquals("720P 极速", hit720!!.first)
    }

    @Test
    fun testMatchQualityNextLowerFallback() {
        // When next episode does not have 1080P, it should fallback to the closest next-lower tier (720P)
        val qs = listOf(
            "720P" to Resolved("http://cdn/720p.mp4", emptyMap(), "video/mp4"),
            "480P" to Resolved("http://cdn/480p.mp4", emptyMap(), "video/mp4"),
            "360P" to Resolved("http://cdn/360p.mp4", emptyMap(), "video/mp4"),
        )

        val hit = PlayerCore.matchQuality(qs, "1080P")
        assertNotNull(hit)
        // Closest tier below 1080P is 720P
        assertEquals("720P", hit!!.first)
    }

    @Test
    fun testMatchQualityOriginalReturnsNull() {
        val qs = listOf(
            "1080P" to Resolved("http://cdn/1080p.mp4", emptyMap(), "video/mp4"),
        )
        assertNull(PlayerCore.matchQuality(qs, "原画"))
        assertNull(PlayerCore.matchQuality(qs, null))
        assertNull(PlayerCore.matchQuality(qs, ""))
    }

    @Test
    fun testPlayItemSeriesKey() {
        val cloudItem = PlayItem(
            url = "cloud://pan115/files/file1.mkv",
            title = "Episode 1",
            sourceId = "pan115",
            cloudFolderKey = "pan115:dir_anime_s1",
            cloudFile = CloudFile(id = "file1", name = "Episode 1.mkv", isDir = false, size = 1000L)
        )
        assertEquals("cloud:pan115:dir_anime_s1", cloudItem.seriesKey)

        val jfItem = PlayItem(
            url = "http://jellyfin/stream",
            title = "Episode 2",
            subtitle = "S01E02",
            sourceId = "jf_local",
            jfItemId = "jf_item_123"
        )
        assertEquals("jf:jf_local:Episode 2", jfItem.seriesKey)
    }
}
