package com.nexos.play.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
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
import com.nexos.play.data.Favorites
import com.nexos.play.data.History
import com.nexos.play.data.Lyrics
import com.nexos.play.data.Playable
import com.nexos.play.data.Playlists
import com.nexos.play.data.VideoHit
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
    private var resolving: String? = null
    private lateinit var upNext: ResultsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        val (startUrl, startTitle) = urlFromIntent(intent)
        pageUrl = startUrl
        if (pageUrl.isBlank() && PlayerHolder.playable == null) {
            finish()
            return
        }
        findViewById<TextView>(R.id.video_title).text = startTitle
        sizePlayer()

        upNext = ResultsAdapter(
            onPick = { hit -> openVideo(hit.url, hit.title, hit.thumbUrl) }
        )
        findViewById<RecyclerView>(R.id.up_next_list).apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity)
            adapter = upNext
            isNestedScrollingEnabled = false
        }

        PlayerHolder.shuffle = Prefs.shuffle(this)
        PlayerHolder.speed = Prefs.speed(this)
        findViewById<MaterialButton>(R.id.btn_audio).setOnClickListener { toggleAudio() }
        findViewById<MaterialButton>(R.id.btn_fav).setOnClickListener { toggleFav() }
        findViewById<MaterialButton>(R.id.btn_save).setOnClickListener { saveToPlaylist() }
        findViewById<MaterialButton>(R.id.btn_lyrics).setOnClickListener { showLyrics() }
        findViewById<MaterialButton>(R.id.btn_speed).setOnClickListener { cycleSpeed() }
        findViewById<MaterialButton>(R.id.btn_queue).setOnClickListener {
            startActivity(Intent(this, QueueActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btn_shuffle).setOnClickListener { toggleShuffle() }
        findViewById<MaterialButton>(R.id.btn_sleep).setOnClickListener { pickSleep() }
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
        val (url, title) = urlFromIntent(intent)
        openVideo(url, title)
    }

    private fun urlFromIntent(intent: Intent): Pair<String, String> {
        var url = intent.getStringExtra(EXTRA_URL).orEmpty()
        var title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (intent.action == Intent.ACTION_SEND) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val found = Regex("https?://\\S+").find(shared)?.value.orEmpty()
            if (found.isNotBlank()) {
                url = found
                title = found
            }
        }
        return url to title
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
        PlayerHolder.onEnded = { advance() }
        PlayerHolder.uiActive = true
        updateShuffleButton()
        updateSleepButton()
        updateFavButton()
        updateSpeedButton()
        findViewById<PlayerView>(R.id.player_view).player = PlayerHolder.player
        val loaded = PlayerHolder.playable
        if (loaded != null && PlayerHolder.pageUrl == pageUrl) {
            bindInfo(loaded)
        } else if (loaded != null && boundUrl != null) {
            pageUrl = PlayerHolder.pageUrl
            bindInfo(loaded)
        } else if (boundUrl == null) {
            resolve()
        }
    }

    private fun advance() {
        if (resolving != null) return
        val next = PlayerHolder.nextUp() ?: return
        Toast.makeText(this, next.title, Toast.LENGTH_SHORT).show()
        openVideo(next.url, next.title)
    }

    private fun prefetchNext() {
        val next = PlayerHolder.nextUp() ?: return
        scope.launch { runCatching { YouTubeRepo.resolve(next.url) } }
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
        PlayerHolder.onEnded = null
        PlayerHolder.uiActive = false
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
        resolving = want
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
                prefetchNext()
            } catch (e: Exception) {
                if (want == pageUrl) {
                    status.text = getString(
                        R.string.status_error, e.message ?: e.javaClass.simpleName
                    )
                }
            } finally {
                if (resolving == want) resolving = null
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
        PlayerHolder.queue = p.related
        updateQualityButton()
        updateFavButton()
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
        if (p.video == null && p.videoOptions.isEmpty() && p.audio == null) {
            Toast.makeText(this, R.string.toast_no_streams, Toast.LENGTH_LONG).show()
            return
        }
        PlayerHolder.ensure(this)
        PlayerHolder.playMedia(p, PlayerHolder.audioOnly, PlayerHolder.qualityIndex)
        PlaybackService.start(this)
        History.record(
            this,
            VideoHit(p.title, pageUrl, p.uploader, 0, p.views, PlayerHolder.currentThumb)
        )
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

    private fun toggleShuffle() {
        PlayerHolder.shuffle = !PlayerHolder.shuffle
        Prefs.setShuffle(this, PlayerHolder.shuffle)
        updateShuffleButton()
    }

    private fun updateShuffleButton() {
        findViewById<MaterialButton>(R.id.btn_shuffle).text =
            getString(if (PlayerHolder.shuffle) R.string.shuffle_on else R.string.shuffle_off)
    }

    private fun updateSleepButton() {
        val min = PlayerHolder.sleepMinutes
        findViewById<MaterialButton>(R.id.btn_sleep).text =
            if (min > 0) getString(R.string.sleep_fmt, min)
            else getString(R.string.btn_sleep)
    }

    private fun pickSleep() {
        val values = intArrayOf(0, 15, 30, 60, 90)
        val checked = values.indexOf(PlayerHolder.sleepMinutes).takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle(R.string.sleep_title)
            .setSingleChoiceItems(R.array.sleep_options, checked) { d, i ->
                val min = values[i]
                if (min > 0) {
                    PlayerHolder.setSleepTimer(min)
                    Toast.makeText(this, getString(R.string.sleep_toast, min), Toast.LENGTH_SHORT).show()
                } else {
                    PlayerHolder.cancelSleepTimer()
                    Toast.makeText(this, R.string.sleep_cancelled, Toast.LENGTH_SHORT).show()
                }
                updateSleepButton()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun currentHit(): VideoHit? {
        val p = PlayerHolder.playable ?: return null
        return VideoHit(p.title, pageUrl, p.uploader, 0, p.views, PlayerHolder.currentThumb)
    }

    private fun toggleFav() {
        val hit = currentHit() ?: return
        val now = Favorites.toggle(this, hit)
        updateFavButton()
        Toast.makeText(
            this, if (now) R.string.fav_added else R.string.fav_removed,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateFavButton() {
        findViewById<MaterialButton>(R.id.btn_fav).text =
            getString(if (Favorites.isFav(this, pageUrl)) R.string.fav_on else R.string.fav_off)
    }

    private fun saveToPlaylist() {
        val hit = currentHit() ?: return
        val lists = Playlists.list(this)
        val names = lists.map { it.name }.toMutableList()
        names.add(getString(R.string.save_new))
        AlertDialog.Builder(this)
            .setTitle(R.string.save_title)
            .setItems(names.toTypedArray()) { _, i ->
                if (i < lists.size) {
                    Playlists.add(this, lists[i].id, hit)
                    Toast.makeText(
                        this, getString(R.string.save_done, lists[i].name),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    askNewList(hit)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askNewList(hit: VideoHit) {
        val edit = EditText(this).apply { hint = getString(R.string.newlist_hint) }
        AlertDialog.Builder(this)
            .setTitle(R.string.newlist_title)
            .setView(edit)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isNotEmpty()) {
                    val created = Playlists.create(this, name)
                    Playlists.add(this, created.id, hit)
                    Toast.makeText(
                        this, getString(R.string.save_done, name), Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun cycleSpeed() {
        val speeds = floatArrayOf(1f, 1.25f, 1.5f, 1.75f, 2f)
        val next = speeds.firstOrNull { it > PlayerHolder.speed + 0.001f } ?: speeds[0]
        PlayerHolder.speed = next
        Prefs.setSpeed(this, next)
        PlayerHolder.player?.setPlaybackSpeed(next)
        updateSpeedButton()
    }

    private fun updateSpeedButton() {
        val s = PlayerHolder.speed
        findViewById<MaterialButton>(R.id.btn_speed).text =
            if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"
    }

    private fun showLyrics() {
        val p = PlayerHolder.playable ?: return
        val status = findViewById<TextView>(R.id.status)
        status.text = getString(R.string.status_loading)
        scope.launch {
            val (artist, track) = splitArtistTrack(p)
            val lines = runCatching { Lyrics.fetch(artist, track) }
                .getOrDefault(emptyList())
            status.text = ""
            if (lines.isEmpty()) {
                Toast.makeText(
                    this@PlayerActivity, R.string.lyrics_none, Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val text = TextView(this@PlayerActivity).apply {
                setText(lines.joinToString("\n") { it.text })
                setPadding(48, 32, 48, 32)
                textSize = 15f
            }
            val scroll = ScrollView(this@PlayerActivity).apply { addView(text) }
            val dlg = AlertDialog.Builder(this@PlayerActivity)
                .setTitle(R.string.lyrics_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .create()
            if (lines.any { it.timeMs >= 0 }) {
                val h = Handler(Looper.getMainLooper())
                val tick = object : Runnable {
                    override fun run() {
                        if (!dlg.isShowing) return
                        val pos = PlayerHolder.player?.currentPosition ?: 0L
                        val idx = lines.indexOfLast { it.timeMs in 0..pos }
                            .coerceAtLeast(0)
                        text.post {
                            val y = text.height * idx / lines.size - scroll.height / 3
                            scroll.smoothScrollTo(0, y.coerceAtLeast(0))
                        }
                        h.postDelayed(this, 1000)
                    }
                }
                h.post(tick)
                dlg.setOnDismissListener { h.removeCallbacksAndMessages(null) }
            }
            dlg.show()
        }
    }

    private fun splitArtistTrack(p: Playable): Pair<String, String> {
        val parts = p.title.split(" - ", limit = 2)
        return if (parts.size == 2) parts[0].trim() to parts[1].trim()
        else p.uploader.trim() to p.title.trim()
    }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, pageUrl)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_title)))
    }

    private fun openVideo(url: String, title: String, thumbUrl: String? = null) {
        if (url.isBlank()) return
        val loaded = PlayerHolder.playable
        if (url == PlayerHolder.pageUrl && loaded != null) {
            pageUrl = url
            bindInfo(loaded)
            return
        }
        PlayerHolder.currentThumb = thumbUrl
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
