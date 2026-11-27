package com.nexos.play.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Segundo motor: API de Piped (respaldo si NewPipeExtractor falla).
 *  Parseo defensivo: ante cualquier forma rara, lanza y sigue la escalera. */
object PipedClient {

    private val INSTANCES = listOf(
        "https://api.piped.private.coffee",
        "https://pipedapi.adminforge.de"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"

    fun search(query: String): List<VideoHit> {
        val json = getAny("/search", mapOf("q" to query, "filter" to "videos"))
        val items = json.optJSONArray("items") ?: JSONArray()
        return mapItems(items)
    }

    fun trending(): List<VideoHit> {
        val raw = getAnyRaw("/trending", mapOf("region" to "US"))
        val items = try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONObject(raw).optJSONArray("items") ?: JSONArray()
        }
        return mapItems(items)
    }

    fun resolve(pageUrl: String): Playable {
        val id = videoId(pageUrl)
            ?: throw IllegalArgumentException("URL sin id de video")
        val d = JSONObject(getAnyRaw("/streams/$id", emptyMap()))
        if (d.has("error")) throw IllegalStateException(d.optString("message", "Piped error"))
        val videos = d.optJSONArray("videoStreams") ?: JSONArray()
        val muxed = mutableListOf<StreamChoice>()
        val only = mutableListOf<StreamChoice>()
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            val url = v.optString("url", "")
            if (url.isBlank()) continue
            val c = StreamChoice(
                url = url,
                label = v.optString("quality", "").ifBlank { "video" },
                ext = extOf(v.optString("mimeType", ""), v.optString("format", ""), true),
                videoOnly = v.optBoolean("videoOnly", true)
            )
            (if (c.videoOnly) only else muxed).add(c)
        }
        val audios = mutableListOf<StreamChoice>()
        val arr = d.optJSONArray("audioStreams") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val url = a.optString("url", "")
            if (url.isBlank()) continue
            val br = a.optLong("bitrate", 0)
            audios.add(
                StreamChoice(
                    url = url,
                    label = a.optString("quality", "").ifBlank {
                        if (br > 0) "${br / 1000}k" else "audio"
                    },
                    ext = extOf(a.optString("mimeType", ""), a.optString("format", ""), false)
                )
            )
        }
        val options = (if (muxed.isNotEmpty()) muxed else only)
            .sortedByDescending { heightOf(it) }
        if (options.isEmpty() && audios.isEmpty()) {
            throw IllegalStateException("Piped sin streams")
        }
        return Playable(
            title = d.optString("title", ""),
            uploader = d.optString("uploaderName", d.optString("uploader", "")),
            video = options.firstOrNull(),
            audio = audios.firstOrNull(),
            videoOptions = options,
            audioOptions = audios,
            description = d.optString("description", ""),
            views = d.optLong("views", d.optLong("viewCount", -1)),
            uploadDate = d.optString("uploadDate", d.optString("uploadedDate", "")),
            related = mapItems(d.optJSONArray("relatedStreams") ?: JSONArray())
        )
    }

    private fun mapItems(items: JSONArray): List<VideoHit> {
        val out = mutableListOf<VideoHit>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            if (o.optString("type", "stream") != "stream") continue
            val rel = o.optString("url", "")
            if ("/watch" !in rel && "v=" !in rel) continue
            out.add(
                VideoHit(
                    title = o.optString("title", ""),
                    url = if (rel.startsWith("http")) rel else "https://www.youtube.com$rel",
                    uploader = o.optString("uploaderName", o.optString("uploader", "")),
                    durationSec = o.optLong("duration", 0),
                    views = o.optLong("views", o.optLong("viewCount", -1)),
                    thumbUrl = o.optString("thumbnail", o.optString("thumbnailUrl", "")).ifBlank { null }
                )
            )
        }
        return out
    }

    private fun heightOf(c: StreamChoice): Int =
        c.label.filter { it.isDigit() }.toIntOrNull() ?: 0

    private fun extOf(mime: String, format: String, video: Boolean): String {
        val m = mime.lowercase()
        return when {
            "webm" in m -> "webm"
            "mp4" in m || "m4a" in m -> if (video) "mp4" else "m4a"
            "mp3" in m -> "mp3"
            "ogg" in m || "opus" in m -> if (video) "webm" else "opus"
            format.equals("WEBM", true) -> "webm"
            format.equals("MPEG_4", true) -> if (video) "mp4" else "m4a"
            else -> if (video) "mp4" else "m4a"
        }
    }

    fun videoId(pageUrl: String): String? {
        val r = Regex("(?:[?&]v=|/shorts/|/embed/|youtu\\.be/|/live/|/v/)([A-Za-z0-9_-]{11})")
        return r.find(pageUrl)?.groupValues?.getOrNull(1)
    }

    private fun getAny(path: String, params: Map<String, String>): JSONObject =
        JSONObject(getAnyRaw(path, params))

    private fun getAnyRaw(path: String, params: Map<String, String>): String {
        var lastErr: Exception? = null
        for (base in INSTANCES) {
            try {
                val url = (base + path).toHttpUrlOrNull()?.newBuilder()?.apply {
                    params.forEach { (k, v) -> addQueryParameter(k, v) }
                }?.build() ?: continue
                val req = Request.Builder().url(url).header("User-Agent", UA).build()
                client.newCall(req).execute().use { raw ->
                    if (!raw.isSuccessful) throw IllegalStateException("HTTP ${raw.code}")
                    return raw.body?.string() ?: throw IllegalStateException("vacio")
                }
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: IllegalStateException("Piped sin respuesta")
    }
}
