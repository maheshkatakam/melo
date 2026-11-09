package com.maheshkatakam.melo.data

import android.net.Uri
import androidx.compose.runtime.Immutable

@Immutable
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val uri: Uri,
    val path: String?,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val track: Int,
    val year: Int,
    val sizeBytes: Long,
    val mimeType: String?,
) {
    val folderPath: String
        get() = path?.substringBeforeLast('/', "") ?: ""

    val folderName: String
        get() = folderPath.substringAfterLast('/').ifBlank { "Unknown folder" }

    val fileName: String
        get() = path?.substringAfterLast('/') ?: title
}

@Immutable
data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val songs: List<Song>,
) {
    val cover: Song get() = songs.first()
}

@Immutable
data class Artist(
    val name: String,
    val songs: List<Song>,
    val albumCount: Int,
)

@Immutable
data class Folder(
    val path: String,
    val name: String,
    val songs: List<Song>,
)

@Immutable
data class Playlist(
    val id: Long,
    val name: String,
    val songIds: List<Long>,
)

enum class SongSort(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    DATE_ADDED("Recently added"),
    DURATION("Duration"),
}

const val UNKNOWN_ARTIST = "Unknown artist"
const val UNKNOWN_ALBUM = "Unknown album"
