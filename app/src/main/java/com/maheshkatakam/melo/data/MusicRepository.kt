package com.maheshkatakam.melo.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

/**
 * Reads the songs on the phone from Android's media library and keeps them up to date.
 * Everything here is local; nothing touches the network.
 */
class MusicRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
) {
    private val collator: Collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }

    private val allSongs = MutableStateFlow<List<Song>>(emptyList())
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _hasPermission = MutableStateFlow(checkPermission())
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private val _loadedOnce = MutableStateFlow(false)
    val loadedOnce: StateFlow<Boolean> = _loadedOnce.asStateFlow()

    /** Visible songs: minus hidden folders and too-short clips, sorted by title. */
    val songs: StateFlow<List<Song>> = combine(allSongs, settings.settings) { list, s ->
        val minMs = s.minDurationSec * 1000L
        list.filter { song ->
            song.durationMs >= minMs && s.hiddenFolders.none { hidden -> song.folderPath == hidden }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private var byId: Map<Long, Song> = emptyMap()

    val albums: StateFlow<List<Album>> = songs.map { list ->
        list.groupBy { it.albumId }.map { (id, songs) ->
            val sorted = songs.sortedWith(compareBy<Song> { it.track }.thenBy(collator) { it.title })
            Album(
                id = id,
                title = sorted.first().album,
                artist = sorted.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }?.key ?: UNKNOWN_ARTIST,
                year = sorted.maxOf { it.year },
                songs = sorted,
            )
        }.sortedWith(compareBy(collator) { it.title })
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val artists: StateFlow<List<Artist>> = songs.map { list ->
        list.groupBy { primaryArtist(it.artist) }.map { (name, songs) ->
            Artist(
                name = name,
                songs = songs.sortedWith(compareBy(collator) { it.title }),
                albumCount = songs.map { it.albumId }.distinct().size,
            )
        }.sortedWith(compareBy(collator) { it.name })
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val folders: StateFlow<List<Folder>> = allSongs.map { list ->
        list.filter { it.folderPath.isNotEmpty() }.groupBy { it.folderPath }.map { (path, songs) ->
            Folder(
                path = path,
                name = path.substringAfterLast('/'),
                songs = songs.sortedWith(compareBy(collator) { it.title }),
            )
        }.sortedWith(compareBy(collator) { it.name })
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private var refreshJob: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            scheduleRefresh(delayMs = 1500)
        }
    }

    init {
        context.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer,
        )
        scope.launch {
            allSongs.collect { list -> byId = list.associateBy { it.id } }
        }
        refresh()
    }

    fun song(id: Long): Song? = byId[id]

    fun songs(ids: List<Long>): List<Song> = ids.mapNotNull { byId[it] }

    /** Re-checks access (e.g. after returning from system settings); rescans only if it changed. */
    fun onPermissionChanged() {
        val granted = checkPermission()
        if (granted != _hasPermission.value) {
            _hasPermission.value = granted
            refresh()
        }
    }

    fun refresh() = scheduleRefresh(0)

    private fun scheduleRefresh(delayMs: Long) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            _hasPermission.value = checkPermission()
            if (!_hasPermission.value) {
                _loadedOnce.value = true
                return@launch
            }
            _scanning.value = true
            val result = withContext(Dispatchers.IO) { runCatching { query() } }
            result.onSuccess { allSongs.value = it }
                .onFailure { Log.e("Melo", "Library scan failed", it) }
            _scanning.value = false
            _loadedOnce.value = true
        }
    }

    fun sorted(list: List<Song>, sort: SongSort): List<Song> = when (sort) {
        SongSort.TITLE -> list.sortedWith(compareBy(collator) { it.title })
        SongSort.ARTIST -> list.sortedWith(compareBy<Song, String>(collator) { it.artist }.thenBy(collator) { it.title })
        SongSort.ALBUM -> list.sortedWith(compareBy<Song, String>(collator) { it.album }.thenBy { it.track })
        SongSort.DATE_ADDED -> list.sortedByDescending { it.dateAddedSec }
        SongSort.DURATION -> list.sortedByDescending { it.durationMs }
    }

    private fun checkPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun query(): List<Song> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.AudioColumns.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val out = ArrayList<Song>(512)
        context.contentResolver.query(collection, projection, selection, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DURATION)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val modifiedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val path = c.getString(dataCol)
                val rawTitle = c.getString(titleCol)
                val title = rawTitle?.takeIf { it.isNotBlank() }
                    ?: path?.substringAfterLast('/')?.substringBeforeLast('.')
                    ?: "Unknown"
                out += Song(
                    id = id,
                    title = title.trim(),
                    artist = cleanTag(c.getString(artistCol)) ?: UNKNOWN_ARTIST,
                    album = cleanTag(c.getString(albumCol)) ?: UNKNOWN_ALBUM,
                    albumId = c.getLong(albumIdCol),
                    durationMs = c.getLong(durationCol),
                    uri = ContentUris.withAppendedId(collection, id),
                    path = path,
                    dateAddedSec = c.getLong(addedCol),
                    dateModifiedSec = c.getLong(modifiedCol),
                    track = c.getInt(trackCol) % 1000,
                    year = c.getInt(yearCol),
                    sizeBytes = c.getLong(sizeCol),
                    mimeType = c.getString(mimeCol),
                )
            }
        }
        out.sortWith(compareBy(collator) { it.title })
        return out
    }

    private fun cleanTag(value: String?): String? {
        val v = value?.trim() ?: return null
        if (v.isEmpty() || v.equals("<unknown>", ignoreCase = true)) return null
        return v
    }

    companion object {
        /** "A, B feat. C" -> "A" so artist pages group collaborations sensibly. */
        fun primaryArtist(artist: String): String {
            val separators = listOf(" feat. ", " ft. ", " featuring ", " & ", ", ", "; ", " x ", " / ")
            var name = artist
            for (sep in separators) {
                val idx = name.indexOf(sep, ignoreCase = true)
                if (idx > 0) name = name.substring(0, idx)
            }
            return name.trim().ifEmpty { artist }
        }
    }
}
