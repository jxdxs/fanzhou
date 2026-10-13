package com.kevin.fanzhou.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

/** Client for Jellyfin and Emby (their REST APIs are nearly identical). */
class JellyfinClient(val source: Source) {
    val isEmby = source.isEmby
    /** Server root as entered (http://host:8096). */
    private val root = source.url.trimEnd('/').removeSuffix("/emby").let { r ->
        // 飞牛影视 users often paste the web UI address (…/v or …/v/…); the API lives at <root>/emby.
        if (source.isFnos) r.replace(Regex("/(v|web)(/.*)?$"), "") else r }
    /** API base: Emby routes live under /emby. */
    private val base = if (isEmby) "$root/emby" else root
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val uid get() = source.userId ?: ""
    private val token get() = source.token ?: ""

    private fun authHeader(withToken: Boolean = true): String {
        val dev = Repo.store.deviceId
        var h = "MediaBrowser Client=\"$CLIENT\", Device=\"Android\", DeviceId=\"$dev\", Version=\"$VERSION\""
        if (withToken && token.isNotEmpty()) h += ", Token=\"$token\""
        return h
    }

    /** Adds auth headers. Jellyfin reads Authorization, Emby reads X-Emby-Authorization + X-Emby-Token. */
    private fun Request.Builder.auth(withToken: Boolean = true): Request.Builder {
        val h = authHeader(withToken)
        header("X-Emby-Authorization", h)
        if (!isEmby) header("Authorization", h)
        if (withToken && token.isNotEmpty()) header("X-Emby-Token", token)
        return this
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(base + path).auth().build()
        Repo.http.newCall(req).execute().use { r ->
            if (r.code == 401) throw IOException("认证失败 (401)，请检查用户名密码或重新登录")
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string() ?: ""
        }
    }

