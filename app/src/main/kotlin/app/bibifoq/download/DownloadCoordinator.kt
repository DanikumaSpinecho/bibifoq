package app.bibifoq.download

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.core.net.toUri
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
    private val engineDownloader: EngineDownloader,
) {

    private val jobs = ConcurrentHashMap<String, Job>()
    private val activeCount = MutableStateFlow(0)

    /**
     * Caps how many downloads actually move bytes at once.
     *
     * Each download already opens several connections of its own, so letting an unbounded
     * number run turns a queue into a self-inflicted denial of service - on the phone's radio
     * and on the host.
     */
    @Volatile
    private var slots = Semaphore(SettingsStore.DEFAULT_CONCURRENT)

    @Volatile
    private var slotCount = SettingsStore.DEFAULT_CONCURRENT

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

        // Resize the gate when the preference changed; queued work waits on the new one.
        if (preferences.concurrentDownloads != slotCount) {
            slotCount = preferences.concurrentDownloads
            slots = Semaphore(preferences.concurrentDownloads)
        }

        val job = scope.launch {
            activeCount.value += 1
            try {
                slots.withPermit {
                    run(
                        id = id,
                        info = info,
                        selection = selection,
                        destination = destination,
                        config = config.copy(maxConnections = preferences.maxConnections),
                        preferences = preferences,
                    )
                }
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

    /**
     * Removes a download.
     *
     * @param deleteFile also erase the media itself, including the copy published to shared
     *   storage. That published copy is the one the user sees in their gallery, so deleting
     *   only the app-private file would look like the delete did nothing.
     */
    suspend fun remove(id: String, deleteFile: Boolean = true) {
        jobs.remove(id)?.cancel()
        val record = dao.byId(id)
        if (deleteFile && record != null) {
            runCatching {
                File(record.filePath).delete()
                // Partial state is never worth keeping once the entry is gone.
                File(record.filePath + ".part").delete()
                File(record.filePath + ".resume").delete()
            }
            record.mediaStoreUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri.toUri(), null, null) }
                    .onFailure { Log.w(TAG, "could not delete the published copy", it) }
            }
        }
        dao.delete(id)
    }

    /** True when the finished file is still where we left it. */
    suspend fun fileExists(id: String): Boolean =
        dao.byId(id)?.let { File(it.filePath).exists() } ?: false

    suspend fun clearFinished() = dao.clearFinished()

    private suspend fun run(
        id: String,
        info: MediaInfo,
        selection: FormatSelection,
        destination: File,
        config: DownloadConfig,
        preferences: SettingsStore.Settings,
    ) {
        val progress = strategyFor(info, selection, destination, config, preferences)
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
                    val published = MediaStorePublisher(context)
                        .publish(update.file, MediaStorePublisher.mimeTypeFor(extension))
                    // Remember where it landed; without this the visible copy cannot be removed.
                    dao.setMediaStoreUri(id, published?.toString())
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
        preferences: SettingsStore.Settings,
    ): Flow<DownloadProgress> {
        val format = selection.video ?: selection.audio!!
        val headers = info.httpHeaders + format.httpHeaders
        val extraArguments = preferences.extraEngineArguments

        // Audio with artwork has to go through the engine: writing a cover image into a
        // container is ffmpeg's job, and the engine already owns ffmpeg. The native path is
        // faster but would hand back an untagged file, which every music player shows as blank.
        val audioWithArtwork = selection.video == null &&
            preferences.audioOnly &&
            preferences.embedThumbnail &&
            info.thumbnailUrl != null

        return when {
            audioWithArtwork -> engineDownloader.download(
                info = info,
                selection = selection,
                destination = destination,
                audioFormat = preferences.audioContainer,
                embedThumbnail = true,
                extraArguments = extraArguments,
            )

            selection.requiresMuxing -> engineDownloader.download(
                info = info,
                selection = selection,
                destination = destination,
                extraArguments = extraArguments,
            )

            format.protocol == Protocol.HLS ->
                HlsDownloader(httpEngine, config).download(
                    DownloadSpec(format.url, destination, headers, format.filesizeBytes),
                )

            format.protocol == Protocol.HTTPS ->
                SegmentedDownloader(httpEngine, config).download(
                    DownloadSpec(format.url, destination, headers, format.filesizeBytes),
                )

            // DASH segment templates and exotic protocols: the engine knows how, we do not.
            else -> engineDownloader.download(
                info = info,
                selection = selection,
                destination = destination,
                extraArguments = extraArguments,
            )
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
