@file:OptIn(ExperimentalMaterial3Api::class)

package com.maheshkatakam.melo.ui.player

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maheshkatakam.melo.data.Song
import com.maheshkatakam.melo.lyrics.AiJob
import com.maheshkatakam.melo.lyrics.Lyrics
import com.maheshkatakam.melo.lyrics.LyricsSource
import com.maheshkatakam.melo.lyrics.LrcParser
import com.maheshkatakam.melo.lyrics.ai.AiStage
import com.maheshkatakam.melo.lyrics.isEnglish
import com.maheshkatakam.melo.lyrics.languageName
import com.maheshkatakam.melo.ui.LocalContainer
import com.maheshkatakam.melo.ui.components.TextInputDialog
import kotlinx.coroutines.launch

@Composable
fun LyricsPanel(song: Song, position: Long, accent: Color, modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val repo = container.lyrics
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entity by remember(song.id) { repo.observe(song.id) }.collectAsStateWithLifecycle(initialValue = null)
    val aiJob by repo.aiJob.collectAsStateWithLifecycle()
    val queue by repo.aiQueue.collectAsStateWithLifecycle()
    val downloading by repo.translator.downloading.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var showOriginal by rememberSaveable(song.id) { mutableStateOf(settings.preferOriginalLyrics) }
    var pasting by remember { mutableStateOf(false) }

    LaunchedEffect(song.id) { repo.ensureScanned(song) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = repo.importFile(song, uri)
                Toast.makeText(context, if (ok) "Lyrics added" else "That file had no lyrics in it", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val importLrc = { importLauncher.launch(arrayOf("*/*")) }
    val startAi = { repo.requestAi(song) }

    if (pasting) {
        TextInputDialog(
            title = "Paste lyrics",
            label = "Lyrics (plain or LRC)",
            confirm = "Save",
            singleLine = false,
            onDismiss = { pasting = false },
            onConfirm = { text ->
                pasting = false
                scope.launch { repo.paste(song, text) }
            },
        )
    }

    val e = entity
    val original = remember(e?.original) { e?.original?.let { LrcParser.parse(it) } }
    val english = remember(e?.english) { e?.english?.let { LrcParser.parse(it) } }
    val running = aiJob?.takeIf { it.songId == song.id }
    val queued = song.id in queue

    Box(modifier) {
        when {
            e == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = accent)
            }
            running != null -> AiProgress(running, accent) { repo.cancelAi(song.id) }
            original == null && english == null -> NoLyrics(
                aiFailed = e.source == LyricsSource.AI_FAILED,
                batchRunning = queue.size > 1 || (aiJob != null && queue.isNotEmpty()),
                onAll = { repo.requestAiForAll() },
                queued = queued,
                queuePosition = queue.indexOf(song.id),
                aiReason = container.aiEngine.unavailableReason,
                accent = accent,
                onAi = startAi,
                onCancelQueue = { repo.cancelAi(song.id) },
                onImport = importLrc,
                onPaste = { pasting = true },
            )
            else -> {
                val showingEnglish = english != null && !(showOriginal && original != null)
                val lines: Lyrics = if (showingEnglish) english!! else original!!
                Column(Modifier.fillMaxSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (english != null && original != null && !isEnglish(e.language)) {
                            FilterChip(
                                selected = showingEnglish,
                                onClick = { showOriginal = false },
                                label = { Text("English") },
                                colors = chipColors(accent),
                            )
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected = !showingEnglish,
                                onClick = { showOriginal = true },
                                label = { Text(languageName(e.language).substringBefore(" (")) },
                                colors = chipColors(accent),
                            )
                        } else {
                            Text(
                                sourceLabel(e.source, e.language, english != null),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        LyricsMenu(
                            hasAi = e.source == LyricsSource.AI,
                            aiSupported = container.aiEngine.isSupported,
                            onAi = startAi,
                            onImport = importLrc,
                            onPaste = { pasting = true },
                            onRescan = { scope.launch { repo.rescan(song) } },
                            onRemove = { scope.launch { repo.clear(song) } },
                        )
                    }
                    if (english != null && original != null && !isEnglish(e.language)) {
                        Text(
                            sourceLabel(e.source, e.language, true),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (english == null && original != null && !isEnglish(e.language)) {
                        val code = repo.translator.translatable(e.language)
                        TranslateCard(
                            languageLabel = languageName(e.language),
                            canTranslateText = code != null,
                            downloading = code != null && code in downloading,
                            queuedForAi = queued,
                            aiSupported = container.aiEngine.isSupported,
                            accent = accent,
                            onDownload = { code?.let { repo.downloadPackAndTranslate(song.id, it) } },
                            onAi = startAi,
                        )
                    }
                    LyricsLines(
                        lyrics = lines,
                        position = position,
                        accent = accent,
                        onSeek = { container.player.seekTo(it) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun sourceLabel(source: String, language: String?, hasEnglish: Boolean): String = when {
    source == LyricsSource.AI -> "Written by on-device AI from the audio · may contain mistakes"
    hasEnglish && !isEnglish(language) -> "Translated on your phone from ${languageName(language)}"
    source == LyricsSource.LRC_FILE -> "From the .lrc file next to this song"
    source == LyricsSource.EMBEDDED -> "Stored inside the song file"
    source == LyricsSource.IMPORTED -> "Imported lyrics"
    source == LyricsSource.PASTED -> "Pasted lyrics"
    else -> ""
}

@Composable
private fun chipColors(accent: Color) = FilterChipDefaults.filterChipColors(
    selectedContainerColor = accent.copy(alpha = 0.25f),
    selectedLabelColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
private fun LyricsLines(
    lyrics: Lyrics,
    position: Long,
    accent: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val synced = lyrics.synced
    val current = if (synced) lyrics.lines.indexOfLast { (it.timeMs ?: 0L) <= position + 300 } else -1
    LaunchedEffect(current) {
        if (synced && current >= 0 && !listState.isScrollInProgress) {
            listState.animateScrollToItem((current - 2).coerceAtLeast(0))
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(if (synced) 16.dp else 6.dp),
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            val active = index == current
            val target = when {
                !synced -> MaterialTheme.colorScheme.onSurface
                active -> MaterialTheme.colorScheme.onSurface
                index < current -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            }
            val color by animateColorAsState(target, tween(250), label = "lyric")
            Text(
                text = line.text.ifBlank { "♪" },
                style = if (synced) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.Bold else if (synced) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) lerpAccent(color, accent) else color,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = synced && line.timeMs != null) { line.timeMs?.let(onSeek) },
            )
        }
    }
}

private fun lerpAccent(base: Color, accent: Color): Color =
    androidx.compose.ui.graphics.lerp(base, accent, 0.25f)

@Composable
private fun NoLyrics(
    aiFailed: Boolean,
    batchRunning: Boolean,
    onAll: () -> Unit,
    queued: Boolean,
    queuePosition: Int,
    aiReason: String?,
    accent: Color,
    onAi: () -> Unit,
    onCancelQueue: () -> Unit,
    onImport: () -> Unit,
    onPaste: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = accent, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            if (aiFailed) "The AI couldn't hear any singing" else "No lyrics stored in this song",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        when {
            queued -> {
                Text(
                    if (queuePosition <= 0) "Next in line for AI lyrics." else "Waiting for AI lyrics · ${queuePosition} ahead",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onCancelQueue) { Text("Cancel") }
            }
            aiReason == null -> {
                Text(
                    if (aiFailed) {
                        "It may be an instrumental, or the vocals are hard to pick out. You can try again."
                    } else {
                        "Melo can listen to this song on your phone and write the lyrics in English, " +
                            "even if it's sung in another language. It takes a few minutes and you can keep listening."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = onAi,
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (aiFailed) "Try again" else "Make English lyrics")
                }
                if (!batchRunning) {
                    TextButton(onClick = onAll) { Text("Do all my songs instead") }
                }
            }
            else -> Text(
                aiReason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Have the real lyrics? Add them yourself:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onImport) {
                Icon(Icons.Rounded.Description, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Import .lrc")
            }
            OutlinedButton(onClick = onPaste) {
                Icon(Icons.Rounded.ContentPaste, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Paste")
            }
        }
    }
}

@Composable
private fun AiProgress(job: AiJob, accent: Color, onCancel: () -> Unit) {
    val (label, fraction) = when (job.stage) {
        AiStage.DECODING -> "Reading the song… ${job.percent}%" to job.percent / 100f * 0.15f
        AiStage.LOADING_MODEL -> "Waking up the AI model…" to 0.15f
        AiStage.LISTENING -> "Listening and writing English lyrics… ${job.percent}%" to (0.15f + job.percent / 100f * 0.85f)
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = accent, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(16.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            color = accent,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "This runs entirely on your phone, no internet needed. Keep listening — the lyrics appear here when ready.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun TranslateCard(
    languageLabel: String,
    canTranslateText: Boolean,
    downloading: Boolean,
    queuedForAi: Boolean,
    aiSupported: Boolean,
    accent: Color,
    onDownload: () -> Unit,
    onAi: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Translate, null, tint = accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("These lyrics are in $languageLabel", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (canTranslateText) {
                    "Download the $languageLabel pack once (about 30 MB). After that, every song in $languageLabel is translated offline."
                } else {
                    "Melo can't translate this text offline, but the AI can listen and write English lyrics."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canTranslateText) {
                    Button(
                        onClick = onDownload,
                        enabled = !downloading,
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                    ) {
                        if (downloading) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("Downloading…")
                        } else {
                            Text("Download pack")
                        }
                    }
                }
                if (aiSupported) {
                    TextButton(onClick = onAi, enabled = !queuedForAi) {
                        Text(if (queuedForAi) "Queued for AI" else "Use AI instead")
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsMenu(
    hasAi: Boolean,
    aiSupported: Boolean,
    onAi: () -> Unit,
    onImport: () -> Unit,
    onPaste: () -> Unit,
    onRescan: () -> Unit,
    onRemove: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreHoriz, "Lyrics options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (aiSupported) {
                DropdownMenuItem(
                    text = { Text(if (hasAi) "Redo AI lyrics" else "Make English lyrics with AI") },
                    leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null) },
                    onClick = {
                        open = false
                        onAi()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Import .lrc file") },
                leadingIcon = { Icon(Icons.Rounded.Description, null) },
                onClick = {
                    open = false
                    onImport()
                },
            )
            DropdownMenuItem(
                text = { Text("Paste lyrics") },
                leadingIcon = { Icon(Icons.Rounded.ContentPaste, null) },
                onClick = {
                    open = false
                    onPaste()
                },
            )
            DropdownMenuItem(
                text = { Text("Check the song file again") },
                leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                onClick = {
                    open = false
                    onRescan()
                },
            )
            DropdownMenuItem(
                text = { Text("Remove lyrics") },
                leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                onClick = {
                    open = false
                    onRemove()
                },
            )
        }
    }
}
