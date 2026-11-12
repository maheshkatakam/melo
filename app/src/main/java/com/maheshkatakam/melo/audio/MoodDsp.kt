package com.maheshkatakam.melo.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Melo's sound engine: turns a [SoundProfile] into actual audio processing.
 *
 * Chain: headroom → tone EQ + bass punch → warmth → stereo width / spatial → room reverb →
 * levelling → loudness → limiter (so it can never distort by clipping).
 *
 * Works on interleaved stereo floats in -1..1. Changes are smoothed, so switching moods or
 * dragging a slider doesn't click. Pure Kotlin with no Android dependencies.
 */
class MoodDsp(private val sampleRate: Int) {

    private var target = SoundProfile.FLAT
    private var targetDirty = true

    // --- EQ: 6 biquads per channel (low shelf, 3 peaks, high shelf, bass punch) -------------
    private val stages = 6
    private val coefTarget = DoubleArray(stages * 5)
    private val coef = DoubleArray(stages * 5)
    private val zL = DoubleArray(stages * 2)
    private val zR = DoubleArray(stages * 2)

    // --- Smoothed scalars ---------------------------------------------------------------------
    private var preGain = 1f
    private var preGainT = 1f
    private var width = 1f
    private var widthT = 1f
    private var spatial = 0f
    private var spatialT = 0f
    private var reverbAmt = 0f
    private var reverbAmtT = 0f
    private var warmth = 0f
    private var warmthT = 0f
    private var levelling = 0f
    private var levellingT = 0f
    private var outGain = 1f
    private var outGainT = 1f

    // --- Spatial delay lines -------------------------------------------------------------------
    private val delaySize = nextPow2((sampleRate * 0.035).toInt())
    private val delayMask = delaySize - 1
    private val sideDelay = FloatArray(delaySize)
    private val midDelay = FloatArray(delaySize)
    private var delayPos = 0
    private val dSideA = ms(13.0)
    private val dSideB = ms(21.0)
    private val dMidA = ms(17.0)
    private val dMidB = ms(23.0)
    private var midLp = 0f
    private var midHp = 0f
    private val lpCoef = onePole(5000.0)
    private val hpCoef = onePole(250.0)

    // --- Reverb (Freeverb-style: 8 combs + 4 allpasses per side) ---------------------------------
    private val combTunings = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allpassTunings = intArrayOf(556, 441, 341, 225)
    private val stereoSpread = 23
    private val combL = Array(8) { FloatArray(scale(combTunings[it])) }
    private val combR = Array(8) { FloatArray(scale(combTunings[it] + stereoSpread)) }
    private val combPosL = IntArray(8)
    private val combPosR = IntArray(8)
    private val combStoreL = FloatArray(8)
    private val combStoreR = FloatArray(8)
    private val apL = Array(4) { FloatArray(scale(allpassTunings[it])) }
    private val apR = Array(4) { FloatArray(scale(allpassTunings[it] + stereoSpread)) }
    private val apPosL = IntArray(4)
    private val apPosR = IntArray(4)
    private var combFeedback = 0.8f
    private val combDamp = 0.35f
    private var reverbTail = 0

    // --- Dynamics ----------------------------------------------------------------------------------
    private var compEnv = 0f
    private val compAttack = timeCoef(0.008)
    private val compRelease = timeCoef(0.30)
    private var limEnv = 0f
    private val limRelease = timeCoef(0.12)

    /** Blocks processed since the last change while the profile is neutral. */
    private var neutralBlocks = 0

    /** True once a neutral profile has fully settled, so the caller can pass audio through untouched. */
    val isTransparent: Boolean get() = target.isNeutral && neutralBlocks > 400

    init {
        computeTargets()
        System.arraycopy(coefTarget, 0, coef, 0, coef.size)
    }

    fun setProfile(profile: SoundProfile) {
        if (profile != target) {
            target = profile
            targetDirty = true
            neutralBlocks = 0
        }
    }

    fun reset() {
        zL.fill(0.0); zR.fill(0.0)
        sideDelay.fill(0f); midDelay.fill(0f)
        combL.forEach { it.fill(0f) }; combR.forEach { it.fill(0f) }
        apL.forEach { it.fill(0f) }; apR.forEach { it.fill(0f) }
        combStoreL.fill(0f); combStoreR.fill(0f)
        midLp = 0f; midHp = 0f; compEnv = 0f; limEnv = 0f; reverbTail = 0
    }

