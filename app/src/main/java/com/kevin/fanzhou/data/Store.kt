package com.kevin.fanzhou.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val CORE_AUTO = "auto"
const val CORE_EXO = "exo"
const val CORE_MPV = "mpv"

class Store(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("fanzhou", Context.MODE_PRIVATE)
    private val posPrefs: SharedPreferences = context.getSharedPreferences("positions", Context.MODE_PRIVATE)
    private val tmdbPrefs: SharedPreferences = context.getSharedPreferences("tmdb_cache", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _sources = MutableStateFlow(loadSources())
    val sources: StateFlow<List<Source>> = _sources

    private fun loadSources(): List<Source> = try {
        json.decodeFromString(prefs.getString("sources", "[]") ?: "[]")
    } catch (e: Exception) { emptyList() }

    private fun saveSources(list: List<Source>) {
        prefs.edit().putString("sources", json.encodeToString(list)).apply()
        _sources.value = list
    }

    fun upsert(source: Source) {
        val list = _sources.value.toMutableList()
        val i = list.indexOfFirst { it.id == source.id }
        if (i >= 0) list[i] = source else list.add(source)
        saveSources(list)
    }

    /** Re-reads sources after a config import. */
    fun reloadSources() { _sources.value = loadSources() }

    fun remove(id: String) = saveSources(_sources.value.filterNot { it.id == id })
    fun get(id: String): Source? = _sources.value.firstOrNull { it.id == id }

    var tmdbKey: String
        get() = prefs.getString("tmdb_key", "") ?: ""
        set(v) { prefs.edit().putString("tmdb_key", v.trim()).apply() }

    var deviceId: String
        get() = prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also { deviceId = it }
        set(v) { prefs.edit().putString("device_id", v).apply() }

    var defaultSpeed: Float
        get() = prefs.getFloat("speed", 1f)
        set(v) { prefs.edit().putFloat("speed", v).apply() }

    /** 播放器屏幕方向：landscape / portrait / auto */
    var playerOrientation: String
        get() = prefs.getString("player_orientation", "landscape") ?: "landscape"
        set(v) { prefs.edit().putString("player_orientation", v).apply() }

    var seekStepSec: Int
        get() = prefs.getInt("seek_step", 10)
        set(v) { prefs.edit().putInt("seek_step", v).apply() }

    var autoPlayNext: Boolean
        get() = prefs.getBoolean("auto_next", true)
        set(v) { prefs.edit().putBoolean("auto_next", v).apply() }

    /** Manually picked cloud quality label remembered per folder ("source:folder" key). */
    fun qualityForFolder(key: String): String? = prefs.getString("quality_folder_$key", null)
    fun setQualityForFolder(key: String, label: String?) {
        if (label.isNullOrEmpty()) prefs.edit().remove("quality_folder_$key").apply()
        else prefs.edit().putString("quality_folder_$key", label).apply()
    }

    var gridColumnsMin: Int
        get() = prefs.getInt("grid_min", 110)
        set(v) { prefs.edit().putInt("grid_min", v).apply() }

    /** 播放内核: "auto" (ExoPlayer, switch to MPV when needed), "exo" or "mpv". */
    var playerCore: String
        get() = prefs.getString("player_core", CORE_AUTO) ?: CORE_AUTO
        set(v) { prefs.edit().putString("player_core", v).apply() }

    /** MPV hardware decoding (mediacodec-copy); off = software decoding. */
    var mpvHwdec: Boolean
        get() = prefs.getBoolean("mpv_hwdec", true)
        set(v) { prefs.edit().putBoolean("mpv_hwdec", v).apply() }

    /** 跳过片头：一键跳到该秒数（默认 90 秒）。 */
    var introSkipSec: Int
        get() = prefs.getInt("intro_skip_sec", 90)
        set(v) { prefs.edit().putInt("intro_skip_sec", v).apply() }

    var folderSort: String
        get() = prefs.getString("folder_sort", "name") ?: "name"
        set(v) { prefs.edit().putString("folder_sort", v).apply() }

    var folderVideosOnly: Boolean
        get() = prefs.getBoolean("folder_videos_only", true)
        set(v) { prefs.edit().putBoolean("folder_videos_only", v).apply() }

    // ---- cloud drive browser ----
    /** View mode per cloud source: "list" / "grid" / "poster". */
    fun cloudView(sid: String): String = prefs.getString("cloud_view_$sid", "list") ?: "list"
    fun setCloudView(sid: String, v: String) { prefs.edit().putString("cloud_view_$sid", v).apply() }

    /** Cloud sort key: "name" / "time" / "size". */
    var cloudSort: String
        get() = prefs.getString("cloud_sort", "name") ?: "name"
        set(v) { prefs.edit().putString("cloud_sort", v).apply() }
    var cloudSortDesc: Boolean
        get() = prefs.getBoolean("cloud_sort_desc", false)
        set(v) { prefs.edit().putBoolean("cloud_sort_desc", v).apply() }
    var cloudFoldersFirst: Boolean
        get() = prefs.getBoolean("cloud_folders_first", true)
        set(v) { prefs.edit().putBoolean("cloud_folders_first", v).apply() }
    var cloudVideosOnly: Boolean
        get() = prefs.getBoolean("cloud_videos_only", true)
        set(v) { prefs.edit().putBoolean("cloud_videos_only", v).apply() }

    /** Recently played cloud videos for one source, newest first. */
    fun cloudRecent(sid: String): List<CloudRecent> = try {
        json.decodeFromString(prefs.getString("cloud_recent_$sid", "[]") ?: "[]")
    } catch (e: Exception) { emptyList() }

    fun addCloudRecent(sid: String, r: CloudRecent) {
        val list = (listOf(r) + cloudRecent(sid).filterNot { it.id == r.id }).take(20)
        prefs.edit().putString("cloud_recent_$sid", json.encodeToString(list)).apply()
    }

    /** Favorite cloud videos & folders for one source, newest first. */
    fun cloudFavs(sid: String): List<CloudFav> = try {
        json.decodeFromString(prefs.getString("cloud_favs_$sid", "[]") ?: "[]")
    } catch (e: Exception) { emptyList() }

    fun isCloudFav(sid: String, id: String): Boolean = cloudFavs(sid).any { it.id == id }

    /** Returns true if the item is now a favorite, false if it was removed. */
    fun toggleCloudFav(sid: String, fav: CloudFav): Boolean {
        val cur = cloudFavs(sid)
        return if (cur.any { it.id == fav.id }) {
            prefs.edit().putString("cloud_favs_$sid", json.encodeToString(cur.filterNot { it.id == fav.id })).apply()
            false
        } else {
            prefs.edit().putString("cloud_favs_$sid", json.encodeToString(listOf(fav) + cur)).apply()
            true
        }
    }

    fun removeCloudFav(sid: String, id: String) {
        val cur = cloudFavs(sid)
        prefs.edit().putString("cloud_favs_$sid", json.encodeToString(cur.filterNot { it.id == id })).apply()
    }

    fun position(key: String): Long = posPrefs.getLong(key, 0)
    fun savePosition(key: String, ms: Long) { posPrefs.edit().putLong(key, ms).apply() }

    // ---- online subtitle providers (user's own credentials) ----
    var assrtToken: String
        get() = prefs.getString("assrt_token", "") ?: ""
        set(v) { prefs.edit().putString("assrt_token", v.trim()).apply() }
    var subdlKey: String
        get() = prefs.getString("subdl_key", "") ?: ""
        set(v) { prefs.edit().putString("subdl_key", v.trim()).apply() }
    var osApiKey: String
        get() = prefs.getString("os_api_key", "") ?: ""
        set(v) { prefs.edit().putString("os_api_key", v.trim()).apply() }

    /** Downloaded online subtitle cached per item (resumeKey -> local file path). */
    fun savedSub(resumeKey: String): String? = prefs.getString("osub_$resumeKey", null)
    fun saveSub(resumeKey: String, path: String) { prefs.edit().putString("osub_$resumeKey", path).apply() }

    fun tmdbCached(key: String): String? = tmdbPrefs.getString(key, null)
    fun tmdbPut(key: String, value: String) { tmdbPrefs.edit().putString(key, value).apply() }
    fun clearTmdbCache() { tmdbPrefs.edit().clear().apply() }
}

@kotlinx.serialization.Serializable
data class CloudFav(
    val id: String,
    val name: String,
    val isDir: Boolean,
    val extra: String? = null,
    val size: Long = 0,
    val thumb: String? = null,
    val duration: Long = 0,
    /** Videos: containing folder. Folders: unused. */
    val dirId: String? = null,
    val dirName: String? = null,
    /** Folders: crumb names of the parent folder, for direct navigation. */
    val path: List<String> = emptyList(),
    val at: Long = 0,
)

@kotlinx.serialization.Serializable
data class CloudRecent(
    val id: String,
    val name: String,
    val extra: String? = null,
    val size: Long = 0,
    val thumb: String? = null,
    val duration: Long = 0,
    val dirId: String? = null,
    val dirName: String? = null,
    val at: Long = 0,
)
