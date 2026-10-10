package com.kevin.fanzhou

import com.kevin.fanzhou.data.DavEntry
import com.kevin.fanzhou.data.Source
import com.kevin.fanzhou.data.WebDavClient
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Live check against a real WebDAV server; skipped unless FZ_DAV_URL/USER/PASS are set. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class DavOpsTest {
    @Test fun renameAndDelete() = runBlocking {
        val url = System.getenv("FZ_DAV_URL"); assumeTrue(url != null)
        (System.getenv("https_proxy") ?: System.getenv("HTTPS_PROXY"))?.let { p ->
            val u = java.net.URI(p)
            System.setProperty("https.proxyHost", u.host); System.setProperty("https.proxyPort", u.port.toString())
            u.userInfo?.let { ui ->
                val (pu, pp) = ui.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "")
                java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                    override fun getPasswordAuthentication() = java.net.PasswordAuthentication(java.net.URLDecoder.decode(pu, "UTF-8"), java.net.URLDecoder.decode(pp, "UTF-8").toCharArray())
                })
            }
        }
        val c = WebDavClient(Source(id = "t", type = Source.TYPE_WEBDAV, name = "t", url = url!!, username = System.getenv("FZ_DAV_USER")!!, password = System.getenv("FZ_DAV_PASS")!!))
        val f = DavEntry("a b.txt", url.trimEnd('/') + "/a%20b.txt", false, 1, null)
        c.rename(f, "重命名 测试.txt")
        val g = DavEntry("重命名 测试.txt", url.trimEnd('/') + "/" + java.net.URLEncoder.encode("重命名 测试.txt", "UTF-8").replace("+", "%20"), false, 1, null)
        c.delete(g)
        val d = DavEntry("sub", url.trimEnd('/') + "/sub/", true, 0, null)
        c.rename(d, "sub2")
        c.delete(DavEntry("sub2", url.trimEnd('/') + "/sub2/", true, 0, null))
        println("DAVOPS OK")
    }
}
