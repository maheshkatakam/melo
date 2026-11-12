package com.maheshkatakam.melo.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Measured head-related impulse responses: how a sound from a given direction reaches each
 * ear. From the MIT Media Lab KEMAR dummy-head measurements by Bill Gardner and Keith
 * Martin (1994), which are free to use with attribution.
 *
 * Each entry is for a source on the listener's right at [az] degrees (0 = front, 90 = right,
 * 180 = behind) and [el] degrees up. Sources on the left use the mirror image.
 */
class HrtfSet(
    val az: FloatArray,
    val el: FloatArray,
    /** [direction][tap], at 44.1 kHz. */
    val left: Array<FloatArray>,
    val right: Array<FloatArray>,
    /** Average response over all directions (diffuse field), 0..22.05 kHz, used to keep the tone neutral. */
    val diffuse: FloatArray,
) {
    fun index(azimuth: Int, elevation: Int): Int {
        for (i in az.indices) if (az[i].toInt() == azimuth && el[i].toInt() == elevation) return i
        error("No HRTF for $azimuth/$elevation")
    }

    companion object {
        const val RATE = 44_100

        fun parse(bytes: ByteArray): HrtfSet {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val count = b.int
            val taps = b.int
            val dfPoints = b.int
            val az = FloatArray(count)
            val el = FloatArray(count)
            val l = Array(count) { FloatArray(taps) }
            val r = Array(count) { FloatArray(taps) }
            for (i in 0 until count) {
                az[i] = b.float
                el[i] = b.float
                for (t in 0 until taps) l[i][t] = b.float
                for (t in 0 until taps) r[i][t] = b.float
            }
            val df = FloatArray(dfPoints) { b.float }
            return HrtfSet(az, el, l, r, df)
        }
    }
}

/**
 * Turns ordinary audio into 3D sound for headphones.
 *
 * For stereo music it works a little like an automatic re-mix: in every slice of time and
 * frequency it works out what is a distinct sound and where it sits between left and right,
 * and what is ambience (reverb, room, crowd). Distinct sounds are placed on a wide stage in
 * front of you; ambience is moved to your sides, behind and above. Everything is then
 * rendered through measured ear responses ([HrtfSet]) so it seems to come from outside your
 * head.
 *
 * It can also play real surround files (5.1 / 7.1) as virtual speakers around you, and turn
 * stereo into 5.1 for the phone's own spatial audio.
 *
 * Pure Kotlin, no Android dependencies. Not Dolby Atmos.
 */
class ImmersiveRenderer(private val sampleRate: Int, private val hrtf: HrtfSet) {

    enum class Mode { STEREO_TO_BINAURAL, SURROUND_TO_BINAURAL, STEREO_TO_51 }

    private val n: Int = when {
        sampleRate <= 50_000 -> 2048
        sampleRate <= 100_000 -> 4096
        else -> 8192
    }
    private val hop = n / 4
    private val bins = n / 2 + 1
    private val fft = Fft(n)
    private val window = FloatArray(n) { sqrt(0.5 - 0.5 * cos(2.0 * PI * it / n)).toFloat() }

    /** Audio comes out this many frames after it goes in. */
    val latencyFrames: Int get() = n

    // HRTF spectra for sources on the right: [dir * bins + k].
    private val dirs = hrtf.az.size
    private val hLre = FloatArray(dirs * bins)
    private val hLim = FloatArray(dirs * bins)
    private val hRre = FloatArray(dirs * bins)
    private val hRim = FloatArray(dirs * bins)

    // Ambience filters (near ear / far ear), rebuilt when strength changes.
    private val ambNearRe = FloatArray(bins)
    private val ambNearIm = FloatArray(bins)
    private val ambFarRe = FloatArray(bins)
    private val ambFarIm = FloatArray(bins)
    private val decorA = FloatArray(bins * 2)
    private val decorB = FloatArray(bins * 2)
    private val lowBlend = FloatArray(bins)

    private var strength = -1f
    private var stageHalfWidth = 60f
    private var ambFront = 0.6f
    private var ambSurround = 0.5f

    // Running statistics per bin.
    private val sLL = FloatArray(bins)
    private val sRR = FloatArray(bins)
    private val sLRre = FloatArray(bins)
    private val sLRim = FloatArray(bins)

    // Streaming state.
    private var inChannels = 0
    private var outChannels = 0
    private var inWin = Array(0) { FloatArray(0) }
    private var outAcc = Array(0) { FloatArray(0) }
    private var fill = 0
    private val re = Array(8) { FloatArray(n) }
    private val im = Array(8) { FloatArray(n) }
    private val yRe = Array(6) { FloatArray(n) }
    private val yIm = Array(6) { FloatArray(n) }

