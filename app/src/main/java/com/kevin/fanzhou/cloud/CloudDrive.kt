package com.kevin.fanzhou.cloud

import com.kevin.fanzhou.data.DavEntry
import com.kevin.fanzhou.data.PlayItem
import com.kevin.fanzhou.data.Repo
import com.kevin.fanzhou.data.Source
import com.kevin.fanzhou.data.SubTrack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

data class CloudFile(
    val id: String,
    val name: String,
    val isDir: Boolean,
    val size: Long = 0,
    /** Provider-specific extra id (115 pickcode, aliyun drive id). */
    val extra: String? = null,
    /** Last modified time, epoch ms (0 = unknown). */
    val modified: Long = 0,
    /** Thumbnail URL, if the provider has one (load with [CloudDrive.thumbHeaders]). */
    val thumb: String? = null,
    /** Video duration in ms (0 = unknown). */
    val duration: Long = 0,
) {
    val ext get() = name.substringAfterLast('.', "").lowercase()
    val isVideo get() = !isDir && ext in DavEntry.VIDEO_EXT
    val isSubtitle get() = !isDir && ext in SubTrack.SUB_EXT
}

data class Resolved(val url: String, val headers: Map<String, String>, val mime: String? = null)

abstract class CloudDrive(var source: Source) {
    abstract val rootId: String
    abstract suspend fun list(dirId: String): List<CloudFile>
    /** Direct (original quality) link. Blocking, called from loader threads. */
    abstract suspend fun resolve(file: CloudFile): Resolved
    /** Server-side transcoded stream (HLS) as a fallback, when available. */
    open suspend fun resolveTranscode(file: CloudFile): Resolved? = null
    /** Transcoded renditions the provider offers, best first (label to stream). */
    open suspend fun qualities(file: CloudFile): List<Pair<String, Resolved>> =
        resolveTranscode(file)?.let { listOf("转码" to it) } ?: emptyList()
    /** Storage (used bytes, total bytes) when the provider exposes it. */
    open suspend fun usage(): Pair<Long, Long>? = null
    /** Headers needed to load [CloudFile.thumb] images. */
    open val thumbHeaders: Map<String, String> get() = emptyMap()
    /** Moves [file] to the drive's recycle bin. */
    open suspend fun delete(file: CloudFile): Unit = throw java.io.IOException("该网盘暂不支持删除")
    open suspend fun rename(file: CloudFile, newName: String): Unit = throw java.io.IOException("该网盘暂不支持重命名")
    /** Called once after login to fill drive ids etc. */
    open suspend fun prepare(): Source = source

    protected fun save(update: (Source) -> Source) {
        source = update(source)
        Repo.store.upsert(source)
    }

    protected fun updateCookies(res: Response, cookie: String): String {
        var c = cookie
        res.headers("Set-Cookie").forEach { sc ->
            val kv = sc.substringBefore(';')
            val k = kv.substringBefore('=').trim()
            val v = kv.substringAfter('=', "")
            if (k.isNotEmpty() && v.isNotEmpty() && v != "deleted") c = setCookie(c, k, v)
        }
        return c
    }

    fun playItem(f: CloudFile, siblings: List<CloudFile>): PlayItem {
        val base = f.name.substringBeforeLast('.')
        val subs = siblings.filter { it.isSubtitle && it.name.startsWith(base) }.map { s ->
            val tag = s.name.removePrefix(base).removeSuffix(".${s.ext}").trim('.', ' ', '_', '-')
            SubTrack(uri(s), tag.ifEmpty { s.ext.uppercase() }, SubTrack.mimeFor(s.ext), tag.ifEmpty { null })
        }
        val key = "cloud:${source.id}:${f.id}"
        return PlayItem(
            url = uri(f),
            title = f.name.substringBeforeLast('.'),
            startMs = Repo.store.position(key),
            sourceId = source.id,
            resumeKey = key,
            subtitles = subs,
            cloudFile = f,
        )
    }

    fun uri(f: CloudFile): String =
        "cloud://${source.id}/${android.net.Uri.encode(f.id)}?x=${android.net.Uri.encode(f.extra ?: "")}&n=${android.net.Uri.encode(f.name)}"

    companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun setCookie(cookie: String, key: String, value: String): String {
            val parts = cookie.split(';').map { it.trim() }.filter { it.isNotEmpty() && it.substringBefore('=') != key }
            return (parts + "$key=$value").joinToString("; ")
        }

        fun create(source: Source): CloudDrive = when (source.type) {
            Source.TYPE_115 -> Drive115(source)
            Source.TYPE_QUARK -> QuarkDrive(source)
            Source.TYPE_ALIYUN -> AliyunDrive(source)
            Source.TYPE_GDRIVE -> GoogleDrive(source)
            else -> throw IllegalArgumentException(source.type)
        }

        private val drives = ConcurrentHashMap<String, CloudDrive>()
        fun get(sourceId: String): CloudDrive? {
            val s = Repo.store.get(sourceId) ?: return null
            val d = drives[sourceId]
            if (d != null && d.source.type == s.type && d.source.token == s.token) return d
            return create(s).also { drives[sourceId] = it }
        }

        // Cache resolved links: they are reused for every seek/range request.
        private val linkCache = ConcurrentHashMap<String, Pair<Long, Resolved>>()
        fun fromUri(uri: android.net.Uri): Pair<String, CloudFile>? {
            if (uri.scheme != "cloud") return null
            val sid = uri.host ?: return null
            val id = uri.pathSegments.firstOrNull() ?: return null
            return sid to CloudFile(id, uri.getQueryParameter("n") ?: "", false, 0, uri.getQueryParameter("x")?.ifEmpty { null })
        }

        fun invalidate(sourceId: String, fileId: String) { linkCache.remove("$sourceId/$fileId") }

        suspend fun resolveCached(sourceId: String, file: CloudFile): Resolved {
            val key = "$sourceId/${file.id}"
            linkCache[key]?.let { (t, r) -> if (System.currentTimeMillis() - t < 10 * 60_000) return r }
            val d = get(sourceId) ?: throw IllegalStateException("媒体源不存在")
            val r = d.resolve(file)
            linkCache[key] = System.currentTimeMillis() to r
            return r
        }
    }
}

// ---- small JSON helpers ----
fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
fun JsonObject.long(k: String): Long = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0L
fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.let { c -> c == "1" || c == "true" } }
fun JsonElement?.obj(): JsonObject? = this as? JsonObject

/** Accepts epoch seconds or epoch ms (as number or numeric string) and returns epoch ms. */
fun epochMs(v: Long): Long = when {
    v <= 0 -> 0
    v < 100_000_000_000L -> v * 1000
    else -> v
}

/** Parses "2024-01-02T03:04:05.000Z", "2024-01-02 03:04[:05]" or a numeric epoch to epoch ms; 0 on failure. */
fun parseIsoTime(s: String?): Long {
    val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return 0
    t.toLongOrNull()?.let { return epochMs(it) }
    t.toDoubleOrNull()?.let { return epochMs(it.toLong()) }
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
    )
    for (p in patterns) {
        try {
            val f = java.text.SimpleDateFormat(p, java.util.Locale.US)
            if (p.endsWith("'Z'")) f.timeZone = java.util.TimeZone.getTimeZone("UTC")
            else if (!p.contains("XXX")) f.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            f.isLenient = false
            return f.parse(t)?.time ?: continue
        } catch (_: Exception) {}
    }
    return 0
}

/** Reads a numeric field that may be a number, a numeric string or a decimal string. */
fun JsonObject.num(k: String): Double = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.toDoubleOrNull() ?: 0.0
