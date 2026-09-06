package app.bibifoq.engine

import app.bibifoq.core.model.Completeness
import app.bibifoq.core.model.MediaFormat
import app.bibifoq.core.model.MediaInfo
import app.bibifoq.core.model.PlaylistEntry
import app.bibifoq.core.model.Protocol
import app.bibifoq.core.model.Provenance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Converts the extraction engine's JSON dump into the app's own model.
 *
 * Kept free of Android APIs so it can be unit tested on the JVM: this mapping is where most
 * engine-related bugs would otherwise hide, since the JSON is loosely typed, varies by site,
 * and uses sentinel strings (`"none"`) where a null would be expected.
 */
object YtDlpInfoMapper {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(rawJson: String, sourceUrl: String): MediaInfo {
        val root = json.parseToJsonElement(rawJson) as? JsonObject
            ?: error("engine returned something that is not a JSON object")
        return map(root, sourceUrl)
    }

    fun map(root: JsonObject, sourceUrl: String): MediaInfo {
        val headers = root.obj("http_headers")?.toStringMap().orEmpty()
        val entries = (root["entries"] as? JsonArray).orEmpty()

        return MediaInfo(
            sourceUrl = sourceUrl,
            id = root.str("id") ?: sourceUrl.hashCode().toString(),
            title = root.str("title")
                ?: root.str("fulltitle")
                ?: root.str("alt_title")
                ?: "Untitled",
            uploader = root.str("uploader") ?: root.str("channel") ?: root.str("creator"),
            description = root.str("description"),
            durationMs = root.num("duration")?.let { (it * 1000).toLong() },
            thumbnailUrl = root.str("thumbnail") ?: bestThumbnail(root),
            uploadDate = root.str("upload_date")?.let(::formatUploadDate),
            webpageUrl = root.str("webpage_url") ?: sourceUrl,
            isLive = root.bool("is_live") ?: false,
            formats = mapFormats(root, headers),
            entries = entries.mapNotNull { mapEntry(it) },
            extractor = "yt-dlp:" + (root.str("extractor_key") ?: root.str("extractor") ?: "generic"),
            provenance = Provenance.YTDLP,
            completeness = Completeness.COMPLETE,
            httpHeaders = headers,
        )
    }

    private fun mapFormats(root: JsonObject, parentHeaders: Map<String, String>): List<MediaFormat> {
        val formats = (root["formats"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let { format -> mapFormat(format, parentHeaders) } }

        if (formats.isNotEmpty()) return formats

        // Some extractors return a single stream at the top level with no `formats` array.
        val directUrl = root.str("url") ?: return emptyList()
        return listOf(
            MediaFormat(
                id = root.str("format_id") ?: "default",
                url = directUrl,
                protocol = protocolOf(root.str("protocol"), root.str("ext")),
                container = root.str("ext"),
                videoCodec = root.codec("vcodec"),
                audioCodec = root.codec("acodec"),
                width = root.int("width"),
                height = root.int("height"),
                httpHeaders = parentHeaders,
            ),
        )
    }

    private fun mapFormat(format: JsonObject, parentHeaders: Map<String, String>): MediaFormat? {
        val url = format.str("url") ?: return null
        val filesize = format.num("filesize")?.toLong()
        val approximate = format.num("filesize_approx")?.toLong()

        return MediaFormat(
            id = format.str("format_id") ?: url.hashCode().toString(),
            url = url,
            protocol = protocolOf(format.str("protocol"), format.str("ext")),
            container = format.str("ext"),
            videoCodec = format.codec("vcodec"),
            audioCodec = format.codec("acodec"),
            width = format.int("width"),
            height = format.int("height"),
            frameRate = format.num("fps"),
            // The engine reports bitrates in kbit/s; the model stores bit/s.
            bitrateBps = (format.num("tbr") ?: format.num("vbr") ?: format.num("abr"))
                ?.let { (it * 1000).toLong() },
            sampleRateHz = format.int("asr"),
            filesizeBytes = filesize ?: approximate,
            filesizeApproximate = filesize == null && approximate != null,
            language = format.str("language"),
            note = format.str("format_note"),
            httpHeaders = format.obj("http_headers")?.toStringMap() ?: parentHeaders,
        )
    }

    private fun mapEntry(element: JsonElement): PlaylistEntry? {
        val entry = element as? JsonObject ?: return null
        val id = entry.str("id") ?: return null
        return PlaylistEntry(
            id = id,
            title = entry.str("title") ?: id,
            url = entry.str("url") ?: entry.str("webpage_url") ?: return null,
            durationMs = entry.num("duration")?.let { (it * 1000).toLong() },
            thumbnailUrl = entry.str("thumbnail") ?: bestThumbnail(entry),
        )
    }

    /**
     * Maps the engine's protocol name onto how *we* would fetch the stream.
     *
     * This decides whether the download goes through the parallel range downloader or the
     * segment fetcher, so guessing wrong here means a download that cannot work.
     */
    internal fun protocolOf(protocol: String?, extension: String?): Protocol = when {
        protocol == null -> when (extension?.lowercase()) {
            "m3u8" -> Protocol.HLS
            "mpd" -> Protocol.DASH
            else -> Protocol.HTTPS
        }
        protocol.startsWith("m3u8") -> Protocol.HLS
        protocol.startsWith("http_dash") || protocol == "dash" -> Protocol.DASH
        protocol == "https" || protocol == "http" -> Protocol.HTTPS
        // Anything else (rtmp, ws, ftp, "niconico_dmc"…) we cannot fetch ourselves.
        else -> Protocol.UNSUPPORTED
    }

    /** `20240311` is what the engine emits; `2024-03-11` is what the rest of the app expects. */
    internal fun formatUploadDate(raw: String): String? {
        if (raw.length != 8 || raw.any { !it.isDigit() }) return null
        return "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}"
    }

    /** Picks the largest thumbnail the engine listed, since it sorts them worst-first. */
    private fun bestThumbnail(root: JsonObject): String? =
        (root["thumbnails"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.maxByOrNull { (it.int("width") ?: it.int("preference") ?: 0) }
            ?.str("url")

    /**
     * Codecs come back as the string `"none"` rather than null when a stream lacks that track,
     * and treating that as a codec name would make every audio stream look like a video.
     */
    private fun JsonObject.codec(key: String): String? =
        str(key)?.takeIf { !it.equals("none", ignoreCase = true) }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }
            ?.content
            ?.takeIf { it.isNotBlank() && it != "null" && it != "NA" }

    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun JsonObject.int(key: String): Int? = num(key)?.toInt()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.content?.lowercase()?.let {
            when (it) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
        }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.toStringMap(): Map<String, String> =
        mapNotNull { (key, value) -> (value as? JsonPrimitive)?.content?.let { key to it } }.toMap()

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
