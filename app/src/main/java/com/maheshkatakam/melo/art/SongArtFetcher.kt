package com.maheshkatakam.melo.art

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import com.maheshkatakam.melo.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer

/** Coil request model for a song's picture. */
data class SongArt(val song: Song, val large: Boolean, val version: Int)

class NoArtworkException : Exception("No artwork")

class SongArtFetcher(
    private val data: SongArt,
    private val options: Options,
    private val repository: ArtworkRepository,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val bytes = withContext(Dispatchers.IO) { repository.loadArt(data.song, data.large) }
            ?: throw NoArtworkException()
        return SourceResult(
            source = ImageSource(Buffer().write(bytes), options.context),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val repository: ArtworkRepository) : Fetcher.Factory<SongArt> {
        override fun create(data: SongArt, options: Options, imageLoader: ImageLoader): Fetcher =
            SongArtFetcher(data, options, repository)
    }
}

class SongArtKeyer : Keyer<SongArt> {
    override fun key(data: SongArt, options: Options): String =
        "art:${data.song.id}:${data.song.dateModifiedSec}:${data.version}:${if (data.large) "L" else "S"}"
}
