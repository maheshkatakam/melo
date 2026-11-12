@file:OptIn(ExperimentalMaterial3Api::class)

package com.maheshkatakam.melo.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maheshkatakam.melo.data.MusicRepository
import com.maheshkatakam.melo.data.Song
import com.maheshkatakam.melo.ui.LocalContainer
import com.maheshkatakam.melo.ui.LocalNavigator
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SongMenuSheet(song: Song, playlistId: Long?, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val favorites by container.library.favoriteIds.collectAsState()
    val isFavorite = song.id in favorites
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = container.artwork.setCustomArt(song.id, uri)
                Toast.makeText(context, if (ok) "Picture updated" else "Couldn't use that picture", Toast.LENGTH_SHORT).show()
            }
        }
        onDismiss()
    }

    if (showPlaylistDialog) {
        AddToPlaylistDialog(listOf(song.id)) {
            showPlaylistDialog = false
            onDismiss()
        }
        return
    }
    if (showInfo) {
        SongInfoDialog(song) {
            showInfo = false
            onDismiss()
        }
        return
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SongArtwork(song, Modifier.size(56.dp), shape = RoundedCornerShape(12.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${song.artist} · ${song.album}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            MenuItem(Icons.Rounded.PlaylistPlay, "Play next") {
                container.player.playNext(listOf(song))
                Toast.makeText(context, "Playing next", Toast.LENGTH_SHORT).show()
                onDismiss()
            }
            MenuItem(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue") {
                container.player.addToQueue(listOf(song))
                Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
                onDismiss()
            }
            MenuItem(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") { showPlaylistDialog = true }
            MenuItem(
                if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (isFavorite) "Remove from favorites" else "Add to favorites",
            ) {
                container.library.toggleFavorite(song.id)
                onDismiss()
            }
            if (playlistId != null) {
                MenuItem(Icons.Rounded.Delete, "Remove from this playlist") {
                    container.library.removeFromPlaylist(playlistId, song.id)
                    onDismiss()
                }
            }
            MenuItem(Icons.Rounded.Album, "Go to album") {
                onDismiss()
                navigator.openAlbum(song.albumId)
            }
            MenuItem(Icons.Rounded.Person, "Go to artist") {
                onDismiss()
                navigator.openArtist(MusicRepository.primaryArtist(song.artist))
            }
            MenuItem(Icons.Rounded.AutoAwesome, "Make English lyrics with AI") {
                container.lyrics.requestAi(song)
                if (container.aiEngine.isSupported) {
                    Toast.makeText(context, "Added to the AI lyrics queue", Toast.LENGTH_SHORT).show()
                }
                onDismiss()
            }
            MenuItem(Icons.Rounded.Image, "Choose picture") {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            if (container.artwork.hasCustomArt(song.id)) {
                MenuItem(Icons.Rounded.Restore, "Use original picture") {
                    scope.launch { container.artwork.removeCustomArt(song.id) }
                    onDismiss()
                }
            }
            MenuItem(Icons.Rounded.Info, "Song details") { showInfo = true }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun SongInfoDialog(song: Song, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val sizeMb = song.sizeBytes / (1024.0 * 1024.0)
    var quality by remember(song.id) { mutableStateOf(container.quality.cached(song.id)) }
    var checking by remember(song.id) { mutableStateOf(false) }
    LaunchedEffect(song.id) {
        if (quality == null) quality = container.quality.describe(song)
    }
    val rows = listOf(
        "Title" to song.title,
        "Artist" to song.artist,
        "Album" to song.album,
        "Year" to (if (song.year > 0) song.year.toString() else "—"),
        "Length" to formatDuration(song.durationMs),
        "Quality on the label" to (quality?.label ?: (song.mimeType ?: "—")),
        "Size" to String.format(Locale.US, "%.1f MB", sizeMb),
        "File" to (song.path ?: song.uri.toString()),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Song details") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                rows.forEach { (k, v) ->
                    Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 10.dp))
                }
                Text("Real quality", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val verdict = quality?.verdict
                when {
                    verdict != null -> Text(verdict, style = MaterialTheme.typography.bodyMedium)
                    checking -> Text("Listening to the song…", style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        Text(
                            "Labels can lie: many downloads say 320 kbps but were converted from 128. Melo can check what's really in the sound.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            checking = true
                            scope.launch {
                                val result = container.quality.analyze(song)
                                quality = result
                                checking = false
                            }
                        }) { Text("Check real quality") }
                    }
                }
                if (verdict != null) {
                    Text(
                        "An estimate from the highest frequencies present in the middle of the song.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
