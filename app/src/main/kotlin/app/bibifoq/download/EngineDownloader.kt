package app.bibifoq.download

import app.bibifoq.core.downloader.DownloadProgress
import app.bibifoq.core.model.FormatSelection
import app.bibifoq.core.model.MediaInfo
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.coroutineContext

/**
 * Downloads through the extraction engine instead of the native downloader.
 *
 * Used only where the native path genuinely cannot go: a selection that pairs a video-only and
 * an audio-only stream has to be merged with ffmpeg afterwards, and the engine already owns
 * that binary and the muxing logic. Reimplementing it here would mean shipping a second copy
 * of ffmpeg and a second set of container edge cases for no user-visible gain.
 *
 * Everything that is a single stream goes down the faster native path instead - see
 * [DownloadCoordinator].
 */
class EngineDownloader {

    fun download(
        info: MediaInfo,
        selection: FormatSelection,
        destination: File,
    ): Flow<DownloadProgress> = callbackFlow {
        val clock = TimeSource.Monotonic.markNow()
        val processId = UUID.randomUUID().toString()
        val total = selection.totalBytes

        destination.parentFile?.mkdirs()

        val formatSpec = listOfNotNull(selection.video?.id, selection.audio?.id)
            .joinToString("+")

        val request = YoutubeDLRequest(info.webpageUrl).apply {
            addOption("--no-warnings")
            addOption("--ignore-config")
            addOption("--no-playlist")
            addOption("-f", formatSpec)
            addOption("-o", destination.absolutePath)
            // Ask for a single container rather than whatever the streams happened to be in.
            addOption("--merge-output-format", destination.extension.ifBlank { "mp4" })
            addOption("--newline")
        }

        trySend(
            DownloadProgress.Started(totalBytes = total, connections = 1, resumedBytes = 0),
        )

        val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            }
        }

        try {
            val response = YoutubeDL.getInstance().execute(request, processId) { percent, _, _ ->
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
            }

            if (response.exitCode != 0) {
                trySend(
                    DownloadProgress.Failed(
                        IllegalStateException(
                            response.err.trim().lines().lastOrNull { it.isNotBlank() }
                                ?: "engine exited with ${response.exitCode}",
                        ),
                    ),
                )
            } else {
                trySend(
                    DownloadProgress.Completed(
                        file = destination,
                        totalBytes = destination.length(),
                        elapsed = clock.elapsedNow(),
                    ),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            trySend(DownloadProgress.Failed(error))
        } finally {
            cancellationHandle?.dispose()
        }

        close()
        awaitClose { runCatching { YoutubeDL.getInstance().destroyProcessById(processId) } }
    }.flowOn(Dispatchers.IO)
}
