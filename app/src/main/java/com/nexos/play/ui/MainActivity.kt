package com.nexos.play.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nexos.play.BuildConfig
import com.nexos.play.R
import com.nexos.play.data.Favorites
import com.nexos.play.data.History
import com.nexos.play.data.HitJson
import com.nexos.play.data.Playlists
import com.nexos.play.data.UpdateInfo
import com.nexos.play.data.Updater
import com.nexos.play.data.YouTubeRepo
import com.nexos.play.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var adapter: ResultsAdapter
    private var searched = false
    private var section = 0
    private var playlistId = ""
    private var playlistName = ""

    private val voiceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val heard = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull().orEmpty()
            if (heard.isNotBlank()) {
                findViewById<EditText>(R.id.search_input).setText(heard)
                findViewById<ImageButton>(R.id.search_go).performClick()
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importBackup(uri.toString())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.applyOverlay(this)
        setContentView(R.layout.activity_main)

        val input = findViewById<EditText>(R.id.search_input)
        val go = findViewById<ImageButton>(R.id.search_go)
        val progress = findViewById<ProgressBar>(R.id.progress)
        val status = findViewById<TextView>(R.id.status)
        val list = findViewById<RecyclerView>(R.id.results)

        adapter = ResultsAdapter(
            onPick = { hit ->
                startActivity(PlayerActivity.intent(this, hit.url, hit.title))
            },
            onLongPress = { hit -> removeFromSection(hit.url) }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
            R.id.bottom_nav
        ).setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showSection(0)
                R.id.nav_history -> showSection(1)
                R.id.nav_favs -> showSection(2)
                R.id.nav_lists -> showListsDialog()
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        findViewById<View>(R.id.btn_search_toggle).setOnClickListener {
            val row = findViewById<View>(R.id.search_row)
            row.visibility = if (row.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (row.visibility == View.VISIBLE) {
                findViewById<EditText>(R.id.search_input).requestFocus()
            }
        }
        findViewById<TextView>(R.id.history_clear).setOnClickListener { clearAction() }
        findViewById<TextView>(R.id.btn_server).setOnClickListener { showMenu() }
        findViewById<ImageButton>(R.id.btn_voice).setOnClickListener { startVoice() }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                go.performClick()
                true
            } else {
                false
            }
        }
        go.setOnClickListener {
            val q = input.text.toString().trim()
            if (q.isEmpty()) return@setOnClickListener
            hideKeyboard(input)
            if (q.startsWith("http")) {
                startActivity(PlayerActivity.intent(this, q, q))
                return@setOnClickListener
            }
            searched = true
            findViewById<View>(R.id.history_header).visibility = View.GONE
            progress.visibility = View.VISIBLE
            status.text = getString(R.string.status_searching)
            scope.launch {
                try {
                    val hits = YouTubeRepo.search(q)
                    adapter.submit(hits)
                    status.text = if (hits.isEmpty()) getString(R.string.status_empty)
                    else getString(R.string.results_count, hits.size)
                } catch (e: Exception) {
                    status.text = getString(
                        R.string.status_error, e.message ?: e.javaClass.simpleName
                    )
                } finally {
                    progress.visibility = View.GONE
                }
            }
        }
        showSection(0)
    }

    override fun onStart() {
        super.onStart()
        if (!searched) showSection(section)
        if (Prefs.dueUpdateCheck(this)) {
            scope.launch {
                val info = runCatching { Updater.check(BuildConfig.VERSION_NAME) }
                    .getOrNull()
                if (info != null) showUpdateDialog(info)
            }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle(R.string.update_title)
            .setMessage(getString(R.string.update_msg, info.tag, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.update_go) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(info.url)))
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun showSection(n: Int) {
        section = n
        searched = false
        val header = findViewById<View>(R.id.history_header)
        val title = findViewById<TextView>(R.id.section_title)
        val clear = findViewById<TextView>(R.id.history_clear)
        val status = findViewById<TextView>(R.id.status)
        header.visibility = View.VISIBLE
        when (n) {
            0 -> {
                title.text = getString(R.string.feed_title)
                clear.visibility = View.GONE
                loadFeed()
            }
            1 -> {
                title.text = getString(R.string.history_title)
                clear.visibility = View.VISIBLE
                status.text = ""
                adapter.submit(History.list(this))
            }
            2 -> {
                title.text = getString(R.string.favs_title)
                clear.visibility = View.VISIBLE
                status.text = ""
                adapter.submit(Favorites.list(this))
            }
            else -> {
                title.text = playlistName.ifBlank { getString(R.string.lists_title) }
                clear.visibility = View.VISIBLE
                status.text = ""
                adapter.submit(Playlists.get(this, playlistId)?.items.orEmpty())
            }
        }
    }

    private fun loadFeed() {
        val progress = findViewById<ProgressBar>(R.id.progress)
        val status = findViewById<TextView>(R.id.status)
        progress.visibility = View.VISIBLE
        status.text = getString(R.string.status_loading_feed)
        scope.launch {
            try {
                val hits = YouTubeRepo.trending()
                if (section == 0 && !searched) {
                    adapter.submit(hits)
                    status.text = if (hits.isEmpty()) getString(R.string.status_empty) else ""
                }
            } catch (e: Exception) {
                if (section == 0 && !searched) {
                    status.text = getString(
                        R.string.status_error, e.message ?: e.javaClass.simpleName
                    )
                }
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun removeFromSection(url: String) {
        when (section) {
            1 -> History.remove(this, url)
            2 -> Favorites.remove(this, url)
            3 -> if (playlistId.isNotBlank()) Playlists.remove(this, playlistId, url)
            else -> return
        }
        Toast.makeText(this, R.string.removed_from_list, Toast.LENGTH_SHORT).show()
        showSection(section)
    }

    private fun clearAction() {
        when (section) {
            1 -> {
                History.clear(this)
                showSection(1)
            }
            2 -> {
                Favorites.clear(this)
                showSection(2)
            }
            3 -> {
                AlertDialog.Builder(this)
                    .setMessage(R.string.list_delete_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        Playlists.delete(this, playlistId)
                        playlistId = ""
                        playlistName = ""
                        Toast.makeText(this, R.string.list_deleted, Toast.LENGTH_SHORT).show()
                        showSection(0)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun showListsDialog() {
        val lists = Playlists.list(this)
        val names = lists.map { "${it.name} (${it.items.size})" }.toMutableList()
        names.add(getString(R.string.save_new))
        AlertDialog.Builder(this)
            .setTitle(R.string.lists_title)
            .setItems(names.toTypedArray()) { _, i ->
                if (i < lists.size) {
                    playlistId = lists[i].id
                    playlistName = lists[i].name
                    showSection(3)
                } else {
                    askNewList()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askNewList() {
        val edit = EditText(this).apply { hint = getString(R.string.newlist_hint) }
        AlertDialog.Builder(this)
            .setTitle(R.string.newlist_title)
            .setView(edit)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = edit.text.toString().trim()
                if (name.isNotEmpty()) {
                    val created = Playlists.create(this, name)
                    playlistId = created.id
                    playlistName = created.name
                    showSection(3)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startVoice() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
            }
            voiceLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.voice_none, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMenu() {
        val themeLabel = getString(
            R.string.menu_theme, getString(
                when (Prefs.themeMode(this)) {
                    0 -> R.string.theme_light
                    2 -> R.string.theme_black
                    else -> R.string.theme_dark
                }
            )
        )
        val prebufferLabel = getString(
            if (Prefs.prebuffer30(this)) R.string.menu_prebuffer_on else R.string.menu_prebuffer_off
        )
        val podcastLabel = getString(
            if (Prefs.podcastMode(this)) R.string.menu_podcast_on else R.string.menu_podcast_off
        )
        val silenceLabel = getString(
            if (Prefs.skipSilence(this)) R.string.menu_silence_on else R.string.menu_silence_off
        )
        val items = arrayOf(
            getString(R.string.menu_server), themeLabel,
            getString(R.string.menu_backup), prebufferLabel,
            podcastLabel, silenceLabel
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_title)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> askBackend()
                    1 -> themeDialog()
                    2 -> backupDialog()
                    3 -> {
                        Prefs.setPrebuffer30(this, !Prefs.prebuffer30(this))
                        Toast.makeText(this, R.string.prebuffer_note, Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        Prefs.setPodcastMode(this, !Prefs.podcastMode(this))
                        Toast.makeText(this, R.string.podcast_note, Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        Prefs.setSkipSilence(this, !Prefs.skipSilence(this))
                        com.nexos.play.playback.PlayerHolder.applySkipSilence()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun themeDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.theme_title)
            .setSingleChoiceItems(
                arrayOf(
                    getString(R.string.theme_light),
                    getString(R.string.theme_dark),
                    getString(R.string.theme_black)
                ),
                Prefs.themeMode(this)
            ) { d, i ->
                Prefs.setThemeMode(this, i)
                Prefs.applyTheme(this)
                d.dismiss()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun backupDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_title)
            .setItems(
                arrayOf(
                    getString(R.string.backup_export),
                    getString(R.string.backup_import)
                )
            ) { _, i ->
                if (i == 0) exportBackup() else importLauncher.launch("*/*")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportBackup() {
        val root = JSONObject()
        val hist = JSONArray()
        History.list(this).forEach { hist.put(HitJson.toJson(it)) }
        val favs = JSONArray()
        Favorites.list(this).forEach { favs.put(HitJson.toJson(it)) }
        val lists = JSONArray()
        Playlists.list(this).forEach { lists.put(Playlists.toJson(it)) }
        root.put("history", hist)
        root.put("favorites", favs)
        root.put("playlists", lists)
        root.put("shuffle", Prefs.shuffle(this))
        root.put("backend", Prefs.backendUrl(this))
        runCatching {
            val file = File(cacheDir, "nexosplay-backup.json")
            file.writeText(root.toString())
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.backup_share)))
        }
    }

    private fun importBackup(uriString: String) {
        val ok = runCatching {
            val uri = android.net.Uri.parse(uriString)
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                ?: return@runCatching false
            val root = JSONObject(text)
            val hist = root.optJSONArray("history") ?: JSONArray()
            History.setAll(
                this,
                List(hist.length()) { HitJson.fromJson(hist.getJSONObject(it)) }
                    .filter { it.url.isNotBlank() }
            )
            val favs = root.optJSONArray("favorites") ?: JSONArray()
            Favorites.setAll(
                this,
                List(favs.length()) { HitJson.fromJson(favs.getJSONObject(it)) }
                    .filter { it.url.isNotBlank() }
            )
            val lists = root.optJSONArray("playlists") ?: JSONArray()
            Playlists.setAll(
                this,
                List(lists.length()) { Playlists.fromJson(lists.getJSONObject(it)) }
                    .filter { it.id.isNotBlank() }
            )
            if (root.has("shuffle")) Prefs.setShuffle(this, root.optBoolean("shuffle"))
            if (root.has("backend")) Prefs.setBackendUrl(this, root.optString("backend"))
            true
        }.getOrDefault(false)
        Toast.makeText(
            this, if (ok) R.string.backup_done else R.string.backup_bad,
            Toast.LENGTH_LONG
        ).show()
        if (ok) showSection(section)
    }

    private fun hideKeyboard(view: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun askBackend() {
        val edit = EditText(this).apply {
            hint = getString(R.string.backend_hint)
            setText(Prefs.backendUrl(this@MainActivity))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.backend_title)
            .setView(edit)
            .setPositiveButton(R.string.backend_save) { _, _ ->
                Prefs.setBackendUrl(this, edit.text.toString().trim())
                Toast.makeText(this, R.string.backend_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
