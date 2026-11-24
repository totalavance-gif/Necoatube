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
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_result, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val it = items[position]
        h.title.text = it.title
        h.meta.text = listOf(
            it.uploader.ifBlank { h.itemView.context.getString(R.string.unknown_uploader) },
            fmtDuration(it.durationSec),
            fmtViews(h, it.views)
        ).filter { it.isNotEmpty() }.joinToString(" • ")
        Thumbs.load(it.thumbUrl, h.thumb)
        h.itemView.setOnClickListener { onPick(it) }
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
