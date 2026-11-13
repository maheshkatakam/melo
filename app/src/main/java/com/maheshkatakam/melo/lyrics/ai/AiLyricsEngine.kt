package com.maheshkatakam.melo.lyrics.ai

import android.content.Context
import android.os.Build
import android.util.Log
import com.maheshkatakam.melo.data.SettingsRepository
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

/**
 * What the AI heard.
 * @param original the words in the song's own language and script (null for English songs)
 * @param english English lyrics, when the AI wrote them itself
 * @param language Whisper language code
 */
data class AiResult(val original: Lyrics?, val english: Lyrics?, val language: String, val accurateModel: Boolean)

/** The song was readable but the AI heard nothing it could write down. */
class NoSingingException(message: String) : Exception(message)

enum class AiStage { SEARCHING_ONLINE, DECODING, LOADING_MODEL, DETECTING_LANGUAGE, LISTENING }

/**
 * Offline lyrics with an on-device Whisper speech model (whisper.cpp).
 *
 * 1. Work out the language by listening to several parts of the song (not just the intro),
 *    choosing only among the languages you said your songs are in. Or use the one you picked.
 * 2. Write down the words in that language and its own script, with timing.
 * 3. English comes from translating those words on the phone (see LyricsRepository).
 *
 * Built-in model: Whisper "small". Optional download: Whisper "large-v3-turbo", which is far
 * better at Indian languages (tested on Telugu, Hindi and Tamil songs).
 */
class AiLyricsEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    val models: AiModelStore,
    private val settings: SettingsRepository,
) {

    val modelAsset = "models/ggml-small-q5_1.bin"

    /** Why AI lyrics can't run on this phone, or null if it can. */
    val unavailableReason: String? by lazy { checkSupport() }
    val isSupported: Boolean get() = unavailableReason == null

    private val lock = Mutex()
    private var handle = 0L
    private var loadedModel: String? = null
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

    /** True when the next song will use the downloaded accurate model. */
    fun usesAccurateModel(): Boolean = settings.settings.value.accurateLyricsModel && models.readyFile() != null

    /**
     * Runs the full pipeline for one song. Only one song is processed at a time; callers
     * queue up behind the lock. Cancel the calling coroutine to stop.
     *
     * @param language a language you picked for this song, or null to work it out
     */
    suspend fun transcribe(
        song: Song,
        language: String?,
        canTranslateText: (String) -> Boolean,
        onProgress: (AiStage, Int) -> Unit,
    ): AiResult = lock.withLock {
        check(isSupported) { unavailableReason ?: "Unsupported" }
        releaseJob?.cancel()
        try {
            onProgress(AiStage.DECODING, 0)
            val samples = withContext(Dispatchers.Default) {
                AudioDecoder.decode(context, song.uri) { p -> onProgress(AiStage.DECODING, (p * 100).toInt()) }
            }
            if (samples.size < AudioDecoder.TARGET_RATE * 3) throw NoSingingException("The song is too short.")

            onProgress(AiStage.LOADING_MODEL, 0)
            val accurateFile = if (settings.settings.value.accurateLyricsModel) models.readyFile() else null
            val h = withContext(Dispatchers.IO) { ensureModel(accurateFile) }

            val lang = language ?: run {
                onProgress(AiStage.DETECTING_LANGUAGE, 0)
                detectLanguage(h, samples)
            }
            val english = lang == "en"
            val prompt = SongLanguages.get(lang)?.prompt?.takeIf { it.isNotEmpty() }

            // Languages the phone's translator can't handle (Malayalam, Punjabi…) get a
            // second pass in which the AI writes English itself.
            val secondPass = !english && !canTranslateText(lang)
            val passes = if (secondPass) 2 else 1

            val lyrics = Lyrics(listen(h, samples, lang, prompt, translate = false, pass = 0, passes = passes, onProgress))
            if (lyrics.lines.isEmpty()) throw NoSingingException("No singing could be recognised in this song.")
            val translated = if (secondPass) {
                Lyrics(listen(h, samples, lang, null, translate = true, pass = 1, passes = passes, onProgress)).takeIf { it.lines.isNotEmpty() }
            } else {
                null
            }
            AiResult(
                original = if (english) null else lyrics,
                english = if (english) lyrics else translated,
                language = lang,
                accurateModel = loadedModel != modelAsset,
            )
        } finally {
            scheduleRelease()
        }
    }

    private suspend fun listen(
        h: Long,
        samples: FloatArray,
        lang: String,
        prompt: String?,
        translate: Boolean,
        pass: Int,
        passes: Int,
        onProgress: (AiStage, Int) -> Unit,
    ): List<LyricLine> {
        onProgress(AiStage.LISTENING, pass * 100 / passes)
        val rc = coroutineScope {
            val poller = launch {
                while (isActive) {
                    onProgress(AiStage.LISTENING, (pass * 100 + WhisperNative.progress(h)) / passes)
                    delay(400)
                }
            }
            try {
                // Lines of about 45 letters. UTF-8 needs 3 bytes per letter in Indian scripts.
                val maxLen = if (!translate && ScriptCheck.knows(lang)) 130 else 48
                val retries = loadedModel == modelAsset // see melo_whisper.cpp
                runInterruptible(h) {
                    WhisperNative.transcribe(h, samples, threads(), translate, lang, prompt, BEAM_SIZE, maxLen, retries)
                }
            } finally {
                poller.cancel()
            }
        }
        if (rc != 0) error("The AI couldn't process this song (code $rc).")
        return collectLines(h, if (translate) "en" else lang, prompt)
    }

    /**
     * Listens to three 30-second stretches spread through the song (intros are often
     * instrumental) and picks the most likely of your song languages.
     */
    private suspend fun detectLanguage(h: Long, samples: FloatArray): String {
        val seconds = samples.size / AudioDecoder.TARGET_RATE
        val offsets = if (seconds <= 45) {
            intArrayOf(0)
        } else {
            intArrayOf(20, 45, 70).map { pct -> (seconds * pct / 100).coerceAtMost(seconds - 30) }.distinct().toIntArray()
        }
        val probs = runInterruptibleArray(h) { WhisperNative.languageProbs(h, samples, threads(), offsets) }
        val wanted = settings.settings.value.songLanguages.ifEmpty { SongLanguages.defaultCodes }
        var best = "en"
        var bestP = -1f
        for (id in probs.indices) {
            val code = WhisperNative.languageCode(id)
            if (code !in wanted) continue
            if (probs[id] > bestP) {
                bestP = probs[id]
                best = code
            }
        }
        Log.i(TAG, "Language: $best (p=$bestP) from windows ${offsets.joinToString()}")
        return best
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

    private suspend fun runInterruptibleArray(h: Long, block: () -> FloatArray): FloatArray {
        var out = FloatArray(0)
        runInterruptible(h) {
            out = block()
            0
        }
        return out
    }

    /** Loads the accurate model if [file] is given, otherwise the built-in one. */
    private fun ensureModel(file: File?): Long {
        val key = file?.path ?: modelAsset
        if (handle != 0L && loadedModel == key) return handle
        if (handle != 0L) {
            WhisperNative.free(handle)
            handle = 0L
        }
        var h = if (file != null) WhisperNative.initFromFile(file.path) else 0L
        var loaded = key
        if (h == 0L) {
            if (file != null) Log.w(TAG, "Accurate model failed to load; using the built-in one")
            h = WhisperNative.initFromAsset(context.assets, modelAsset)
            loaded = modelAsset
        }
        if (h == 0L) error("Couldn't load the AI model.")
        handle = h
        loadedModel = loaded
        return h
    }

    /** Frees the model's memory (400–800 MB) when the AI hasn't been used for a while. */
    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(90_000)
            lock.withLock {
                if (handle != 0L) {
                    WhisperNative.free(handle)
                    handle = 0L
                    loadedModel = null
                }
            }
        }
    }

    private fun threads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    private fun collectLines(h: Long, language: String, prompt: String?): List<LyricLine> {
        data class Seg(val start: Long, val end: Long, val text: String, val scriptShare: Float)

        val segs = mutableListOf<Seg>()
        val n = WhisperNative.segmentCount(h)
        for (i in 0 until n) {
            val raw = clean(String(WhisperNative.segmentText(h, i), Charsets.UTF_8))
            // Over instrumental parts the model can get stuck repeating one syllable
            // ("కాగాగాగాగా…"). Mostly-stuck lines are dropped; a little repetition is trimmed.
            val text = raw.replace(STUCK, "$1$1")
            if (text.isEmpty() || text.length * 2 < raw.length) continue
            if (prompt != null && text.trimEnd('.', '।', ' ') == prompt.trimEnd('.', '।', ' ')) continue
            val noSpeech = WhisperNative.segmentNoSpeechProb(h, i)
            if (noSpeech > 0.75f && text.length < 25) continue
            if (HALLUCINATIONS.containsMatchIn(text)) continue
            val start = WhisperNative.segmentStartMs(h, i)
            val end = WhisperNative.segmentEndMs(h, i).coerceAtLeast(start)
            val share = ScriptCheck.share(text, language)
            splitLong(text).forEach { (part, from, to) ->
                segs += Seg(start + (end - start) * from / to.coerceAtLeast(1), end, part, share)
            }
        }
        // When most lines are in the song's script, lines in other scripts are junk the model
        // wrote over instrumental parts ("Redmi 4", stray Cyrillic…).
        val kept = if (ScriptCheck.knows(language) && segs.count { it.scriptShare >= 0.6f } * 2 >= segs.size) {
            segs.filter { it.scriptShare >= 0.6f }
        } else {
            segs
        }
        // Collapse the "same line over and over" loops the model sometimes falls into.
        val out = mutableListOf<LyricLine>()
        var repeat = 0
        for (s in kept) {
            val prev = out.lastOrNull()
            if (prev != null && prev.text.equals(s.text, ignoreCase = true)) {
                repeat++
                if (repeat >= 2) continue
            } else {
                repeat = 0
            }
            out += LyricLine(s.start, s.text)
        }
        return out
    }

    /**
     * Splits a long line at the word gap nearest its middle until each piece is about 45
     * letters or less. Returns (piece, letters before it, total letters) so the caller can
     * spread the timing over the pieces.
     */
    private fun splitLong(text: String): List<Triple<String, Int, Int>> {
        val total = text.length
        val out = mutableListOf<Triple<String, Int, Int>>()
        fun go(part: String, offset: Int) {
            val words = part.split(' ')
            if (part.length <= MAX_LINE || words.size < 4) {
                out += Triple(part, offset, total)
                return
            }
            var bestCut = -1
            var pos = 0
            for (w in 0 until words.size - 1) {
                pos += words[w].length + 1
                if (bestCut < 0 || kotlin.math.abs(pos - part.length / 2) < kotlin.math.abs(bestCut - part.length / 2)) bestCut = pos
            }
            go(part.substring(0, bestCut).trim(), offset)
            go(part.substring(bestCut).trim(), offset + bestCut)
        }
        go(text, 0)
        return out
    }

    private fun clean(text: String): String = text
        .replace(Regex("""\[[^\]]*]|\([^)]*(music|applause|laugh|instrumental|singing)[^)]*\)""", RegexOption.IGNORE_CASE), "")
        .replace("♪", "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trimStart('-', ' ', ',')
        .trim()

    companion object {
        private const val TAG = "MeloAI"
        private const val BEAM_SIZE = 1
        private const val MAX_LINE = 45
        /** The same 1–8 characters four or more times in a row: the model got stuck. */
        private val STUCK = Regex("""(.{1,8}?)\1{3,}""")
        private val HALLUCINATIONS = Regex(
            """thank(s| you) (so much )?for watching|please subscribe|like and subscribe|subtitles? by|amara\.org|""" +
                """transcribed by|translated by|www\.|\.com\b|सब्सक्राइब|సబ్‌?స్క్రైబ్|சப்ஸ்க்ரைப்""",
            RegexOption.IGNORE_CASE,
        )
    }
}
