package com.kevin.fanzhou.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSubtitlesTest {

    @Test
    fun testCleanQueryBracketBlock() {
        val q1 = OnlineSubtitles.cleanQuery("[GM-Team][国漫][凡人修仙传][2020][1080p].mp4")
        assertEquals("凡人修仙传", q1)

        val q2 = OnlineSubtitles.cleanQuery("【诸神字幕组】[进击的巨人][Shingeki no Kyojin][01][1080P].mkv")
        assertTrue(q2.contains("进击的巨人"))
        assertFalse(q2.contains("【") || q2.contains("】") || q2.contains("[") || q2.contains("]"))
    }

    @Test
    fun testCleanQueryStandardReleases() {
        val q1 = OnlineSubtitles.cleanQuery("Friends.S01E01.1080p.BluRay.x264.mkv")
        assertEquals("Friends S01E01", q1)

        val q2 = OnlineSubtitles.cleanQuery("肖申克的救赎.The.Shawshank.Redemption.1994.1080p.BluRay.x264.mkv")
        assertEquals("肖申克的救赎 The Shawshank Redemption 1994", q2)

        val q3 = OnlineSubtitles.cleanQuery("流浪地球2.The.Wandering.Earth.II.2023.2160p.WEB-DL.H265.AAC-HDCTV.mkv")
        assertFalse(q3.contains("WEB-DL", ignoreCase = true))
        assertFalse(q3.contains("H265", ignoreCase = true))
        assertFalse(q3.contains("AAC", ignoreCase = true))
    }

    @Test
    fun testQueryVariants() {
        val v = OnlineSubtitles.queryVariants("肖申克的救赎 The Shawshank Redemption 1994")
        assertTrue(v.contains("肖申克的救赎 The Shawshank Redemption 1994"))
        assertTrue(v.contains("肖申克的救赎"))
        assertTrue(v.contains("The Shawshank Redemption"))

        val v2 = OnlineSubtitles.queryVariants("凡人修仙传 2020")
        assertTrue(v2.contains("凡人修仙传 2020"))
        assertTrue(v2.contains("凡人修仙传"))
    }

    @Test
    fun testToUtf8Encodings() {
        val original = "1\n00:00:01,000 --> 00:00:02,000\n你好，世界！"
        val utf8Bytes = original.toByteArray(Charsets.UTF_8)
        assertEquals(original, String(OnlineSubtitles.toUtf8(utf8Bytes), Charsets.UTF_8))

        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8Bytes
        assertEquals(original, String(OnlineSubtitles.toUtf8(utf8Bom), Charsets.UTF_8))

        val utf16Le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + original.toByteArray(Charsets.UTF_16LE)
        assertEquals(original, String(OnlineSubtitles.toUtf8(utf16Le), Charsets.UTF_8))

        val utf16Be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + original.toByteArray(Charsets.UTF_16BE)
        assertEquals(original, String(OnlineSubtitles.toUtf8(utf16Be), Charsets.UTF_8))

        val gbBytes = original.toByteArray(charset("GB18030"))
        assertEquals(original, String(OnlineSubtitles.toUtf8(gbBytes), Charsets.UTF_8))
    }

    @Test
    fun testHintExt() {
        val assContent = "[Script Info]\nTitle: Test\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello"
        assertEquals("ass", OnlineSubtitles.hintExt(assContent.toByteArray(Charsets.UTF_8)))

        val vttContent = "WEBVTT\n\n00:01.000 --> 00:02.000\nHello"
        assertEquals("vtt", OnlineSubtitles.hintExt(vttContent.toByteArray(Charsets.UTF_8)))

        val srtContent = "1\n00:00:01,000 --> 00:00:02,000\nHello"
        assertEquals("srt", OnlineSubtitles.hintExt(srtContent.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun testUnwrapZip() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nSub".toByteArray(Charsets.UTF_8)
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("movie.chs.srt"))
            zos.write(srt)
            zos.closeEntry()
        }
        val (data, ext) = OnlineSubtitles.unwrap(baos.toByteArray(), "movie.mp4")
        assertEquals("srt", ext)
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nSub", String(data, Charsets.UTF_8))
    }

    @Test
    fun testShiftSubtitleSrt() {
        val srt = "1\n00:01:20,500 --> 00:01:23,000\nHello"
        val shiftedForward = OnlineSubtitles.shiftSubtitle(srt, "srt", 1500)
        assertTrue(shiftedForward.contains("00:01:22,000 --> 00:01:24,500"))

        val shiftedBackward = OnlineSubtitles.shiftSubtitle(srt, "srt", -1000)
        assertTrue(shiftedBackward.contains("00:01:19,500 --> 00:01:22,000"))
    }

    @Test
    fun testShiftSubtitleAss() {
        val ass = "Dialogue: 0,0:01:20.50,0:01:23.00,Default,,0,0,0,,Hello"
        val shifted = OnlineSubtitles.shiftSubtitle(ass, "ass", 1500)
        assertTrue(shifted.contains("0:01:22.00,0:01:24.50"))
    }
}