    init {
        buildHrtfTables()
        for (k in 0 until bins) {
            val f = k.toDouble() * sampleRate / n
            // Small, different phase wobbles so the rear and overhead ambience don't sound like copies.
            val pa = 1.0 * sin(2 * PI * f * 0.0031) + 0.6 * sin(2 * PI * f * 0.0057 + 1.0)
            val pb = 1.0 * sin(2 * PI * f * 0.0043 + 2.0) + 0.6 * sin(2 * PI * f * 0.0071)
            decorA[k * 2] = cos(pa).toFloat(); decorA[k * 2 + 1] = sin(pa).toFloat()
            decorB[k * 2] = cos(pb).toFloat(); decorB[k * 2 + 1] = sin(pb).toFloat()
            // Deep bass has no direction to speak of: keep it solid and untouched.
            lowBlend[k] = ((f - 110.0) / 110.0).coerceIn(0.0, 1.0).toFloat()
        }
        setStrength(0.6f)
    }

    /** 0 = close to normal stereo, 1 = widest stage and most surround. */
    fun setStrength(value: Float) {
        val s = value.coerceIn(0f, 1f)
        if (s == strength) return
        strength = s
        stageHalfWidth = 30f + 50f * s
        ambFront = 1f - 0.65f * s
        ambSurround = sqrt((1f - ambFront * ambFront) / 3f) * 1.15f
        val front = hrtf.index(30, 0)
        val side = hrtf.index(110, 0)
        val highFront = hrtf.index(45, 40)
        val highRear = hrtf.index(135, 40)
        for (k in 0 until bins) {
            val ar = decorA[k * 2]; val ai = decorA[k * 2 + 1]
            val br = decorB[k * 2]; val bi = decorB[k * 2 + 1]
            fun mix(hre: FloatArray, him: FloatArray, outRe: FloatArray, outIm: FloatArray) {
                val f = front * bins + k; val s1 = side * bins + k; val h1 = highFront * bins + k; val h2 = highRear * bins + k
                outRe[k] = ambFront * hre[f] + ambSurround * (hre[s1] + (hre[h1] * ar - him[h1] * ai) + (hre[h2] * br - him[h2] * bi))
                outIm[k] = ambFront * him[f] + ambSurround * (him[s1] + (hre[h1] * ai + him[h1] * ar) + (hre[h2] * bi + him[h2] * br))
            }
            mix(hRre, hRim, ambNearRe, ambNearIm) // source on the right: right ear is the near one
            mix(hLre, hLim, ambFarRe, ambFarIm)
        }
    }

    fun reset() {
        sLL.fill(0f); sRR.fill(0f); sLRre.fill(0f); sLRim.fill(0f)
        inWin.forEach { it.fill(0f) }
        outAcc.forEach { it.fill(0f) }
        fill = 0
    }

