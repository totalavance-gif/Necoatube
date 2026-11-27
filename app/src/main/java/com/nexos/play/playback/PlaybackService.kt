package com.nexos.play.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nexos.play.R
import com.nexos.play.data.YouTubeRepo
import com.nexos.play.ui.PlayerActivity
import com.nexos.play.util.Thumbs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Servicio en primer plano: mantiene el audio con la app minimizada. */
class PlaybackService : Service() {

    companion object {
        const val ACTION_PLAY = "com.nexos.play.PLAY"
        const val ACTION_PAUSE = "com.nexos.play.PAUSE"
        const val ACTION_CLOSE = "com.nexos.play.CLOSE"
        private const val CHANNEL = "nexosplay_playback"
        private const val NOTIF_ID = 41

        fun start(ctx: Context) {
            val intent = Intent(ctx, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    private var watched: ExoPlayer? = null
    private var artUrl: String? = null
    private var artBitmap: android.graphics.Bitmap? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            showNotification()
        }

        override fun onPlaybackStateChanged(state: Int) {
            showNotification()
            if (state == Player.STATE_ENDED && !PlayerHolder.uiActive) advanceHeadless()
        }
    }

    private fun advanceHeadless() {
        val next = PlayerHolder.nextUp() ?: return
        scope.launch {
            runCatching {
                val p = YouTubeRepo.resolve(next.url)
                PlayerHolder.playable = p
                PlayerHolder.pageUrl = next.url
                PlayerHolder.qualityIndex = 0
                PlayerHolder.queue = p.related
                PlayerHolder.ensure(this@PlaybackService)
                PlayerHolder.playMedia(p, PlayerHolder.audioOnly, 0)
                showNotification()
                PlayerHolder.nextUp()?.let { following ->
                    launch { runCatching { YouTubeRepo.resolve(following.url) } }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> PlayerHolder.player?.let {
                if (it.playbackState == Player.STATE_ENDED) it.seekTo(0)
                it.playWhenReady = true
            }
            ACTION_PAUSE -> PlayerHolder.player?.playWhenReady = false
            ACTION_CLOSE -> {
                PlayerHolder.reset()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        watch(PlayerHolder.player)
        showNotification()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (PlayerHolder.player?.playWhenReady != true) {
            PlayerHolder.reset()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        watched?.removeListener(listener)
        watched = null
        super.onDestroy()
    }

    private fun watch(p: ExoPlayer?) {
        if (watched !== p) {
            watched?.removeListener(listener)
            watched = p
            p?.addListener(listener)
        }
    }

    private fun actionIntent(action: String, code: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showNotification() {
        val playable = PlayerHolder.playable
        val playing = PlayerHolder.player?.playWhenReady == true
        val thumb = PlayerHolder.currentThumb
        if (thumb != artUrl) {
            artUrl = thumb
            artBitmap = null
            Thumbs.loadBitmap(thumb) { bmp ->
                if (bmp != null && PlayerHolder.currentThumb == thumb) {
                    artBitmap = bmp
                    showNotification()
                }
            }
        }
        val open = PlayerActivity.intent(
            this, PlayerHolder.pageUrl, playable?.title.orEmpty()
        ).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val content = PendingIntent.getActivity(
            this, 10, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggle = if (playing) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, getString(R.string.notif_pause),
                actionIntent(ACTION_PAUSE, 11)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, getString(R.string.notif_play),
                actionIntent(ACTION_PLAY, 11)
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(artBitmap)
            .setContentTitle(playable?.title ?: getString(R.string.app_name))
            .setContentText(playable?.uploader ?: "")
            .setContentIntent(content)
            .addAction(toggle)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notif_close), actionIntent(ACTION_CLOSE, 12)
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
        com.nexos.play.ui.NexosWidget.updateAll(this)
    }
}
