package com.maheshkatakam.melo.discover

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import com.maheshkatakam.melo.art.ArtworkRepository
import com.maheshkatakam.melo.data.LibraryRepository
import com.maheshkatakam.melo.data.MusicRepository
import com.maheshkatakam.melo.data.SettingsRepository
import com.maheshkatakam.melo.data.Song
import com.maheshkatakam.melo.data.db.DiscoveryDao
import com.maheshkatakam.melo.data.db.DiscoveryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object DiscoveryStatus {
    const val PENDING = "pending"      // fingerprinted offline, waiting for internet
    const val FOUND = "found"
    const val NOT_FOUND = "not_found"
}

sealed interface IdentifyOutcome {
    data class Found(val discovery: DiscoveryEntity, val inLibrary: Song?) : IdentifyOutcome
    data object NotFound : IdentifyOutcome
    data object SavedForLater : IdentifyOutcome
    data class Failed(val message: String) : IdentifyOutcome
}

/**
 * Songs you identified with Melo or shared from Shazam. Identification is free: Melo
 * fingerprints the audio on the phone and looks it up in Shazam's catalogue. Fingerprints
 * made offline are kept (a few KB, no audio) and looked up when the phone is back online. When a discovered song later
 * appears in your library, Melo adds it to Favorites and, if it has no picture, uses the
 * cover found during identification.
 */
