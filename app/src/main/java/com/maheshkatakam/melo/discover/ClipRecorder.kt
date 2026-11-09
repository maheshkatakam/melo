package com.maheshkatakam.melo.discover

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File

/** Records a short AAC clip from the microphone for song identification. */
class ClipRecorder(private val context: Context) {

    val dir: File = File(context.filesDir, "discover").apply { mkdirs() }

    /**
     * Records [seconds] of audio. [onTick] gets the seconds left. Cancelling the coroutine
     * stops the recording and deletes the file.
     */
    suspend fun record(seconds: Int, onTick: (Int) -> Unit): File {
        val file = File(dir, "clip_${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        var finished = false
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioChannels(1)
            recorder.setAudioSamplingRate(44_100)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            for (left in seconds downTo 1) {
                onTick(left)
                delay(1_000)
            }
            onTick(0)
            recorder.stop()
            finished = true
            return file
        } catch (c: CancellationException) {
            file.delete()
            throw c
        } catch (t: Throwable) {
            file.delete()
            throw IllegalStateException("Couldn't use the microphone. Is another app recording?", t)
        } finally {
            if (!finished) runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }
}
