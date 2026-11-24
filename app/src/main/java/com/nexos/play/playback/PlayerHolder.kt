package com.nexos.play.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import com.nexos.play.data.Playable
import com.nexos.play.data.StreamChoice
import com.nexos.play.data.VideoHit
import com.nexos.play.net.AppDownloader
import com.nexos.play.ui.PlayerActivity

/**
 * Reproductor único de la app: vive fuera de las activities para que el audio
 * siga en segundo plano. Sin PiP a petición del usuario.
 */
object PlayerHolder {
    var player: ExoPlayer? = null
        private set
    var playable: Playable? = null
    var pageUrl: String = ""
    var audioOnly: Boolean = false
    var qualityIndex: Int = 0
    val positions = mutableMapOf<String, Long>()
    var onPlayerEvent: ((String) -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    var queue: List<VideoHit> = emptyList()
    var uiActive: Boolean = false
    var shuffle: Boolean = false
    var currentThumb: String? = null
    var sleepMinutes: Int = 0
        private set
    private val history = LinkedHashSet<String>()
    private var session: MediaSession? = null
    private var appCtx: Context? = null
    private val sleepHandler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null

    /** Siguiente de la cola (en orden o al azar), evitando repetidos. */
    fun nextUp(): VideoHit? {
        val others = queue.filter { it.url != pageUrl }
        if (others.isEmpty()) return null
        val fresh = others.filter { !history.contains(it.url) }
        val pool = fresh.ifEmpty { others }
        return if (shuffle) pool.randomOrNull() else pool.firstOrNull()
    }

    fun setSleepTimer(min: Int) {
        cancelSleepTimer()
        if (min <= 0) return
        sleepMinutes = min
        val r = Runnable {
            sleepMinutes = 0
            savePosition()
            player?.playWhenReady = false
            appCtx?.let {
                it.stopService(Intent(it, PlaybackService::class.java))
            }
            onPlayerEvent?.invoke("Estado: en pausa")
        }
        sleepRunnable = r
        sleepHandler.postDelayed(r, min * 60_000L)
    }

    fun cancelSleepTimer() {
        sleepRunnable?.let { sleepHandler.removeCallbacks(it) }
        sleepRunnable = null
        sleepMinutes = 0
    }

    private val eventListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            onPlayerEvent?.invoke("Error ${error.errorCodeName}: ${error.message}")
        }

        override fun onPlaybackStateChanged(state: Int) {
            reportState()
            if (state == Player.STATE_ENDED) onEnded?.invoke()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            reportState()
        }
    }

    private fun reportState() {
        val p = player ?: return
        val s = when (p.playbackState) {
            Player.STATE_IDLE -> "detenido"
            Player.STATE_BUFFERING -> "cargando…"
            Player.STATE_READY -> if (p.playWhenReady) "reproduciendo" else "en pausa"
            Player.STATE_ENDED -> "terminado"
            else -> "?"
        }
        onPlayerEvent?.invoke("Estado: $s")
    }

    fun ensure(ctx: Context): ExoPlayer {
        player?.let { return it }
        appCtx = ctx.applicationContext
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 1_500, 3_000)
            .build()
        val created = ExoPlayer.Builder(ctx.applicationContext)
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        created.addListener(eventListener)
        player = created
        session = MediaSession.Builder(ctx.applicationContext, created)
            .setSessionActivity(openIntent())
            .build()
        return created
    }

    private fun openIntent(): PendingIntent {
        val ctx = appCtx ?: throw IllegalStateException("holder sin contexto")
        val open = PlayerActivity.intent(ctx, pageUrl, playable?.title.orEmpty())
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            ctx, 20, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateSession() {
        session?.setSessionActivity(openIntent())
    }

    private fun mediaItem(url: String): MediaItem {
        val p = playable
        return MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(p?.title.orEmpty())
                    .setArtist(p?.uploader.orEmpty())
                    .build()
            )
            .build()
    }

    fun play(choice: StreamChoice, audio: StreamChoice?) {
        val p = player ?: return
        history.add(pageUrl)
        while (history.size > 50) history.remove(history.first())
        val factory = ProgressiveMediaSource.Factory(
            DefaultHttpDataSource.Factory().setUserAgent(AppDownloader.USER_AGENT)
        )
        if (choice.videoOnly && audio != null) {
            p.setMediaSource(
                MergingMediaSource(
                    factory.createMediaSource(mediaItem(choice.url)),
                    factory.createMediaSource(mediaItem(audio.url))
                )
            )
        } else {
            p.setMediaSource(factory.createMediaSource(mediaItem(choice.url)))
        }
        p.seekTo(positions[pageUrl] ?: 0L)
        p.prepare()
        p.playWhenReady = true
        updateSession()
    }

    fun savePosition() {
        player?.let { if (it.currentPosition > 0) positions[pageUrl] = it.currentPosition }
    }

    fun reset() {
        cancelSleepTimer()
        runCatching { player?.stop() }
        runCatching { session?.release() }
        runCatching { player?.release() }
        session = null
        player = null
        playable = null
        pageUrl = ""
        audioOnly = false
        qualityIndex = 0
    }
}
