@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.maheshkatakam.melo.ui.discover

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maheshkatakam.melo.data.db.DiscoveryEntity
import com.maheshkatakam.melo.discover.DiscoveryStatus
import com.maheshkatakam.melo.discover.IdentifyOutcome
import com.maheshkatakam.melo.ui.LocalContainer
import com.maheshkatakam.melo.ui.LocalIdentify
import com.maheshkatakam.melo.ui.LocalNavigator
import com.maheshkatakam.melo.ui.components.BackTopBar
import com.maheshkatakam.melo.ui.components.EmptyState
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun DiscoveredScreen() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val identify = LocalIdentify.current
    val list by container.discoveries.discoveries.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Discovered", onBack = { navigator.back() }) {
            IconButton(onClick = identify) { Icon(Icons.Rounded.Mic, "Identify a song") }
        }
        if (list.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Mic,
                title = "Nothing discovered yet",
                message = "When you hear a song you like, tap Identify. Or find it in Shazam and tap Share › Save to Melo. " +
                    "It's saved here, and once you download it Melo adds it to your Favorites.",
            ) {
                Button(onClick = identify) {
                    Icon(Icons.Rounded.Mic, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Identify a song")
                }
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Text(
                    "Songs you identified. Once one shows up in your library, Melo adds it to Favorites.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            items(list, key = { it.id }) { d ->
                DiscoveryRow(d, onClick = { open = d.id }, onPlay = {
                    d.matchedSongId?.let { id -> container.music.song(id) }?.let { container.player.play(listOf(it)) }
                })
            }
        }
    }

    val selected = list.firstOrNull { it.id == open }
    if (selected != null) {
        DiscoveryDetailSheet(selected, onDismiss = { open = null })
    }
}

private fun statusText(d: DiscoveryEntity): String = when {
    d.status == DiscoveryStatus.PENDING -> "Waiting for internet to identify"
    d.status == DiscoveryStatus.NOT_FOUND -> "Couldn't identify this one"
    d.matchedSongId != null -> "In your library"
    else -> "Not downloaded yet"
}

private fun whenText(d: DiscoveryEntity): String =
    DateUtils.getRelativeTimeSpanString(d.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

@Composable
private fun DiscoveryRow(d: DiscoveryEntity, onClick: () -> Unit, onPlay: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DiscoveryArt(d, RoundedCornerShape(12.dp), Modifier.size(52.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                d.title ?: if (d.status == DiscoveryStatus.PENDING) "Recording saved" else "Unknown song",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(d.artist, statusText(d), whenText(d)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (d.matchedSongId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            d.matchedSongId != null -> IconButton(onClick = onPlay) { Icon(Icons.Rounded.PlayArrow, "Play") }
            d.status == DiscoveryStatus.PENDING -> Icon(
                Icons.Rounded.CloudOff,
                "Waiting for internet",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
            else -> Unit
        }
    }
}

@Composable
private fun DiscoveryDetailSheet(d: DiscoveryEntity, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val song = d.matchedSongId?.let { container.music.song(it) }
    val canRetry = d.status != DiscoveryStatus.FOUND && d.clipPath?.let { File(it).exists() } == true

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DiscoveryArt(d, RoundedCornerShape(22.dp), Modifier.size(200.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                d.title ?: "Recording saved",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            d.artist?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            val extra = listOfNotNull(d.album, d.releaseDate?.take(4)).joinToString(" · ")
            if (extra.isNotBlank()) {
                Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song != null) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    statusText(d) + " · found " + whenText(d).replaceFirstChar { it.lowercase() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))

            if (song != null) {
                Button(onClick = {
                    container.player.play(listOf(song))
                    onDismiss()
                }) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Play")
                }
                Spacer(Modifier.height(8.dp))
            }
            if (d.title != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                    d.spotifyUrl?.let { url -> OutlinedButton(onClick = { openUrl(context, url) }) { Text("Spotify") } }
                    d.appleMusicUrl?.let { url -> OutlinedButton(onClick = { openUrl(context, url) }) { Text("Apple Music") } }
                    d.songLink?.let { url -> OutlinedButton(onClick = { openUrl(context, url) }) { Text("Listen") } }
                    OutlinedButton(onClick = { openUrl(context, youtubeSearchUrl(d)) }) {
                        Icon(Icons.Rounded.Search, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("YouTube")
                    }
                }
            }
            if (canRetry) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    scope.launch {
                        val outcome = container.discoveries.retry(d.id)
                        val msg = when (outcome) {
                            is IdentifyOutcome.Found -> "Found: ${outcome.discovery.title}"
                            IdentifyOutcome.NotFound -> "Still couldn't identify it"
                            IdentifyOutcome.SavedForLater -> "Still offline. Saved for later."
                            is IdentifyOutcome.NeedsKey -> outcome.message
                            is IdentifyOutcome.Failed -> outcome.message
                            null -> "The recording is no longer available"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        onDismiss()
                    }
                }) { Text("Try identifying again") }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                container.discoveries.remove(d.id)
                onDismiss()
            }) {
                Icon(Icons.Rounded.Delete, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Remove")
            }
        }
    }
}
