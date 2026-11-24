package com.nexos.play.data

import org.json.JSONObject

/** Serialización común de VideoHit (claves estables, no cambiar). */
object HitJson {
    fun toJson(h: VideoHit): JSONObject = JSONObject()
        .put("title", h.title)
        .put("url", h.url)
        .put("uploader", h.uploader)
        .put("duration", h.durationSec)
        .put("views", h.views)
        .put("thumb", h.thumbUrl ?: "")

    fun fromJson(o: JSONObject): VideoHit = VideoHit(
        title = o.optString("title"),
        url = o.optString("url"),
        uploader = o.optString("uploader"),
        durationSec = o.optLong("duration"),
        views = o.optLong("views", -1),
        thumbUrl = o.optString("thumb").ifBlank { null }
    )
}
