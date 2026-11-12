package com.maheshkatakam.melo.lyrics.ai

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Decodes any audio file Android can play into 16 kHz mono float samples, which is
 * what the speech model expects. Uses the phone's own decoders (MediaCodec).
 */
object AudioDecoder {
    const val TARGET_RATE = 16_000
    private const val MAX_SECONDS = 12 * 60 // very long mixes are cut to keep memory sane

    suspend fun decode(context: Context, uri: Uri, onProgress: (Float) -> Unit): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            error("No audio track")
        }
        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmFloat = false
        val mono = FloatBuilder((minOf(durationUs / 1_000_000.0, MAX_SECONDS.toDouble()) * sampleRate).toInt().coerceIn(1 shl 16, 1 shl 25))
        val maxSamples = MAX_SECONDS.toLong() * 48_000L

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val read = extractor.readSampleData(buffer, 0)
                        if (read < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val time = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, read, time, 0)
                            extractor.advance()
                            if (durationUs > 0) onProgress((time.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.nativeOrder())
                            val ch = channels.coerceAtLeast(1)
                            if (pcmFloat) {
                                val fb = buffer.asFloatBuffer()
                                val frames = fb.remaining() / ch
                                for (f in 0 until frames) {
                                    var sum = 0f
                                    for (c in 0 until ch) sum += fb.get()
                                    mono.add(sum / ch)
                                }
                            } else {
                                val sb = buffer.asShortBuffer()
                                val frames = sb.remaining() / ch
                                for (f in 0 until frames) {
                                    var sum = 0f
                                    for (c in 0 until ch) sum += sb.get() / 32768f
                                    mono.add(sum / ch)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (mono.size >= maxSamples || mono.size >= MAX_SECONDS.toLong() * sampleRate) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            extractor.release()
        }
        onProgress(1f)
        return resample(mono.toArray(), sampleRate, TARGET_RATE)
    }

    /** Band-limited (windowed-sinc) resampler. */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val ratio = fromRate.toDouble() / toRate
        val outLen = floor(input.size / ratio).toInt()
        val out = FloatArray(outLen)
        val cutoff = min(1.0, 1.0 / ratio) * 0.95
        val half = 16
        val phases = 256
        // Precomputed kernel table: phases x (2*half) taps.
        val table = FloatArray(phases * 2 * half)
        for (p in 0 until phases) {
            val frac = p.toDouble() / phases
            for (k in 0 until 2 * half) {
                val x = (k - half + 1) - frac
                val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x * cutoff) / (PI * x * cutoff)
                val window = 0.5 + 0.5 * cos(PI * x / half) // Hann
                table[p * 2 * half + k] = (cutoff * sinc * (if (abs(x) <= half) window else 0.0)).toFloat()
            }
        }
        for (n in 0 until outLen) {
            val center = n * ratio
            val base = floor(center).toInt()
            val frac = center - base
            val phase = (frac * phases).toInt().coerceIn(0, phases - 1)
            val t = phase * 2 * half
            var acc = 0f
            for (k in 0 until 2 * half) {
                val idx = base + k - half + 1
                if (idx in input.indices) acc += input[idx] * table[t + k]
            }
            out[n] = acc
        }
        return out
    }

    private class FloatBuilder(initial: Int) {
        private var data = FloatArray(initial)
        var size = 0
            private set

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf((data.size * 3) / 2 + 1)
            data[size++] = v
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
