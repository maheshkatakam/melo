@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.maheshkatakam.melo.ui.player

import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.maheshkatakam.melo.audio.Mood
import com.maheshkatakam.melo.playback.SleepTimerState
import com.maheshkatakam.melo.ui.LocalContainer
import com.maheshkatakam.melo.ui.LocalNavigator
import com.maheshkatakam.melo.ui.LocalSongMenu
import com.maheshkatakam.melo.ui.components.EmptyState
import com.maheshkatakam.melo.quality.SongQuality
import com.maheshkatakam.melo.ui.components.SongArtwork
import com.maheshkatakam.melo.ui.components.SongInfoDialog
import com.maheshkatakam.melo.ui.components.formatDuration
import com.maheshkatakam.melo.ui.components.rememberArtColor

private enum class PlayerSheet { QUEUE, MOOD, SLEEP }

@Composable
fun PlayerScreen() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val songMenu = LocalSongMenu.current
    val now by container.player.state.collectAsStateWithLifecycle()
    val position by container.player.position.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val mood by container.effects.currentMood.collectAsStateWithLifecycle()
    val favorites by container.library.favoriteIds.collectAsStateWithLifecycle()
    val sleep by container.sleepTimer.state.collectAsStateWithLifecycle()
    val headphones by container.effects.headphones.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var showInfo by remember { mutableStateOf(false) }

    val song = now.song
    val artColor = if (settings.artworkColors) rememberArtColor(song) else null
    val accent by animateColorAsState(artColor ?: MaterialTheme.colorScheme.primary, tween(600), label = "accent")
    val background = MaterialTheme.colorScheme.background

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.50f), background.copy(alpha = 0.96f), background))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp),
        ) {
            // Top bar
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navigator.back() }) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Close player", modifier = Modifier.size(30.dp))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (showLyrics) "LYRICS" else "NOW PLAYING",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        song?.album ?: "",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
                IconButton(onClick = { song?.let { songMenu.open(it, null) } }, enabled = song != null) {
                    Icon(Icons.Rounded.MoreVert, "More options")
                }
            }

            if (!now.hasContent) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EmptyState(Icons.Rounded.MusicOff, "Nothing is playing", "Pick a song from your library to start listening.")
                }
            } else {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Crossfade(targetState = showLyrics, label = "art-lyrics") { lyrics ->
                        if (lyrics && song != null) {
                            LyricsPanel(song = song, position = position, accent = accent, modifier = Modifier.fillMaxSize())
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                val scale by animateFloatAsState(if (now.isPlaying) 1f else 0.9f, tween(400), label = "art-scale")
                                SongArtwork(
                                    song,
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .scale(scale)
                                        .shadow(28.dp, RoundedCornerShape(26.dp), ambientColor = accent, spotColor = accent),
                                    large = true,
                                    shape = RoundedCornerShape(26.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))

                // Title + favorite
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            now.title,
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(),
                        )
                        Text(
                            now.artist,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = song != null) {
                                song?.let { navigator.openArtist(com.maheshkatakam.melo.data.MusicRepository.primaryArtist(it.artist)) }
                            },
                        )
                        if (song != null) {
                            val quality by produceState<SongQuality?>(container.quality.cached(song.id), song.id) {
                                if (value == null) value = container.quality.describe(song)
                            }
                            quality?.let { q ->
                                Text(
                                    q.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { showInfo = true }
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    if (song != null) {
                        val fav = song.id in favorites
                        IconButton(onClick = { container.library.toggleFavorite(song.id) }) {
                            Icon(
                                if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = if (fav) "Remove from favorites" else "Add to favorites",
                                tint = if (fav) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                SeekBar(position = position, duration = now.durationMs, accent = accent, onSeek = { container.player.seekTo(it) })
                Spacer(Modifier.height(4.dp))

                // Transport controls
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { container.player.toggleShuffle() }) {
                        Icon(
                            Icons.Rounded.Shuffle,
                            "Shuffle",
                            tint = if (now.shuffle) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { container.player.previous() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "Previous", modifier = Modifier.size(38.dp))
                    }
                    Surface(
                        shape = CircleShape,
                        color = accent,
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .clickable { container.player.togglePlay() },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (now.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (now.isPlaying) "Pause" else "Play",
                                tint = if (accent.luminanceApprox() > 0.6f) Color.Black else Color.White,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                    IconButton(onClick = { container.player.next() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipNext, "Next", modifier = Modifier.size(38.dp))
                    }
                    IconButton(onClick = { container.player.cycleRepeat() }) {
                        Icon(
                            if (now.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                            "Repeat",
                            tint = if (now.repeatMode != Player.REPEAT_MODE_OFF) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Bottom actions
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MoodChip(mood, onClick = { sheet = PlayerSheet.MOOD }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    ToggleIcon(
                        selected = settings.immersive,
                        accent = accent,
                        onClick = {
                            val on = !settings.immersive
                            container.settings.setImmersive(on)
                            val message = when {
                                !on -> "3D sound off"
                                headphones || (settings.systemSpatial && container.effects.phoneSpatialSupported) -> "3D sound on"
                                else -> "3D sound will start when you connect headphones"
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        },
                    ) { Icon(Icons.Rounded.SurroundSound, "3D sound") }
                    ToggleIcon(
                        selected = showLyrics,
                        accent = accent,
                        onClick = { showLyrics = !showLyrics },
                    ) { Icon(Icons.Rounded.Subtitles, "Lyrics") }
                    ToggleIcon(
                        selected = sleep != SleepTimerState.Off,
                        accent = accent,
                        onClick = { sheet = PlayerSheet.SLEEP },
                    ) { Icon(Icons.Rounded.Timer, "Sleep timer") }
                    ToggleIcon(selected = false, accent = accent, onClick = { sheet = PlayerSheet.QUEUE }) {
                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue")
                    }
                }
            }
        }
    }

    if (showInfo && song != null) SongInfoDialog(song) { showInfo = false }

    when (sheet) {
        PlayerSheet.QUEUE -> QueueSheet(onDismiss = { sheet = null })
        PlayerSheet.MOOD -> MoodSheet(onDismiss = { sheet = null }, onOpenSound = {
            sheet = null
            navigator.openSound()
        })
        PlayerSheet.SLEEP -> SleepTimerSheet(onDismiss = { sheet = null })
        null -> Unit
    }
}

private fun Color.luminanceApprox(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable
private fun ToggleIcon(
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (selected) accent.copy(alpha = 0.22f) else Color.Transparent,
            contentColor = if (selected) accent else MaterialTheme.colorScheme.onSurface,
        ),
    ) { content() }
}

@Composable
fun MoodChip(mood: Mood, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(mood.colors.map { it.copy(alpha = 0.85f) }))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(mood.icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text("Mood", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
            Text(mood.name, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SeekBar(position: Long, duration: Long, accent: Color, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val total = duration.coerceAtLeast(1L)
    val fraction = dragging ?: (position.toFloat() / total).coerceIn(0f, 1f)
    Column {
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * total).toLong()) }
                dragging = null
            },
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
            ),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                formatDuration(((dragging ?: fraction) * total).toLong()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatDuration(duration),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
