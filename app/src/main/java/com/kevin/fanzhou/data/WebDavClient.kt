package com.kevin.fanzhou.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.URLDecoder

/** A browsable file source (WebDAV, SMB) shown by the shared folder screen. */
interface FileBrowser {
    val source: Source
    val rootUrl: String
    suspend fun list(url: String = rootUrl): List<DavEntry>
    fun playItem(e: DavEntry, siblings: List<DavEntry> = emptyList()): PlayItem
    /** Display name of a folder url. */
    fun folderName(url: String): String =
        java.net.URLDecoder.decode(url.trimEnd('/').substringAfterLast('/').replace("+", "%2B"), "UTF-8")
    suspend fun delete(e: DavEntry)
    suspend fun rename(e: DavEntry, newName: String)
}

class WebDavClient(override val source: Source) : FileBrowser {
    private val root = source.url.trimEnd('/') + "/"
    override val rootUrl get() = root

    val headers: Map<String, String>
        get() = if (source.username.isNotEmpty())
            mapOf("Authorization" to Credentials.basic(source.username, source.password, Charsets.UTF_8))
        else emptyMap()

    override suspend fun list(url: String): List<DavEntry> = withContext(Dispatchers.IO) {
        val body = """<?xml version="1.0" encoding="utf-8"?><D:propfind xmlns:D="DAV:"><D:prop><D:displayname/><D:resourcetype/><D:getcontentlength/><D:getlastmodified/></D:prop></D:propfind>"""
        val b = Request.Builder().url(url)
            .header("Depth", "1")
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
        headers.forEach { (k, v) -> b.header(k, v) }
        val xml = Repo.http.newCall(b.build()).execute().use { r ->
            if (r.code == 401) throw IOException("认证失败 (401)")
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string() ?: ""
        }
        val self = url.toHttpUrl()
        parse(xml, url).filter { e ->
            val p = e.href.toHttpUrl().encodedPath.trimEnd('/')
            p != self.encodedPath.trimEnd('/')
        }.sortedWith(compareBy<DavEntry>({ !it.isDir }, { naturalKey(it.name) }))
    }

    suspend fun test() { list(root) }

    private fun send(b: Request.Builder, what: String) {
        headers.forEach { (k, v) -> b.header(k, v) }
        Repo.http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException(when (r.code) {
                401, 403 -> "没有权限$what（HTTP ${r.code}）"
                405 -> "服务器不允许$what（HTTP 405），可能是只读目录"
                412 -> "已有同名文件"
                else -> "${what}失败（HTTP ${r.code}）"
            })
        }
    }

    override suspend fun delete(e: DavEntry) = withContext(Dispatchers.IO) {
        send(Request.Builder().url(e.href).delete(), "删除")
    }

    override suspend fun rename(e: DavEntry, newName: String) = withContext(Dispatchers.IO) {
        val parent = e.href.trimEnd('/').substringBeforeLast('/') + "/"
        val dest = parent + java.net.URLEncoder.encode(newName, "UTF-8").replace("+", "%20") + if (e.isDir) "/" else ""
        send(Request.Builder().url(e.href).method("MOVE", null).header("Destination", davDestination(dest)).header("Overwrite", "F"), "重命名")
    }

    /** Path-only Destination: reverse proxies that terminate TLS (e.g. fnOS) reject an https://
     *  Destination with 502/403 because the backend sees http. Tested on Kevin's fnOS gateway. */
    private fun davDestination(abs: String): String =
        abs.toHttpUrlOrNull()?.let { u -> u.encodedPath + (u.encodedQuery?.let { "?$it" } ?: "") } ?: abs

    private fun parse(xml: String, requestUrl: String): List<DavEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))
        val out = ArrayList<DavEntry>()
        var href: String? = null
        var name: String? = null
        var isDir = false
        var size = 0L
        var modified: String? = null
        var text = StringBuilder()
        val reqUrl = requestUrl.toHttpUrl()
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (parser.name) {
                        "response" -> { href = null; name = null; isDir = false; size = 0; modified = null }
                        "collection" -> isDir = true
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "href" -> href = text.toString().trim()
                    "displayname" -> name = text.toString().trim().ifEmpty { null }
                    "getcontentlength" -> size = text.toString().trim().toLongOrNull() ?: 0
                    "getlastmodified" -> modified = text.toString().trim()
                    "response" -> {
                        val h = href
                        if (h != null) {
                            val abs = reqUrl.resolve(h)?.toString() ?: h
                            val fallback = URLDecoder.decode(h.trimEnd('/').substringAfterLast('/').replace("+", "%2B"), "UTF-8")
                            out.add(DavEntry(name ?: fallback, abs, isDir, size, modified, parseHttpDate(modified)))
                        }
                    }
                }
            }
            ev = parser.next()
        }
        return out
    }

    override fun playItem(e: DavEntry, siblings: List<DavEntry>): PlayItem {
        return playItemInner(e).copy(subtitles = siblingSubtitles(e, siblings))
    }

    private fun playItemInner(e: DavEntry) = PlayItem(
        url = e.href,
        title = e.name.substringBeforeLast('.'),
        headers = headers,
        startMs = Repo.store.position(e.href),
        sourceId = source.id,
        resumeKey = e.href,
    )

    companion object {
        fun parseHttpDate(s: String?): Long {
            if (s.isNullOrBlank()) return 0
            return try {
                java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(s)?.time ?: 0
            } catch (_: Exception) { 0 }
        }

        /** Subtitle files next to a video whose name starts with the video's base name. */
        fun siblingSubtitles(e: DavEntry, siblings: List<DavEntry>): List<SubTrack> {
            val base = e.name.substringBeforeLast('.')
            return siblings.filter { s ->
                !s.isDir && s.name.substringAfterLast('.', "").lowercase() in SubTrack.SUB_EXT && s.name.startsWith(base)
            }.map { s ->
                val ext = s.name.substringAfterLast('.')
                val tag = s.name.removePrefix(base).removeSuffix(".$ext").trim('.', ' ', '_', '-')
                SubTrack(s.href, tag.ifEmpty { ext.uppercase() }, SubTrack.mimeFor(ext), tag.ifEmpty { null })
            }
        }

        fun naturalKey(s: String): String =
            Regex("\\d+").replace(s.lowercase()) { it.value.padStart(8, '0') }
    }
}
