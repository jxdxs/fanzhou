package com.kevin.fanzhou

import com.kevin.fanzhou.cloud.Drive123
import com.kevin.fanzhou.data.Source
import com.kevin.fanzhou.data.WebDavClient
import com.kevin.fanzhou.ui.extract115Cookies
import com.kevin.fanzhou.ui.parse123Token
import com.kevin.fanzhou.ui.parseAliyunToken
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudLoginAndDriveTest {

    @Test
    fun testExtract115Cookies() {
        val raw = "acw_tc=123; UID=10001_A; expires=Wed, 21 Oct 2026; CID=CID_VAL_123; domain=.115.com; SEID=SEID_VAL_XYZ; KID=KID_ABC; path=/"
        val res = extract115Cookies(raw)
        assertNotNull(res)
        assertTrue(res!!.contains("UID=10001_A"))
        assertTrue(res.contains("CID=CID_VAL_123"))
        assertTrue(res.contains("SEID=SEID_VAL_XYZ"))
        assertTrue(res.contains("KID=KID_ABC"))

        // Missing CID
        val invalid = "UID=10001; SEID=abc"
        assertNull(extract115Cookies(invalid))
    }

    @Test
    fun testParseAliyunToken() {
        val rawJson = """{"default_drive_id":"drive_123","user_id":"u_999","refresh_token":"rf_token_abc123","access_token":"acc_xyz"}"""
        val res = parseAliyunToken(rawJson)
        assertNotNull(res)
        assertEquals("rf_token_abc123", res!!.first)
        assertEquals("u_999", res.second["user_id"])
        assertEquals("acc_xyz", res.second["access"])
        assertEquals("drive_123", res.second["default_drive"])

        // EvaluateJavascript quoted string
        val quoted = "\"{\\\"refresh_token\\\":\\\"rf_quoted\\\",\\\"userId\\\":\\\"u_quoted\\\"}\""
        val resQuoted = parseAliyunToken(quoted)
        assertNotNull(resQuoted)
        assertEquals("rf_quoted", resQuoted!!.first)
        assertEquals("u_quoted", resQuoted.second["user_id"])
    }

    @Test
    fun testParse123Token() {
        val rawJson = """{"code":0,"data":{"token":"token_123_abc"}}"""
        val token = parse123Token(rawJson)
        assertEquals("Bearer token_123_abc", token)

        val directString = "Bearer my_secret_token"
        assertEquals("Bearer my_secret_token", parse123Token(directString))

        val plainToken = "eyJhbGciOi..."
        assertEquals("Bearer eyJhbGciOi...", parse123Token(plainToken))
    }

    @Test
    fun testEpisodeNaturalSort() {
        val filenames = listOf(
            "Game of Thrones S01E10.mp4",
            "Game of Thrones S01E02.mp4",
            "Game of Thrones S01E01.mp4",
            "Game of Thrones S01E09.mp4"
        )
        val sorted = filenames.sortedBy { WebDavClient.naturalKey(it) }
        assertEquals(
            listOf(
                "Game of Thrones S01E01.mp4",
                "Game of Thrones S01E02.mp4",
                "Game of Thrones S01E09.mp4",
                "Game of Thrones S01E10.mp4"
            ),
            sorted
        )
    }

    @Test
    fun testDrive123Creation() {
        val src = Source(id = "test123", type = Source.TYPE_123, name = "123网盘", url = "", token = "test_token")
        val drive = com.kevin.fanzhou.cloud.CloudDrive.create(src)
        assertTrue(drive is Drive123)
        assertEquals("0", drive.rootId)
    }
}