    private suspend fun post(path: String, body: String, auth: Boolean = true): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(base + path).auth(auth)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        Repo.http.newCall(req).execute().use { r ->
            if (r.code == 401) throw IOException("认证失败 (401)，请检查用户名密码或重新登录")
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string() ?: ""
        }
    }

    suspend fun login(): Source {
        val body = buildJsonObject {
            put("Username", source.username); put("Pw", source.password)
            if (isEmby) put("Password", source.password)
        }.toString()
        val res = json.decodeFromString<JfAuthResult>(post("/Users/AuthenticateByName", body, auth = false))
        return source.copy(token = res.accessToken, userId = res.user.id)
    }

    private val fields = "PrimaryImageAspectRatio,Overview,Genres,CommunityRating,OfficialRating,ProductionYear,ChildCount,ParentBackdropItemId,ParentBackdropImageTags,SeriesPrimaryImageTag"

    suspend fun views(): List<JfItem> = json.decodeFromString<JfItems>(get("/Users/$uid/Views")).items

    suspend fun resume(): List<JfItem> = json.decodeFromString<JfItems>(
        get("/Users/$uid/Items/Resume?Limit=20&MediaTypes=Video&Recursive=true&Fields=$fields&EnableTotalRecordCount=false")
    ).items

    suspend fun nextUp(): List<JfItem> = json.decodeFromString<JfItems>(
        get("/Shows/NextUp?UserId=$uid&Limit=20&Fields=$fields&EnableTotalRecordCount=false")
    ).items

    /** Items the user marked with the heart (IsFavorite). */
    suspend fun favorites(): List<JfItem> = json.decodeFromString<JfItems>(
        get("/Users/$uid/Items?Filters=IsFavorite&Recursive=true&IncludeItemTypes=Movie,Series,Episode&Limit=60&SortBy=DateCreated&SortOrder=Descending&Fields=$fields&ImageTypeLimit=1&EnableTotalRecordCount=false")
    ).items

    suspend fun latest(parentId: String): List<JfItem> = json.decodeFromString<List<JfItem>>(
        get("/Users/$uid/Items/Latest?ParentId=$parentId&Limit=24&Fields=$fields&GroupItems=true")
    )

    suspend fun items(
        parent: JfItem?, parentId: String, start: Int, limit: Int,
        sortBy: String = "SortName", sortOrder: String = "Ascending",
    ): JfItems {
        val ct = parent?.collectionType
        val recursive = ct == "movies" || ct == "tvshows"
        val types = when (ct) {
            "movies" -> "&IncludeItemTypes=Movie"
            "tvshows" -> "&IncludeItemTypes=Series"
            else -> ""
        }
        return json.decodeFromString(
            get("/Users/$uid/Items?ParentId=$parentId&StartIndex=$start&Limit=$limit&SortBy=$sortBy&SortOrder=$sortOrder&Recursive=$recursive$types&Fields=$fields&ImageTypeLimit=1")
        )
    }

    suspend fun search(term: String): List<JfItem> {
        val q = URLEncoder.encode(term, "UTF-8")
        return json.decodeFromString<JfItems>(
            get("/Users/$uid/Items?SearchTerm=$q&Recursive=true&IncludeItemTypes=Movie,Series,Episode&Limit=60&Fields=$fields")
        ).items
    }

    suspend fun item(id: String): JfItem = json.decodeFromString(
        get("/Users/$uid/Items/$id?Fields=$fields,MediaSources,People")
    )

    suspend fun seasons(seriesId: String): List<JfItem> = json.decodeFromString<JfItems>(
        get("/Shows/$seriesId/Seasons?UserId=$uid&Fields=$fields")
    ).items

    suspend fun episodes(seriesId: String, seasonId: String): List<JfItem> = json.decodeFromString<JfItems>(
        get("/Shows/$seriesId/Episodes?UserId=$uid&SeasonId=$seasonId&Fields=$fields,MediaSources")
    ).items

    suspend fun similar(id: String): List<JfItem> = try {
        json.decodeFromString<JfItems>(get("/Items/$id/Similar?UserId=$uid&Limit=12&Fields=$fields")).items
    } catch (e: Exception) { emptyList() }

    suspend fun setPlayed(id: String, played: Boolean) = withContext(Dispatchers.IO) {
        val b = Request.Builder().url("$base/Users/$uid/PlayedItems/$id").auth()
        val req = if (played) b.post(ByteArray(0).toRequestBody()).build() else b.delete().build()
        Repo.http.newCall(req).execute().close()
    }

    suspend fun setFavorite(id: String, fav: Boolean) = withContext(Dispatchers.IO) {
        val b = Request.Builder().url("$base/Users/$uid/FavoriteItems/$id").auth()
        val req = if (fav) b.post(ByteArray(0).toRequestBody()).build() else b.delete().build()
        Repo.http.newCall(req).execute().close()
    }

    /** 飞牛影视 refuses image requests without a token; Jellyfin/Emby serve them openly. */
    private fun img(u: String) = if (source.isFnos && token.isNotEmpty()) "$u&api_key=$token" else u

    /**
     * 飞牛影视 ignores the api_key query parameter on media/image endpoints
     * (its JSON API works because those calls carry headers). Send the token
     * as request headers for media requests: used directly for MPV, and via
     * the Repo.http interceptor for Coil image loads and Exo streams.
     */
    fun authHeaders(): Map<String, String> {
        if (!source.isFnos || token.isEmpty()) return emptyMap()
        val h = authHeader()
        return mapOf(
            "X-Emby-Authorization" to h,
            "Authorization" to h,
            "X-Emby-Token" to token,
        )
    }

    fun primaryImage(item: JfItem, maxHeight: Int = 480): String? = primaryImage0(item, maxHeight)?.let(::img)
    fun seriesPoster(item: JfItem, maxHeight: Int = 480): String? = seriesPoster0(item, maxHeight)?.let(::img)
    fun backdrop(item: JfItem, maxWidth: Int = 1280): String? = backdrop0(item, maxWidth)?.let(::img)

    private fun primaryImage0(item: JfItem, maxHeight: Int = 480): String? {
        item.imageTags["Primary"]?.let { return "$base/Items/${item.id}/Images/Primary?maxHeight=$maxHeight&quality=90&tag=$it" }
        if (item.seriesId != null && item.seriesPrimaryTag != null)
            return "$base/Items/${item.seriesId}/Images/Primary?maxHeight=$maxHeight&quality=90&tag=${item.seriesPrimaryTag}"
        return null
    }

    private fun seriesPoster0(item: JfItem, maxHeight: Int = 480): String? =
        if (item.type == "Episode" && item.seriesId != null && item.seriesPrimaryTag != null)
            "$base/Items/${item.seriesId}/Images/Primary?maxHeight=$maxHeight&quality=90&tag=${item.seriesPrimaryTag}"
        else primaryImage0(item, maxHeight)

    private fun backdrop0(item: JfItem, maxWidth: Int = 1280): String? {
        item.backdropTags.firstOrNull()?.let { return "$base/Items/${item.id}/Images/Backdrop?maxWidth=$maxWidth&quality=85&tag=$it" }
        if (item.parentBackdropItemId != null && item.parentBackdropTags.isNotEmpty())
            return "$base/Items/${item.parentBackdropItemId}/Images/Backdrop?maxWidth=$maxWidth&quality=85&tag=${item.parentBackdropTags.first()}"
        return null
    }

    fun thumb(item: JfItem, maxWidth: Int = 640): String? =
        if (item.type == "Episode") item.imageTags["Primary"]?.let { img("$base/Items/${item.id}/Images/Primary?maxWidth=$maxWidth&quality=85&tag=$it") } ?: backdrop(item, maxWidth)
        else backdrop(item, maxWidth) ?: primaryImage(item)

    fun streamUrl(itemId: String, mediaSourceId: String?): String {
        // 飞牛影视只认最简形式（和第三方客户端 cineisle 一致）：多余的 MediaSourceId
        // （列表里拿不到真实媒体源 ID，只能拿视频 ID 顶上）会被它严格校验拒绝。
        // 鉴权走请求头（authHeaders / Repo.http 拦截器），api_key 留作兼容。
        if (source.isFnos) return "$base/Videos/$itemId/stream?static=true&api_key=$token"
        val ms = mediaSourceId ?: itemId
        return "$base/Videos/$itemId/stream?static=true&MediaSourceId=$ms&api_key=$token&DeviceId=${Repo.store.deviceId}"
    }

    fun transcodeUrl(itemId: String, mediaSourceId: String?, maxBitrate: Int = 8_000_000, maxWidth: Int = 0): String {
        val ms = mediaSourceId ?: itemId
        return "$base/Videos/$itemId/master.m3u8?MediaSourceId=$ms&api_key=$token&DeviceId=${Repo.store.deviceId}" +
            "&VideoCodec=h264,hevc&AudioCodec=aac,mp3,ac3&MaxStreamingBitrate=$maxBitrate&TranscodingMaxAudioChannels=2&SegmentContainer=ts" +
            (if (maxWidth > 0) "&MaxWidth=$maxWidth" else "")
    }

    fun externalSubtitles(item: JfItem): List<Pair<String, JfMediaStream>> {
        val src = item.mediaSources.firstOrNull() ?: return emptyList()
        return src.streams.filter { it.type == "Subtitle" && it.isExternal }.map { st ->
            val d = st.deliveryUrl
            val url = when {
                d == null -> {
                    // Emby (and older Jellyfin) omit DeliveryUrl outside PlaybackInfo: build the stream route.
                    val fmt = when (val c = st.codec?.lowercase()) { null, "subrip" -> "srt"; "webvtt" -> "vtt"; else -> c }
                    "$base/Videos/${item.id}/${src.id.ifEmpty { item.id }}/Subtitles/${st.index}/Stream.$fmt"
                }
                d.startsWith("http") -> d
                d.startsWith("/emby/") -> root + d
                else -> base + d
            }
            val withKey = if ("api_key=" in url) url else url + (if ('?' in url) "&" else "?") + "api_key=$token"
            withKey to st
        }
    }

    fun playItem(item: JfItem, start: Boolean = true): PlayItem {
        val msId = item.mediaSources.firstOrNull()?.id
        val title = if (item.type == "Episode") (item.seriesName ?: item.name) else item.name
        val sub = if (item.type == "Episode") "${item.episodeLabel ?: ""} ${item.name}".trim() else item.year?.toString()
        return PlayItem(
            url = streamUrl(item.id, msId),
            title = title,
            subtitle = sub,
            headers = authHeaders(),
            startMs = if (start) (item.userData?.positionTicks ?: 0) / 10_000 else 0,
            sourceId = source.id,
            jfItemId = item.id,
            jfMediaSourceId = msId,
            resumeKey = "jf:${source.id}:${item.id}",
            container = item.mediaSources.firstOrNull()?.container?.substringBefore(',')?.lowercase(),
            subtitles = externalSubtitles(item).map { (url, st) ->
                val codec = st.codec ?: url.substringAfterLast('.').substringBefore('?')
                SubTrack(url, st.displayTitle ?: st.language ?: "外挂字幕", SubTrack.mimeFor(codec), st.language)
            },
        )
    }

    suspend fun reportStart(itemId: String, msId: String?, posMs: Long) = report("/Sessions/Playing", itemId, msId, posMs, false)
    suspend fun reportProgress(itemId: String, msId: String?, posMs: Long, paused: Boolean) = report("/Sessions/Playing/Progress", itemId, msId, posMs, paused)
    suspend fun reportStop(itemId: String, msId: String?, posMs: Long) = report("/Sessions/Playing/Stopped", itemId, msId, posMs, false)

    private suspend fun report(path: String, itemId: String, msId: String?, posMs: Long, paused: Boolean) {
        try {
            val body = buildJsonObject {
                put("ItemId", itemId)
                put("MediaSourceId", msId ?: itemId)
                put("PositionTicks", posMs * 10_000)
                put("IsPaused", paused)
                put("PlayMethod", "DirectPlay")
                put("CanSeek", true)
            }.toString()
            post(path, body)
        } catch (_: Exception) { }
    }

    companion object {
        const val CLIENT = "Fanzhou"
        val VERSION = com.kevin.fanzhou.BuildConfig.VERSION_NAME
        fun normalizeUrl(input: String): String {
            var u = input.trim().trimEnd('/')
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            u.toHttpUrl()
            return u
        }
    }
}
