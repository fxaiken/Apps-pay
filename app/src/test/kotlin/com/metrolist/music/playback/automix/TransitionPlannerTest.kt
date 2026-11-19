package com.metrolist.music.playback.automix

import com.metrolist.music.playback.automix.TransitionPlanner.Plan
import com.metrolist.music.playback.automix.TransitionPlanner.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionPlannerTest {
    private fun early(
        remaining: Long,
        body: Float? = -12f,
        last600: Float = -12f,
        last1500: Float = -12f,
        last750: Float = -12f,
        prev750: Float = -12f,
    ) = TransitionPlanner.detectEarlyEnd(remaining, body, last600, last1500, last750, prev750, maxFadeMs = 8_000)

    @Test
    fun `keeps playing while the song is at full level`() {
        assertNull(early(10_000))
    }

    @Test
    fun `starts the next song as soon as the outgoing one goes silent`() {
        val plan = early(9_000, last600 = -70f, last1500 = -60f, last750 = -70f, prev750 = -50f)!!
        assertEquals(Reason.SILENCE, plan.reason)
        assertEquals(1_200, plan.fadeMs)
        assertEquals(0, plan.delayMs)
    }

    @Test
    fun `a short silence near the very end uses the time that is left`() {
        assertEquals(700, early(700, last600 = -80f)!!.fadeMs)
    }

    @Test
    fun `detects a fade-out and mixes over what is left of it`() {
        val plan = early(6_000, last600 = -26f, last1500 = -27f, last750 = -29f, prev750 = -25f)!!
        assertEquals(Reason.FADE_OUT, plan.reason)
        assertEquals(6_000, plan.fadeMs)
    }

    @Test
    fun `fade length is capped by the user setting`() {
        assertEquals(8_000, early(14_000, last600 = -26f, last1500 = -27f, last750 = -29f, prev750 = -25f)!!.fadeMs)
    }

    @Test
    fun `a quiet passage that grows louder again is not an ending`() {
        assertNull(early(6_000, last600 = -26f, last1500 = -27f, last750 = -22f, prev750 = -30f))
    }

    @Test
    fun `a quiet break long before the end is ignored`() {
        assertNull(early(20_000, last600 = -26f, last1500 = -27f, last750 = -29f, prev750 = -25f))
        assertNull(early(40_000, last600 = -80f))
    }

    @Test
    fun `does nothing without enough of the song heard, or with no time left`() {
        assertNull(early(6_000, body = null, last600 = -80f))
        assertNull(early(300, last600 = -80f))
    }

    @Test
    fun `quiet songs are judged against their own level`() {
        // a soft acoustic song at -35 dB: -60 dB is silence for it, -40 dB is not
        assertEquals(Reason.SILENCE, early(5_000, body = -35f, last600 = -72f)!!.reason)
        assertNull(early(5_000, body = -35f, last600 = -40f, last1500 = -40f, last750 = -40f, prev750 = -40f))
    }

    private val tempo120 = TrackAnalysis.Tempo(bpm = 120.0, confidence = 0.7, beatMs = 500.0, msToNextBeat = 180.0)

    @Test
    fun `mix starts on the next beat and lasts whole beats`() {
        val plan = TransitionPlanner.alignToBeat(Plan(0, 5_000, Reason.SCHEDULED), tempo120, 180.0, remainingMs = 5_200)
        assertEquals(180, plan.delayMs)
        assertEquals(4_000, plan.fadeMs) // 8 beats, the closest whole-beat length that fits
        assertEquals(120.0, plan.bpm!!, 0.01)
    }

    @Test
    fun `chooses the beat count closest to the wanted length`() {
        val plan = TransitionPlanner.alignToBeat(Plan(0, 7_000, Reason.FADE_OUT), tempo120, 100.0, remainingMs = 20_000)
        assertEquals(8_000, plan.fadeMs) // 16 beats
    }

    @Test
    fun `no beat alignment with an unsure tempo, a silence, or no room`() {
        val base = Plan(0, 5_000, Reason.SCHEDULED)
        assertEquals(base, TransitionPlanner.alignToBeat(base, tempo120.copy(confidence = 0.2), 180.0, 6_000))
        assertEquals(base, TransitionPlanner.alignToBeat(base, null, 180.0, 6_000))
        assertEquals(base, TransitionPlanner.alignToBeat(base, tempo120, 180.0, 2_100))
        val silence = Plan(0, 1_200, Reason.SILENCE)
        assertEquals(silence, TransitionPlanner.alignToBeat(silence, tempo120, 180.0, 6_000))
        assertEquals(base, TransitionPlanner.alignToBeat(base, tempo120, 900.0, 6_000))
    }

    @Test
    fun `progress waits for a silent intro, but not forever`() {
        assertEquals(0.1f, TransitionPlanner.nextProgress(0.1f, 20, 4_000, incomingAudible = false, heldMs = 500), 1e-6f)
        assertEquals(0.105f, TransitionPlanner.nextProgress(0.1f, 20, 4_000, incomingAudible = true, heldMs = 500), 1e-6f)
        assertEquals(0.105f, TransitionPlanner.nextProgress(0.1f, 20, 4_000, incomingAudible = false, heldMs = 2_000), 1e-6f)
        assertEquals(1f, TransitionPlanner.nextProgress(0.999f, 20, 4_000, incomingAudible = true, heldMs = 0), 1e-6f)
    }

    @Test
    fun `soft clip leaves normal samples alone and never exceeds full scale`() {
        assertEquals(0.5, TransitionPlanner.softClip(0.5), 0.0)
        assertEquals(-0.8, TransitionPlanner.softClip(-0.8), 0.0)
        var previous = 0.0
        var x = 0.0
        while (x < 4.0) {
            val y = TransitionPlanner.softClip(x)
            assertTrue("monotonic at $x", y >= previous)
            assertTrue("bounded at $x", y <= 1.0)
            assertEquals(-y, TransitionPlanner.softClip(-x), 1e-12)
            previous = y
            x += 0.01
        }
    }
}
