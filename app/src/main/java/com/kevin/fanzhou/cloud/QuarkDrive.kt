package com.kevin.fanzhou.cloud

import com.kevin.fanzhou.data.Repo
import com.kevin.fanzhou.data.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

class QuarkDrive(source: Source) : CloudDrive(source) {
    override val rootId = "0"
    private val cookie get() = source.token ?: ""

    private fun call(path: String, query: String = "", body: String? = null): JsonObject {
        val url = "$API$path?pr=ucpro&fr=pc&uc_param_str=$query"
        val b = Request.Builder().url(url)
            .header("Cookie", cookie).header("Referer", REFERER).header("User-Agent", UA)
            .header("Accept", "application/json, text/plain, */*")
        if (body != null) b.post(body.toRequestBody("application/json".toMediaType()))
        val r = Repo.http.newCall(b.build()).execute()
        val newCookie = updateCookies(r, cookie)
        if (newCookie != cookie) save { it.copy(token = newCookie) }
        val text = r.use { it.body?.string() ?: "" }
        val o = json.parseToJsonElement(text).jsonObject
        val status = o.long("status")
        if (status >= 400 || o.long("code") != 0L) throw IOException(o.str("message") ?: "夸克请求失败 ($status)，可能需要重新登录")
        return o
    }

    override suspend fun list(dirId: String): List<CloudFile> = withContext(Dispatchers.IO) {
        val out = ArrayList<CloudFile>()
        var page = 1
        while (true) {
            val o = call("/file/sort", "&pdir_fid=$dirId&_page=$page&_size=100&_fetch_total=1&fetch_all_file=1&fetch_risk_file_name=1&_sort=file_type:asc,file_name:asc")
            val list = o["data"].obj()?.get("list") as? JsonArray ?: JsonArray(emptyList())
            list.forEach { e ->
                val f = e as? JsonObject ?: return@forEach
                val isFile = f.bool("file") ?: true
                // duration: seconds, either top-level "duration" or inside "video_max_resolution"/"video_info" when present.
                val durSec = f.num("duration").takeIf { it > 0 }
                    ?: f["video_max_resolution"].obj()?.num("duration")?.takeIf { it > 0 }
                    ?: f["video_info"].obj()?.num("duration") ?: 0.0
                out.add(
                    CloudFile(
                        f.str("fid") ?: "", android.text.Html.fromHtml(f.str("file_name") ?: "", 0).toString(), !isFile, f.long("size"),
                        modified = epochMs(listOf("updated_at", "l_updated_at", "created_at").map { k -> f.long(k) }.firstOrNull { it > 0 } ?: 0),
                        thumb = (f.str("thumbnail") ?: f.str("big_thumbnail"))?.takeIf { it.startsWith("http") },
                        duration = (durSec * 1000).toLong(),
                    )
                )
            }
            val total = o["metadata"].obj()?.long("_total") ?: 0
            if (list.isEmpty() || page * 100 >= total) break
            page++
        }
        out
    }

    override val thumbHeaders get() = mapOf("Referer" to "$REFERER/", "User-Agent" to UA)

    override suspend fun rename(file: CloudFile, newName: String) = withContext(Dispatchers.IO) {
        call("/file/rename", body = buildJsonObject { put("fid", file.id); put("file_name", newName) }.toString()); Unit
    }

    override suspend fun delete(file: CloudFile) = withContext(Dispatchers.IO) {
        call("/file/delete", body = """{"action_type":2,"filelist":["${file.id}"],"exclude_fids":[]}"""); Unit
    }

    override suspend fun resolve(file: CloudFile): Resolved = withContext(Dispatchers.IO) {
        val body = buildJsonObject { putJsonArray("fids") { add(file.id) } }.toString()
        val o = call("/file/download", body = body)
        val url = (o["data"] as? JsonArray)?.firstOrNull()?.obj()?.str("download_url") ?: throw IOException("夸克未返回下载地址")
        Resolved(url, mapOf("Cookie" to cookie, "Referer" to REFERER, "User-Agent" to UA))
    }

