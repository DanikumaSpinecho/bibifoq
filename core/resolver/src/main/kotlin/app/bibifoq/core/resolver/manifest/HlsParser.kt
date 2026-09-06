package app.bibifoq.core.resolver.manifest

import app.bibifoq.core.model.MediaFormat
import app.bibifoq.core.model.Protocol
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Reads an HLS playlist into a format list.
 *
 * A master playlist already contains everything a quality picker needs - resolution, bitrate,
 * codecs, frame rate - as plain text. Parsing it directly costs one small request, where asking
 * a general extraction engine for the same answer costs an interpreter start plus its own
 * fetches.
 */
object HlsParser {

    /**
     * @param playlist raw m3u8 text
     * @param baseUrl the URL [playlist] was fetched from, used to resolve relative URIs
     */
    fun parse(playlist: String, baseUrl: HttpUrl): HlsPlaylist {
        val lines = playlist.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != "#EXTM3U") return HlsPlaylist.Invalid

        return if (lines.any { it.startsWith(STREAM_INF) }) {
            HlsPlaylist.Master(parseMaster(lines, baseUrl), parseAudioRenditions(lines, baseUrl))
        } else if (lines.any { it.startsWith(EXTINF) }) {
            val segments = parseSegments(lines, baseUrl)
            HlsPlaylist.Media(
                durationMs = sumSegmentDurations(lines),
                isLive = lines.none { it == ENDLIST },
                segmentCount = segments.size,
                segments = segments,
                mediaSequence = lines.firstOrNull { it.startsWith(MEDIA_SEQUENCE) }
                    ?.removePrefix(MEDIA_SEQUENCE)?.trim()?.toLongOrNull() ?: 0L,
            )
        } else {
            HlsPlaylist.Invalid
        }
    }

    private fun parseMaster(lines: List<String>, baseUrl: HttpUrl): List<MediaFormat> {
        val formats = mutableListOf<MediaFormat>()
        lines.forEachIndexed { index, line ->
            if (!line.startsWith(STREAM_INF)) return@forEachIndexed
            // The URI is on the next non-comment line.
            val uri = lines.drop(index + 1).firstOrNull { !it.startsWith("#") } ?: return@forEachIndexed
            val attrs = AttributeList.parse(line.removePrefix(STREAM_INF))
            val resolved = baseUrl.resolveOrNull(uri) ?: return@forEachIndexed
            val (width, height) = attrs.resolution()
            val codecs = attrs["CODECS"]?.split(',')?.map { it.trim() }.orEmpty()

            formats += MediaFormat(
                id = "hls-${attrs["BANDWIDTH"] ?: index}",
                url = resolved.toString(),
                protocol = Protocol.HLS,
                container = "mp4",
                videoCodec = codecs.firstOrNull { it.isVideoCodec() },
                // A variant with no audio codec listed still normally carries audio; only
                // treat it as video-only when the playlist points at a separate audio group.
                audioCodec = codecs.firstOrNull { it.isAudioCodec() }
                    ?: "mp4a".takeIf { attrs["AUDIO"] == null },
                width = width,
                height = height,
                frameRate = attrs["FRAME-RATE"]?.toDoubleOrNull(),
                bitrateBps = (attrs["AVERAGE-BANDWIDTH"] ?: attrs["BANDWIDTH"])?.toLongOrNull(),
                note = attrs["VIDEO-RANGE"],
            )
        }
        return formats.sortedBy { it.bitrateBps ?: 0L }
    }

    private fun parseAudioRenditions(lines: List<String>, baseUrl: HttpUrl): List<MediaFormat> =
        lines.filter { it.startsWith(MEDIA) }
            .mapNotNull { line ->
                val attrs = AttributeList.parse(line.removePrefix(MEDIA))
                if (!attrs["TYPE"].equals("AUDIO", ignoreCase = true)) return@mapNotNull null
                val uri = attrs["URI"] ?: return@mapNotNull null
                val resolved = baseUrl.resolveOrNull(uri) ?: return@mapNotNull null
                MediaFormat(
                    id = "hls-audio-${attrs["GROUP-ID"] ?: attrs["NAME"] ?: "default"}",
                    url = resolved.toString(),
                    protocol = Protocol.HLS,
                    container = "m4a",
                    audioCodec = "mp4a",
                    language = attrs["LANGUAGE"],
                    note = attrs["NAME"],
                )
            }

    /**
     * Walks the playlist in order, carrying the encryption key forward.
     *
     * `#EXT-X-KEY` applies to every segment after it until the next one, so the key has to be
     * tracked as state rather than read per segment.
     */
    private fun parseSegments(lines: List<String>, baseUrl: HttpUrl): List<HlsSegment> {
        val segments = mutableListOf<HlsSegment>()
        var currentKey: HlsKey? = null
        var pendingDurationMs: Long? = null

        lines.forEach { line ->
            when {
                line.startsWith(KEY) -> {
                    val attrs = AttributeList.parse(line.removePrefix(KEY))
                    val method = attrs["METHOD"].orEmpty()
                    currentKey = if (method.equals("NONE", ignoreCase = true) || method.isEmpty()) {
                        null
                    } else {
                        HlsKey(
                            method = method,
                            uri = attrs["URI"]?.let { baseUrl.resolve(it)?.toString() },
                            iv = attrs["IV"],
                        )
                    }
                }
                line.startsWith(EXTINF) -> {
                    pendingDurationMs = line.removePrefix(EXTINF).substringBefore(',').trim()
                        .toDoubleOrNull()?.let { (it * 1000).toLong() }
                }
                !line.startsWith("#") -> {
                    val resolved = baseUrl.resolveOrNull(line) ?: return@forEach
                    segments += HlsSegment(
                        url = resolved.toString(),
                        durationMs = pendingDurationMs,
                        key = currentKey,
                    )
                    pendingDurationMs = null
                }
            }
        }
        return segments
    }

    private fun sumSegmentDurations(lines: List<String>): Long? {
        var total = 0.0
        var seen = false
        lines.forEach { line ->
            if (!line.startsWith(EXTINF)) return@forEach
            val value = line.removePrefix(EXTINF).substringBefore(',').trim().toDoubleOrNull()
            if (value != null) {
                total += value
                seen = true
            }
        }
        return if (seen) (total * 1000).toLong() else null
    }

    private fun HttpUrl.resolveOrNull(link: String): HttpUrl? =
        resolve(link) ?: link.toHttpUrlOrNull()

    private fun String.isVideoCodec(): Boolean =
        startsWith("avc") || startsWith("hev") || startsWith("hvc") ||
            startsWith("vp0") || startsWith("vp9") || startsWith("av01") || startsWith("dvh")

    private fun String.isAudioCodec(): Boolean =
        startsWith("mp4a") || startsWith("ac-3") || startsWith("ec-3") ||
            startsWith("opus") || startsWith("alac") || startsWith("flac")

    private const val STREAM_INF = "#EXT-X-STREAM-INF:"
    private const val MEDIA = "#EXT-X-MEDIA:"
    private const val EXTINF = "#EXTINF:"
    private const val ENDLIST = "#EXT-X-ENDLIST"
    private const val KEY = "#EXT-X-KEY:"
    private const val MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE:"
}

