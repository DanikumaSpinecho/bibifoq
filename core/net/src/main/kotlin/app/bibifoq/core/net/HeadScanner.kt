package app.bibifoq.core.net

import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.coroutineContext

/**
 * Reads just enough of an HTML document to find its metadata, then hangs up.
 *
 * Every scrap of metadata a page publishes for machines - OpenGraph, Twitter cards, JSON-LD,
 * oEmbed discovery links - lives in `<head>`. Video pages are routinely 1-3 MB of markup and
 * inline JSON, so reading the whole body to find a `<meta>` tag in the first 8 KB wastes most
 * of the request. Stopping at `</head>` typically cuts the bytes read by 50-100x, and on a
 * mobile connection that is the difference between a resolve that feels instant and one that
 * does not.
 */
object HeadScanner {

    /** Hard ceiling, for pages that never close their head or bury it behind inline scripts. */
    const val DEFAULT_MAX_CHARS: Int = 512 * 1024

    private const val CHUNK = 8 * 1024

    /**
     * Fetches [url] and returns the document up to and including `</head>`.
     *
     * @return the partial HTML, or null when the response was not HTML or the request failed.
     */
    suspend fun fetchHead(
        engine: HttpEngine,
        url: String,
        userAgent: String = UserAgents.DESKTOP,
        extraHeaders: Map<String, String> = emptyMap(),
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): HeadDocument? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
            .build()

        val response = runCatchingCancellable { engine.execute(request) }.getOrNull() ?: return null
        return response.use { resp ->
            if (!resp.isSuccessful) return@use null
            val contentType = resp.header("Content-Type").orEmpty()
            if (!contentType.contains("html", ignoreCase = true) && contentType.isNotEmpty()) {
                return@use null
            }
            val html = readUntilHeadClose(resp, maxChars) ?: return@use null
            HeadDocument(html = html, finalUrl = resp.request.url.toString())
        }
    }

    /**
     * Drains [response]'s body only as far as `</head>`, honouring the charset the server
     * declared. Cancelling the calling coroutine aborts mid-stream.
     */
    suspend fun readUntilHeadClose(response: Response, maxChars: Int = DEFAULT_MAX_CHARS): String? =
        withContext(Dispatchers.IO) {
            val body = response.body ?: return@withContext null
            val charset: Charset = body.contentType()?.charset() ?: StandardCharsets.UTF_8
            val builder = StringBuilder(CHUNK)
            val buffer = CharArray(CHUNK)

            InputStreamReader(body.byteStream(), charset).use { reader ->
                // Only the tail of what we already have can contain a split "</head", so we
                // rescan a short overlap rather than the whole builder on each chunk.
                var scanFrom = 0
                while (true) {
                    coroutineContext.ensureActive()
                    val read = reader.read(buffer)
                    if (read < 0) break
                    builder.appendRange(buffer, 0, read)

                    val hit = builder.indexOf(HEAD_CLOSE, startIndex = scanFrom, ignoreCase = true)
                    if (hit >= 0) {
                        return@withContext builder.substring(0, hit + HEAD_CLOSE.length)
                    }
                    scanFrom = (builder.length - HEAD_CLOSE.length).coerceAtLeast(0)
                    if (builder.length >= maxChars) break
                }
            }
            builder.toString().takeIf { it.isNotBlank() }
        }

    private const val HEAD_CLOSE = "</head"
}

/** An HTML `<head>` plus the URL it was actually served from, after redirects. */
data class HeadDocument(
    val html: String,
    val finalUrl: String,
)
