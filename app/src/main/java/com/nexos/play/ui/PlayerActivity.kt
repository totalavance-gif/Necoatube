package com.nexos.play.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.nexos.play.R
import com.nexos.play.data.BackendClient
import com.nexos.play.data.Playable
import com.nexos.play.data.StreamChoice
import com.nexos.play.data.YouTubeRepo
import com.nexos.play.playback.PlaybackService
import com.nexos.play.playback.PlayerHolder
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
        private const val REQ_NOTIF = 1001

        fun intent(ctx: Context, url: String, title: String) =
            Intent(ctx, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var pageUrl: String
    private var boundUrl: String? = null
    private lateinit var upNext: ResultsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        pageUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (pageUrl.isBlank() && PlayerHolder.playable == null) {
            finish()
            return
        }
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

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openVideo(
            intent.getStringExtra(EXTRA_URL).orEmpty(),
            intent.getStringExtra(EXTRA_TITLE).orEmpty()
        )
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
        PlayerHolder.ensure(this)
        PlayerHolder.onPlayerEvent = { msg ->
            findViewById<TextView>(R.id.status).text = msg
            if (msg.startsWith("Error")) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
        findViewById<PlayerView>(R.id.player_view).player = PlayerHolder.player
        val loaded = PlayerHolder.playable
        if (loaded != null && PlayerHolder.pageUrl == pageUrl) {
            bindInfo(loaded)
        } else if (boundUrl == null) {
            resolve()
        }
    }

    override fun onResume() {
        super.onResume()
        if (boundUrl != null && PlayerHolder.playable == null) {
            findViewById<TextView>(R.id.status).text = getString(R.string.status_stopped)
        }
    }

    override fun onStop() {
        PlayerHolder.savePosition()
        PlayerHolder.onPlayerEvent = null
        findViewById<PlayerView>(R.id.player_view).player = null
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { findViewById<PlayerView>(R.id.player_view).player = null }
        scope.cancel()
        super.onDestroy()
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
                PlayerHolder.playable = p
                PlayerHolder.pageUrl = want
                PlayerHolder.qualityIndex = 0
                bindInfo(p)
                status.text = "Streams: ${p.video?.label ?: "-"} + " +
                    "${p.audio?.label ?: "-"}"
                playCurrent()
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
        boundUrl = PlayerHolder.pageUrl
        findViewById<TextView>(R.id.video_title).text = p.title
        findViewById<TextView>(R.id.channel_name).text =
            p.uploader.ifBlank { getString(R.string.unknown_uploader) }
        findViewById<TextView>(R.id.channel_avatar).text =
            p.uploader.trim().firstOrNull()?.uppercase() ?: "?"
        findViewById<TextView>(R.id.channel_meta).text = metaLine(p)
        findViewById<TextView>(R.id.video_desc).text = p.description.ifBlank { "—" }
        upNext.submit(p.related)
        updateQualityButton()
        findViewById<MaterialButton>(R.id.btn_audio).text =
            getString(if (PlayerHolder.audioOnly) R.string.audio_on else R.string.audio_off)
    }

    private fun metaLine(p: Playable): String {
        val parts = mutableListOf<String>()
        if (p.views >= 0) parts += "%,d ${getString(R.string.views)}".format(p.views)
        if (p.uploadDate.isNotBlank()) parts += p.uploadDate
        return parts.joinToString(" • ")
    }

    private fun playCurrent() {
        val p = PlayerHolder.playable ?: return
        val choice = if (PlayerHolder.audioOnly) p.audio else currentVideo(p)
        if (choice == null) {
            Toast.makeText(this, R.string.toast_no_streams, Toast.LENGTH_LONG).show()
            return
        }
        PlayerHolder.ensure(this)
        PlayerHolder.play(choice, p.audio)
        PlaybackService.start(this)
        findViewById<MaterialButton>(R.id.btn_audio).text =
            getString(if (PlayerHolder.audioOnly) R.string.audio_on else R.string.audio_off)
    }

    private fun currentVideo(p: Playable): StreamChoice? =
        p.videoOptions.getOrNull(PlayerHolder.qualityIndex) ?: p.video

    private fun toggleAudio() {
        PlayerHolder.savePosition()
        PlayerHolder.audioOnly = !PlayerHolder.audioOnly
        playCurrent()
    }

    private fun pickQuality() {
        val opts = PlayerHolder.playable?.videoOptions.orEmpty()
        if (opts.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_streams, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.quality_title)
            .setSingleChoiceItems(
                opts.map { it.label }.toTypedArray(), PlayerHolder.qualityIndex
            ) { d, i ->
                PlayerHolder.savePosition()
                PlayerHolder.qualityIndex = i
                updateQualityButton()
                playCurrent()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateQualityButton() {
        val label = PlayerHolder.playable?.videoOptions
            ?.getOrNull(PlayerHolder.qualityIndex)?.label
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
        if (url.isBlank()) return
        val loaded = PlayerHolder.playable
        if (url == PlayerHolder.pageUrl && loaded != null) {
            pageUrl = url
            bindInfo(loaded)
            return
        }
        PlayerHolder.savePosition()
        PlayerHolder.audioOnly = false
        PlayerHolder.qualityIndex = 0
        pageUrl = url
        PlayerHolder.positions.remove(url)
        boundUrl = null
        findViewById<TextView>(R.id.video_title).text = title
        findViewById<TextView>(R.id.video_desc).text = ""
        upNext.submit(emptyList())
        updateQualityButton()
        resolve()
    }

    private fun download(video: Boolean) {
        val p = PlayerHolder.playable
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
                val pick = (if (PlayerHolder.audioOnly) info.audio else info.video)
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
