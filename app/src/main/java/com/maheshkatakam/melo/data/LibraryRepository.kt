package com.maheshkatakam.melo.data

import com.maheshkatakam.melo.data.db.FavoriteEntity
import com.maheshkatakam.melo.data.db.MeloDatabase
import com.maheshkatakam.melo.data.db.MoodStatEntity
import com.maheshkatakam.melo.data.db.PlayStatEntity
import com.maheshkatakam.melo.data.db.PlaylistEntity
import com.maheshkatakam.melo.data.db.PlaylistSongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Favorites, playlists and listening history. All stored on the phone. */
class LibraryRepository(
    private val db: MeloDatabase,
    private val music: MusicRepository,
    private val scope: CoroutineScope,
) {
    val favoriteIds: StateFlow<Set<Long>> = db.favorites().observeIds()
        .map { it.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    val favorites: StateFlow<List<Song>> = combine(db.favorites().observeIds(), music.songs) { ids, songs ->
        val byId = songs.associateBy { it.id }
        ids.mapNotNull { byId[it] }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val playlists: StateFlow<List<Playlist>> = combine(
        db.playlists().observePlaylists(),
        db.playlists().observeEntries(),
    ) { lists, entries ->
        val grouped = entries.groupBy { it.playlistId }
        lists.map { p ->
            Playlist(p.id, p.name, grouped[p.id].orEmpty().sortedBy { it.position }.map { it.songId })
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val playStats = db.stats().observePlayStats()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val recentlyPlayed: StateFlow<List<Song>> = combine(playStats, music.songs) { stats, songs ->
        val byId = songs.associateBy { it.id }
        stats.sortedByDescending { it.lastPlayedAt }.mapNotNull { byId[it.songId] }.take(50)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val mostPlayed: StateFlow<List<Song>> = combine(playStats, music.songs) { stats, songs ->
        val byId = songs.associateBy { it.id }
        stats.filter { it.playCount > 0 }
            .sortedWith(compareByDescending<PlayStatEntity> { it.playCount }.thenByDescending { it.lastPlayedAt })
            .mapNotNull { byId[it.songId] }
            .take(50)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val playCounts: StateFlow<Map<Long, Int>> = playStats
        .map { list -> list.associate { it.songId to it.playCount } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val recentlyAdded: StateFlow<List<Song>> = music.songs
        .map { list -> list.sortedByDescending { it.dateAddedSec }.take(50) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun toggleFavorite(songId: Long) {
        scope.launch {
            if (db.favorites().count(songId) > 0) db.favorites().remove(songId)
            else db.favorites().add(FavoriteEntity(songId, System.currentTimeMillis()))
        }
    }

    suspend fun addFavorite(songId: Long) {
        if (db.favorites().count(songId) == 0) db.favorites().add(FavoriteEntity(songId, System.currentTimeMillis()))
    }

    fun createPlaylist(name: String, initialSongs: List<Long> = emptyList(), onCreated: (Long) -> Unit = {}) {
        scope.launch {
            val now = System.currentTimeMillis()
            val id = db.playlists().insertPlaylist(PlaylistEntity(name = name.trim(), createdAt = now, updatedAt = now))
            if (initialSongs.isNotEmpty()) addToPlaylistNow(id, initialSongs)
            onCreated(id)
        }
    }

    fun addToPlaylist(playlistId: Long, songIds: List<Long>) {
        scope.launch { addToPlaylistNow(playlistId, songIds) }
    }

    private suspend fun addToPlaylistNow(playlistId: Long, songIds: List<Long>) {
        val dao = db.playlists()
        var pos = dao.maxPosition(playlistId) + 1
        val fresh = songIds.distinct().filter { dao.contains(playlistId, it) == 0 }
        if (fresh.isEmpty()) return
        dao.insertEntries(fresh.map { PlaylistSongEntity(playlistId = playlistId, songId = it, position = pos++) })
        dao.touch(playlistId, System.currentTimeMillis())
    }

    fun removeFromPlaylist(playlistId: Long, songId: Long) {
        scope.launch { db.playlists().removeSong(playlistId, songId) }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        scope.launch { db.playlists().rename(playlistId, name.trim(), System.currentTimeMillis()) }
    }

    fun deletePlaylist(playlistId: Long) {
        scope.launch { db.playlists().deleteWithEntries(playlistId) }
    }

    /** Called by the player when a song has been listened to for a little while. */
    suspend fun recordPlay(songId: Long, moodId: String) {
        val now = System.currentTimeMillis()
        val stats = db.stats()
        val current = stats.getPlayStat(songId)
        stats.upsertPlayStat(PlayStatEntity(songId, (current?.playCount ?: 0) + 1, now))
        if (moodId != "normal") {
            val mood = stats.getMoodStat(songId, moodId)
            stats.upsertMoodStat(MoodStatEntity(songId, moodId, (mood?.plays ?: 0) + 1, now))
        }
    }

    /** Songs you've played most while in this mood. */
    suspend fun moodMix(moodId: String): List<Song> {
        val stats = db.stats().moodStats(moodId)
        return stats.mapNotNull { music.song(it.songId) }
    }
}
