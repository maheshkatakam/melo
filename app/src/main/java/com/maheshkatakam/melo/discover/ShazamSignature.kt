package com.maheshkatakam.melo.discover

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Shazam-compatible audio fingerprint ("signature").
 *
 * A Kotlin port of the fingerprinting in shazamio-core (MIT licence, Copyright (c) 2024
 * dotX12), https://github.com/shazamio/shazamio-core, `src/fingerprinting/algorithm.rs`
 * and `signature_format.rs`. Only the signature format and the algorithm's parameters
 * are needed for compatibility.
 *
 * Input is 16 kHz mono 16-bit audio. The result is a few kilobytes and contains no audio.
 */
object ShazamSignature {
    const val SAMPLE_RATE = 16_000
    /** Shazam clients send a 12-second window; 15 s or more gets no matches. */
    const val WINDOW_SECONDS = 12

    private const val FRAME_LENGTH = 2048
    private const val HOP_LENGTH = 128
    private const val BIN_COUNT = FRAME_LENGTH / 2 + 1
    private const val HISTORY_LENGTH = 256
    private const val PEAK_LAG = 45
    private const val REFERENCE_LAG = 48
    private val FREQUENCY_NEIGHBOUR_DISTANCES = intArrayOf(2, 3, 6, 9)
    private val TIME_NEIGHBOUR_DISTANCES = intArrayOf(4, 7, 14, 21, 28, 35, 42)
    private val SPREAD_BACK_LAGS = intArrayOf(1, 3, 6)
    private const val FIRST_PEAK_BIN = 10
    private const val LAST_PEAK_BIN = 1014
    private const val MIN_PEAK_POWER = 1f / 64f

    private const val DATA_URI_PREFIX = "data:audio/vnd.shazam.sig;base64,"
    private const val HEADER_MAGIC = 0xcafe2580L
    private const val HEADER_SECOND_MAGIC = 0x94119c00L
    private const val HEADER_FIXED_VALUE = 0x007c0000L
    private const val SAMPLE_RATE_ID_16K = 3L
    private const val BODY_MAGIC = 0x40000000L
    private const val BAND_MARKER_BASE = 0x60030040L
    private const val FRAME_ESCAPE = 0xff

    class Peak(val frame: Int, val magnitude: Int, val refinedBin: Int)

    class Fingerprint(val sampleCount: Int, val peaksByBand: Map<Int, List<Peak>>) {
        /** Truncated f32 maths, as the reference reports it. */
        val durationMs: Int get() = (sampleCount.toFloat() / SAMPLE_RATE.toFloat() * 1000f).toInt()

        val peakCount: Int get() = peaksByBand.values.sumOf { it.size }

        fun encode(): ByteArray {
            val body = ByteArrayOutputStream()
            for (band in peaksByBand.keys.sorted()) {
                val encoded = encodePeaks(peaksByBand.getValue(band))
                body.writeU32(BAND_MARKER_BASE + band)
                body.writeU32(encoded.size.toLong())
                body.write(encoded)
                while (body.size() % 4 != 0) body.write(0)
            }
            val bodyBytes = body.toByteArray()
            val bodyLength = (bodyBytes.size + 8).toLong()
            val paddedSampleCount = sampleCount.toLong() + SAMPLE_RATE * 24 / 100

            val out = ByteArrayOutputStream(48 + bodyBytes.size + 8)
            val words = longArrayOf(
                HEADER_MAGIC,
                0,
                bodyLength,
                HEADER_SECOND_MAGIC,
                0,
                0,
                0,
                SAMPLE_RATE_ID_16K shl 27,
                0,
                0,
                paddedSampleCount,
                HEADER_FIXED_VALUE,
                BODY_MAGIC,
                bodyLength,
            )
            for (w in words) out.writeU32(w)
            out.write(bodyBytes)
            val signature = out.toByteArray()

            val crc = CRC32().apply { update(signature, 8, signature.size - 8) }.value
            signature[4] = (crc and 0xff).toByte()
            signature[5] = ((crc shr 8) and 0xff).toByte()
            signature[6] = ((crc shr 16) and 0xff).toByte()
            signature[7] = ((crc shr 24) and 0xff).toByte()
            return signature
        }

