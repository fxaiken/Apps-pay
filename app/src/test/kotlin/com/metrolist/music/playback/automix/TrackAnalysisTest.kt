package com.metrolist.music.playback.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class TrackAnalysisTest {
    private val sr = 44_100

    private fun sine(seconds: Double, amplitude: Double, freq: Double = 440.0): FloatArray =
        FloatArray((seconds * sr).toInt()) { (amplitude * sin(2 * PI * freq * it / sr)).toFloat() }

    /** A drum-like click track: short decaying bursts every beat, over quiet noise. */
    private fun clicks(seconds: Double, bpm: Double, firstBeatAt: Double = 0.0, seed: Int = 1): FloatArray {
        val rnd = Random(seed)
        val beat = 60.0 / bpm
        return FloatArray((seconds * sr).toInt()) { i ->
            val t = i / sr.toDouble()
            val since = ((t - firstBeatAt) % beat + beat) % beat
            val hit = if (t >= firstBeatAt && since < 0.03) (0.8 * (1 - since / 0.03) * (rnd.nextDouble() * 2 - 1)) else 0.0
            (hit + 0.01 * (rnd.nextDouble() * 2 - 1)).toFloat()
        }
    }

    @Test
    fun `body level of a steady tone is its RMS`() {
        val a = TrackAnalysis(sr)
        a.add(sine(8.0, 0.5))
        // RMS of a sine with amplitude 0.5 is 0.354 -> -9.0 dBFS
        assertEquals(-9.0f, a.bodyLevelDb()!!, 1.0f)
        assertEquals(-9.0f, a.recentLevelDb(500), 0.5f)
        assertTrue(a.isAudible())
    }

    @Test
    fun `no body level before five seconds of sound`() {
        val a = TrackAnalysis(sr)
        a.add(sine(3.0, 0.5))
        assertNull(a.bodyLevelDb())
    }

    @Test
    fun `silence is not audible and does not count towards the body level`() {
        val a = TrackAnalysis(sr)
        a.add(sine(6.0, 0.5))
        a.add(FloatArray(sr * 2))
        assertFalse(a.isAudible())
        assertEquals(-9.0f, a.bodyLevelDb()!!, 1.0f)
        assertTrue(a.recentLevelDb(600) < -80f)
    }

    @Test
    fun `recent level can look back past the newest audio`() {
        val a = TrackAnalysis(sr)
        a.add(sine(2.0, 0.5))
        a.add(sine(1.0, 0.05))
        assertEquals(-29.0f, a.recentLevelDb(750), 1f)
        assertEquals(-9.0f, a.recentLevelDb(750, skipMs = 1_200), 1f)
    }

    @Test
    fun `finds the tempo of a 120 bpm beat`() {
        val a = TrackAnalysis(sr)
        a.add(clicks(10.0, 120.0))
        val t = a.tempo()
        assertNotNull(t)
        assertEquals(120.0, t!!.bpm, 2.0)
        assertTrue("confidence ${t.confidence}", t.confidence > 0.35)
    }

    @Test
    fun `finds other tempos too`() {
        for (bpm in listOf(90.0, 100.0, 128.0, 140.0, 160.0)) {
            val a = TrackAnalysis(sr)
            a.add(clicks(10.0, bpm, seed = bpm.toInt()))
            val t = a.tempo()
            assertNotNull("no tempo for $bpm", t)
            assertEquals("bpm for $bpm", bpm, t!!.bpm, bpm * 0.025)
        }
    }

    @Test
    fun `predicts when the next beat falls`() {
        // beats every 0.5 s starting at 0.2 s; audio stops at 10.0 s -> last beat at 9.7 s, next at 10.2 s
        val a = TrackAnalysis(sr)
        a.add(clicks(10.0, 120.0, firstBeatAt = 0.2))
        val t = a.tempo()!!
        assertEquals(200.0, t.msToNextBeat, 40.0)
    }

    @Test
    fun `a steady tone has no tempo`() {
        val a = TrackAnalysis(sr)
        a.add(sine(10.0, 0.5))
        assertNull(a.tempo())
    }

    @Test
    fun `processed time follows the audio and the base set after a seek`() {
        val a = TrackAnalysis(sr)
        a.add(sine(2.0, 0.5))
        assertEquals(2_000.0, a.processedMs.toDouble(), 15.0)
        a.reset(baseMs = 60_000)
        a.add(sine(1.0, 0.5))
        assertEquals(61_000.0, a.processedMs.toDouble(), 15.0)
        assertNull(a.bodyLevelDb())
    }

    @Test
    fun `equal-power gains keep the combined power constant`() {
        for (i in 0..20) {
            val p = i / 20f
            val out = TrackAnalysis.fadeOutGain(p)
            val inn = TrackAnalysis.fadeInGain(p)
            assertEquals(1f, out * out + inn * inn, 1e-4f)
        }
        assertEquals(1f, TrackAnalysis.fadeOutGain(0f), 1e-6f)
        assertEquals(0f, TrackAnalysis.fadeInGain(0f), 1e-6f)
        assertTrue(abs(TrackAnalysis.fadeOutGain(1f)) < 1e-6f)
    }
}
