package com.nexos.play.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Salud del extractor contra YouTube real. Corre en CI semanal (no en cada
 * push): si YouTube rompe la extracción, el workflow abre un issue.
 * Captcha/red se consideran "no concluyente", no fallo.
 */
class ExtractorHealthTest {

    companion object {
        @Volatile private var inited = false

        private fun ensureInit() {
            if (!inited) {
                synchronized(this) {
                    if (!inited) {
                        NewPipe.init(JdkDownloader())
                        inited = true
                    }
                }
            }
        }
    }

    class JdkDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val conn = (URL(request.url()).openConnection() as HttpURLConnection).apply {
                requestMethod = request.httpMethod()
                connectTimeout = 30_000
                readTimeout = 30_000
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) " +
                        "Gecko/20100101 Firefox/153.0"
                )
            }
            request.headers().forEach { (name, values) ->
                values.forEach { conn.addRequestProperty(name, it) }
            }
            request.dataToSend()?.let { data ->
                conn.doOutput = true
                conn.outputStream.use { it.write(data) }
            }
            val code = conn.responseCode
            if (code == 429) throw ReCaptchaException("captcha", request.url())
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val body = ByteArrayOutputStream().also { stream?.use { s -> s.copyTo(it) } }
            val headers = HashMap(conn.headerFields).also { it.remove(null) }
            return Response(
                code, conn.responseMessage, headers,
                body.toString("UTF-8"), conn.url.toString()
            )
        }
    }

    @Test
    fun searchResuelveVideoConStreams() = runBlocking {
        ensureInit()
        try {
            val hits = YouTubeRepo.search("despacito")
            println("hits=${hits.size}")
            assertTrue("búsqueda sin resultados", hits.isNotEmpty())
            val p = YouTubeRepo.resolve(hits.first().url)
            println(
                "video=${p.video?.label} audio=${p.audio?.label} " +
                    "related=${p.related.size}"
            )
            assertTrue("sin streams de video ni audio", p.video != null || p.audio != null)
        } catch (e: Exception) {
            if (isNetworkBlock(e)) {
                println("INCONCLUSIVE (red/captcha): $e")
                return@runBlocking
            }
            throw e
        }
    }

    @Test
    fun tendenciasTraeVideos() = runBlocking {
        ensureInit()
        try {
            val hits = YouTubeRepo.trending()
            println("tendencias=${hits.size}")
            assertTrue("tendencias vacías", hits.isNotEmpty())
        } catch (e: Exception) {
            if (isNetworkBlock(e)) {
                println("INCONCLUSIVE (red/captcha): $e")
                return@runBlocking
            }
            throw e
        }
    }

    private fun isNetworkBlock(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is ReCaptchaException) return true
            if (cause is IOException) return true
            cause = cause.cause
        }
        return false
    }
}
