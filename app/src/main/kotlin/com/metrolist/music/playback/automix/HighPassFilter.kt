/**
 * FRAME MUSIC — DJ mode (GPL-3.0, as the rest of the app)
 */

package com.metrolist.music.playback.automix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Second-order Butterworth high-pass (RBJ cookbook), one state per channel, interleaved samples.
 * The cutoff moves smoothly towards its target so sweeping it does not click; at 0 Hz the audio
 * passes untouched.
 */
class HighPassFilter(private val sampleRate: Int, private val channels: Int) {
    private val x1 = DoubleArray(channels)
    private val x2 = DoubleArray(channels)
    private val y1 = DoubleArray(channels)
    private val y2 = DoubleArray(channels)
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    var targetHz: Float = 0f
    var currentHz: Float = 0f
        private set

    /** Filters [frames] interleaved frames of [data] in place. */
    fun process(data: FloatArray, frames: Int) {
        // glide the cutoff a little per block: about 30 ms to settle
        val step = 1f - kotlin.math.exp(-frames / (sampleRate * GLIDE_SECONDS)).toFloat()
        currentHz += (targetHz - currentHz) * step
        if (kotlin.math.abs(targetHz - currentHz) < 0.5f) currentHz = targetHz

        if (currentHz < MIN_HZ) {
            // keep the state warm so turning the filter back on does not pop
            for (f in 0 until frames) for (c in 0 until channels) {
                val x = data[f * channels + c].toDouble()
                x2[c] = x1[c]; x1[c] = x; y2[c] = y1[c]; y1[c] = x
            }
            return
        }
        coefficients(currentHz.toDouble())
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val i = f * channels + c
                val x = data[i].toDouble()
                val y = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c]
                x2[c] = x1[c]; x1[c] = x
                y2[c] = y1[c]; y1[c] = y
                data[i] = y.toFloat()
            }
        }
    }

    fun reset() {
        x1.fill(0.0); x2.fill(0.0); y1.fill(0.0); y2.fill(0.0)
        currentHz = targetHz
    }

    private fun coefficients(hz: Double) {
        val w = 2 * PI * hz / sampleRate
        val alpha = sin(w) / (2 * (1 / sqrt(2.0)))
        val cosW = cos(w)
        val a0 = 1 + alpha
        b0 = (1 + cosW) / 2 / a0
        b1 = -(1 + cosW) / a0
        b2 = (1 + cosW) / 2 / a0
        a1 = -2 * cosW / a0
        a2 = (1 - alpha) / a0
    }

    companion object {
        private const val MIN_HZ = 20f
        private const val GLIDE_SECONDS = 0.03f
    }
}
