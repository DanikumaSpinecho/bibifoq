package app.bibifoq.core.net

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] for suspending code.
 *
 * Plain `runCatching` catches [CancellationException] too, which quietly breaks structured
 * concurrency: a cancelled coroutine reports a failed [Result] and keeps going instead of
 * unwinding. Every "try this, fall through on failure" path in the resolver needs the failure
 * handling *and* correct cancellation, so it uses this instead.
 */
suspend inline fun <T> runCatchingCancellable(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }
