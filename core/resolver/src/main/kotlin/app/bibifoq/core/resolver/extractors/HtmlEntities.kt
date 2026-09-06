package app.bibifoq.core.resolver.extractors

/**
 * Resolves HTML character references in text pulled out of a page.
 *
 * Titles are the reason this has to be right. `og:title` is attribute text, so anything a
 * publisher writes gets escaped on the way in - and a French title carries guillemets and
 * accents as a matter of course. Decoding a partial table leaves `&laquo;` and `&eacute;`
 * sitting in the UI and, worse, in the filename written to disk.
 *
 * Decoding happens in a **single pass**. Running one replacement after another is what makes
 * `&amp;#39;` - a literal `&#39;` that the publisher escaped on purpose - turn into an
 * apostrophe that was never there.
 */
internal object HtmlEntities {

    fun decode(input: String): String {
        if ('&' !in input) return input
        return REFERENCE.replace(input) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    codePoint(body.drop(2).toIntOrNull(16)) ?: match.value
                body.startsWith("#") ->
                    codePoint(body.drop(1).toIntOrNull()) ?: match.value
                // Named references are case-sensitive: &Eacute; and &eacute; differ.
                else -> NAMED[body] ?: match.value
            }
        }
    }

    private fun codePoint(value: Int?): String? {
        if (value == null || value !in 1..0x10FFFF) return null
        // Surrogate halves are not characters; a page emitting them is broken, not expressive.
        if (value in 0xD800..0xDFFF) return null
        return String(Character.toChars(value))
    }

    /** `&name;`, `&#8230;` or `&#x2026;`. The length bound keeps a stray `&` from matching far. */
    private val REFERENCE = Regex("""&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z][a-zA-Z0-9]{1,31});""")

    /**
     * Names in the Latin-1 block, in code point order from 160.
     *
     * Written as a sequence rather than 96 explicit pairs because the ordering *is* the
     * specification: HTML 4.01 assigns these names to U+00A0 through U+00FF consecutively, so
     * generating them from the order removes any chance of a transcription slip.
     */
    private val LATIN1_NAMES = (
        "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr " +
            "deg plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo frac14 frac12 " +
            "frac34 iquest Agrave Aacute Acirc Atilde Auml Aring AElig Ccedil Egrave Eacute " +
            "Ecirc Euml Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml " +
            "times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave aacute acirc " +
            "atilde auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml " +
            "eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml " +
            "yacute thorn yuml"
        ).split(' ')

    /** First code point of the Latin-1 supplement block, which [LATIN1_NAMES] enumerates. */
    const val LATIN1_START: Int = 0xA0

    internal val NAMED: Map<String, String> = buildMap {
        LATIN1_NAMES.forEachIndexed { index, name ->
            put(name, String(Character.toChars(LATIN1_START + index)))
        }

        // The five that HTML reserves, which appear in essentially every escaped title.
        put("amp", "&")
        put("lt", "<")
        put("gt", ">")
        put("quot", "\"")
        put("apos", "'")

        // Punctuation and symbols publishers actually use in titles.
        listOf(
            "OElig" to 0x152, "oelig" to 0x153, "Scaron" to 0x160, "scaron" to 0x161,
            "Yuml" to 0x178, "fnof" to 0x192, "circ" to 0x2C6, "tilde" to 0x2DC,
            "ensp" to 0x2002, "emsp" to 0x2003, "thinsp" to 0x2009,
            "ndash" to 0x2013, "mdash" to 0x2014,
            "lsquo" to 0x2018, "rsquo" to 0x2019, "sbquo" to 0x201A,
            "ldquo" to 0x201C, "rdquo" to 0x201D, "bdquo" to 0x201E,
            "dagger" to 0x2020, "Dagger" to 0x2021, "bull" to 0x2022, "hellip" to 0x2026,
            "permil" to 0x2030, "prime" to 0x2032, "Prime" to 0x2033,
            "lsaquo" to 0x2039, "rsaquo" to 0x203A, "oline" to 0x203E, "frasl" to 0x2044,
            "euro" to 0x20AC, "trade" to 0x2122,
            "larr" to 0x2190, "uarr" to 0x2191, "rarr" to 0x2192, "darr" to 0x2193,
            "harr" to 0x2194, "minus" to 0x2212, "lowast" to 0x2217, "radic" to 0x221A,
            "infin" to 0x221E, "ne" to 0x2260, "le" to 0x2264, "ge" to 0x2265,
            "loz" to 0x25CA, "spades" to 0x2660, "clubs" to 0x2663, "hearts" to 0x2665,
            "diams" to 0x2666,
        ).forEach { (name, code) -> put(name, String(Character.toChars(code))) }
    }
}
