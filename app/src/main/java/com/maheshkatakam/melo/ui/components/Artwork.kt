package com.maheshkatakam.melo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import com.maheshkatakam.melo.art.SongArt
import com.maheshkatakam.melo.data.Song
import com.maheshkatakam.melo.ui.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val coverPalettes = listOf(
    listOf(Color(0xFFFF7A59), Color(0xFFB4235A)),
    listOf(Color(0xFF8B5CF6), Color(0xFF3B1E8A)),
    listOf(Color(0xFF38BDF8), Color(0xFF1E3A8A)),
    listOf(Color(0xFF34D399), Color(0xFF065F46)),
    listOf(Color(0xFFFBBF24), Color(0xFFB45309)),
    listOf(Color(0xFFF472B6), Color(0xFF7E22CE)),
    listOf(Color(0xFF60A5FA), Color(0xFF312E81)),
    listOf(Color(0xFFFB7185), Color(0xFF7F1D1D)),
    listOf(Color(0xFF2DD4BF), Color(0xFF134E4A)),
    listOf(Color(0xFFA3E635), Color(0xFF365314)),
)

fun paletteFor(seed: String): List<Color> = coverPalettes[(seed.hashCode() and 0x7fffffff) % coverPalettes.size]

/** A pleasant gradient cover used when a song has no picture anywhere. */
@Composable
fun GeneratedCover(
    seed: String,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val colors = remember(seed) { paletteFor(seed) }
    BoxWithConstraints(
        modifier = modifier.background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        val size = maxWidth
        if (label != null) {
            Text(
                text = label.take(1).uppercase(),
                color = Color.White.copy(alpha = 0.92f),
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.42f).sp,
            )
        } else {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(size * 0.42f),
            )
        }
    }
}

@Composable
fun SongArtwork(
    song: Song?,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    shape: Shape = RoundedCornerShape(10),
) {
    val container = LocalContainer.current
    val revision by container.artwork.revision.collectAsState()
    Box(modifier.clip(shape)) {
        GeneratedCover(seed = song?.album?.takeIf { it.isNotBlank() } ?: song?.title ?: "melo", modifier = Modifier.fillMaxSize())
        if (song != null) {
            val model = remember(song.id, song.dateModifiedSec, revision, large) {
                SongArt(song, large, container.artwork.version(song.id))
            }
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** The most fitting accent colour from a song's picture (or its generated cover). */
@Composable
fun rememberArtColor(song: Song?): Color? {
    val container = LocalContainer.current
    val revision by container.artwork.revision.collectAsState()
    val fallback = song?.let { paletteFor(it.album.ifBlank { it.title }).first() }
    val color by produceState<Color?>(initialValue = null, song?.id, revision) {
        value = if (song == null) null else withContext(Dispatchers.IO) {
            val bitmap = runCatching { container.artwork.loadBitmap(song, 160) }.getOrNull()
            if (bitmap == null) {
                null
            } else {
                val palette = Palette.from(bitmap).maximumColorCount(16).generate()
                val swatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch
                    ?: palette.mutedSwatch
                bitmap.recycle()
                swatch?.rgb?.let { Color(it) }
            }
        } ?: fallback
    }
    return color ?: fallback
}
