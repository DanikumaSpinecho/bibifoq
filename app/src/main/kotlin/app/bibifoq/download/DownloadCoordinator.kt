package app.bibifoq.download

import android.content.Context
import android.os.Environment
import android.util.Log
import app.bibifoq.core.downloader.DownloadConfig
import app.bibifoq.core.downloader.DownloadProgress
import app.bibifoq.core.downloader.DownloadSpec
import app.bibifoq.core.downloader.FileNamer
import app.bibifoq.core.downloader.HlsDownloader
import app.bibifoq.core.downloader.SegmentedDownloader
import app.bibifoq.core.model.FormatSelection
import app.bibifoq.core.model.MediaInfo
import app.bibifoq.core.model.Protocol
import app.bibifoq.core.net.HttpEngine
import app.bibifoq.data.DownloadDao
import app.bibifoq.data.DownloadRecord
import app.bibifoq.data.DownloadState
import app.bibifoq.data.SettingsStore
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns the download queue: what to fetch, with which engine, and what the database should say
 * about it.
 *
 * ## Choosing an engine
 *
 * The native downloader is faster - parallel ranges, real resume - but it can only produce one
 * file from one stream. A selection that pairs separate video and audio tracks has to be muxed,
 * which the extraction engine already does correctly with its own ffmpeg. So the rule is: take
 * the fast path whenever the result is a single stream, and hand the rest to the engine.
 */
class DownloadCoordinator(
    private val context: Context,
    private val httpEngine: HttpEngine,
    private val dao: DownloadDao,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {

    private val jobs = ConcurrentHashMap<String, Job>()
    private val activeCount = MutableStateFlow(0)

    /** How many downloads are running, so the foreground service knows when to stop. */
    val active: Flow<Int> = activeCount.asStateFlow()

    fun observeAll(): Flow<List<DownloadRecord>> = dao.observeAll()

    /**
     * Queues [selection] for download and returns the record id.
     *
     * The record is written before any bytes move, so a download that dies immediately still
     * shows up in the list with its error instead of vanishing.
     */
    suspend fun enqueue(
        info: MediaInfo,
        selection: FormatSelection,
        config: DownloadConfig = DownloadConfig(),
    ): String {
        val id = UUID.randomUUID().toString()
        val preferences = settingsSnapshot()
        val format = selection.video ?: selection.audio!!
        val directory = downloadDirectory()
        val filename = FileNamer.render(info, format, preferences.filenameTemplate)
        val destination = FileNamer.uniqueIn(directory, filename)

        dao.upsert(
            DownloadRecord(
                id = id,
                sourceUrl = info.webpageUrl,
                title = info.title,
                uploader = info.uploader,
                thumbnailUrl = info.thumbnailUrl,
                filePath = destination.absolutePath,
                formatId = format.id,
                formatLabel = format.label(),
                state = DownloadState.QUEUED,
                totalBytes = selection.totalBytes,
                downloadedBytes = 0,
                errorMessage = null,
                createdAtMillis = System.currentTimeMillis(),
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )

        val job = scope.launch {
            activeCount.value += 1
            try {
                run(id, info, selection, destination, config.copy(maxConnections = preferences.maxConnections))
            } finally {
                activeCount.value -= 1
                jobs.remove(id)
            }
        }
        jobs[id] = job
        return id
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        scope.launch {
            dao.updateProgress(
                id = id,
                state = DownloadState.CANCELLED,
                downloaded = dao.byId(id)?.downloadedBytes ?: 0,
                total = dao.byId(id)?.totalBytes,
                error = null,
                now = System.currentTimeMillis(),
            )
        }
    }

    suspend fun remove(id: String) {
        jobs.remove(id)?.cancel()
        dao.byId(id)?.let { record ->
            runCatching {
                File(record.filePath).delete()
                File(record.filePath + ".part").delete()
                File(record.filePath + ".resume").delete()
            }
        }
        dao.delete(id)
    }

    suspend fun clearFinished() = dao.clearFinished()

    private suspend fun run(
        id: String,
        info: MediaInfo,
        selection: FormatSelection,
        destination: File,
        config: DownloadConfig,
    ) {
        val progress = strategyFor(info, selection, destination, config)
        var lastTotal = selection.totalBytes

        progress.collect { update ->
            when (update) {
                is DownloadProgress.Started -> {
                    lastTotal = update.totalBytes ?: lastTotal
                    dao.updateProgress(id, DownloadState.RUNNING, update.resumedBytes, lastTotal, null, now())
                }

                is DownloadProgress.Running -> {
                    lastTotal = update.totalBytes ?: lastTotal
                    dao.updateProgress(
                        id, DownloadState.RUNNING, update.downloadedBytes, lastTotal, null, now(),
                    )
                }

                is DownloadProgress.Completed -> {
                    dao.updateProgress(
                        id, DownloadState.COMPLETED, update.totalBytes, update.totalBytes, null, now(),
                    )
                    val extension = (selection.video ?: selection.audio)?.container
                    MediaStorePublisher(context)
                        .publish(update.file, MediaStorePublisher.mimeTypeFor(extension))
                }

                is DownloadProgress.Failed -> {
                    Log.w(TAG, "download $id failed", update.error)
                    dao.updateProgress(
                        id,
                        DownloadState.FAILED,
                        dao.byId(id)?.downloadedBytes ?: 0,
                        lastTotal,
                        update.error.message ?: update.error::class.simpleName,
                        now(),
                    )
                }
            }
        }
    }

    /**
     * Picks the downloader for a selection.
     *
     * Muxing and protocols we cannot fetch ourselves go to the engine; everything else takes
     * the native path, which is where the parallel-range speedup lives.
     */
    private fun strategyFor(
        info: MediaInfo,
        selection: FormatSelection,
        destination: File,
        config: DownloadConfig,
    ): Flow<DownloadProgress> {
        val format = selection.video ?: selection.audio!!
        val headers = info.httpHeaders + format.httpHeaders

        return when {
            selection.requiresMuxing -> EngineDownloader().download(info, selection, destination)

            format.protocol == Protocol.HLS ->
                HlsDownloader(httpEngine, config).download(
                    DownloadSpec(format.url, destination, headers, format.filesizeBytes),
                )

            format.protocol == Protocol.HTTPS ->
                SegmentedDownloader(httpEngine, config).download(
                    DownloadSpec(format.url, destination, headers, format.filesizeBytes),
                )

            // DASH segment templates and exotic protocols: the engine knows how, we do not.
            else -> EngineDownloader().download(info, selection, destination)
        }
    }

    private suspend fun settingsSnapshot(): SettingsStore.Settings = settings.settings.first()

    private fun downloadDirectory(): File =
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(context.filesDir, "downloads").apply { mkdirs() }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val TAG = "DownloadCoordinator"
    }
}
