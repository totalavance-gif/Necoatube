package com.nexos.play.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nexos.play.R
import com.nexos.play.data.VideoHit
import com.nexos.play.util.Thumbs

class ResultsAdapter(private val onPick: (VideoHit) -> Unit) :
    RecyclerView.Adapter<ResultsAdapter.Holder>() {

    private val items = mutableListOf<VideoHit>()

    fun submit(list: List<VideoHit>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val badge: TextView = v.findViewById(R.id.badge_duration)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_result, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val hit = items[position]
        h.title.text = hit.title
        h.meta.text = listOf(
            hit.uploader.ifBlank { h.itemView.context.getString(R.string.unknown_uploader) },
            fmtDuration(hit.durationSec),
            fmtViews(h, hit.views)
        ).filter { it.isNotEmpty() }.joinToString(" • ")
        Thumbs.load(hit.thumbUrl, h.thumb)
        if (hit.durationSec > 0) {
            h.badge.visibility = View.VISIBLE
            h.badge.text = fmtDuration(hit.durationSec)
        } else {
            h.badge.visibility = View.GONE
        }
        h.itemView.setOnClickListener { onPick(hit) }
    }

    override fun getItemCount() = items.size

    private fun fmtDuration(sec: Long): String {
        if (sec <= 0) return ""
        val m = sec / 60
        val s = sec % 60
        val h = m / 60
        return if (h > 0) "%d:%02d:%02d".format(h, m % 60, s) else "%d:%02d".format(m, s)
    }

    private fun fmtViews(h: Holder, views: Long): String {
        if (views < 0) return ""
        return "%,d %s".format(views, h.itemView.context.getString(R.string.views))
    }
}
