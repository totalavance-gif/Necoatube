package com.nexos.play.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import com.nexos.play.data.BackendClient
import com.nexos.play.data.Playable
import com.nexos.play.data.StreamChoice
import com.nexos.play.data.VideoHit
import com.nexos.play.data.YouTubeRepo
import com.nexos.play.net.AppDownloader
import com.nexos.play.ui.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var speed: Float = 1f
    var currentThumb: String? = null
    var sleepMinutes: Int = 0
        private set
    private val history = LinkedHashSet<String>()
    private var session: MediaSession? = null
    private var appCtx: Context? = null
    private val sleepHandler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null

    fun removeFromQueue(url: String) {
        queue = queue.filter { it.url != url }
    }

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

    private data class Step(val video: StreamChoice?, val audio: StreamChoice?)
    private var ladder: List<Step> = emptyList()
    private var ladderIndex = 0
    private var refreshed = false
    private var triedBackend = false
    private var messagedAudio = false
    private var lastAudioOnly = false
    private var lastQuality = 0
    private val holderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun str(id: Int, fallback: String): String =
        appCtx?.getString(id) ?: fallback

    private val eventListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            ladderIndex++
            if (ladderIndex < ladder.size) playStep() else nextOrFail(error)
        }

        override fun onPlaybackStateChanged(state: Int) {
            reportState()
            if (state == Player.STATE_BUFFERING) scheduleBufferWatch()
            else {
                cancelBufferWatch()
                if (state == Player.STATE_READY) playedOnce = true
            }
            if (state == Player.STATE_ENDED) onEnded?.invoke()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            reportState()
        }
    }

    private val bufferHandler = Handler(Looper.getMainLooper())
    private var bufferRunnable: Runnable? = null

    private var playedOnce = false

    /** Si se atasca cargando, baja solo a un paso con video.
     *  8s tras empezar; 25s en la carga inicial (da tiempo a la precarga). */
    private fun scheduleBufferWatch() {
        cancelBufferWatch()
        val stepAtStart = ladderIndex
        val wasPlayed = playedOnce
        val r = Runnable {
            bufferRunnable = null
            if (player?.playbackState == Player.STATE_BUFFERING &&
                ladderIndex == stepAtStart
            ) {
                val next = ((ladderIndex + 1) until ladder.size)
                    .firstOrNull { ladder[it].video != null }
                if (next != null) {
                    onPlayerEvent?.invoke(
                        str(
                            com.nexos.play.R.string.buffering_drop,
                            "Conexión lenta: bajando calidad…"
                        )
                    )
                    ladderIndex = next
                    playStep()
                }
            }
        }
        bufferRunnable = r
        bufferHandler.postDelayed(r, if (wasPlayed) 8_000 else 25_000)
    }

    private fun cancelBufferWatch() {
        bufferRunnable?.let { bufferHandler.removeCallbacks(it) }
        bufferRunnable = null
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

    private var builtPrebuffer: Boolean? = null

    fun ensure(ctx: Context): ExoPlayer {
        player?.let { return it }
        appCtx = ctx.applicationContext
        val wantPrebuffer = com.nexos.play.util.Prefs.prebuffer30(ctx)
        val startMs = if (wantPrebuffer) 30_000 else 1_500
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 60_000, startMs, 3_000)
            .build()
        val created = ExoPlayer.Builder(ctx.applicationContext)
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        created.addListener(eventListener)
        player = created
        builtPrebuffer = wantPrebuffer
        loadPositions()
        session = MediaSession.Builder(ctx.applicationContext, created)
            .setSessionActivity(openIntent())
            .build()
        return created
    }

    private fun releasePlayer() {
        savePosition()
        cancelBufferWatch()
        cancelSleepTimer()
        runCatching { player?.release() }
        player = null
        runCatching { session?.release() }
        session = null
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

    fun playMedia(p: Playable, audioOnly: Boolean, qualityIndex: Int) {
        // Si cambio la precarga, reconstruir el motor (aplica por video).
        appCtx?.let { ctx ->
            val want = com.nexos.play.util.Prefs.prebuffer30(ctx)
            if (player != null && builtPrebuffer != want) {
                releasePlayer()
                ensure(ctx)
            }
        }
        lastAudioOnly = audioOnly
        lastQuality = qualityIndex
        cancelBufferWatch()
        ladder = buildLadder(p, audioOnly, qualityIndex)
        ladderIndex = 0
        refreshed = false
        triedBackend = false
        messagedAudio = false
        history.add(pageUrl)
        while (history.size > 50) history.remove(history.first())
        playStep()
    }

    private fun buildLadder(
        p: Playable,
        audioOnly: Boolean,
        qualityIndex: Int
    ): List<Step> {
        val audios = p.audioOptions.ifEmpty { listOfNotNull(p.audio) }
            .distinctBy { it.url }
        if (audioOnly || (p.video == null && p.videoOptions.isEmpty())) {
            return audios.map { Step(null, it) }
        }
        val sel = p.videoOptions.getOrNull(qualityIndex) ?: p.video
        val videos = (listOfNotNull(sel) + p.videoOptions + listOfNotNull(p.video))
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<StreamChoice> { if (it.url == sel?.url) 1 else 0 }
                    .thenByDescending { heightOf(it) }
            )
        val bestAudio = audios.firstOrNull()
        return (videos.map { Step(it, bestAudio) } + audios.map { Step(null, it) })
            .distinct()
    }

    private fun heightOf(c: StreamChoice): Int =
        c.label.filter { it.isDigit() }.toIntOrNull() ?: 0

    private fun playStep() {
        val p = player
        val step = ladder.getOrNull(ladderIndex)
        if (p == null || step == null) {
            nextOrFail()
            return
        }
        playedOnce = false
        val factory = ProgressiveMediaSource.Factory(
            OkHttpDataSource.Factory(AppDownloader.playbackClient())
        )
        val v = step.video
        val a = step.audio
        if (v != null && v.videoOnly && a != null) {
            p.setMediaSource(
                MergingMediaSource(
                    factory.createMediaSource(mediaItem(v.url)),
                    factory.createMediaSource(mediaItem(a.url))
                )
            )
        } else {
            val single = v ?: a ?: return nextOrFail()
            p.setMediaSource(factory.createMediaSource(mediaItem(single.url)))
        }
        if (v == null && !messagedAudio) {
            messagedAudio = true
            announce(str(com.nexos.play.R.string.audio_fallback, "Reproduciendo audio"))
        }
        p.seekTo(positions[pageUrl] ?: 0L)
        p.setPlaybackSpeed(speed)
        p.prepare()
        p.playWhenReady = true
        updateSession()
    }

    private fun nextOrFail(lastError: PlaybackException? = null) {
        when {
            !refreshed -> {
                refreshed = true
                refreshAndRetry()
            }
            !triedBackend -> {
                triedBackend = true
                tryBackend()
            }
            else -> finalError(lastError)
        }
    }

    private fun refreshAndRetry() {
        onPlayerEvent?.invoke(
            str(com.nexos.play.R.string.retrying, "Reintentando…")
        )
        holderScope.launch {
            val p = runCatching {
                withContext(Dispatchers.IO) { YouTubeRepo.resolve(pageUrl, fresh = true) }
            }.getOrNull()
            if (p == null) {
                nextOrFail()
                return@launch
            }
            playable = p
            queue = p.related
            ladder = buildLadder(p, lastAudioOnly, lastQuality)
            ladderIndex = 0
            playStep()
        }
    }

    private fun tryBackend() {
        val ctx = appCtx
        val base = if (ctx != null) {
            com.nexos.play.util.Prefs.backendUrl(ctx)
        } else ""
        if (ctx == null || base.isBlank()) {
            nextOrFail()
            return
        }
        holderScope.launch {
            val info = runCatching {
                withContext(Dispatchers.IO) { BackendClient.fetchInfo(base, pageUrl) }
            }.getOrNull()
            val v = info?.video?.let { StreamChoice(it.url, it.label, it.ext) }
            val a = info?.audio?.let { StreamChoice(it.url, it.label, it.ext) }
            val steps = listOfNotNull(
                v?.let { Step(it, null) },
                a?.let { Step(null, it) }
            )
            if (steps.isEmpty()) {
                nextOrFail()
                return@launch
            }
            announce(
                str(com.nexos.play.R.string.via_server_play, "Reproduciendo vía servidor")
            )
            ladder = steps
            ladderIndex = 0
            playStep()
        }
    }

    private fun finalError(lastError: PlaybackException?) {
        val msg = lastError?.let { friendlyError(it) }
            ?: str(com.nexos.play.R.string.toast_no_streams, "Sin stream directo")
        onPlayerEvent?.invoke(msg)
        if (!msg.startsWith("Error")) {
            appCtx?.let { Toast.makeText(it, msg, Toast.LENGTH_LONG).show() }
        }
    }

    private fun friendlyError(e: PlaybackException): String {
        var cause: Throwable? = e.cause
        var httpCode = -1
        while (cause != null) {
            val code = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            if (code != null) {
                httpCode = code
                break
            }
            cause = cause.cause
        }
        val what = when {
            httpCode > 0 -> "HTTP $httpCode"
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                "sin conexión"
            e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "servidor rechazó el stream"
            e.errorCodeName.contains("DECODER") -> "formato no soportado"
            else -> e.errorCodeName
        }
        return "Error $what: ${e.message}"
    }

    private fun announce(msg: String) {
        onPlayerEvent?.invoke(msg)
        appCtx?.let { Toast.makeText(it, msg, Toast.LENGTH_LONG).show() }
    }

    fun savePosition() {
        player?.let {
            if (it.currentPosition > 0) {
                positions[pageUrl] = it.currentPosition
                appCtx?.let { ctx ->
                    com.nexos.play.util.Prefs.savePositions(ctx, positions)
                }
            }
        }
    }

    fun loadPositions() {
        appCtx?.let {
            if (positions.isEmpty()) {
                positions.putAll(com.nexos.play.util.Prefs.positions(it))
            }
        }
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
