package com.kevin.fanzhou.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kevin.fanzhou.data.CORE_EXO
import com.kevin.fanzhou.data.CORE_MPV
import com.kevin.fanzhou.data.CloudRecent
import com.kevin.fanzhou.data.PlayItem
import com.kevin.fanzhou.data.PlayQueue
import com.kevin.fanzhou.data.Repo

/** Chooses between the ExoPlayer and MPV cores. */
object PlayerCore {
    /** Containers ExoPlayer cannot open (or opens badly): go straight to MPV in 自动 mode. */
    val MPV_EXT = setOf("iso", "rmvb", "rm", "wmv", "asf", "vob", "m2ts", "mts", "dat", "divx", "ogm", "bdmv", "img")

    /** Set when the user explicitly picked this core for the video: no automatic core switching. */
    const val EXTRA_FORCED = "core_forced"
    const val EXTRA_HANDOFF = "core_handoff"

    fun extOf(p: PlayItem): String {
        p.container?.takeIf { it.isNotEmpty() }?.let { return it.lowercase() }
        p.cloudFile?.let { return it.ext }
        val seg = runCatching { Uri.parse(p.url).lastPathSegment }.getOrNull() ?: return ""
        return seg.substringAfterLast('.', "").lowercase()
    }

    fun prefersMpv(p: PlayItem) = extOf(p) in MPV_EXT

    /** Whether libmpv's native libraries load on this device. */
    val mpvAvailable: Boolean by lazy {
        try {
            System.loadLibrary("mpv"); System.loadLibrary("player"); true
        } catch (_: Throwable) { false }
    }

    fun start(context: Context, items: List<PlayItem>, index: Int) {
        PlayQueue.set(items, index)
        val core = Repo.store.playerCore
        val mpv = when (core) {
            CORE_MPV -> true
            CORE_EXO -> false
            else -> items.getOrNull(index)?.let { prefersMpv(it) } == true
        } && mpvAvailable
        launch(context, mpv, forced = false)
    }

    fun launch(context: Context, mpv: Boolean, forced: Boolean, handoff: Boolean = false) {
        val i = Intent(context, if (mpv) MpvPlayerActivity::class.java else PlayerActivity::class.java)
            .putExtra(EXTRA_FORCED, forced)
            .putExtra(EXTRA_HANDOFF, handoff)
        if (context !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }

    /**
     * 播放切到新的网盘视频时（自动连播、选集、下一集…），把它顶到最近播放最前面。
     * 保留已有条目的目录名等信息，只刷新时间。
     */
    fun bumpCloudRecent(p: PlayItem) {
        val cf = p.cloudFile ?: return
        val sid = p.sourceId ?: return
        val old = Repo.store.cloudRecent(sid).firstOrNull { it.id == cf.id }
        val dirId = p.cloudFolderKey?.substringAfter(':', "")?.takeIf { it.isNotEmpty() }
        val finalDirId = old?.dirId ?: dirId
        val finalDirName = old?.dirName
        Repo.store.addCloudRecent(
            sid,
            CloudRecent(
                id = cf.id,
                name = cf.name,
                extra = cf.extra ?: old?.extra,
                size = if (cf.size > 0) cf.size else (old?.size ?: 0),
                thumb = cf.thumb ?: old?.thumb,
                duration = if (cf.duration > 0) cf.duration else (old?.duration ?: 0),
                dirId = finalDirId,
                dirName = finalDirName,
            ),
        )
    }

    val JF_PRESETS = listOf(
        Triple("超清 1080P · 20Mbps", 20_000_000, 1920),
        Triple("超清 1080P · 10Mbps", 10_000_000, 1920),
        Triple("高清 720P · 4Mbps", 4_000_000, 1280),
        Triple("标清 480P · 1.5Mbps", 1_500_000, 854),
        Triple("流畅 360P · 0.7Mbps", 700_000, 640),
    )

    fun matchQuality(qs: List<Pair<String, com.kevin.fanzhou.cloud.Resolved>>, target: String?): Pair<String, com.kevin.fanzhou.cloud.Resolved>? {
        if (qs.isEmpty() || target.isNullOrEmpty() || target == "原画") return null
        val exact = qs.firstOrNull { it.first == target }
        if (exact != null) return exact

        val res = Regex("(?i)(4K|2K|1080P|720P|540P|480P|360P)").find(target)?.value
        if (res != null) {
            val hit = qs.firstOrNull { it.first.contains(res, ignoreCase = true) }
            if (hit != null) return hit
        }

        val order = listOf("4K", "2K", "1080P", "720P", "540P", "480P", "360P")
        val targetIdx = if (res != null) order.indexOfFirst { it.equals(res, ignoreCase = true) } else 2
        val best = qs.minByOrNull { item ->
            val p = Regex("(?i)(4K|2K|1080P|720P|540P|480P|360P)").find(item.first)?.value
            val idx = if (p != null) order.indexOfFirst { it.equals(p, ignoreCase = true) } else 99
            if (idx >= targetIdx) idx - targetIdx else (100 + (targetIdx - idx))
        }
        return best ?: qs.firstOrNull()
    }
}
