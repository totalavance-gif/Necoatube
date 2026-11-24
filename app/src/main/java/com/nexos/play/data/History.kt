package com.nexos.play.data

import android.content.Context
import org.json.JSONArray

/** Historial local de reproducidos (máx. 50, los recientes primero). */
object History {
    private const val FILE = "history"
    private const val KEY = "items"
    private const val MAX = 50

    fun record(ctx: Context, hit: VideoHit) {
        val items = list(ctx).filter { it.url != hit.url }.toMutableList()
        items.add(0, hit)
        save(ctx, items.take(MAX))
    }

    fun list(ctx: Context): List<VideoHit> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { HitJson.fromJson(arr.getJSONObject(it)) }
                .filter { it.url.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun remove(ctx: Context, url: String) {
        save(ctx, list(ctx).filter { it.url != url })
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    fun setAll(ctx: Context, items: List<VideoHit>) {
        save(ctx, items.take(MAX))
    }

    private fun save(ctx: Context, items: List<VideoHit>) {
        val arr = JSONArray()
        items.forEach { arr.put(HitJson.toJson(it)) }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
