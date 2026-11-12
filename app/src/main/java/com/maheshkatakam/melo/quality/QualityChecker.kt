package com.maheshkatakam.melo.quality

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.maheshkatakam.melo.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** What the file says about itself, plus (after a check) what it really sounds like. */
data class SongQuality(
    val format: String,
    val lossless: Boolean,
    val bitrateKbps: Int,
    val sampleRateHz: Int,
    val channels: Int,
    val bitDepth: Int?,
    val real: QualityAnalyzer.Result?,
) {
    /** e.g. "MP3 · 320 kbps · 44.1 kHz" or "FLAC · 24-bit · 96 kHz". */
    val label: String
        get() = buildList {
            add(format)
            if (lossless && bitDepth != null) add("$bitDepth-bit") else if (bitrateKbps > 0) add("$bitrateKbps kbps")
            if (sampleRateHz > 0) add(String.format(Locale.US, "%.1f kHz", sampleRateHz / 1000f).replace(".0 ", " "))
        }.joinToString(" · ")

    /** Plain-language verdict once the sound has been analysed. */
    val verdict: String?
        get() {
            val r = real ?: return null
            val khz = String.format(Locale.US, "%.1f kHz", r.cutoffHz / 1000f)
            val grade = r.grade
            return when {
                lossless && grade != QualityAnalyzer.Grade.FULL ->
                    "Lossless file, but the sound stops at $khz (${grade.label}). It was most likely made from a compressed source, so it isn't true lossless quality."
                lossless -> "Genuine full-range audio up to $khz. This looks like real lossless quality."
                bitrateKbps >= 250 && grade.approxKbps <= 160 ->
                    "Labelled $bitrateKbps kbps, but the sound stops at $khz, ${grade.label}. It was probably converted up from a lower-quality source, which doesn't add quality."
                grade == QualityAnalyzer.Grade.FULL -> "Full-range audio up to $khz. This is as good as a compressed file gets."
                else -> "The sound reaches $khz, ${grade.label}. That matches what the file says."
            }
        }
}

/** Reads a song's format details and, on request, analyses its real quality. */
class QualityChecker(private val context: Context) {

    private val cache = ConcurrentHashMap<Long, SongQuality>()

    fun cached(songId: Long): SongQuality? = cache[songId]

    /** Quick: format facts only (no audio analysis). */
    suspend fun describe(song: Song): SongQuality = cache[song.id] ?: withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, song.uri, null)
            val format = audioFormat(extractor)?.second
            val mime = format?.getString(MediaFormat.KEY_MIME) ?: song.mimeType.orEmpty()
            val rate = format?.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0
            val channels = format?.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 2
            val overall = if (song.durationMs > 0) (song.sizeBytes * 8 / song.durationMs).toInt() else 0
            val declared = (format?.intOrNull(MediaFormat.KEY_BIT_RATE) ?: 0) / 1000
            val lossless = mime.contains("flac") || mime.contains("raw") || mime.contains("wav") || mime.contains("alac")
            val depth = when (format?.intOrNull(MediaFormat.KEY_PCM_ENCODING)) {
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
                AudioFormat.ENCODING_PCM_8BIT -> 8
                else -> format?.intOrNull("bits-per-sample") ?: if (lossless) 16 else null
            }
            SongQuality(
                format = formatName(mime, song.path),
                lossless = lossless,
                bitrateKbps = roundBitrate(if (declared in 8..2000) declared else overall),
                sampleRateHz = rate,
                channels = channels,
                bitDepth = depth,
                real = null,
            )
        } catch (t: Throwable) {
            SongQuality(formatName(song.mimeType.orEmpty(), song.path), false, 0, 0, 2, null, null)
        } finally {
            extractor.release()
        }
    }

    /** Slower (a second or two): listens to the middle of the song to find its real quality. */
    suspend fun analyze(song: Song): SongQuality {
        val base = describe(song)
        if (base.real != null) return base
        val result = withContext(Dispatchers.Default) {
            runCatching {
                val (samples, rate) = decodeMiddle(song, seconds = 16)
                QualityAnalyzer.analyze(samples, rate)
            }.getOrNull()
        }
        val full = base.copy(real = result)
        if (result != null) cache[song.id] = full
        return full
    }

    private fun audioFormat(extractor: MediaExtractor): Pair<Int, MediaFormat>? {
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return i to f
        }
        return null
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun formatName(mime: String, path: String?): String {
        val ext = path?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when {
            mime.contains("flac") -> "FLAC"
            mime.contains("mpeg") || ext == "mp3" -> "MP3"
            mime.contains("opus") -> "Opus"
            mime.contains("vorbis") || ext == "ogg" -> "OGG"
            mime.contains("alac") -> "ALAC"
            mime.contains("mp4a") || mime.contains("aac") || ext == "m4a" -> "AAC"
            mime.contains("raw") || mime.contains("wav") || ext == "wav" -> "WAV"
            ext.isNotEmpty() -> ext.uppercase()
            else -> "Audio"
        }
    }

    /** 317 → 320, 127 → 128, so labels read the way people expect. */
    private fun roundBitrate(kbps: Int): Int {
        val common = intArrayOf(32, 48, 64, 96, 112, 128, 160, 192, 224, 256, 320)
        val nearest = common.minByOrNull { kotlin.math.abs(it - kbps) } ?: return kbps
        return if (kotlin.math.abs(nearest - kbps) <= nearest * 0.06) nearest else kbps
    }

    /** Decodes [seconds] of mono audio from the middle of the song at its own sample rate. */
    private suspend fun decodeMiddle(song: Song, seconds: Int): Pair<FloatArray, Int> {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, song.uri, null)
        val (track, format) = audioFormat(extractor) ?: run {
            extractor.release()
            error("No audio track")
        }
        extractor.selectTrack(track)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else song.durationMs * 1000
        val startUs = (durationUs / 2 - seconds * 500_000L).coerceAtLeast(0)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmFloat = false
        var out = FloatArray(rate * seconds)
        var size = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var done = false
        try {
            while (!done) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val read = extractor.readSampleData(buffer, 0)
                        if (read < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    if (newRate != rate) {
                        rate = newRate
                        out = FloatArray(rate * seconds)
                        size = 0
                    }
                } else if (outIndex >= 0) {
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
                                if (size < out.size) out[size++] = sum / ch
                            }
                        } else {
                            val sb = buffer.asShortBuffer()
                            val frames = sb.remaining() / ch
                            for (f in 0 until frames) {
                                var sum = 0f
                                for (c in 0 until ch) sum += sb.get() / 32768f
                                if (size < out.size) out[size++] = sum / ch
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || size >= out.size) done = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            extractor.release()
        }
        return out.copyOf(size) to rate
    }
}
