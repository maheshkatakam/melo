package com.maheshkatakam.melo.lyrics.ai

import android.content.Context
import android.os.Build
import android.util.Log
import com.maheshkatakam.melo.data.Song
import com.maheshkatakam.melo.lyrics.LyricLine
import com.maheshkatakam.melo.lyrics.Lyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class AiResult(val english: Lyrics, val language: String)

/** The song was readable but the AI heard nothing it could write down. */
class NoSingingException(message: String) : Exception(message)

enum class AiStage { DECODING, LOADING_MODEL, LISTENING }

/**
 * Offline lyrics: an on-device Whisper speech model listens to the song and writes the
 * words directly in English (it translates from ~99 languages, including Indian ones).
 */
class AiLyricsEngine(private val context: Context, private val scope: CoroutineScope) {

    val modelAsset = "models/ggml-small-q5_1.bin"

    /** Why AI lyrics can't run on this phone, or null if it can. */
    val unavailableReason: String? by lazy { checkSupport() }
    val isSupported: Boolean get() = unavailableReason == null

    private val lock = Mutex()
    private var handle = 0L
    private var releaseJob: Job? = null

    private fun checkSupport(): String? {
        if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) return "AI lyrics need a 64-bit phone."
        val cpuinfo = runCatching { File("/proc/cpuinfo").readText() }.getOrNull()
        if (cpuinfo != null) {
            val features = cpuinfo.lineSequence()
                .filter { it.startsWith("Features", ignoreCase = true) }
                .joinToString(" ")
            if (features.isNotBlank() && (!features.contains("asimdhp") || !features.contains("asimddp"))) {
                return "This phone's processor is too old for the on-device AI model."
            }
        }
        val hasModel = runCatching { context.assets.open(modelAsset).use { true } }.getOrDefault(false)
        if (!hasModel) return "The AI model isn't included in this build."
        return try {
            System.loadLibrary("melo_whisper")
            null
        } catch (t: Throwable) {
            Log.e(TAG, "Native library failed to load", t)
            "The AI engine couldn't start on this phone."
        }
    }

    /**
     * Runs the full pipeline for one song. Only one song is processed at a time; callers
     * queue up behind the lock. Cancel the calling coroutine to stop.
     */
    suspend fun transcribe(song: Song, onProgress: (AiStage, Int) -> Unit): AiResult = lock.withLock {
        check(isSupported) { unavailableReason ?: "Unsupported" }
        releaseJob?.cancel()
        try {
            onProgress(AiStage.DECODING, 0)
            val samples = withContext(Dispatchers.Default) {
                AudioDecoder.decode(context, song.uri) { p -> onProgress(AiStage.DECODING, (p * 100).toInt()) }
            }
            if (samples.size < AudioDecoder.TARGET_RATE) throw NoSingingException("The song is too short.")

            onProgress(AiStage.LOADING_MODEL, 0)
            val h = withContext(Dispatchers.IO) { ensureModel() }

            onProgress(AiStage.LISTENING, 0)
            val rc = coroutineScope {
                val poller = launch {
                    while (isActive) {
                        onProgress(AiStage.LISTENING, WhisperNative.progress(h))
                        delay(400)
                    }
                }
                try {
                    runInterruptible(h) { WhisperNative.transcribe(h, samples, threads(), true) }
                } finally {
                    poller.cancel()
                }
            }
            if (rc != 0) error("The AI couldn't process this song (code $rc).")

            val language = WhisperNative.detectedLanguage(h).ifBlank { "und" }
            val lines = collectLines(h)
            if (lines.isEmpty()) throw NoSingingException("No singing could be recognised in this song.")
            AiResult(Lyrics(lines), language)
        } finally {
            scheduleRelease()
        }
    }

    /** Runs a blocking native call; cancelling the coroutine aborts whisper. */
    private suspend fun runInterruptible(h: Long, block: () -> Int): Int = coroutineScope {
        val watcher = launch {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                WhisperNative.cancel(h)
            }
        }
        try {
            withContext(Dispatchers.Default) { block() }
        } finally {
            watcher.cancel()
        }
    }

    private fun ensureModel(): Long {
        if (handle != 0L) return handle
        val h = WhisperNative.initFromAsset(context.assets, modelAsset)
        if (h == 0L) error("Couldn't load the AI model.")
        handle = h
        return h
    }

    /** Frees ~400 MB of memory when the AI hasn't been used for a while. */
    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(90_000)
            lock.withLock {
                if (handle != 0L) {
                    WhisperNative.free(handle)
                    handle = 0L
                }
            }
        }
    }

    private fun threads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    private fun collectLines(h: Long): List<LyricLine> {
        val raw = mutableListOf<LyricLine>()
        val n = WhisperNative.segmentCount(h)
        for (i in 0 until n) {
            val text = clean(String(WhisperNative.segmentText(h, i), Charsets.UTF_8))
            if (text.isEmpty()) continue
            val noSpeech = WhisperNative.segmentNoSpeechProb(h, i)
            if (noSpeech > 0.75f && text.length < 25) continue
            if (HALLUCINATIONS.containsMatchIn(text)) continue
            raw += LyricLine(WhisperNative.segmentStartMs(h, i), text)
        }
        // Collapse the "same line over and over" loops the model sometimes falls into.
        val out = mutableListOf<LyricLine>()
        var repeat = 0
        for (line in raw) {
            val prev = out.lastOrNull()
            if (prev != null && prev.text.equals(line.text, ignoreCase = true)) {
                repeat++
                if (repeat >= 2) continue
            } else {
                repeat = 0
            }
            out += line
        }
        return out
    }

    private fun clean(text: String): String = text
        .replace(Regex("""\[[^\]]*]|\([^)]*(music|applause|laugh|instrumental|singing)[^)]*\)""", RegexOption.IGNORE_CASE), "")
        .replace("♪", "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trimStart('-', ' ')
        .trim()

    companion object {
        private const val TAG = "MeloAI"
        private val HALLUCINATIONS = Regex(
            """thank(s| you) (so much )?for watching|please subscribe|like and subscribe|subtitles? by|amara\.org|transcribed by|translated by|www\.|\.com\b""",
            RegexOption.IGNORE_CASE,
        )
    }
}
