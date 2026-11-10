package com.maheshkatakam.melo.discover

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.maheshkatakam.melo.lyrics.ai.AudioDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Records the microphone as 16 kHz mono 16-bit audio (what the fingerprint needs) into a
 * growing buffer that other coroutines can snapshot while recording continues.
 */
class MicRecorder {
    private val lock = Any()
    private var buffer = ShortArray(ShazamSignature.SAMPLE_RATE * (ShazamSignature.WINDOW_SECONDS + 1))
    private var size = 0
    private var rate = ShazamSignature.SAMPLE_RATE

    @Volatile
    var finished = false
        private set

    /** Seconds of audio captured so far. */
    val seconds: Float get() = synchronized(lock) { size.toFloat() / rate }

    /** Everything recorded so far, as 16 kHz samples. */
    fun snapshot(): ShortArray {
        val copy = synchronized(lock) { buffer.copyOf(size) }
        if (rate == ShazamSignature.SAMPLE_RATE) return copy
        val floats = FloatArray(copy.size) { copy[it] / 32768f }
        val resampled = AudioDecoder.resample(floats, rate, ShazamSignature.SAMPLE_RATE)
        return ShortArray(resampled.size) { (resampled[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
    }

    /** Records until [seconds] of audio are captured or the coroutine is cancelled. */
    @SuppressLint("MissingPermission") // checked by the UI before listening starts
    suspend fun record(seconds: Int) = withContext(Dispatchers.IO) {
        val record = open() ?: throw IllegalStateException("Couldn't use the microphone. Is another app recording?")
        try {
            synchronized(lock) {
                buffer = ShortArray(rate * (seconds + 1))
                size = 0
            }
            record.startRecording()
            val chunk = ShortArray(rate / 10)
            val target = rate * seconds
            while (isActive && synchronized(lock) { size } < target) {
                val n = record.read(chunk, 0, chunk.size)
                if (n < 0) throw IllegalStateException("The microphone stopped working (error $n).")
                if (n == 0) continue
                synchronized(lock) {
                    val room = minOf(n, buffer.size - size)
                    System.arraycopy(chunk, 0, buffer, size, room)
                    size += room
                }
            }
        } finally {
            finished = true
            runCatching { record.stop() }
            record.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord? {
        // VOICE_RECOGNITION skips the noise suppression and auto gain that smear music.
        val sources = intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        val rates = intArrayOf(ShazamSignature.SAMPLE_RATE, 44_100, 48_000)
        for (r in rates) {
            val min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            for (source in sources) {
                val record = runCatching {
                    AudioRecord(source, r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, r))
                }.getOrNull() ?: continue
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    rate = r
                    return record
                }
                record.release()
            }
        }
        return null
    }
}
