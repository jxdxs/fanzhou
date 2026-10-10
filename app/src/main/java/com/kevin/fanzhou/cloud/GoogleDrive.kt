package com.kevin.fanzhou.cloud

import com.kevin.fanzhou.data.Repo
import com.kevin.fanzhou.data.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Google Drive via the official Drive v3 REST API.
 * Source.token = OAuth refresh_token; extra: cid/csec (OAuth client, defaults to rclone's public
 * desktop client so a token copied from an rclone "drive" remote works), at/exp (cached access token).
 */
class GoogleDrive(source: Source) : CloudDrive(source) {
    override val rootId = "root"
    private val clientId get() = source.extra["cid"]?.ifBlank { null } ?: DEFAULT_CLIENT_ID
    private val clientSecret get() = source.extra["csec"]?.ifBlank { null } ?: DEFAULT_CLIENT_SECRET

    @Synchronized
    private fun accessToken(force: Boolean = false): String {
        val at = source.extra["at"]
        val exp = source.extra["exp"]?.toLongOrNull() ?: 0
        if (!force && !at.isNullOrEmpty() && System.currentTimeMillis() < exp - 120_000) return at
        val rt = source.token ?: throw IOException("谷歌云盘未登录")
        val o = tokenRequest(clientId, clientSecret, FormBody.Builder()
            .add("grant_type", "refresh_token").add("refresh_token", rt))
        val newAt = o.str("access_token") ?: throw IOException("刷新谷歌令牌失败")
        val newExp = System.currentTimeMillis() + o.long("expires_in").coerceAtLeast(600) * 1000
        save { it.copy(extra = it.extra + mapOf("at" to newAt, "exp" to newExp.toString())) }
        return newAt
    }

    private fun call(url: String, method: String = "GET", body: String? = null, retried: Boolean = false): JsonObject {
        val b = Request.Builder().url(url).header("Authorization", "Bearer ${accessToken(retried)}")
        if (body != null) b.method(method, body.toRequestBody("application/json".toMediaType()))
        else if (method != "GET") b.method(method, null)
        Repo.http.newCall(b.build()).execute().use { r ->
            if (r.code == 401 && !retried) return call(url, method, body, true)
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) throw IOException(errorText(r.code, text))
            return if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text).jsonObject
        }
    }

    override suspend fun list(dirId: String): List<CloudFile> = withContext(Dispatchers.IO) {
        val out = ArrayList<CloudFile>()
        var page: String? = null
        do {
            val u = "$API/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", "'${dirId.replace("'", "\\'")}' in parents and trashed = false")
                .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size,modifiedTime,thumbnailLink,videoMediaMetadata(durationMillis))")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("orderBy", "folder,name_natural")
                .addQueryParameter("supportsAllDrives", "true")
                .addQueryParameter("includeItemsFromAllDrives", "true")
                .apply { page?.let { addQueryParameter("pageToken", it) } }
                .build().toString()
            val o = call(u)
            (o["files"] as? JsonArray)?.forEach { e ->
                val f = e as? JsonObject ?: return@forEach
                val mime = f.str("mimeType") ?: ""
                val isDir = mime == FOLDER
                if (!isDir && mime.startsWith("application/vnd.google-apps.")) return@forEach // Docs/Sheets can't be played
                out.add(CloudFile(
                    f.str("id") ?: return@forEach, f.str("name") ?: "", isDir, f.long("size"),
                    modified = parseIsoTime(f.str("modifiedTime")),
                    thumb = f.str("thumbnailLink"),
                    duration = f["videoMediaMetadata"].obj()?.long("durationMillis") ?: 0,
                ))
            }
            page = o.str("nextPageToken")
        } while (page != null)
        out
    }

    override suspend fun resolve(file: CloudFile): Resolved = withContext(Dispatchers.IO) {
        Resolved("$API/files/${file.id}?alt=media&supportsAllDrives=true", mapOf("Authorization" to "Bearer ${accessToken()}"))
    }

    override val thumbHeaders: Map<String, String>
        get() = source.extra["at"]?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

    override suspend fun usage(): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val q = call("$API/about?fields=storageQuota")["storageQuota"].obj() ?: return@withContext null
        q.long("usage") to q.long("limit")
    }

    override suspend fun rename(file: CloudFile, newName: String) = withContext(Dispatchers.IO) {
        call("$API/files/${file.id}?supportsAllDrives=true", "PATCH", buildJsonObject { put("name", newName) }.toString()); Unit
    }

    /** Moves to Google Drive's trash (recoverable for 30 days). */
    override suspend fun delete(file: CloudFile) = withContext(Dispatchers.IO) {
        call("$API/files/${file.id}?supportsAllDrives=true", "PATCH", buildJsonObject { put("trashed", true) }.toString()); Unit
    }

    override suspend fun prepare(): Source = withContext(Dispatchers.IO) { accessToken(); source }

    companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val FOLDER = "application/vnd.google-apps.folder"
        /** rclone's public desktop OAuth client (from rclone's source); users may enter their own. */
        const val DEFAULT_CLIENT_ID = ""
        const val DEFAULT_CLIENT_SECRET = ""
        const val REDIRECT_PORT = 53682
        const val REDIRECT = "http://127.0.0.1:$REDIRECT_PORT/"
        const val SCOPE = "https://www.googleapis.com/auth/drive"

        fun authUrl(clientId: String, state: String): String = "https://accounts.google.com/o/oauth2/auth".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", REDIRECT)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", SCOPE)
            .addQueryParameter("access_type", "offline")
            .addQueryParameter("prompt", "consent")
            .addQueryParameter("state", state)
            .build().toString()

        fun tokenRequest(cid: String, csec: String, form: FormBody.Builder): JsonObject {
            val req = Request.Builder().url("https://oauth2.googleapis.com/token")
                .post(form.add("client_id", cid).add("client_secret", csec).build()).build()
            Repo.http.newCall(req).execute().use { r ->
                val text = r.body?.string() ?: ""
                if (!r.isSuccessful) throw IOException(errorText(r.code, text))
                return json.parseToJsonElement(text).jsonObject
            }
        }

        /** Exchanges an authorization code from the loopback redirect for a refresh token. */
        fun exchangeCode(cid: String, csec: String, code: String): String =
            tokenRequest(cid, csec, FormBody.Builder().add("grant_type", "authorization_code").add("code", code).add("redirect_uri", REDIRECT))
                .str("refresh_token") ?: throw IOException("谷歌没有返回 refresh_token，请重试")

        /** Accepts a bare refresh token or an rclone token JSON ({"access_token":…,"refresh_token":…}). */
        fun parseRefreshToken(text: String): String {
            val t = text.trim()
            if (t.startsWith("{")) return runCatching { json.parseToJsonElement(t).jsonObject.str("refresh_token") }.getOrNull()
                ?: throw IOException("没在 JSON 里找到 refresh_token")
            Regex("\"refresh_token\"\\s*:\\s*\"([^\"]+)\"").find(t)?.let { return it.groupValues[1] }
            return t
        }

        fun errorText(code: Int, body: String): String {
            val msg = runCatching {
                val o = json.parseToJsonElement(body).jsonObject
                o["error"].obj()?.str("message") ?: o.str("error_description") ?: o.str("error")
            }.getOrNull()
            return when {
                code == 400 && msg?.contains("invalid_grant") == true || msg == "invalid_grant" -> "谷歌登录已失效，请重新登录"
                code == 403 && msg?.contains("rate", true) == true -> "谷歌接口限流（共享 Client ID 配额用尽），稍后再试或填自己的 Client ID"
                else -> "谷歌云盘请求失败 ($code)" + (msg?.let { "：$it" } ?: "")
            }
        }
    }
}
