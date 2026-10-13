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
import com.github.junrar.Junrar
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

    /** Settings → 测试连接: one tiny search per configured provider, one line each. */
    suspend fun testAll(): List<String> = withContext(Dispatchers.IO) {
        val out = ArrayList<String>()
        if (Repo.store.assrtToken.isNotBlank()) out += try {
            val hits = searchAssrt("Avatar")
            if (hits.isNotEmpty()) "射手网：可用（找到 ${hits.size} 条字幕）" else "射手网：可用（接口响应正常）"
        } catch (e: Exception) { "射手网：失败（${e.message ?: "请求异常"}）" }
        if (Repo.store.subdlKey.isNotBlank()) out += try {
            val hits = searchSubDl("Avatar", null)
            if (hits.isNotEmpty()) "SubDL：可用（找到 ${hits.size} 条字幕）" else "SubDL：可用（接口响应正常）"
        } catch (e: Exception) { "SubDL：失败（${e.message ?: "请求异常"}）" }
        if (Repo.store.osApiKey.isNotBlank()) out += try {
            val hits = searchOpenSubtitles("Avatar")
            if (hits.isNotEmpty()) "OpenSubtitles：可用（找到 ${hits.size} 条字幕）" else "OpenSubtitles：可用（接口响应正常）"
        } catch (e: Exception) { "OpenSubtitles：失败（${e.message ?: "请求异常"}）" }
        if (out.isEmpty()) out += "还没有填任何令牌"
        out
    }

    private val JUNK_TAGS = setOf(
        "2160p", "1080p", "720p", "480p", "4k", "8k", "uhd", "fhd", "hd",
        "hdr10", "hdr10+", "hdr", "dv", "dovi", "dolby", "vision",
        "web-dl", "webdl", "webrip", "bluray", "bdrip", "dvdrip", "hdtv", "remux",
        "x264", "x265", "h264", "h265", "hevc", "avc", "10bit", "8bit",
        "aac", "ac3", "eac3", "ddp", "ddp5.1", "dts", "dts-hd", "dtshd", "atmos", "truehd", "flac", "mp3",
        "proper", "repack", "complete", "subbed", "chs", "cht", "gb", "big5",
        "amzn", "netflix", "nf", "disney", "disney+", "hulu", "bilibili", "bili", "hdctv", "mkv", "mp4",
        "国漫", "日漫", "动漫", "新番", "美剧", "日剧", "韩剧", "电影", "动画", "国语", "粤语", "英语", "中字", "双语"
    )

    private fun isJunkTag(raw: String): Boolean {
        val s = raw.lowercase()
        if (s in JUNK_TAGS) return true
        if (listOf("字幕组", "fansub", "team", "raws", "rip", "studio", "group").any { s.contains(it) }) return true
        return false
    }

    /** File name → search keywords: drops release junk and brackets. */
    fun cleanQuery(name: String): String {
        val s = name.substringBeforeLast('.')
        // Check if format is bracket based e.g. [Group][Category][Title][Episode][Res]
        val brackets = Regex("""[\[【(（](.*?)[\]】)）]""").findAll(s).map { it.groupValues[1].trim() }.toList()
        if (brackets.isNotEmpty()) {
            var titleCandidate = ""
            var epCandidate = ""
            for (b in brackets) {
                val bl = b.lowercase()
                if (isJunkTag(bl)) continue
                if (Regex("""^(2160|1080|720|480)p?$""").matches(bl) || Regex("""^\d{4}$""").matches(b)) continue
                if (Regex("""^\d{1,3}$""").matches(b)) {
                    if (epCandidate.isEmpty()) epCandidate = b
                    continue
                }
                if (Regex("""(?i)^([se]\d{1,3}|s\d{1,2}e\d{1,3}|ep\d{1,3}|第\d{1,3}[集话期])$""").matches(b)) {
                    if (epCandidate.isEmpty()) epCandidate = b
                    continue
                }
                titleCandidate = b
                break
            }
            if (titleCandidate.isNotEmpty()) {
                val ep = if (epCandidate.isNotEmpty() && !titleCandidate.contains(epCandidate)) " $epCandidate" else ""
                return "$titleCandidate$ep".trim()
            }
        }
        return sanitizeTitle(s)
    }

    private fun sanitizeTitle(raw: String): String {
        var q = raw
        q = q.replace(Regex("""[\[\]\(\)\（\）\【\】\{\}\《\》\「\」\『\』]"""), " ")
        q = q.replace('.', ' ').replace('_', ' ')
        q = q.replace(Regex("""(?i)\b(2160p|1080p|720p|480p|4k|8k|uhd|fhd|hdr10\+?|hdr|dv|dovi|dolby(\s*vision)?|web-?dl|webrip|bluray|bdrip|dvdrip|hdtv|remux|x264|x265|h\.?264|h\.?265|hevc|avc|10bit|8bit|aac\d*|ac3|eac3|ddp\d*(\.\d)?|dts(-?hd)?|atmos|truehd|flac|mp3|proper|repack|complete|subbed|chs|cht|gb|big5|amzn|netflix|nf|disney\+?|hulu|bilibili|bili|hdctv)\b"""), " ")
        q = q.replace(Regex("""\s*-\s*"""), " ")
        return q.replace(Regex("""\s+"""), " ").trim()
    }

    /** Generates alternative search queries for relaxed/fallback searching. */
    fun queryVariants(query: String): List<String> {
        val out = LinkedHashSet<String>()
        val trimmed = query.trim()
        if (trimmed.isNotBlank()) out.add(trimmed)

        // Without year: 19xx or 20xx
        val withoutYear = trimmed.replace(Regex("""\b(19\d\d|20\d\d)\b"""), "").replace(Regex("""\s+"""), " ").trim()
        if (withoutYear.length >= 2) out.add(withoutYear)

        // Chinese title part alone (if contains >= 2 Chinese chars)
        val zh = Regex("""[\u4e00-\u9fa5]{2,}""").findAll(trimmed).map { it.value }.joinToString(" ").trim()
        if (zh.length >= 2) out.add(zh)

        // English title part alone (if contains >= 3 letter/number words)
        val enWords = Regex("""[A-Za-z0-9]{2,}(\s+[A-Za-z0-9]{2,})*""").findAll(withoutYear)
            .map { it.value.trim() }
            .filter { it.length >= 3 && !it.matches(Regex("""^\d+$""")) }
            .maxByOrNull { it.length }
        if (!enWords.isNullOrBlank()) out.add(enWords)

        return out.toList()
    }

    private fun episodeOf(name: String): Pair<Int, Int>? =
        Regex("""(?i)S(\d{1,2})E(\d{1,3})""").find(name)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            ?: Regex("""(?i)\bEP?(\d{1,3})\b""").find(name)?.let { 1 to it.groupValues[1].toInt() }
            ?: Regex("""第(\d{1,3})[集话期]""").find(name)?.let { 1 to it.groupValues[1].toInt() }

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        return Repo.http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            r.body?.string() ?: ""
        }
    }

    private fun httpBytes(url: String): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        return Repo.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            r.body?.bytes() ?: ByteArray(0)
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
        val queries = queryVariants(query)
        for (q in queries) {
            val hits = doSearchAssrt(token, q)
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    private fun doSearchAssrt(token: String, q: String): List<Hit> {
        val url = "https://api.assrt.net/v1/sub/search?token=${enc(token)}&q=${enc(q)}&cnt=15&no_muxer=1"
        val resp = httpGet(url)
        val root = json.parseToJsonElement(resp) as? JsonObject ?: return emptyList()
        val status = (root["status"] as? JsonPrimitive)?.contentOrNull
        if (status != null && status != "0") {
            throw IllegalStateException(root.str("errmsg") ?: "接口返回错误（令牌可能不对）")
        }
        val subObj = root["sub"] as? JsonObject
        val subs = (subObj?.get("subs") as? JsonArray) ?: (root["subs"] as? JsonArray) ?: return emptyList()
        return subs.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val title = o.str("videoname")?.takeIf { it.isNotBlank() && it != "不知道" }
                ?: o.str("native_name")?.takeIf { it.isNotBlank() }
                ?: o.str("release_site")?.takeIf { it.isNotBlank() }
                ?: o.str("typename")?.takeIf { it.isNotBlank() }
                ?: "字幕"
            val lang = when (val l = o["lang"]) {
                is JsonObject -> l.str("desc") ?: l.str("langlist") ?: ""
                is JsonPrimitive -> l.contentOrNull ?: ""
                else -> o.str("subtype") ?: ""
            }
            Hit("射手", title, langName(lang), id)
        }
    }

    // ---------------- OpenSubtitles ----------------

    private fun searchOpenSubtitles(query: String): List<Hit> {
        val key = Repo.store.osApiKey
        if (key.isBlank()) return emptyList()
        val queries = queryVariants(query)
        for (q in queries) {
            val hits = doSearchOpenSubtitles(key, q)
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    private fun doSearchOpenSubtitles(key: String, query: String): List<Hit> {
        val url = "https://api.opensubtitles.com/api/v1/subtitles?query=${enc(query)}&languages=zh-cn,zh-tw,en"
        val root = json.parseToJsonElement(httpGet(url, mapOf("Api-Key" to key, "Accept" to "application/json"))) as? JsonObject ?: return emptyList()
        val data = root["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val at = o["attributes"] as? JsonObject ?: return@mapNotNull null
            val files = at["files"] as? JsonArray ?: return@mapNotNull null
            val fid = (files.firstOrNull() as? JsonObject)?.str("file_id") ?: return@mapNotNull null
            val release = at.str("release") ?: (files.firstOrNull() as? JsonObject)?.str("file_name") ?: "字幕"
            Hit("OpenSub", release, langName(at.str("language")), fid)
        }
    }

    // ---------------- SubDL ----------------

    private fun searchSubDl(query: String, ep: Pair<Int, Int>?): List<Hit> {
        val key = Repo.store.subdlKey
        if (key.isBlank()) return emptyList()
        val queries = queryVariants(query)
        for (q in queries) {
            val hits = doSearchSubDl(key, q, ep)
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    private fun doSearchSubDl(key: String, query: String, ep: Pair<Int, Int>?): List<Hit> {
        val cleanTitle = query.replace(Regex("""(?i)\b(S\d{1,2}E\d{1,3}|EP?\d{1,3}|第\d{1,3}[集话期])\b"""), "").trim()
        val searchName = cleanTitle.ifEmpty { query }
        var url = "https://api.subdl.com/api/v1/subtitles?api_key=${enc(key)}&film_name=${enc(searchName)}&languages=ZH,ZH_BG,EN"
        if (ep != null) url += "&season_number=${ep.first}&episode_number=${ep.second}"
        val resp = httpGet(url)
        val root = json.parseToJsonElement(resp) as? JsonObject ?: return emptyList()
        if ((root["status"] as? JsonPrimitive)?.contentOrNull == "false") {
            val msg = root.str("message") ?: root.str("error") ?: "API Key 无效"
            if (msg.contains("not found", ignoreCase = true)) return emptyList()
            throw IllegalStateException(msg)
        }
        val subs = root["subtitles"] as? JsonArray ?: return emptyList()
        return subs.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val dl = o.str("download_url") ?: o.str("url") ?: return@mapNotNull null
            val title = o.str("release_name") ?: o.str("name") ?: "字幕"
            Hit("SubDL", title, langName(o.str("lang")), dl)
        }
    }

    // ---------------- download ----------------

    /** Downloads the picked hit into [dir]; returns a UTF-8 subtitle file ready to load. */
    suspend fun fetch(hit: Hit, fileTitle: String, dir: File): File {
        return withContext(Dispatchers.IO) {
            dir.mkdirs()
            val (bytes, hintFilename) = when (hit.provider) {
                "射手" -> fetchAssrt(hit.ref, fileTitle)
                "OpenSub" -> fetchOpenSubtitles(hit.ref) to null
                else -> httpBytes(hit.ref) to null
            }
            val (data, rawExt) = unwrap(bytes, hintFilename ?: fileTitle, dir)
            val utf8Data = toUtf8(data)
            val ext = hintExt(utf8Data, rawExt)
            val safe = fileTitle.replace(Regex("""[^\p{L}\p{N}]+"""), "_").take(40).trim('_').ifEmpty { "sub" }
            val out = File(dir, "${safe}_${hit.provider}.$ext")
            out.writeBytes(utf8Data)
            out
        }
    }

    private fun fetchAssrt(id: String, fileTitle: String): Pair<ByteArray, String?> {
        val token = Repo.store.assrtToken
        val url = "https://api.assrt.net/v1/sub/detail?token=${enc(token)}&id=${enc(id)}"
        val root = json.parseToJsonElement(httpGet(url)) as? JsonObject
            ?: throw IllegalStateException("射手网无响应")
        val subObj = root["sub"] as? JsonObject
        val subs = subObj?.get("subs") ?: root["subs"]
        val rec = when (subs) {
            is JsonObject -> subs
            is JsonArray -> subs.firstOrNull() as? JsonObject
            else -> null
        } ?: throw IllegalStateException("找不到该字幕的下载信息")
        val filelist = rec["filelist"] as? JsonArray
        if (filelist != null && filelist.isNotEmpty()) {
            val files = filelist.mapNotNull { it as? JsonObject }
                .mapNotNull { f ->
                    val name = f.str("f") ?: f.str("filename") ?: f.str("name") ?: ""
                    val fileUrl = f.str("url") ?: f.str("link") ?: ""
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in SUB_EXTS && fileUrl.isNotBlank()) {
                        Triple(name, fileUrl, ext)
                    } else null
                }
            if (files.isNotEmpty()) {
                val ep = episodeOf(fileTitle)
                val pick = (if (ep != null) files.firstOrNull { (n, _, _) ->
                    Regex("""(?i)(S0?${ep.first}E0?${ep.second}\b|E0?${ep.second}\b|第0?${ep.second}[集话期])""").containsMatchIn(n)
                } else null) ?: files.first()
                return httpBytes(pick.second) to pick.first
            }
        }
        val whole = rec.str("url") ?: throw IllegalStateException("字幕文件无下载地址")
        val wholeName = rec.str("filename") ?: rec.str("native_name")
        return httpBytes(whole) to wholeName
    }

    private fun fetchOpenSubtitles(fileId: String): ByteArray {
        val key = Repo.store.osApiKey
        val body = """{"file_id": $fileId}""".toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://api.opensubtitles.com/api/v1/download")
            .header("User-Agent", UA).header("Api-Key", key)
            .header("Accept", "application/json")
            .post(body).build()
        val text = Repo.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("OpenSubtitles 下载失败 HTTP ${r.code}（免费额度可能已用完）")
            r.body?.string() ?: ""
        }
        val root = json.parseToJsonElement(text) as? JsonObject ?: throw IllegalStateException("OpenSubtitles 无响应")
        val link = root.str("link") ?: throw IllegalStateException("OpenSubtitles 没有返回下载链接")
        return httpBytes(link)
    }

    val SUB_EXTS = listOf("srt", "ass", "ssa", "vtt")

    /** If [bytes] are a zip or rar, extract the best subtitle inside; otherwise pass through. */
    fun unwrap(bytes: ByteArray, hintName: String, tempDir: File? = null): Pair<ByteArray, String> {
        val ep = episodeOf(hintName)

        // 1. Check ZIP: 'PK\x03\x04' or 'PK'
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            val entries = ArrayList<Pair<String, ByteArray>>()
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        val ext = e.name.substringAfterLast('.', "").lowercase()
                        if (!e.isDirectory && ext in SUB_EXTS) {
                            entries.add(e.name to zin.readBytes())
                        }
                        zin.closeEntry()
                    }
                }
            } catch (_: Exception) {}
            if (entries.isNotEmpty()) {
                val pick = (if (ep != null) entries.firstOrNull { (name, _) ->
                    Regex("""(?i)(S0?${ep.first}E0?${ep.second}\b|E0?${ep.second}\b|第0?${ep.second}[集话期])""").containsMatchIn(name)
                } else null) ?: entries.maxByOrNull { it.second.size }!!
                return pick.second to pick.first.substringAfterLast('.', "srt").lowercase()
            }
        }

        // 2. Check RAR: 'Rar!\x1a\x07'
        if (bytes.size >= 4 && bytes[0] == 0x52.toByte() && bytes[1] == 0x61.toByte() && bytes[2] == 0x72.toByte() && bytes[3] == 0x21.toByte()) {
            val baseDir = tempDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
            val outDir = File(baseDir, "rar_${System.currentTimeMillis()}_${(1000..9999).random()}")
            outDir.mkdirs()
            try {
                val extracted = Junrar.extract(ByteArrayInputStream(bytes), outDir)
                val subs = extracted.filter { f ->
                    f.isFile && f.extension.lowercase() in SUB_EXTS
                }
                if (subs.isNotEmpty()) {
                    val pick = (if (ep != null) subs.firstOrNull { f ->
                        Regex("""(?i)(S0?${ep.first}E0?${ep.second}\b|E0?${ep.second}\b|第0?${ep.second}[集话期])""").containsMatchIn(f.name)
                    } else null) ?: subs.maxByOrNull { it.length() }!!
                    return pick.readBytes() to pick.extension.lowercase()
                }
            } catch (_: Exception) {
            } finally {
                outDir.deleteRecursively()
            }
        }

        val ext = hintExt(bytes, hintName)
        return bytes to ext
    }

    fun hintExt(utf8Bytes: ByteArray, hintNameOrExt: String? = null): String {
        val ext = hintNameOrExt?.substringAfterLast('.', "")?.lowercase()?.ifEmpty { hintNameOrExt?.lowercase() }
        val head = try {
            val len = minOf(utf8Bytes.size, 2048)
            String(utf8Bytes, 0, len, StandardCharsets.UTF_8).trimStart()
        } catch (_: Exception) { "" }
        return when {
            head.contains("[Script Info]", ignoreCase = true) || head.contains("Dialogue:", ignoreCase = true) -> "ass"
            head.startsWith("WEBVTT", ignoreCase = true) -> "vtt"
            ext in SUB_EXTS -> ext!!
            else -> "srt"
        }
    }

    /** Decodes [bytes] to valid UTF-8, handling UTF-16 LE/BE, UTF-8 BOM, GB18030, and Big5. */
    fun toUtf8(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        // 1. UTF-8 with BOM: EF BB BF
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return bytes.copyOfRange(3, bytes.size)
        }
        // 2. UTF-16 LE with BOM: FF FE
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            val text = String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
            return text.trimStart('\uFEFF').toByteArray(StandardCharsets.UTF_8)
        }
        // 3. UTF-16 BE with BOM: FE FF
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            val text = String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
            return text.trimStart('\uFEFF').toByteArray(StandardCharsets.UTF_8)
        }
        // 4. UTF-16 LE without BOM
        if (bytes.size >= 8 && bytes[1] == 0.toByte() && bytes[3] == 0.toByte() && bytes[5] == 0.toByte() && bytes[7] == 0.toByte()) {
            val text = String(bytes, StandardCharsets.UTF_16LE)
            return text.trimStart('\uFEFF').toByteArray(StandardCharsets.UTF_8)
        }
        // 5. UTF-16 BE without BOM
        if (bytes.size >= 8 && bytes[0] == 0.toByte() && bytes[2] == 0.toByte() && bytes[4] == 0.toByte() && bytes[6] == 0.toByte()) {
            val text = String(bytes, StandardCharsets.UTF_16BE)
            return text.trimStart('\uFEFF').toByteArray(StandardCharsets.UTF_8)
        }
        // 6. Strict UTF-8 decoding
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            try {
                String(bytes, charset("GB18030"))
            } catch (_: Exception) {
                try {
                    String(bytes, charset("Big5"))
                } catch (_: Exception) {
                    String(bytes, StandardCharsets.UTF_8)
                }
            }
        }
        return text.trimStart('\uFEFF').toByteArray(StandardCharsets.UTF_8)
    }

    fun shiftSubtitle(text: String, ext: String, offsetMs: Long): String {
        if (offsetMs == 0L || text.isBlank()) return text
        return when (ext.lowercase()) {
            "srt", "vtt" -> shiftSrtOrVtt(text, offsetMs)
            "ass", "ssa" -> shiftAss(text, offsetMs)
            else -> text
        }
    }

    private fun shiftSrtOrVtt(text: String, offsetMs: Long): String {
        val regex = Regex("""(\d{2}):(\d{2}):(\d{2})([,.])(\d{3})""")
        return regex.replace(text) { m ->
            val h = m.groupValues[1].toLong()
            val min = m.groupValues[2].toLong()
            val s = m.groupValues[3].toLong()
            val sep = m.groupValues[4]
            val ms = m.groupValues[5].toLong()
            val totalMs = (h * 3600_000 + min * 60_000 + s * 1000 + ms + offsetMs).coerceAtLeast(0)
            val newH = totalMs / 3600_000
            val newMin = (totalMs % 3600_000) / 60_000
            val newS = (totalMs % 60_000) / 1000
            val newMs = totalMs % 1000
            "%02d:%02d:%02d%s%03d".format(java.util.Locale.US, newH, newMin, newS, sep, newMs)
        }
    }

    private fun shiftAss(text: String, offsetMs: Long): String {
        val regex = Regex("""(\d{1,2}):(\d{2}):(\d{2})\.(\d{2})""")
        return regex.replace(text) { m ->
            val h = m.groupValues[1].toLong()
            val min = m.groupValues[2].toLong()
            val s = m.groupValues[3].toLong()
            val cs = m.groupValues[4].toLong() * 10
            val totalMs = (h * 3600_000 + min * 60_000 + s * 1000 + cs + offsetMs).coerceAtLeast(0)
            val newH = totalMs / 3600_000
            val newMin = (totalMs % 3600_000) / 60_000
            val newS = (totalMs % 60_000) / 1000
            val newCs = (totalMs % 1000) / 10
            "%01d:%02d:%02d.%02d".format(java.util.Locale.US, newH, newMin, newS, newCs)
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
