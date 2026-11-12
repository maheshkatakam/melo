package com.maheshkatakam.melo.quality

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * Estimates the real quality of a song from its sound, not its label.
 *
 * Lossy encoders (MP3, AAC) throw away the highest frequencies, and how much they remove
 * depends on the bitrate: roughly 16 kHz at 128 kbps, 19 kHz at 192, 20 kHz and above at
 * 320. A file labelled "320 kbps" that has nothing above 16 kHz was converted up from a
 * 128 kbps source. This finds that cut-off. It is an estimate: a few genuine recordings
 * simply have little treble.
 */
object QualityAnalyzer {

    data class Result(
        /** Highest frequency with real content, in Hz. */
        val cutoffHz: Int,
        /** What that corresponds to, e.g. "about 128 kbps". */
        val grade: Grade,
        /**
         * For rebuilding the missing treble ([com.maheshkatakam.melo.audio.TrebleRestorer]):
         * how loud the rebuilt band should be relative to the band it is copied from, in dB,
         * so the spectrum carries on at its natural slope. Null when nothing is missing.
         */
        val restoreGainDb: Float? = null,
    )

    enum class Grade(val label: String, val approxKbps: Int) {
        VERY_LOW("very low, like 64 kbps or less", 64),
        LOW("low, like 96 kbps", 96),
        MEDIUM("like a 128 kbps file", 128),
        GOOD("like a 160 kbps file", 160),
        HIGH("like a 192–256 kbps file", 224),
        FULL("full quality (320 kbps or lossless grade)", 320),
    }

    private const val FFT = 4096

    /** [samples] is mono audio at [sampleRate] (at least a few seconds). */
    fun analyze(samples: FloatArray, sampleRate: Int): Result? {
        if (samples.size < FFT * 8 || sampleRate < 32_000) return null
        val window = FloatArray(FFT) { (0.5 - 0.5 * cos(2 * PI * it / FFT)).toFloat() }
        val re = DoubleArray(FFT)
        val im = DoubleArray(FFT)
        val power = DoubleArray(FFT / 2)
        var frames = 0
        var pos = 0
        while (pos + FFT <= samples.size) {
            for (i in 0 until FFT) {
                re[i] = (samples[pos + i] * window[i]).toDouble()
                im[i] = 0.0
            }
            fft(re, im)
            for (k in 0 until FFT / 2) {
                power[k] += re[k] * re[k] + im[k] * im[k]
            }
            frames++
            pos += FFT / 2
        }
        if (frames == 0) return null
        val binHz = sampleRate.toDouble() / FFT
        fun bandDb(src: DoubleArray, fromHz: Double, toHz: Double): Double {
            val a = (fromHz / binHz).toInt().coerceIn(1, FFT / 2 - 1)
            val b = (toHz / binHz).toInt().coerceIn(a + 1, FFT / 2)
            var sum = 0.0
            for (k in a until b) sum += src[k]
            return 10 * log10(sum / (b - a) + 1e-30)
        }
        val reference = bandDb(power, 1000.0, 4000.0) - 10 * log10(frames.toDouble())
        if (reference < -50) return null // silence

        // Walk down from the top in 250 Hz steps until there is real content. Encoders leave
        // nothing at all above their cut-off (about -100 dB), while real treble that is merely
        // quiet stays far above that, so a generous threshold separates the two cleanly.
        val nyquist = sampleRate / 2.0
        var f = minOf(nyquist, 22_000.0) - 250
        var cutoff = 0.0
        while (f >= 4000) {
            if (bandDb(power, f, f + 250) - 10 * log10(frames.toDouble()) > reference - 70) {
                cutoff = f + 250
                break
            }
            f -= 250
        }
        val hz = cutoff.toInt()
        val grade = when {
            hz < 11_500 -> Grade.VERY_LOW
            hz < 15_800 -> Grade.LOW
            hz < 17_300 -> Grade.MEDIUM
            hz < 19_000 -> Grade.GOOD
            hz < 19_900 -> Grade.HIGH
            else -> Grade.FULL
        }
        var restoreGain: Float? = null
        if (hz in 6000..19_000) {
            val width = minOf(nyquist - hz, hz * 0.5, 7000.0)
            if (width >= 1500) {
                val scale = 10 * log10(frames.toDouble())
                val low = bandDb(power, hz - width, hz - width + 1000) - scale
                val top = bandDb(power, hz - 1500.0, hz - 400.0) - scale
                // The copied band starts as loud as its bottom edge; bring it down to where the top edge left off.
                // A few dB under that on purpose: slightly too little treble is harmless, too much is harsh.
                restoreGain = (top - low - 7.0).coerceIn(-30.0, -6.0).toFloat()
            }
        }
        return Result(hz, grade, restoreGain)
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
