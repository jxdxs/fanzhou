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

    fun launch(context: Context, mpv: Boolean, forced: Boolean) {
        val i = Intent(context, if (mpv) MpvPlayerActivity::class.java else PlayerActivity::class.java)
            .putExtra(EXTRA_FORCED, forced)
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
                at = System.currentTimeMillis(),
            ),
        )
    }
}
