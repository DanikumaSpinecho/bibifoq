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

        val out = StringBuilder(input.length)
        var index = 0
        while (index < input.length) {
            val char = input[index]
            if (char != '&') {
                out.append(char)
                index++
                continue
            }
            val consumed = decodeReferenceAt(input, index, out)
            if (consumed > 0) {
                index += consumed
            } else {
                out.append(char)
                index++
            }
        }
        return out.toString()
    }

    /**
     * Decodes the reference starting at [start], appending to [out].
     *
     * @return how many characters were consumed, or 0 when this is not a reference and the
     *   `&` should be kept as written.
     */
    private fun decodeReferenceAt(input: String, start: Int, out: StringBuilder): Int {
        val afterAmpersand = start + 1
        if (afterAmpersand >= input.length) return 0

        if (input[afterAmpersand] == '#') return decodeNumericAt(input, start, out)

        var end = afterAmpersand
        while (end < input.length && input[end].isNameChar() && end - afterAmpersand < maxNameLength) {
            end++
        }
        if (end == afterAmpersand) return 0

        // Properly terminated: the name means what it says.
        if (end < input.length && input[end] == ';') {
            val replacement = NAMED[input.substring(afterAmpersand, end)] ?: return 0
            out.append(replacement)
            return end + 1 - start
        }

        // No semicolon. Browsers still decode the historical names here, and real pages rely on
        // it - "Caf&eacute" is not a typo anyone fixes. Longest match wins, but only when what
        // follows could not be part of a longer name or an assignment: that guard is what keeps
        // a query string like "?a=1&copy=2" from acquiring a copyright sign.
        var candidate = end
        while (candidate > afterAmpersand) {
            val replacement = NAMED[input.substring(afterAmpersand, candidate)]
            if (replacement != null) {
                val following = input.getOrNull(candidate)
                if (following != null && (following.isNameChar() || following == '=')) return 0
                out.append(replacement)
                return candidate - start
            }
            candidate--
        }
        return 0
    }

    /** Numeric references must be terminated; an unterminated one is far more often a false alarm. */
    private fun decodeNumericAt(input: String, start: Int, out: StringBuilder): Int {
        var index = start + 2
        val hexadecimal = index < input.length && (input[index] == 'x' || input[index] == 'X')
        if (hexadecimal) index++

        val digitsStart = index
        while (index < input.length && input[index].isDigitFor(hexadecimal)) index++
        if (index == digitsStart || index >= input.length || input[index] != ';') return 0

        val value = input.substring(digitsStart, index)
            .toIntOrNull(if (hexadecimal) 16 else 10) ?: return 0
        val replacement = codePoint(value) ?: return 0
        out.append(replacement)
        return index + 1 - start
    }

    private fun codePoint(value: Int): String? {
        if (value !in 1..0x10FFFF) return null
        // Surrogate halves are not characters; a page emitting one is broken, not expressive.
        if (value in 0xD800..0xDFFF) return null
        return String(Character.toChars(value))
    }

    /** Entity names are ASCII; accented letters never appear inside one. */
    private fun Char.isNameChar(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun Char.isDigitFor(hexadecimal: Boolean): Boolean =
        if (hexadecimal) this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F' else this in '0'..'9'

    /** Derived from the table so adding a longer name later cannot silently truncate the scan. */
    private val maxNameLength: Int by lazy { NAMED.keys.maxOf { it.length } }


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
