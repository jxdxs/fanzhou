package com.kevin.fanzhou.subtitle

import com.kevin.fanzhou.cloud.CloudDrive
import com.kevin.fanzhou.cloud.str
import com.kevin.fanzhou.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/**
* Online subtitle search across assrt (射手网), SubDL and OpenSubtitles.
* Each provider needs the user's own credential (Settings → 在线字幕);
* providers without one are skipped. Downloads are normalized to UTF-8
* (many Chinese subtitles ship as GBK/Big5) and zips are unpacked.
*/
object OnlineSubtitles {

data class Hit(
val provider: String,
val title: String,
val lang: String,
/** Provider reference: assrt subtitle id, SubDL download url, or OpenSubtitles file id. */
val ref: String,
)

private const val UA = "Fanzhou/2.3 (Android)"
private val json = CloudDrive.json

fun anyConfigured(): Boolean =
Repo.store.assrtToken.isNotBlank() || Repo.store.subdlKey.isNotBlank() || Repo.store.osApiKey.isNotBlank()

/** File name → search keywords: drops release junk but keeps SxxExx. */
fun cleanQuery(name: String): String {
var q = name.substringBeforeLast('.')
q = q.replace(Regex("""[\[(（]"""), " ")
q = q.replace('.', ' ').replace('_', ' ')
q = q.replace(Regex("""(?i)\b(2160p|1080p|720p|480p|4k|uhd|hdr10\+?|dolby|vision|web-?dl|webrip|bluray|bdrip|dvdrip|hdtv|x264|x265|h\.?264|h\.?265|hevc|aac|dts|atmos|remux|proper|repack)\b"""), " ")
return q.replace(Regex("""\s+"""), " ").trim()
}

private fun episodeOf(name: String): Pair<Int, Int>? =
Regex("""(?i)S(\d{1,2})E(\d{1,3})""").find(name)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt()}

private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String {
val b = Request.Builder().url(url).header("User-Agent", UA)
headers.forEach { (k, v) -> b.header(k, v)}
return Repo.http.newCall(b.build()).execute().use { r ->
if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
r.body?.string()?: ""
}
}

private fun httpBytes(url: String): ByteArray {
val req = Request.Builder().url(url).header("User-Agent", UA).build()
return Repo.http.newCall(req).execute().use { r ->
if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
r.body?.bytes()?: ByteArray(0)
}
}

private fun langName(raw: String?): String = when {
raw == null -> ""
raw.contains("简") || raw.equals("zh-cn", true) || raw.equals("ZH", true) || raw.contains("chs", true) -> "简体"
raw.contains("繁") || raw.equals("zh-tw", true) || raw.equals("ZH_BG", true) || raw.contains("cht", true) -> "繁体"
raw.contains("双语") -> "双语"
raw.contains("英") || raw.equals("en", true) || raw.equals("EN", true) -> "英文"
raw.contains("日") || raw.equals("ja", true) -> "日语"
raw.contains("韩") || raw.equals("ko", true) -> "韩语"
else -> raw
}

/**
 * Searches all configured providers off the main thread. If every configured
 * provider failed (bad token, no network...), throws with the first error so
 * the UI can say what actually went wrong instead of a bare "no results".
 */
suspend fun searchAll(query: String, fileName: String): List<Hit> = withContext(Dispatchers.IO) {
val ep = episodeOf(fileName)
val errors = ArrayList<String>()
fun runProvider(name: String, block: () -> List<Hit>): List<Hit> =
try { block() } catch (e: Exception) {
if (name.isNotEmpty()) errors.add("$name：${e.message ?: "请求失败"}")
emptyList()
}
coroutineScope {
val a = async { runProvider(if (Repo.store.assrtToken.isBlank()) "" else "射手网") { searchAssrt(query) } }
val b = async { runProvider(if (Repo.store.osApiKey.isBlank()) "" else "OpenSubtitles") { searchOpenSubtitles(query) } }
val c = async { runProvider(if (Repo.store.subdlKey.isBlank()) "" else "SubDL") { searchSubDl(query, ep) } }
val hits = a.await() + b.await() + c.await()
if (hits.isEmpty() && errors.isNotEmpty()) throw IllegalStateException(errors.first())
hits
}
}

