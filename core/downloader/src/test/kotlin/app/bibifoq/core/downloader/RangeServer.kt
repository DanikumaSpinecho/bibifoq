package app.bibifoq.core.downloader

import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/**
 * A mock origin that actually implements HTTP range semantics.
 *
 * Asserting that the downloader splits a file correctly needs a server that answers ranges the
 * way a CDN does, including the 206 status and the `Content-Range` header the resume logic
 * reads back.
 */
class RangeServer(
    private val content: ByteArray,
    private val supportRanges: Boolean = true,
    /** Cuts the first [failFirstRequests] range responses short, to exercise retries. */
    private val failFirstRequests: Int = 0,
) : Dispatcher() {

    private val failures = AtomicInteger(0)
    private val served = AtomicInteger(0)

    /** Every range request the server received, in arrival order. */
    val rangeHeaders: MutableList<String?> = java.util.Collections.synchronizedList(mutableListOf())

    val requestCount: Int get() = served.get()

    override fun dispatch(request: RecordedRequest): MockResponse {
        served.incrementAndGet()
        val range = request.getHeader("Range")
        rangeHeaders += range

        if (!supportRanges) {
            return MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", content.size.toString())
                .setBody(Buffer().write(content))
        }

        if (range == null) {
            return MockResponse()
                .setResponseCode(200)
                .setHeader("Accept-Ranges", "bytes")
                .setHeader("Content-Length", content.size.toString())
                .setBody(Buffer().write(content))
        }

        val spec = range.removePrefix("bytes=")
        val start = spec.substringBefore('-').toIntOrNull() ?: 0
        val end = spec.substringAfter('-').toIntOrNull()?.coerceAtMost(content.size - 1)
            ?: (content.size - 1)
        if (start > end || start >= content.size) {
            return MockResponse().setResponseCode(416)
        }

        val slice = content.copyOfRange(start, end + 1)
        val response = MockResponse()
            .setResponseCode(206)
            .setHeader("Accept-Ranges", "bytes")
            .setHeader("Content-Range", "bytes $start-$end/${content.size}")
            .setBody(Buffer().write(slice))

        // A truncated body with a full Content-Length is what a dropped connection looks like.
        if (start != end && failures.get() < failFirstRequests) {
            failures.incrementAndGet()
            return response
                .setBody(Buffer().write(slice.copyOfRange(0, slice.size / 2)))
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END)
        }
        return response
    }
}
