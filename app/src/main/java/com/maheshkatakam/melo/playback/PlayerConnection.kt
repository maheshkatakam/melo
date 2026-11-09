package com.maheshkatakam.melo.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.maheshkatakam.melo.art.ArtworkRepository
import com.maheshkatakam.melo.data.MusicRepository
import com.maheshkatakam.melo.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Immutable
data class QueueEntry(
    val index: Int,
    val mediaId: String,
    val song: Song?,
    val title: String,
    val artist: String,
)

@Immutable
data class NowPlaying(
    val song: Song? = null,
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    val hasContent: Boolean = false,
)

/** The UI's handle on the playback service. Must be used from the main thread. */
class PlayerConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val music: MusicRepository,
    private val artwork: ArtworkRepository,
) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private var cachedQueue: List<QueueEntry> = emptyList()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val queueChanged = events.containsAny(
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
            )
            refresh(rebuildQueue = queueChanged)
        }
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull()
            if (c == null) {
                future = null
                return@addListener
            }
            controller = c
            c.addListener(listener)
            refresh(rebuildQueue = true)
            val waiting = pending.toList()
            pending.clear()
            waiting.forEach { it(c) }
        }, ContextCompat.getMainExecutor(context))

        scope.launch(Dispatchers.Main) {
            while (isActive) {
                controller?.let { _position.value = it.currentPosition.coerceAtLeast(0) }
                delay(if (_state.value.isPlaying) 250 else 1_000)
            }
        }
        scope.launch(Dispatchers.Main) {
            // Re-resolve titles once the library finishes loading.
            music.songs.collect { refresh(rebuildQueue = true) }
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else {
            pending += block
            connect()
        }
    }

    private fun refresh(rebuildQueue: Boolean) {
        val c = controller ?: return
        val mediaId = c.currentMediaItem?.mediaId
        val song = MediaIds.songId(mediaId)?.let { music.song(it) }
        val metadata = c.mediaMetadata
        if (rebuildQueue) cachedQueue = buildQueue(c)
        _state.value = NowPlaying(
            song = song,
            mediaId = mediaId,
            title = song?.title ?: metadata.title?.toString() ?: metadata.displayTitle?.toString() ?: "",
            artist = song?.artist ?: metadata.artist?.toString() ?: "",
            isPlaying = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: song?.durationMs ?: 0,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            queue = cachedQueue,
            currentIndex = c.currentMediaItemIndex,
            hasContent = c.mediaItemCount > 0,
        )
        _position.value = c.currentPosition.coerceAtLeast(0)
    }

    /** Queue in the order it will actually play (respects shuffle). */
    private fun buildQueue(c: MediaController): List<QueueEntry> {
        val timeline = c.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val shuffle = c.shuffleModeEnabled
        val out = ArrayList<QueueEntry>(c.mediaItemCount)
        var i = timeline.getFirstWindowIndex(shuffle)
        var guard = 0
        while (i != C.INDEX_UNSET && guard++ < 20_000) {
            val item = c.getMediaItemAt(i)
            val song = MediaIds.songId(item.mediaId)?.let { music.song(it) }
            out += QueueEntry(
                index = i,
                mediaId = item.mediaId,
                song = song,
                title = song?.title ?: item.mediaMetadata.title?.toString() ?: "Unknown",
                artist = song?.artist ?: item.mediaMetadata.artist?.toString() ?: "",
            )
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
        }
        return out
    }

    // ----- Commands --------------------------------------------------------------------------

    fun play(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false) = withController { c ->
        if (songs.isEmpty()) return@withController
        val list = if (shuffle) songs.shuffled() else songs
        val start = if (shuffle) 0 else startIndex.coerceIn(0, songs.lastIndex)
        c.shuffleModeEnabled = false
        c.setMediaItems(list.map { it.toMediaItem(artwork.customArtUri(it.id)) }, start, 0L)
        c.prepare()
        c.play()
    }

    fun playExternal(uri: Uri, title: String?) = withController { c ->
        c.setMediaItem(externalMediaItem(uri, title))
        c.prepare()
        c.play()
    }

    fun togglePlay() = withController { c ->
        if (c.mediaItemCount == 0) return@withController
        if (c.playbackState == Player.STATE_ENDED) {
            c.seekToDefaultPosition(0)
            c.play()
        } else if (c.playbackState == Player.STATE_IDLE) {
            c.prepare()
            c.play()
        } else if (c.isPlaying) {
            c.pause()
        } else {
            c.play()
        }
    }

    fun pause() = withController { it.pause() }
    fun resume() = withController { if (it.mediaItemCount > 0) it.play() }

    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }

    fun seekTo(positionMs: Long) = withController {
        it.seekTo(positionMs)
        _position.value = positionMs
    }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun playNext(songs: List<Song>) = withController { c ->
        if (songs.isEmpty()) return@withController
        val items = songs.map { it.toMediaItem(artwork.customArtUri(it.id)) }
        if (c.mediaItemCount == 0) {
            c.setMediaItems(items)
            c.prepare()
            c.play()
        } else {
            c.addMediaItems(c.currentMediaItemIndex + 1, items)
        }
    }

    fun addToQueue(songs: List<Song>) = withController { c ->
        if (songs.isEmpty()) return@withController
        val items = songs.map { it.toMediaItem(artwork.customArtUri(it.id)) }
        if (c.mediaItemCount == 0) {
            c.setMediaItems(items)
            c.prepare()
            c.play()
        } else {
            c.addMediaItems(items)
        }
    }

    fun skipTo(index: Int) = withController { c ->
        if (index !in 0 until c.mediaItemCount) return@withController
        c.seekToDefaultPosition(index)
        c.play()
    }

    fun removeAt(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    fun clearUpcoming() = withController { c ->
        val current = c.currentMediaItemIndex
        val count = c.mediaItemCount
        if (count == 0) return@withController
        // Keep only the current song.
        if (current + 1 < count) c.removeMediaItems(current + 1, count)
        if (current > 0) c.removeMediaItems(0, current)
    }
}
