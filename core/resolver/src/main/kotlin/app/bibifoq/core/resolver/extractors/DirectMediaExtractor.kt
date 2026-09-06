package app.bibifoq.core.resolver.extractors

import app.bibifoq.core.model.Completeness
import app.bibifoq.core.model.MediaFormat
import app.bibifoq.core.model.MediaInfo
import app.bibifoq.core.model.Protocol
import app.bibifoq.core.model.Provenance
import app.bibifoq.core.net.HttpEngine
import app.bibifoq.core.net.UserAgents
import app.bibifoq.core.net.closeQuietly
import app.bibifoq.core.resolver.ExtractionContext
import app.bibifoq.core.resolver.NativeExtractor
import app.bibifoq.core.resolver.UrlNormalizer
import app.bibifoq.core.resolver.manifest.DashParser
import app.bibifoq.core.resolver.manifest.HlsParser
import app.bibifoq.core.resolver.manifest.HlsPlaylist
import okhttp3.HttpUrl
import okhttp3.Request
import app.bibifoq.core.net.runCatchingCancellable

/**
 * Handles URLs that already point straight at media: a file, or an HLS/DASH manifest.
 *
 * This is the cheapest path in the app. A direct file needs one HEAD request; a manifest needs
 * one GET of a few kilobytes. Either way the answer is complete and authoritative, so no other
 * tier ever has to run.
 */
class DirectMediaExtractor(
    private val engine: HttpEngine,
) : NativeExtractor {

    override val name: String = "direct"
    override val priority: Int = 0

    override fun canHandle(url: HttpUrl): Boolean = url.mediaExtension() != null

    override suspend fun extract(context: ExtractionContext): MediaInfo? {
        val url = context.url
        return when (val extension = url.mediaExtension()) {
            null -> null
            "m3u8", "m3u" -> extractHls(context, url)
            "mpd" -> extractDash(context, url)
            else -> extractFile(context, url, extension)
        }
    }

    private suspend fun extractHls(context: ExtractionContext, url: HttpUrl): MediaInfo? {
        val body = fetchText(url.toString(), MANIFEST_BYTE_LIMIT) ?: return null
        val formats: List<MediaFormat>
        var durationMs: Long? = null
        var isLive = false

        when (val playlist = HlsParser.parse(body, url)) {
            is HlsPlaylist.Master -> {
                formats = playlist.variants + playlist.audioRenditions
            }
            is HlsPlaylist.Media -> {
                durationMs = playlist.durationMs
                isLive = playlist.isLive
                formats = listOf(
                    MediaFormat(
                        id = "hls",
                        url = url.toString(),
                        protocol = Protocol.HLS,
                        container = "mp4",
                        note = "${playlist.segmentCount} segments",
                    ),
                )
            }
            HlsPlaylist.Invalid -> return null
        }
        if (formats.isEmpty()) return null

        return baseInfo(context, url, formats).copy(
            durationMs = durationMs,
            isLive = isLive,
            extractor = "$name:hls",
        )
    }

    private suspend fun extractDash(context: ExtractionContext, url: HttpUrl): MediaInfo? {
        val body = fetchText(url.toString(), MANIFEST_BYTE_LIMIT) ?: return null
        val manifest = DashParser.parse(body, url) ?: return null
        return baseInfo(context, url, manifest.formats).copy(
            durationMs = manifest.durationMs,
            isLive = manifest.isLive,
            extractor = "$name:dash",
        )
    }

    private suspend fun extractFile(
        context: ExtractionContext,
        url: HttpUrl,
        extension: String,
    ): MediaInfo? {
        val probe = probe(url.toString()) ?: return null
        // A server that hands back HTML for a ".mp4" URL is serving a landing page, not a file.
        if (probe.contentType?.startsWith("text/html") == true) return null

        val isAudio = extension in AUDIO_EXTENSIONS
        val format = MediaFormat(
            id = extension,
            url = url.toString(),
            protocol = Protocol.HTTPS,
            container = extension,
            videoCodec = if (isAudio) null else extension,
            audioCodec = if (isAudio) extension else null,
            filesizeBytes = probe.contentLength,
            note = if (probe.supportsRanges) "resumable" else null,
        )
        return baseInfo(context, url, listOf(format)).copy(extractor = "$name:file")
    }

    private fun baseInfo(
        context: ExtractionContext,
        url: HttpUrl,
        formats: List<MediaFormat>,
    ) = MediaInfo(
        sourceUrl = context.normalizedUrl,
        id = UrlNormalizer.filenameStem(url) ?: url.host,
        // There is no page to read a title from, so the filename is the best we have.
        title = UrlNormalizer.filenameStem(url) ?: url.host,
        formats = formats,
        extractor = name,
        provenance = Provenance.NATIVE,
        completeness = Completeness.COMPLETE,
    )

    private suspend fun fetchText(url: String, limitBytes: Long): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UserAgents.DESKTOP)
            .build()
        val response = runCatchingCancellable { engine.execute(request) }.getOrNull() ?: return null
        return response.use { resp ->
            if (!resp.isSuccessful) return@use null
            // Manifests are small; anything larger is not one, and reading it would be waste.
            runCatching { resp.peekBody(limitBytes).string() }.getOrNull()
        }
    }

    /**
     * Asks the server about a file without downloading it.
     *
     * Falls back to a one-byte ranged GET, because a fair number of CDNs answer HEAD with 405
     * while happily serving ranges.
     */
    private suspend fun probe(url: String): Probe? {
        val head = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", UserAgents.DESKTOP)
            .build()

        runCatchingCancellable { engine.execute(head) }.getOrNull()?.let { response ->
            if (response.isSuccessful) {
                val probe = Probe(
                    contentLength = response.header("Content-Length")?.toLongOrNull(),
                    contentType = response.header("Content-Type")?.substringBefore(';')?.trim(),
                    supportsRanges = response.header("Accept-Ranges")
                        .equals("bytes", ignoreCase = true),
                )
                response.closeQuietly()
                return probe
            }
            response.closeQuietly()
        }

        val ranged = Request.Builder()
            .url(url)
            .header("User-Agent", UserAgents.DESKTOP)
            .header("Range", "bytes=0-0")
            .build()
        val response = runCatchingCancellable { engine.execute(ranged) }.getOrNull() ?: return null
        return response.use { resp ->
            if (!resp.isSuccessful) return@use null
            val total = resp.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
            Probe(
                contentLength = total,
                contentType = resp.header("Content-Type")?.substringBefore(';')?.trim(),
                supportsRanges = resp.code == 206,
            )
        }
    }

    private data class Probe(
        val contentLength: Long?,
        val contentType: String?,
        val supportsRanges: Boolean,
    )

    private companion object {
        const val MANIFEST_BYTE_LIMIT = 4L * 1024 * 1024

        val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "avi", "flv", "ts", "m4v", "3gp")
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "opus", "ogg", "oga", "flac", "wav", "aac", "weba")
        val MANIFEST_EXTENSIONS = setOf("m3u8", "m3u", "mpd")

        fun HttpUrl.mediaExtension(): String? {
            val last = pathSegments.lastOrNull()?.takeIf { it.contains('.') } ?: return null
            val extension = last.substringAfterLast('.').lowercase()
            return extension.takeIf {
                it in VIDEO_EXTENSIONS || it in AUDIO_EXTENSIONS || it in MANIFEST_EXTENSIONS
            }
        }
    }
}
