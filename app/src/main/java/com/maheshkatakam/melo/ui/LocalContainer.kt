package com.maheshkatakam.melo.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.maheshkatakam.melo.AppContainer
import com.maheshkatakam.melo.data.Song

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

/** Opens the song options sheet from anywhere in the UI. */
fun interface SongMenuLauncher {
    fun open(song: Song, playlistId: Long?)
}

val LocalSongMenu = staticCompositionLocalOf<SongMenuLauncher> { SongMenuLauncher { _, _ -> } }

/** Navigation shortcuts screens can use without holding the NavController. */
interface Navigator {
    fun openPlayer()
    fun openAlbum(albumId: Long)
    fun openArtist(name: String)
    fun openFolder(path: String)
    fun openPlaylist(id: Long)
    fun openCollection(kind: String)
    fun openSound()
    fun openSettings()
    fun back()
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }
