package com.maheshkatakam.melo.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Plugs Melo's [SoundPipeline] into the player. When nothing is switched on, stereo audio
 * passes through untouched.
 */
@OptIn(UnstableApi::class)
class MoodAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var config = SoundPipeline.Config()

    /** Ear-response data for 3D sound; set once at start-up. */
    @Volatile
    var hrtf: HrtfSet? = null

    /**
     * When true, stereo is expanded to 5.1 for the phone's own spatial audio, and real
     * surround files are left alone for it. Read when playback (re)starts.
     */
    @Volatile
    var surroundOut: Boolean = false

    private var pipeline: SoundPipeline? = null
    private var inCh = 2
    private var outCh = 2
    private var rate = 0
    private var inBuf = FloatArray(0)
    private var outBuf = FloatArray(0)

    fun setConfig(value: SoundPipeline.Config) {
        config = value
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        val out = when (inputAudioFormat.channelCount) {
            1, 2 -> if (surroundOut) 6 else 2
            6, 8 -> if (surroundOut) return AudioProcessor.AudioFormat.NOT_SET else 2
            else -> return AudioProcessor.AudioFormat.NOT_SET
        }
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, out, C.ENCODING_PCM_16BIT)
    }

    override fun onFlush() {
        val input = inputAudioFormat
        val output = outputAudioFormat
        val current = pipeline
        if (current == null || rate != input.sampleRate || inCh != input.channelCount || outCh != output.channelCount) {
            rate = input.sampleRate
            inCh = input.channelCount
            outCh = output.channelCount
            pipeline = SoundPipeline(rate, inCh, outCh, hrtf).also { it.config = config }
        } else {
            current.reset()
        }
    }

    override fun onReset() {
        pipeline = null
        rate = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        val frames = bytes / (2 * inCh)
        if (frames == 0) return
        val engine = pipeline
        val out = replaceOutputBuffer(frames * 2 * outCh)
        if (engine == null) {
            // Shouldn't happen (flush always comes first); keep the audio flowing regardless.
            if (inCh == outCh) out.put(inputBuffer) else inputBuffer.position(inputBuffer.limit())
            out.flip()
            return
        }
        engine.config = config
        if (engine.isTransparent) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        if (inBuf.size < frames * inCh) inBuf = FloatArray(frames * inCh)
        if (outBuf.size < frames * outCh) outBuf = FloatArray(frames * outCh)
        val src = inBuf
        val dst = outBuf
        var pos = inputBuffer.position()
        for (i in 0 until frames * inCh) {
            src[i] = inputBuffer.getShort(pos) * SCALE
            pos += 2
        }
        engine.process(src, frames, dst)
        for (i in 0 until frames * outCh) {
            val s = (dst[i] * 32767f).toInt()
            out.putShort((if (s > 32767) 32767 else if (s < -32768) -32768 else s).toShort())
        }
        inputBuffer.position(inputBuffer.position() + frames * 2 * inCh)
        out.flip()
    }

    companion object {
        private const val SCALE = 1f / 32768f
    }
}
