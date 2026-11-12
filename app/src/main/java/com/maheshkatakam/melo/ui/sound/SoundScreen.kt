@file:OptIn(ExperimentalMaterial3Api::class)

package com.maheshkatakam.melo.ui.sound

import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maheshkatakam.melo.audio.Moods
import com.maheshkatakam.melo.audio.SoundProfile
import com.maheshkatakam.melo.ui.LocalContainer
import com.maheshkatakam.melo.ui.LocalNavigator
import com.maheshkatakam.melo.ui.components.BackTopBar
import com.maheshkatakam.melo.ui.components.ConfirmDialog
import com.maheshkatakam.melo.ui.player.MoodTile
import java.util.Locale

@Composable
fun SoundScreen() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val effects = container.effects
    val moods by effects.moods.collectAsStateWithLifecycle()
    val current by effects.currentMood.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val headphones by effects.headphones.collectAsStateWithLifecycle()
    val restore by effects.restore.collectAsStateWithLifecycle()

    var editing by remember(current.id, current.profile) { mutableStateOf(current.profile) }
    val dirty = editing != current.profile
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    LaunchedEffect(editing, dirty) { effects.preview(if (dirty) editing else null) }
    DisposableEffect(Unit) { onDispose { effects.preview(null) } }

    if (saving) {
        SaveMoodDialog(
            initialName = if (current.custom) current.name else "",
            canUpdate = current.custom,
            onDismiss = { saving = false },
            onSave = { name, icon, asNew ->
                saving = false
                effects.saveCustomMood(name, icon, editing, existingId = if (current.custom && !asNew) current.id else null)
            },
        )
    }
    if (deleting) {
        ConfirmDialog(
            title = "Delete \"${current.name}\"?",
            message = "This custom mood will be removed.",
            confirm = "Delete",
            onDismiss = { deleting = false },
            onConfirm = { effects.deleteCustomMood(current.id) },
        )
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Sound & moods", onBack = { navigator.back() })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(moods, key = { it.id }) { mood ->
                        MoodTile(mood, selected = mood.id == current.id, modifier = Modifier.width(104.dp)) {
                            effects.setMood(mood.id)
                        }
                    }
                }
            }
            item {
                Text(
                    current.details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            item {
                Card("3D sound") {
                    ToggleRow(
                        "3D sound for headphones",
                        when {
                            !settings.immersive -> "Places the music around you: a wide stage in front, ambience at your sides, behind and above"
                            headphones || (settings.systemSpatial && effects.phoneSpatialSupported) -> "On. Works with any mood"
                            else -> "On, waiting for headphones. It doesn't work on speakers"
                        },
                        settings.immersive,
                    ) { container.settings.setImmersive(it) }
                    if (settings.immersive) {
                        var strength by remember(settings.immersiveStrength) { mutableFloatStateOf(settings.immersiveStrength) }
                        LabeledSlider(
                            title = "How far it surrounds you",
                            detail = null,
                            valueText = "${Math.round(strength * 100)}%",
                            value = strength,
                            range = 0f..1f,
                            onChange = { strength = it },
                            onFinished = { container.settings.setImmersiveStrength(strength) },
                        )
                        if (effects.phoneSpatialSupported) {
                            ToggleRow(
                                "Use this phone's spatial audio (experimental)",
                                "Melo turns the song into 5.1 surround and your phone renders it. This is what allows head tracking " +
                                    "on earbuds that support it. Turn it off if the sound goes wrong.",
                                settings.systemSpatial,
                            ) { container.settings.setSystemSpatial(it) }
                        }
                    }
                    Note("Melo's own effect, made from the stereo song as it plays. It is not Dolby Atmos. Real 5.1 and 7.1 files are played as speakers around you.")
                }
            }
            item {
                Card("Quality") {
                    ToggleRow(
                        "Rebuild missing treble",
                        restore?.let {
                            "This song's sound stops at " + String.format(Locale.US, "%.1f kHz", it.cutoffHz / 1000f) +
                                "; Melo is filling in the top. A best guess, not the original"
                        } ?: "Low-quality files (like 128 kbps) have their highest notes cut off. Melo fills them back in as a best guess. Good files are left alone",
                        settings.restoreTreble,
                    ) { container.settings.setRestoreTreble(it) }
                    ToggleRow(
                        "Hi-res direct",
                        "24-bit files skip all of Melo's processing and play at full precision, so moods and 3D don't apply to them. " +
                            "Takes effect after you close and reopen Melo",
                        settings.hiResDirect,
                    ) { container.settings.setHiResDirect(it) }
                }
            }
            item {
                Card("Tone") {
                    SoundProfile.BAND_LABELS.forEachIndexed { i, label ->
                        LabeledSlider(
                            title = SoundProfile.BAND_NAMES[i],
                            detail = label,
                            valueText = String.format(Locale.US, "%+.1f dB", editing.bands[i]),
                            value = editing.bands[i],
                            range = -SoundProfile.MAX_DB..SoundProfile.MAX_DB,
                            onChange = { v ->
                                editing = editing.copy(bands = editing.bands.toMutableList().also { it[i] = Math.round(v * 2) / 2f })
                            },
                        )
                    }
                    LabeledSlider(
                        title = "Bass punch",
                        detail = "Kick and sub",
                        valueText = String.format(Locale.US, "+%.1f dB", editing.bass),
                        value = editing.bass,
                        range = 0f..10f,
                        onChange = { editing = editing.copy(bass = Math.round(it * 2) / 2f) },
                    )
                }
            }
            item {
                Card("Space") {
                    LabeledSlider(
                        title = "Stereo width",
                        detail = "100% = as recorded",
                        valueText = "${Math.round(editing.width * 100)}%",
                        value = editing.width,
                        range = 0.5f..1.6f,
                        onChange = { editing = editing.copy(width = Math.round(it * 20) / 20f) },
                    )
                    LabeledSlider(
                        title = "Depth",
                        detail = "A light widening that also works on speakers",
                        valueText = "${Math.round(editing.spatial * 100)}%",
                        value = editing.spatial,
                        range = 0f..1f,
                        onChange = { editing = editing.copy(spatial = Math.round(it * 20) / 20f) },
                    )
                    LabeledSlider(
                        title = "Room",
                        detail = "Ambience around the music",
                        valueText = "${Math.round(editing.reverb * 100)}%",
                        value = editing.reverb,
                        range = 0f..0.6f,
                        onChange = { editing = editing.copy(reverb = Math.round(it * 50) / 50f) },
                    )
                    if (editing.reverb > 0f) {
                        LabeledSlider(
                            title = "Room size",
                            detail = "Small room to big hall",
                            valueText = "${Math.round(editing.room * 100)}%",
                            value = editing.room,
                            range = 0f..1f,
                            onChange = { editing = editing.copy(room = Math.round(it * 20) / 20f) },
                        )
                    }
                }
            }
            item {
                Card("Feel") {
                    LabeledSlider(
                        title = "Warmth",
                        detail = "Soft, analogue-style richness",
                        valueText = "${Math.round(editing.warmth * 100)}%",
                        value = editing.warmth,
                        range = 0f..1f,
                        onChange = { editing = editing.copy(warmth = Math.round(it * 20) / 20f) },
                    )
                    LabeledSlider(
                        title = "Even out volume",
                        detail = "Quiet parts up, loud parts down",
                        valueText = "${Math.round(editing.levelling * 100)}%",
                        value = editing.levelling,
                        range = 0f..1f,
                        onChange = { editing = editing.copy(levelling = Math.round(it * 20) / 20f) },
                    )
                    LabeledSlider(
                        title = "Loudness",
                        detail = "Protected from distortion",
                        valueText = String.format(Locale.US, "%+.1f dB", editing.gainDb),
                        value = editing.gainDb,
                        range = -8f..6f,
                        onChange = { editing = editing.copy(gainDb = Math.round(it * 2) / 2f) },
                    )
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { saving = true }, enabled = dirty || !current.custom) {
                        Text(if (current.custom) "Save" else "Save as my mood")
                    }
                    if (dirty) {
                        OutlinedButton(onClick = { editing = current.profile }) { Text("Reset") }
                    }
                    Spacer(Modifier.weight(1f))
                    if (current.custom) {
                        TextButton(onClick = { deleting = true }) {
                            Icon(Icons.Rounded.Delete, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }
                }
                if (dirty) {
                    Note(
                        "You're hearing your changes now. Save them to keep them as a mood.",
                        Modifier.padding(horizontal = 20.dp),
                    )
                }
            }

            item {
                Card("Playback") {
                    var speed by remember(settings.playbackSpeed) { mutableFloatStateOf(settings.playbackSpeed) }
                    var pitch by remember(settings.playbackPitch) { mutableFloatStateOf(settings.playbackPitch) }
                    LabeledSlider(
                        title = "Speed",
                        detail = null,
                        valueText = String.format(Locale.US, "%.2fx", speed),
                        value = speed,
                        range = 0.5f..2f,
                        steps = 5,
                        onChange = { speed = it },
                        onFinished = { container.settings.setSpeed(speed) },
                    )
                    LabeledSlider(
                        title = "Pitch",
                        detail = null,
                        valueText = String.format(Locale.US, "%.2f", pitch),
                        value = pitch,
                        range = 0.75f..1.25f,
                        steps = 9,
                        onChange = { pitch = it },
                        onFinished = { container.settings.setPitch(pitch) },
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { container.settings.setSkipSilence(!settings.skipSilence) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Skip silence", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Trims quiet gaps between and inside songs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = settings.skipSilence, onCheckedChange = { container.settings.setSkipSilence(it) })
                    }
                    if (speed != 1f || pitch != 1f) {
                        TextButton(onClick = {
                            container.settings.setSpeed(1f)
                            container.settings.setPitch(1f)
                        }) { Text("Back to normal speed and pitch") }
                    }
                }
            }
            item {
                Note(
                    "Moods run on Melo's own sound engine, so they sound the same on every phone and headphone. " +
                        "Normal leaves the audio completely untouched. If your phone has its own effects (like Dolby), " +
                        "they are added on top; turn them off for the cleanest result.",
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun LabeledSlider(
    title: String,
    detail: String?,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    steps: Int = 0,
    onFinished: (() -> Unit)? = null,
) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (detail != null) {
                Text(
                    "  ·  $detail",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            onValueChangeFinished = onFinished,
        )
    }
}

@Composable
private fun SaveMoodDialog(
    initialName: String,
    canUpdate: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, icon: String, asNew: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var icon by remember { mutableStateOf(Moods.customIcons.keys.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (canUpdate) "Save mood" else "Name your mood") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    label = { Text("Mood name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                Text("Icon", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Moods.customIcons.entries.toList(), key = { it.key }) { (key, vector) ->
                        val selected = key == icon
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .then(
                                    if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier,
                                )
                                .clickable { icon = key },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                vector,
                                null,
                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, icon, !canUpdate) }, enabled = name.isNotBlank()) {
                Text(if (canUpdate) "Update" else "Save")
            }
        },
        dismissButton = {
            if (canUpdate) {
                TextButton(onClick = { onSave(name, icon, true) }, enabled = name.isNotBlank()) { Text("Save as new") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
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
