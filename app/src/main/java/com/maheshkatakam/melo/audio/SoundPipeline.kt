package com.maheshkatakam.melo.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Everything Melo does to the sound, in order:
 *
 *   rebuild missing treble → mood (tone, bass, warmth, room, levelling, limiter) → 3D
 *
 * Takes mono, stereo, 5.1 or 7.1 audio and produces stereo, or 5.1 when the phone's own
 * spatial audio is going to do the 3D part. Settings can be changed from any thread while
 * audio is running; switches are faded so they don't click. Pure Kotlin.
 */
class SoundPipeline(
    private val sampleRate: Int,
    private val inChannels: Int,
    val outChannels: Int,
    private val hrtf: HrtfSet?,
) {
    class Config(
        val profile: SoundProfile = SoundProfile.FLAT,
        /** 3D rendering wanted right now (already takes "are headphones connected" into account). */
        val immersive: Boolean = false,
        val strength: Float = 0.6f,
        val restoreCutoffHz: Int? = null,
        val restoreGainDb: Float? = null,
    )

    @Volatile
    var config: Config = Config()

    private val dsp = MoodDsp(sampleRate)
    private val restorer = TrebleRestorer(sampleRate)
    private var renderer: ImmersiveRenderer? = null
    private var applied: Config? = null
    private var immersiveNow = false
    private var fadeIn = false
    private var stereo = FloatArray(0)
    private var wide = FloatArray(0)
    private var limiterEnv = 0f
    private val limiterRelease = (1 - Math.exp(-1.0 / (0.12 * sampleRate))).toFloat()

    private val surroundIn = inChannels > 2

    /** True when audio would pass through completely unchanged (stereo in, stereo out, nothing switched on). */
    val isTransparent: Boolean
        get() {
            val c = config
            return inChannels == 2 && outChannels == 2 && c === applied && !c.immersive && !immersiveNow && !fadeIn &&
                dsp.isTransparent && restorer.isIdle
        }

    fun reset() {
        dsp.reset()
        restorer.reset()
        renderer?.reset()
        limiterEnv = 0f
    }

    /** [input] holds [frames] frames of [inChannels]; [output] receives [frames] frames of [outChannels]. */
    fun process(input: FloatArray, frames: Int, output: FloatArray) {
        val c = config
        val wantImmersive = c.immersive && hrtf != null
        if (c !== applied) {
            applied = c
            restorer.configure(c.restoreCutoffHz, c.restoreGainDb)
            renderer?.setStrength(c.strength)
        }
        // Switching 3D on or off changes the timing slightly, so fade out, switch, fade in.
        val switching = wantImmersive != immersiveNow
        val binaural = immersiveNow && outChannels == 2
        dsp.setProfile(
            if (binaural) c.profile.copy(spatial = 0f, width = min(c.profile.width, 1.1f), reverb = max(c.profile.reverb, 0.05f))
            else if (immersiveNow) c.profile.copy(spatial = 0f, width = min(c.profile.width, 1.1f))
            else c.profile,
        )

        if (stereo.size < frames * 2) stereo = FloatArray(frames * 2)
        val st = stereo
        val r = renderer

        if (surroundIn) {
            if (immersiveNow && r != null) {
                r.process(ImmersiveRenderer.Mode.SURROUND_TO_BINAURAL, input, inChannels, frames, st)
            } else {
                downmix(input, frames, st)
            }
            dsp.process(st, frames)
            System.arraycopy(st, 0, output, 0, frames * 2)
        } else {
            if (inChannels == 1) {
                for (i in 0 until frames) {
                    st[i * 2] = input[i]
                    st[i * 2 + 1] = input[i]
                }
            } else {
                System.arraycopy(input, 0, st, 0, frames * 2)
            }
            restorer.process(st, frames)
            dsp.process(st, frames)
            if (outChannels == 6) {
                if (immersiveNow && r != null) {
                    r.process(ImmersiveRenderer.Mode.STEREO_TO_51, st, 2, frames, output)
                } else {
                    for (i in 0 until frames) {
                        output[i * 6] = st[i * 2]
                        output[i * 6 + 1] = st[i * 2 + 1]
                        output[i * 6 + 2] = 0f; output[i * 6 + 3] = 0f; output[i * 6 + 4] = 0f; output[i * 6 + 5] = 0f
                    }
                }
            } else {
                if (immersiveNow && r != null) r.process(ImmersiveRenderer.Mode.STEREO_TO_BINAURAL, st, 2, frames, st)
                System.arraycopy(st, 0, output, 0, frames * 2)
            }
        }

        if (immersiveNow) limit(output, frames)

        if (switching) {
            fade(output, frames, down = true)
            immersiveNow = wantImmersive
            if (wantImmersive && renderer == null) {
                renderer = ImmersiveRenderer(sampleRate, hrtf!!).also { it.setStrength(c.strength) }
            }
            renderer?.reset()
            limiterEnv = 0f
            fadeIn = true
        } else if (fadeIn) {
            fade(output, frames, down = false)
            fadeIn = false
        }
    }

    private fun fade(buf: FloatArray, frames: Int, down: Boolean) {
        val ch = outChannels
        val len = min(frames, sampleRate / 20)
        if (down) {
            // Hold full level, then ramp to silence over the last [len] frames.
            val start = frames - len
            for (i in 0 until len) {
                val g = 1f - (i + 1).toFloat() / len
                for (c in 0 until ch) buf[(start + i) * ch + c] *= g
            }
        } else {
            for (i in 0 until len) {
                val g = (i + 1).toFloat() / len
                for (c in 0 until ch) buf[i * ch + c] *= g
            }
        }
    }

    /** Safety limiter after 3D rendering (ear responses can add a little level). */
    private fun limit(buf: FloatArray, frames: Int) {
        val ch = outChannels
        var env = limiterEnv
        for (i in 0 until frames) {
            var peak = 0f
            for (c in 0 until ch) peak = max(peak, abs(buf[i * ch + c]))
            env = if (peak > env) peak else env + (peak - env) * limiterRelease
            if (env > CEILING) {
                val g = CEILING / env
                for (c in 0 until ch) buf[i * ch + c] *= g
            }
        }
        limiterEnv = env
    }

    /** Standard fold-down of 5.1 / 7.1 to stereo (Android channel order). */
    private fun downmix(input: FloatArray, frames: Int, out: FloatArray) {
        val ch = inChannels
        for (i in 0 until frames) {
            val o = i * ch
            var l = input[o]
            var r = if (ch > 1) input[o + 1] else input[o]
            if (ch >= 6) {
                val centre = input[o + 2] * 0.7071f
                val lfe = input[o + 3] * 0.5f
                l += centre + lfe + input[o + 4] * 0.7071f
                r += centre + lfe + input[o + 5] * 0.7071f
                if (ch >= 8) {
                    l += input[o + 6] * 0.7071f
                    r += input[o + 7] * 0.7071f
                }
                l *= 0.5f
                r *= 0.5f
            }
            out[i * 2] = l
            out[i * 2 + 1] = r
        }
    }

    companion object {
        private const val CEILING = 0.97f
    }
}
