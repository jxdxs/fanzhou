package com.kevin.fanzhou.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Source(
    val id: String,
    val type: String, // see TYPE_* constants
    val name: String,
    val url: String,
    val username: String = "",
    val password: String = "",
    val token: String? = null,
    val userId: String? = null,
    val extra: Map<String, String> = emptyMap(),
) {
    /** Jellyfin or Emby media server (both use [JellyfinClient]). */
    val isJellyfin get() = type == TYPE_JELLYFIN || type == TYPE_EMBY || type == TYPE_FNOS
    /** Emby API dialect (/emby base, X-Emby headers). 飞牛影视 exposes an Emby-compatible API. */
    val isEmby get() = type == TYPE_EMBY || type == TYPE_FNOS
    val isFnos get() = type == TYPE_FNOS
    val isSmb get() = type == TYPE_SMB
    val isCloud get() = type in CLOUD_TYPES
    val typeLabel get() = when (type) {
        TYPE_JELLYFIN -> "Jellyfin"
        TYPE_EMBY -> "Emby"
        TYPE_FNOS -> "飞牛影视"
        TYPE_WEBDAV -> "WebDAV"
        TYPE_SMB -> "SMB"
        TYPE_115 -> "115网盘"
        TYPE_QUARK -> "夸克网盘"
        TYPE_ALIYUN -> "阿里云盘"
        TYPE_123 -> "123云盘"
        TYPE_GDRIVE -> "谷歌云盘"
        else -> type
    }
    companion object {
        const val TYPE_JELLYFIN = "jellyfin"
        const val TYPE_EMBY = "emby"
        const val TYPE_FNOS = "fnos"
        const val TYPE_WEBDAV = "webdav"
        const val TYPE_SMB = "smb"
        const val TYPE_115 = "p115"
        const val TYPE_QUARK = "quark"
        const val TYPE_ALIYUN = "aliyun"
        const val TYPE_123 = "p123"
        const val TYPE_GDRIVE = "gdrive"
        val CLOUD_TYPES = setOf(TYPE_115, TYPE_QUARK, TYPE_ALIYUN, TYPE_123, TYPE_GDRIVE)
    }
}

@Serializable
data class JfAuthResult(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("User") val user: JfUser,
)

@Serializable
data class JfUser(@SerialName("Id") val id: String, @SerialName("Name") val name: String = "")

@Serializable
data class JfItems(
    @SerialName("Items") val items: List<JfItem> = emptyList(),
    @SerialName("TotalRecordCount") val total: Int = 0,
)

@Serializable
data class JfUserData(
    @SerialName("PlaybackPositionTicks") val positionTicks: Long = 0,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("UnplayedItemCount") val unplayed: Int? = null,
    @SerialName("IsFavorite") val favorite: Boolean = false,
)

@Serializable
data class JfMediaStream(
    @SerialName("Type") val type: String = "",
    @SerialName("Codec") val codec: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("Index") val index: Int = 0,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("DeliveryUrl") val deliveryUrl: String? = null,
)

@Serializable
data class JfMediaSource(
    @SerialName("Id") val id: String = "",
    @SerialName("Container") val container: String? = null,
    @SerialName("MediaStreams") val streams: List<JfMediaStream> = emptyList(),
)

@Serializable
data class JfPerson(
    @SerialName("Name") val name: String = "",
    @SerialName("Role") val role: String? = null,
    @SerialName("Type") val type: String? = null,
)

@Serializable
data class JfItem(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String = "",
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("ProductionYear") val year: Int? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("CommunityRating") val rating: Double? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropTags: List<String> = emptyList(),
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropTags: List<String> = emptyList(),
    @SerialName("ParentThumbItemId") val parentThumbItemId: String? = null,
    @SerialName("SeriesPrimaryImageTag") val seriesPrimaryTag: String? = null,
    @SerialName("UserData") val userData: JfUserData? = null,
    @SerialName("MediaSources") val mediaSources: List<JfMediaSource> = emptyList(),
    @SerialName("People") val people: List<JfPerson> = emptyList(),
    @SerialName("IsFolder") val isFolder: Boolean = false,
    @SerialName("ChildCount") val childCount: Int? = null,
) {
    val isPlayable get() = type in setOf("Movie", "Episode", "Video", "MusicVideo", "Trailer")
    val progress: Float
        get() {
            val total = runTimeTicks ?: return 0f
            val pos = userData?.positionTicks ?: 0
            return if (total > 0) (pos.toFloat() / total).coerceIn(0f, 1f) else 0f
        }
    val episodeLabel: String?
        get() = if (type == "Episode") buildString {
            parentIndexNumber?.let { append("S").append(it) }
            indexNumber?.let { append("E").append(it) }
        } else null
}

data class DavEntry(
    val name: String,
    val href: String, // absolute URL
    val isDir: Boolean,
    val size: Long,
    val modified: String?,
    val modifiedMs: Long = 0,
) {
    val isVideo get() = !isDir && VIDEO_EXT.contains(name.substringAfterLast('.', "").lowercase())
    companion object {
        val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "m4v", "ts", "m2ts", "webm", "flv", "wmv", "rmvb", "mpg", "mpeg", "3gp", "iso", "vob", "m3u8")
    }
}

data class PlayItem(
    val url: String,
    val title: String,
    val subtitle: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val startMs: Long = 0,
    val sourceId: String? = null,
    val jfItemId: String? = null,
    val jfMediaSourceId: String? = null,
    val resumeKey: String? = null,
    val subtitles: List<SubTrack> = emptyList(),
    val cloudFile: com.kevin.fanzhou.cloud.CloudFile? = null,
    /** "sourceId:folderId" for cloud items; scopes the remembered-quality feature to one folder. */
    val cloudFolderKey: String? = null,
    val mime: String? = null,
    /** Container/extension hint (mkv, iso, rmvb…) used to pick the player core. */
    val container: String? = null,
) {
    val effectiveResumeKey: String
        get() = resumeKey
            ?: jfItemId?.let { "jf:${sourceId ?: ""}:$it" }
            ?: cloudFile?.let { "cloud:${sourceId ?: ""}:${it.id}" }
            ?: "url:${url.substringBefore('?')}"

    /** Scopes episode settings (like custom skip intro/outro seconds) to the entire series/folder. */
    val seriesKey: String
        get() {
            if (!cloudFolderKey.isNullOrEmpty()) return "cloud:$cloudFolderKey"
            if (!jfItemId.isNullOrEmpty()) return "jf:${sourceId ?: ""}:$title"
            if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("smb://") || url.startsWith("file://") || url.startsWith("/")) {
                val parent = url.substringBefore('?').substringBeforeLast('/')
                if (parent.isNotEmpty()) return "dir:${sourceId ?: ""}:$parent"
            }
            return "title:$title"
        }
}

data class SubTrack(val url: String, val label: String, val mime: String, val lang: String? = null) {
    companion object {
        val SUB_EXT = setOf("srt", "ass", "ssa", "vtt")
        fun mimeFor(extOrCodec: String): String = when (extOrCodec.lowercase()) {
            "ass", "ssa" -> "text/x-ssa"
            "vtt", "webvtt" -> "text/vtt"
            else -> "application/x-subrip"
        }
    }
}

object PlayQueue {
    var items: List<PlayItem> = emptyList()
    var index: Int = 0
    fun set(list: List<PlayItem>, start: Int) { items = list; index = start }
}