// ---------------- assrt (射手网) ----------------

private fun searchAssrt(query: String): List<Hit> {
val token = Repo.store.assrtToken
if (token.isBlank()) return emptyList()
val url = "https://api.assrt.net/v1/sub/search?token=${enc(token)}&q=${enc(query)}&cnt=15&no_muxer=1"
val root = json.parseToJsonElement(httpGet(url)) as? JsonObject?: return emptyList()
if ((root["status"] as? JsonPrimitive)?.contentOrNull!= "0") {
throw IllegalStateException(root.str("errmsg") ?: "接口返回错误（令牌可能不对）")
}
val subs = root["subs"] as? JsonArray?: return emptyList()
return subs.mapNotNull { e ->
val o = e as? JsonObject?: return@mapNotNull null
val id = o.str("id")?: return@mapNotNull null
val title = o.str("videoname")?: o.str("native_name")?: "字幕"
val lang = when (val l = o["lang"]) {
is JsonObject -> l.str("desc")?: ""
is JsonPrimitive -> l.contentOrNull?: ""
else -> ""
}
Hit("射手", title, langName(lang), id)
}
}

// ---------------- OpenSubtitles ----------------

private fun searchOpenSubtitles(query: String): List<Hit> {
val key = Repo.store.osApiKey
if (key.isBlank()) return emptyList()
val url = "https://api.opensubtitles.com/api/v1/subtitles?query=${enc(query)}&languages=zh-cn,zh-tw,en"
val root = json.parseToJsonElement(httpGet(url, mapOf("Api-Key" to key))) as? JsonObject?: return emptyList()
val data = root["data"] as? JsonArray?: return emptyList()
return data.mapNotNull { e ->
val o = e as? JsonObject?: return@mapNotNull null
val at = o["attributes"] as? JsonObject?: return@mapNotNull null
val files = at["files"] as? JsonArray?: return@mapNotNull null
val fid = (files.firstOrNull() as? JsonObject)?.str("file_id")?: return@mapNotNull null
val release = at.str("release")?: (files.firstOrNull() as? JsonObject)?.str("file_name")?: "字幕"
Hit("OpenSub", release, langName(at.str("language")), fid)
}
}

// ---------------- SubDL ----------------

private fun searchSubDl(query: String, ep: Pair<Int, Int>?): List<Hit> {
val key = Repo.store.subdlKey
if (key.isBlank()) return emptyList()
var url = "https://api.subdl.com/api/v1/subtitles?api_key=${enc(key)}&film_name=${enc(query)}&languages=ZH,ZH_BG,EN"
if (ep!= null) url += "&season_number=${ep.first}&episode_number=${ep.second}"
val root = json.parseToJsonElement(httpGet(url)) as? JsonObject?: return emptyList()
val subs = root["subtitles"] as? JsonArray?: return emptyList()
return subs.mapNotNull { e ->
val o = e as? JsonObject?: return@mapNotNull null
val dl = o.str("download_url")?: o.str("url")?: return@mapNotNull null
val title = o.str("release_name")?: o.str("name")?: "字幕"
Hit("SubDL", title, langName(o.str("lang")), dl)
}
}

// ---------------- download ----------------

/** Downloads the picked hit into [dir]; returns a UTF-8 subtitle file ready to load. */
suspend fun fetch(hit: Hit, fileTitle: String, dir: File): File = withContext(Dispatchers.IO) {
dir.mkdirs()
val bytes = when (hit.provider) {
"射手" -> fetchAssrt(hit.ref, fileTitle)
"OpenSub" -> fetchOpenSubtitles(hit.ref)
else -> httpBytes(hit.ref)
}
val (data, ext) = unwrap(bytes, fileTitle)
val safe = fileTitle.replace(Regex("""[^\p{L}\p{N}]+"""), "_").take(40).trim('_').ifEmpty { "sub"}
val out = File(dir, "${safe}_${hit.provider}.$ext")
out.writeBytes(toUtf8(data))
return out
}

