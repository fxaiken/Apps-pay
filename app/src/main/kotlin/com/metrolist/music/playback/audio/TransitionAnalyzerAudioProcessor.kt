/**
 * FRAME MUSIC — smart transitions (GPL-3.0, as the rest of the app)
 */

package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.metrolist.music.playback.automix.TrackAnalysis
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pass-through processor that lets [TrackAnalysis] hear exactly what the listener hears (after
 * normalisation and EQ). The audio is not changed. Reads come from the main thread, so every access
 * to the analysis goes through [lock].
 */
@UnstableApi
class TransitionAnalyzerAudioProcessor : BaseAudioProcessor() {
    private val lock = Any()
    private var analysis: TrackAnalysis? = null
    private var channelCount = 0
    private var mono = FloatArray(0)

    @Volatile
    var enabled = true

    data class Snapshot(
        val bodyDb: Float?,
        val last600Db: Float,
        val last1500Db: Float,
        val last750Db: Float,
        val previous750Db: Float,
        val processedMs: Long,
        val tempo: TrackAnalysis.Tempo?,
    )

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        channelCount = inputAudioFormat.channelCount
        synchronized(lock) {
            if (analysis?.sampleRate != inputAudioFormat.sampleRate) {
                analysis = TrackAnalysis(inputAudioFormat.sampleRate)
            }
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        if (enabled && channelCount > 0) analyse(inputBuffer)
        replaceOutputBuffer(inputBuffer.remaining()).apply {
            put(inputBuffer)
            flip()
        }
    }

    private fun analyse(buffer: ByteBuffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val base = buffer.position()
        val frames = buffer.remaining() / 2 / channelCount
        if (mono.size < frames) mono = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channelCount) sum += buffer.getShort(base + (f * channelCount + c) * 2)
            mono[f] = sum / (channelCount * 32768f)
        }
        synchronized(lock) { analysis?.add(mono, frames) }
    }

    /** Starts listening afresh, for a new song or after a seek to [positionMs]. */
    fun resetFor(positionMs: Long) {
        synchronized(lock) { analysis?.reset(positionMs) }
    }

    fun isAudible(): Boolean = synchronized(lock) { analysis?.isAudible() ?: true }

    fun snapshot(withTempo: Boolean): Snapshot? =
        synchronized(lock) {
            val a = analysis ?: return null
            Snapshot(
                bodyDb = a.bodyLevelDb(),
                last600Db = a.recentLevelDb(600),
                last1500Db = a.recentLevelDb(1_500),
                last750Db = a.recentLevelDb(750),
                previous750Db = a.recentLevelDb(750, skipMs = 750),
                processedMs = a.processedMs,
                tempo = if (withTempo) a.tempo() else null,
            )
        }

    override fun onReset() {
        channelCount = 0
        synchronized(lock) { analysis = null }
    }
}
