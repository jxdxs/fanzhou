package com.kevin.fanzhou.data

import android.content.Context
import android.content.SharedPreferences
import com.kevin.fanzhou.cloud.CloudFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class FolderStat(
    val total: Int,
    val videoCount: Int,
    val dirCount: Int,
    val totalSize: Long,
    val timestamp: Long,
) {
    val otherCount: Int get() = (total - videoCount - dirCount).coerceAtLeast(0)
}

object FolderStatCache {
    private const val PREF_NAME = "folder_stat_cache"
    private var prefs: SharedPreferences? = null
    private val memCache = ConcurrentHashMap<String, FolderStat>()

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs = sp
        try {
            sp.all.forEach { (k, v) ->
                if (v is String) {
                    parseStat(v)?.let { memCache[k] = it }
                }
            }
        } catch (_: Exception) {}
    }

    private fun parseStat(raw: String): FolderStat? {
        val parts = raw.split(':')
        if (parts.size < 5) return null
        val total = parts[0].toIntOrNull() ?: return null
        val vids = parts[1].toIntOrNull() ?: return null
        val dirs = parts[2].toIntOrNull() ?: return null
        val size = parts[3].toLongOrNull() ?: return null
        val ts = parts[4].toLongOrNull() ?: return null
        return FolderStat(total, vids, dirs, size, ts)
    }

    private fun cacheKey(sid: String, dirId: String) = "$sid:$dirId"

    fun get(sid: String, dirId: String): FolderStat? = memCache[cacheKey(sid, dirId)]

    fun put(sid: String, dirId: String, list: List<CloudFile>): FolderStat {
        val stat = fromList(list)
        put(sid, dirId, stat)
        return stat
    }

    fun put(sid: String, dirId: String, stat: FolderStat) {
        val key = cacheKey(sid, dirId)
        memCache[key] = stat
        prefs?.edit()?.putString(key, "${stat.total}:${stat.videoCount}:${stat.dirCount}:${stat.totalSize}:${stat.timestamp}")?.apply()
        _version.value++
    }

    fun clear() {
        memCache.clear()
        prefs?.edit()?.clear()?.apply()
        _version.value++
    }

    fun fromList(list: List<CloudFile>): FolderStat {
        var vids = 0
        var dirs = 0
        var totalSize = 0L
        for (f in list) {
            when {
                f.isDir -> dirs++
                f.isVideo -> {
                    vids++
                    if (f.size > 0) totalSize += f.size
                }
                else -> {
                    if (f.size > 0) totalSize += f.size
                }
            }
        }
        return FolderStat(
            total = list.size,
            videoCount = vids,
            dirCount = dirs,
            totalSize = totalSize,
            timestamp = System.currentTimeMillis(),
        )
    }
}
