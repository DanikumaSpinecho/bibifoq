package app.bibifoq.core.net

import java.io.InputStream
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
 *
 * ## Bytes first, characters second
 *
 * The scan works on raw bytes and decodes once at the end, rather than decoding as it reads.
 * That ordering matters: a page's encoding is frequently declared *only* in a `<meta charset>`
 * inside the very region being read, so a reader that has already committed to a charset
 * cannot honour it - and everything accented comes out as mojibake. Reading bytes also removes
 * the risk of splitting a multi-byte character across two buffers.
 */
object HeadScanner {

    /** Hard ceiling, for pages that never close their head or bury it behind inline scripts. */
    const val DEFAULT_MAX_BYTES: Int = 512 * 1024

    private const val CHUNK = 16 * 1024

    /** How far in to look for a `<meta charset>`; the specification says the first 1024 bytes. */
    private const val CHARSET_SNIFF_LIMIT = 8 * 1024

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
        maxBytes: Int = DEFAULT_MAX_BYTES,
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
            val html = readUntilHeadClose(resp, maxBytes) ?: return@use null
            HeadDocument(html = html, finalUrl = resp.request.url.toString())
        }
    }

    /**
     * Drains [response]'s body only as far as `</head>`, decoding with the charset the page
     * actually uses. Cancelling the calling coroutine aborts mid-stream.
     */
    suspend fun readUntilHeadClose(response: Response, maxBytes: Int = DEFAULT_MAX_BYTES): String? =
        withContext(Dispatchers.IO) {
            val body = response.body ?: return@withContext null
            val declared = body.contentType()?.charset()
            val bytes = readBytesUntilHeadClose(body.byteStream(), maxBytes)
            if (bytes.isEmpty()) return@withContext null

            // Precedence follows the HTML spec: a byte order mark wins, then what the server
            // said, then what the document says about itself, then the modern default.
            val charset = detectBom(bytes)
                ?: declared
                ?: sniffMetaCharset(bytes)
                ?: StandardCharsets.UTF_8

            val start = bomLength(bytes)
            String(bytes, start, bytes.size - start, charset).takeIf { it.isNotBlank() }
        }

    /**
     * Reads until the ASCII sequence `</head` appears, or the cap is reached.
     *
     * Searching bytes is safe here because every encoding we can meet in practice is
     * ASCII-transparent for these characters; a UTF-16 page is caught by its byte order mark
     * or by the server's own declaration.
     */
    private suspend fun readBytesUntilHeadClose(stream: InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(CHUNK)
        val buffer = ByteArray(CHUNK)
        var searchFrom = 0

        stream.use { input ->
            while (out.size() < maxBytes) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)

                val snapshot = out.toByteArray()
                val hit = indexOf(snapshot, HEAD_CLOSE, searchFrom)
                if (hit >= 0) return snapshot.copyOf(hit + HEAD_CLOSE.size)
                // Only the tail can hold a split marker, so rescan a short overlap rather than
                // the whole buffer each time.
                searchFrom = (snapshot.size - HEAD_CLOSE.size).coerceAtLeast(0)
            }
        }
        return out.toByteArray()
    }

    /** Looks for a `<meta charset>` or a `content="…; charset=…"` in the head we just read. */
    internal fun sniffMetaCharset(bytes: ByteArray): Charset? {
        val window = String(
            bytes,
            0,
            minOf(bytes.size, CHARSET_SNIFF_LIMIT),
            // Latin-1 maps every byte to a character, so the declaration survives whatever the
            // real encoding turns out to be.
            StandardCharsets.ISO_8859_1,
        )
        val name = CHARSET_DECLARATION.find(window)?.groupValues?.get(1)?.trim()?.trim('"', '\'')
            ?: return null
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    internal fun detectBom(bytes: ByteArray): Charset? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte() -> StandardCharsets.UTF_8
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            StandardCharsets.UTF_16BE
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            StandardCharsets.UTF_16LE
        else -> null
    }

    private fun bomLength(bytes: ByteArray): Int = when (detectBom(bytes)) {
        StandardCharsets.UTF_8 -> 3
        StandardCharsets.UTF_16BE, StandardCharsets.UTF_16LE -> 2
        else -> 0
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (start in from..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return start
        }
        return -1
    }

    private val HEAD_CLOSE = "</head".toByteArray(StandardCharsets.US_ASCII)

    private val CHARSET_DECLARATION = Regex(
        """charset\s*=\s*["']?\s*([A-Za-z0-9_:.\-]+)""",
        RegexOption.IGNORE_CASE,
    )
}

/** An HTML `<head>` plus the URL it was actually served from, after redirects. */
data class HeadDocument(
    val html: String,
    val finalUrl: String,
)
