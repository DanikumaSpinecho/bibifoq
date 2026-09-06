package app.bibifoq.core.resolver.extractors

import app.bibifoq.core.model.Completeness
import app.bibifoq.core.model.MediaFormat
import app.bibifoq.core.model.MediaInfo
import app.bibifoq.core.model.Protocol
import app.bibifoq.core.model.Provenance
import app.bibifoq.core.resolver.ExtractionContext
import app.bibifoq.core.resolver.NativeExtractor
import app.bibifoq.core.resolver.manifest.DashParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl

/**
 * Reads schema.org JSON-LD and OpenGraph out of a page `<head>`.
 *
 * Between them these two cover an enormous share of the web, because search engines and social
 * previews depend on them - sites that publish nothing else still publish these. That makes
 * this the broadest native extractor: it works on hosts nobody wrote a dedicated extractor for.
 *
 * It runs on the `<head>` fragment the context already fetched, so on a page where the oEmbed
 * extractor also ran, this one costs no extra network at all.
 */
class StructuredDataExtractor : NativeExtractor {

    override val name: String = "structured-data"
    override val priority: Int = 20

    override fun canHandle(url: HttpUrl): Boolean = true

    override suspend fun extract(context: ExtractionContext): MediaInfo? {
        val document = context.pageHead() ?: return null
        val head = HtmlHead.parse(document.html)

        val video = head.jsonLdBlocks
            .asSequence()
            .mapNotNull { parseJsonLd(it) }
            .firstOrNull()

        val title = video?.name
            ?: head["og:title", "twitter:title"]
            ?: head.title
            ?: return null

        val contentUrl = video?.contentUrl
            ?: head["og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream"]

        val formats = buildList {
            // og:video is very often an iframe player, not a stream. Only trust it when the
            // URL actually looks like media, otherwise we would hand the downloader an HTML page.
            val direct = contentUrl?.takeIf { it.looksLikeMedia() }
            if (direct != null) {
                val extension = direct.substringAfterLast('.').substringBefore('?').lowercase()
                add(
                    MediaFormat(
                        id = "og-$extension",
                        url = direct,
                        protocol = when (extension) {
                            "m3u8" -> Protocol.HLS
                            "mpd" -> Protocol.DASH
                            else -> Protocol.HTTPS
                        },
                        container = extension,
                        videoCodec = extension.takeIf { it !in AUDIO_EXTENSIONS },
                        audioCodec = extension.takeIf { it in AUDIO_EXTENSIONS },
                        width = head["og:video:width"]?.toIntOrNull(),
                        height = head["og:video:height"]?.toIntOrNull(),
                        httpHeaders = mapOf("Referer" to document.finalUrl),
                    ),
                )
            }
        }

        return MediaInfo(
            sourceUrl = context.normalizedUrl,
            id = video?.identifier ?: context.url.pathSegments.lastOrNull().orEmpty()
                .ifBlank { context.url.host },
            title = title,
            uploader = video?.author ?: head["og:site_name", "twitter:creator", "author"],
            description = video?.description ?: head["og:description", "twitter:description"],
            durationMs = video?.durationMs
                ?: head["og:video:duration", "video:duration"]?.toDurationMs(),
            thumbnailUrl = video?.thumbnailUrl ?: head["og:image", "twitter:image", "og:image:url"],
            uploadDate = video?.uploadDate?.take(10),
            webpageUrl = head.canonicalUrl() ?: document.finalUrl,
            isLive = video?.isLive == true || head["og:video:type"]?.contains("live") == true,
            formats = formats,
            extractor = if (video != null) "$name:json-ld" else "$name:opengraph",
            provenance = Provenance.NATIVE,
            // A single og:video URL is not a quality ladder; call it a preview and let the
            // general engine offer the real choice.
            completeness = Completeness.PREVIEW,
            httpHeaders = mapOf("Referer" to document.finalUrl),
        )
    }

    /**
     * Pulls the first schema.org `VideoObject` out of a JSON-LD block.
     *
     * Blocks legitimately come as a bare object, an array of objects, or an `@graph` wrapper,
     * and a page can carry several - only one of which is the video.
     */
    internal fun parseJsonLd(block: String): VideoObject? {
        val element = runCatching { LENIENT.parseToJsonElement(block) }.getOrNull() ?: return null
        return candidates(element).firstNotNullOfOrNull { obj ->
            val type = obj["@type"]?.asStringList().orEmpty()
            if (type.none { it.equals("VideoObject", true) || it.equals("AudioObject", true) ||
                    it.equals("MusicRecording", true) || it.equals("Movie", true) }
            ) {
                return@firstNotNullOfOrNull null
            }
            VideoObject(
                name = obj.str("name") ?: obj.str("headline"),
                description = obj.str("description"),
                thumbnailUrl = obj["thumbnailUrl"]?.asStringList()?.firstOrNull()
                    ?: (obj["thumbnail"] as? JsonObject)?.str("url"),
                uploadDate = obj.str("uploadDate") ?: obj.str("datePublished"),
                durationMs = obj.str("duration")?.toDurationMs(),
                contentUrl = obj.str("contentUrl"),
                identifier = obj.str("identifier") ?: obj.str("@id"),
                author = (obj["author"] as? JsonObject)?.str("name")
                    ?: obj.str("author")
                    ?: (obj["creator"] as? JsonObject)?.str("name"),
                isLive = (obj["publication"] as? JsonObject)?.get("isLiveBroadcast")
                    ?.let { (it as? JsonPrimitive)?.content == "true" } == true,
            )
        }
    }

    /** Flattens the object / array / `@graph` shapes JSON-LD is allowed to take. */
    private fun candidates(element: JsonElement): Sequence<JsonObject> = sequence {
        when (element) {
            is JsonObject -> {
                yield(element)
                (element["@graph"] as? JsonArray)?.forEach { child ->
                    yieldAll(candidates(child))
                }
            }
            is JsonArray -> element.forEach { child -> yieldAll(candidates(child)) }
            else -> Unit
        }
    }

    /** The subset of schema.org media properties worth reading. */
    internal data class VideoObject(
        val name: String?,
        val description: String?,
        val thumbnailUrl: String?,
        val uploadDate: String?,
        val durationMs: Long?,
        val contentUrl: String?,
        val identifier: String?,
        val author: String?,
        val isLive: Boolean,
    )

    private companion object {
        val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }

        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "opus", "ogg", "flac", "wav", "aac")
        val MEDIA_EXTENSIONS = AUDIO_EXTENSIONS +
            setOf("mp4", "webm", "mkv", "mov", "m4v", "ts", "m3u8", "mpd")

        fun String.looksLikeMedia(): Boolean {
            val path = substringBefore('?').substringBefore('#')
            if ('.' !in path.substringAfterLast('/')) return false
            return path.substringAfterLast('.').lowercase() in MEDIA_EXTENSIONS
        }

        /** Durations arrive either as ISO-8601 (`PT1M33S`) or as plain seconds. */
        fun String.toDurationMs(): Long? {
            val trimmed = trim()
            if (trimmed.startsWith("P", ignoreCase = true)) {
                return DashParser.parseIso8601Duration(trimmed)
            }
            return trimmed.toDoubleOrNull()?.let { (it * 1000).toLong() }
        }

        fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

        fun JsonElement.asStringList(): List<String> = when (this) {
            is JsonPrimitive -> listOf(content)
            is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.content }
            else -> emptyList()
        }
    }
}
