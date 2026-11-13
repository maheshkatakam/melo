package com.maheshkatakam.melo.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maheshkatakam.melo.lyrics.ai.ModelState
import com.maheshkatakam.melo.ui.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One tap to get English lyrics for every song. Shows how many are done, what's being
 * worked on, and lets you stop or limit it to charging.
 *
 * [showWhenDone] keeps the card visible (as a "all done" summary) once every song has lyrics.
 */
@Composable
fun LyricsBatchCard(modifier: Modifier = Modifier, showWhenDone: Boolean = false) {
    val container = LocalContainer.current
    val repo = container.lyrics
    val supported by produceState<Boolean?>(initialValue = null) {
        value = withContext(Dispatchers.Default) { container.aiEngine.isSupported }
    }
    if (supported != true) return

    val songs by container.music.songs.collectAsStateWithLifecycle()
    val english by repo.doneIds.collectAsStateWithLifecycle()
    val modelState by container.aiEngine.models.state.collectAsStateWithLifecycle()
    val noSinging by repo.noSingingIds.collectAsStateWithLifecycle()
    val oldAi by repo.oldAiCount.collectAsStateWithLifecycle(initialValue = 0)
    val queue by repo.aiQueue.collectAsStateWithLifecycle()
    val job by repo.aiJob.collectAsStateWithLifecycle()
    val waiting by repo.waitingForCharger.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()

    if (songs.isEmpty()) return
    val ids = remember(songs) { songs.map { it.id }.toSet() }
    val ready = remember(english, ids) { english.count { it in ids } }
    val skipped = remember(noSinging, english, ids) { noSinging.count { it in ids && it !in english } }
    val remaining = (songs.size - ready - skipped).coerceAtLeast(0)
    val busy = job != null || queue.isNotEmpty()
    if (!busy && remaining == 0 && oldAi == 0 && !showWhenDone) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (!busy && remaining == 0) Icons.Rounded.CheckCircle else Icons.Rounded.AutoAwesome,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Lyrics for all songs", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "$ready of ${songs.size} songs ready" + if (skipped > 0) " · $skipped without singing" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { if (songs.isEmpty()) 0f else (ready + skipped).toFloat() / songs.size },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
            )
            Spacer(Modifier.height(10.dp))

            when {
                busy -> {
                    val current = job
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                current != null -> "Now: ${current.title} · ${current.overallPercent}%" +
                                    if (queue.isNotEmpty()) " · ${queue.size} more" else ""
                                waiting -> "AI paused until you plug in your charger · ${queue.size} left"
                                else -> "Starting…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { repo.cancelAllAi() }) { Text("Stop") }
                    }
                    Text(
                        "Keeps going in the background, even if you close Melo. Watch it in your notifications.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                remaining == 0 -> Text(
                    "Every song has lyrics. New songs you add can be done the same way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    Text(
                        "Melo looks up the real lyrics online first (free), then the on-device AI listens to the rest " +
                            "and writes their lyrics, most-played songs first. The AI takes a few minutes per song, so a big library is an overnight job.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (modelState !is ModelState.Ready && modelState !is ModelState.Downloading && modelState !is ModelState.Checking) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Songs in Telugu, Hindi, Tamil or another Indian language? Get the accurate AI model first " +
                                "(${container.aiEngine.models.accurate.sizeBytes / (1024 * 1024)} MB, one time). The built-in one struggles with them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(onClick = { container.aiEngine.models.download() }) { Text("Download the accurate model") }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { repo.requestAiForAll() }) {
                        Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Get lyrics for $remaining songs")
                    }
                }
            }

            if (!busy && oldAi > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "$oldAi " + (if (oldAi == 1) "song has" else "songs have") + " lyrics (or a \"no singing\" mark) from Melo's old AI, " +
                        "which often got Indian-language songs wrong. The new one does much better.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { repo.redoOldAi() }) {
                    Text(if (oldAi == 1) "Redo it with the new AI" else "Redo those $oldAi songs with the new AI")
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { container.settings.setAiOnlyWhileCharging(!settings.aiOnlyWhileCharging) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.BatteryChargingFull,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Only while charging", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Saves your battery and keeps the phone cool",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.aiOnlyWhileCharging,
                    onCheckedChange = { container.settings.setAiOnlyWhileCharging(it) },
                )
            }
        }
    }
}
