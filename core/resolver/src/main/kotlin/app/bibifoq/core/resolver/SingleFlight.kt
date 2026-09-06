package app.bibifoq.core.resolver

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Collapses concurrent calls for the same key into one execution.
 *
 * Without this, a user who pastes a URL while the clipboard prefetch for that same URL is
 * still running pays for two extractions - and the expensive tier is expensive precisely
 * because it starts an interpreter. Callers that arrive while a call is in flight simply await
 * the one already running.
 */
class SingleFlight<K : Any, V>(private val scope: CoroutineScope) {

    private val inFlight = ConcurrentHashMap<K, CompletableDeferred<Result<V>>>()

    /**
     * Runs [block] for [key], or joins the in-flight call if there already is one.
     *
     * The work runs in [scope], not in the caller, so a caller that gives up does not cancel
     * the shared computation out from under everyone else waiting on it.
     */
    suspend fun run(key: K, block: suspend () -> V): V {
        val fresh = CompletableDeferred<Result<V>>()
        val existing = inFlight.putIfAbsent(key, fresh)
        if (existing != null) return existing.await().getOrThrow()

        scope.launch {
            val outcome = runCatching { block() }
            inFlight.remove(key, fresh)
            fresh.complete(outcome)
        }
        return fresh.await().getOrThrow()
    }

    /** True while a call for [key] is running; used to avoid queueing redundant prefetches. */
    fun isRunning(key: K): Boolean = inFlight.containsKey(key)

    val activeCount: Int get() = inFlight.size
}
