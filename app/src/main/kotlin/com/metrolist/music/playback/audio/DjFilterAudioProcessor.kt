/**
 * FRAME MUSIC — DJ mode (GPL-3.0, as the rest of the app)
 */

package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.metrolist.music.playback.automix.HighPassFilter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bass cut used during DJ blends. Untouched pass-through unless [arm] has been called; the cutoff is
 * set from the main thread and picked up by the audio thread on the next buffer.
 */
@UnstableApi
class DjFilterAudioProcessor : BaseAudioProcessor() {
    private var sampleRate = 0
    private var channelCount = 0
    private var filter: HighPassFilter? = null
    private var scratch = FloatArray(0)

    @Volatile private var armed = false
    @Volatile private var targetHz = 0f
    @Volatile private var resetPending = false

    /** Starts filtering at [hz]; with [immediate] the cutoff jumps there (for a song that is still silent). */
    fun arm(hz: Float, immediate: Boolean) {
        targetHz = hz
        resetPending = immediate
        armed = true
    }

    fun setCutoff(hz: Float) {
        targetHz = hz
    }

    fun disarm() {
        armed = false
        targetHz = 0f
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        filter = HighPassFilter(sampleRate, channelCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val f = filter
        if (!armed || f == null || channelCount == 0) {
            replaceOutputBuffer(inputBuffer.remaining()).apply {
                put(inputBuffer)
                flip()
            }
            return
        }
        f.targetHz = targetHz
        if (resetPending) {
            f.reset()
            resetPending = false
        }

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val samples = inputBuffer.remaining() / 2
        if (scratch.size < samples) scratch = FloatArray(samples)
        for (i in 0 until samples) scratch[i] = inputBuffer.getShort() / 32768f
        f.process(scratch, samples / channelCount)

        val out = replaceOutputBuffer(samples * 2)
        out.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            out.putShort((scratch[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
        }
        out.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        filter?.reset()
    }

    override fun onReset() {
        sampleRate = 0
        channelCount = 0
        filter = null
    }
}
