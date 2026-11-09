@file:OptIn(ExperimentalMaterial3Api::class)

package com.maheshkatakam.melo.ui.discover

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maheshkatakam.melo.discover.IdentifyOutcome
import com.maheshkatakam.melo.ui.LocalContainer
import com.maheshkatakam.melo.ui.LocalNavigator
import com.maheshkatakam.melo.ui.theme.Coral
import com.maheshkatakam.melo.ui.theme.Violet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

private const val LISTEN_SECONDS = 10

private sealed interface Stage {
    data object Setup : Stage
    data object Starting : Stage
    data class Listening(val secondsLeft: Int) : Stage
    data object Identifying : Stage
    data class Done(val outcome: IdentifyOutcome) : Stage
    data class Problem(val message: String, val micDenied: Boolean = false) : Stage
}

@Composable
fun IdentifySheet(onDismiss: () -> Unit, onOpenDiscovered: () -> Unit) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val repo = container.discoveries

    var stage by remember { mutableStateOf<Stage>(if (settings.auddToken.isBlank()) Stage.Setup else Stage.Starting) }
    var job by remember { mutableStateOf<Job?>(null) }
    var pausedMusic by remember { mutableStateOf(false) }

    fun listen() {
        job?.cancel()
        job = scope.launch {
            if (container.player.state.value.isPlaying) {
                container.player.pause()
                pausedMusic = true
            }
            try {
                stage = Stage.Listening(LISTEN_SECONDS)
                val clip = repo.recorder.record(LISTEN_SECONDS) { left -> stage = Stage.Listening(left) }
                stage = Stage.Identifying
                // Runs in the app's scope so the result is saved even if you close this sheet.
                val outcome = container.scope.async { repo.identify(clip) }.await()
                stage = Stage.Done(outcome)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                stage = Stage.Problem(t.message ?: "Something went wrong while listening.")
            } finally {
                if (pausedMusic) {
                    container.player.resume()
                    pausedMusic = false
                }
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen()
        else stage = Stage.Problem("Melo needs the microphone to hear the song playing around you.", micDenied = true)
    }

    fun start() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) listen() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(Unit) {
        if (stage == Stage.Starting) start()
    }
    DisposableEffect(Unit) {
        onDispose {
            job?.cancel()
            if (pausedMusic) container.player.resume()
        }
    }

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
            when (val s = stage) {
                Stage.Setup -> SetupContent(
                    onKeySaved = { key ->
                        container.settings.setAuddToken(key)
                        stage = Stage.Starting
                        start()
                    },
                )
                Stage.Starting -> ListeningContent(LISTEN_SECONDS, starting = true) { onDismiss() }
                is Stage.Listening -> ListeningContent(s.secondsLeft, starting = false) { onDismiss() }
                Stage.Identifying -> {
                    Spacer(Modifier.height(24.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Finding the song…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(24.dp))
                }
                is Stage.Done -> ResultContent(
                    outcome = s.outcome,
                    autoFavorite = settings.autoFavoriteDiscovered,
                    onAgain = { start() },
                    onOpenDiscovered = onOpenDiscovered,
                    onOpenSettings = {
                        onDismiss()
                        navigator.openSettings()
                    },
                    onPlay = { song ->
                        container.player.play(listOf(song))
                        onDismiss()
                    },
                )
                is Stage.Problem -> {
                    Message(Icons.Rounded.MusicOff, "Couldn't listen", s.message)
                    Spacer(Modifier.height(16.dp))
                    if (s.micDenied) {
                        Button(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                            )
                        }) { Text("Open app settings") }
                    } else {
                        Button(onClick = { start() }) { Text("Try again") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupContent(onKeySaved: (String) -> Unit) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    Text("Identify songs around you", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Spacer(Modifier.height(6.dp))
    Text(
        "Melo listens for 10 seconds and tells you what's playing, then saves it to your Discovered list. " +
            "This needs the internet.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(18.dp))
    OptionCard(
        title = "Built into Melo",
        body = "Uses AudD's song recognition. Your first 300 songs are free; after that it's \$5 a month only if you want more. " +
            "Get your free key, then paste it here (it stays on this phone).",
    ) {
        OutlinedButton(onClick = { openUrl(context, AUDD_DASHBOARD) }) { Text("1. Get a free key") }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim() },
            label = { Text("2. Paste your AudD key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onKeySaved(key) }, enabled = key.length >= 8, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Mic, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Save and start listening")
        }
    }
    Spacer(Modifier.height(12.dp))
    OptionCard(
        title = "Or use the free Shazam app",
        body = "Identify the song in Shazam, then tap Share › Save to Melo. It lands in your Discovered list, " +
            "with its cover, and works the same way.",
    ) {
        FilledTonalButton(onClick = { openShazam(context) }, modifier = Modifier.fillMaxWidth()) { Text("Open Shazam") }
    }
}

@Composable
private fun OptionCard(title: String, body: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ListeningContent(secondsLeft: Int, starting: Boolean, onCancel: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "scale",
    )
    Spacer(Modifier.height(12.dp))
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(170.dp)) {
        Box(
            Modifier
                .size(150.dp)
                .scale(scale)
                .background(Brush.radialGradient(listOf(Coral.copy(alpha = 0.35f), Color.Transparent)), CircleShape),
        )
        Box(
            Modifier
                .size(104.dp)
                .background(Brush.linearGradient(listOf(Coral, Violet)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(48.dp))
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(if (starting) "Getting ready…" else "Listening… $secondsLeft", style = MaterialTheme.typography.titleLarge)
    Text(
        "Hold your phone near the music",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = onCancel) { Text("Cancel") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultContent(
    outcome: IdentifyOutcome,
    autoFavorite: Boolean,
    onAgain: () -> Unit,
    onOpenDiscovered: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlay: (com.maheshkatakam.melo.data.Song) -> Unit,
) {
    val context = LocalContext.current
    when (outcome) {
        is IdentifyOutcome.Found -> {
            val d = outcome.discovery
            Spacer(Modifier.height(4.dp))
            DiscoveryArt(d, RoundedCornerShape(22.dp), Modifier.size(200.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                d.title ?: "Unknown song",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(d.artist.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            val extra = listOfNotNull(d.album, d.releaseDate?.take(4)).joinToString(" · ")
            if (extra.isNotBlank()) {
                Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(14.dp))
            val inLibrary = outcome.inLibrary
            if (inLibrary != null) {
                StatusLine(Icons.Rounded.CheckCircle, "It's already in your library" + if (autoFavorite) " · added to Favorites" else "")
                Spacer(Modifier.height(10.dp))
                Button(onClick = { onPlay(inLibrary) }) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Play it now")
                }
            } else {
                StatusLine(
                    Icons.Rounded.Favorite,
                    if (autoFavorite) "Saved. When you download it, Melo adds it to Favorites." else "Saved to Discovered.",
                )
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                d.spotifyUrl?.let { url -> OutlinedButton(onClick = { openUrl(context, url) }) { Text("Spotify") } }
                d.appleMusicUrl?.let { url -> OutlinedButton(onClick = { openUrl(context, url) }) { Text("Apple Music") } }
                OutlinedButton(onClick = { openUrl(context, youtubeSearchUrl(d)) }) {
                    Icon(Icons.Rounded.Search, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("YouTube")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(onClick = onAgain) { Text("Identify another") }
                TextButton(onClick = onOpenDiscovered) { Text("All discovered") }
            }
        }
        IdentifyOutcome.NotFound -> {
            Message(Icons.Rounded.MusicOff, "Couldn't recognise this one", "Try again closer to the speaker, ideally during the chorus. Very new or rare songs sometimes aren't in the database yet.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAgain) { Text("Try again") }
            TextButton(onClick = { openShazam(context) }) { Text("Try Shazam instead") }
        }
        IdentifyOutcome.SavedForLater -> {
            Message(Icons.Rounded.CloudOff, "Saved for later", "You're offline, so Melo kept the recording. It will identify the song automatically when you're back online.")
            Spacer(Modifier.height(16.dp))
            Row {
                TextButton(onClick = onAgain) { Text("Record another") }
                TextButton(onClick = onOpenDiscovered) { Text("All discovered") }
            }
        }
        is IdentifyOutcome.NeedsKey -> {
            Message(Icons.Rounded.Mic, "Recognition key needed", outcome.message)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenSettings) { Text("Open settings") }
            TextButton(onClick = { openShazam(context) }) { Text("Use Shazam instead") }
        }
        is IdentifyOutcome.Failed -> {
            Message(Icons.Rounded.MusicOff, "Something went wrong", outcome.message)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAgain) { Text("Try again") }
        }
    }
}

@Composable
private fun StatusLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Message(icon: ImageVector, title: String, body: String) {
    Spacer(Modifier.height(16.dp))
    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    Spacer(Modifier.height(6.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}
