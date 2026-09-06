package app.bibifoq.core.resolver.extractors

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class HtmlHeadTest {

    @Test
    fun `reads OpenGraph and Twitter card properties`() {
        val head = HtmlHead.parse(
            """
            <head>
              <title>Fallback title</title>
              <meta property="og:title" content="A short film">
              <meta property="og:description" content='Made in 2024'>
              <meta property="og:image" content="https://cdn.example.com/thumb.jpg">
              <meta name="twitter:creator" content="@someone">
            </head>
            """.trimIndent(),
        )

        assertEquals("A short film", head["og:title"])
        assertEquals("Made in 2024", head["og:description"])
        assertEquals("https://cdn.example.com/thumb.jpg", head["og:image"])
        assertEquals("@someone", head["twitter:creator"])
        assertEquals("Fallback title", head.title)
    }

    @Test
    fun `falls through a list of property names in order`() {
        val head = HtmlHead.parse("""<meta name="twitter:title" content="from twitter">""")
        assertEquals("from twitter", head["og:title", "twitter:title"])
        assertNull(head["og:title", "og:site_name"])
    }

    @Test
    fun `finds the advertised oEmbed endpoint`() {
        val head = HtmlHead.parse(
            """<link rel="alternate" type="application/json+oembed" href="https://x.example/oembed?url=a&amp;b=1" title="oEmbed">""",
        )
        assertEquals("https://x.example/oembed?url=a&b=1", head.oEmbedHref())
    }

    @Test
    fun `finds the canonical URL`() {
        val head = HtmlHead.parse("""<link rel="canonical" href="https://example.com/real">""")
        assertEquals("https://example.com/real", head.canonicalUrl())
    }

    @Test
    fun `captures JSON-LD blocks whole`() {
        val head = HtmlHead.parse(
            """
            <script type="application/ld+json">{"@type":"VideoObject","name":"x"}</script>
            <script type="application/javascript">var a = 1;</script>
            """.trimIndent(),
        )
        assertEquals(1, head.jsonLdBlocks.size)
        assertEquals("""{"@type":"VideoObject","name":"x"}""", head.jsonLdBlocks.single())
    }

    @Test
    fun `decodes the entities that actually appear in titles`() {
        assertEquals("Rock & Roll", HtmlHead.decodeEntities("Rock &amp; Roll"))
        assertEquals("\"quoted\"", HtmlHead.decodeEntities("&quot;quoted&quot;"))
        assertEquals("it's", HtmlHead.decodeEntities("it&#39;s"))
        assertEquals("it's", HtmlHead.decodeEntities("it&#x27;s"))
        assertEquals("café", HtmlHead.decodeEntities("caf&#233;"))
    }

    @Test
    fun `does not double-decode an escaped entity`() {
        // "&amp;#39;" is a literal "&#39;", not an apostrophe.
        assertEquals("&#39;", HtmlHead.decodeEntities("&amp;#39;"))
    }

    @Test
    fun `keeps the first value when a property is repeated`() {
        val head = HtmlHead.parse(
            """
            <meta property="og:image" content="https://first.example/a.jpg">
            <meta property="og:image" content="https://second.example/b.jpg">
            """.trimIndent(),
        )
        assertEquals("https://first.example/a.jpg", head["og:image"])
    }
}
