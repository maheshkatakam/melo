package com.maheshkatakam.melo.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maheshkatakam.melo.ui.LocalContainer
import com.maheshkatakam.melo.ui.LocalNavigator
import com.maheshkatakam.melo.ui.LocalSongMenu
import com.maheshkatakam.melo.ui.components.EmptyState
import com.maheshkatakam.melo.ui.components.GeneratedCover
import com.maheshkatakam.melo.ui.components.SectionHeader
import com.maheshkatakam.melo.ui.components.SongArtwork
import com.maheshkatakam.melo.ui.components.SongRow
import com.maheshkatakam.melo.ui.components.songCount
import java.text.Normalizer

private fun normalize(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()

@Composable
fun SearchScreen() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val songMenu = LocalSongMenu.current
    val focus = LocalFocusManager.current
    val songs by container.music.songs.collectAsStateWithLifecycle()
    val albums by container.music.albums.collectAsStateWithLifecycle()
    val artists by container.music.artists.collectAsStateWithLifecycle()
    val favorites by container.library.favoriteIds.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    val q = normalize(query.trim())
    val songResults = remember(q, songs) {
        if (q.isEmpty()) emptyList() else songs.filter {
            normalize(it.title).contains(q) || normalize(it.artist).contains(q) || normalize(it.album).contains(q)
        }.sortedBy { if (normalize(it.title).startsWith(q)) 0 else 1 }.take(200)
    }
    val albumResults = remember(q, albums) {
        if (q.isEmpty()) emptyList() else albums.filter { normalize(it.title).contains(q) }.take(20)
    }
    val artistResults = remember(q, artists) {
        if (q.isEmpty()) emptyList() else artists.filter { normalize(it.name).contains(q) }.take(20)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Songs, artists, albums") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .focusRequester(focusRequester),
        )

        if (q.isEmpty()) {
            EmptyState(Icons.Rounded.Search, "Search your music", "Find any song, artist or album on your phone. Works offline.")
        } else if (songResults.isEmpty() && albumResults.isEmpty() && artistResults.isEmpty()) {
            EmptyState(Icons.Rounded.Search, "No matches", "Nothing on your phone matches \"$query\".")
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (artistResults.isNotEmpty()) {
                item { SectionHeader("Artists") }
                items(artistResults, key = { "ar-${it.name}" }) { artist ->
                    ResultRow(
                        title = artist.name,
                        subtitle = songCount(artist.songs.size),
                        onClick = { navigator.openArtist(artist.name) },
                    ) {
                        GeneratedCover(artist.name, Modifier.fillMaxSize(), label = artist.name)
                    }
                }
            }
            if (albumResults.isNotEmpty()) {
                item { SectionHeader("Albums") }
                items(albumResults, key = { "al-${it.id}" }) { album ->
                    ResultRow(
                        title = album.title,
                        subtitle = album.artist,
                        onClick = { navigator.openAlbum(album.id) },
                    ) {
                        SongArtwork(album.cover, Modifier.fillMaxSize())
                    }
                }
            }
            if (songResults.isNotEmpty()) {
                item { SectionHeader("Songs") }
                itemsIndexed(songResults, key = { _, s -> "s-${s.id}" }) { index, song ->
                    SongRow(
                        song = song,
                        isFavorite = song.id in favorites,
                        onClick = {
                            focus.clearFocus()
                            container.player.play(songResults, index)
                        },
                        onMore = { songMenu.open(song, null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultRow(title: String, subtitle: String, onClick: () -> Unit, art: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp)),
        ) { art() }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
