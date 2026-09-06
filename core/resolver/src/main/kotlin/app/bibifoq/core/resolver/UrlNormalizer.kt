package app.bibifoq.core.resolver

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Turns whatever the user pasted into a canonical URL.
 *
 * This exists mostly for the metadata cache: the same video shared from three apps arrives
 * with three different tracking-parameter soups, and without normalisation each one is a cache
 * miss and a fresh extraction.
 */
object UrlNormalizer {

    /** Parameters that never change which media a URL points at. */
    private val TRACKING_PARAMS = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_name",
        "utm_id", "utm_referrer",
        "fbclid", "gclid", "dclid", "gclsrc", "msclkid", "twclid", "ttclid", "igshid", "igsh",
        "mc_cid", "mc_eid", "yclid", "_ga", "_gl", "ref_src", "ref_url", "share_id",
        "si", "feature", "spm", "scene", "from", "src", "source", "app", "is_from_webapp",
        "sender_device", "web_id", "_r", "_t", "cid", "trk", "trkCampaign",
    )

    /** Schemes we are willing to touch at all. */
    private val ALLOWED_SCHEMES = setOf("http", "https")

    /**
     * Extracts the first URL out of arbitrary shared text.
     *
     * Share sheets love to hand over `"Look at this  https://example.com/v/1  #cool"`, so
     * pasting is a text-scraping problem before it is a URL problem.
     */
    fun extractFirstUrl(text: String): String? {
        val match = URL_IN_TEXT.find(text) ?: return null
        // Trailing punctuation is almost always sentence punctuation, not part of the URL.
        return match.value.trimEnd('.', ',', ')', ']', '}', '"', '\'', '>', ';', '!', '?')
    }

    /**
     * Canonicalises [raw] for cache keying and extraction.
     *
     * @return the normalised URL, or null when [raw] is not an http(s) URL we can use.
     */
    fun normalize(raw: String): String? {
        val text = raw.trim()
        val hasExplicitScheme = text.contains("://")
        // Bare hosts are common in pastes ("vimeo.com/123"), so assume https rather than
        // rejecting - but see the dot check below for why that inference is kept on a leash.
        val candidate = if (hasExplicitScheme) text else "https://$text"

        val url = candidate.toHttpUrlOrNull() ?: return null
        if (url.scheme !in ALLOWED_SCHEMES) return null
        if (url.host.isBlank()) return null
        // A single-label host is only legitimate when the user actually typed a scheme:
        // "http://localhost:8080/v.mp4" is a real URL, while the bare word "video" is not, and
        // without this the https guess would happily turn any stray word into a request.
        if (!hasExplicitScheme && !url.host.contains('.')) return null

        val builder = url.newBuilder()
            .fragment(null)
            .host(url.host.lowercase().removePrefix("www."))

        builder.query(null)
        url.queryParameterNames
            .filterNot { it.lowercase() in TRACKING_PARAMS }
            .sorted()
            .forEach { name ->
                url.queryParameterValues(name).forEach { value ->
                    builder.addQueryParameter(name, value)
                }
            }

        return builder.build().toString().trimTrailingSlash()
    }

    /** The cache key for [normalizedUrl]; identical to the URL today, isolated for later. */
    fun cacheKey(normalizedUrl: String): String = normalizedUrl

    fun hostOf(url: String): String? = url.toHttpUrlOrNull()?.host

    /** Last path segment without its extension, useful as a fallback title. */
    fun filenameStem(url: HttpUrl): String? =
        url.pathSegments.lastOrNull { it.isNotBlank() }?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }

    private fun String.trimTrailingSlash(): String =
        // Only strip a slash that carries no meaning, i.e. not the one in "https://host/".
        if (endsWith("/") && count { it == '/' } > 3) dropLast(1) else this

    private val URL_IN_TEXT = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
}
