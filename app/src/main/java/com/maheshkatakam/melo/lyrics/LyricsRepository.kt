package com.maheshkatakam.melo.lyrics

import android.content.Context
import android.net.Uri
import com.maheshkatakam.melo.data.MusicRepository
import com.maheshkatakam.melo.data.SettingsRepository
import com.maheshkatakam.melo.data.Song
import com.maheshkatakam.melo.data.db.LyricsDao
import com.maheshkatakam.melo.data.db.LyricsEntity
import com.maheshkatakam.melo.lyrics.ai.AiLyricsEngine
import com.maheshkatakam.melo.lyrics.ai.AiStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AiJob(val songId: Long, val title: String, val stage: AiStage, val percent: Int)

/**
 * Where lyrics come from, in order:
 *  1. A .lrc file next to the song, or lyrics stored inside the song file
 *  2. Lyrics you import or paste
 *  3. The on-device AI, which listens and writes English lyrics
 * Non-English text lyrics are translated on the phone once the language pack is present.
 */
class LyricsRepository(
    private val context: Context,
    private val dao: LyricsDao,
    private val music: MusicRepository,
    val translator: LyricsTranslator,
    val engine: AiLyricsEngine,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val reader = EmbeddedLyricsReader(context)
    private val scanLock = Mutex()

    private val _aiJob = MutableStateFlow<AiJob?>(null)
    val aiJob: StateFlow<AiJob?> = _aiJob.asStateFlow()

    private val _aiQueue = MutableStateFlow<List<Long>>(emptyList())
    val aiQueue: StateFlow<List<Long>> = _aiQueue.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val aiLyricsCount: Flow<Int> = dao.observeAiCount()

    private var worker: Job? = null
    private var currentJob: Job? = null
    private var currentSongId: Long? = null

    init {
        scope.launch { translator.refreshDownloaded() }
    }

    fun observe(songId: Long): Flow<LyricsEntity?> = dao.observe(songId)

    /** Looks for stored lyrics the first time a song is shown or played. */
    suspend fun ensureScanned(song: Song) {
        scanLock.withLock {
            val existing = dao.get(song.id)
            if (existing != null) {
                maybeTranslate(existing)
                return
            }
            val found = withContext(Dispatchers.IO) {
                reader.readSidecar(song)?.let { it to LyricsSource.LRC_FILE }
                    ?: reader.readEmbedded(song)?.let { it to LyricsSource.EMBEDDED }
            }
            if (found == null) {
                dao.upsert(LyricsEntity(song.id, LyricsSource.NONE, null, null, null, now()))
            } else {
                storeOriginal(song.id, found.first, found.second)
            }
        }
    }

    private suspend fun storeOriginal(songId: Long, text: String, source: String): Boolean {
        val lyrics = LrcParser.parse(text)
        if (lyrics.isBlank()) return false
        val language = translator.detectLanguage(lyrics.plainText)
        val serialized = LrcParser.serialize(lyrics)
        val english = if (isEnglish(language)) serialized else null
        val entity = LyricsEntity(songId, source, language, serialized, english, now())
        dao.upsert(entity)
        maybeTranslate(entity)
        return true
    }

    /** Translates stored lyrics if the language pack is already on the phone. */
    private suspend fun maybeTranslate(entity: LyricsEntity) {
        if (entity.english != null || entity.original == null) return
        val code = translator.translatable(entity.language) ?: return
        if (!translator.isReady(code)) return
        val translated = runCatching {
            translator.translateToEnglish(LrcParser.parse(entity.original), code)
        }.getOrNull() ?: return
        dao.upsert(entity.copy(english = LrcParser.serialize(translated), updatedAt = now()))
    }

    fun downloadPackAndTranslate(songId: Long, language: String) {
        scope.launch {
            if (!translator.isOnline()) {
                _messages.tryEmit("Connect to the internet once to download the ${languageName(language)} pack.")
                return@launch
            }
            if (!translator.download(language)) {
                _messages.tryEmit("Couldn't download the ${languageName(language)} pack. Try again on Wi-Fi.")
                return@launch
            }
            dao.get(songId)?.let { maybeTranslate(it) }
        }
    }

    fun onNowPlaying(songId: Long) {
        scope.launch {
            val song = music.song(songId) ?: return@launch
            ensureScanned(song)
            if (settings.settings.value.autoAiLyrics && engine.isSupported) {
                val entity = dao.get(songId)
                if (entity?.english == null) requestAi(song)
            }
        }
    }

    suspend fun importFile(song: Song, uri: Uri): Boolean {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
        }
        if (text.isNullOrBlank()) return false
        return storeOriginal(song.id, text, LyricsSource.IMPORTED)
    }

    suspend fun paste(song: Song, text: String): Boolean = storeOriginal(song.id, text, LyricsSource.PASTED)

    suspend fun clear(song: Song) {
        dao.upsert(LyricsEntity(song.id, LyricsSource.NONE, null, null, null, now()))
    }

    suspend fun rescan(song: Song) {
        dao.delete(song.id)
        ensureScanned(song)
    }

    // ---- AI queue --------------------------------------------------------------------------

    fun requestAi(song: Song) {
        if (!engine.isSupported) {
            _messages.tryEmit(engine.unavailableReason ?: "AI lyrics aren't available on this phone.")
            return
        }
        if (song.id == currentSongId || song.id in _aiQueue.value) return
        _aiQueue.value = _aiQueue.value + song.id
        ensureWorker()
    }

    fun requestAiForAll(songs: List<Song>) {
        if (!engine.isSupported) {
            _messages.tryEmit(engine.unavailableReason ?: "AI lyrics aren't available on this phone.")
            return
        }
        scope.launch {
            val done = dao.songsWithEnglish().toSet()
            val todo = songs.map { it.id }.filter { it !in done && it != currentSongId && it !in _aiQueue.value }
            _aiQueue.value = _aiQueue.value + todo
            ensureWorker()
        }
    }

    fun cancelAi(songId: Long) {
        _aiQueue.value = _aiQueue.value - songId
        if (currentSongId == songId) currentJob?.cancel()
    }

    fun cancelAllAi() {
        _aiQueue.value = emptyList()
        currentJob?.cancel()
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val next = _aiQueue.value.firstOrNull() ?: break
                _aiQueue.value = _aiQueue.value.drop(1)
                val song = music.song(next) ?: continue
                currentSongId = next
                val job = launch { runAi(song) }
                currentJob = job
                job.join()
                currentJob = null
                currentSongId = null
            }
        }
    }

    private suspend fun runAi(song: Song) {
        _aiJob.value = AiJob(song.id, song.title, AiStage.DECODING, 0)
        try {
            val result = engine.transcribe(song) { stage, percent ->
                _aiJob.value = AiJob(song.id, song.title, stage, percent)
            }
            val existing = dao.get(song.id)
            dao.upsert(
                LyricsEntity(
                    songId = song.id,
                    source = LyricsSource.AI,
                    language = if (existing?.original != null) existing.language ?: result.language else result.language,
                    original = existing?.original,
                    english = LrcParser.serialize(result.english),
                    updatedAt = now(),
                ),
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            _messages.tryEmit("${song.title}: ${t.message ?: "AI lyrics failed"}")
        } finally {
            _aiJob.value = null
        }
    }

    private fun now() = System.currentTimeMillis()
}
