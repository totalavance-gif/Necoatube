package com.nexos.play.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Mini cargador de miniaturas sin dependencias, con caché en memoria. */
object Thumbs {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4)
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun load(url: String?, view: ImageView) {
        view.setImageBitmap(null)
        if (url.isNullOrBlank()) return
        view.tag = url
        cache.get(url)?.let { view.setImageBitmap(it); return }
        pool.execute {
            val bmp = fetch(url)
            if (bmp != null) {
                cache.put(url, bmp)
                main.post { if (view.tag == url) view.setImageBitmap(bmp) }
            }
        }
    }

    fun loadBitmap(url: String?, done: (Bitmap?) -> Unit) {
        if (url.isNullOrBlank()) {
            done(null)
            return
        }
        cache.get(url)?.let { done(it); return }
        pool.execute {
            val bmp = fetch(url)
            if (bmp != null) cache.put(url, bmp)
            main.post { done(bmp) }
        }
    }

    private fun fetch(url: String): Bitmap? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        conn.inputStream.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}