/** The shapes an m3u8 can take. */
sealed interface HlsPlaylist {
    /** A playlist of playlists: one entry per quality. */
    data class Master(
        val variants: List<MediaFormat>,
        val audioRenditions: List<MediaFormat>,
    ) : HlsPlaylist

    /** A playlist of segments: one single stream, whose duration we can total up. */
    data class Media(
        val durationMs: Long?,
        val isLive: Boolean,
        val segmentCount: Int,
        val segments: List<HlsSegment> = emptyList(),
        /** Sequence number of the first segment; the default IV for AES-128 derives from it. */
        val mediaSequence: Long = 0,
    ) : HlsPlaylist

    /** Not an m3u8, or one with nothing in it. */
    data object Invalid : HlsPlaylist
}

/**
 * Parses `KEY=VALUE,KEY="quoted,value"` attribute lists.
 *
 * Splitting on commas naively breaks on `CODECS="avc1.4d401f,mp4a.40.2"`, which is exactly the
 * attribute we care most about, so quoting has to be respected.
 */
internal class AttributeList private constructor(private val values: Map<String, String>) {

    operator fun get(key: String): String? = values[key]

    /** `RESOLUTION=1920x1080` split into its two halves. */
    fun resolution(): Pair<Int?, Int?> {
        val raw = values["RESOLUTION"] ?: return null to null
        val parts = raw.split('x', 'X')
        if (parts.size != 2) return null to null
        return parts[0].trim().toIntOrNull() to parts[1].trim().toIntOrNull()
    }

    companion object {
        fun parse(input: String): AttributeList {
            val result = mutableMapOf<String, String>()
            val current = StringBuilder()
            var key: String? = null
            var inQuotes = false

            fun commit() {
                val k = key
                if (k != null) result[k.trim().uppercase()] = current.toString().trim().trim('"')
                key = null
                current.setLength(0)
            }

            input.forEach { ch ->
                when {
                    ch == '"' -> {
                        inQuotes = !inQuotes
                        current.append(ch)
                    }
                    ch == '=' && !inQuotes && key == null -> {
                        key = current.toString()
                        current.setLength(0)
                    }
                    ch == ',' && !inQuotes -> commit()
                    else -> current.append(ch)
                }
            }
            commit()
            return AttributeList(result)
        }
    }
}

/** One segment of a media playlist. */
data class HlsSegment(
    val url: String,
    val durationMs: Long?,
    val key: HlsKey?,
)

/** The `#EXT-X-KEY` in force for a segment. */
data class HlsKey(
    val method: String,
    val uri: String?,
    /** Hex IV as written in the playlist (`0x...`), or null to derive it from the sequence. */
    val iv: String?,
) {
    val isAes128: Boolean get() = method.equals("AES-128", ignoreCase = true)
}
