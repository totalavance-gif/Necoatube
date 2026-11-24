package com.nexos.play.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LyricLine(val timeMs: Long, val text: String)

/** Letras vía lrclib (gratis, sin key): sincronizadas si hay, si no planas. */
object Lyrics {
    private val TAG = Regex("""\[(\d+):(\d{2})([.:](\d{1,3}))?]""")

    suspend fun fetch(artist: String, track: String): List<LyricLine> =
        withContext(Dispatchers.IO) {
            val a = artist.trim()
            val t = track.trim()
            if (a.isEmpty() || t.isEmpty()) return@withContext emptyList()
            val exact = get(
                "https://lrclib.net/api/get?artist_name=${enc(a)}&track_name=${enc(t)}"
            )
            val synced = exact?.optString("syncedLyrics").orEmpty()
            if (synced.isNotBlank()) return@withContext parse(synced)
            val plain = exact?.optString("plainLyrics").orEmpty()
            if (plain.isNotBlank()) {
                return@withContext plain.lines().map { LyricLine(-1, it) }
                    .filter { it.text.isNotBlank() }
            }
            val found = search(a, t)
            if (found.isNotBlank()) return@withContext parse(found)
            emptyList()
        }

    private fun search(artist: String, track: String): String {
        val text = getText(
            "https://lrclib.net/api/search?artist_name=${enc(artist)}&track_name=${enc(track)}"
        ) ?: return ""
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return ""
        repeat(arr.length()) { i ->
            val s = arr.optJSONObject(i)?.optString("syncedLyrics").orEmpty()
            if (s.isNotBlank()) return s
        }
        repeat(arr.length()) { i ->
            val s = arr.optJSONObject(i)?.optString("plainLyrics").orEmpty()
            if (s.isNotBlank()) return s
        }
        return ""
    }

    private fun get(url: String): JSONObject? =
        getText(url)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun getText(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "NexosPlay/1.6")
        }
        if (conn.responseCode != 200) return null
        conn.inputStream.bufferedReader().readText()
    }.getOrNull()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    internal fun parse(lrc: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        lrc.lines().forEach { line ->
            val tags = TAG.findAll(line).toList()
            val text = line.replace(TAG, "").trim()
            if (tags.isEmpty() || text.isEmpty()) return@forEach
            tags.forEach { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[4].padEnd(3, '0').take(3).toLong()
                out += LyricLine(min * 60_000 + sec * 1_000 + frac, text)
            }
        }
        return out.sortedBy { it.timeMs }
    }
}
