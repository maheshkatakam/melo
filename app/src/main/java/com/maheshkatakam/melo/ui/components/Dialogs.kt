@file:OptIn(ExperimentalMaterial3Api::class)

package com.maheshkatakam.melo.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.maheshkatakam.melo.ui.LocalContainer

/** Pick a playlist (or make a new one) for the given songs. */
@Composable
fun AddToPlaylistDialog(songIds: List<Long>, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val playlists by container.library.playlists.collectAsState()
    var creating by remember { mutableStateOf(playlists.isEmpty()) }
    if (creating) {
        TextInputDialog(
            title = "New playlist",
            label = "Playlist name",
            confirm = "Create",
            onDismiss = onDismiss,
            onConfirm = { name ->
                container.library.createPlaylist(name, songIds)
                onDismiss()
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { creating = true }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text("New playlist", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(playlists, key = { it.id }) { p ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    container.library.addToPlaylist(p.id, songIds)
                                    onDismiss()
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(p.name, style = MaterialTheme.typography.titleSmall)
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
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initial: String = "",
    singleLine: Boolean = true,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 6,
                maxLines = if (singleLine) 1 else 12,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
