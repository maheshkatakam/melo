package com.maheshkatakam.melo.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maheshkatakam.melo.lyrics.ai.SongLanguages

/**
 * "What language is this song sung in?" Picking one tells the AI exactly what to listen for,
 * which is the single biggest help for regional songs. [onPick] gets null for "work it out".
 */
@Composable
fun SongLanguageDialog(
    current: String?,
    yourLanguages: Set<String>,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val preferred = yourLanguages.ifEmpty { SongLanguages.defaultCodes }
    val ordered = SongLanguages.all.sortedBy { if (it.code in preferred) 0 else 1 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Song language") },
        text = {
            Column {
                Text(
                    "Tell the AI which language this song is sung in. It writes the words in that language, and Melo translates them to English.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    item { LanguageRow("Let Melo work it out", current == null) { onPick(null) } }
                    items(ordered, key = { it.code }) { lang ->
                        LanguageRow(lang.name, current == lang.code) { onPick(lang.code) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LanguageRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Chips for "the languages my songs are in", used to narrow the AI's guesses. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SongLanguageChips(selected: Set<String>, onChange: (Set<String>) -> Unit) {
    val effective = selected.ifEmpty { SongLanguages.defaultCodes }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        SongLanguages.all.forEach { lang ->
            val on = lang.code in effective
            FilterChip(
                selected = on,
                onClick = {
                    val next = if (on) effective - lang.code else effective + lang.code
                    if (next.isNotEmpty()) onChange(next)
                },
                label = { Text(lang.name) },
            )
        }
    }
}
