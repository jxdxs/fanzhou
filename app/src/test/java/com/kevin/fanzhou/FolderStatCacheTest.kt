package com.kevin.fanzhou

import com.kevin.fanzhou.cloud.CloudFile
import com.kevin.fanzhou.data.FolderStatCache
import com.kevin.fanzhou.data.Repo
import com.kevin.fanzhou.ui.folderDesc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FolderStatCacheTest {

    @Before
    fun setup() {
        Repo.init(RuntimeEnvironment.getApplication())
        FolderStatCache.clear()
    }

    @Test
    fun testFromListCalculation() {
        val files = listOf(
            CloudFile("1", "Episode 1.mp4", isDir = false, size = 1000L),
            CloudFile("2", "Episode 2.mkv", isDir = false, size = 2000L),
            CloudFile("3", "Subtitles", isDir = true, size = 0L),
            CloudFile("4", "Notes.txt", isDir = false, size = 50L),
        )

        val stat = FolderStatCache.fromList(files)
        assertEquals(4, stat.total)
        assertEquals(2, stat.videoCount)
        assertEquals(1, stat.dirCount)
        assertEquals(1, stat.otherCount)
        assertEquals(3050L, stat.totalSize)
    }

    @Test
    fun testCachePutGetAndPersistence() {
        val sid = "source_123"
        val dirId = "folder_abc"

        assertNull(FolderStatCache.get(sid, dirId))

        val files = listOf(
            CloudFile("v1", "movie.mp4", isDir = false, size = 5000000L),
            CloudFile("d1", "extra", isDir = true),
        )
        FolderStatCache.put(sid, dirId, files)

        val cached = FolderStatCache.get(sid, dirId)
        assertNotNull(cached)
        assertEquals(2, cached!!.total)
        assertEquals(1, cached.videoCount)
        assertEquals(1, cached.dirCount)
        assertEquals(5000000L, cached.totalSize)
    }

    @Test
    fun testFolderDescFormatting() {
        val sid = "source_test"

        // 1. Cached stat with videos and other items
        val dirWithMixed = CloudFile("d_mix", "Series", isDir = true)
        FolderStatCache.put(sid, "d_mix", listOf(
            CloudFile("1", "ep1.mp4", false),
            CloudFile("2", "ep2.mp4", false),
            CloudFile("3", "subs", true),
        ))
        assertEquals("2 个视频 · 共 3 项", folderDesc(dirWithMixed, sid))

        // 2. Cached stat with only videos
        val dirAllVids = CloudFile("d_vids", "Season 1", isDir = true)
        FolderStatCache.put(sid, "d_vids", listOf(
            CloudFile("1", "ep1.mp4", false),
            CloudFile("2", "ep2.mp4", false),
        ))
        assertEquals("2 个视频", folderDesc(dirAllVids, sid))

        // 3. Cached stat empty
        val dirEmpty = CloudFile("d_empty", "Empty", isDir = true)
        FolderStatCache.put(sid, "d_empty", emptyList())
        assertEquals("空文件夹", folderDesc(dirEmpty, sid))

        // 4. Native childCount
        val dirNative = CloudFile("d_native", "CloudFolder", isDir = true, childCount = 42)
        assertEquals("42 项", folderDesc(dirNative, sid))

        // 5. Native childCount == 0
        val dirNativeZero = CloudFile("d_zero", "ZeroFolder", isDir = true, childCount = 0)
        assertEquals("空文件夹", folderDesc(dirNativeZero, sid))

        // 6. Native size
        val dirWithSize = CloudFile("d_size", "SizedFolder", isDir = true, size = 1024L * 1024 * 50)
        assertEquals("文件夹 · 50 MB", folderDesc(dirWithSize, sid))

        // 7. Generic fallback
        val dirPlain = CloudFile("d_plain", "PlainFolder", isDir = true)
        assertEquals("文件夹", folderDesc(dirPlain, sid))
    }
}
