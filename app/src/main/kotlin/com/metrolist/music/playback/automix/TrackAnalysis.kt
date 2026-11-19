/**
 * FRAME MUSIC — smart transitions (GPL-3.0, as the rest of the app)
 */

package com.metrolist.music.playback.automix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Listens to the decoded audio of one track as it plays and keeps what the transition planner needs:
 * how loud the song normally is, how loud the last moments were, and the beat (tempo and where the
 * next beat falls). Pure Kotlin, no Android types, so it is unit-tested on the JVM.
 *
 * Not thread-safe on its own; the audio processor that owns it synchronises access.
 */
class TrackAnalysis(val sampleRate: Int) {
    /** About 11.6 ms per analysis step: fine enough for beat timing, cheap enough for any phone. */
    val hopSize: Int = max(64, (sampleRate * HOP_SECONDS).roundToInt())
    val hopMs: Double = hopSize * 1000.0 / sampleRate

    private val levelCapacity = (LEVEL_HISTORY_SECONDS * 1000 / hopMs).toInt()
    private val levels = FloatArray(levelCapacity)
    private val onsetCapacity = (ONSET_HISTORY_SECONDS * 1000 / hopMs).toInt()
    private val onsets = FloatArray(onsetCapacity)
    private val histogram = IntArray(HISTOGRAM_BINS)

    private var hopsWritten = 0L
    private var histogramCount = 0
    private var hopEnergy = 0.0
    private var hopFill = 0
    private var previousLogEnergy = Double.NaN

    /** Media position (ms) the analysis started from; set after a seek so [processedMs] stays in media time. */
    var baseMs: Long = 0L
        private set

    /** Media time, in ms, of the newest analysed audio. Runs slightly ahead of what is heard. */
    val processedMs: Long
        get() = baseMs + (hopsWritten * hopMs).toLong()

    fun reset(baseMs: Long = 0L) {
        this.baseMs = baseMs
        levels.fill(0f)
        onsets.fill(0f)
        histogram.fill(0)
        hopsWritten = 0
        histogramCount = 0
        hopEnergy = 0.0
        hopFill = 0
        previousLogEnergy = Double.NaN
    }

    /** Adds mono samples in the range -1..1. */
    fun add(mono: FloatArray, count: Int = mono.size) {
        for (i in 0 until count) {
            val s = mono[i].toDouble()
            hopEnergy += s * s
            hopFill++
            if (hopFill == hopSize) closeHop()
        }
    }

    private fun closeHop() {
        val meanSquare = hopEnergy / hopSize
        hopEnergy = 0.0
        hopFill = 0
        val db = (10.0 * log10(meanSquare + 1e-12)).toFloat().coerceAtLeast(SILENCE_FLOOR_DB)

        levels[(hopsWritten % levelCapacity).toInt()] = db
        if (db > AUDIBLE_DB) {
            histogram[(db - SILENCE_FLOOR_DB).toInt().coerceIn(0, HISTOGRAM_BINS - 1)]++
            histogramCount++
        }

        val logEnergy = ln(meanSquare + 1e-10)
        val onset = if (previousLogEnergy.isNaN()) 0.0 else max(0.0, logEnergy - previousLogEnergy)
        previousLogEnergy = logEnergy
        onsets[(hopsWritten % onsetCapacity).toInt()] = onset.toFloat()

        hopsWritten++
    }

    /** Typical loudness of the song so far (60th percentile of audible moments), or null before 5 s of sound. */
    fun bodyLevelDb(): Float? {
        if (histogramCount * hopMs < MIN_BODY_MS) return null
        val target = (histogramCount * BODY_PERCENTILE).toInt()
        var seen = 0
        for (bin in histogram.indices) {
            seen += histogram[bin]
            if (seen > target) return SILENCE_FLOOR_DB + bin + 0.5f
        }
        return null
    }

    /** Energy-averaged level of the last [windowMs] (or less, if less has been heard). */
    fun recentLevelDb(windowMs: Long, skipMs: Long = 0): Float {
        val skip = (skipMs / hopMs).toInt()
        val want = max(1, (windowMs / hopMs).toInt())
        val available = minOf(hopsWritten, levelCapacity.toLong()).toInt() - skip
        if (available <= 0) return SILENCE_FLOOR_DB
        val n = minOf(want, available)
        var sum = 0.0
        for (k in 0 until n) {
            val index = ((hopsWritten - 1 - skip - k) % levelCapacity).toInt()
            sum += Math.pow(10.0, levels[index] / 10.0)
        }
        return (10.0 * log10(sum / n + 1e-12)).toFloat().coerceAtLeast(SILENCE_FLOOR_DB)
    }

    /** True when the last 200 ms carry real sound (used to wait for a song that starts with silence). */
    fun isAudible(): Boolean = hopsWritten > 0 && recentLevelDb(200) > AUDIBLE_DB

