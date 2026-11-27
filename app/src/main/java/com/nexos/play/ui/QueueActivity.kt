package com.nexos.play.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nexos.play.R
import com.nexos.play.playback.PlayerHolder
import com.nexos.play.util.Prefs

/** Cola del autoplay: tocar salta, mantener quita. */
class QueueActivity : AppCompatActivity() {

    private lateinit var adapter: ResultsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.applyOverlay(this)
        setContentView(R.layout.activity_queue)

        adapter = ResultsAdapter(
            onPick = { hit ->
                startActivity(PlayerActivity.intent(this, hit.url, hit.title))
                finish()
            },
            onLongPress = { hit ->
                PlayerHolder.removeFromQueue(hit.url)
                refresh()
                Toast.makeText(this, R.string.removed_from_list, Toast.LENGTH_SHORT).show()
            }
        )
        findViewById<RecyclerView>(R.id.queue_list).apply {
            layoutManager = LinearLayoutManager(this@QueueActivity)
            adapter = this@QueueActivity.adapter
        }
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    private fun refresh() {
        val now = PlayerHolder.playable?.title
        findViewById<TextView>(R.id.queue_now).text =
            if (now.isNullOrBlank()) "" else getString(R.string.queue_now, now)
        val items = PlayerHolder.queue.filter { it.url != PlayerHolder.pageUrl }
        adapter.submit(items)
        findViewById<TextView>(R.id.queue_now).visibility = View.VISIBLE
        if (items.isEmpty()) {
            findViewById<TextView>(R.id.queue_now).text = getString(R.string.queue_empty)
        }
    }
}
