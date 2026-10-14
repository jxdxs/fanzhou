package com.kevin.fanzhou.player

import android.media.audiofx.LoudnessEnhancer
import com.kevin.fanzhou.data.Repo
import dev.jdtech.mpv.MPVLib

/**
 * Universal audio volume boost and speech/dialogue enhancement for ExoPlayer and MPV.
 */
object AudioBoost {

    fun applyExo(enhancer: LoudnessEnhancer?, audioSessionId: Int): LoudnessEnhancer? {
        if (audioSessionId == 0) {
            try { enhancer?.release() } catch (_: Throwable) {}
            return null
        }
        val boost = Repo.store.volumeBoost
        val voice = Repo.store.voiceEnhance
        if (boost <= 1.0f && !voice) {
            try { enhancer?.enabled = false } catch (_: Throwable) {}
            return enhancer
        }
        val target = enhancer ?: try {
            LoudnessEnhancer(audioSessionId)
        } catch (_: Throwable) {
            return null
        }
        try {
            // Target gain in mB: 1000 mB = +10dB, double perceived volume
            val gainMb = ((boost - 1.0f) * 1000).toInt() + (if (voice) 350 else 0)
            target.setTargetGain(gainMb.coerceIn(0, 1500))
            target.enabled = true
        } catch (_: Throwable) {}
        return target
    }

    fun applyMpv(mpv: MPVLib?) {
        if (mpv == null) return
        val boost = Repo.store.volumeBoost
        val voice = Repo.store.voiceEnhance
        try {
            // MPV volume: 100 is normal (100%), up to 200 (200%)
            val vol = (boost * 100.0).coerceIn(100.0, 200.0)
            mpv.setPropertyDouble("volume", vol)
            if (voice) {
                // dynaudnorm: dynamic audio normalizer for night/dialogue voice boost
                mpv.setPropertyString("af", "lavfi=[dynaudnorm=f=75:g=25:p=0.55]")
            } else {
                mpv.setPropertyString("af", "")
            }
        } catch (_: Throwable) {}
    }
}
