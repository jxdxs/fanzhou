package com.kevin.fanzhou.player

import android.view.View
import com.kevin.fanzhou.data.Repo

data class PlayerChapter(
    val timeMs: Long,
    val title: String,
)

/**
 * Controller managing intro & outro skipping logic and dialogs
 * shared across PlayerActivity and MpvPlayerActivity.
 */
class PlayerSkipController {
    var introSkippedIndex = -1
    var outroSkippedIndex = -1
    var chapters: List<PlayerChapter> = emptyList()

    fun onIndexChanged(newIndex: Int, startMs: Long) {
        introSkippedIndex = if (startMs > 5000L) newIndex else -1
        outroSkippedIndex = -1
        chapters = emptyList()
    }

    fun checkAutoSkip(
        posMs: Long,
        durMs: Long,
        currentIndex: Int,
        queueSize: Int,
        seriesKey: String? = null,
        onSeek: (Long) -> Unit,
        onNextEpisode: () -> Unit,
        showHint: (String) -> Unit,
    ) {
        if (durMs <= 0 || currentIndex < 0) return

        // 1. 自动跳过片头 (Auto Skip Intro)
        if (introSkippedIndex != currentIndex) {
            val introSec = seriesKey?.let { Repo.store.introSkipForSeries(it) } ?: Repo.store.introSkipSec
            if (introSec > 0) {
                // Check chapter first for OP
                val opChapterIdx = chapters.indexOfFirst {
                    it.timeMs < (durMs / 2) && (
                        it.title.contains("op", ignoreCase = true) ||
                        it.title.contains("opening", ignoreCase = true) ||
                        it.title.contains("片头", ignoreCase = true) ||
                        it.title.contains("主题曲", ignoreCase = true)
                    )
                }
                val chapterIntroTarget = if (opChapterIdx >= 0 && opChapterIdx + 1 < chapters.size) {
                    chapters[opChapterIdx + 1].timeMs
                } else null

                val introTarget = if (chapterIntroTarget != null && chapterIntroTarget in 10_000L..300_000L) {
                    chapterIntroTarget
                } else {
                    introSec * 1000L
                }

                if (durMs > introTarget + 30_000L) {
                    if (posMs < introTarget - 2000L) {
                        introSkippedIndex = currentIndex
                        onSeek(introTarget)
                        val hint = if (chapterIntroTarget != null && introTarget == chapterIntroTarget) {
                            "已按章节跳过片头"
                        } else {
                            "已跳过片头"
                        }
                        showHint(hint)
                        return
                    } else {
                        introSkippedIndex = currentIndex
                    }
                } else {
                    introSkippedIndex = currentIndex
                }
            }
        }

        // 2. 自动跳过片尾 (Auto Skip Outro)
        if (outroSkippedIndex != currentIndex) {
            val outroSec = seriesKey?.let { Repo.store.outroSkipForSeries(it) } ?: Repo.store.outroSkipSec
            if (outroSec > 0) {
                val edChapter = chapters.firstOrNull {
                    it.timeMs > (durMs / 2) && (
                        it.title.contains("ed", ignoreCase = true) ||
                        it.title.contains("ending", ignoreCase = true) ||
                        it.title.contains("片尾", ignoreCase = true) ||
                        it.title.contains("credits", ignoreCase = true)
                    )
                }
                val outroTarget = edChapter?.timeMs ?: (durMs - outroSec * 1000L)

                val minOutro = 30_000L
                if (outroTarget > minOutro && posMs >= outroTarget) {
                    outroSkippedIndex = currentIndex
                    val hint = if (edChapter != null && outroTarget == edChapter.timeMs) {
                        if (currentIndex + 1 < queueSize) "已按章节跳过片尾，播放下一集" else "已按章节跳过片尾"
                    } else {
                        if (currentIndex + 1 < queueSize) "已跳过片尾，播放下一集" else "已跳过片尾"
                    }
                    if (currentIndex + 1 < queueSize) {
                        onNextEpisode()
                        showHint(hint)
                    } else {
                        onSeek(maxOf(0L, durMs - 1000L))
                        showHint(hint)
                    }
                }
            }
        }
    }

    fun showSkipIntroPopup(
        anchor: View,
        currentPosMs: Long,
        currentIndex: Int,
        seriesKey: String? = null,
        onSeek: (Long) -> Unit,
        showHint: (String) -> Unit,
        toast: (String) -> Unit,
    ) {
        val seriesOverride = seriesKey?.let { Repo.store.introSkipForSeries(it) }
        val curSec = seriesOverride ?: Repo.store.introSkipSec
        SkipPopup.show(
            anchor = anchor,
            title = "跳过片头" + if (seriesOverride != null) "（本剧专属）" else "",
            initialSec = curSec,
            minSec = 0,
            maxSec = 180,
            seriesHint = seriesKey,
            initialRememberSeries = seriesKey != null,
            onSecChanged = { sec, remember ->
                if (seriesKey != null && remember) {
                    Repo.store.setIntroSkipForSeries(seriesKey, sec)
                } else {
                    if (seriesKey != null) Repo.store.setIntroSkipForSeries(seriesKey, null)
                    Repo.store.introSkipSec = sec
                }
            },
            onSkip = { sec ->
                if (sec <= 0) {
                    showHint("已关闭跳过片头")
                    return@show
                }
                val target = sec * 1000L
                if (currentPosMs < target - 2000L) {
                    introSkippedIndex = currentIndex
                    onSeek(target)
                    showHint("已跳过片头")
                } else toast("当前进度已超过片头（${sec}秒）")
            },
        )
    }

    fun showSkipOutroPopup(
        anchor: View,
        durMs: Long,
        currentIndex: Int,
        seriesKey: String? = null,
        hasNext: Boolean,
        onNext: () -> Unit,
        onSeekEnd: (Long) -> Unit,
        showHint: (String) -> Unit,
        toast: (String) -> Unit,
    ) {
        val seriesOverride = seriesKey?.let { Repo.store.outroSkipForSeries(it) }
        val curSec = seriesOverride ?: Repo.store.outroSkipSec
        SkipPopup.show(
            anchor = anchor,
            title = "跳过片尾" + if (seriesOverride != null) "（本剧专属）" else "",
            initialSec = curSec,
            minSec = 0,
            maxSec = 300,
            seriesHint = seriesKey,
            initialRememberSeries = seriesKey != null,
            onSecChanged = { sec, remember ->
                if (seriesKey != null && remember) {
                    Repo.store.setOutroSkipForSeries(seriesKey, sec)
                } else {
                    if (seriesKey != null) Repo.store.setOutroSkipForSeries(seriesKey, null)
                    Repo.store.outroSkipSec = sec
                }
            },
            onSkip = { sec ->
                if (sec <= 0) {
                    showHint("已关闭跳过片尾")
                    return@show
                }
                if (durMs <= 0) toast("无法跳过片尾")
                else {
                    outroSkippedIndex = currentIndex
                    if (hasNext) {
                        onNext()
                        showHint("已跳过片尾，播放下一集")
                    } else {
                        onSeekEnd(maxOf(0L, durMs - 2000L))
                        showHint("已跳过片尾")
                    }
                }
            },
        )
    }
}
