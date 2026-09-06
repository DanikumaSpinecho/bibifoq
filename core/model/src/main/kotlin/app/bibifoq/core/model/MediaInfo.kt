package app.bibifoq.core.model

import kotlinx.serialization.Serializable

/**
 * Everything the app knows about a single downloadable item.
 *
 * An instance may be *partial*: the resolver emits a cheap preview long before the
 * full format list is known, so [completeness] tells the UI how much it can trust.
 */
@Serializable
data class MediaInfo(
    /** The URL the user actually gave us, after normalisation. */
    val sourceUrl: String,
    /** Stable identifier within [extractor]; falls back to a hash of [sourceUrl]. */
    val id: String,
    val title: String,
    val uploader: String? = null,
    val description: String? = null,
    /** Runtime in milliseconds. Null for live streams and for sites that do not publish it. */
    val durationMs: Long? = null,
    val thumbnailUrl: String? = null,
    /** ISO-8601 date (yyyy-MM-dd) when published, when the site tells us. */
    val uploadDate: String? = null,
    val webpageUrl: String = sourceUrl,
    val isLive: Boolean = false,
    val formats: List<MediaFormat> = emptyList(),
    /** Non-empty when [sourceUrl] pointed at a playlist/channel rather than one item. */
    val entries: List<PlaylistEntry> = emptyList(),
    /** Name of the extractor that produced this, e.g. "opengraph" or "yt-dlp:Vimeo". */
    val extractor: String,
    val provenance: Provenance,
    val completeness: Completeness,
    /** Headers that must be replayed when fetching [formats]; referer/cookies mostly. */
    val httpHeaders: Map<String, String> = emptyMap(),
) {
    val isPlaylist: Boolean get() = entries.isNotEmpty()

    /** True when we have enough to start a download without asking anyone else. */
    val isDownloadable: Boolean get() = formats.isNotEmpty()

    /**
     * Merges a later, richer resolution over this one. Fields the newer pass left blank keep
     * the preview's value, so upgrading from [Completeness.PREVIEW] never blanks the UI.
     */
    fun mergedWith(newer: MediaInfo): MediaInfo = newer.copy(
        title = newer.title.ifBlank { title },
        uploader = newer.uploader ?: uploader,
        description = newer.description ?: description,
        durationMs = newer.durationMs ?: durationMs,
        thumbnailUrl = newer.thumbnailUrl ?: thumbnailUrl,
        uploadDate = newer.uploadDate ?: uploadDate,
        formats = newer.formats.ifEmpty { formats },
        entries = newer.entries.ifEmpty { entries },
        httpHeaders = httpHeaders + newer.httpHeaders,
    )
}

/** Where a [MediaInfo] came from — surfaced in the UI's debug panel and in benchmarks. */
@Serializable
enum class Provenance {
    /** Served from the on-device metadata cache; no network at all. */
    CACHE,

    /** Produced by a native Kotlin extractor (oEmbed, JSON-LD, OpenGraph, manifest…). */
    NATIVE,

    /** Produced by the embedded yt-dlp runtime. */
    YTDLP,
}

/** How much of a [MediaInfo] is filled in. */
@Serializable
enum class Completeness {
    /**
     * Title/thumbnail/duration are trustworthy; [MediaInfo.formats] is empty or partial.
     * Good enough to render a card, not good enough to pick a quality.
     */
    PREVIEW,

    /** The format list is authoritative. Safe to start a download from. */
    COMPLETE,
}

/** One item of a playlist, kept deliberately thin so flat playlist parsing stays cheap. */
@Serializable
data class PlaylistEntry(
    val id: String,
    val title: String,
    val url: String,
    val durationMs: Long? = null,
    val thumbnailUrl: String? = null,
)
