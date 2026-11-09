@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.maheshkatakam.melo.ui.library

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maheshkatakam.melo.data.SongSort
import com.maheshkatakam.melo.ui.LocalContainer
import com.maheshkatakam.melo.ui.LocalNavigator
import com.maheshkatakam.melo.ui.LocalSongMenu
import com.maheshkatakam.melo.ui.components.EmptyState
import com.maheshkatakam.melo.ui.components.GeneratedCover
import com.maheshkatakam.melo.ui.components.PlayShuffleRow
import com.maheshkatakam.melo.ui.components.SongArtwork
import com.maheshkatakam.melo.ui.components.SongRow
import com.maheshkatakam.melo.ui.components.TextInputDialog
import com.maheshkatakam.melo.ui.components.songCount
import kotlinx.coroutines.launch

private val libraryTabs = listOf("Songs", "Albums", "Artists", "Folders", "Playlists")

@Composable
fun LibraryScreen() {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { libraryTabs.size }
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var sortOpen by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val result = container.importer.import(uris)
                Toast.makeText(context, "Added ${songCount(result.imported)}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Library", style = MaterialTheme.typography.headlineSmall) },
            actions = {
                Box {
                    IconButton(onClick = { sortOpen = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort songs") }
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        SongSort.entries.forEach { sort ->
                            DropdownMenuItem(
                                text = { Text(sort.label) },
                                trailingIcon = {
                                    if (settings.songSort == sort) Icon(Icons.Rounded.Check, null)
                                },
                                onClick = {
                                    container.settings.setSongSort(sort)
                                    sortOpen = false
                                },
                            )
                        }
                    }
                }
                IconButton(onClick = { importLauncher.launch(arrayOf("audio/*")) }) {
                    Icon(Icons.Rounded.LibraryAdd, "Add songs")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        ScrollableTabRow(
            selectedTabIndex = pager.currentPage,
            edgePadding = 12.dp,
            containerColor = MaterialTheme.colorScheme.background,
            divider = {},
            indicator = { positions ->
                if (pager.currentPage < positions.size) {
                    TabRowDefaults.PrimaryIndicator(
                        Modifier.tabIndicatorOffset(positions[pager.currentPage]),
                        width = 28.dp,
                        shape = RoundedCornerShape(3.dp),
                    )
                }
            },
        ) {
            libraryTabs.forEachIndexed { index, title ->
                Tab(
                    selected = pager.currentPage == index,
                    onClick = { scope.launch { pager.animateScrollToPage(index) } },
                    text = { Text(title, style = MaterialTheme.typography.titleSmall) },
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            when (page) {
                0 -> SongsTab(settings.songSort)
                1 -> AlbumsTab()
                2 -> ArtistsTab()
                3 -> FoldersTab()
                else -> PlaylistsTab()
            }
        }
    }
}

@Composable
private fun SongsTab(sort: SongSort) {
    val container = LocalContainer.current
    val songMenu = LocalSongMenu.current
    val songs by container.music.songs.collectAsStateWithLifecycle()
    val favorites by container.library.favoriteIds.collectAsStateWithLifecycle()
    val now by container.player.state.collectAsStateWithLifecycle()
    val sorted = remember(songs, sort) { container.music.sorted(songs, sort) }

    if (sorted.isEmpty()) {
        EmptyState(Icons.Rounded.LibraryMusic, "No songs yet", "Add songs from the + button above, or copy them into your Music folder.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            PlayShuffleRow(
                onPlay = { container.player.play(sorted, 0) },
                onShuffle = { container.player.play(sorted, shuffle = true) },
            )
        }
        itemsIndexed(sorted, key = { _, s -> s.id }) { index, song ->
            SongRow(
                song = song,
                isCurrent = now.song?.id == song.id,
                isFavorite = song.id in favorites,
                onClick = { container.player.play(sorted, index) },
                onMore = { songMenu.open(song, null) },
            )
        }
    }
}

@Composable
private fun AlbumsTab() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val albums by container.music.albums.collectAsStateWithLifecycle()
    if (albums.isEmpty()) {
        EmptyState(Icons.Rounded.LibraryMusic, "No albums", "Albums appear here once you have songs.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.id }) { album ->
            Column(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { navigator.openAlbum(album.id) },
            ) {
                SongArtwork(album.cover, Modifier.fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(8.dp))
                Text(album.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${album.artist} · ${album.songs.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ArtistsTab() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val artists by container.music.artists.collectAsStateWithLifecycle()
    if (artists.isEmpty()) {
        EmptyState(Icons.Rounded.LibraryMusic, "No artists", "Artists appear here once you have songs.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(artists, key = { it.name }) { artist ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { navigator.openArtist(artist.name) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(52.dp).clip(CircleShape)) {
                    GeneratedCover(artist.name, Modifier.fillMaxSize(), label = artist.name)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(artist.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${songCount(artist.songs.size)} · ${artist.albumCount} album${if (artist.albumCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FoldersTab() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val folders by container.music.folders.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    if (folders.isEmpty()) {
        EmptyState(Icons.Rounded.Folder, "No folders", "Folders with music appear here.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Text(
                "Hide folders you don't want in your library (like voice notes) from the ⋮ menu.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        items(folders, key = { it.path }) { folder ->
            val hidden = folder.path in settings.hiddenFolders
            var menu by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { navigator.openFolder(folder.path) }
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (hidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Folder,
                            null,
                            tint = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        folder.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        (if (hidden) "Hidden · " else "") + songCount(folder.songs.size) + " · " + folder.path.removePrefix("/storage/emulated/0/"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Folder options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (hidden) "Show in library" else "Hide from library") },
                            leadingIcon = { Icon(if (hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, null) },
                            onClick = {
                                menu = false
                                if (hidden) container.settings.unhideFolder(folder.path) else container.settings.hideFolder(folder.path)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistsTab() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val playlists by container.library.playlists.collectAsStateWithLifecycle()
    val favorites by container.library.favorites.collectAsStateWithLifecycle()
    val discovered by container.discoveries.discoveries.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    if (creating) {
        TextInputDialog(
            title = "New playlist",
            label = "Playlist name",
            confirm = "Create",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                container.library.createPlaylist(name)
            },
        )
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            PlaylistRowLayout(
                title = "New playlist",
                subtitle = "Make a list for any occasion",
                onClick = { creating = true },
            ) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), modifier = Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item {
            PlaylistRowLayout(
                title = "Favorites",
                subtitle = songCount(favorites.size),
                onClick = { navigator.openCollection("favorites") },
            ) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f), modifier = Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Favorite, null, tint = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
        item {
            PlaylistRowLayout(
                title = "Discovered",
                subtitle = if (discovered.isEmpty()) "Songs you identify" else songCount(discovered.size),
                onClick = { navigator.openDiscovered() },
            ) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f), modifier = Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.TravelExplore, null, tint = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
        items(playlists, key = { it.id }) { p ->
            val cover = remember(p.songIds) { p.songIds.firstOrNull()?.let { container.music.song(it) } }
            PlaylistRowLayout(title = p.name, subtitle = songCount(p.songIds.size), onClick = { navigator.openPlaylist(p.id) }) {
                if (cover != null) {
                    SongArtwork(cover, Modifier.fillMaxSize(), shape = RoundedCornerShape(14.dp))
                } else {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))) {
                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistRowLayout(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    art: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp)) { art() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
