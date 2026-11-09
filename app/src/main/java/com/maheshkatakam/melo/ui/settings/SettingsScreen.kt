@file:OptIn(ExperimentalMaterial3Api::class)

package com.maheshkatakam.melo.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maheshkatakam.melo.BuildConfig
import com.maheshkatakam.melo.data.ThemeMode
import com.maheshkatakam.melo.lyrics.languageName
import com.maheshkatakam.melo.ui.LocalContainer
import com.maheshkatakam.melo.ui.LocalNavigator
import com.maheshkatakam.melo.ui.components.BackTopBar
import com.maheshkatakam.melo.ui.components.LyricsBatchCard
import com.maheshkatakam.melo.ui.components.songCount
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private val featuredLanguages = listOf("hi", "te", "ta", "kn", "mr", "bn", "gu", "ur", "es", "ko", "ja", "ar")

@Composable
fun SettingsScreen() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val songs by container.music.songs.collectAsStateWithLifecycle()
    val scanning by container.music.scanning.collectAsStateWithLifecycle()
    val aiCount by remember { container.lyrics.aiLyricsCount }.collectAsStateWithLifecycle(initialValue = 0)
    val downloaded by container.translator.downloaded.collectAsStateWithLifecycle()
    val downloading by container.translator.downloading.collectAsStateWithLifecycle()
    var showAllLanguages by remember { mutableStateOf(false) }
    var allFilesGranted by remember { mutableStateOf(hasAllFilesAccess()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allFilesGranted = hasAllFilesAccess() }
    LaunchedEffect(Unit) { container.translator.refreshDownloaded() }

    val languages = remember(showAllLanguages) {
        val all = container.translator.available
        val featured = featuredLanguages.filter { it in all }
        if (showAllLanguages) featured + all.filter { it !in featured }.sortedBy { languageName(it) } else featured
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Settings", onBack = { navigator.back() })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { Header("Appearance") }
            item {
                Group {
                    Text("Theme", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val options = listOf(ThemeMode.DARK to "Dark", ThemeMode.LIGHT to "Light", ThemeMode.SYSTEM to "System")
                        options.forEachIndexed { i, (mode, label) ->
                            SegmentedButton(
                                selected = settings.themeMode == mode,
                                onClick = { container.settings.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                            ) { Text(label) }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 31) {
                        SwitchRow("Wallpaper colours", "Use Material You colours from your wallpaper", settings.materialYou) {
                            container.settings.setMaterialYou(it)
                        }
                    }
                    SwitchRow("Colours from album art", "The player takes its colour from the song's picture", settings.artworkColors) {
                        container.settings.setArtworkColors(it)
                    }
                }
            }

            item { Header("Library") }
            item {
                Group {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Songs on this phone", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (scanning) "Scanning…" else songCount(songs.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { container.music.refresh() }, enabled = !scanning) { Text("Scan again") }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Ignore clips shorter than", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 15, 30, 60).forEach { sec ->
                            FilterChip(
                                selected = settings.minDurationSec == sec,
                                onClick = { container.settings.setMinDuration(sec) },
                                label = { Text(if (sec == 0) "Off" else "$sec s") },
                            )
                        }
                    }
                    if (settings.hiddenFolders.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Hidden folders", style = MaterialTheme.typography.titleSmall)
                        settings.hiddenFolders.sorted().forEach { path ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    path.removePrefix("/storage/emulated/0/"),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { container.settings.unhideFolder(path) }) { Text("Show") }
                            }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 30) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Lyric files & folder covers", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (allFilesGranted) "Allowed. Melo reads .lrc files and cover.jpg next to your songs."
                                    else "Allow file access so Melo can read .lrc lyric files and cover images next to your songs.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!allFilesGranted) {
                                TextButton(onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
                                        )
                                    }
                                }) { Text("Allow") }
                            } else {
                                Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            item { Header("Lyrics") }
            item {
                Group {
                    val reason = container.aiEngine.unavailableReason
                    Text("On-device AI lyrics", style = MaterialTheme.typography.titleSmall)
                    Text(
                        reason ?: "Listens to a song and writes the lyrics in English, from any language. " +
                            "Runs fully offline. Accuracy depends on how clear the vocals are. $aiCount songs done so far.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (reason == null) {
                        SwitchRow(
                            "Make lyrics as I listen",
                            "Automatically starts for songs you play that have no English lyrics",
                            settings.autoAiLyrics,
                        ) { container.settings.setAutoAiLyrics(it) }
                    }
                    SwitchRow(
                        "Show original language first",
                        "When a song has both, show the original lyrics before the English ones",
                        settings.preferOriginalLyrics,
                    ) { container.settings.setPreferOriginalLyrics(it) }
                }
            }
            item { LyricsBatchCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), showWhenDone = true) }

            item { Header("Translation packs") }
            item {
                Text(
                    "For lyrics stored as text in another language. Each pack downloads once (about 30 MB) and then translates offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            items(languages, key = { it }) { code ->
                val isDownloaded = code in downloaded
                val isDownloading = code in downloading
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(languageName(code), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when {
                                isDownloading -> "Downloading…"
                                isDownloaded -> "Ready offline"
                                else -> "Not downloaded"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when {
                        isDownloading -> CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp)
                        isDownloaded -> IconButton(onClick = { scope.launch { container.translator.delete(code) } }) {
                            Icon(Icons.Rounded.Delete, "Remove ${languageName(code)} pack")
                        }
                        else -> IconButton(onClick = {
                            scope.launch {
                                if (!container.translator.isOnline()) {
                                    android.widget.Toast.makeText(context, "Connect to the internet to download this pack", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    container.translator.download(code)
                                }
                            }
                        }) { Icon(Icons.Rounded.Download, "Download ${languageName(code)} pack") }
                    }
                }
            }
            item {
                TextButton(
                    onClick = { showAllLanguages = !showAllLanguages },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text(if (showAllLanguages) "Show fewer languages" else "Show all languages") }
            }

            item { Header("About") }
            item {
                Group {
                    Text("Melo ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Your music, pictures, lyrics and the AI all stay on this phone. The internet is only used if you download a translation pack.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun hasAllFilesAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp),
    )
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
