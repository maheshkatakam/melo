package com.maheshkatakam.melo.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Plugs [MoodDsp] into the player. With the Normal mood the audio passes through untouched.
 */
@OptIn(UnstableApi::class)
class MoodAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var profile: SoundProfile = SoundProfile.FLAT
    private var dsp: MoodDsp? = null
    private var channels = 2
    private var scratch = FloatArray(0)

    fun setProfile(value: SoundProfile) {
        profile = value
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // Mono and stereo are processed; surround files play as they are.
        if (inputAudioFormat.channelCount !in 1..2) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush() {
        val format = inputAudioFormat
        channels = format.channelCount
        val current = dsp
        if (current == null || currentRate != format.sampleRate) {
            dsp = MoodDsp(format.sampleRate).also { it.setProfile(profile) }
            currentRate = format.sampleRate
        } else {
            current.reset()
        }
    }

    private var currentRate = 0

    override fun onReset() {
        dsp = null
        currentRate = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val out = replaceOutputBuffer(bytes)
        val engine = dsp
        val wanted = profile
        engine?.setProfile(wanted)
        if (engine == null || engine.isTransparent) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        val frames = bytes / (2 * channels)
        if (scratch.size < frames * 2) scratch = FloatArray(frames * 2)
        val buf = scratch
        var pos = inputBuffer.position()
        if (channels == 2) {
            for (i in 0 until frames * 2) {
                buf[i] = inputBuffer.getShort(pos) * SCALE
                pos += 2
            }
        } else {
            for (i in 0 until frames) {
                val v = inputBuffer.getShort(pos) * SCALE
                buf[i * 2] = v
                buf[i * 2 + 1] = v
                pos += 2
            }
        }
        engine.process(buf, frames)
        if (channels == 2) {
            for (i in 0 until frames * 2) out.putShort(toShort(buf[i]))
        } else {
            for (i in 0 until frames) out.putShort(toShort((buf[i * 2] + buf[i * 2 + 1]) * 0.5f))
        }
        inputBuffer.position(inputBuffer.position() + frames * 2 * channels)
        out.flip()
    }

    private fun toShort(v: Float): Short {
        val s = (v * 32767f).toInt()
        return (if (s > 32767) 32767 else if (s < -32768) -32768 else s).toShort()
    }

    companion object {
        private const val SCALE = 1f / 32768f
    }
}