        fun toDataUri(): String = DATA_URI_PREFIX + Base64.getEncoder().encodeToString(encode())
    }

    /** Fingerprints [samples] from [from] (inclusive) to [to] (exclusive). */
    fun fingerprint(samples: ShortArray, from: Int = 0, to: Int = samples.size): Fingerprint {
        val spectrogram = Spectrogram()
        var offset = from
        while (offset + HOP_LENGTH <= to) {
            spectrogram.pushHop(samples, offset)
            offset += HOP_LENGTH
        }
        return Fingerprint(to - from, spectrogram.peaks)
    }

    /** The middle [WINDOW_SECONDS] of a longer recording, where recognition odds are best. */
    fun fingerprintMiddle(samples: ShortArray): Fingerprint {
        val window = WINDOW_SECONDS * SAMPLE_RATE
        if (samples.size <= window) return fingerprint(samples)
        val start = samples.size / 2 - window / 2
        return fingerprint(samples, start, start + window)
    }

    private fun encodePeaks(peaks: List<Peak>): ByteArray {
        val out = ByteArrayOutputStream(peaks.size * 5)
        var previousFrame = 0
        for (peak in peaks) {
            var delta = peak.frame - previousFrame
            if (delta >= FRAME_ESCAPE) {
                out.write(FRAME_ESCAPE)
                out.writeU32(peak.frame.toLong())
                delta = 0
            }
            out.write(delta)
            out.writeU16(peak.magnitude)
            out.writeU16(peak.refinedBin)
            previousFrame = peak.frame
        }
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeU32(v: Long) {
        write((v and 0xff).toInt())
        write(((v shr 8) and 0xff).toInt())
        write(((v shr 16) and 0xff).toInt())
        write(((v shr 24) and 0xff).toInt())
    }

    private fun ByteArrayOutputStream.writeU16(v: Int) {
        write(v and 0xff)
        write((v shr 8) and 0xff)
    }

    private fun bandOf(frequencyHz: Int): Int = when (frequencyHz) {
        in 250..519 -> 0
        in 520..1449 -> 1
        in 1450..3499 -> 2
        in 3500..5500 -> 3
        else -> -1
    }

    private fun logMagnitude(power: Float): Float = max(ln(power), 1f / 64f) * 1477.3f + 6144f

    /** Parabola vertex through the peak and its neighbours, in 1/64 of a bin (u16). */
    private fun refineBin(bin: Int, below: Float, peak: Float, above: Float): Int {
        val curvature = peak * 2f - below - above
        val vertexOffset = (above - below) * 32f / curvature
        return (bin * 64 + vertexOffset.toInt()) and 0xffff
    }

    private class Spectrogram {
        private val window = FloatArray(FRAME_LENGTH) { i ->
            val s = sin(PI * (i + 1).toDouble() / (FRAME_LENGTH + 1).toDouble())
            (s * s).toFloat()
        }
        private val samples = ShortArray(FRAME_LENGTH)
        private val powers = Array(HISTORY_LENGTH) { FloatArray(BIN_COUNT) }
        private val spreads = Array(HISTORY_LENGTH) { FloatArray(BIN_COUNT) }
        private var count = 0
        private val fft = RealFft(FRAME_LENGTH)
        private val windowed = DoubleArray(FRAME_LENGTH)
        val peaks = HashMap<Int, MutableList<Peak>>()

        private fun slot(lag: Int) = (count + HISTORY_LENGTH - 1 - lag) % HISTORY_LENGTH

        fun pushHop(source: ShortArray, offset: Int) {
            System.arraycopy(samples, HOP_LENGTH, samples, 0, FRAME_LENGTH - HOP_LENGTH)
            System.arraycopy(source, offset, samples, FRAME_LENGTH - HOP_LENGTH, HOP_LENGTH)

            val power = powerSpectrum()
            val spread = power.copyOf()
            for (bin in 0 until BIN_COUNT - 2) {
                spread[bin] = max(max(power[bin], power[bin + 1]), power[bin + 2])
            }

            count++
            for (lag in SPREAD_BACK_LAGS) {
                val earlier = spreads[slot(lag)]
                for (i in 0 until BIN_COUNT) if (spread[i] > earlier[i]) earlier[i] = spread[i]
            }
            val now = slot(0)
            powers[now] = power
            spreads[now] = spread

            if (count > PEAK_LAG) pickPeaks()
        }

        private fun powerSpectrum(): FloatArray {
            for (i in 0 until FRAME_LENGTH) windowed[i] = (samples[i].toFloat() * window[i]).toDouble()
            val (re, im) = fft.forward(windowed)
            return FloatArray(BIN_COUNT) { k ->
                val p = ((re[k] * re[k] + im[k] * im[k]) / 131072.0).toFloat()
                if (p < 1e-10f) 1e-10f else p
            }
        }

        private fun pickPeaks() {
            val frameIndex = count - 1 - PEAK_LAG
            val candidate = powers[slot(PEAK_LAG)]
            val reference = spreads[slot(REFERENCE_LAG)]
            val neighbours = ArrayList<FloatArray>(TIME_NEIGHBOUR_DISTANCES.size * 2)
            for (d in TIME_NEIGHBOUR_DISTANCES) {
                neighbours += spreads[slot(REFERENCE_LAG - d)]
                neighbours += spreads[slot(REFERENCE_LAG + d)]
            }

            for (bin in FIRST_PEAK_BIN..LAST_PEAK_BIN) {
                val power = candidate[bin]
                val below = bin - 1
                if (power < MIN_PEAK_POWER || power < reference[below]) continue

                var ok = true
                for (d in FREQUENCY_NEIGHBOUR_DISTANCES) {
                    if (!(power > reference[below - d] && power > reference[below + d])) {
                        ok = false
                        break
                    }
                }
                if (!ok) continue
                for (frame in neighbours) {
                    if (!(power > frame[below])) {
                        ok = false
                        break
                    }
                }
                if (!ok) continue

                val magnitude = logMagnitude(power)
                val refined = refineBin(bin, logMagnitude(candidate[bin - 1]), magnitude, logMagnitude(candidate[bin + 1]))
                val band = bandOf(refined * 125 / 1024)
                if (band < 0) continue
                peaks.getOrPut(band) { ArrayList() } += Peak(
                    frame = frameIndex,
                    magnitude = magnitude.toInt().coerceIn(0, 0xffff),
                    refinedBin = refined,
                )
            }
        }
    }

    /** Plain radix-2 FFT of a real signal; returns bins 0..n/2. */
    private class RealFft(private val n: Int) {
        private val levels = Integer.numberOfTrailingZeros(n)
        private val cosTable = DoubleArray(n / 2) { cos(2.0 * PI * it / n) }
        private val sinTable = DoubleArray(n / 2) { sin(2.0 * PI * it / n) }
        private val reversed = IntArray(n) { Integer.reverse(it) ushr (32 - levels) }
        private val re = DoubleArray(n)
        private val im = DoubleArray(n)
        private val outRe = DoubleArray(n / 2 + 1)
        private val outIm = DoubleArray(n / 2 + 1)

        fun forward(input: DoubleArray): Pair<DoubleArray, DoubleArray> {
            for (i in 0 until n) {
                re[reversed[i]] = input[i]
                im[reversed[i]] = 0.0
            }
            var size = 2
            while (size <= n) {
                val half = size / 2
                val step = n / size
                var start = 0
                while (start < n) {
                    var k = 0
                    for (j in start until start + half) {
                        val l = j + half
                        val c = cosTable[k]
                        val s = sinTable[k]
                        val tRe = re[l] * c + im[l] * s
                        val tIm = -re[l] * s + im[l] * c
                        re[l] = re[j] - tRe
                        im[l] = im[j] - tIm
                        re[j] += tRe
                        im[j] += tIm
                        k += step
                    }
                    start += size
                }
                size *= 2
            }
            for (k in 0..n / 2) {
                outRe[k] = re[k]
                outIm[k] = im[k]
            }
            return outRe to outIm
        }
    }
}
