package com.nexos.play.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.ConcurrentHashMap

data class VideoHit(
    val title: String,
    val url: String,
    val uploader: String,
    val durationSec: Long,
    val views: Long,
    val thumbUrl: String?
)

data class StreamChoice(val url: String, val label: String, val ext: String)

data class Playable(
    val title: String,
    val uploader: String,
    val video: StreamChoice?,
    val audio: StreamChoice?,
    val videoOptions: List<StreamChoice> = emptyList(),
    val description: String = "",
    val views: Long = -1,
    val uploadDate: String = "",
    val related: List<VideoHit> = emptyList()
)

/** Búsqueda y resolución sin cuenta ni API key, vía NewPipeExtractor. */
object YouTubeRepo {

    private const val CACHE_TTL_MS = 30 * 60 * 1000L
    private const val CACHE_MAX = 50

    private data class Cached(val at: Long, val playable: Playable)

    private val cache = ConcurrentHashMap<String, Cached>()

    suspend fun search(query: String): List<VideoHit> = withContext(Dispatchers.IO) {
        retryIO {
            val service = NewPipe.getService("YouTube")
            val info = SearchInfo.getInfo(service, service.searchQHFactory.fromQuery(query))
            mapHits(info.relatedItems)
        }
    }

    suspend fun resolve(pageUrl: String): Playable {
        cache[pageUrl]?.let {
            if (System.currentTimeMillis() - it.at < CACHE_TTL_MS) return it.playable
        }
        val playable = withContext(Dispatchers.IO) {
            retryIO {
                val service = NewPipe.getService("YouTube")
                val info = StreamInfo.getInfo(service, pageUrl)
                val options = muxedOptions(info.videoStreams.orEmpty())
                Playable(
                    title = info.name,
                    uploader = info.uploaderName ?: "",
                    video = options.firstOrNull(),
                    audio = bestAudio(info.audioStreams.orEmpty()),
                    videoOptions = options,
                    description = info.description?.content().orEmpty(),
                    views = info.viewCount,
                    uploadDate = info.textualUploadDate.orEmpty(),
                    related = mapHits(info.relatedItems.orEmpty())
                )
            }
        }
        if (cache.size >= CACHE_MAX) cache.clear()
        cache[pageUrl] = Cached(System.currentTimeMillis(), playable)
        return playable
    }

    private fun mapHits(items: List<InfoItem>): List<VideoHit> {
        return items
            .filterIsInstance<StreamInfoItem>()
            .filter { it.streamType == StreamType.VIDEO_STREAM }
            .map {
                VideoHit(
                    title = it.name,
                    url = it.url,
                    uploader = it.uploaderName ?: "",
                    durationSec = it.duration,
                    views = it.viewCount,
                    thumbUrl = it.thumbnails.firstOrNull()?.url
                )
            }
    }

    /** Todos los mp4 progresivos (video+audio juntos), de mayor a menor altura. */
    private fun muxedOptions(streams: List<VideoStream>): List<StreamChoice> {
        return streams
            .filter {
                it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.isVideoOnly
            }
            .sortedByDescending { maxOf(it.height, 0) }
            .map { StreamChoice(it.content, it.resolution, it.format?.suffix ?: "mp4") }
    }

    /** Mejor audio progresivo por bitrate. */
    private fun bestAudio(streams: List<AudioStream>): StreamChoice? {
        return streams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { maxOf(it.averageBitrate, 0) }
            ?.let {
                val kbps = if (it.averageBitrate > 0) "${it.averageBitrate / 1000}k" else "audio"
                StreamChoice(it.content, kbps, it.format?.suffix ?: "m4a")
            }
    }

    private suspend fun <T> retryIO(times: Int = 2, block: () -> T): T {
        var err: Exception? = null
        repeat(times) { i ->
            try {
                return block()
            } catch (e: Exception) {
                err = e
                if (i < times - 1) delay(800)
            }
        }
        throw err!!
    }
}
