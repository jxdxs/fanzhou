package com.kevin.fanzhou.cloud

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.kevin.fanzhou.data.Repo
import com.kevin.fanzhou.data.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class Drive115(source: Source) : CloudDrive(source) {
    override val rootId = "0"
    private val cookie get() = source.token ?: ""

    private fun req(url: String) = Request.Builder().url(url).header("Cookie", cookie).header("User-Agent", UA)

    override suspend fun list(dirId: String): List<CloudFile> = withContext(Dispatchers.IO) {
        val out = ArrayList<CloudFile>()
        var offset = 0
        while (true) {
            val url = "https://webapi.115.com/files?aid=1&cid=$dirId&o=file_name&asc=1&offset=$offset&show_dir=1&limit=1000&snap=0&natsort=1&record_open_time=1&format=json&fc_mix=0"
            val body = Repo.http.newCall(req(url).build()).execute().use { it.body?.string() ?: "" }
            val o = json.parseToJsonElement(body).jsonObject
            if (o.bool("state") != true) throw IOException(o.str("error") ?: o.str("msg") ?: "115 请求失败，可能需要重新登录")
            val data = o["data"] as? JsonArray ?: JsonArray(emptyList())
            data.forEach { e ->
                val it = e as? JsonObject ?: return@forEach
                val fid = it.str("fid")
                // 115 times: "te"/"tp"/"tu" are epoch-second strings; "t" is either epoch seconds or "yyyy-MM-dd HH:mm".
                val mod = listOf("te", "tu", "t", "tp").firstNotNullOfOrNull { k -> parseIsoTime(it.str(k)).takeIf { v -> v > 0 } } ?: 0L
                if (fid != null) {
                    val rawThumb = it.str("thumb") ?: it.str("snap") ?: it.str("u")
                    val thumbUrl = when {
                        rawThumb.isNullOrEmpty() -> null
                        rawThumb.startsWith("http://") || rawThumb.startsWith("https://") -> rawThumb
                        rawThumb.startsWith("//") -> "https:$rawThumb"
                        else -> null
                    }
                    out.add(
                        CloudFile(
                            fid, it.str("n") ?: "", false, it.long("s"), it.str("pc"),
                            modified = mod,
                            thumb = thumbUrl,
                            duration = (it.num("play_long") * 1000).toLong(),
                        )
                    )
                } else {
                    val count = it.intOrNull("c") ?: it.intOrNull("fc") ?: it.intOrNull("count")
                    out.add(
                        CloudFile(
                            id = it.str("cid") ?: "",
                            name = it.str("n") ?: "",
                            isDir = true,
                            size = it.long("s"),
                            modified = mod,
                            childCount = count,
                        )
                    )
                }
            }
            val count = o.long("count").toInt()
            offset += data.size
            if (data.isEmpty() || offset >= count) break
        }
        out
    }

    override val thumbHeaders get() = mapOf("Referer" to "https://115.com/", "User-Agent" to UA, "Cookie" to cookie)

    override suspend fun resolve(file: CloudFile): Resolved = withContext(Dispatchers.IO) {
        val pc = file.extra ?: throw IOException("缺少 pickcode")
        val key = M115.generateKey()
        val payload = buildJsonObject { put("pickcode", pc) }.toString().toByteArray()
        val form = FormBody.Builder().add("data", M115.encode(payload, key)).build()
        val r = Repo.http.newCall(req("https://proapi.115.com/app/chrome/downurl?t=${System.currentTimeMillis() / 1000}").post(form).build()).execute()
        val playCookie = updateCookies(r, cookie)
        val body = r.use { it.body?.string() ?: "" }
        val o = json.parseToJsonElement(body).jsonObject
        if (o.bool("state") != true) throw IOException(o.str("msg") ?: o.str("error") ?: "115 获取播放地址失败")
        val enc = (o["data"] as? JsonPrimitive)?.content ?: throw IOException("115 返回数据为空")
        val info = json.parseToJsonElement(String(M115.decode(enc, key))).jsonObject
        val first = info.values.firstOrNull()?.obj() ?: throw IOException("115 未返回下载信息")
        val url = first["url"].obj()?.str("url")?.takeIf { it.isNotEmpty() } ?: throw IOException("115 未返回下载地址")
        Resolved(url, mapOf("Cookie" to playCookie, "User-Agent" to UA))
    }

    override suspend fun resolveTranscode(file: CloudFile): Resolved? {
        val pc = file.extra ?: return null
        return Resolved("https://115.com/api/video/m3u8/$pc.m3u8", mapOf("Cookie" to cookie, "User-Agent" to UA), "application/x-mpegURL")
    }

    override suspend fun qualities(file: CloudFile): List<Pair<String, Resolved>> = withContext(Dispatchers.IO) {
        val master = resolveTranscode(file) ?: return@withContext emptyList()
        val text = runCatching {
            Repo.http.newCall(req(master.url).build()).execute().use { it.body?.string() ?: "" }
        }.getOrDefault("")
        val lines = text.lines().map { it.trim() }
        val out = mutableListOf<Triple<Int, String, Resolved>>()
        lines.forEachIndexed { i, l ->
            if (!l.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@forEachIndexed
            val abs = master.url.toHttpUrlOrNull()?.resolve(uri)?.toString() ?: return@forEachIndexed
            val h = Regex("RESOLUTION=\\d+x(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val name = Regex("NAME=\"([^\"]+)\"").find(l)?.groupValues?.get(1)
            val label = when {
                h >= 2000 -> "4K"; h >= 1400 -> "2K"; h >= 1000 -> "超清 1080P"; h >= 700 -> "高清 720P"; h >= 460 -> "标清 480P"; h > 0 -> "流畅 ${h}P"
                else -> name ?: "转码"
            }
            out += Triple(h, label, Resolved(abs, master.headers, "application/x-mpegURL"))
        }
        if (out.isEmpty()) listOf("转码" to master)
        else out.sortedByDescending { it.first }.distinctBy { it.second }.map { it.second to it.third }
    }

    private fun postForm(url: String, form: FormBody, what: String) {
        val body = Repo.http.newCall(req(url).post(form).build()).execute().use { it.body?.string() ?: "" }
        val o = json.parseToJsonElement(body).jsonObject
        if (o.bool("state") != true) throw IOException(o.str("error") ?: o.str("msg") ?: "115 ${what}失败")
    }

    override suspend fun rename(file: CloudFile, newName: String) = withContext(Dispatchers.IO) {
        postForm("https://webapi.115.com/files/batch_rename", FormBody.Builder().add("files_new_name[${file.id}]", newName).build(), "重命名")
    }

    override suspend fun delete(file: CloudFile) = withContext(Dispatchers.IO) {
        postForm("https://webapi.115.com/rb/delete", FormBody.Builder().add("fid[0]", file.id).add("ignore_warn", "1").build(), "删除")
    }

    override suspend fun usage(): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val body = Repo.http.newCall(req("https://webapi.115.com/files/index_info").build()).execute().use { it.body?.string() ?: "" }
        val o = json.parseToJsonElement(body).jsonObject
        val space = o["data"].obj()?.get("space_info").obj() ?: return@withContext null
        val total = space["all_total"].obj()?.long("size") ?: 0
        val used = space["all_use"].obj()?.long("size") ?: 0
        if (total > 0) used to total else null
    }

    class Login : QrLogin {
        override val appHint = "打开 115 App 扫码登录"
        private var uid = ""; private var time = 0L; private var sign = ""
        private val longPoll = Repo.http.newBuilder().readTimeout(40, TimeUnit.SECONDS).build()

        override suspend fun start(): String = withContext(Dispatchers.IO) {
            val body = Repo.http.newCall(Request.Builder().url("https://qrcodeapi.115.com/api/1.0/web/1.0/token/").header("User-Agent", UA).build())
                .execute().use { it.body?.string() ?: "" }
            val d = json.parseToJsonElement(body).jsonObject["data"].obj() ?: throw IOException("获取二维码失败")
            uid = d.str("uid") ?: ""; time = d.long("time"); sign = d.str("sign") ?: ""
            d.str("qrcode") ?: throw IOException("获取二维码失败")
        }

        override suspend fun poll(): QrState = withContext(Dispatchers.IO) {
            val body = try {
                longPoll.newCall(Request.Builder().url("https://qrcodeapi.115.com/get/status/?uid=$uid&time=$time&sign=$sign&_=${System.currentTimeMillis()}").header("User-Agent", UA).build())
                    .execute().use { it.body?.string() ?: "" }
            } catch (e: java.net.SocketTimeoutException) { return@withContext QrState.Waiting }
            val d = json.parseToJsonElement(body).jsonObject["data"].obj()
            when (d?.long("status")?.toInt() ?: 0) {
                1 -> QrState.Scanned
                -1 -> QrState.Expired
                -2 -> QrState.Failed("已取消登录")
                2 -> {
                    val form = FormBody.Builder().add("account", uid).add("app", APP).build()
                    val res = Repo.http.newCall(Request.Builder().url("https://passportapi.115.com/app/1.0/$APP/1.0/login/qrcode/").header("User-Agent", UA).post(form).build())
                        .execute().use { it.body?.string() ?: "" }
                    val c = json.parseToJsonElement(res).jsonObject["data"].obj()?.get("cookie").obj()
                        ?: return@withContext QrState.Failed("登录失败：$res".take(120))
                    QrState.Done("UID=${c.str("UID")}; CID=${c.str("CID")}; SEID=${c.str("SEID")}; KID=${c.str("KID")}")
                }
                else -> QrState.Waiting
            }
        }
    }

    companion object {
        const val UA = "Mozilla/5.0 115Browser/27.0.5.7"
        const val APP = "alipaymini"
    }
}
