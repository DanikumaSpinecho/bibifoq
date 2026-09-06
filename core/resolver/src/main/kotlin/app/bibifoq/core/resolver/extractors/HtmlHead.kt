package app.bibifoq.core.resolver.extractors

/**
 * A tiny, forgiving reader for the handful of `<head>` constructs that carry media metadata.
 *
 * A full HTML parser is the wrong tool here: we are looking at a fragment that may be cut off
 * mid-document (see `HeadScanner`), from sites whose markup is frequently invalid, and we only
 * ever need four tag shapes. Scanning for those directly is both smaller and faster than
 * building a DOM we would immediately throw away.
 */
internal object HtmlHead {

    fun parse(html: String): HeadMetadata {
        val metas = META_TAG.findAll(html)
            .map { HtmlAttributes.parse(it.groupValues[1]) }
            .toList()

        val properties = mutableMapOf<String, String>()
        metas.forEach { attrs ->
            // OpenGraph uses `property`, Twitter cards and plain HTML use `name`.
            val key = (attrs["property"] ?: attrs["name"] ?: attrs["itemprop"])?.lowercase()
            val value = attrs["content"] ?: return@forEach
            if (key != null && key !in properties) properties[key] = decodeEntities(value)
        }

        val links = LINK_TAG.findAll(html)
            .map { HtmlAttributes.parse(it.groupValues[1]) }
            .toList()

        val jsonLd = JSON_LD.findAll(html)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()

        val title = TITLE_TAG.find(html)?.groupValues?.get(1)?.trim()?.let(::decodeEntities)

        return HeadMetadata(
            title = title,
            properties = properties,
            links = links,
            jsonLdBlocks = jsonLd,
        )
    }

    /** Resolves HTML character references. See [HtmlEntities] for why this is not trivial. */
    fun decodeEntities(input: String): String = HtmlEntities.decode(input)

    private val META_TAG = Regex("""<meta\s+([^>]*?)/?>""", RegexOption.IGNORE_CASE)
    private val LINK_TAG = Regex("""<link\s+([^>]*?)/?>""", RegexOption.IGNORE_CASE)
    private val TITLE_TAG = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val JSON_LD = Regex(
        """<script[^>]+type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
}

/** What we managed to pull out of a `<head>`. */
internal data class HeadMetadata(
    val title: String?,
    /** OpenGraph / Twitter / itemprop values, keyed by lowercased property name. */
    val properties: Map<String, String>,
    val links: List<Map<String, String>>,
    val jsonLdBlocks: List<String>,
) {
    operator fun get(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { properties[it]?.takeIf(String::isNotBlank) }

    /** The oEmbed endpoint the page advertises, if it advertises one. */
    fun oEmbedHref(): String? = links.firstOrNull { link ->
        link["rel"].equals("alternate", ignoreCase = true) &&
            link["type"]?.contains("oembed", ignoreCase = true) == true
    }?.get("href")?.let(HtmlHead::decodeEntities)

    fun canonicalUrl(): String? = links
        .firstOrNull { it["rel"].equals("canonical", ignoreCase = true) }
        ?.get("href")
        ?.let(HtmlHead::decodeEntities)
}

/** Parses the attribute soup inside a single tag: `a="1" b='2' c=3 d`. */
internal object HtmlAttributes {
    fun parse(raw: String): Map<String, String> =
        ATTRIBUTE.findAll(raw).associate { match ->
            val name = match.groupValues[1].lowercase()
            val value = match.groupValues[2].ifEmpty {
                match.groupValues[3].ifEmpty { match.groupValues[4] }
            }
            name to value
        }

    private val ATTRIBUTE = Regex("""([\w:.\-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")
}
