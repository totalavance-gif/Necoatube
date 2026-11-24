package com.nexos.play.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val tag: String, val url: String)

/** Compara la versión instalada contra la última Release de GitHub. */
object Updater {
    suspend fun check(current: String): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(
                "https://api.github.com/repos/totalavance-gif/Necoatube/releases/latest"
            ).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "NexosPlay/$current")
            }
            if (conn.responseCode != 200) return@withContext null
            val o = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = o.optString("tag_name")
            val url = o.optString("html_url")
            if (tag.isBlank() || url.isBlank()) return@withContext null
            if (isNewer(current, tag)) UpdateInfo(tag, url) else null
        }.getOrNull()
    }

    internal fun isNewer(current: String, tag: String): Boolean {
        fun parts(s: String) = s.trim().removePrefix("v").split(".")
            .map { it.toIntOrNull() ?: 0 }
        val a = parts(current)
        val b = parts(tag)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (y > x) return true
            if (y < x) return false
        }
        return false
    }
}
