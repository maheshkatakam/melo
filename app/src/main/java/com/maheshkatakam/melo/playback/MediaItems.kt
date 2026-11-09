package com.maheshkatakam.melo.playback

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.maheshkatakam.melo.data.Song

/** Media ids are either a MediaStore song id ("123") or an external file ("uri:content://..."). */
object MediaIds {
    private const val URI_PREFIX = "uri:"

    fun forSong(id: Long) = id.toString()
    fun forUri(uri: Uri) = URI_PREFIX + uri.toString()

    fun songId(mediaId: String?): Long? = mediaId?.toLongOrNull()
    fun externalUri(mediaId: String?): Uri? =
        mediaId?.takeIf { it.startsWith(URI_PREFIX) }?.let { Uri.parse(it.removePrefix(URI_PREFIX)) }
}

fun Song.toMediaItem(artworkUri: Uri?): MediaItem =
    MediaItem.Builder()
        .setMediaId(MediaIds.forSong(id))
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setDisplayTitle(title)
                .setArtworkUri(artworkUri)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        )
        .build()

fun externalMediaItem(uri: Uri, title: String?): MediaItem =
    MediaItem.Builder()
        .setMediaId(MediaIds.forUri(uri))
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title ?: uri.lastPathSegment ?: "Audio")
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        )
        .build()

/** A bare item for a song id that isn't in the scanned library yet (e.g. right after boot). */
fun fallbackMediaItem(songId: Long): MediaItem =
    MediaItem.Builder()
        .setMediaId(MediaIds.forSong(songId))
        .setUri(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId))
        .build()
