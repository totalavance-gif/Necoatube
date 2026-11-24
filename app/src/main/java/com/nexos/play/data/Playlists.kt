package com.nexos.play.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Playlist(val id: String, val name: String, val items: List<VideoHit> = emptyList())

/** Listas de reproducción locales. */
object Playlists {
    private const val FILE = "playlists"
    private const val KEY = "lists"

    fun list(ctx: Context): List<Playlist> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val items = o.optJSONArray("items") ?: JSONArray()
                Playlist(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    items = List(items.length()) { j ->
                        HitJson.fromJson(items.getJSONObject(j))
                    }.filter { it.url.isNotBlank() }
                )
            }.filter { it.id.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun get(ctx: Context, id: String): Playlist? = list(ctx).firstOrNull { it.id == id }

    fun create(ctx: Context, name: String): Playlist {
        val lists = list(ctx).toMutableList()
        val created = Playlist(UUID.randomUUID().toString(), name)
        lists.add(0, created)
        save(ctx, lists)
        return created
    }

    fun delete(ctx: Context, id: String) {
        save(ctx, list(ctx).filter { it.id != id })
    }

    fun add(ctx: Context, id: String, hit: VideoHit) {
        save(ctx, list(ctx).map { p ->
            if (p.id != id || p.items.any { it.url == hit.url }) p
            else p.copy(items = p.items + hit)
        })
    }

    fun remove(ctx: Context, id: String, url: String) {
        save(ctx, list(ctx).map { p ->
            if (p.id != id) p else p.copy(items = p.items.filter { it.url != url })
        })
    }

    fun setAll(ctx: Context, lists: List<Playlist>) {
        save(ctx, lists)
    }

    fun toJson(p: Playlist): JSONObject {
        val arr = JSONArray()
        p.items.forEach { arr.put(HitJson.toJson(it)) }
        return JSONObject().put("id", p.id).put("name", p.name).put("items", arr)
    }

    fun fromJson(o: JSONObject): Playlist {
        val items = o.optJSONArray("items") ?: JSONArray()
        return Playlist(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name"),
            items = List(items.length()) { HitJson.fromJson(items.getJSONObject(it)) }
                .filter { it.url.isNotBlank() }
        )
    }

    private fun save(ctx: Context, lists: List<Playlist>) {
        val arr = JSONArray()
        lists.forEach { arr.put(toJson(it)) }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
