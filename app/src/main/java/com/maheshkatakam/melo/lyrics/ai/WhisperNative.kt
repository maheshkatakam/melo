package com.maheshkatakam.melo.lyrics.ai

import android.content.res.AssetManager

/** Thin JNI wrapper over whisper.cpp (see app/src/main/cpp/melo_whisper.cpp). */
object WhisperNative {
    external fun initFromAsset(assetManager: AssetManager, path: String): Long
    external fun free(handle: Long)
    external fun transcribe(handle: Long, samples: FloatArray, threads: Int, translate: Boolean): Int
    external fun cancel(handle: Long)
    external fun progress(handle: Long): Int
    external fun segmentCount(handle: Long): Int
    external fun segmentText(handle: Long, index: Int): ByteArray
    external fun segmentStartMs(handle: Long, index: Int): Long
    external fun segmentEndMs(handle: Long, index: Int): Long
    external fun segmentNoSpeechProb(handle: Long, index: Int): Float
    external fun detectedLanguage(handle: Long): String
}
