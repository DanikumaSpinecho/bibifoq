package app.bibifoq.core.resolver.extractors

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * The extension of a media URL decides its container, its protocol, and what the user is shown
 * as the format. Getting it from the wrong part of the URL is not a cosmetic slip: it puts
 * query-string fragments where a codec name belongs.
 */
class StructuredDataExtractorTest {

    @Test
    fun `reads the extension from the path, not from the query`() {
        // A signed CDN URL carries dots in its query - an expiry, an IP - so the last dot in
        // the whole string is nowhere near the file extension.
        assertEquals(
            "mp4",
            mediaExtensionOf("https://cdn.e.com/v/clip.mp4?ip=203.0.113.9&id=152&hash=wx0w%2bsg%3d"),
        )
        assertEquals("m3u8", mediaExtensionOf("https://cdn.e.com/live/master.m3u8?token=a.b.c"))
        assertEquals("mp3", mediaExtensionOf("https://cdn.e.com/a/track.mp3#t=30"))
    }

    @Test
    fun `refuses a URL that names no media extension`() {
        assertNull(mediaExtensionOf("https://e.com/watch/12345"))
        assertNull(mediaExtensionOf("https://e.com/player.html?v=1"))
        // A dot in the query must not be mistaken for an extension.
        assertNull(mediaExtensionOf("https://e.com/embed?src=1.2.3.4"))
        // A known-looking suffix that is not a media type stays rejected.
        assertNull(mediaExtensionOf("https://e.com/v/clip.txt"))
    }

    @Test
    fun `handles a bare path without a host`() {
        assertEquals("mp4", mediaExtensionOf("/media/clip.mp4"))
        assertNull(mediaExtensionOf("clip"))
    }
}
