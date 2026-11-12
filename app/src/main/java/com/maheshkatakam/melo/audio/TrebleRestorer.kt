package com.maheshkatakam.melo.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Rebuilds the treble that MP3/AAC compression cut off.
 *
 * Low-bitrate files have nothing above a cut-off (about 16 kHz at 128 kbps). This takes the
 * band just below the cut-off and copies it up into the empty space, at a level that
 * continues the song's natural slope. Cymbal hits and "air" come back in time with the
 * music, because the copy follows the real signal. It's the same idea broadcasters and
 * modern codecs use (spectral band replication).
 *
 * It is a convincing guess, not the original recording: the detail the encoder threw away
 * can't be recovered. Works on interleaved stereo floats, in place.
 */
class TrebleRestorer(private val sampleRate: Int) {

    private var active = false
    private var gain = 0f
    private var gainTarget = 0f
    private var phase = 0.0
    private var phaseStep = 0.0

    // Per channel: 2 biquads (4th-order high-pass) for the source band, 4 (8th-order) after shifting.
    private val src = Array(2) { Array(2) { Biquad() } }
    private val post = Array(2) { Array(4) { Biquad() } }

    /** [cutoffHz] is where the file's sound ends; [gainDb] from the quality analysis. Null turns it off. */
    fun configure(cutoffHz: Int?, gainDb: Float?) {
        val nyquist = sampleRate / 2.0
        if (cutoffHz == null || gainDb == null || cutoffHz < 6000 || cutoffHz > nyquist - 1500) {
            gainTarget = 0f
            return
        }
        val start = cutoffHz - 250.0
        val width = min(min(nyquist - start, start * 0.5), 7000.0)
        val qs4 = doubleArrayOf(0.5412, 1.3066)
        val qs8 = doubleArrayOf(0.5098, 0.6013, 0.9000, 2.5629)
        for (c in 0..1) {
            for (i in 0..1) src[c][i].highPass(start - width, qs4[i], sampleRate)
            for (i in 0..3) post[c][i].highPass(start, qs8[i], sampleRate)
        }
        phaseStep = 2 * PI * width / sampleRate
        gainTarget = 10.0.pow(gainDb / 20.0).toFloat()
        active = true
    }

    fun reset() {
        for (c in 0..1) {
            src[c].forEach { it.clear() }
            post[c].forEach { it.clear() }
        }
        gain = 0f
    }

    val isIdle: Boolean get() = !active || (gainTarget == 0f && gain < 1e-4f)

    fun process(buffer: FloatArray, frames: Int) {
        if (isIdle) return
        var g = gain
        val gt = gainTarget
        var ph = phase
        for (i in 0 until frames) {
            g += (gt - g) * 0.0005f // fades in/out over ~50 ms
            val carrier = 2.0 * cos(ph)
            ph += phaseStep
            if (ph > 2 * PI) ph -= 2 * PI
            for (c in 0..1) {
                val x = buffer[i * 2 + c].toDouble()
                var s = src[c][0].run(x)
                s = src[c][1].run(s)
                var m = s * carrier
                m = post[c][0].run(m); m = post[c][1].run(m); m = post[c][2].run(m); m = post[c][3].run(m)
                buffer[i * 2 + c] = (x + g * m).toFloat()
            }
        }
        gain = g
        phase = ph
    }

    private class Biquad {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
        private var z1 = 0.0; private var z2 = 0.0

        fun highPass(f: Double, q: Double, rate: Int) {
            val w = 2 * PI * f / rate
            val alpha = sin(w) / (2 * q)
            val cw = cos(w)
            val a0 = 1 + alpha
            b0 = (1 + cw) / 2 / a0; b1 = -(1 + cw) / a0; b2 = (1 + cw) / 2 / a0
            a1 = -2 * cw / a0; a2 = (1 - alpha) / a0
        }

        fun clear() {
            z1 = 0.0; z2 = 0.0
        }

        fun run(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }
}