    /** Processes [frames] stereo frames of interleaved audio in place. */
    fun process(buffer: FloatArray, frames: Int) {
        var done = 0
        while (done < frames) {
            val n = minOf(BLOCK, frames - done)
            smooth()
            processBlock(buffer, done * 2, n)
            done += n
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun smooth() {
        if (targetDirty) {
            computeTargets()
            targetDirty = false
        }
        val k = 0.2
        for (i in coef.indices) coef[i] += (coefTarget[i] - coef[i]) * k
        val kf = 0.2f
        preGain += (preGainT - preGain) * kf
        width += (widthT - width) * kf
        spatial += (spatialT - spatial) * kf
        reverbAmt += (reverbAmtT - reverbAmt) * kf
        warmth += (warmthT - warmth) * kf
        levelling += (levellingT - levelling) * kf
        outGain += (outGainT - outGain) * kf
        if (target.isNeutral) neutralBlocks++
    }

    private fun computeTargets() {
        val p = target
        setBiquad(0, lowShelf(SoundProfile.FREQUENCIES_HZ[0].toDouble(), p.bands[0].toDouble()))
        setBiquad(1, peak(SoundProfile.FREQUENCIES_HZ[1].toDouble(), 0.9, p.bands[1].toDouble()))
        setBiquad(2, peak(SoundProfile.FREQUENCIES_HZ[2].toDouble(), 0.9, p.bands[2].toDouble()))
        setBiquad(3, peak(SoundProfile.FREQUENCIES_HZ[3].toDouble(), 0.9, p.bands[3].toDouble()))
        setBiquad(4, highShelf(SoundProfile.FREQUENCIES_HZ[4].toDouble(), p.bands[4].toDouble()))
        setBiquad(5, peak(60.0, 0.8, p.bass.toDouble()))

        // Leave room for boosts so the limiter isn't working all the time.
        val biggestBoost = max(0f, max(p.bands[0] + p.bass, p.bands.max()))
        preGainT = db(-0.5f * biggestBoost)
        widthT = p.width
        spatialT = p.spatial
        reverbAmtT = p.reverb
        warmthT = p.warmth
        levellingT = p.levelling
        outGainT = db(p.gainDb + 0.35f * biggestBoost)
        combFeedback = 0.70f + 0.22f * p.room
    }

    private fun processBlock(buf: FloatArray, offset: Int, frames: Int) {
        val c = coef
        val warm = warmth
        val a = 1.5f * warm
        val warmNorm = 1f / (1f + 0.5f * a)
        val sp = spatial
        val w = width * (1f + 0.35f * sp)
        val rev = reverbAmt
        val reverbOn = rev > 0.0005f || reverbTail > 0
        if (rev > 0.0005f) reverbTail = sampleRate * 3 else if (reverbTail > 0) reverbTail -= frames
        val lev = levelling
        val ratioInv = 1f / (1f + 3f * lev)
        val makeup = lev * 5f
        val og = outGain
        val pg = preGain

        var i = offset
        val end = offset + frames * 2
        while (i < end) {
            var l = (buf[i] * pg).toDouble()
            var r = (buf[i + 1] * pg).toDouble()

            // Tone EQ + bass punch (transposed direct form II).
            var s = 0
            while (s < stages) {
                val o = s * 5
                val z = s * 2
                val b0 = c[o]; val b1 = c[o + 1]; val b2 = c[o + 2]; val a1 = c[o + 3]; val a2 = c[o + 4]
                val yl = b0 * l + zL[z]
                zL[z] = b1 * l - a1 * yl + zL[z + 1]
                zL[z + 1] = b2 * l - a2 * yl
                l = yl
                val yr = b0 * r + zR[z]
                zR[z] = b1 * r - a1 * yr + zR[z + 1]
                zR[z + 1] = b2 * r - a2 * yr
                r = yr
                s++
            }
            var fl = l.toFloat()
            var fr = r.toFloat()

            // Warmth: gentle, symmetrical saturation.
            if (warm > 0.001f) {
                fl = fl * (1f + a) / (1f + a * abs(fl)) * warmNorm
                fr = fr * (1f + a) / (1f + a * abs(fr)) * warmNorm
            }

            // Width and spatial.
            val mid = (fl + fr) * 0.5f
            val side = (fl - fr) * 0.5f * w
            fl = mid + side
            fr = mid - side
            if (sp > 0.001f || delayActive) {
                midLp += (mid - midLp) * lpCoef
                midHp += (midLp - midHp) * hpCoef
                val band = midLp - midHp
                sideDelay[delayPos] = side
                midDelay[delayPos] = band
                val sA = sideDelay[(delayPos - dSideA) and delayMask]
                val sB = sideDelay[(delayPos - dSideB) and delayMask]
                val mA = midDelay[(delayPos - dMidA) and delayMask]
                val mB = midDelay[(delayPos - dMidB) and delayMask]
                delayPos = (delayPos + 1) and delayMask
                fl += sp * (0.45f * sA + 0.22f * mA - 0.12f * mB)
                fr += sp * (-0.45f * sB + 0.22f * mB - 0.12f * mA)
            }

            // Room reverb.
            if (reverbOn) {
                val input = (fl + fr) * 0.015f
                var wl = 0f
                var wr = 0f
                var k = 0
                while (k < 8) {
                    val bl = combL[k]; var pl = combPosL[k]
                    val ol = bl[pl]
                    combStoreL[k] = ol * (1f - combDamp) + combStoreL[k] * combDamp
                    bl[pl] = input + combStoreL[k] * combFeedback
                    if (++pl >= bl.size) pl = 0
                    combPosL[k] = pl
                    wl += ol
                    val br = combR[k]; var pr = combPosR[k]
                    val or = br[pr]
                    combStoreR[k] = or * (1f - combDamp) + combStoreR[k] * combDamp
                    br[pr] = input + combStoreR[k] * combFeedback
                    if (++pr >= br.size) pr = 0
                    combPosR[k] = pr
                    wr += or
                    k++
                }
                k = 0
                while (k < 4) {
                    val bl = apL[k]; var pl = apPosL[k]
                    val bufl = bl[pl]
                    val outl = -wl + bufl
                    bl[pl] = wl + bufl * 0.5f
                    if (++pl >= bl.size) pl = 0
                    apPosL[k] = pl
                    wl = outl
                    val br = apR[k]; var pr = apPosR[k]
                    val bufr = br[pr]
                    val outr = -wr + bufr
                    br[pr] = wr + bufr * 0.5f
                    if (++pr >= br.size) pr = 0
                    apPosR[k] = pr
                    wr = outr
                    k++
                }
                fl += wl * rev * 2.2f
                fr += wr * rev * 2.2f
            }

            // Levelling (compressor): louder parts come down, everything comes up a bit.
            val peak = max(abs(fl), abs(fr))
            if (lev > 0.001f) {
                compEnv += (peak - compEnv) * (if (peak > compEnv) compAttack else compRelease)
                val levelDb = 8.6858896f * ln(compEnv + 1e-9f)
                val over = levelDb - COMP_THRESHOLD_DB
                val gainDb = makeup - (if (over > 0f) over * (1f - ratioInv) else 0f)
                val g = exp(gainDb * 0.11512925f)
                fl *= g
                fr *= g
            }

            fl *= og
            fr *= og

            // Limiter: instant attack, smooth release. Output never exceeds the ceiling.
            val p2 = max(abs(fl), abs(fr))
            limEnv = if (p2 > limEnv) p2 else limEnv + (p2 - limEnv) * limRelease
            if (limEnv > CEILING) {
                val g = CEILING / limEnv
                fl *= g
                fr *= g
            }

            buf[i] = fl
            buf[i + 1] = fr
            i += 2
        }
        delayActive = sp > 0.001f
    }

    private var delayActive = false

    // --- helpers ---------------------------------------------------------------------------------

    private fun setBiquad(stage: Int, c: DoubleArray) {
        System.arraycopy(c, 0, coefTarget, stage * 5, 5)
    }

    private fun peak(f: Double, q: Double, gainDb: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * f / sampleRate
        val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha / a
        return doubleArrayOf(
            (1 + alpha * a) / a0, (-2 * cos(w0)) / a0, (1 - alpha * a) / a0,
            (-2 * cos(w0)) / a0, (1 - alpha / a) / a0,
        )
    }

    private fun lowShelf(f: Double, gainDb: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * f / sampleRate
        val cosw = cos(w0)
        val alpha = sin(w0) / 2 * sqrt(2.0)
        val sq = 2 * sqrt(a) * alpha
        val a0 = (a + 1) + (a - 1) * cosw + sq
        return doubleArrayOf(
            a * ((a + 1) - (a - 1) * cosw + sq) / a0,
            2 * a * ((a - 1) - (a + 1) * cosw) / a0,
            a * ((a + 1) - (a - 1) * cosw - sq) / a0,
            -2 * ((a - 1) + (a + 1) * cosw) / a0,
            ((a + 1) + (a - 1) * cosw - sq) / a0,
        )
    }

    private fun highShelf(f: Double, gainDb: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * minOf(f, sampleRate * 0.45) / sampleRate
        val cosw = cos(w0)
        val alpha = sin(w0) / 2 * sqrt(2.0)
        val sq = 2 * sqrt(a) * alpha
        val a0 = (a + 1) - (a - 1) * cosw + sq
        return doubleArrayOf(
            a * ((a + 1) + (a - 1) * cosw + sq) / a0,
            -2 * a * ((a - 1) + (a + 1) * cosw) / a0,
            a * ((a + 1) + (a - 1) * cosw - sq) / a0,
            2 * ((a - 1) - (a + 1) * cosw) / a0,
            ((a + 1) - (a - 1) * cosw - sq) / a0,
        )
    }

    private fun db(v: Float): Float = 10.0.pow(v / 20.0).toFloat()
    private fun ms(v: Double): Int = (sampleRate * v / 1000.0).toInt().coerceIn(1, delaySize - 1)
    private fun scale(samplesAt44k: Int): Int = max(8, (samplesAt44k.toLong() * sampleRate / 44100).toInt())
    private fun onePole(cutoffHz: Double): Float = (1 - exp(-2 * PI * cutoffHz / sampleRate)).toFloat()
    private fun timeCoef(seconds: Double): Float = (1 - exp(-1.0 / (seconds * sampleRate))).toFloat()

    private fun nextPow2(v: Int): Int {
        var n = 256
        while (n < v) n = n shl 1
        return n
    }

    companion object {
        private const val BLOCK = 128
        private const val CEILING = 0.97f
        private const val COMP_THRESHOLD_DB = -20f
    }
}
