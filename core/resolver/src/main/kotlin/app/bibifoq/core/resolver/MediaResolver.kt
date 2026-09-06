package app.bibifoq.core.resolver

import app.bibifoq.core.model.Completeness
import app.bibifoq.core.model.MediaInfo
import app.bibifoq.core.model.Provenance
import app.bibifoq.core.net.HttpEngine
import app.bibifoq.core.net.UserAgents
import app.bibifoq.core.net.runCatchingCancellable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Turns a URL into downloadable media, as fast as the URL allows.
 *
 * ## Why this is shaped the way it is
 *
 * The slow part of a downloader is never the download - it is working out *what* to download.
 * A general extraction engine has to boot an interpreter, import a large module tree, and then
 * do site-specific work, which on a mid-range phone is comfortably over a second before any
 * network request happens. Doing that for every URL, including ones whose metadata is sitting
 * in plain sight, is the whole problem.
 *
 * So resolution runs in tiers:
 *
 *  - **Tier 0, cache.** A URL the user already looked at resolves with no I/O at all.
 *  - **Tier 1, native extractors.** Pure Kotlin readers for published standards - direct media
 *    files, HLS/DASH manifests, oEmbed, schema.org JSON-LD, OpenGraph. One or two small
 *    requests, no interpreter. For a direct file or a manifest the answer is *complete*, and
 *    nothing else needs to run.
 *  - **Tier 2, the general engine.** Everything else, held back by [ResolverConfig.headStart]
 *    so tier 1 gets a chance to make it unnecessary.
 *
 * The tiers are not strictly sequential: tier 2 is started speculatively while tier 1 is still
 * working, so a URL that needs the engine does not pay tier 1's latency on top of tier 2's.
 * When tier 1 answers completely first, the pending tier 2 call is cancelled before it does
 * any real work.
 *
 * Results stream ([ResolveUpdate]) rather than arriving all at once, so a title and thumbnail
 * from tier 1 can be on screen while tier 2 is still deciding what the formats are.
 */
