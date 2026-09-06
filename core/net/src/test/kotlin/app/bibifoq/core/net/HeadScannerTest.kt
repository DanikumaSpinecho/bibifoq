package app.bibifoq.core.net

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HeadScannerTest {

    private lateinit var server: MockWebServer
    private val engine = HttpEngine()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    @Test
    fun `stops reading at the end of the head`() = runBlocking {
        val body = buildString {
            append("<html><head><title>Small</title>")
            append("""<meta property="og:title" content="wanted">""")
            append("</head><body>")
            // A realistically bloated page body that we should never pay to read.
            append("x".repeat(4_000_000))
            append("</body></html>")
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(body))

        val head = assertNotNull(HeadScanner.fetchHead(engine, server.url("/page").toString()))

        assertTrue(head.html.endsWith("</head"), "should stop at the head close tag")
        assertTrue(head.html.contains("""content="wanted""""))
        // The point of the exercise: a fraction of the document, not all four megabytes.
        assertTrue(
            head.html.length < 1_000,
            "read ${head.html.length} chars of a 4 MB document",
        )
    }

    @Test
    fun `returns the whole document when there is no head close tag`() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>no head</body></html>"),
        )
        val head = assertNotNull(HeadScanner.fetchHead(engine, server.url("/p").toString()))
        assertEquals("<html><body>no head</body></html>", head.html)
    }

    @Test
    fun `finds a head close tag split across two reads`() = runBlocking {
        // 8 KB is the read chunk size, so this lands the closing tag on a buffer boundary.
        val padding = "<!-- ${"a".repeat(8 * 1024)} -->"
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/html")
                .setBody("<head>$padding<title>t</title></head><body>tail</body>"),
        )

        val head = assertNotNull(HeadScanner.fetchHead(engine, server.url("/p").toString()))

        assertTrue(head.html.endsWith("</head>".dropLast(1)))
        assertTrue(!head.html.contains("tail"))
    }

    @Test
    fun `honours the declared charset`() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody("<head><title>café crème</title></head>"),
        )
        val head = assertNotNull(HeadScanner.fetchHead(engine, server.url("/p").toString()))
        assertTrue(head.html.contains("café crème"))
    }

    @Test
    fun `ignores responses that are not HTML`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody("binary"))
        assertNull(HeadScanner.fetchHead(engine, server.url("/img.jpg").toString()))
    }

    @Test
    fun `ignores error responses`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "text/html").setBody("<head></head>"))
        assertNull(HeadScanner.fetchHead(engine, server.url("/missing").toString()))
    }

    @Test
    fun `honours a charset the page declares only in its own head`() = runBlocking {
        // The server says nothing about encoding; the document does. Decoding as UTF-8 anyway
        // is what turns every accent into mojibake.
        val html = "<head><meta charset=\"ISO-8859-1\"><title>Café à Noël</title></head>"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody(okio.Buffer().write(html.toByteArray(Charsets.ISO_8859_1))),
        )

        val head = assertNotNull(HeadScanner.fetchHead(engine, server.url("/p").toString()))

        assertTrue(head.html.contains("Café à Noël"), "was: ${head.html}")
    }

    @Test
    fun `prefers the server's charset over the document's`() = runBlocking {
        // A stale meta tag is common; the transport-level declaration is the authority.
        val html = "<head><meta charset=\"ISO-8859-1\"><title>Café</title></head>"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody(okio.Buffer().write(html.toByteArray(Charsets.UTF_8))),
        )

        val head = assertNotNull(HeadScanner.fetchHead(engine, server.url("/p").toString()))

        assertTrue(head.html.contains("Café"), "was: ${head.html}")
    }

    @Test
    fun `reads a content-type style charset declaration`() {
        val html = """<meta http-equiv="Content-Type" content="text/html; charset=windows-1252">"""
        assertEquals(
            java.nio.charset.Charset.forName("windows-1252"),
            HeadScanner.sniffMetaCharset(html.toByteArray(Charsets.ISO_8859_1)),
        )
    }

    @Test
    fun `ignores a charset name that names no known encoding`() {
        assertNull(HeadScanner.sniffMetaCharset("""<meta charset="utf-99">""".toByteArray()))
        assertNull(HeadScanner.sniffMetaCharset("<head><title>x</title></head>".toByteArray()))
    }

    @Test
    fun `strips a byte order mark instead of leaving it in the title`() = runBlocking {
        val html = "<head><title>Café</title></head>"
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            html.toByteArray(Charsets.UTF_8)
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/html").setBody(okio.Buffer().write(withBom)),
        )

        val head = assertNotNull(HeadScanner.fetchHead(engine, server.url("/p").toString()))

        assertTrue(head.html.startsWith("<head>"), "BOM leaked into the document: ${head.html.take(12)}")
        assertTrue(head.html.contains("Café"))
    }

    @Test
    fun `reports the URL it ended up at after a redirect`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<head><title>t</title></head>"))

        val head = assertNotNull(HeadScanner.fetchHead(engine, server.url("/start").toString()))

        assertEquals(server.url("/final").toString(), head.finalUrl)
    }
}
