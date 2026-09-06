package app.bibifoq.core.resolver

import app.bibifoq.core.model.MediaInfo
import app.bibifoq.core.net.HeadDocument
import app.bibifoq.core.net.HeadScanner
import app.bibifoq.core.net.HttpEngine
import app.bibifoq.core.net.UserAgents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import okhttp3.HttpUrl
import app.bibifoq.core.net.runCatchingCancellable

/**
 * A native, pure-Kotlin metadata extractor.
 *
 * These are the fast path. Each one costs at most a request or two and no interpreter start-up,
 * so a URL any of them can handle resolves in a fraction of what the general engine needs.
 * They deliberately target *published, cross-site standards* (oEmbed, schema.org, OpenGraph,
 * HLS/DASH) rather than per-site scraping, so they cover a long tail of hosts at once and do
 * not rot every time one site ships a redesign.
 */
interface NativeExtractor {
    /** Stable name, reported in [MediaInfo.extractor] and in the diagnostics panel. */
    val name: String

    /**
     * Cheap, synchronous, no I/O. Returning true only means "worth trying".
     * Lower [priority] runs first.
     */
    fun canHandle(url: HttpUrl): Boolean

    val priority: Int get() = 100

    /**
     * Attempts extraction. Returns null when this extractor found nothing usable, which is a
     * normal outcome, not an error - the orchestrator simply moves on to the next candidate.
     */
    suspend fun extract(context: ExtractionContext): MediaInfo?
}

/**
 * Shared state for one resolution attempt.
 *
 * The important part is [pageHead]: several extractors want the same document `<head>`, and
 * fetching it once and sharing it turns three sequential requests into one.
 */
class ExtractionContext(
    val url: HttpUrl,
    val engine: HttpEngine,
    private val scope: CoroutineScope,
    val userAgent: String = UserAgents.DESKTOP,
) {
    val normalizedUrl: String get() = url.toString()

    private val headDeferred: Deferred<HeadDocument?> by lazy {
        scope.async { HeadScanner.fetchHead(engine, url.toString(), userAgent) }
    }

    /**
     * The document `<head>`, fetched at most once per resolution and shared by every extractor
     * that asks for it. Null when the URL did not serve HTML.
     */
    suspend fun pageHead(): HeadDocument? = runCatchingCancellable { headDeferred.await() }.getOrNull()

    /**
     * Starts fetching the page head now without waiting for it, so the request is already in
     * flight while cheaper checks run.
     */
    fun prefetchPageHead() {
        headDeferred.start()
    }
}