class MediaResolver(
    private val httpEngine: HttpEngine,
    private val extractors: List<NativeExtractor>,
    private val cache: MetadataCache = InMemoryMetadataCache(),
    private val remoteEngine: RemoteEngine? = null,
    private val config: ResolverConfig = ResolverConfig(),
    /** Scope that outlives any single caller, used for prefetching and request coalescing. */
    private val backgroundScope: CoroutineScope,
) {

    private val singleFlight = SingleFlight<String, MediaInfo>(backgroundScope)

    fun resolve(
        rawUrl: String,
        options: RemoteEngineOptions = RemoteEngineOptions(),
        mode: ResolveMode = ResolveMode.FAST,
        /**
         * The height the user actually wants. A cheap answer below this is not good enough to
         * stop on, however fast it was.
         */
        desiredHeight: Int? = null,
    ): Flow<ResolveUpdate> =
        channelFlow {
            val clock = TimeSource.Monotonic.markNow()

            val normalized = UrlNormalizer.extractFirstUrl(rawUrl)?.let(UrlNormalizer::normalize)
                ?: UrlNormalizer.normalize(rawUrl)
            if (normalized == null) {
                send(ResolveUpdate.Failed(ResolveError.InvalidUrl(rawUrl), clock.elapsedNow()))
                return@channelFlow
            }
            send(ResolveUpdate.Started(normalized))

            val httpUrl = normalized.toHttpUrlOrNull()
            if (httpUrl == null) {
                send(ResolveUpdate.Failed(ResolveError.InvalidUrl(rawUrl), clock.elapsedNow()))
                return@channelFlow
            }

            // ---- Tier 0: cache -------------------------------------------------------------
            var best: MediaInfo? = null
            if (config.useCache) {
                cache.get(UrlNormalizer.cacheKey(normalized))?.let { hit ->
                    val cached = hit.info.copy(provenance = Provenance.CACHE)
                    // ALL_FORMATS exists precisely to go past what we already have, so it may
                    // seed from the cache but must never stop there.
                    if (mode == ResolveMode.FAST && cached.completeness == Completeness.COMPLETE) {
                        send(ResolveUpdate.Complete(cached, clock.elapsedNow(), Provenance.CACHE))
                        return@channelFlow
                    }
                    best = cached
                    if (mode == ResolveMode.FAST) {
                        send(ResolveUpdate.Partial(cached, clock.elapsedNow()))
                    }
                }
            }

            val context = ExtractionContext(
                url = httpUrl,
                engine = httpEngine,
                scope = this,
                userAgent = config.userAgent,
            )

            // ---- Tier 2, started lazily so tier 1 can cancel it before it costs anything ----
            val headStart = if (mode == ResolveMode.ALL_FORMATS) {
                // The user asked for the full ladder; holding the engine back only delays it.
                Duration.ZERO
            } else if (extractors.any { it.priority < AUTHORITATIVE_PRIORITY && it.canHandle(httpUrl) }) {
                // Something claims this URL outright and will answer completely. Hold the
                // expensive engine back long enough that it normally never starts.
                config.authoritativeHeadStart
            } else if (remoteEngine?.isWarm == true) {
                // A warm engine is cheap to start, so there is little to gain by waiting.
                config.warmHeadStart
            } else {
                config.headStart
            }

            // The outcome is wrapped rather than thrown on purpose: a failing `async` cancels
            // its parent scope the moment it fails, which would tear this flow down before the
            // native-preview fallback below ever got a chance to run.
            val remoteDeferred: Deferred<Result<MediaInfo>>? = remoteEngine?.let { engine ->
                async {
                    delay(headStart)
                    runCatchingCancellable {
                        singleFlight.run(normalized) { engine.fetchInfo(normalized, options) }
                    }
                }
            }

            // ---- Tier 1: native extractors -------------------------------------------------
            // With a cached preview already in hand there is nothing left for tier 1 to add on
            // an explicit full-ladder request, so skip the request it would make.
            val skipNative = mode == ResolveMode.ALL_FORMATS && best != null
            val native = if (skipNative) {
                null
            } else {
                runCatchingCancellable { runNativeTier(context) }.getOrNull()
            }

            if (native != null) {
                best = best?.mergedWith(native) ?: native
                // A stream we can already fetch is enough to stop on. Enumerating the rest of
                // the ladder costs an interpreter start, and most of the time nobody wanted it -
                // so it is offered rather than spent, via moreFormatsAvailable below.
                if (mode == ResolveMode.FAST && isGoodEnoughToStopOn(best!!, desiredHeight)) {
                    remoteDeferred?.cancel()
                    val result = best!!
                    cache.putIfCaching(normalized, result)
                    send(
                        ResolveUpdate.Complete(
                            info = result,
                            elapsed = clock.elapsedNow(),
                            winner = Provenance.NATIVE,
                            moreFormatsAvailable = remoteEngine != null &&
                                result.completeness != Completeness.COMPLETE,
                        ),
                    )
                    return@channelFlow
                }
                send(ResolveUpdate.Partial(best!!, clock.elapsedNow()))
            }

            // ---- Tier 2 result -------------------------------------------------------------
            if (remoteDeferred == null) {
                // No general engine configured. A preview carrying a usable stream is still a
                // download; a preview with nothing to fetch is not.
                val fallback = best
                if (fallback != null && fallback.isDownloadable) {
                    val promoted = fallback.copy(completeness = Completeness.COMPLETE)
                    cache.putIfCaching(normalized, promoted)
                    send(ResolveUpdate.Complete(promoted, clock.elapsedNow(), Provenance.NATIVE))
                } else {
                    send(
                        ResolveUpdate.Failed(
                            if (fallback == null) ResolveError.Unsupported(normalized)
                            else ResolveError.NoFormats(normalized),
                            clock.elapsedNow(),
                        ),
                    )
                }
                return@channelFlow
            }

            val remote = remoteDeferred.await()
            remote.fold(
                onSuccess = { info ->
                    val merged = best?.mergedWith(info) ?: info
                    val result = merged.copy(completeness = Completeness.COMPLETE)
                    cache.putIfCaching(normalized, result)
                    send(ResolveUpdate.Complete(result, clock.elapsedNow(), Provenance.YTDLP))
                },
                onFailure = { failure ->
                    // The engine failed, but a native preview with a stream still works.
                    val fallback = best
                    if (fallback != null && fallback.isDownloadable) {
                        // Do not cache a fall-back: it would make one engine failure look like
                        // this site's permanent answer for the next half hour.
                        send(
                            ResolveUpdate.Complete(
                                info = fallback,
                                elapsed = clock.elapsedNow(),
                                winner = Provenance.NATIVE,
                                moreFormatsAvailable = true,
                                degradedReason = failure.message
                                    ?: failure::class.simpleName
                                    ?: "the extraction engine failed",
                            ),
                        )
                    } else {
                        send(
                            ResolveUpdate.Failed(
                                failure as? ResolveError
                                    ?: ResolveError.EngineFailure(
                                        failure.message ?: "extraction engine failed",
                                        failure,
                                    ),
                                clock.elapsedNow(),
                            ),
                        )
                    }
                },
            )
        }

    /**
     * Resolves [rawUrl] in the background and warms the cache, discarding the result.
     *
     * Called when a URL merely *appears* - a share intent arriving, a URL showing up on the
     * clipboard - so that by the time the user taps download the answer is already sitting in
     * the cache. This is the difference between a resolve the user waits for and one they
     * never see.
     */
    fun prefetch(rawUrl: String) {
        val normalized = UrlNormalizer.extractFirstUrl(rawUrl)?.let(UrlNormalizer::normalize)
            ?: UrlNormalizer.normalize(rawUrl)
            ?: return
        if (singleFlight.isRunning(normalized)) return

        backgroundScope.launch {
            if (config.useCache && cache.get(UrlNormalizer.cacheKey(normalized)) != null) return@launch
            // Open the connection while we are here; the resolve that follows reuses it.
            httpEngine.prewarm(normalized)
            runCatching {
                collectQuietly(resolve(normalized))
            }
        }
    }

    /**
     * Runs every applicable native extractor and merges what they find.
     *
     * They run concurrently because they mostly wait on I/O, and because they share one fetch
     * of the page `<head>` - so running three of them usually costs one request, not three.
     */
    private suspend fun runNativeTier(context: ExtractionContext): MediaInfo? = coroutineScope {
        val applicable = extractors
            .filter { it.canHandle(context.url) }
            .sortedBy { it.priority }
        if (applicable.isEmpty()) return@coroutineScope null

        // An extractor that claims the URL outright answers completely, so give it a chance to
        // finish before spending requests on the broad-coverage ones.
        val authoritative = applicable.filter { it.priority < AUTHORITATIVE_PRIORITY }
        authoritative.forEach { extractor ->
            val result = runCatchingCancellable { extractor.extract(context) }.getOrNull()
            if (result != null && result.completeness == Completeness.COMPLETE) {
                return@coroutineScope result
            }
        }

        val rest = applicable - authoritative.toSet()
        if (rest.isEmpty()) return@coroutineScope null

        // Get the shared document head moving before anyone awaits it.
        context.prefetchPageHead()

        val found = rest
            .map { extractor ->
                async { extractor to runCatchingCancellable { extractor.extract(context) }.getOrNull() }
            }
            .awaitAll()
            .mapNotNull { (extractor, info) -> info?.let { extractor.priority to it } }

        if (found.isEmpty()) return@coroutineScope null

        // Fold least-trusted first so the most-trusted extractor's values land on top.
        found.sortedByDescending { it.first }
            .map { it.second }
            .reduce { acc, info -> acc.mergedWith(info) }
    }

    /**
     * Whether a cheap answer is worth stopping on.
     *
     * Being downloadable is not the same as being what the user wanted. Pages routinely
     * advertise a low-resolution fallback in `og:video` - it is there for social previews, not
     * for viewing - so stopping at the first playable stream quietly hands back 360p to someone
     * who was watching 720p. Speed is only worth having when the answer is also right.
     *
     * A format list marked [Completeness.COMPLETE] is a real quality ladder and is always
     * enough. Otherwise the best height on offer has to reach the target, and an unknown height
     * counts as not reaching it: the engine is cheap next to silently downloading the wrong thing.
     */
    private fun isGoodEnoughToStopOn(info: MediaInfo, desiredHeight: Int?): Boolean {
        if (!info.isDownloadable) return false
        if (info.completeness == Completeness.COMPLETE) return true
        val target = desiredHeight ?: config.satisfyingHeight
        val best = info.formats.mapNotNull { it.height }.maxOrNull() ?: return false
        return best >= target
    }

    private suspend fun MetadataCache.putIfCaching(key: String, info: MediaInfo) {
        if (config.useCache) put(UrlNormalizer.cacheKey(key), info)
    }

    private suspend fun collectQuietly(flow: Flow<ResolveUpdate>) {
        flow.collect { /* the cache write inside resolve() is the point; updates are ignored */ }
    }

    private companion object {
        /**
         * Extractors below this priority are treated as authoritative for URLs they claim:
         * they either produce a complete answer or nothing at all.
         */
        const val AUTHORITATIVE_PRIORITY = 10
    }
}

