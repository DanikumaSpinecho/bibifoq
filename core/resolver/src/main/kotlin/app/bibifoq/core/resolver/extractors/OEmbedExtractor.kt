package app.bibifoq.core.resolver.extractors

import app.bibifoq.core.model.Completeness
import app.bibifoq.core.model.MediaInfo
import app.bibifoq.core.model.Provenance
import app.bibifoq.core.net.HttpEngine
import app.bibifoq.core.net.UserAgents
import app.bibifoq.core.resolver.ExtractionContext
import app.bibifoq.core.resolver.NativeExtractor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import app.bibifoq.core.net.runCatchingCancellable

/**
 * Reads metadata from a site's oEmbed endpoint.
 *
 * oEmbed is a published cross-site standard, which is what makes it worth building on: one
 * implementation covers every host that speaks it, and the response is a small JSON document
 * rather than a megabyte of page markup. For hosts in [KNOWN_ENDPOINTS] we skip discovery
 * entirely and go straight to the endpoint, so a resolve is a single small request.
 *
 * The result is a [Completeness.PREVIEW]: oEmbed describes a video, it does not hand out
 * stream URLs. That is exactly the split the tiered resolver is built around - put the title
 * and thumbnail on screen now, work out the formats behind it.
 */
class OEmbedExtractor(
    private val engine: HttpEngine,
    private val json: Json = LenientJson,
) : NativeExtractor {

    override val name: String = "oembed"
    override val priority: Int = 10

    override fun canHandle(url: HttpUrl): Boolean = true

    override suspend fun extract(context: ExtractionContext): MediaInfo? {
        val endpoint = knownEndpointFor(context.url, context.normalizedUrl)
            ?: discoverEndpoint(context)
            ?: return null

        val payload = fetchJson(endpoint) ?: return null
        val title = payload.string("title") ?: return null

        return MediaInfo(
            sourceUrl = context.normalizedUrl,
            id = payload.string("video_id")
                ?: context.url.pathSegments.lastOrNull().orEmpty().ifBlank { context.url.host },
            title = title,
            uploader = payload.string("author_name"),
            description = payload.string("description"),
            durationMs = payload.number("duration")?.let { seconds -> (seconds * 1000).toLong() },
            thumbnailUrl = payload.string("thumbnail_url"),
            uploadDate = payload.string("upload_date"),
            extractor = "$name:${payload.string("provider_name") ?: context.url.host}",
            provenance = Provenance.NATIVE,
            completeness = Completeness.PREVIEW,
        )
    }

    /** Endpoint from the site's own `<link rel="alternate" type="application/json+oembed">`. */
    private suspend fun discoverEndpoint(context: ExtractionContext): HttpUrl? {
        val head = context.pageHead() ?: return null
        val href = HtmlHead.parse(head.html).oEmbedHref() ?: return null
        val resolved = context.url.resolve(href) ?: href.toHttpUrlOrNull() ?: return null
        return resolved.newBuilder().setQueryParameter("format", "json").build()
    }

    private fun knownEndpointFor(url: HttpUrl, normalizedUrl: String): HttpUrl? {
        val host = url.host.removePrefix("www.")
        val template = KNOWN_ENDPOINTS.entries
            .firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }
            ?.value
            ?: return null
        return template.toHttpUrlOrNull()
            ?.newBuilder()
            ?.setQueryParameter("url", normalizedUrl)
            ?.setQueryParameter("format", "json")
            ?.build()
    }

    private suspend fun fetchJson(endpoint: HttpUrl): JsonObject? {
        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", UserAgents.DESKTOP)
            .header("Accept", "application/json")
            .build()
        val response = runCatchingCancellable { engine.execute(request) }.getOrNull() ?: return null
        return response.use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = runCatching { resp.peekBody(RESPONSE_LIMIT).string() }.getOrNull()
                ?: return@use null
            runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString || !it.content.equals("null", true) }
            ?.content
            ?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

    companion object {
        private const val RESPONSE_LIMIT = 512L * 1024

        internal val LenientJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        /**
         * Documented public oEmbed endpoints, so we can skip the discovery round trip.
         *
         * Keyed by registrable domain; subdomains match too.
         */
        val KNOWN_ENDPOINTS: Map<String, String> = mapOf(
            "vimeo.com" to "https://vimeo.com/api/oembed.json",
            "soundcloud.com" to "https://soundcloud.com/oembed",
            "dailymotion.com" to "https://www.dailymotion.com/services/oembed",
            "tiktok.com" to "https://www.tiktok.com/oembed",
            "reddit.com" to "https://www.reddit.com/oembed",
            "flickr.com" to "https://www.flickr.com/services/oembed",
            "streamable.com" to "https://api.streamable.com/oembed.json",
            "mixcloud.com" to "https://app.mixcloud.com/oembed/",
            "ted.com" to "https://www.ted.com/services/v1/oembed.json",
            "kickstarter.com" to "https://www.kickstarter.com/services/oembed",
            "youtube.com" to "https://www.youtube.com/oembed",
            "youtu.be" to "https://www.youtube.com/oembed",
        )
    }
}
