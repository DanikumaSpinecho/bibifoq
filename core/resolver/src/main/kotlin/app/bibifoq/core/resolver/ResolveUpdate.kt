package app.bibifoq.core.resolver

import app.bibifoq.core.model.MediaInfo
import app.bibifoq.core.model.Provenance
import kotlin.time.Duration

/**
 * One step of a resolution.
 *
 * Resolution is a stream rather than a single value on purpose: a cheap extractor can put a
 * title and a thumbnail on screen in a few hundred milliseconds while the authoritative format
 * list is still being worked out. The UI renders [Partial] immediately and swaps in [Complete]
 * when it lands, so the app feels responsive even when total work is unchanged.
 */
sealed interface ResolveUpdate {

    /** Emitted immediately so the UI can show a spinner attached to a known URL. */
    data class Started(val normalizedUrl: String) : ResolveUpdate

    /**
     * Enough metadata to render, not enough to download.
     * More updates are still coming.
     */
    data class Partial(
        val info: MediaInfo,
        val elapsed: Duration,
    ) : ResolveUpdate

    /** The result this resolution is stopping at. Terminal. */
    data class Complete(
        val info: MediaInfo,
        val elapsed: Duration,
        /** Which tier produced the final answer, for the diagnostics panel. */
        val winner: Provenance,
        /**
         * True when a cheap tier answered and the general engine was never asked, so a fuller
         * format list is still obtainable - at the cost of actually running it.
         *
         * This is what lets the UI offer "look for other resolutions" instead of spending that
         * time on every single link, when most of the time the stream already found is the one
         * the user wanted.
         */
        val moreFormatsAvailable: Boolean = false,
        /**
         * Set when this result is a fall-back rather than the real answer - the general engine
         * was asked and failed, so what is offered is only what the page itself advertised.
         *
         * Without this the user sees a single low-resolution stream and reasonably concludes
         * the site has nothing better, when in fact nothing ever looked.
         */
        val degradedReason: String? = null,
    ) : ResolveUpdate

    /** Terminal failure. Any [Partial] already emitted is still valid for display. */
    data class Failed(
        val error: ResolveError,
        val elapsed: Duration,
    ) : ResolveUpdate
}

/** Why a resolution could not finish. */
sealed class ResolveError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class InvalidUrl(val raw: String) : ResolveError("Not a usable http(s) URL: $raw")

    class Unsupported(val url: String) :
        ResolveError("No extractor could handle $url")

    class Network(cause: Throwable) : ResolveError("Network failure: ${cause.message}", cause)

    class NoFormats(val url: String) :
        ResolveError("Metadata was found for $url but it exposes no downloadable stream")

    class EngineFailure(message: String, cause: Throwable? = null) : ResolveError(message, cause)
}
