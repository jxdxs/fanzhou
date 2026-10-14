package com.kevin.fanzhou.player

import android.view.View
import com.kevin.fanzhou.data.Repo

/**
 * Controller managing intro & outro skipping logic and dialogs
 * shared across PlayerActivity and MpvPlayerActivity.
 */
class PlayerSkipController {
    var introSkippedIndex = -1
    var outroSkippedIndex = -1

    fun onIndexChanged(newIndex: Int, startMs: Long) {
        introSkippedIndex = if (startMs > 5000L) newIndex else -1
        outroSkippedIndex = -1
    }

    fun checkAutoSkip(
        posMs: Long,
        durMs: Long,
        currentIndex: Int,
        queueSize: Int,
        onSeek: (Long) -> Unit,
        onNextEpisode: () -> Unit,
        showHint: (String) -> Unit,
    ) {
        if (durMs <= 0 || currentIndex < 0) return

        // 1. 自动跳过片头 (Auto Skip Intro)
        val introSec = Repo.store.introSkipSec
        if (introSec > 0 && introSkippedIndex != currentIndex) {
            val introTarget = introSec * 1000L
            if (durMs > introTarget + 30_000L) {
                if (posMs < introTarget - 2000L) {
                    introSkippedIndex = currentIndex
                    onSeek(introTarget)
                    showHint("已跳过片头")
                    return
                } else {
                    introSkippedIndex = currentIndex
                }
            } else {
                introSkippedIndex = currentIndex
            }
        }

        // 2. 自动跳过片尾 (Auto Skip Outro)
        val outroSec = Repo.store.outroSkipSec
        if (outroSec > 0 && outroSkippedIndex != currentIndex) {
            val outroTarget = durMs - outroSec * 1000L
            val minOutro = (introSec * 1000L) + 10_000L
            if (outroTarget > minOutro && posMs >= outroTarget) {
                outroSkippedIndex = currentIndex
                if (currentIndex + 1 < queueSize) {
                    onNextEpisode()
                    showHint("已跳过片尾，播放下一集")
                } else {
                    onSeek(maxOf(0L, durMs - 1000L))
                    showHint("已跳过片尾")
                }
            }
        }
    }

    fun showSkipIntroPopup(
        anchor: View,
        currentPosMs: Long,
        currentIndex: Int,
        onSeek: (Long) -> Unit,
        showHint: (String) -> Unit,
        toast: (String) -> Unit,
    ) {
        SkipPopup.show(
            anchor, "跳过片头", Repo.store.introSkipSec, 0, 180,
            onSecChanged = { Repo.store.introSkipSec = it },
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
        hasNext: Boolean,
        onNext: () -> Unit,
        onSeekEnd: (Long) -> Unit,
        showHint: (String) -> Unit,
        toast: (String) -> Unit,
    ) {
        SkipPopup.show(
            anchor, "跳过片尾", Repo.store.outroSkipSec, 0, 300,
            onSecChanged = { Repo.store.outroSkipSec = it },
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
