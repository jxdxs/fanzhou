package com.kevin.fanzhou.player

import androidx.annotation.OptIn
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * 时间驱动型流媒体预加载控制：
 * 持续预加载指定深度时长（3 / 5 / 8 分钟），
 * 并配置严格的 Java 堆内存上限保护，坚决防止内存溢出（OOM）导致播放界面闪退。
 */
@OptIn(UnstableApi::class)
class DualConditionLoadControl(
    private val base: DefaultLoadControl,
    private val allocator: DefaultAllocator,
    private val targetDurationUs: Long,
    private val maxSafeBytes: Long,
    private val minBufferUs: Long = 30_000_000L,
) : LoadControl {

    /** 硬上限：软上限 ×1.25，且不超过 JVM 最大堆的 45%。 */
    private val hardCapBytes: Long =
        minOf((maxSafeBytes * 1.25).toLong(), (Runtime.getRuntime().maxMemory() * 0.45).toLong())
            .coerceAtLeast(maxSafeBytes)

    override fun onPrepared() {
        base.onPrepared(PlayerId.UNSET)
    }

    override fun onPrepared(playerId: PlayerId) {
        base.onPrepared(playerId)
    }

    @Deprecated("Deprecated in Java")
    override fun onTracksSelected(
        renderers: Array<out Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>
    ) {
        base.onTracksSelected(renderers, trackGroups, trackSelections)
    }

    @Deprecated("Deprecated in Java")
    override fun onTracksSelected(
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        renderers: Array<out Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>
    ) {
        base.onTracksSelected(timeline, mediaPeriodId, renderers, trackGroups, trackSelections)
    }

    @Deprecated("Deprecated in Java")
    override fun onTracksSelected(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        renderers: Array<out Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>
    ) {
        base.onTracksSelected(playerId, timeline, mediaPeriodId, renderers, trackGroups, trackSelections)
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>
    ) {
        base.onTracksSelected(parameters, trackGroups, trackSelections)
    }

    override fun onStopped() {
        base.onStopped(PlayerId.UNSET)
    }

    override fun onStopped(playerId: PlayerId) {
        base.onStopped(playerId)
    }

    override fun onReleased() {
        base.onReleased(PlayerId.UNSET)
    }

    override fun onReleased(playerId: PlayerId) {
        base.onReleased(playerId)
    }

    override fun getAllocator(): Allocator = allocator

    @Deprecated("Deprecated in Java")
    override fun getBackBufferDurationUs(): Long = base.getBackBufferDurationUs(PlayerId.UNSET)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = base.getBackBufferDurationUs(playerId)

    @Deprecated("Deprecated in Java")
    override fun retainBackBufferFromKeyframe(): Boolean = base.retainBackBufferFromKeyframe(PlayerId.UNSET)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = base.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val allocated = allocator.totalBytesAllocated.toLong()
        // 1. 堆内存硬保护：超过安全上限立即停止加载，坚决杜绝 OOM 导致播放界面闪退
        if (allocated >= maxSafeBytes) {
            // 回看缓冲也占用同一预算：前向不足保底时允许小幅越过软上限（不超过硬上限），避免回看缓冲挤占导致前向断流
            return parameters.bufferedDurationUs < minBufferUs && allocated < hardCapBytes
        }
        // 2. 基础保底缓冲：未满 minBufferUs (30秒) 持续缓冲
        if (parameters.bufferedDurationUs < minBufferUs) {
            return true
        }
        // 3. 达到设定的预加载时长（3 / 5 / 8 分钟）即停止继续预加载
        if (parameters.bufferedDurationUs >= targetDurationUs) {
            return false
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun shouldContinueLoading(
        playbackPositionUs: Long,
        bufferedDurationUs: Long,
        playbackSpeed: Float
    ): Boolean {
        val allocated = allocator.totalBytesAllocated.toLong()
        if (allocated >= maxSafeBytes) {
            return bufferedDurationUs < minBufferUs && allocated < hardCapBytes
        }
        if (bufferedDurationUs < minBufferUs) {
            return true
        }
        if (bufferedDurationUs >= targetDurationUs) {
            return false
        }
        return true
    }

    override fun shouldContinuePreloading(
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long
    ): Boolean {
        val allocated = allocator.totalBytesAllocated.toLong()
        if (allocated >= maxSafeBytes) {
            return false
        }
        if (bufferedDurationUs < minBufferUs) {
            return true
        }
        if (bufferedDurationUs >= targetDurationUs) {
            return false
        }
        return true
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        base.shouldStartPlayback(parameters)

    @Deprecated("Deprecated in Java")
    override fun shouldStartPlayback(
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
        playbackSpeed: Float,
        rebuffering: Boolean,
        targetLiveOffsetUs: Long
    ): Boolean = base.shouldStartPlayback(timeline, mediaPeriodId, bufferedDurationUs, playbackSpeed, rebuffering, targetLiveOffsetUs)

    @Deprecated("Deprecated in Java")
    override fun shouldStartPlayback(
        bufferedDurationUs: Long,
        playbackSpeed: Float,
        rebuffering: Boolean,
        targetLiveOffsetUs: Long
    ): Boolean = base.shouldStartPlayback(bufferedDurationUs, playbackSpeed, rebuffering, targetLiveOffsetUs)
}
