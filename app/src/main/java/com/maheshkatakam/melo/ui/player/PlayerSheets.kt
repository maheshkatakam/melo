@file:OptIn(ExperimentalMaterial3Api::class)

package com.maheshkatakam.melo.ui.player

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maheshkatakam.melo.audio.Mood
import com.maheshkatakam.melo.playback.QueueEntry
import com.maheshkatakam.melo.playback.SleepTimerState
import com.maheshkatakam.melo.ui.LocalContainer
import com.maheshkatakam.melo.ui.components.SongArtwork
import com.maheshkatakam.melo.ui.components.formatDuration
import com.maheshkatakam.melo.ui.components.songCount
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun QueueSheet(onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val now by container.player.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val currentPos = now.queue.indexOfFirst { it.index == now.currentIndex }

    LaunchedEffect(Unit) {
        if (currentPos > 0) listState.scrollToItem(currentPos)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxHeight(0.85f)) {
            Row(
                Modifier.padding(start = 20.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Queue", style = MaterialTheme.typography.titleLarge)
                    Text(
                        songCount(now.queue.size) + if (now.shuffle) " · shuffled" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { container.player.clearUpcoming() }) { Text("Clear") }
            }
            Text(
                "Tap to play · swipe to remove",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            LazyColumn(state = listState, modifier = Modifier.navigationBarsPadding()) {
                items(now.queue, key = { "${it.index}:${it.mediaId}" }) { entry ->
                    QueueRow(
                        entry = entry,
                        isCurrent = entry.index == now.currentIndex,
                        onClick = { container.player.skipTo(entry.index) },
                        onRemove = { container.player.removeAt(entry.index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(entry: QueueEntry, isCurrent: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled && !isCurrent) {
                onRemove()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f))
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Rounded.Delete, "Remove") }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SongArtwork(entry.song, Modifier.size(48.dp), shape = RoundedCornerShape(10.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isCurrent) {
                Icon(Icons.Rounded.GraphicEq, "Now playing", tint = MaterialTheme.colorScheme.primary)
            } else {
                entry.song?.let {
                    Text(
                        formatDuration(it.durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun MoodSheet(onDismiss: () -> Unit, onOpenSound: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val moods by container.effects.moods.collectAsStateWithLifecycle()
    val current by container.effects.currentMood.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("How are you feeling?", style = MaterialTheme.typography.titleLarge)
            Text(
                "Each mood reshapes the sound to fit how you feel. Melo also learns which songs you play in each mood.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.height(((moods.size + 2) / 3 * 104).dp.coerceAtMost(430.dp)),
            ) {
                items(moods, key = { it.id }) { mood ->
                    MoodTile(mood, selected = mood.id == current.id) { container.effects.setMood(mood.id) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                current.name + ": " + current.details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            val mix = container.library.moodMix(current.id)
                            val songs = if (mix.size >= 5) mix else container.music.songs.value
                            if (songs.isEmpty()) return@launch
                            container.player.play(songs, shuffle = true)
                            if (mix.size < 5 && current.id != "normal") {
                                Toast.makeText(context, "Shuffling everything. Your ${current.name} mix grows as you listen.", Toast.LENGTH_LONG).show()
                            }
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("${current.name} mix", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledTonalButton(onClick = onOpenSound, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Tune, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Fine-tune")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun MoodTile(mood: Mood, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(94.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(mood.colors))
            .then(
                if (selected) Modifier.border(3.dp, Color.White, RoundedCornerShape(18.dp)) else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Icon(mood.icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
        if (selected) {
            Icon(
                Icons.Rounded.CheckCircle,
                "Selected",
                tint = Color.White,
                modifier = Modifier
                    .size(20.dp)
                    .align(Alignment.TopEnd),
            )
        }
        Text(
            mood.name,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

@Composable
fun SleepTimerSheet(onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val state by container.sleepTimer.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var remaining by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(state) {
        while (true) {
            val s = state
            remaining = if (s is SleepTimerState.At) (s.endsAtMillis - System.currentTimeMillis()).coerceAtLeast(0) else 0L
            delay(1_000)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Sleep timer", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            val status = when (val s = state) {
                SleepTimerState.Off -> "Music keeps playing"
                SleepTimerState.EndOfSong -> "Stopping after this song"
                is SleepTimerState.At -> "Stopping in ${formatDuration(remaining)} (fades out gently)"
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
            listOf(10, 15, 30, 45, 60, 90).forEach { minutes ->
                SheetOption("$minutes minutes") {
                    container.sleepTimer.start(minutes)
                    onDismiss()
                }
            }
            SheetOption("End of this song") {
                container.sleepTimer.endOfSong()
                onDismiss()
            }
            if (state != SleepTimerState.Off) {
                SheetOption("Turn off timer", color = MaterialTheme.colorScheme.error) {
                    container.sleepTimer.cancel()
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun SheetOption(label: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    )
}
