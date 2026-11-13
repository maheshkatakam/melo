package com.maheshkatakam.melo.lyrics.ai

import android.content.res.AssetManager

/** Thin JNI wrapper over whisper.cpp (see app/src/main/cpp/melo_whisper.cpp). */
object WhisperNative {
    external fun initFromAsset(assetManager: AssetManager, path: String): Long
    external fun initFromFile(path: String): Long
    external fun free(handle: Long)

    /**
     * @param language a Whisper language code ("te", "hi"…) or "auto"
     * @param prompt optional text the model treats as what came before (steers script and style)
     * @param beamSize 1 = greedy; more tries several guesses at once (slower, fewer mistakes)
     * @param maxLen longest line in bytes before it's split (0 = the model's own phrases)
     * @param retries let whisper retry unsure stretches with more randomness
     */
    external fun transcribe(
        handle: Long,
        samples: FloatArray,
        threads: Int,
        translate: Boolean,
        language: String,
        prompt: String?,
        beamSize: Int,
        maxLen: Int,
        retries: Boolean,
    ): Int

    /** Language probabilities averaged over 30 s windows starting at [offsetsSec]; index = language id. */
    external fun languageProbs(handle: Long, samples: FloatArray, threads: Int, offsetsSec: IntArray): FloatArray
    external fun languageCode(id: Int): String

    external fun cancel(handle: Long)
    external fun progress(handle: Long): Int
    external fun segmentCount(handle: Long): Int
    external fun segmentText(handle: Long, index: Int): ByteArray
    external fun segmentStartMs(handle: Long, index: Int): Long
    external fun segmentEndMs(handle: Long, index: Int): Long
    external fun segmentNoSpeechProb(handle: Long, index: Int): Float
    external fun detectedLanguage(handle: Long): String
}
