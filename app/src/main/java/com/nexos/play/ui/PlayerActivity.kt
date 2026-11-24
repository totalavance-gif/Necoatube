package com.nexos.play.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import com.nexos.play.R
import com.nexos.play.data.BackendClient
import com.nexos.play.data.Playable
import com.nexos.play.data.YouTubeRepo
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
    private lateinit var pageUrl: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        pageUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        findViewById<TextView>(R.id.video_title).text =
            intent.getStringExtra(EXTRA_TITLE).orEmpty()

        findViewById<MaterialButton>(R.id.btn_audio).setOnClickListener { toggleAudio() }
        findViewById<MaterialButton>(R.id.btn_dl_video).setOnClickListener { download(video = true) }
        findViewById<MaterialButton>(R.id.btn_dl_audio).setOnClickListener { download(video = false) }
        findViewById<MaterialButton>(R.id.btn_backend).setOnClickListener { viaBackend() }
    }

    override fun onStart() {
        super.onStart()
        ensurePlayer()
        resolve()
    }

    override fun onStop() {
        player?.release()
        player = null
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensurePlayer() {
        if (player == null) {
            player = ExoPlayer.Builder(this).build()
            findViewById<PlayerView>(R.id.player_view).player = player
        }
    }

    private fun resolve() {
        val progress = findViewById<ProgressBar>(R.id.progress)
        val status = findViewById<TextView>(R.id.status)
        progress.visibility = View.VISIBLE
        status.text = getString(R.string.status_loading)
        scope.launch {
            try {
                val p = YouTubeRepo.resolve(pageUrl)
                playable = p
                findViewById<TextView>(R.id.video_title).text = p.title
                playCurrent()
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

    private fun playCurrent() {
        val p = playable ?: return
        val choice = if (audioOnly) p.audio else p.video
        if (choice == null) {
            Toast.makeText(this, R.string.toast_no_streams, Toast.LENGTH_LONG).show()
            return
        }
        ensurePlayer()
        player?.run {
            setMediaItem(MediaItem.fromUri(choice.url))
            prepare()
            playWhenReady = true
        }
        findViewById<MaterialButton>(R.id.btn_audio).text =
            getString(if (audioOnly) R.string.audio_on else R.string.audio_off)
    }

    private fun toggleAudio() {
        audioOnly = !audioOnly
        playCurrent()
    }

    private fun download(video: Boolean) {
        val p = playable
        val choice = if (video) p?.video else p?.audio
        if (choice == null) {
            Toast.makeText(this, R.string.toast_no_streams, Toast.LENGTH_LONG).show()
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
