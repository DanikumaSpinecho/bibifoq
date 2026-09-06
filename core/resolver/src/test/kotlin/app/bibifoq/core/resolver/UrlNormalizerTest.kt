package app.bibifoq.core.resolver

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class UrlNormalizerTest {

    @Test
    fun `strips tracking parameters so shares of one video share a cache entry`() {
        val fromTwitter = UrlNormalizer.normalize(
            "https://vimeo.com/123456789?utm_source=twitter&utm_campaign=x&fbclid=abc",
        )
        val fromMessages = UrlNormalizer.normalize("https://vimeo.com/123456789?si=deadbeef")
        val plain = UrlNormalizer.normalize("https://vimeo.com/123456789")

        assertEquals(plain, fromTwitter)
        assertEquals(plain, fromMessages)
    }

    @Test
    fun `keeps parameters that select the media`() {
        assertEquals(
            "https://example.com/watch?v=abc123",
            UrlNormalizer.normalize("https://example.com/watch?v=abc123&utm_source=news"),
        )
    }

    @Test
    fun `orders remaining parameters so argument order does not split the cache`() {
        assertEquals(
            UrlNormalizer.normalize("https://example.com/p?a=1&b=2"),
            UrlNormalizer.normalize("https://example.com/p?b=2&a=1"),
        )
    }

    @Test
    fun `drops www and the fragment and lowercases the host`() {
        assertEquals(
            "https://example.com/video",
            UrlNormalizer.normalize("https://WWW.Example.COM/video#t=30"),
        )
    }

    @Test
    fun `trims a meaningless trailing slash but keeps the root one`() {
        assertEquals("https://example.com/video", UrlNormalizer.normalize("https://example.com/video/"))
        assertEquals("https://example.com/", UrlNormalizer.normalize("https://example.com/"))
    }

    @Test
    fun `assumes https for a bare host`() {
        assertEquals("https://example.com/v/1", UrlNormalizer.normalize("example.com/v/1"))
    }

    @Test
    fun `rejects input that is not a usable web URL`() {
        assertNull(UrlNormalizer.normalize("ftp://example.com/file.mp4"))
        assertNull(UrlNormalizer.normalize("just some text"))
        assertNull(UrlNormalizer.normalize(""))
    }

    @Test
    fun `accepts a single-label host only when a scheme was given`() {
        assertEquals("http://localhost:8080/v.mp4", UrlNormalizer.normalize("http://localhost:8080/v.mp4"))
        // Without a scheme, a bare word must not be guessed into a request.
        assertNull(UrlNormalizer.normalize("video"))
    }

    @Test
    fun `pulls the URL out of shared text`() {
        val shared = "Check this out https://example.com/v/42, it's great!"
        assertEquals("https://example.com/v/42", UrlNormalizer.extractFirstUrl(shared))
    }

    @Test
    fun `keeps path characters that only look like punctuation`() {
        assertEquals(
            "https://example.com/a(b)c",
            UrlNormalizer.extractFirstUrl("see https://example.com/a(b)c"),
        )
    }
}
