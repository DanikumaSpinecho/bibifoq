package app.bibifoq.core.resolver.extractors

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Titles are attribute text, so publishers escape them, and a French title escapes a lot:
 * guillemets, accents, apostrophes. A partial table leaves the raw `&laquo;` in the UI and in
 * the filename on disk.
 */
class HtmlEntitiesTest {

    @Test
    fun `decodes the guillemets and accents a French title is full of`() {
        assertEquals(
            // &rsquo; is the typographic apostrophe U+2019, not the straight ASCII one.
            "« Le Voyage » d\u2019André, à Noël",
            HtmlEntities.decode("&laquo; Le Voyage &raquo; d&rsquo;Andr&eacute;, &agrave; No&euml;l"),
        )
    }

    @Test
    fun `covers the whole Latin-1 block`() {
        // The names map to U+00A0..U+00FF consecutively; if the sequence ever drifts, every
        // name after the slip decodes to the wrong character.
        assertEquals(96, HtmlEntities.NAMED.count { it.value.single().code in 0xA0..0xFF })
        assertEquals(" ", HtmlEntities.decode("&nbsp;"))
        assertEquals("¿", HtmlEntities.decode("&iquest;"))
        assertEquals("À", HtmlEntities.decode("&Agrave;"))
        assertEquals("ÿ", HtmlEntities.decode("&yuml;"))
        assertEquals("ç", HtmlEntities.decode("&ccedil;"))
        assertEquals("ü", HtmlEntities.decode("&uuml;"))
        assertEquals("ñ", HtmlEntities.decode("&ntilde;"))
    }

    @Test
    fun `treats names as case-sensitive because the specification does`() {
        assertEquals("É", HtmlEntities.decode("&Eacute;"))
        assertEquals("é", HtmlEntities.decode("&eacute;"))
    }

    @Test
    fun `decodes in a single pass so an escaped escape survives`() {
        // The publisher wrote a literal "&#39;" and escaped the ampersand to say so. Decoding
        // twice would silently turn their text into an apostrophe.
        assertEquals("&#39;", HtmlEntities.decode("&amp;#39;"))
        assertEquals("&amp;", HtmlEntities.decode("&amp;amp;"))
    }

    @Test
    fun `handles decimal and hexadecimal references`() {
        assertEquals("é", HtmlEntities.decode("&#233;"))
        assertEquals("é", HtmlEntities.decode("&#xE9;"))
        assertEquals("é", HtmlEntities.decode("&#XE9;"))
        assertEquals("…", HtmlEntities.decode("&#8230;"))
        // Outside the basic plane, so it needs a surrogate pair.
        assertEquals("🎬", HtmlEntities.decode("&#127916;"))
    }

    @Test
    fun `leaves anything it does not recognise exactly as written`() {
        assertEquals("&notanentity;", HtmlEntities.decode("&notanentity;"))
        assertEquals("a & b", HtmlEntities.decode("a & b"))
        assertEquals("Q&A", HtmlEntities.decode("Q&A"))
        assertEquals("50% &", HtmlEntities.decode("50% &"))
    }

    @Test
    fun `refuses references that are not characters`() {
        // A lone surrogate half is not a character; emitting it would corrupt the string.
        assertEquals("&#55296;", HtmlEntities.decode("&#55296;"))
        assertEquals("&#0;", HtmlEntities.decode("&#0;"))
        assertEquals("&#1114112;", HtmlEntities.decode("&#1114112;"))
    }

    @Test
    fun `decodes the typography publishers actually use`() {
        assertEquals("—", HtmlEntities.decode("&mdash;"))
        assertEquals("…", HtmlEntities.decode("&hellip;"))
        assertEquals("€", HtmlEntities.decode("&euro;"))
        assertEquals("™", HtmlEntities.decode("&trade;"))
        assertEquals("“quoted”", HtmlEntities.decode("&ldquo;quoted&rdquo;"))
        assertEquals("œuf", HtmlEntities.decode("&oelig;uf"))
    }

    @Test
    fun `returns the input untouched when there is nothing to decode`() {
        val plain = "Just a normal title"
        assertTrue(plain === HtmlEntities.decode(plain))
    }
}
