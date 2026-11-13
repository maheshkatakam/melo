package com.maheshkatakam.melo.lyrics

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.maheshkatakam.melo.MainActivity
import com.maheshkatakam.melo.MeloApp
import com.maheshkatakam.melo.R
import com.maheshkatakam.melo.appContainer
import com.maheshkatakam.melo.lyrics.ai.AiStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps Melo alive while the AI writes lyrics, so "Get all lyrics" carries on after you
 * leave the app or turn the screen off. Shows progress and a Stop button in a notification.
 * Stops itself as soon as there is nothing left to do.
 */
class LyricsService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotification: android.app.Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        goForeground(build(title = "Getting lyrics ready", text = "Starting…", progress = -1, subText = null))

        val repo = appContainer.lyrics
        scope.launch {
            combine(repo.aiJob, repo.aiQueue, repo.waitingForCharger) { job, queue, waiting ->
                Triple(job, queue, waiting)
            }.collect { (job, queue, waiting) ->
                if (job == null && queue.isEmpty()) {
                    // Give the worker a moment in case another song is about to start.
                    delay(2_000)
                    if (repo.aiJob.value == null && repo.aiQueue.value.isEmpty()) {
                        finish()
                        return@collect
                    }
                }
                val left = queue.size
                val leftText = if (left > 0) "$left more to go" else null
                val notification = when {
                    job != null -> build(
                        title = "Getting lyrics",
                        text = job.title + when (job.stage) {
                            AiStage.SEARCHING_ONLINE -> " · looking online"
                            AiStage.DECODING -> " · reading the song"
                            AiStage.LOADING_MODEL -> " · waking up the AI"
                            AiStage.DETECTING_LANGUAGE -> " · working out the language"
                            AiStage.LISTENING -> " · ${job.overallPercent}%"
                        },
                        progress = job.overallPercent,
                        subText = leftText,
                    )
                    waiting -> build(
                        title = "English lyrics paused",
                        text = "The AI continues when you plug in your charger · $left songs left",
                        progress = null,
                        subText = null,
                    )
                    else -> build(title = "Getting lyrics ready", text = "Next song coming up…", progress = -1, subText = leftText)
                }
                updateNotification(notification)
                if (job != null || (queue.isNotEmpty() && !waiting)) holdWakeLock() else releaseWakeLock()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start via startForegroundService must be answered with startForeground.
        goForeground(lastNotification ?: build("Getting lyrics ready", "Starting…", -1, null))
        if (intent?.action == ACTION_STOP) {
            appContainer.lyrics.cancelAllAi()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun finish() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(notification: android.app.Notification) {
        lastNotification = notification
        val type = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (t: Throwable) {
            Log.w(TAG, "Couldn't enter the foreground", t)
        }
    }

    private fun updateNotification(notification: android.app.Notification) {
        lastNotification = notification
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
    }

    /** progress: 0..100, -1 for indeterminate, null for none. */
    private fun build(title: String, text: String, progress: Int?, subText: String?): android.app.Notification {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, LyricsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(subText)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                when {
                    progress == null -> Unit
                    progress < 0 -> setProgress(0, 0, true)
                    else -> setProgress(100, progress.coerceIn(0, 100), false)
                }
            }
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AI lyrics", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while Melo writes English lyrics"
                setShowBadge(false)
            },
        )
    }

    private fun holdWakeLock() {
        val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Melo:lyrics")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        // Re-armed on every update; the timeout is only a safety net.
        lock.acquire(30 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
    }

    companion object {
        private const val TAG = "MeloLyricsService"
        private const val CHANNEL_ID = "ai_lyrics"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP = "com.maheshkatakam.melo.STOP_LYRICS"

        /** Safe to call from anywhere; if Android won't allow it right now, work still runs in-app. */
        fun start(context: Context) {
            // Android refuses (and can crash the app) if this starts while Melo is off screen.
            // The work itself still runs; the notification appears next time Melo is opened.
            if (!MeloApp.isInForeground) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, LyricsService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "Couldn't start the lyrics service", t)
            }
        }
    }
}
