@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.maheshkatakam.melo.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maheshkatakam.melo.data.Album
import com.maheshkatakam.melo.data.Song
import com.maheshkatakam.melo.ui.LocalContainer
import com.maheshkatakam.melo.ui.LocalNavigator
import com.maheshkatakam.melo.ui.LocalSongMenu
import com.maheshkatakam.melo.ui.components.BackTopBar
import com.maheshkatakam.melo.ui.components.ConfirmDialog
import com.maheshkatakam.melo.ui.components.EmptyState
import com.maheshkatakam.melo.ui.components.GeneratedCover
import com.maheshkatakam.melo.ui.components.PlayShuffleRow
import com.maheshkatakam.melo.ui.components.SongArtwork
import com.maheshkatakam.melo.ui.components.SongRow
import com.maheshkatakam.melo.ui.components.TextInputDialog
import com.maheshkatakam.melo.ui.components.formatTotal
import com.maheshkatakam.melo.ui.components.songCount

/** Shared layout: header + play/shuffle + songs. */
@Composable
private fun SongListPage(
    title: String,
    subtitle: String,
    songs: List<Song>,
    headerArt: @Composable () -> Unit,
    playlistId: Long? = null,
    empty: Triple<ImageVector, String, String> = Triple(Icons.Rounded.LibraryMusic, "Nothing here yet", ""),
    topActions: @Composable () -> Unit = {},
    extraHeader: @Composable () -> Unit = {},
    numbered: Boolean = false,
) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val songMenu = LocalSongMenu.current
    val favorites by container.library.favoriteIds.collectAsStateWithLifecycle()
    val now by container.player.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        BackTopBar(title = "", onBack = { navigator.back() }, actions = topActions)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(116.dp)) { headerArt() }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { extraHeader() }
            if (songs.isEmpty()) {
                item { EmptyState(empty.first, empty.second, empty.third) }
            } else {
                item {
                    PlayShuffleRow(
                        onPlay = { container.player.play(songs, 0) },
                        onShuffle = { container.player.play(songs, shuffle = true) },
                    )
                }
                itemsIndexed(songs, key = { i, s -> "$i-${s.id}" }) { index, song ->
                    SongRow(
                        song = song,
                        isCurrent = now.song?.id == song.id,
                        isFavorite = song.id in favorites,
                        subtitle = if (numbered && song.track > 0) "${song.track}. ${song.artist}" else null,
                        onClick = { container.player.play(songs, index) },
                        onMore = { songMenu.open(song, playlistId) },
                    )
                }
            }
        }
    }
}

private fun summary(songs: List<Song>) = "${songCount(songs.size)} · ${formatTotal(songs.sumOf { it.durationMs })}"

@Composable
fun AlbumScreen(albumId: Long) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val albums by container.music.albums.collectAsStateWithLifecycle()
    val album = albums.firstOrNull { it.id == albumId }
    if (album == null) {
        Column { BackTopBar("", onBack = { navigator.back() }) }
        return
    }
    SongListPage(
        title = album.title,
        subtitle = listOfNotNull(album.artist, album.year.takeIf { it > 0 }?.toString(), summary(album.songs)).joinToString(" · "),
        songs = album.songs,
        headerArt = { SongArtwork(album.cover, Modifier.fillMaxSize(), large = true, shape = RoundedCornerShape(18.dp)) },
        numbered = true,
    )
}

