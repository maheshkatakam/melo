package com.maheshkatakam.melo.lyrics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import com.maheshkatakam.melo.data.LibraryRepository
import com.maheshkatakam.melo.data.MusicRepository
import com.maheshkatakam.melo.data.SettingsRepository
import com.maheshkatakam.melo.data.Song
import com.maheshkatakam.melo.data.db.LyricsDao
import com.maheshkatakam.melo.data.db.LyricsEntity
import com.maheshkatakam.melo.data.db.LyricsStatus
import com.maheshkatakam.melo.lyrics.ai.AiLyricsEngine
import com.maheshkatakam.melo.lyrics.ai.AiStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AiJob(val songId: Long, val title: String, val stage: AiStage, val percent: Int) {
    /** Overall progress for this song, 0..100 (reading the file is the first ~15%). */
    val overallPercent: Int
        get() = when (stage) {
            AiStage.SEARCHING_ONLINE -> 0
            AiStage.DECODING -> percent * 15 / 100
            AiStage.LOADING_MODEL -> 15
            AiStage.DETECTING_LANGUAGE -> 18
            AiStage.LISTENING -> 20 + percent * 80 / 100
        }
}

/**
 * Where lyrics come from, in order:
 *  1. A .lrc file next to the song, or lyrics stored inside the song file
 *  2. Lyrics you import or paste
 *  3. The real lyrics from the free LRCLIB database, if the phone is online (looked up once)
 *  4. The on-device AI, which listens and writes the words down
 * Non-English text lyrics are translated on the phone once the language pack is present.
 *
 * AI work runs one song at a time from a queue. Songs you ask for directly (or that are
 * playing with "as I listen" on) jump to the front and always run; songs added by
 * "Get all lyrics" can wait for the charger. The queue is saved, so it picks up again
 * after the phone or the app restarts.
 */