    /**
     * Tempo of the last few seconds, from the autocorrelation of the onset envelope.
     * Returns null when there is too little audio or no steady beat.
     */
    fun tempo(): Tempo? {
        val n = minOf(hopsWritten, onsetCapacity.toLong()).toInt()
        if (n * hopMs < MIN_TEMPO_MS) return null

        // oldest -> newest, mean removed
        val env = DoubleArray(n)
        var mean = 0.0
        for (i in 0 until n) {
            env[i] = onsets[((hopsWritten - n + i) % onsetCapacity).toInt()].toDouble()
            mean += env[i]
        }
        mean /= n
        for (i in 0 until n) env[i] -= mean

        val r0 = autocorrelation(env, 0)
        // A steady sound (a held note, a drone) has almost no onsets; its tiny ripples are not a beat.
        if (sqrt(r0) < MIN_ONSET_STRENGTH) return null

        val minLag = max(2, (60_000.0 / MAX_BPM / hopMs).toInt())
        val maxLag = minOf(n / 3, (60_000.0 / MIN_BPM / hopMs).toInt() + 1)
        if (maxLag <= minLag + 1) return null

        val r = DoubleArray(maxLag + 2)
        for (lag in minLag - 1..maxLag + 1) r[lag] = autocorrelation(env, lag) / r0

        var bestLag = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            if (r[lag] < r[lag - 1] || r[lag] < r[lag + 1]) continue // local peaks only
            val bpm = 60_000.0 / (lag * hopMs)
            val prior = exp(-0.5 * sq(log2(bpm / PREFERRED_BPM) / PRIOR_OCTAVES))
            val score = r[lag] * prior
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag < 0) return null
        val confidence = r[bestLag]
        if (confidence < MIN_CONFIDENCE) return null

        // parabolic refinement of the peak position -> fractional lag
        val a = r[bestLag - 1]
        val b = r[bestLag]
        val c = r[bestLag + 1]
        val denom = a - 2 * b + c
        val offset = if (abs(denom) > 1e-12) (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5) else 0.0
        val lag = bestLag + offset
        val beatMs = lag * hopMs

        // phase: how many hops ago the last beat was
        var bestPhase = 0
        var bestPhaseScore = Double.NEGATIVE_INFINITY
        val phases = lag.toInt().coerceAtLeast(1)
        for (phase in 0 until phases) {
            var s = 0.0
            var k = 0
            while (true) {
                val index = (n - 1 - phase - k * lag).roundToInt()
                if (index < 0) break
                // use the raw (non-negative) onset values for phase
                s += env[index] + mean
                k++
            }
            if (s > bestPhaseScore) {
                bestPhaseScore = s
                bestPhase = phase
            }
        }
        val msSinceBeat = bestPhase * hopMs
        val msToNextBeat = (beatMs - msSinceBeat).coerceAtLeast(0.0)
        return Tempo(bpm = 60_000.0 / beatMs, confidence = confidence, beatMs = beatMs, msToNextBeat = msToNextBeat)
    }

    private fun autocorrelation(x: DoubleArray, lag: Int): Double {
        var s = 0.0
        for (i in lag until x.size) s += x[i] * x[i - lag]
        return s / (x.size - lag)
    }

    data class Tempo(
        val bpm: Double,
        /** Normalised autocorrelation at the beat period, 0..1. */
        val confidence: Double,
        val beatMs: Double,
        /** Time from the newest analysed audio to the next expected beat. */
        val msToNextBeat: Double,
    )

    companion object {
        const val SILENCE_FLOOR_DB = -90f
        const val AUDIBLE_DB = -50f
        private const val HOP_SECONDS = 0.0116
        private const val LEVEL_HISTORY_SECONDS = 30
        private const val ONSET_HISTORY_SECONDS = 10
        private const val HISTOGRAM_BINS = 91
        private const val BODY_PERCENTILE = 0.6
        private const val MIN_BODY_MS = 5_000
        private const val MIN_TEMPO_MS = 6_000
        private const val MIN_BPM = 70.0
        private const val MAX_BPM = 180.0
        private const val PREFERRED_BPM = 120.0
        private const val PRIOR_OCTAVES = 1.0
        const val MIN_CONFIDENCE = 0.25
        private const val MIN_ONSET_STRENGTH = 0.01

        private fun sq(x: Double) = x * x
        private fun log2(x: Double) = ln(x) / ln(2.0)

        /** Equal-power crossfade gains for progress 0..1: the overall loudness stays constant through the mix. */
        fun fadeOutGain(progress: Float): Float = kotlin.math.cos(progress.coerceIn(0f, 1f) * PI / 2).toFloat()
        fun fadeInGain(progress: Float): Float = kotlin.math.sin(progress.coerceIn(0f, 1f) * PI / 2).toFloat()

        fun rms(x: FloatArray): Double = sqrt(x.sumOf { it.toDouble() * it } / x.size)
    }
}
