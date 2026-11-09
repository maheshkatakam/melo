package com.maheshkatakam.melo.playback

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.maheshkatakam.melo.MainActivity
import com.maheshkatakam.melo.R
import com.maheshkatakam.melo.appContainer
import com.maheshkatakam.melo.data.SavedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container by lazy { appContainer }

    private var playRecordJob: Job? = null
    private var saveJob: Job? = null
    private var sleepJob: Job? = null
    private var consecutiveErrors = 0

    override fun onCreate() {
        super.onCreate()
        val c = container

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(listener)
        c.effects.attach(player.audioSessionId)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            },
        )

        scope.launch {
            c.settings.settings.collect { s ->
                player.playbackParameters = PlaybackParameters(s.playbackSpeed, s.playbackPitch)
                player.skipSilenceEnabled = s.skipSilence
            }
        }
        scope.launch { c.sleepTimer.state.collect { handleSleepTimer(it) } }
        scope.launch {
            while (isActive) {
                delay(15_000)
                if (player.isPlaying) saveQueue()
            }
        }
        scope.launch { restoreQueue() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveQueue()
        scope.cancel()
        container.effects.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------

    private fun resolveId(mediaId: String): MediaItem? {
        val c = container
        MediaIds.songId(mediaId)?.let { id ->
            val song = c.music.song(id) ?: return fallbackMediaItem(id)
            return song.toMediaItem(c.artwork.customArtUri(id))
        }
        MediaIds.externalUri(mediaId)?.let { return externalMediaItem(it, null) }
        return null
    }

    private suspend fun restoreQueue() {
        if (player.mediaItemCount > 0) return
        val saved = container.settings.loadQueue() ?: return
        withTimeoutOrNull(4_000) { container.music.loadedOnce.first { it } }
        if (player.mediaItemCount > 0) return
        val items = saved.songIds.mapNotNull { resolveId(it) }
        if (items.isEmpty()) return
        val index = saved.index.coerceIn(0, items.lastIndex)
        player.setMediaItems(items, index, saved.positionMs.coerceAtLeast(0))
        player.prepare()
    }

    private fun saveQueue() {
        if (!::player.isInitialized) return
        val count = player.mediaItemCount
        val ids = (0 until count).map { player.getMediaItemAt(it).mediaId }
        container.settings.saveQueue(
            SavedQueue(ids, player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition.coerceAtLeast(0)),
        )
    }

    private fun saveQueueSoon() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1_500)
            saveQueue()
        }
    }

    /** Counts a play once the song has actually been listened to for a bit. */
    private fun schedulePlayRecord(item: MediaItem?) {
        playRecordJob?.cancel()
        val mediaId = item?.mediaId ?: return
        val songId = MediaIds.songId(mediaId) ?: return
        container.lyrics.onNowPlaying(songId)
        playRecordJob = scope.launch {
            while (isActive) {
                delay(2_000)
                if (player.currentMediaItem?.mediaId != mediaId) return@launch
                val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                if (player.currentPosition >= minOf(20_000L, duration / 2)) {
                    container.library.recordPlay(songId, container.effects.currentMood.value.id)
                    return@launch
                }
            }
        }
    }

    private fun handleSleepTimer(state: SleepTimerState) {
        sleepJob?.cancel()
        player.pauseAtEndOfMediaItems = state is SleepTimerState.EndOfSong
        if (state !is SleepTimerState.At) {
            player.volume = 1f
            return
        }
        sleepJob = scope.launch {
            val fadeMs = 12_000L
            val wait = state.endsAtMillis - System.currentTimeMillis() - fadeMs
            if (wait > 0) delay(wait)
            val steps = 24
            for (i in steps downTo 0) {
                player.volume = i / steps.toFloat()
                delay(fadeMs / steps)
            }
            player.pause()
            player.volume = 1f
            container.sleepTimer.cancel()
        }
    }

    private val listener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            container.effects.attach(audioSessionId)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            consecutiveErrors = 0
            schedulePlayRecord(mediaItem)
            saveQueueSoon()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            saveQueueSoon()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveQueue()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                container.sleepTimer.state.value is SleepTimerState.EndOfSong
            ) {
                container.sleepTimer.cancel()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w("MeloPlayback", "Playback error, skipping", error)
            consecutiveErrors++
            if (consecutiveErrors < 5 && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.mapNotNull { item ->
                resolveId(item.mediaId) ?: item.takeIf { it.localConfiguration != null }
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                val saved = container.settings.loadQueue()
                withTimeoutOrNull(4_000) { container.music.loadedOnce.first { it } }
                val items = saved?.songIds?.mapNotNull { resolveId(it) }.orEmpty()
                if (saved == null || items.isEmpty()) {
                    future.setException(IllegalStateException("Nothing to resume"))
                } else {
                    future.set(
                        MediaSession.MediaItemsWithStartPosition(
                            items,
                            saved.index.coerceIn(0, items.lastIndex),
                            saved.positionMs,
                        ),
                    )
                }
            }
            return future
        }
    }
}