/**
 * How hard a resolution should work.
 *
 * The split exists because enumerating every resolution a site offers is the expensive part,
 * and it is wasted whenever the stream found cheaply was already the one wanted.
 */
enum class ResolveMode {
    /**
     * Stop as soon as there is something downloadable. Reports
     * [ResolveUpdate.Complete.moreFormatsAvailable] when a fuller list could still be fetched.
     */
    FAST,

    /** Run the general engine and return the authoritative format list, whatever it costs. */
    ALL_FORMATS,
}

/** Latency-shaping knobs for [MediaResolver]. */
data class ResolverConfig(
    /**
     * How long the general engine waits before starting, giving native extractors a chance to
     * make it unnecessary. Long enough to cover a fast native hit, short enough that a URL
     * needing the engine barely notices.
     */
    val headStart: Duration = 220.milliseconds,

    /** Head start when the engine is already warm and starting it is nearly free. */
    val warmHeadStart: Duration = 80.milliseconds,

    /**
     * Head start when a native extractor has claimed the URL outright. Direct files and
     * manifests are answered natively in well under this, so the engine normally never runs.
     */
    val authoritativeHeadStart: Duration = 4.seconds,

    val useCache: Boolean = true,

    /**
     * Height a cheap answer must reach before the resolver stops there, when the user has
     * expressed no preference of their own. Set at the resolution most people are actually
     * watching, so a fallback stream never passes for the real thing.
     */
    val satisfyingHeight: Int = 720,

    val userAgent: String = UserAgents.DESKTOP,
)
