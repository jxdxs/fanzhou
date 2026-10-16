package com.kevin.fanzhou.cloud

import android.util.Base64
import com.kevin.fanzhou.data.Repo
import com.kevin.fanzhou.data.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.math.BigInteger
import java.util.UUID

class AliyunDrive(source: Source) : CloudDrive(source) {
    override val rootId = "ROOT"
    private val mutex = Mutex()
    @Volatile private var sessionReady = false
    @Volatile private var preparedForRoot = false

    private val userId get() = source.extra["user_id"] ?: ""
    private val deviceId get() = Secp256k1.hex(Secp256k1.sha256(userId))
    private val privKey get() = BigInteger(deviceId, 16).mod(Secp256k1.N)
    private val signature: String
        get() = Secp256k1.signHex(privKey, Secp256k1.sha256("$APP_ID:$deviceId:$userId:0"))

    private class ApiError(val code: String, msg: String) : IOException(msg)

    private suspend fun refreshToken() = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("refresh_token", source.token ?: ""); put("grant_type", "refresh_token") }.toString()
        val text = Repo.http.newCall(
            Request.Builder().url("https://auth.alipan.com/v2/account/token").post(body.toRequestBody(JSON)).build()
        ).execute().use { it.body?.string() ?: "" }
        val o = json.parseToJsonElement(text).jsonObject
        val access = o.str("access_token") ?: throw IOException("阿里云盘登录已失效，请重新扫码（${o.str("message") ?: ""}）")
        val refresh = o.str("refresh_token") ?: source.token ?: ""
        val uid = o.str("user_id") ?: userId
        save { it.copy(token = refresh, extra = it.extra + mapOf("access" to access, "user_id" to uid) + (o.str("default_drive_id")?.let { d -> mapOf("default_drive" to d) } ?: emptyMap())) }
    }

    private fun rawPost(url: String, body: String): Pair<Int, JsonObject> {
        val r = Repo.http.newCall(
            Request.Builder().url(url)
                .header("Authorization", "Bearer ${source.extra["access"] ?: ""}")
                .header("Origin", "https://www.alipan.com")
                .header("Referer", "https://www.alipan.com/")
                .header("x-device-id", deviceId)
                .header("x-signature", signature)
                .header("x-request-id", UUID.randomUUID().toString())
                .header("X-Canary", "client=Android,app=adrive,version=v4.1.0")
                .post(body.toRequestBody(JSON)).build()
        ).execute()
        val text = r.use { it.body?.string() ?: "{}" }
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        return r.code to o
    }

    private suspend fun createSession() {
        val body = buildJsonObject {
            put("deviceName", "samsung"); put("modelName", "SM-G9810"); put("nonce", 0)
            put("pubKey", Secp256k1.publicKeyHex(privKey)); put("refreshToken", source.token ?: "")
        }.toString()
        rawPost("https://api.alipan.com/users/v1/users/device/create_session", body)
        sessionReady = true
    }

    private suspend fun post(url: String, body: String, retry: Int = 2): JsonObject = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (source.extra["access"].isNullOrEmpty() || userId.isEmpty()) refreshToken()
            if (!sessionReady) runCatching { createSession() }
        }
        val (code, o) = rawPost(url, body)
        val err = o.str("code")
        if (err != null && code >= 400) {
            if (retry > 0) when (err) {
                "AccessTokenInvalid", "AccessTokenExpired" -> { mutex.withLock { refreshToken() }; return@withContext post(url, body, retry - 1) }
                "DeviceSessionSignatureInvalid", "UserDeviceOffline", "UserDeviceIllegality" -> {
                    mutex.withLock { createSession() }; return@withContext post(url, body, retry - 1)
                }
            }
            throw ApiError(err, o.str("message") ?: err)
        }
        if (code >= 400) throw IOException("阿里云盘请求失败 ($code)")
        o
    }

    override suspend fun prepare(): Source = withContext(Dispatchers.IO) {
        refreshToken()
        val o = post("https://user.aliyundrive.com/v2/user/get", "{}")
        val extra = HashMap<String, String>()
        o.str("resource_drive_id")?.takeIf { it.isNotEmpty() }?.let { extra["resource_drive"] = it }
        (o.str("backup_drive_id") ?: o.str("default_drive_id"))?.takeIf { it.isNotEmpty() }?.let { extra["backup_drive"] = it }
        save { it.copy(extra = it.extra + extra, name = it.name.ifEmpty { o.str("nick_name") ?: "阿里云盘" }) }
        source
    }

    override suspend fun list(dirId: String): List<CloudFile> {
        if (dirId == rootId) {
            val res = source.extra["resource_drive"]
            val bak = source.extra["backup_drive"] ?: source.extra["default_drive"]
            val roots = listOfNotNull(
                res?.let { CloudFile("$it/root", "资源库", true, extra = it) },
                bak?.let { CloudFile("$it/root", "备份盘", true, extra = it) },
            )
            if (roots.isNotEmpty()) return roots
            if (preparedForRoot) throw IOException("未获取到阿里云盘的盘符信息")
            preparedForRoot = true
            prepare()
            return list(rootId)
        }
        val driveId = dirId.substringBefore('/')
        val parent = dirId.substringAfter('/')
        val out = ArrayList<CloudFile>()
        var marker = ""
        do {
            val body = buildJsonObject {
                put("drive_id", driveId); put("parent_file_id", parent); put("limit", 200); put("marker", marker)
                put("order_by", "name"); put("order_direction", "ASC"); put("fields", "*"); put("url_expire_sec", 14400)
            }.toString()
            val o = post("https://api.alipan.com/adrive/v3/file/list", body)
            (o["items"] as? JsonArray)?.forEach { e ->
                val f = e as? JsonObject ?: return@forEach
                val isDir = f.str("type") == "folder"
                val id = f.str("file_id") ?: return@forEach
                val meta = f["video_media_metadata"].obj() ?: f["video_preview_metadata"].obj()
                val dur = meta?.str("duration")?.toDoubleOrNull() ?: 0.0
                val rawThumb = f.str("thumbnail") ?: meta?.str("thumbnail") ?: f["video_preview_metadata"].obj()?.str("thumbnail") ?: f["video_media_metadata"].obj()?.str("thumbnail")
                val thumbUrl = when {
                    rawThumb.isNullOrEmpty() -> null
                    rawThumb.startsWith("http://") || rawThumb.startsWith("https://") -> rawThumb
                    rawThumb.startsWith("//") -> "https:$rawThumb"
                    else -> null
                }
                val childCount = if (isDir) (f.intOrNull("file_count") ?: f.intOrNull("sub_folder_count") ?: f.intOrNull("count")) else null
                out.add(
                    CloudFile(
                        if (isDir) "$driveId/$id" else id, f.str("name") ?: "", isDir, f.long("size"), driveId,
                        modified = parseIsoTime(f.str("updated_at") ?: f.str("created_at")),
                        thumb = thumbUrl,
                        duration = (dur * 1000).toLong(),
                        childCount = childCount,
                    )
                )
            }
            marker = o.str("next_marker") ?: ""
        } while (marker.isNotEmpty())
        return out
    }

    override suspend fun usage(): Pair<Long, Long>? {
        val d = runCatching { post("https://api.alipan.com/adrive/v1/user/driveCapacityDetails", "{}") }.getOrNull()
        if (d != null) {
            val total = d.long("drive_total_size"); val used = d.long("drive_used_size")
            if (total > 0) return used to total
        }
        val p = runCatching { post("https://api.alipan.com/v2/databox/get_personal_info", "{}") }.getOrNull() ?: return null
        val info = p["personal_space_info"].obj() ?: return null
        val total = info.long("total_size"); val used = info.long("used_size")
        return if (total > 0) used to total else null
    }

    override val thumbHeaders get() = mapOf("Referer" to "https://www.alipan.com/")

    /** (drive id, file id); folder ids are "drive/file". */
    private fun ids(f: CloudFile): Pair<String, String> {
        if (f.id.endsWith("/root")) throw IOException("不能修改盘符")
        return if (f.isDir) f.id.substringBefore('/') to f.id.substringAfter('/') else (f.extra ?: "") to f.id
    }

    override suspend fun rename(file: CloudFile, newName: String) {
        val (d, id) = ids(file)
        post("https://api.alipan.com/v3/file/update", buildJsonObject {
            put("drive_id", d); put("file_id", id); put("name", newName); put("check_name_mode", "refuse")
        }.toString())
    }

    override suspend fun delete(file: CloudFile) {
        val (d, id) = ids(file)
        post("https://api.alipan.com/v2/recyclebin/trash", buildJsonObject { put("drive_id", d); put("file_id", id) }.toString())
    }

    override suspend fun resolve(file: CloudFile): Resolved {
        val body = buildJsonObject { put("drive_id", file.extra ?: ""); put("file_id", file.id); put("expire_sec", 14400) }.toString()
        val o = post("https://api.alipan.com/v2/file/get_download_url", body)
        val url = o.str("url")?.takeIf { it.isNotEmpty() } ?: throw IOException("阿里云盘未返回下载地址")
        return Resolved(url, mapOf("Referer" to "https://www.alipan.com/"))
    }

    private suspend fun fetchVideoPlayInfo(file: CloudFile): JsonObject? {
        val driveId = file.extra ?: ""
        // 优先尝试 quick_video（边转边播 2.0，快速生成完整播放列表切片，支持长时大缓存预加载）
        val bodyQuick = buildJsonObject {
            put("drive_id", driveId); put("file_id", file.id); put("category", "quick_video")
            put("template_id", ""); put("get_subtitle_info", true); put("url_expire_sec", 14400)
        }.toString()
        val oQuick = runCatching { post("https://api.alipan.com/v2/file/get_video_preview_play_info", bodyQuick) }.getOrNull()
        val previewQuick = oQuick?.get("video_preview_play_info")?.obj()
        val quickTasks = (previewQuick?.get("quick_video_list") as? JsonArray)
            ?: (previewQuick?.get("live_transcoding_task_list") as? JsonArray)
        if (quickTasks?.any { it.obj()?.str("status") == "finished" && !it.obj()?.str("url").isNullOrEmpty() } == true) {
            return oQuick
        }
        // 回退至传统 live_transcoding
        val bodyLive = buildJsonObject {
            put("drive_id", driveId); put("file_id", file.id); put("category", "live_transcoding")
            put("template_id", ""); put("get_subtitle_info", true); put("url_expire_sec", 14400)
        }.toString()
        return runCatching { post("https://api.alipan.com/v2/file/get_video_preview_play_info", bodyLive) }.getOrNull()
    }

    private fun extractTasks(o: JsonObject?): List<JsonObject> {
        val preview = o?.get("video_preview_play_info")?.obj() ?: return emptyList()
        val quick = (preview["quick_video_list"] as? JsonArray)?.mapNotNull { it.obj() }
            ?.filter { it.str("status") == "finished" && !it.str("url").isNullOrEmpty() }.orEmpty()
        if (quick.isNotEmpty()) return quick
        return (preview["live_transcoding_task_list"] as? JsonArray)?.mapNotNull { it.obj() }
            ?.filter { it.str("status") == "finished" && !it.str("url").isNullOrEmpty() }.orEmpty()
    }

    private val templateNames = linkedMapOf(
        "264_1440p" to "2K", "QHD" to "2K",
        "264_1080p" to "超清 1080P", "FHD" to "超清 1080P",
        "264_720p" to "高清 720P", "HD" to "高清 720P",
        "264_540p" to "标清 540P", "SD" to "标清 540P",
        "264_480p" to "标清 480P",
        "264_360p" to "流畅 360P", "LD" to "流畅 360P",
    )

    override suspend fun resolveTranscode(file: CloudFile): Resolved? {
        val o = fetchVideoPlayInfo(file) ?: return null
        val tasks = extractTasks(o)
        val order = templateNames.keys.toList()
        val best = tasks.minByOrNull { order.indexOf(it.str("template_id")).let { i -> if (i < 0) 99 else i } } ?: return null
        return Resolved(best.str("url")!!, mapOf("Referer" to "https://www.alipan.com/"), "application/x-mpegURL")
    }

    override suspend fun qualities(file: CloudFile): List<Pair<String, Resolved>> {
        val o = fetchVideoPlayInfo(file) ?: return emptyList()
        val tasks = extractTasks(o)
        val order = templateNames.keys.toList()
        return tasks.sortedBy { order.indexOf(it.str("template_id")).let { i -> if (i < 0) 99 else i } }
            .distinctBy { templateNames[it.str("template_id")] ?: it.str("template_id") ?: "转码" }
            .map { t ->
                val tid = t.str("template_id") ?: ""
                (templateNames[tid] ?: tid.ifEmpty { "转码" }) to
                    Resolved(t.str("url")!!, mapOf("Referer" to "https://www.alipan.com/"), "application/x-mpegURL")
            }
    }

    class Login : QrLogin {
        override val appHint = "打开阿里云盘 App 扫码登录"
        private var t = ""; private var ck = ""
        private val base = "https://passport.alipan.com/newlogin/qrcode"
        private val q = "appName=aliyun_drive&fromSite=52&appEntrance=web&isMobile=false&lang=zh_CN&returnUrl=&bizParams=&_bx-v=2.2.3"

        override suspend fun start(): String = withContext(Dispatchers.IO) {
            val text = Repo.http.newCall(Request.Builder().url("$base/generate.do?$q").build()).execute().use { it.body?.string() ?: "" }
            val d = json.parseToJsonElement(text).jsonObject["content"].obj()?.get("data").obj() ?: throw IOException("获取二维码失败")
            t = d.str("t") ?: ""; ck = d.str("ck") ?: ""
            d.str("codeContent") ?: throw IOException("获取二维码失败")
        }

        override suspend fun poll(): QrState = withContext(Dispatchers.IO) {
            kotlinx.coroutines.delay(1500)
            val form = FormBody.Builder().add("t", t).add("ck", ck).add("appName", "aliyun_drive").add("appEntrance", "web")
                .add("isMobile", "false").add("lang", "zh_CN").add("returnUrl", "").add("fromSite", "52").add("bizParams", "")
                .add("navlanguage", "zh-CN").add("navPlatform", "MacIntel").build()
            val text = Repo.http.newCall(Request.Builder().url("$base/query.do?appName=aliyun_drive&fromSite=52&_bx-v=2.2.3").post(form).build())
                .execute().use { it.body?.string() ?: "" }
            val d = json.parseToJsonElement(text).jsonObject["content"].obj()?.get("data").obj() ?: return@withContext QrState.Waiting
            when (d.str("qrCodeStatus")) {
                "SCANED" -> QrState.Scanned
                "EXPIRED" -> QrState.Expired
                "CANCELED" -> QrState.Failed("已取消登录")
                "CONFIRMED" -> {
                    val biz = d.str("bizExt") ?: return@withContext QrState.Failed("登录失败")
                    val decoded = String(Base64.decode(biz, Base64.DEFAULT), charset("GBK"))
                    val r = json.parseToJsonElement(decoded).jsonObject["pds_login_result"].obj()
                    val refresh = r?.str("refreshToken") ?: return@withContext QrState.Failed("登录失败：未获取到令牌")
                    QrState.Done(refresh, mapOf("user_id" to (r.str("userId") ?: ""), "access" to (r.str("accessToken") ?: "")))
                }
                else -> QrState.Waiting
            }
        }
    }

    companion object {
        const val APP_ID = "5dde4e1bdf9e4966b387ba58f4b3fdc3"
        private val JSON = "application/json".toMediaType()
    }
}
