package app.bibifoq.download

import app.bibifoq.core.downloader.DownloadProgress
import app.bibifoq.core.model.FormatSelection
import app.bibifoq.core.model.MediaInfo
import app.bibifoq.core.net.FileCookieStore
import app.bibifoq.engine.YtDlpEngine
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import java.io.File
import java.util.UUID
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.coroutineContext

/**
 * Downloads through the extraction engine instead of the native downloader.
 *
 * Used only where the native path genuinely cannot go: a selection pairing a video-only and an
 * audio-only stream has to be merged with ffmpeg afterwards, and the engine already owns that
 * binary and the muxing logic.
 *
 * ## Not extracting the page twice
 *
 * The obvious implementation - hand the engine the page URL and let it work everything out -
 * makes it repeat the entire extraction that tier 2 just finished. That is slow, which is the
 * one thing this project exists to avoid, and it doubles how often a site sees us, so rate
 * limits and bot checks get hit at download time on pages that resolved fine moments earlier.
 *
 * So the resolve keeps its raw JSON and this replays it with `--load-info-json`, skipping
 * extraction entirely. Those stream URLs are usually signed and do expire, so a failure falls
 * back to a full extraction once - never worse than doing it that way to begin with.
 */
class EngineDownloader(
    private val engine: YtDlpEngine,
    private val cookies: FileCookieStore? = null,
) {

    fun download(
        info: MediaInfo,
        selection: FormatSelection,
        destination: File,
    ): Flow<DownloadProgress> = callbackFlow {
        val clock = TimeSource.Monotonic.markNow()
        val total = selection.totalBytes

        // The launch-time warm-up is best effort and may have failed; a download must not
        // discover that by way of an opaque native error.
        try {
            engine.ensureReady()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            trySend(DownloadProgress.Failed(error))
            close()
            return@callbackFlow
        }

        destination.parentFile?.mkdirs()
        trySend(DownloadProgress.Started(totalBytes = total, connections = 1, resumedBytes = 0))

        val formatSpec = listOfNotNull(selection.video?.id, selection.audio?.id).joinToString("+")
        val container = destination.extension.ifBlank { "mp4" }
        val savedInfo = engine.infoJsonFor(info.sourceUrl) ?: engine.infoJsonFor(info.webpageUrl)

        try {
            var response: YoutubeDLResponse? = null
            var failure: Throwable? = null

            if (savedInfo != null) {
                val attempt = runAttempt(
                    request = buildRequest(formatSpec, destination, container, savedInfo, null),
                    total = total,
                )
                attempt.fold(onSuccess = { response = it }, onFailure = { failure = it })
                if (response?.exitCode != 0 && failure == null) {
                    failure = IllegalStateException(response.errorLine())
                    response = null
                }
            }

            if (response == null) {
                // Either there was no saved extraction to replay, or its URLs had expired.
                val attempt = runAttempt(
                    request = buildRequest(formatSpec, destination, container, null, info.webpageUrl),
                    total = total,
                )
                attempt.fold(
                    onSuccess = { response = it },
                    onFailure = { failure = it },
                )
            }

            val finished = response
            when {
                finished != null && finished.exitCode == 0 -> trySend(
                    DownloadProgress.Completed(
                        file = destination,
                        totalBytes = destination.length(),
                        elapsed = clock.elapsedNow(),
                    ),
                )

                finished != null -> trySend(
                    DownloadProgress.Failed(IllegalStateException(finished.errorLine())),
                )

                else -> trySend(
                    DownloadProgress.Failed(
                        failure ?: IllegalStateException("the engine produced no result"),
                    ),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            trySend(DownloadProgress.Failed(error))
        }

        close()
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    private suspend fun ProducerScope<DownloadProgress>.runAttempt(
        request: YoutubeDLRequest,
        total: Long?,
    ): Result<YoutubeDLResponse> {
        val processId = UUID.randomUUID().toString()
        // The engine call blocks on a child process, so cancelling has to kill that process or
        // it keeps running and holding the CPU.
        val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            }
        }
        return try {
            Result.success(
                YoutubeDL.getInstance().execute(request, processId) { percent, _, _ ->
                    val fraction = (percent / 100f).coerceIn(0f, 1f)
                    trySend(
                        DownloadProgress.Running(
                            // The engine reports a percentage, not bytes, so the byte count is
                            // derived. It is what the UI shows either way.
                            downloadedBytes = total?.let { (it * fraction).toLong() } ?: 0L,
                            totalBytes = total,
                            bytesPerSecond = 0,
                            connections = 1,
                        ),
                    )
                },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Result.failure(error)
        } finally {
            cancellationHandle?.dispose()
        }
    }

    private fun buildRequest(
        formatSpec: String,
        destination: File,
        container: String,
        savedInfo: File?,
        webpageUrl: String?,
    ): YoutubeDLRequest =
        EngineCommand.build(
            formatSpec = formatSpec,
            destination = destination,
            container = container,
            savedInfo = savedInfo,
            webpageUrl = webpageUrl,
            cookiesFile = cookies?.fileOrNull(),
        )

    private fun YoutubeDLResponse?.errorLine(): String {
        val stderr = this?.err?.trim().orEmpty()
        return stderr.lines().lastOrNull { it.isNotBlank() }
            ?: "engine exited with ${this?.exitCode}"
    }
}
