/**
 * FRAME MUSIC — smart transitions (GPL-3.0, as the rest of the app)
 */

package com.metrolist.music.playback.automix

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Decides when the next song should start and how long the mix lasts, from what [TrackAnalysis]
 * heard. Pure functions so every rule is unit-tested.
 */
object TransitionPlanner {
    enum class Reason { SILENCE, FADE_OUT, SCHEDULED }

    data class Plan(
        /** Wait this long before starting the mix (to land on a beat). */
        val delayMs: Long,
        val fadeMs: Long,
        val reason: Reason,
        /** Set when the mix was fitted to the song's beat. */
        val bpm: Double? = null,
    )

    /** The song is ending early (trailing silence or a fade-out): start the next one now instead of waiting. */
    fun detectEarlyEnd(
        remainingMs: Long,
        bodyDb: Float?,
        last600Db: Float,
        last1500Db: Float,
        last750Db: Float,
        previous750Db: Float,
        maxFadeMs: Long,
    ): Plan? {
        if (bodyDb == null) return null
        if (remainingMs < MIN_REMAINING_MS || remainingMs > SILENCE_WINDOW_MS) return null

        val silenceThreshold = maxOf(SILENCE_DB, bodyDb - SILENCE_BELOW_BODY_DB)
        if (last600Db < silenceThreshold) {
            return Plan(0, minOf(SILENCE_FADE_MS, remainingMs), Reason.SILENCE)
        }

        if (remainingMs <= FADE_OUT_WINDOW_MS &&
            last1500Db < bodyDb - FADE_OUT_DROP_DB &&
            last750Db <= previous750Db + FADE_OUT_RISE_TOLERANCE_DB
        ) {
            val fade = remainingMs.coerceIn(MIN_FADE_MS, maxOf(MIN_FADE_MS, maxFadeMs))
            return Plan(0, fade, Reason.FADE_OUT)
        }
        return null
    }

    /**
     * Fits a planned mix to the beat of the outgoing song: start on the next beat and make the mix a
     * whole number of beats (4, 8, 16 or 32) close to the wanted length. Without a confident tempo,
     * or when there is no room, the plan is returned unchanged.
     */
    fun alignToBeat(
        plan: Plan,
        tempo: TrackAnalysis.Tempo?,
        msToNextBeatHeard: Double?,
        remainingMs: Long,
    ): Plan {
        if (plan.reason == Reason.SILENCE) return plan
        if (tempo == null || msToNextBeatHeard == null || tempo.confidence < ALIGN_MIN_CONFIDENCE) return plan
        val delay = msToNextBeatHeard
        if (delay < 0 || delay > tempo.beatMs + 1) return plan

        val room = remainingMs - delay
        val candidates = listOf(4, 8, 16, 32)
            .map { (it * tempo.beatMs).roundToLong() }
            .filter { it in MIN_FADE_MS..room.toLong() }
        if (candidates.isEmpty()) return plan
        val fade = candidates.minBy { abs(it - plan.fadeMs) }
        return plan.copy(delayMs = delay.roundToLong(), fadeMs = fade, bpm = tempo.bpm)
    }

    /**
     * Mix progress after [elapsedMs], holding still while the incoming song is still silent at its start
     * (up to [MAX_HOLD_MS]), so a song with a quiet intro does not leave a hole.
     */
    fun nextProgress(
        current: Float,
        stepMs: Long,
        fadeMs: Long,
        incomingAudible: Boolean,
        heldMs: Long,
    ): Float {
        if (!incomingAudible && heldMs < MAX_HOLD_MS) return current
        return (current + stepMs.toFloat() / fadeMs.coerceAtLeast(1)).coerceAtMost(1f)
    }

    /** Gentle limiter for normalisation boosts: untouched below -1 dBFS, then rounds off instead of clipping hard. */
    fun softClip(x: Double): Double {
        val a = abs(x)
        if (a <= SOFT_CLIP_KNEE) return x
        val over = (a - SOFT_CLIP_KNEE) / (1 - SOFT_CLIP_KNEE)
        val shaped = SOFT_CLIP_KNEE + (1 - SOFT_CLIP_KNEE) * kotlin.math.tanh(over)
        return if (x < 0) -shaped else shaped
    }

    const val MIN_REMAINING_MS = 400L
    const val SILENCE_WINDOW_MS = 25_000L
    const val FADE_OUT_WINDOW_MS = 15_000L
    const val SILENCE_DB = -55f
    const val SILENCE_BELOW_BODY_DB = 35f
    const val SILENCE_FADE_MS = 1_200L
    const val FADE_OUT_DROP_DB = 12f
    const val FADE_OUT_RISE_TOLERANCE_DB = 1f
    const val MIN_FADE_MS = 2_000L
    const val ALIGN_MIN_CONFIDENCE = 0.35
    const val MAX_HOLD_MS = 2_000L
    const val SOFT_CLIP_KNEE = 0.891
}