class LyricsRepository(
    private val context: Context,
    private val dao: LyricsDao,
    private val music: MusicRepository,
    private val library: LibraryRepository,
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

    /** True while "Get all lyrics" is paused until the phone is plugged in. */
    private val _waitingForCharger = MutableStateFlow(false)
    val waitingForCharger: StateFlow<Boolean> = _waitingForCharger.asStateFlow()

    /** Songs that skip the line and ignore the charging rule. */
    private val priority = MutableStateFlow<Set<Long>>(emptySet())

    /** Songs the user asked for directly, so failures are worth telling them about. */
    private val notify = MutableStateFlow<Set<Long>>(emptySet())

    /** Songs where you asked for the AI specifically, so the online lookup is skipped. */
    private val aiOnly = MutableStateFlow<Set<Long>>(emptySet())

    /** Languages picked for individual songs ("this song is in Telugu"). */
    private val songLanguagePrefs = context.getSharedPreferences("melo_song_languages", Context.MODE_PRIVATE)

    fun pickedLanguage(songId: Long): String? = songLanguagePrefs.getString(songId.toString(), null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val aiLyricsCount: Flow<Int> = dao.observeAiCount()

    private val status: StateFlow<List<LyricsStatus>> = dao.observeStatus()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Songs that already have English lyrics (from any source). */
    val englishIds: StateFlow<Set<Long>> = status
        .map { rows -> rows.filter { it.hasEnglish }.map { it.songId }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Songs that have lyrics now or that "Get all lyrics" has finished with. */
    val doneIds: StateFlow<Set<Long>> = status
        .map { rows -> rows.filter { it.hasEnglish || it.source == LyricsSource.AI || it.source == LyricsSource.ONLINE }.map { it.songId }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /**
     * Songs done by the old AI (before Melo 1.0.11): it guessed the language from the intro and
     * often wrote junk for Indian-language songs, or wrongly marked them as having no singing.
     */
    val oldAiCount: Flow<Int> = dao.observeAiCountBefore(NEW_AI_SINCE)

    /** Clears the old AI's lyrics and puts those songs through the new pipeline. */
    fun redoOldAi() {
        scope.launch {
            val ids = dao.aiSongsBefore(NEW_AI_SINCE)
            if (ids.isEmpty()) return@launch
            ids.chunked(500).forEach { dao.deleteAll(it) }
            val set = ids.toSet()
            requestAiForAll(music.songs.value.filter { it.id in set })
        }
    }

    /** Songs the AI tried but heard no singing in (usually instrumentals). */
    val noSingingIds: StateFlow<Set<Long>> = status
        .map { rows -> rows.filter { it.source == LyricsSource.AI_FAILED }.map { it.songId }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private var worker: Job? = null
    private var currentJob: Job? = null
    @Volatile
    private var currentSongId: Long? = null

    init {
        scope.launch { translator.refreshDownloaded() }
        scope.launch { restoreQueue() }
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
                if (entity?.english == null && entity?.source != LyricsSource.AI_FAILED) {
                    requestAi(song, userInitiated = false)
                }
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

    /**
     * One song, straight to the front of the line.
     * @param language the song's language if you picked it ("te"), or null to let the AI decide
     */
    fun requestAi(song: Song, userInitiated: Boolean = true, language: String? = null, useOnline: Boolean = true) {
        if (!useOnline || language != null) aiOnly.update { it + song.id }
        if (!engine.isSupported) {
            if (userInitiated) _messages.tryEmit(engine.unavailableReason ?: "AI lyrics aren't available on this phone.")
            return
        }
        if (language != null) songLanguagePrefs.edit().putString(song.id.toString(), language).apply()
        if (song.id == currentSongId) {
            if (language == null) return
            currentJob?.cancel() // restart it in the chosen language
        }
        priority.update { it + song.id }
        if (userInitiated) notify.update { it + song.id }
        _aiQueue.update { queue -> listOf(song.id) + (queue - song.id) }
        persistQueue()
        ensureWorker()
        LyricsService.start(context)
    }

    /** Every song that doesn't have English lyrics yet, most-listened first. */
    fun requestAiForAll(songs: List<Song> = music.songs.value) {
        if (!engine.isSupported) {
            _messages.tryEmit(engine.unavailableReason ?: "AI lyrics aren't available on this phone.")
            return
        }
        scope.launch {
            val rows = dao.statusList()
            val skip = rows.filter { it.hasEnglish || it.source in DONE_SOURCES }.map { it.songId }.toSet()
            val plays = library.playCounts.value
            val favorites = library.favoriteIds.value
            val ordered = songs
                .filter { it.id !in skip && it.id != currentSongId }
                .sortedWith(
                    compareByDescending<Song> { plays[it.id] ?: 0 }
                        .thenByDescending { it.id in favorites }
                        .thenByDescending { it.dateAddedSec },
                )
                .map { it.id }
            if (ordered.isEmpty()) {
                _messages.tryEmit("All your songs already have English lyrics.")
                return@launch
            }
            var added = 0
            _aiQueue.update { queue ->
                val fresh = ordered.filter { it !in queue }
                added = fresh.size
                queue + fresh
            }
            persistQueue()
            ensureWorker()
            LyricsService.start(context)
            val total = _aiQueue.value.size
            val tail = if (settings.settings.value.aiOnlyWhileCharging && !isCharging()) {
                " It starts when you plug in your charger."
            } else {
                " It keeps going even if you close Melo."
            }
            _messages.tryEmit(
                if (added == 0) "Already working on it: $total songs to go."
                else "Getting English lyrics for $total songs.$tail",
            )
        }
    }

    /** Called when Melo comes to the screen: shows the progress notification if work is under way. */
    fun onAppForeground() {
        if (_aiJob.value != null || _aiQueue.value.isNotEmpty()) LyricsService.start(context)
    }

    /** After file access is granted: look again for .lrc files next to songs that had no lyrics. */
    fun forgetMisses() {
        scope.launch { dao.deleteMisses() }
    }

    fun cancelAi(songId: Long) {
        _aiQueue.update { it - songId }
        priority.update { it - songId }
        notify.update { it - songId }
        persistQueue()
        if (currentSongId == songId) currentJob?.cancel()
    }

    fun cancelAllAi() {
        _aiQueue.value = emptyList()
        priority.value = emptySet()
        notify.value = emptySet()
        persistQueue()
        currentJob?.cancel()
    }

    private fun persistQueue() = settings.saveAiQueue(_aiQueue.value)

    private suspend fun restoreQueue() {
        val saved = settings.loadAiQueue()
        if (saved.isEmpty()) return
        music.loadedOnce.first { it }
        if (!engine.isSupported) return
        val rows = dao.statusList()
        val skip = rows.filter { it.hasEnglish || it.source in DONE_SOURCES }.map { it.songId }.toSet()
        val ids = saved.filter { it !in skip && music.song(it) != null }
        if (ids.isEmpty()) {
            persistQueue()
            return
        }
        _aiQueue.update { queue -> queue + ids.filter { it !in queue } }
        persistQueue()
        ensureWorker()
        LyricsService.start(context)
    }

    @Synchronized
    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val queue = _aiQueue.value
                if (queue.isEmpty()) break
                val urgent = priority.value
                val urgentNext = queue.firstOrNull { it in urgent }
                if (urgentNext == null && settings.settings.value.aiOnlyWhileCharging && !isCharging()) {
                    // The AI waits for the charger, but looking up real lyrics online is quick
                    // and light, so that carries on.
                    if (lookUpNextOnline(queue)) continue
                    _waitingForCharger.value = true
                    delay(15_000)
                    continue
                }
                _waitingForCharger.value = false
                val id: Long = urgentNext ?: queue.first()
                val wasUrgent = urgentNext != null
                val tellUser = id in notify.value
                _aiQueue.update { it - id }
                priority.update { it - id }
                notify.update { it - id }
                persistQueue()

                val song = music.song(id) ?: continue
                currentSongId = id
                val job = launch { process(song, force = wasUrgent, tellUser = tellUser) }
                currentJob = job
                job.join()
                currentJob = null
                currentSongId = null
            }
            _waitingForCharger.value = false
        }
    }

    /**
     * Looks up one queued song online (while the AI waits for the charger). Returns false when
     * there's nothing left to look up or the phone is offline.
     */
    private suspend fun lookUpNextOnline(queue: List<Long>): Boolean {
        if (!settings.settings.value.onlineLyrics || !translator.isOnline()) return false
        val id = queue.firstOrNull { it !in aiOnly.value } ?: return false
        aiOnly.update { it + id } // looked up once; the AI takes it from here if nothing is found
        val song = music.song(id) ?: return true
        ensureScanned(song)
        if (dao.get(id)?.english != null) {
            _aiQueue.update { it - id }
            persistQueue()
            return true
        }
        _aiJob.value = AiJob(id, song.title, AiStage.SEARCHING_ONLINE, 0)
        try {
            val found = findOnlineSafely(song)
            if (found != null && storeOriginal(id, found.text, LyricsSource.ONLINE)) {
                _aiQueue.update { it - id }
                priority.update { it - id }
                notify.update { it - id }
                persistQueue()
                dao.get(id)?.let { translateWhenPossible(it, tellUser = false) }
            }
        } finally {
            _aiJob.value = null
        }
        delay(300) // be polite to the free service
        return true
    }

    private suspend fun process(song: Song, force: Boolean, tellUser: Boolean) {
        // Stored or translatable lyrics may turn up first; no need for the AI then.
        ensureScanned(song)
        val before = dao.get(song.id)
        if (!force && (before?.english != null || before?.source == LyricsSource.AI_FAILED)) return

        val skipOnline = song.id in aiOnly.value
        aiOnly.update { it - song.id }
        if (!skipOnline && settings.settings.value.onlineLyrics && translator.isOnline()) {
            _aiJob.value = AiJob(song.id, song.title, AiStage.SEARCHING_ONLINE, 0)
            try {
                val found = findOnlineSafely(song)
                if (found != null && storeOriginal(song.id, found.text, LyricsSource.ONLINE)) {
                    dao.get(song.id)?.let { translateWhenPossible(it, tellUser) }
                    return
                }
            } finally {
                _aiJob.value = null
            }
        }

        _aiJob.value = AiJob(song.id, song.title, AiStage.DECODING, 0)
        try {
            val result = engine.transcribe(song, pickedLanguage(song.id), { translator.translatable(it) != null }) { stage, percent ->
                _aiJob.value = AiJob(song.id, song.title, stage, percent)
            }
            val entity = LyricsEntity(
                songId = song.id,
                source = LyricsSource.AI,
                language = result.language,
                original = result.original?.let { LrcParser.serialize(it) },
                english = result.english?.let { LrcParser.serialize(it) },
                updatedAt = now(),
            )
            dao.upsert(entity)
            if (entity.english == null) translateWhenPossible(entity, tellUser)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Remember the miss so "Get all lyrics" doesn't retry it every time.
            val existing = dao.get(song.id)
            if (existing?.original == null) {
                dao.upsert(LyricsEntity(song.id, LyricsSource.AI_FAILED, null, null, null, now()))
            }
            if (tellUser) {
                val reason = t.message ?: "AI lyrics failed"
                _messages.tryEmit("${song.title}: $reason")
            }
        } finally {
            _aiJob.value = null
        }
    }

    /**
     * The AI (and the online database) give the words in the song's own language; English
     * comes from the on-phone translator. Its language pack (about 30 MB) downloads once, the
     * first time it's needed, if the phone is online; otherwise the lyrics screen offers it.
     */
    private suspend fun translateWhenPossible(entity: LyricsEntity, tellUser: Boolean) {
        if (entity.english != null) return
        val code = translator.translatable(entity.language) ?: return
        if (!translator.isReady(code)) {
            if (!translator.isOnline() || code in translator.downloading.value) return
            if (!translator.download(code)) {
                if (tellUser) _messages.tryEmit("Lyrics are ready in ${languageName(code)}. Connect to the internet once to get the English translation pack.")
                return
            }
            // A new pack: translate every song that was waiting for it.
            entity.language?.let { lang -> dao.untranslated(lang).forEach { maybeTranslate(it) } }
            return
        }
        dao.get(entity.songId)?.let { maybeTranslate(it) }
    }

    /** Online lookup that never throws, except to pass on cancellation. */
    private suspend fun findOnlineSafely(song: Song): FoundLyrics? = try {
        OnlineLyrics.find(song)
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        null
    }

    /** "Find real lyrics online" from the lyrics menu. */
    fun findOnline(song: Song) {
        scope.launch {
            if (!translator.isOnline()) {
                _messages.tryEmit("Connect to the internet to look up the lyrics.")
                return@launch
            }
            val found = findOnlineSafely(song)
            if (found == null || !storeOriginal(song.id, found.text, LyricsSource.ONLINE)) {
                _messages.tryEmit("Couldn't find the real lyrics for this song online.")
                return@launch
            }
            dao.get(song.id)?.let { translateWhenPossible(it, tellUser = true) }
        }
    }

    fun isCharging(): Boolean {
        val battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return true
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        /** When the new lyrics AI shipped (13 Nov 2026, 17:00 UTC). Older AI rows came from the old one. */
        private const val NEW_AI_SINCE = 1_794_589_200_000L

        /** Songs "Get all lyrics" doesn't need to do again. */
        private val DONE_SOURCES = setOf(LyricsSource.AI_FAILED, LyricsSource.AI, LyricsSource.ONLINE)
    }
}
