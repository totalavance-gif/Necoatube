package com.nexos.play.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Cliente del servidor propio yt-dlp (motor opcional para descargas). */
object BackendClient {

    data class BackendStream(val url: String, val label: String, val ext: String)
    data class BackendInfo(
        val title: String,
        val uploader: String,
        val video: BackendStream?,
        val audio: BackendStream?
    )

    @Throws(IOException::class)
    fun fetchInfo(base: String, pageUrl: String): BackendInfo {
        val endpoint = "${base.trimEnd('/')}/info?url=${URLEncoder.encode(pageUrl, "UTF-8")}"
        val json = JSONObject(get(endpoint))
        return BackendInfo(
            title = json.optString("title"),
            uploader = json.optString("uploader"),
            video = json.optJSONObject("video")?.toStream(),
            audio = json.optJSONObject("audio")?.toStream()
        )
    }

    private fun JSONObject.toStream() = BackendStream(
        url = getString("url"),
        label = optString("label"),
        ext = optString("ext", "mp4")
    )

    @Throws(IOException::class)
    private fun get(endpoint: String): String {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 90_000
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
            throw IOException("HTTP $code ${msg.ifBlank { text.take(200) }}")
        }
        return text
    }
}
