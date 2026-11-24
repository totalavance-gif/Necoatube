package com.nexos.play.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Historial local de reproducidos (máx. 50, los recientes primero). */
object History {
    private const val FILE = "history"
    private const val KEY = "items"
    private const val MAX = 50

    fun record(ctx: Context, hit: VideoHit) {
        val items = list(ctx).filter { it.url != hit.url }.toMutableList()
        items.add(0, hit)
        val arr = JSONArray()
        items.take(MAX).forEach {
            arr.put(
                JSONObject()
                    .put("title", it.title)
                    .put("url", it.url)
                    .put("uploader", it.uploader)
                    .put("duration", it.durationSec)
                    .put("views", it.views)
                    .put("thumb", it.thumbUrl ?: "")
            )
        }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun list(ctx: Context): List<VideoHit> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                VideoHit(
                    title = o.optString("title"),
                    url = o.optString("url"),
                    uploader = o.optString("uploader"),
                    durationSec = o.optLong("duration"),
                    views = o.optLong("views", -1),
                    thumbUrl = o.optString("thumb").ifBlank { null }
                )
            }.filter { it.url.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
