package com.nexos.play.data

import android.content.Context
import org.json.JSONArray

/** Favoritos locales (máx. 200, los recientes primero). */
object Favorites {
    private const val FILE = "favorites"
    private const val KEY = "items"
    private const val MAX = 200

    fun toggle(ctx: Context, hit: VideoHit): Boolean {
        val items = list(ctx).toMutableList()
        val i = items.indexOfFirst { it.url == hit.url }
        val nowFav = if (i >= 0) {
            items.removeAt(i)
            false
        } else {
            items.add(0, hit)
            true
        }
        save(ctx, items.take(MAX))
        return nowFav
    }

    fun isFav(ctx: Context, url: String): Boolean =
        list(ctx).any { it.url == url }

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