private fun fetchAssrt(id: String, fileTitle: String): ByteArray {
val token = Repo.store.assrtToken
val url = "https://api.assrt.net/v1/sub/detail?token=${enc(token)}&id=${enc(id)}"
val root = json.parseToJsonElement(httpGet(url)) as? JsonObject
?: throw IllegalStateException("射手网无响应")
val subs = root["subs"]
val rec = when (subs) {
is JsonObject -> subs
is JsonArray -> subs.firstOrNull() as? JsonObject
else -> null
}?: throw IllegalStateException("找不到该字幕的下载信息")
val filelist = rec["filelist"] as? JsonArray
if (filelist!= null && filelist.isNotEmpty()) {
val files = filelist.mapNotNull { it as? JsonObject}
.filter { f -> SUB_EXTS.any { ext -> (f.str("filename")?: "").lowercase().endsWith(".$ext")}}
if (files.isNotEmpty()) {
val ep = episodeOf(fileTitle)
val pick = (if (ep!= null) files.firstOrNull { f ->
val n = f.str("filename")?: ""
Regex("""(?i)(S0?${ep.first}E0?${ep.second}\b|E0?${ep.second}\b)""").containsMatchIn(n)
} else null)?: files.first()
return httpBytes(pick.str("url")?: throw IllegalStateException("字幕文件无下载地址"))
}
}
val whole = rec.str("url")?: throw IllegalStateException("字幕文件无下载地址")
return httpBytes(whole)
}

private fun fetchOpenSubtitles(fileId: String): ByteArray {
val key = Repo.store.osApiKey
val body = """{"file_id": $fileId}""".toRequestBody("application/json".toMediaType())
val req = Request.Builder()
.url("https://api.opensubtitles.com/api/v1/download")
.header("User-Agent", UA).header("Api-Key", key)
.post(body).build()
val text = Repo.http.newCall(req).execute().use { r ->
if (!r.isSuccessful) throw IllegalStateException("OpenSubtitles 下载失败 HTTP ${r.code}（免费额度可能已用完）")
r.body?.string()?: ""
}
val root = json.parseToJsonElement(text) as? JsonObject?: throw IllegalStateException("OpenSubtitles 无响应")
val link = root.str("link")?: throw IllegalStateException("OpenSubtitles 没有返回下载链接")
return httpBytes(link)
}

private val SUB_EXTS = listOf("srt", "ass", "ssa", "vtt")

/** If [bytes] are a zip, extract the best subtitle inside; otherwise pass through with ext sniffing. */
private fun unwrap(bytes: ByteArray, hintName: String): Pair<ByteArray, String> {
if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
val entries = ArrayList<Pair<String, ByteArray>>()
ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
while (true) {
val e = zin.nextEntry?: break
val ext = e.name.substringAfterLast('.', "").lowercase()
if (!e.isDirectory && ext in SUB_EXTS) entries.add(e.name to zin.readBytes())
zin.closeEntry()
}
}
val pick = entries.maxByOrNull { it.second.size}?: throw IllegalStateException("压缩包里没有字幕文件")
return pick.second to pick.first.substringAfterLast('.', "srt").lowercase()
}
val ext = hintExt(bytes)
return bytes to ext
}

private fun hintExt(bytes: ByteArray): String {
val head = try {
String(bytes, 0, minOf(bytes.size, 512), StandardCharsets.UTF_8).trimStart()
} catch (e: Exception) { ""}
return when {
head.startsWith("[Script Info]", ignoreCase = true) || head.contains("Dialogue:", ignoreCase = true) -> "ass"
head.startsWith("WEBVTT", ignoreCase = true) -> "vtt"
else -> "srt"
}
}

/** Many Chinese subtitles are GBK/Big5; players want UTF-8. */
private fun toUtf8(bytes: ByteArray): ByteArray {
val text = try {
StandardCharsets.UTF_8.newDecoder()
.onMalformedInput(CodingErrorAction.REPORT)
.onUnmappableCharacter(CodingErrorAction.REPORT)
.decode(ByteBuffer.wrap(bytes)).toString()
} catch (e: CharacterCodingException) {
String(bytes, charset("GB18030"))
}
return text.trimStart('﻿').toByteArray(StandardCharsets.UTF_8)
}

private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
