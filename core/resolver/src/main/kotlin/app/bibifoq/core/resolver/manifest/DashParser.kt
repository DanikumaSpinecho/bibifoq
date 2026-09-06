package app.bibifoq.core.resolver.manifest

import app.bibifoq.core.model.MediaFormat
import app.bibifoq.core.model.Protocol
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Reads an MPEG-DASH manifest into a format list.
 *
 * Same idea as [HlsParser]: the manifest is a complete, self-describing quality ladder, so
 * parsing it beats asking a general engine to go and find the same thing.
 */
object DashParser {

    fun parse(manifest: String, baseUrl: HttpUrl): DashManifest? {
        val document = runCatching {
            secureFactory().newDocumentBuilder()
                .parse(ByteArrayInputStream(manifest.toByteArray(StandardCharsets.UTF_8)))
        }.getOrNull() ?: return null

        val mpd = document.documentElement?.takeIf { it.localName == "MPD" || it.tagName == "MPD" }
            ?: return null

        val durationMs = mpd.getAttribute("mediaPresentationDuration")
            .takeIf { it.isNotBlank() }
            ?.let(::parseIso8601Duration)
        val isLive = mpd.getAttribute("type").equals("dynamic", ignoreCase = true)

        // A manifest-level <BaseURL> rebases every relative URL below it.
        val manifestBase = mpd.childElements()
            .firstOrNull { it.localNameOrTag() == "BaseURL" }
            ?.textContent?.trim()
            ?.let { baseUrl.resolve(it) }
            ?: baseUrl

        val formats = mutableListOf<MediaFormat>()
        mpd.descendants("AdaptationSet").forEach { adaptationSet ->
            val setMime = adaptationSet.getAttribute("mimeType").takeIf { it.isNotBlank() }
            val setCodecs = adaptationSet.getAttribute("codecs").takeIf { it.isNotBlank() }
            val setLang = adaptationSet.getAttribute("lang").takeIf { it.isNotBlank() }
            val setFrameRate = adaptationSet.getAttribute("frameRate").takeIf { it.isNotBlank() }

            adaptationSet.childElements()
                .filter { it.localNameOrTag() == "Representation" }
                .forEach { representation ->
                    val mime = representation.attrOrNull("mimeType") ?: setMime ?: return@forEach
                    val codecs = representation.attrOrNull("codecs") ?: setCodecs
                    val isVideo = mime.startsWith("video/")
                    val isAudio = mime.startsWith("audio/")
                    if (!isVideo && !isAudio) return@forEach

                    val relative = representation.childElements()
                        .firstOrNull { it.localNameOrTag() == "BaseURL" }
                        ?.textContent?.trim()
                    val url = relative?.let { manifestBase.resolve(it) ?: it.toHttpUrlOrNull() }
                        ?: manifestBase

                    val bandwidth = representation.attrOrNull("bandwidth")?.toLongOrNull()
                    formats += MediaFormat(
                        id = representation.attrOrNull("id") ?: "dash-${formats.size}",
                        url = url.toString(),
                        // Without an explicit BaseURL the segments are described by templates
                        // we do not expand here, so the stream stays a DASH fetch.
                        protocol = if (relative != null) Protocol.HTTPS else Protocol.DASH,
                        container = mime.substringAfter('/').substringBefore(';'),
                        videoCodec = codecs.takeIf { isVideo },
                        audioCodec = codecs.takeIf { isAudio },
                        width = representation.attrOrNull("width")?.toIntOrNull(),
                        height = representation.attrOrNull("height")?.toIntOrNull(),
                        frameRate = (representation.attrOrNull("frameRate") ?: setFrameRate)
                            ?.let(::parseFrameRate),
                        bitrateBps = bandwidth,
                        sampleRateHz = representation.attrOrNull("audioSamplingRate")?.toIntOrNull(),
                        filesizeBytes = estimateSize(bandwidth, durationMs),
                        filesizeApproximate = bandwidth != null && durationMs != null,
                        language = representation.attrOrNull("lang") ?: setLang,
                    )
                }
        }

        if (formats.isEmpty()) return null
        return DashManifest(
            formats = formats.sortedBy { it.bitrateBps ?: 0L },
            durationMs = durationMs,
            isLive = isLive,
        )
    }

    /** `30000/1001` and `25` are both legal frame-rate spellings in a manifest. */
    internal fun parseFrameRate(raw: String): Double? {
        val trimmed = raw.trim()
        if (!trimmed.contains('/')) return trimmed.toDoubleOrNull()
        val numerator = trimmed.substringBefore('/').trim().toDoubleOrNull() ?: return null
        val denominator = trimmed.substringAfter('/').trim().toDoubleOrNull() ?: return null
        return if (denominator == 0.0) null else numerator / denominator
    }

    /** Parses `PT1H2M3.5S` style durations without pulling in a date library. */
    internal fun parseIso8601Duration(raw: String): Long? =
        runCatching { java.time.Duration.parse(raw.trim()).toMillis() }.getOrNull()

    private fun estimateSize(bitrateBps: Long?, durationMs: Long?): Long? {
        if (bitrateBps == null || durationMs == null) return null
        return bitrateBps / 8 * durationMs / 1000
    }

    /**
     * A parser that will not resolve external entities.
     *
     * We hand this untrusted XML from arbitrary hosts, so XXE and billion-laughs are real
     * exposures rather than theoretical ones.
     */
    private fun secureFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
            isNamespaceAware = true
        }

    private fun Element.attrOrNull(name: String): String? =
        getAttribute(name).takeIf { it.isNotBlank() }

    private fun Element.localNameOrTag(): String = localName ?: tagName

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length)
            .mapNotNull { childNodes.item(it) }
            .filterIsInstance<Element>()

    private fun Element.descendants(name: String): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(node: Node) {
            (0 until node.childNodes.length).forEach { index ->
                val child = node.childNodes.item(index) ?: return@forEach
                if (child is Element) {
                    if (child.localNameOrTag() == name) out += child
                    walk(child)
                }
            }
        }
        walk(this)
        return out
    }
}

data class DashManifest(
    val formats: List<MediaFormat>,
    val durationMs: Long?,
    val isLive: Boolean,
)
