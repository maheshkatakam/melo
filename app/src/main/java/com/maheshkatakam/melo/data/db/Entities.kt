package com.maheshkatakam.melo.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val songId: Long,
    val addedAt: Long,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "playlist_songs", indices = [Index("playlistId")])
data class PlaylistSongEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val playlistId: Long,
    val songId: Long,
    val position: Int,
)

@Entity(tableName = "play_stats")
data class PlayStatEntity(
    @PrimaryKey val songId: Long,
    val playCount: Int,
    val lastPlayedAt: Long,
)

/** How often a song was played while a given mood was active; powers "mood mixes". */
@Entity(tableName = "mood_stats", primaryKeys = ["songId", "moodId"])
data class MoodStatEntity(
    val songId: Long,
    val moodId: String,
    val plays: Int,
    val lastPlayedAt: Long,
)

/**
 * Cached lyrics for a song. [original] and [english] are stored as LRC text when timed,
 * or plain text otherwise. [source] is one of [com.maheshkatakam.melo.lyrics.LyricsSource].
 */
@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val songId: Long,
    val source: String,
    val language: String?,
    val original: String?,
    val english: String?,
    val updatedAt: Long,
)

@Entity(tableName = "custom_moods")
data class CustomMoodEntity(
    @PrimaryKey val id: String,
    val name: String,
    val iconKey: String,
    /** Five gains in dB for 60 Hz, 230 Hz, 910 Hz, 3.6 kHz and 14 kHz, comma separated. */
    val bands: String,
    val bass: Int,
    val virtualizer: Int,
    val loudness: Int,
    val createdAt: Long,
)

/**
 * A song you identified (or shared from Shazam) so you can find it later.
 * [status] is one of [com.maheshkatakam.melo.discover.DiscoveryStatus].
 */
@Entity(tableName = "discoveries")
data class DiscoveryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val status: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val releaseDate: String?,
    /** Cover saved on the phone, so it shows offline. */
    val artworkPath: String?,
    val songLink: String?,
    val spotifyUrl: String?,
    val appleMusicUrl: String?,
    /** The 10-second recording, kept until it has been identified. */
    val clipPath: String?,
    /** "melo" (built-in) or "shared" (from Shazam or another app). */
    val source: String,
    val createdAt: Long,
    /** The song in your library once you've downloaded it. */
    val matchedSongId: Long?,
)
