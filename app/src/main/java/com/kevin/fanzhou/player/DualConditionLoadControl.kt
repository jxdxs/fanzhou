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
 * 双条件协同加载控制：
 * 必须【同时满足】「目标缓存容量（Bytes）」与「目标预加载时长（Duration）」两个条件才会停止预加载，
 * 避免单一条件（仅时间或仅容量）满足就过早中断流媒体缓冲导致的快进卡顿。
 */
@OptIn(UnstableApi::class)
class DualConditionLoadControl(
    private val base: DefaultLoadControl,
    private val allocator: DefaultAllocator,
    private val targetBytes: Long,
    private val targetDurationUs: Long,
    private val maxSafeBytes: Long,
    private val minBufferUs: Long = 30_000_000L,
) : LoadControl {

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
        if (allocated >= maxSafeBytes) {
            return false
        }
        if (parameters.bufferedDurationUs < minBufferUs) {
            return true
        }
        val sizeMet = allocated >= targetBytes
        val timeMet = parameters.bufferedDurationUs >= targetDurationUs
        // 需两个条件均满足才停止预加载；若任一条件未达标则持续预加载
        if (sizeMet && timeMet) {
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
            return false
        }
        if (bufferedDurationUs < minBufferUs) {
            return true
        }
        val sizeMet = allocated >= targetBytes
        val timeMet = bufferedDurationUs >= targetDurationUs
        if (sizeMet && timeMet) {
            return false
        }
        return true
    }

    override fun shouldContinuePreloading(
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long
    ): Boolean = base.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)

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