    /**
     * Streams [frames] frames from [input] (interleaved, [inCh] channels) to [output]
     * (interleaved, 2 channels, or 6 for [Mode.STEREO_TO_51]). Output is delayed by [latencyFrames].
     * [input] and [output] may be the same array when the channel counts match.
     */
    fun process(mode: Mode, input: FloatArray, inCh: Int, frames: Int, output: FloatArray) {
        val outCh = if (mode == Mode.STEREO_TO_51) 6 else 2
        if (inCh != inChannels || outCh != outChannels) {
            inChannels = inCh
            outChannels = outCh
            inWin = Array(inCh) { FloatArray(n) }
            outAcc = Array(outCh) { FloatArray(n) }
            fill = 0
        }
        val base = n - hop
        for (i in 0 until frames) {
            for (c in 0 until inCh) inWin[c][base + fill] = input[i * inCh + c]
            for (c in 0 until outCh) output[i * outCh + c] = outAcc[c][fill]
            if (++fill == hop) {
                for (c in 0 until outCh) {
                    val a = outAcc[c]
                    System.arraycopy(a, hop, a, 0, n - hop)
                    java.util.Arrays.fill(a, n - hop, n, 0f)
                }
                frame(mode)
                for (c in 0 until inCh) System.arraycopy(inWin[c], hop, inWin[c], 0, n - hop)
                fill = 0
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun frame(mode: Mode) {
        for (c in 0 until inChannels) {
            val r = re[c]; val m = im[c]; val src = inWin[c]
            for (i in 0 until n) {
                r[i] = src[i] * window[i]
                m[i] = 0f
            }
            fft.forward(r, m)
        }
        val outs = outChannels
        for (c in 0 until outs) {
            java.util.Arrays.fill(yRe[c], 0f)
            java.util.Arrays.fill(yIm[c], 0f)
        }
        when (mode) {
            Mode.STEREO_TO_BINAURAL -> stereoFrame(toSurround = false)
            Mode.STEREO_TO_51 -> stereoFrame(toSurround = true)
            Mode.SURROUND_TO_BINAURAL -> surroundFrame()
        }
        for (c in 0 until outs) {
            val r = yRe[c]; val m = yIm[c]
            // Rebuild the mirrored half so the result is real.
            for (k in 1 until n / 2) {
                r[n - k] = r[k]
                m[n - k] = -m[k]
            }
            m[0] = 0f; m[n / 2] = 0f
            fft.inverse(r, m)
            val acc = outAcc[c]
            for (i in 0 until n) acc[i] += r[i] * window[i] * 0.5f
        }
    }

    /** Stereo music: split into placed sounds and ambience, then render. */
    private fun stereoFrame(toSurround: Boolean) {
        val xlr = re[0]; val xli = im[0]; val xrr = re[1]; val xri = im[1]
        val halfWidthIdx = stageHalfWidth / 5f
        for (k in 0 until bins) {
            val lr = xlr[k]; val li = xli[k]; val rr = xrr[k]; val ri = xri[k]
            // Smoothed statistics of how the two channels relate in this bin.
            sLL[k] = SMOOTH * sLL[k] + (1 - SMOOTH) * (lr * lr + li * li)
            sRR[k] = SMOOTH * sRR[k] + (1 - SMOOTH) * (rr * rr + ri * ri)
            sLRre[k] = SMOOTH * sLRre[k] + (1 - SMOOTH) * (lr * rr + li * ri)
            sLRim[k] = SMOOTH * sLRim[k] + (1 - SMOOTH) * (li * rr - lr * ri)
            val a = sLL[k]; val b = sRR[k]; val cr = sLRre[k]; val ci = sLRim[k]

            // Principal direction (vL, vR) of the 2x2 covariance = the dominant sound here.
            val d = (a - b) * 0.5f
            val root = sqrt(d * d + cr * cr + ci * ci)
            val lambda = (a + b) * 0.5f + root
            var vLr: Float; var vLi: Float; var vRr: Float; var vRi: Float
            if (a >= b) {
                vLr = lambda - b; vLi = 0f; vRr = cr; vRi = -ci
            } else {
                vLr = cr; vLi = ci; vRr = lambda - a; vRi = 0f
            }
            val norm = sqrt(vLr * vLr + vLi * vLi + vRr * vRr + vRi * vRi)
            if (norm < 1e-12f) {
                vLr = 0.70710678f; vLi = 0f; vRr = 0.70710678f; vRi = 0f
            } else {
                val inv = 1f / norm
                vLr *= inv; vLi *= inv; vRr *= inv; vRi *= inv
            }
            // p = v^H x : the dominant sound's signal.
            val pr = vLr * lr + vLi * li + vRr * rr + vRi * ri
            val pi = vLr * li - vLi * lr + vRr * ri - vRi * rr
            // What's left over in each channel is ambience.
            val alr = lr - (vLr * pr - vLi * pi); val ali = li - (vLr * pi + vLi * pr)
            val arr = rr - (vRr * pr - vRi * pi); val ari = ri - (vRr * pi + vRi * pr)

            val magL = sqrt(vLr * vLr + vLi * vLi)
            val magR = sqrt(vRr * vRr + vRi * vRi)
            // Give the placed sound the phase it has in its louder channel.
            val objR: Float; val objI: Float
            if (magL >= magR) {
                val ur = vLr / magL; val ui = vLi / magL
                objR = pr * ur - pi * ui; objI = pr * ui + pi * ur
            } else {
                val ur = vRr / magR; val ui = vRi / magR
                objR = pr * ur - pi * ui; objI = pr * ui + pi * ur
            }
            // -1 = fully left, 0 = centre, +1 = fully right.
            val pan = (atan2(magR, magL) * (4f / PI.toFloat()) - 1f).coerceIn(-1f, 1f)
            val low = lowBlend[k]

            if (toSurround) {
                val side = abs(pan)
                val gC = cos(side * (PI.toFloat() / 2))
                val gS = sin(side * (PI.toFloat() / 2))
                val dr = decorA[k * 2]; val di = decorA[k * 2 + 1]
                var flr = ambFront * alr; var fli = ambFront * ali
                var frr = ambFront * arr; var fri = ambFront * ari
                if (pan < 0) { flr += objR * gS; fli += objI * gS } else { frr += objR * gS; fri += objI * gS }
                val sur = ambSurround * 2.2f
                yRe[0][k] = low * flr + (1 - low) * lr; yIm[0][k] = low * fli + (1 - low) * li
                yRe[1][k] = low * frr + (1 - low) * rr; yIm[1][k] = low * fri + (1 - low) * ri
                yRe[2][k] = low * objR * gC; yIm[2][k] = low * objI * gC
                yRe[4][k] = low * sur * (alr * dr - ali * di); yIm[4][k] = low * sur * (alr * di + ali * dr)
                yRe[5][k] = low * sur * (arr * dr + ari * di); yIm[5][k] = low * sur * (ari * dr - arr * di)
                continue
            }

            // Place the sound on the stage: interpolate the ear responses for its angle.
            val pos = abs(pan) * halfWidthIdx
            val i0 = min(pos.toInt(), 35)
            val t = pos - i0
            val b0 = i0 * bins + k; val b1 = b0 + bins
            val nearRe = hRre[b0] + (hRre[b1] - hRre[b0]) * t; val nearIm = hRim[b0] + (hRim[b1] - hRim[b0]) * t
            val farRe = hLre[b0] + (hLre[b1] - hLre[b0]) * t; val farIm = hLim[b0] + (hLim[b1] - hLim[b0]) * t
            var outLr: Float; var outLi: Float; var outRr: Float; var outRi: Float
            if (pan >= 0) {
                outRr = objR * nearRe - objI * nearIm; outRi = objR * nearIm + objI * nearRe
                outLr = objR * farRe - objI * farIm; outLi = objR * farIm + objI * farRe
            } else {
                outLr = objR * nearRe - objI * nearIm; outLi = objR * nearIm + objI * nearRe
                outRr = objR * farRe - objI * farIm; outRi = objR * farIm + objI * farRe
            }
            // Ambience: left leftovers surround your left side, right leftovers your right.
            val nr = ambNearRe[k]; val ni = ambNearIm[k]; val fr = ambFarRe[k]; val fi = ambFarIm[k]
            outLr += alr * nr - ali * ni + arr * fr - ari * fi
            outLi += alr * ni + ali * nr + arr * fi + ari * fr
            outRr += arr * nr - ari * ni + alr * fr - ali * fi
            outRi += arr * ni + ari * nr + alr * fi + ali * fr

            yRe[0][k] = low * outLr + (1 - low) * lr; yIm[0][k] = low * outLi + (1 - low) * li
            yRe[1][k] = low * outRr + (1 - low) * rr; yIm[1][k] = low * outRi + (1 - low) * ri
        }
    }

    /** Real surround files: each channel becomes a virtual speaker at its standard position. */
    private fun surroundFrame() {
        val ch = inChannels
        // Android channel order. Angles in degrees, negative = left. Float.NaN = LFE (no direction).
        val angles: FloatArray = when (ch) {
            6 -> floatArrayOf(-30f, 30f, 0f, Float.NaN, -110f, 110f)
            8 -> floatArrayOf(-30f, 30f, 0f, Float.NaN, -150f, 150f, -90f, 90f)
            else -> FloatArray(ch) { if (it % 2 == 0) -30f else 30f }
        }
        for (c in 0 until ch) {
            val xr = re[c]; val xi = im[c]
            val angle = angles[c]
            if (angle.isNaN()) {
                for (k in 0 until bins) {
                    yRe[0][k] += xr[k] * 0.5f; yIm[0][k] += xi[k] * 0.5f
                    yRe[1][k] += xr[k] * 0.5f; yIm[1][k] += xi[k] * 0.5f
                }
                continue
            }
            val d = hrtf.index(abs(angle).toInt(), 0) * bins
            val nearOut = if (angle >= 0) 1 else 0
            val farOut = 1 - nearOut
            for (k in 0 until bins) {
                val a = xr[k]; val b = xi[k]
                yRe[nearOut][k] += a * hRre[d + k] - b * hRim[d + k]; yIm[nearOut][k] += a * hRim[d + k] + b * hRre[d + k]
                yRe[farOut][k] += a * hLre[d + k] - b * hLim[d + k]; yIm[farOut][k] += a * hLim[d + k] + b * hLre[d + k]
            }
        }
    }

    /**
     * Ear responses at this engine's frequencies: evaluated straight from the measured
     * impulse responses, tone-neutralised with the diffuse-field curve, with clean bass
     * (the measurements are unreliable below ~200 Hz) and scaled so a centred sound is
     * exactly as loud as in plain stereo.
     */
    private fun buildHrtfTables() {
        val taps = hrtf.left[0].size
        val df = hrtf.diffuse
        val dfRef = df[(1000.0 / (HrtfSet.RATE / 2.0) * (df.size - 1)).toInt()]
        val delays = Array(2) { DoubleArray(dirs) }
        var frontPower = 0.0
        var frontCount = 0
        for (d in 0 until dirs) {
            for (ear in 0..1) {
                val h = if (ear == 0) hrtf.left[d] else hrtf.right[d]
                val outRe = if (ear == 0) hLre else hRre
                val outIm = if (ear == 0) hLim else hRim
                var peak = 0f
                for (v in h) peak = max(peak, abs(v))
                var onset = 0
                while (onset < taps - 1 && abs(h[onset]) < 0.3f * peak) onset++
                delays[ear][d] = onset.toDouble() / HrtfSet.RATE
                for (k in 0 until bins) {
                    val f = min(k.toDouble() * sampleRate / n, HrtfSet.RATE / 2.0)
                    val w = 2 * PI * f / HrtfSet.RATE
                    val cw = cos(w); val sw = sin(w)
                    var cr = 1.0; var ci = 0.0 // e^{-jwt}, advanced one tap at a time
                    var sr = 0.0; var si = 0.0
                    for (t in 0 until taps) {
                        sr += h[t] * cr; si += h[t] * ci
                        val nr = cr * cw + ci * sw
                        ci = ci * cw - cr * sw
                        cr = nr
                    }
                    val dfPos = f / (HrtfSet.RATE / 2.0) * (df.size - 1)
                    val di = min(dfPos.toInt(), df.size - 2)
                    val dfMag = (df[di] + (df[di + 1] - df[di]) * (dfPos - di)) / dfRef
                    // Neutral tone, plus a touch of air: frontal ear responses are a little dull up top.
                    val air = Math.pow(10.0, (Math.log(max(f, 5000.0) / 5000.0) / Math.log(2.0) * 2.0).coerceAtMost(2.5) / 20.0)
                    val eq = (1.0 / dfMag).coerceIn(0.25, 4.0) * air
                    outRe[d * bins + k] = (sr * eq).toFloat()
                    outIm[d * bins + k] = (si * eq).toFloat()
                    if (d == 0 && f in 400.0..8000.0) {
                        frontPower += (sr * sr + si * si) * eq * eq
                        frontCount++
                    }
                }
            }
        }
        // Normalise: straight ahead = 1/sqrt(2) per ear on average, matching plain stereo loudness.
        val scale = (LEVEL / sqrt(frontPower / frontCount)).toFloat()
        for (d in 0 until dirs) {
            for (ear in 0..1) {
                val outRe = if (ear == 0) hLre else hRre
                val outIm = if (ear == 0) hLim else hRim
                val tau = delays[ear][d]
                for (k in 0 until bins) {
                    val i = d * bins + k
                    val f = min(k.toDouble() * sampleRate / n, HrtfSet.RATE / 2.0)
                    // Below ~300 Hz: flat level with just the arrival-time difference.
                    val c = (1.0 - (f - 150.0) / 250.0).coerceIn(0.0, 1.0).toFloat()
                    val wt = 2 * PI * f * tau
                    outRe[i] = (1 - c) * outRe[i] * scale + c * (LEVEL * cos(wt)).toFloat()
                    outIm[i] = (1 - c) * outIm[i] * scale - c * (LEVEL * sin(wt)).toFloat()
                }
            }
        }
    }

    companion object {
        private const val SMOOTH = 0.88f
        private const val LEVEL = 0.70710678
    }
}

/** Small in-place complex FFT (power-of-two sizes). */
class Fft(private val n: Int) {
    private val levels = Integer.numberOfTrailingZeros(n)
    private val cosT = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { sin(2.0 * PI * it / n).toFloat() }
    private val rev = IntArray(n) { Integer.reverse(it) ushr (32 - levels) }

    fun forward(re: FloatArray, im: FloatArray) = transform(re, im, -1f)

    /** Inverse, scaled by 1/n. */
    fun inverse(re: FloatArray, im: FloatArray) {
        transform(re, im, 1f)
        val s = 1f / n
        for (i in 0 until n) {
            re[i] *= s
            im[i] *= s
        }
    }

    private fun transform(re: FloatArray, im: FloatArray, sign: Float) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
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
                    val c = cosT[k]
                    val s = sinT[k] * sign
                    val tr = re[l] * c - im[l] * s
                    val ti = re[l] * s + im[l] * c
                    re[l] = re[j] - tr; im[l] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }
}