@Composable
fun ArtistScreen(name: String) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val artists by container.music.artists.collectAsStateWithLifecycle()
    val albums by container.music.albums.collectAsStateWithLifecycle()
    val artist = artists.firstOrNull { it.name == name }
    val songs = artist?.songs.orEmpty()
    val artistAlbums: List<Album> = remember(songs, albums) {
        val ids = songs.map { it.albumId }.toSet()
        albums.filter { it.id in ids }
    }
    SongListPage(
        title = name,
        subtitle = summary(songs),
        songs = songs,
        headerArt = {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(58.dp))) {
                GeneratedCover(name, Modifier.fillMaxSize(), label = name)
            }
        },
        extraHeader = {
            if (artistAlbums.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(artistAlbums, key = { it.id }) { album ->
                        Column(
                            Modifier
                                .width(120.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { navigator.openAlbum(album.id) },
                        ) {
                            SongArtwork(album.cover, Modifier.fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(14.dp))
                            Spacer(Modifier.height(4.dp))
                            Text(album.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun FolderScreen(path: String) {
    val container = LocalContainer.current
    val folders by container.music.folders.collectAsStateWithLifecycle()
    val folder = folders.firstOrNull { it.path == path }
    val songs = folder?.songs.orEmpty()
    SongListPage(
        title = folder?.name ?: path.substringAfterLast('/'),
        subtitle = summary(songs),
        songs = songs,
        headerArt = { CoverOrGenerated(songs.firstOrNull(), folder?.name ?: path) },
    )
}

@Composable
fun PlaylistScreen(playlistId: Long) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val playlists by container.library.playlists.collectAsStateWithLifecycle()
    val allSongs by container.music.songs.collectAsStateWithLifecycle()
    val playlist = playlists.firstOrNull { it.id == playlistId }
    val songs = remember(playlist, allSongs) { playlist?.let { container.music.songs(it.songIds) }.orEmpty() }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    if (playlist == null) {
        Column { BackTopBar("", onBack = { navigator.back() }) }
        return
    }
    if (renaming) {
        TextInputDialog(
            title = "Rename playlist",
            label = "Name",
            confirm = "Save",
            initial = playlist.name,
            onDismiss = { renaming = false },
            onConfirm = {
                renaming = false
                container.library.renamePlaylist(playlist.id, it)
            },
        )
    }
    if (deleting) {
        ConfirmDialog(
            title = "Delete playlist?",
            message = "\"${playlist.name}\" will be removed. Your songs stay on the phone.",
            confirm = "Delete",
            onDismiss = { deleting = false },
            onConfirm = {
                container.library.deletePlaylist(playlist.id)
                navigator.back()
            },
        )
    }
    SongListPage(
        title = playlist.name,
        subtitle = summary(songs),
        songs = songs,
        playlistId = playlist.id,
        headerArt = {
            val first = songs.firstOrNull()
            if (first != null) SongArtwork(first, Modifier.fillMaxSize(), large = true, shape = RoundedCornerShape(18.dp))
            else Box(Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))) { GeneratedCover(playlist.name, Modifier.fillMaxSize(), label = playlist.name) }
        },
        empty = Triple(Icons.Rounded.LibraryMusic, "This playlist is empty", "Use \"Add to playlist\" from any song's ⋮ menu."),
        topActions = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Playlist options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = {
                            menu = false
                            renaming = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete playlist") },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        onClick = {
                            menu = false
                            deleting = true
                        },
                    )
                }
            }
        },
    )
}

@Composable
fun CollectionScreen(kind: String) {
    val container = LocalContainer.current
    val favorites by container.library.favorites.collectAsStateWithLifecycle()
    val recent by container.library.recentlyPlayed.collectAsStateWithLifecycle()
    val most by container.library.mostPlayed.collectAsStateWithLifecycle()
    val added by container.library.recentlyAdded.collectAsStateWithLifecycle()
    val moods by container.effects.moods.collectAsStateWithLifecycle()

    if (kind.startsWith("mood:")) {
        val moodId = kind.removePrefix("mood:")
        val mood = moods.firstOrNull { it.id == moodId }
        val mix by produceState(initialValue = emptyList<Song>(), moodId) { value = container.library.moodMix(moodId) }
        SongListPage(
            title = "${mood?.name ?: "Mood"} mix",
            subtitle = if (mix.isEmpty()) "Builds up as you listen" else summary(mix),
            songs = mix,
            headerArt = {
                if (mood != null) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))) {
                        com.maheshkatakam.melo.ui.player.MoodTile(mood, selected = false, modifier = Modifier.fillMaxSize()) {}
                    }
                } else {
                    CoverOrGenerated(null, moodId)
                }
            },
            empty = Triple(
                Icons.Rounded.LibraryMusic,
                "Your ${mood?.name ?: ""} mix is empty",
                "Pick this mood and play some songs. Melo remembers what you listen to in each mood and builds the mix for you.",
            ),
        )
        return
    }

    val (title, songs, emptyText) = when (kind) {
        "favorites" -> Triple("Favorites", favorites, "Tap the heart on any song to keep it here.")
        "recent" -> Triple("Recently played", recent, "Songs you play will show up here.")
        "most" -> Triple("Your top songs", most, "Your most played songs will show up here.")
        else -> Triple("Recently added", added, "New songs on your phone show up here.")
    }
    SongListPage(
        title = title,
        subtitle = summary(songs),
        songs = songs,
        headerArt = { CoverOrGenerated(songs.firstOrNull(), title) },
        empty = Triple(Icons.Rounded.LibraryMusic, "Nothing here yet", emptyText),
    )
}

@Composable
private fun CoverOrGenerated(song: Song?, seed: String) {
    if (song != null) {
        SongArtwork(song, Modifier.fillMaxSize(), large = true, shape = RoundedCornerShape(18.dp))
    } else {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))) {
            GeneratedCover(seed, Modifier.fillMaxSize(), label = seed)
        }
    }
}
