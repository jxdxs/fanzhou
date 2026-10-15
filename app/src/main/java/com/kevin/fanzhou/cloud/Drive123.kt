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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class Drive123(source: Source) : CloudDrive(source) {
    override val rootId = "0"
    private val token get() = (source.token ?: "").trim().let { if (it.startsWith("Bearer ", ignoreCase = true)) it else "Bearer $it" }

    private fun req(url: String) = Request.Builder()
        .url(url)
        .header("Authorization", token)
        .header("Platform", "web")
        .header("App-Version", "3")
        .header("User-Agent", UA)
        .header("Referer", "https://www.123pan.com/")

    override suspend fun list(dirId: String): List<CloudFile> = withContext(Dispatchers.IO) {
        val out = ArrayList<CloudFile>()
        var next = "0"
        while (true) {
            val url = "https://www.123pan.com/b/api/file/list?driveId=0&limit=100&next=$next&orderBy=file_name&orderDirection=asc&parentFileId=$dirId&trashed=false"
            val text = Repo.http.newCall(req(url).build()).execute().use { it.body?.string() ?: "" }
            val o = json.parseToJsonElement(text).jsonObject
            val code = o.long("code")
            if (code != 0L) {
                val msg = o.str("message") ?: "123云盘请求失败 ($code)"
                throw IOException(msg)
            }
            val data = o["data"].obj() ?: break
            val list = data["fileList"] as? JsonArray ?: JsonArray(emptyList())
            list.forEach { elem ->
                val f = elem as? JsonObject ?: return@forEach
                val isDir = (f.long("type") == 1L) || (f.str("type") == "1")
                val fid = f.str("fileId") ?: f.long("fileId").toString()
                val name = f.str("filename") ?: f.str("fileName") ?: ""
                val size = f.long("size")
                val mod = parseIsoTime(f.str("updateAt") ?: f.str("updatedAt"))
                val childCount = if (isDir) (f.intOrNull("fileCount") ?: f.intOrNull("file_count") ?: f.intOrNull("count")) else null
                out.add(
                    CloudFile(
                        id = fid,
                        name = name,
                        isDir = isDir,
                        size = size,
                        extra = f.str("etag"),
                        modified = mod,
                        childCount = childCount,
                    )
                )
            }
            val nextVal = data.str("next") ?: data.long("next").toString()
            if (list.isEmpty() || nextVal == "-1" || nextVal == "0" || nextVal == next) break
            next = nextVal
        }
        out
    }

    override suspend fun resolve(file: CloudFile): Resolved = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            val numId = file.id.toLongOrNull()
            if (numId != null) put("fileId", numId) else put("fileId", file.id)
            put("driveId", 0)
        }.toString()
        val text = Repo.http.newCall(
            req("https://www.123pan.com/b/api/file/download_info")
                .post(body.toRequestBody(JSON))
                .build()
        ).execute().use { it.body?.string() ?: "" }
        val o = json.parseToJsonElement(text).jsonObject
        val code = o.long("code")
        if (code != 0L) {
            val msg = o.str("message") ?: "123云盘获取播放地址失败 ($code)"
            throw IOException(msg)
        }
        val data = o["data"].obj() ?: throw IOException("123云盘返回空数据")
        val dl = data.str("downloadUrl") ?: data.str("DownloadUrl") ?: throw IOException("未找到视频播放直链")
        Resolved(dl, mapOf("User-Agent" to UA, "Referer" to "https://www.123pan.com/"))
    }

    override suspend fun usage(): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val text = runCatching {
            Repo.http.newCall(req("https://www.123pan.com/b/api/user/info").build()).execute().use { it.body?.string() ?: "" }
        }.getOrNull() ?: return@withContext null
        val o = json.parseToJsonElement(text).jsonObject
        val space = o["data"].obj()?.get("space").obj() ?: return@withContext null
        val used = space.long("used")
        val total = space.long("total")
        if (total > 0) used to total else null
    }

    override suspend fun rename(file: CloudFile, newName: String) = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            val numId = file.id.toLongOrNull()
            if (numId != null) put("fileId", numId) else put("fileId", file.id)
            put("filename", newName)
        }.toString()
        val text = Repo.http.newCall(
            req("https://www.123pan.com/b/api/file/rename")
                .post(body.toRequestBody(JSON))
                .build()
        ).execute().use { it.body?.string() ?: "" }
        val o = json.parseToJsonElement(text).jsonObject
        if (o.long("code") != 0L) throw IOException(o.str("message") ?: "123云盘重命名失败")
    }

    override suspend fun delete(file: CloudFile) = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            val numId = file.id.toLongOrNull()
            if (numId != null) put("fileId", numId) else put("fileId", file.id)
        }.toString()
        val text = Repo.http.newCall(
            req("https://www.123pan.com/b/api/file/trash")
                .post(body.toRequestBody(JSON))
                .build()
        ).execute().use { it.body?.string() ?: "" }
        val o = json.parseToJsonElement(text).jsonObject
        if (o.long("code") != 0L) throw IOException(o.str("message") ?: "123云盘删除失败")
    }

    companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val JSON = "application/json".toMediaType()

        suspend fun login(passport: String, pass: String): String = withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("passport", passport.trim())
                put("password", pass.trim())
            }.toString()

            val urls = listOf("https://user.123pan.cn/api/user/sign_in", "https://www.123pan.com/b/api/user/sign_in")
            var lastErr: String? = null
            for (url in urls) {
                val req = Request.Builder()
                    .url(url)
                    .header("Platform", "web")
                    .header("App-Version", "3")
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.123pan.com/")
                    .post(payload.toRequestBody(JSON))
                    .build()
                val text = runCatching {
                    Repo.http.newCall(req).execute().use { it.body?.string() ?: "" }
                }.getOrNull() ?: continue
                val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                val code = o.long("code")
                if (code == 0L) {
                    val d = o["data"].obj()
                    val token = d?.str("token") ?: d?.str("Token")
                    if (!token.isNullOrEmpty()) return@withContext token
                }
                lastErr = o.str("message") ?: "登录失败 ($code)"
            }
            throw IOException(lastErr ?: "123云盘账号或密码登录失败，请检查账号密码或改用网页登录")
        }
    }
}
