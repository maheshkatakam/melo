package com.maheshkatakam.melo.ui.player

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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maheshkatakam.melo.ui.LocalContainer
import com.maheshkatakam.melo.ui.components.SongArtwork

@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val container = LocalContainer.current
    val now by container.player.state.collectAsStateWithLifecycle()
    val position by container.player.position.collectAsStateWithLifecycle()
    val progress = if (now.durationMs > 0) (position.toFloat() / now.durationMs).coerceIn(0f, 1f) else 0f

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen),
    ) {
        Column {
            Row(
                Modifier.padding(start = 8.dp, top = 8.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SongArtwork(now.song, Modifier.size(46.dp), shape = RoundedCornerShape(12.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        now.title.ifBlank { "Nothing playing" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        now.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { container.player.togglePlay() }) {
                    Icon(
                        if (now.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (now.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(30.dp),
                    )
                }
                IconButton(onClick = { container.player.next() }) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next", modifier = Modifier.size(28.dp))
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 6.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            )
        }
    }
}
