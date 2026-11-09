@file:OptIn(ExperimentalFoundationApi::class)

package com.maheshkatakam.melo.ui.home

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maheshkatakam.melo.data.Song
import com.maheshkatakam.melo.ui.LocalContainer
import com.maheshkatakam.melo.ui.LocalNavigator
import com.maheshkatakam.melo.ui.LocalSongMenu
import com.maheshkatakam.melo.ui.components.CenteredLoading
import com.maheshkatakam.melo.ui.components.EmptyState
import com.maheshkatakam.melo.ui.components.GeneratedCover
import com.maheshkatakam.melo.ui.components.LyricsBatchCard
import com.maheshkatakam.melo.ui.components.SectionHeader
import com.maheshkatakam.melo.ui.components.SongArtwork
import com.maheshkatakam.melo.ui.components.songCount
import com.maheshkatakam.melo.ui.player.MoodTile
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
fun HomeScreen() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val songs by container.music.songs.collectAsStateWithLifecycle()
    val loaded by container.music.loadedOnce.collectAsStateWithLifecycle()
    val recent by container.library.recentlyPlayed.collectAsStateWithLifecycle()
    val most by container.library.mostPlayed.collectAsStateWithLifecycle()
    val added by container.library.recentlyAdded.collectAsStateWithLifecycle()
    val playlists by container.library.playlists.collectAsStateWithLifecycle()
    val moods by container.effects.moods.collectAsStateWithLifecycle()
    val mood by container.effects.currentMood.collectAsStateWithLifecycle()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            Toast.makeText(context, "Adding ${songCount(uris.size)}…", Toast.LENGTH_SHORT).show()
            scope.launch {
                val result = container.importer.import(uris)
                val msg = if (result.failed == 0) "Added ${songCount(result.imported)} to Music/Melo"
                else "Added ${result.imported}, ${result.failed} couldn't be copied"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    if (!loaded) {
        CenteredLoading()
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 8.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(greeting(), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (songs.isEmpty()) "Let's get your music in" else "${songCount(songs.size)} ready to play, offline",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { importLauncher.launch(arrayOf("audio/*")) }) {
                    Icon(Icons.Rounded.LibraryAdd, "Add songs")
                }
                IconButton(onClick = { navigator.openSettings() }) {
                    Icon(Icons.Rounded.Settings, "Settings")
                }
            }
        }

        if (songs.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.LibraryMusic,
                    title = "No songs found yet",
                    message = "Melo plays the music files on your phone. Copy songs into your Music or Download folder, or add them right here.",
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(onClick = { importLauncher.launch(arrayOf("audio/*")) }) {
                            Icon(Icons.Rounded.LibraryAdd, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add songs")
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { container.music.refresh() }) {
                            Icon(Icons.Rounded.Refresh, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Scan again")
                        }
                    }
                }
            }
            return@LazyColumn
        }

        item { SectionHeader("How are you feeling?", action = "Sound", onAction = { navigator.openSound() }) }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(moods, key = { it.id }) { m ->
                    MoodTile(m, selected = m.id == mood.id, modifier = Modifier.width(108.dp)) {
                        container.effects.setMood(m.id)
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickTile(Icons.Rounded.Shuffle, "Shuffle all", Modifier.weight(1f)) {
                        container.player.play(songs, shuffle = true)
                    }
                    QuickTile(Icons.Rounded.Favorite, "Favorites", Modifier.weight(1f)) {
                        navigator.openCollection("favorites")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickTile(Icons.Rounded.NewReleases, "Recently added", Modifier.weight(1f)) {
                        navigator.openCollection("added")
                    }
                    QuickTile(Icons.AutoMirrored.Rounded.QueueMusic, "${mood.name} mix", Modifier.weight(1f)) {
                        navigator.openCollection("mood:${mood.id}")
                    }
                }
            }
        }

        item { LyricsBatchCard(Modifier.padding(horizontal = 16.dp)) }

        if (recent.isNotEmpty()) {
            item { SectionHeader("Jump back in", action = "See all", onAction = { navigator.openCollection("recent") }) }
            item { SongCarousel(recent.take(20)) }
        }
        if (most.isNotEmpty()) {
            item { SectionHeader("Your top songs", action = "See all", onAction = { navigator.openCollection("most") }) }
            item { SongCarousel(most.take(20)) }
        }
        item { SectionHeader("Recently added", action = "See all", onAction = { navigator.openCollection("added") }) }
        item { SongCarousel(added.take(20)) }

        if (playlists.isNotEmpty()) {
            item { SectionHeader("Your playlists") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(playlists, key = { it.id }) { p ->
                        val cover = remember(p.songIds) { p.songIds.firstOrNull()?.let { container.music.song(it) } }
                        Column(
                            Modifier
                                .width(140.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .combinedClickable(onClick = { navigator.openPlaylist(p.id) }),
                        ) {
                            if (cover != null) {
                                SongArtwork(cover, Modifier.fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(16.dp))
                            } else {
                                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp))) {
                                    GeneratedCover(p.name, Modifier.fillMaxSize(), label = p.name)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                songCount(p.songIds.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongCarousel(songs: List<Song>) {
    val container = LocalContainer.current
    val songMenu = LocalSongMenu.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
            Column(
                Modifier
                    .width(140.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(
                        onClick = { container.player.play(songs, index) },
                        onLongClick = { songMenu.open(song, null) },
                    ),
            ) {
                SongArtwork(song, Modifier.fillMaxWidth().aspectRatio(1f), shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(6.dp))
                Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    song.artist,
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
private fun QuickTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.height(58.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Late night tunes"
}
