package com.nexos.play.ui

import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nexos.play.R
import com.nexos.play.data.History
import com.nexos.play.data.YouTubeRepo
import com.nexos.play.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var adapter: ResultsAdapter
    private var searched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val input = findViewById<EditText>(R.id.search_input)
        val go = findViewById<ImageButton>(R.id.search_go)
        val progress = findViewById<ProgressBar>(R.id.progress)
        val status = findViewById<TextView>(R.id.status)
        val list = findViewById<RecyclerView>(R.id.results)

        adapter = ResultsAdapter { hit ->
            startActivity(PlayerActivity.intent(this, hit.url, hit.title))
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        showHistory()

        findViewById<TextView>(R.id.history_clear).setOnClickListener {
            History.clear(this)
            showHistory()
        }

        findViewById<TextView>(R.id.btn_server).setOnClickListener { askBackend() }
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
    }

    override fun onStart() {
        super.onStart()
        val input = findViewById<EditText>(R.id.search_input)
        if (input.text.isNullOrBlank()) {
            searched = false
            showHistory()
        }
    }

    private fun showHistory() {
        val items = History.list(this)
        findViewById<View>(R.id.history_header).visibility =
            if (items.isEmpty() || searched) View.GONE else View.VISIBLE
        if (!searched) {
            findViewById<TextView>(R.id.status).text =
                if (items.isEmpty()) getString(R.string.status_ready) else ""
            adapter.submit(items)
        }
    }

    private fun hideKeyboard(view: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_server) {
            askBackend()
            return true
        }
        return super.onOptionsItemSelected(item)
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
