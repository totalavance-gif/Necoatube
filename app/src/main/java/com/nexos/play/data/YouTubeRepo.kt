package com.nexos.play.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

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
    val audio: StreamChoice?
)

/** Búsqueda y resolución sin cuenta ni API key, vía NewPipeExtractor. */
object YouTubeRepo {

    suspend fun search(query: String): List<VideoHit> = withContext(Dispatchers.IO) {
        val service = NewPipe.getService("YouTube")
        val info = SearchInfo.getInfo(service, service.searchQHFactory.fromQuery(query))
        info.relatedItems
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

    suspend fun resolve(pageUrl: String): Playable = withContext(Dispatchers.IO) {
        val service = NewPipe.getService("YouTube")
        val info = StreamInfo.getInfo(service, pageUrl)
        Playable(
            title = info.name,
            uploader = info.uploaderName ?: "",
            video = bestMuxed(info.videoStreams.orEmpty()),
            audio = bestAudio(info.audioStreams.orEmpty())
        )
    }

    /** Mejor mp4 progresivo (video+audio juntos): se reproduce y descarga directo. */
    private fun bestMuxed(streams: List<VideoStream>): StreamChoice? {
        return streams
            .filter {
                it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.isVideoOnly
            }
            .maxByOrNull { maxOf(it.height, 0) }
            ?.let { StreamChoice(it.content, it.resolution, it.format?.suffix ?: "mp4") }
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
}