class DiscoveryRepository(
    private val context: Context,
    private val dao: DiscoveryDao,
    private val music: MusicRepository,
    private val library: LibraryRepository,
    private val artwork: ArtworkRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val dir = File(context.filesDir, "discover").apply { mkdirs() }
    private val pendingLock = Mutex()
    private val matchLock = Mutex()

    val discoveries: StateFlow<List<DiscoveryEntity>> = dao.observeAll()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        // Link discoveries to songs you download later.
        scope.launch {
            combine(discoveries, music.songs) { found, songs -> found to songs }
                .collect { (found, songs) -> matchAll(found, songs) }
        }
        // Identify offline recordings once there's internet.
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        scope.launch { identifyPending() }
                    }
                },
            )
        }
        scope.launch { identifyPending() }
    }

    fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Listens through the microphone and identifies the song. Asks Shazam after 4, 8 and
     * 12 seconds of audio and stops at the first match, so popular songs come back fast.
     * [onListening] reports how much of the 12 seconds has been recorded (0..1).
     */
    suspend fun listenAndIdentify(onListening: (Float) -> Unit): IdentifyOutcome = coroutineScope {
        val recorder = MicRecorder()
        val window = ShazamSignature.WINDOW_SECONDS
        val recording = launch { recorder.record(window) }
        try {
            var lastFailure: RecognitionResult.Failed? = null
            for (checkpoint in intArrayOf(4, 8, window)) {
                while (recorder.seconds < checkpoint && !recorder.finished) {
                    onListening(recorder.seconds / window)
                    delay(100)
                }
                if (recording.isCancelled) break
                onListening(recorder.seconds / window)
                if (!isOnline()) {
                    if (checkpoint < window) continue
                    break
                }
                val fp = withContext(Dispatchers.Default) { ShazamSignature.fingerprint(recorder.snapshot()) }
                if (fp.peakCount < 20) continue // too quiet so far
                when (val result = ShazamClient.recognize(fp.toDataUri(), fp.durationMs)) {
                    is RecognitionResult.Found -> {
                        recording.cancel()
                        return@coroutineScope saveFound(result.song)
                    }
                    RecognitionResult.NotFound -> lastFailure = null
                    is RecognitionResult.Failed -> lastFailure = result
                }
            }
            recording.join()
            val samples = recorder.snapshot()
            if (samples.size < ShazamSignature.SAMPLE_RATE * 3) {
                return@coroutineScope IdentifyOutcome.Failed("The microphone didn't pick anything up.")
            }
            val fp = withContext(Dispatchers.Default) { ShazamSignature.fingerprint(samples) }
            if (fp.peakCount < 20) {
                return@coroutineScope IdentifyOutcome.Failed("It was too quiet to hear the song. Hold your phone closer to the music.")
            }
            val failure = lastFailure
            when {
                !isOnline() || failure?.offline == true -> {
                    savePending(fp)
                    IdentifyOutcome.SavedForLater
                }
                failure != null -> IdentifyOutcome.Failed(failure.message)
                else -> {
                    // Keep the fingerprint so it can be tried again later (Shazam's catalogue grows).
                    dao.insert(blank(DiscoveryStatus.NOT_FOUND, writeSignature(fp)))
                    IdentifyOutcome.NotFound
                }
            }
        } finally {
            recording.cancel()
        }
    }

    private suspend fun saveFound(song: Recognized): IdentifyOutcome {
        val id = dao.insert(blank(DiscoveryStatus.FOUND, null))
        val saved = fill(id, song)
        val match = SongMatcher.find(music.songs.value, saved.title, saved.artist)
        if (match != null) linkToLibrary(saved, match)
        return IdentifyOutcome.Found(dao.get(id) ?: saved, match)
    }

    private suspend fun savePending(fp: ShazamSignature.Fingerprint) {
        dao.insert(blank(DiscoveryStatus.PENDING, writeSignature(fp)))
    }

    /** The fingerprint as a small JSON file: no audio is ever stored. */
    private suspend fun writeSignature(fp: ShazamSignature.Fingerprint): String = withContext(Dispatchers.IO) {
        val file = File(dir, "sig_${System.currentTimeMillis()}.json")
        file.writeText(JSONObject().put("uri", fp.toDataUri()).put("samplems", fp.durationMs).toString())
        file.absolutePath
    }

    private suspend fun readSignature(path: String?): Pair<String, Int>? = withContext(Dispatchers.IO) {
        val file = path?.let { File(it) }?.takeIf { it.exists() } ?: return@withContext null
        runCatching {
            val json = JSONObject(file.readText())
            json.getString("uri") to json.getInt("samplems")
        }.getOrNull()
    }

    /** Text shared from Shazam ("I used Shazam to discover X by Y. https://…") or any app. */
    fun saveShared(text: String?, subject: String?) {
        scope.launch {
            val parsed = parseShared(text, subject) ?: return@launch
            val id = dao.insert(
                blank(DiscoveryStatus.FOUND, null).copy(
                    title = parsed.title,
                    artist = parsed.artist,
                    songLink = parsed.link,
                    source = "shared",
                ),
            )
            if (isOnline()) {
                ITunesLookup.find(parsed.title, parsed.artist)?.let { extra ->
                    fill(id, extra.copy(title = parsed.title, artist = parsed.artist ?: extra.artist, songLink = parsed.link))
                }
            }
        }
    }

    /** Looks up a saved fingerprint again (waiting for internet, or not found last time). */
    suspend fun retry(id: Long): IdentifyOutcome? {
        val d = dao.get(id) ?: return null
        val (uri, ms) = readSignature(d.clipPath) ?: return null
        if (!isOnline()) return IdentifyOutcome.SavedForLater
        return when (val result = ShazamClient.recognize(uri, ms)) {
            is RecognitionResult.Found -> {
                d.clipPath?.let { File(it).delete() }
                dao.update(d.copy(status = DiscoveryStatus.FOUND, clipPath = null))
                val saved = fill(d.id, result.song)
                val match = SongMatcher.find(music.songs.value, saved.title, saved.artist)
                if (match != null) linkToLibrary(saved, match)
                IdentifyOutcome.Found(dao.get(d.id) ?: saved, match)
            }
            RecognitionResult.NotFound -> {
                dao.update(d.copy(status = DiscoveryStatus.NOT_FOUND))
                IdentifyOutcome.NotFound
            }
            is RecognitionResult.Failed -> IdentifyOutcome.Failed(result.message)
        }
    }

    fun remove(id: Long) {
        scope.launch {
            dao.get(id)?.let { d ->
                d.clipPath?.let { File(it).delete() }
                d.artworkPath?.let { File(it).delete() }
            }
            dao.delete(id)
        }
    }

    suspend fun identifyPending() {
        if (!isOnline()) return
        pendingLock.withLock {
            for (d in dao.pending()) {
                val signature = readSignature(d.clipPath)
                if (signature == null) {
                    dao.update(d.copy(status = DiscoveryStatus.NOT_FOUND, clipPath = null))
                    continue
                }
                when (val result = ShazamClient.recognize(signature.first, signature.second)) {
                    is RecognitionResult.Found -> {
                        d.clipPath?.let { File(it).delete() }
                        dao.update(d.copy(status = DiscoveryStatus.FOUND, clipPath = null))
                        fill(d.id, result.song)
                    }
                    RecognitionResult.NotFound -> dao.update(d.copy(status = DiscoveryStatus.NOT_FOUND))
                    is RecognitionResult.Failed -> return // try again next time we're online
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    private fun blank(status: String, clipPath: String?) = DiscoveryEntity(
        status = status,
        title = null,
        artist = null,
        album = null,
        releaseDate = null,
        artworkPath = null,
        songLink = null,
        spotifyUrl = null,
        appleMusicUrl = null,
        clipPath = clipPath,
        source = "melo",
        createdAt = System.currentTimeMillis(),
        matchedSongId = null,
    )

    /** Stores what was found, saving the cover on the phone so it shows offline. */
    private suspend fun fill(id: Long, song: Recognized): DiscoveryEntity {
        val current = dao.get(id) ?: blank(DiscoveryStatus.FOUND, null).copy(id = id)
        var artPath = current.artworkPath
        if (artPath == null && song.artworkUrl != null) {
            val file = File(dir, "art_$id.jpg")
            if (downloadTo(song.artworkUrl, file)) artPath = file.absolutePath
        }
        val updated = current.copy(
            status = DiscoveryStatus.FOUND,
            title = song.title,
            artist = song.artist,
            album = song.album ?: current.album,
            releaseDate = song.releaseDate ?: current.releaseDate,
            artworkPath = artPath,
            songLink = song.songLink ?: current.songLink,
            spotifyUrl = song.spotifyUrl ?: current.spotifyUrl,
            appleMusicUrl = song.appleMusicUrl ?: current.appleMusicUrl,
        )
        dao.update(updated)
        return updated
    }

    private suspend fun matchAll(found: List<DiscoveryEntity>, songs: List<Song>) {
        if (songs.isEmpty()) return
        matchLock.withLock {
            for (d in found) {
                if (d.status != DiscoveryStatus.FOUND || d.matchedSongId != null) continue
                val match = withContext(Dispatchers.Default) { SongMatcher.find(songs, d.title, d.artist) } ?: continue
                linkToLibrary(d, match)
            }
        }
    }

    private suspend fun linkToLibrary(d: DiscoveryEntity, song: Song) {
        dao.setMatch(d.id, song.id)
        val s = settings.settings.value
        if (s.autoFavoriteDiscovered) library.addFavorite(song.id)
        if (s.useDiscoveredCovers && d.artworkPath != null && !artwork.hasCustomArt(song.id)) {
            val hasArt = withContext(Dispatchers.IO) { runCatching { artwork.loadArt(song, large = false) != null }.getOrDefault(true) }
            if (!hasArt) {
                val ok = artwork.setCustomArt(song.id, Uri.fromFile(File(d.artworkPath)))
                if (!ok) Log.w("MeloDiscover", "Couldn't reuse cover for ${song.title}")
            }
        }
    }

    private data class Shared(val title: String, val artist: String?, val link: String?)

    private fun parseShared(text: String?, subject: String?): Shared? {
        val raw = text?.trim().orEmpty()
        val link = Regex("""https?://\S+""").find(raw)?.value?.trimEnd('.', ',', ')')
        val words = raw.replace(Regex("""https?://\S+"""), "").trim().trimEnd('.')
        // Shazam: "I used Shazam to discover <title> by <artist>."
        Regex("""discover(?:ed)?\s+(.+)\s+by\s+(.+)$""", RegexOption.IGNORE_CASE).find(words)?.let { m ->
            return Shared(m.groupValues[1].trim().trim('"', '“', '”'), m.groupValues[2].trim().trimEnd('.'), link)
        }
        // "<title> by <artist>" or "<title> - <artist>"
        Regex("""^(.+)\s+by\s+(.+)$""", RegexOption.IGNORE_CASE).find(words)?.let { m ->
            return Shared(m.groupValues[1].trim(), m.groupValues[2].trim(), link)
        }
        val fallback = subject?.trim()?.takeIf { it.isNotBlank() } ?: words.takeIf { it.isNotBlank() }
        return fallback?.let { Shared(it.take(120), null, link) }
            ?: link?.let { Shared("Shared song", null, it) }
    }
}
