package com.metrolist.music.playback.automix

import com.metrolist.music.playback.automix.DjPlanner.BeatGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class DjPlannerTest {
    @Test
    fun `tempo ratio matches close tempos`() {
        assertEquals(124.0 / 120.0, DjPlanner.tempoRatio(124.0, 120.0)!!, 1e-9)
        assertEquals(1.0, DjPlanner.tempoRatio(128.0, 128.0)!!, 1e-9)
    }

    @Test
    fun `tempo ratio understands half and double time`() {
        assertEquals(1.0, DjPlanner.tempoRatio(140.0, 70.0)!!, 1e-9)
        assertEquals(1.0, DjPlanner.tempoRatio(85.0, 170.0)!!, 1e-9)
    }

    @Test
    fun `tempos too far apart are not stretched`() {
        assertNull(DjPlanner.tempoRatio(128.0, 100.0))
        assertNull(DjPlanner.tempoRatio(0.0, 100.0))
    }

    @Test
    fun `grid from the analysis finds the first beat of the song`() {
        // processed up to 10 000 ms, next beat 200 ms later at 10 200 ms; 500 ms beats -> first beat at 200 ms
        val g = DjPlanner.gridFrom(10_000, TrackAnalysis.Tempo(120.0, 0.8, 500.0, 200.0))
        assertEquals(200.0, g.firstBeatMs, 1e-9)
        assertEquals(0.0, g.phaseAt(10_200.0), 1e-9)
        assertEquals(0.5, g.phaseAt(10_450.0), 1e-9)
        assertEquals(10_700.0, g.nextBeatAt(10_250.0), 1e-9)
        assertEquals(10_200.0, g.nextBeatAt(10_200.0), 1e-9)
    }

    @Test
    fun `phase error is the wrapped beat difference`() {
        val out = BeatGrid(0.0, 500.0)
        val inn = BeatGrid(100.0, 500.0)
        assertEquals(0.0, DjPlanner.phaseError(out, 1_000.0, inn, 1_100.0), 1e-9)
        assertEquals(0.2, DjPlanner.phaseError(out, 1_000.0, inn, 1_200.0), 1e-9)
        assertEquals(-0.2, DjPlanner.phaseError(out, 1_000.0, inn, 1_000.0), 1e-9)
        assertEquals(-0.4, DjPlanner.phaseError(out, 1_000.0, inn, 1_400.0), 1e-9)
    }

    @Test
    fun `seeking by the correction removes the phase error`() {
        val out = BeatGrid(0.0, 500.0)
        val inn = BeatGrid(100.0, 480.0)
        val e = DjPlanner.phaseError(out, 3_100.0, inn, 2_000.0)
        val fixed = 2_000.0 + DjPlanner.correctionMs(e, inn)
        assertEquals(0.0, DjPlanner.phaseError(out, 3_100.0, inn, fixed), 0.01)
    }

    @Test
    fun `the blend is sixteen beats and ends with the song`() {
        assertEquals(172_000, DjPlanner.mixStartMs(180_000, 500.0))
        assertEquals(0, DjPlanner.mixStartMs(5_000, 500.0))
    }

    @Test
    fun `levels and bass swap follow the DJ pattern`() {
        assertEquals(0f, DjPlanner.incomingGain(0f), 1e-6f)
        assertEquals(1f, DjPlanner.incomingGain(0.5f), 1e-6f)
        assertEquals(1f, DjPlanner.outgoingGain(0.5f), 1e-6f)
        assertTrue(DjPlanner.outgoingGain(1f) < 1e-6f)
        assertEquals(DjPlanner.BASS_CUT_HZ, DjPlanner.incomingCutoffHz(0.2f), 0f)
        assertEquals(0f, DjPlanner.outgoingCutoffHz(0.2f), 0f)
        assertEquals(0f, DjPlanner.incomingCutoffHz(0.7f), 0f)
        assertEquals(DjPlanner.BASS_CUT_HZ, DjPlanner.outgoingCutoffHz(0.7f), 0f)
        // never both full bass at once
        var p = 0f
        while (p <= 1f) {
            assertTrue(DjPlanner.incomingCutoffHz(p) > 0f || DjPlanner.outgoingCutoffHz(p) > 0f || p == 0.5f)
            p += 0.01f
        }
    }

    @Test
    fun `tempo glides back after the blend`() {
        assertEquals(1.04, DjPlanner.speedAfterMix(0, 1.04), 1e-9)
        assertEquals(1.02, DjPlanner.speedAfterMix(6_000, 1.04), 1e-9)
        assertEquals(1.0, DjPlanner.speedAfterMix(20_000, 1.04), 1e-9)
    }
}

class HighPassFilterTest {
    private val sr = 44_100

    private fun stereoTone(freq: Double, seconds: Double): FloatArray {
        val frames = (sr * seconds).toInt()
        return FloatArray(frames * 2) { i -> (0.5 * sin(2 * PI * freq * (i / 2) / sr)).toFloat() }
    }

    private fun rms(x: FloatArray, from: Int): Double {
        var s = 0.0
        for (i in from until x.size) s += x[i] * x[i].toDouble()
        return sqrt(s / (x.size - from))
    }

    private fun run(freq: Double, cutoff: Float): Double {
        val f = HighPassFilter(sr, 2)
        f.targetHz = cutoff
        f.reset()
        val x = stereoTone(freq, 1.0)
        var off = 0
        while (off < x.size / 2) {
            val n = minOf(1024, x.size / 2 - off)
            val block = x.copyOfRange(off * 2, (off + n) * 2)
            f.process(block, n)
            block.copyInto(x, off * 2)
            off += n
        }
        return rms(x, x.size / 2) / (0.5 / sqrt(2.0))
    }

    @Test
    fun `removes bass and keeps the rest`() {
        assertTrue("60 Hz kept ${run(60.0, 300f)}", run(60.0, 300f) < 0.06)
        assertTrue("2 kHz lost ${run(2_000.0, 300f)}", run(2_000.0, 300f) > 0.97)
        assertEquals(0.707, run(300.0, 300f), 0.03)
    }

    @Test
    fun `at zero it does nothing`() {
        assertEquals(1.0, run(60.0, 0f), 1e-6)
    }
}
