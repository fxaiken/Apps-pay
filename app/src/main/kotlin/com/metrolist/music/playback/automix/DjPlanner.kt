/**
 * FRAME MUSIC — DJ mode (GPL-3.0, as the rest of the app)
 */

package com.metrolist.music.playback.automix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The maths of a DJ blend between two songs: match the tempo, line up the beats, swap the bass half
 * way and bring the incoming song back to its own tempo afterwards. Pure functions, unit-tested.
 */
object DjPlanner {
    /** A song's beat grid in its own media time: beats at [firstBeatMs] + k × [beatMs]. */
    data class BeatGrid(val firstBeatMs: Double, val beatMs: Double) {
        val bpm: Double get() = 60_000.0 / beatMs

        /** Where [positionMs] falls inside its beat, 0 (on the beat) up to 1. */
        fun phaseAt(positionMs: Double): Double {
            val beats = (positionMs - firstBeatMs) / beatMs
            return beats - floor(beats)
        }

        /** The first beat at or after [positionMs]. */
        fun nextBeatAt(positionMs: Double): Double {
            val k = Math.ceil((positionMs - firstBeatMs) / beatMs - 1e-9)
            return firstBeatMs + k * beatMs
        }
    }

    /** Builds a grid from the analysis: the next beat is [msToNextBeat] after media time [processedMs]. */
    fun gridFrom(processedMs: Long, tempo: TrackAnalysis.Tempo): BeatGrid {
        val nextBeat = processedMs + tempo.msToNextBeat
        val first = nextBeat - floor(nextBeat / tempo.beatMs) * tempo.beatMs
        return BeatGrid(first, tempo.beatMs)
    }

    /**
     * Speed to play the incoming song at so its tempo matches the outgoing one, allowing for one song
     * being counted at half or double time. Null when the two are too far apart to blend cleanly.
     */
    fun tempoRatio(outBpm: Double, inBpm: Double, maxChange: Double = MAX_TEMPO_CHANGE): Double? {
        if (outBpm <= 0 || inBpm <= 0) return null
        return listOf(1.0, 0.5, 2.0)
            .map { outBpm / (inBpm * it) }
            .filter { abs(it - 1) <= maxChange }
            .minByOrNull { abs(it - 1) }
    }

    /**
     * How far the incoming beats are from the outgoing ones, as a fraction of a beat in -0.5..0.5.
     * Positive: the incoming song is ahead. Both positions are what is being heard right now.
     */
    fun phaseError(outGrid: BeatGrid, outPositionMs: Double, inGrid: BeatGrid, inPositionMs: Double): Double {
        var e = inGrid.phaseAt(inPositionMs) - outGrid.phaseAt(outPositionMs)
        if (e >= 0.5) e -= 1
        if (e < -0.5) e += 1
        return e
    }

    /** Seek, in the incoming song's media time, that cancels [phaseError]. */
    fun correctionMs(phaseError: Double, inGrid: BeatGrid): Long = -(phaseError * inGrid.beatMs).toLong()

    /** When the blend starts so that it ends as the outgoing song ends. */
    fun mixStartMs(durationMs: Long, outBeatMs: Double, beats: Int = MIX_BEATS): Long =
        (durationMs - beats * outBeatMs).toLong().coerceAtLeast(0)

    /** Incoming level: rises to full over the first half of the blend. */
    fun incomingGain(progress: Float): Float {
        val p = (progress.coerceIn(0f, 1f) * 2f).coerceAtMost(1f)
        return sin(p * PI / 2).toFloat()
    }

    /** Outgoing level: stays full for the first half, then fades out. */
    fun outgoingGain(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0.5f) return 1f
        return cos((p - 0.5f) * 2f * PI / 2).toFloat()
    }

    /**
     * High-pass cutoff for the incoming song: its bass stays out until the half-way point, then comes
     * back over a tenth of the blend. 0 means no filter.
     */
    fun incomingCutoffHz(progress: Float): Float =
        when {
            progress < 0.5f -> BASS_CUT_HZ
            progress < 0.6f -> BASS_CUT_HZ * (1f - (progress - 0.5f) / 0.1f)
            else -> 0f
        }

    /** High-pass cutoff for the outgoing song: its bass leaves as the incoming bass arrives. */
    fun outgoingCutoffHz(progress: Float): Float =
        when {
            progress < 0.5f -> 0f
            progress < 0.6f -> BASS_CUT_HZ * (progress - 0.5f) / 0.1f
            else -> BASS_CUT_HZ
        }

    /** After the blend, the incoming song glides back from [ratio] to its own tempo over [rampMs]. */
    fun speedAfterMix(elapsedMs: Long, ratio: Double, rampMs: Long = TEMPO_RETURN_MS): Double {
        val t = (elapsedMs.toDouble() / rampMs).coerceIn(0.0, 1.0)
        return ratio + (1.0 - ratio) * t
    }

    const val MAX_TEMPO_CHANGE = 0.08
    const val MIX_BEATS = 16
    const val BASS_CUT_HZ = 300f
    const val TEMPO_RETURN_MS = 12_000L
    /** How long the next song plays silently beforehand so its tempo can be measured. */
    const val PREROLL_MS = 9_000L
}
