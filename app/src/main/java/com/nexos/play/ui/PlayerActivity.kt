package com.nexos.play.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.nexos.play.R
import com.nexos.play.data.BackendClient
import com.nexos.play.data.Playable
import com.nexos.play.data.StreamChoice
import com.nexos.play.data.YouTubeRepo
import com.nexos.play.net.AppDownloader
import com.nexos.play.util.Downloads
import com.nexos.play.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        fun intent(ctx: Context, url: String, title: String) =
            Intent(ctx, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: ExoPlayer? = null
    private var playable: Playable? = null
    private var audioOnly = false
    private var qualityIndex = 0
    private lateinit var pageUrl: String
    private val positions = mutableMapOf<String, Long>()
    private lateinit var upNext: ResultsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        pageUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        findViewById<TextView>(R.id.video_title).text =
            intent.getStringExtra(EXTRA_TITLE).orEmpty()
        sizePlayer()

        upNext = ResultsAdapter { hit -> openVideo(hit.url, hit.title) }
        findViewById<RecyclerView>(R.id.up_next_list).apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity)
            adapter = upNext
            isNestedScrollingEnabled = false
        }

        findViewById<MaterialButton>(R.id.btn_audio).setOnClickListener { toggleAudio() }
        findViewById<MaterialButton>(R.id.btn_quality).setOnClickListener { pickQuality() }
        findViewById<MaterialButton>(R.id.btn_dl_video).setOnClickListener { download(video = true) }
        findViewById<MaterialButton>(R.id.btn_dl_audio).setOnClickListener { download(video = false) }
        findViewById<MaterialButton>(R.id.btn_share).setOnClickListener { share() }
        findViewById<MaterialButton>(R.id.btn_backend).setOnClickListener { viaBackend() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sizePlayer()
    }

    private fun sizePlayer() {
        val view = findViewById<PlayerView>(R.id.player_view)
        view.layoutParams = view.layoutParams.apply {
            height = resources.displayMetrics.widthPixels * 9 / 16
        }
    }

    override fun onStart() {
        super.onStart()
        ensurePlayer()
        if (playable == null) {
            resolve()
        } else if (player?.currentMediaItem == null) {
            playCurrent()
        } else {
            player?.playWhenReady = true
        }
    }

    override fun onStop() {
        savePosition()
        player?.playWhenReady = false
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun ensurePlayer() {
        if (player == null) {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 30_000, 1_500, 3_000)
                .build()
            player = ExoPlayer.Builder(this).setLoadControl(loadControl).build()
        }
        findViewById<PlayerView>(R.id.player_view).player = player
    }

    private fun resolve() {
        val progress = findViewById<ProgressBar>(R.id.progress)
        val status = findViewById<TextView>(R.id.status)
        progress.visibility = View.VISIBLE
        status.text = getString(R.string.status_loading)
        val want = pageUrl
        scope.launch {
            try {
                val p = YouTubeRepo.resolve(want)
                if (want != pageUrl) return@launch
                playable = p
                qualityIndex = 0
                bindInfo(p)
                playCurrent()
                status.text = ""
            } catch (e: Exception) {
                if (want == pageUrl) {
                    status.text = getString(
                        R.string.status_error, e.message ?: e.javaClass.simpleName
                    )
                }
            } finally {
                if (want == pageUrl) progress.visibility = View.GONE
            }
        }
    }

    private fun bindInfo(p: Playable) {
        findViewById<TextView>(R.id.video_title).text = p.title
        findViewById<TextView>(R.id.channel_name).text =
            p.uploader.ifBlank { getString(R.string.unknown_uploader) }
        findViewById<TextView>(R.id.channel_avatar).text =
            p.uploader.trim().firstOrNull()?.uppercase() ?: "?"
        findViewById<TextView>(R.id.channel_meta).text = metaLine(p)
        findViewById<TextView>(R.id.video_desc).text = p.description.ifBlank { "—" }
        upNext.submit(p.related)
        updateQualityButton()
    }

    private fun metaLine(p: Playable): String {
        val parts = mutableListOf<String>()
        if (p.views >= 0) parts += "%,d ${getString(R.string.views)}".format(p.views)
        if (p.uploadDate.isNotBlank()) parts += p.uploadDate
        return parts.joinToString(" • ")
    }

    private fun playCurrent() {
        val p = playable ?: return
        val choice = if (audioOnly) p.audio else currentVideo(p)
        if (choice == null) {
            Toast.makeText(this, R.string.toast_no_streams, Toast.LENGTH_LONG).show()
            return
        }
        switchStream(choice, p.audio)
        findViewById<MaterialButton>(R.id.btn_audio).text =
            getString(if (audioOnly) R.string.audio_on else R.string.audio_off)
    }

    private fun currentVideo(p: Playable): StreamChoice? =
        p.videoOptions.getOrNull(qualityIndex) ?: p.video

    private fun progressiveFactory() =
        ProgressiveMediaSource.Factory(
            DefaultHttpDataSource.Factory().setUserAgent(AppDownloader.USER_AGENT)
        )

    private fun switchStream(choice: StreamChoice, audio: StreamChoice?) {
        ensurePlayer()
        player?.run {
            if (choice.videoOnly && audio != null) {
                val video = progressiveFactory()
                    .createMediaSource(MediaItem.fromUri(choice.url))
                val sound = progressiveFactory()
                    .createMediaSource(MediaItem.fromUri(audio.url))
                setMediaSource(MergingMediaSource(video, sound))
            } else {
                setMediaSource(
                    progressiveFactory().createMediaSource(MediaItem.fromUri(choice.url))
                )
            }
            seekTo(positions[pageUrl] ?: 0L)
            prepare()
            playWhenReady = true
        }
    }

    private fun savePosition() {
        player?.let { if (it.currentPosition > 0) positions[pageUrl] = it.currentPosition }
    }

    private fun toggleAudio() {
        savePosition()
        audioOnly = !audioOnly
        playCurrent()
    }

    private fun pickQuality() {
        val opts = playable?.videoOptions.orEmpty()
        if (opts.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_streams, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.quality_title)
            .setSingleChoiceItems(opts.map { it.label }.toTypedArray(), qualityIndex) { d, i ->
                savePosition()
                qualityIndex = i
                updateQualityButton()
                playCurrent()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateQualityButton() {
        val label = playable?.videoOptions?.getOrNull(qualityIndex)?.label
        findViewById<MaterialButton>(R.id.btn_quality).text =
            if (label.isNullOrBlank()) getString(R.string.quality_title)
            else getString(R.string.quality_fmt, label)
    }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, pageUrl)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_title)))
    }

    private fun openVideo(url: String, title: String) {
        savePosition()
        player?.stop()
        player?.clearMediaItems()
        pageUrl = url
        playable = null
        audioOnly = false
        qualityIndex = 0
        positions.remove(url)
        findViewById<TextView>(R.id.video_title).text = title
        findViewById<TextView>(R.id.video_desc).text = ""
        upNext.submit(emptyList())
        updateQualityButton()
        resolve()
    }

    private fun download(video: Boolean) {
        val p = playable
        val choice = if (video) p?.let { currentVideo(it) } else p?.audio
        if (p == null || choice == null) {
            Toast.makeText(this, R.string.toast_no_streams, Toast.LENGTH_LONG).show()
            return
        }
        if (video && choice.videoOnly) {
            Downloads.enqueue(this, choice.url, Downloads.fileName(p.title, choice.label, choice.ext))
            p.audio?.let { a ->
                Downloads.enqueue(this, a.url, Downloads.fileName(p.title, a.label, a.ext))
            }
            Toast.makeText(this, R.string.toast_video_audio_separate, Toast.LENGTH_LONG).show()
            return
        }
        val name = Downloads.fileName(p.title, choice.label, choice.ext)
        Downloads.enqueue(this, choice.url, name)
        Toast.makeText(this, R.string.toast_download_started, Toast.LENGTH_SHORT).show()
    }

    private fun viaBackend() {
        val base = Prefs.backendUrl(this)
        if (base.isBlank()) {
            Toast.makeText(this, R.string.toast_no_backend, Toast.LENGTH_LONG).show()
            return
        }
        val status = findViewById<TextView>(R.id.status)
        val progress = findViewById<ProgressBar>(R.id.progress)
        progress.visibility = View.VISIBLE
        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { BackendClient.fetchInfo(base, pageUrl) }
                val pick = (if (audioOnly) info.audio else info.video)
                    ?: info.video ?: info.audio
                if (pick == null) throw IllegalStateException(getString(R.string.toast_no_streams))
                val name = Downloads.fileName(
                    info.title.ifBlank { "video" }, pick.label, pick.ext
                )
                Downloads.enqueue(this@PlayerActivity, pick.url, name)
                Toast.makeText(
                    this@PlayerActivity, R.string.toast_download_started, Toast.LENGTH_SHORT
                ).show()
                status.text = ""
            } catch (e: Exception) {
                status.text = getString(
                    R.string.status_error, e.message ?: e.javaClass.simpleName
                )
            } finally {
                progress.visibility = View.GONE
            }
        }
    }
}
