package com.nexos.play.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.nexos.play.data.Playable
import com.nexos.play.data.StreamChoice
import com.nexos.play.data.VideoHit
import com.nexos.play.net.AppDownloader

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
    private val history = LinkedHashSet<String>()

    /** Siguiente sin reproducir de la cola; si no hay, el primero distinto. */
    fun nextUp(): VideoHit? {
        val fresh = queue.firstOrNull { it.url != pageUrl && !history.contains(it.url) }
        return fresh ?: queue.firstOrNull { it.url != pageUrl }
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
        return created
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
                    factory.createMediaSource(MediaItem.fromUri(choice.url)),
                    factory.createMediaSource(MediaItem.fromUri(audio.url))
                )
            )
        } else {
            p.setMediaSource(factory.createMediaSource(MediaItem.fromUri(choice.url)))
        }
        p.seekTo(positions[pageUrl] ?: 0L)
        p.prepare()
        p.playWhenReady = true
    }

    fun savePosition() {
        player?.let { if (it.currentPosition > 0) positions[pageUrl] = it.currentPosition }
    }

    fun reset() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playable = null
        pageUrl = ""
        audioOnly = false
        qualityIndex = 0
    }
}
