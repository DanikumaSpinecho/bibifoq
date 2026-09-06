package app.bibifoq.core.model

import kotlinx.serialization.Serializable

/** A single downloadable stream. */
@Serializable
data class MediaFormat(
    val id: String,
    val url: String,
    val protocol: Protocol,
    val container: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Double? = null,
    /** Average bitrate in bits per second, when advertised. */
    val bitrateBps: Long? = null,
    val sampleRateHz: Int? = null,
    val filesizeBytes: Long? = null,
    /** True when [filesizeBytes] was derived from bitrate x duration rather than measured. */
    val filesizeApproximate: Boolean = false,
    val language: String? = null,
    val note: String? = null,
    /** Headers required to fetch [url]; merged over the parent [MediaInfo.httpHeaders]. */
    val httpHeaders: Map<String, String> = emptyMap(),
) {
    val hasVideo: Boolean get() = videoCodec != null || width != null || height != null
    val hasAudio: Boolean get() = audioCodec != null || sampleRateHz != null

    val kind: FormatKind
        get() = when {
            hasVideo && hasAudio -> FormatKind.MUXED
            hasVideo -> FormatKind.VIDEO_ONLY
            hasAudio -> FormatKind.AUDIO_ONLY
            else -> FormatKind.UNKNOWN
        }

    /** Whether the byte stream can be fetched with parallel HTTP range requests. */
    val supportsRangeParallelism: Boolean get() = protocol == Protocol.HTTPS

    /** Short human label, e.g. "1080p60 · avc1 · 4.2 MB". */
    fun label(): String = buildList {
        height?.let { h -> add(if ((frameRate ?: 0.0) > 30.5) "${h}p${frameRate!!.toInt()}" else "${h}p") }
        videoCodec?.let { add(it.substringBefore('.')) }
        if (!hasVideo) audioCodec?.let { add(it.substringBefore('.')) }
        container?.let { add(it) }
    }.joinToString(" · ").ifBlank { id }
}

@Serializable
enum class FormatKind { MUXED, VIDEO_ONLY, AUDIO_ONLY, UNKNOWN }

/** Delivery protocol, which decides how the downloader fetches the stream. */
@Serializable
enum class Protocol {
    /** A single contiguous resource fetched over HTTP(S). Supports range parallelism. */
    HTTPS,

    /** HLS: an m3u8 playlist of segments. */
    HLS,

    /** MPEG-DASH: an mpd manifest of segments. */
    DASH,

    /** Anything we recognise but cannot fetch ourselves — handed to yt-dlp. */
    UNSUPPORTED,
}