    override suspend fun resolveTranscode(file: CloudFile): Resolved? = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("fid", file.id); put("resolutions", "low,normal,high,super,2k,4k"); put("supports", "fmp4_av,m3u8,dolby_vision")
        }.toString()
        val o = call("/file/v2/play/project", body = body)
        val list = o["data"].obj()?.get("video_list") as? JsonArray ?: return@withContext null
        val url = list.mapNotNull { it.obj()?.get("video_info").obj()?.str("url")?.takeIf { u -> u.isNotEmpty() } }.firstOrNull()
            ?: return@withContext null
        Resolved(url, mapOf("Referer" to REFERER, "User-Agent" to UA), if (url.contains(".m3u8")) "application/x-mpegURL" else null)
    }

    override suspend fun qualities(file: CloudFile): List<Pair<String, Resolved>> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("fid", file.id); put("resolutions", "low,normal,high,super,2k,4k"); put("supports", "fmp4_av,m3u8,dolby_vision")
        }.toString()
        val o = call("/file/v2/play/project", body = body)
        val list = o["data"].obj()?.get("video_list") as? JsonArray ?: return@withContext emptyList()
        val names = mapOf("4k" to "4K", "2k" to "2K", "super" to "超清 1080P", "high" to "高清 720P", "normal" to "标清 480P", "low" to "流畅 360P")
        val order = names.keys.toList()
        list.mapNotNull { it.obj() }.mapNotNull { v ->
            val info = v["video_info"].obj() ?: return@mapNotNull null
            val url = info.str("url")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val res = v.str("resolution") ?: info.str("resolution") ?: ""
            val h = info.long("height")
            Triple(order.indexOf(res).let { if (it < 0) 99 else it }, names[res] ?: (if (h > 0) "${h}P" else res.ifEmpty { "转码" }),
                Resolved(url, mapOf("Referer" to REFERER, "User-Agent" to UA), if (url.contains(".m3u8")) "application/x-mpegURL" else null))
        }.sortedBy { it.first }.distinctBy { it.second }.map { it.second to it.third }
    }

    override suspend fun usage(): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val o = call("/member", "&fetch_subscribe=true&_ch=home&fetch_identity=true")
        val d = o["data"].obj() ?: return@withContext null
        val total = d.long("total_capacity")
        val used = d.long("use_capacity")
        if (total > 0) used to total else null
    }

    class Login : QrLogin {
        override val appHint = "打开夸克 App 扫码登录"
        private var token = ""
        private val noRedirect = Repo.http.newBuilder().followRedirects(false).build()

        override suspend fun start(): String = withContext(Dispatchers.IO) {
            val body = Repo.http.newCall(Request.Builder().url("https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin?client_id=532&v=1.2&request_id=${UUID.randomUUID()}").build())
                .execute().use { it.body?.string() ?: "" }
            token = json.parseToJsonElement(body).jsonObject["data"].obj()?.get("members").obj()?.str("token") ?: throw IOException("获取二维码失败")
            "https://su.quark.cn/4_eMHBJ?token=$token&client_id=532&ssb=weblogin&uc_param_str=&uc_biz_str=S%3Acustom%7COPT%3ASAREA%400%7COPT%3AIMMERSIVE%401%7COPT%3ABACK_BTN_STYLE%400"
        }

        override suspend fun poll(): QrState = withContext(Dispatchers.IO) {
            kotlinx.coroutines.delay(1500)
            val body = Repo.http.newCall(Request.Builder().url("https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken?client_id=532&v=1.2&token=$token&request_id=${UUID.randomUUID()}").build())
                .execute().use { it.body?.string() ?: "" }
            val o = json.parseToJsonElement(body).jsonObject
            when (o.long("status")) {
                2000000L -> {
                    val st = o["data"].obj()?.get("members").obj()?.str("service_ticket") ?: return@withContext QrState.Failed("登录失败")
                    var c = ""
                    var url: String? = "https://pan.quark.cn/account/info?st=$st&lw=scan"
                    var hops = 0
                    while (url != null && hops++ < 5) {
                        val r = noRedirect.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Cookie", c).build()).execute()
                        r.headers("Set-Cookie").forEach { sc ->
                            val kv = sc.substringBefore(';')
                            val k = kv.substringBefore('=').trim(); val v = kv.substringAfter('=', "")
                            if (k.isNotEmpty() && v.isNotEmpty()) c = setCookie(c, k, v)
                        }
                        url = if (r.isRedirect) r.header("Location")?.let { loc -> r.request.url.resolve(loc)?.toString() } else null
                        r.close()
                    }
                    if (!c.contains("__pus")) QrState.Failed("登录失败：未获取到 Cookie") else QrState.Done(c)
                }
                50004002L -> QrState.Expired
                else -> QrState.Waiting
            }
        }
    }

    companion object {
        const val API = "https://drive.quark.cn/1/clouddrive"
        const val REFERER = "https://pan.quark.cn"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"
    }
}
