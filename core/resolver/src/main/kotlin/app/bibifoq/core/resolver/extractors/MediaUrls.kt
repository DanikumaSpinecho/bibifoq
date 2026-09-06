package app.bibifoq.core.resolver.extractors

/** Container extensions that name audio rather than video. */
internal val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "opus", "ogg", "oga", "flac", "wav", "aac", "weba")

/** Everything we are willing to treat as a media file from a URL alone. */
internal val MEDIA_EXTENSIONS = AUDIO_EXTENSIONS +
    setOf("mp4", "webm", "mkv", "mov", "m4v", "ts", "3gp", "m3u8", "mpd")

/**
 * The media extension named by [url], or null when it names none.
 *
 * The query has to be removed first. A signed CDN URL routinely carries dots in its query - an
 * expiry timestamp, a client IP - so reading the text after the last dot of the whole string
 * returns a piece of the query rather than a file extension. That value then travels onward as
 * the format's container and codec, and ends up on screen.
 */
internal fun mediaExtensionOf(url: String): String? {
    val path = url.substringBefore('?').substringBefore('#')
    val lastSegment = path.substringAfterLast('/')
    if ('.' !in lastSegment) return null
    return lastSegment.substringAfterLast('.').lowercase().takeIf { it in MEDIA_EXTENSIONS }
}
