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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

object DiscoveryStatus {
    const val PENDING = "pending"      // recorded offline, waiting for internet
    const val FOUND = "found"
    const val NOT_FOUND = "not_found"
}

sealed interface IdentifyOutcome {
    data class Found(val discovery: DiscoveryEntity, val inLibrary: Song?) : IdentifyOutcome
    data object NotFound : IdentifyOutcome
    data object SavedForLater : IdentifyOutcome
    data class NeedsKey(val message: String) : IdentifyOutcome
    data class Failed(val message: String) : IdentifyOutcome
}

/**
 * Songs you identified with Melo or shared from Shazam. Recordings made offline are kept
 * and identified automatically when the phone is back online. When a discovered song later
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
    val recorder = ClipRecorder(context)
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

    fun hasKey(): Boolean = settings.settings.value.auddToken.isNotBlank()

    fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Sends a fresh recording off for identification (or keeps it for later when offline). */
    suspend fun identify(clip: File): IdentifyOutcome {
        val token = settings.settings.value.auddToken
        if (token.isBlank()) {
            clip.delete()
            return IdentifyOutcome.NeedsKey("Add your free AudD key in Settings first.")
        }
        if (!isOnline()) {
            dao.insert(blank(DiscoveryStatus.PENDING, clip.absolutePath))
            return IdentifyOutcome.SavedForLater
        }
        return when (val result = AudDClient.recognize(clip, token)) {
            is RecognitionResult.Found -> {
                clip.delete()
                val id = dao.insert(blank(DiscoveryStatus.FOUND, null))
                val saved = fill(id, result.song)
                val match = SongMatcher.find(music.songs.value, saved.title, saved.artist)
                if (match != null) linkToLibrary(saved, match)
                IdentifyOutcome.Found(dao.get(id) ?: saved, match)
            }
            RecognitionResult.NotFound -> {
                clip.delete()
                IdentifyOutcome.NotFound
            }
            is RecognitionResult.KeyProblem -> {
                // Keep the recording so it can be identified once the key is fixed.
                dao.insert(blank(DiscoveryStatus.PENDING, clip.absolutePath))
                IdentifyOutcome.NeedsKey(result.message)
            }
            is RecognitionResult.Failed -> {
                if (!isOnline()) {
                    dao.insert(blank(DiscoveryStatus.PENDING, clip.absolutePath))
                    IdentifyOutcome.SavedForLater
                } else {
                    clip.delete()
                    IdentifyOutcome.Failed(result.message)
                }
            }
        }
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

    suspend fun retry(id: Long): IdentifyOutcome? {
        val d = dao.get(id) ?: return null
        val clip = d.clipPath?.let { File(it) }?.takeIf { it.exists() } ?: return null
        dao.delete(id)
        return identify(clip)
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
        if (!hasKey() || !isOnline()) return
        pendingLock.withLock {
            for (d in dao.pending()) {
                val clip = d.clipPath?.let { File(it) }
                if (clip == null || !clip.exists()) {
                    dao.update(d.copy(status = DiscoveryStatus.NOT_FOUND, clipPath = null))
                    continue
                }
                when (val result = AudDClient.recognize(clip, settings.settings.value.auddToken)) {
                    is RecognitionResult.Found -> {
                        clip.delete()
                        dao.update(d.copy(status = DiscoveryStatus.FOUND, clipPath = null))
                        fill(d.id, result.song)
                    }
                    RecognitionResult.NotFound -> {
                        dao.update(d.copy(status = DiscoveryStatus.NOT_FOUND))
                    }
                    is RecognitionResult.KeyProblem -> return
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
            val file = File(recorder.dir, "art_$id.jpg")
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
