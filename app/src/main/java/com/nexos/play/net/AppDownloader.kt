package com.nexos.play.net

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Cliente HTTP del extractor, con lógica tomada de NewPipe/DownloaderImpl. */
class AppDownloader private constructor() : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"

        @Volatile private var instance: AppDownloader? = null

        fun get(): AppDownloader = instance ?: synchronized(this) {
            instance ?: AppDownloader().also { instance = it }
        }

        @Volatile private var playClient: OkHttpClient? = null

        /** Cliente para el reproductor: mismo UA, HTTP/2 y reintentos. */
        fun playbackClient(): OkHttpClient = playClient ?: synchronized(this) {
            playClient ?: OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("User-Agent", USER_AGENT)
                            .build()
                    )
                }
                .build()
                .also { playClient = it }
        }
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        var body = request.dataToSend()?.toRequestBody()
        if (body == null && (method == "POST" || method == "PUT" || method == "PATCH")) {
            body = ByteArray(0).toRequestBody()
        }
        val builder = okhttp3.Request.Builder()
            .method(method, body)
            .url(request.url())
            .addHeader("User-Agent", USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        client.newCall(builder.build()).execute().use { raw ->
            if (raw.code == 429) {
                throw ReCaptchaException("Captcha pedido por el servidor", request.url())
            }
            val text = raw.body?.string() ?: ""
            return Response(
                raw.code,
                raw.message,
                raw.headers.toMultimap(),
                text,
                raw.request.url.toString()
            )
        }
    }
}
