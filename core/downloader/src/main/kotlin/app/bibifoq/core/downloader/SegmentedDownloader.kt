package app.bibifoq.core.downloader

import app.bibifoq.core.net.HttpEngine
import app.bibifoq.core.net.UserAgents
import app.bibifoq.core.net.closeQuietly
import app.bibifoq.core.net.runCatchingCancellable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request

/**
 * Fetches one file over several HTTP connections at once, and can pick up where it left off.
 *
 * Two reasons this is not just "open a stream and copy it":
 *
 *  - **Throughput.** CDNs commonly shape a single connection well below what the link can do.
 *    Requesting disjoint byte ranges in parallel routinely multiplies real download speed, and
 *    costs nothing when it does not help.
 *  - **Resumability.** A phone loses its network constantly. Recording per-range progress means
 *    a resumed download re-requests only the bytes that never arrived, instead of the file.
 *
 * Ranges are written straight to their final offsets via positional [FileChannel] writes, which
 * are safe to issue concurrently, so there is no reassembly step and no temporary per-chunk file.
 */
class SegmentedDownloader(
    private val engine: HttpEngine,
    private val config: DownloadConfig = DownloadConfig(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    fun download(spec: DownloadSpec): Flow<DownloadProgress> = channelFlow {
        val clock = TimeSource.Monotonic.markNow()
        val partFile = File(spec.destination.path + PART_SUFFIX)
        val stateFile = File(spec.destination.path + RESUME_SUFFIX)
        spec.destination.parentFile?.mkdirs()

        val remote = probe(spec)
        val resumed = loadResumeState(stateFile, spec.url, remote.totalBytes)
        val chunks = planChunks(remote, resumed, partFile)

        val downloaded = AtomicLong(chunks.sumOf { it.completed.get() })
        send(
            DownloadProgress.Started(
                totalBytes = remote.totalBytes,
                connections = chunks.size,
                resumedBytes = downloaded.get(),
            ),
        )

        val outcome = runCatchingCancellable {
            withContext(Dispatchers.IO) {
                RandomAccessFile(partFile, "rw").use { raf ->
                    // Allocating the full length up front means every chunk can write at its
                    // final offset immediately, and the file system gets one sizing hint
                    // instead of thousands of extending writes.
                    remote.totalBytes?.let { if (raf.length() != it) raf.setLength(it) }
                    val channel = raf.channel

                    coroutineScope {
                        val reporter = launch { reportProgress(downloaded, remote, chunks.size) }
                        val persister = launch { persistPeriodically(stateFile, spec, remote, chunks) }

                        chunks.map { chunk ->
                            async { fetchChunk(spec, chunk, channel, downloaded, remote.supportsRanges) }
                        }.awaitAll()

                        reporter.cancel()
                        persister.cancel()
                    }
                    channel.force(false)
                }
            }
        }

        outcome.fold(
            onSuccess = {
                if (spec.destination.exists()) spec.destination.delete()
                val renamed = partFile.renameTo(spec.destination)
                if (!renamed) {
                    // Renaming fails across mount points; fall back to a copy.
                    partFile.copyTo(spec.destination, overwrite = true)
                    partFile.delete()
                }
                stateFile.delete()
                send(
                    DownloadProgress.Completed(
                        file = spec.destination,
                        totalBytes = spec.destination.length(),
                        elapsed = clock.elapsedNow(),
                    ),
                )
            },
            onFailure = { failure ->
                // Keep the part file and the ledger: the next attempt resumes from here.
                writeResumeState(stateFile, spec, remote, chunks)
                send(DownloadProgress.Failed(failure))
            },
        )
    }

    /** Asks the server for the size and whether it will serve ranges. */
    private suspend fun probe(spec: DownloadSpec): RemoteFile {
        // A ranged GET answers both questions at once, and unlike HEAD it is rarely refused.
        val request = Request.Builder()
            .url(spec.url)
            .headers(spec)
            .header("Range", "bytes=0-0")
            .build()

        val response = runCatchingCancellable { engine.execute(request) }.getOrNull()
            ?: return RemoteFile(spec.expectedBytes, supportsRanges = false)

        return response.use { resp ->
            when {
                resp.code == 206 -> RemoteFile(
                    totalBytes = resp.header("Content-Range")?.substringAfter('/')?.trim()
                        ?.toLongOrNull() ?: spec.expectedBytes,
                    supportsRanges = true,
                )
                resp.isSuccessful -> RemoteFile(
                    totalBytes = resp.header("Content-Length")?.toLongOrNull() ?: spec.expectedBytes,
                    supportsRanges = false,
                )
                else -> RemoteFile(spec.expectedBytes, supportsRanges = false)
            }
        }
    }

    /**
     * Decides how many ranges to split the file into, honouring anything already on disk.
     *
     * A file the server will not range, or one too small for the extra requests to pay for
     * themselves, becomes a single sequential chunk.
     */
    internal fun planChunks(remote: RemoteFile, resumed: ResumeState?, partFile: File): List<Chunk> {
        val total = remote.totalBytes
        if (!remote.supportsRanges || total == null || total <= 0) {
            return listOf(Chunk(start = 0, endInclusive = -1, initialCompleted = 0))
        }

        if (resumed != null && resumed.totalBytes == total && partFile.exists()) {
            return resumed.chunks.map { Chunk(it.start, it.endInclusive, it.completedBytes) }
        }

        val byBudget = (total / config.minBytesPerConnection).toInt()
        val connections = min(config.maxConnections, max(1, byBudget))
        if (connections == 1) return listOf(Chunk(0, total - 1, 0))

        val chunkSize = total / connections
        return (0 until connections).map { index ->
            val start = index * chunkSize
            val end = if (index == connections - 1) total - 1 else start + chunkSize - 1
            Chunk(start, end, 0)
        }
    }

    /**
     * Downloads one range, retrying transient failures.
     *
     * A retry resumes from the chunk's own progress rather than its start, so a connection that
     * dies at 90% costs the last 10% and not the whole range.
     */
    private suspend fun fetchChunk(
        spec: DownloadSpec,
        chunk: Chunk,
        channel: FileChannel,
        downloaded: AtomicLong,
        supportsRanges: Boolean,
    ) {
        var attempt = 0
        while (true) {
            if (chunk.isComplete) return
            try {
                streamChunk(spec, chunk, channel, downloaded, supportsRanges)
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                attempt++
                if (attempt > config.maxRetriesPerChunk) throw error
                // Back off, but not so far that a flaky link turns into a stalled download.
                delay(RETRY_BASE_DELAY_MILLIS * (1L shl (attempt - 1)))
            }
        }
    }

    private suspend fun streamChunk(
        spec: DownloadSpec,
        chunk: Chunk,
        channel: FileChannel,
        downloaded: AtomicLong,
        supportsRanges: Boolean,
    ) {
        val builder = Request.Builder().url(spec.url).headers(spec)
        if (supportsRanges && chunk.endInclusive >= 0) {
            builder.header("Range", "bytes=${chunk.resumeFrom}-${chunk.endInclusive}")
        } else if (chunk.completed.get() > 0) {
            builder.header("Range", "bytes=${chunk.resumeFrom}-")
        }

        val response = engine.execute(builder.build())
        if (!response.isSuccessful) {
            response.closeQuietly()
            error("HTTP ${response.code} for ${spec.url}")
        }

        response.use { resp ->
            val body = resp.body ?: error("empty body for ${spec.url}")
            val source = body.byteStream()
            val buffer = ByteArray(config.bufferBytes)
            var position = chunk.resumeFrom

            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                // Positional writes do not touch the channel's own position, so every chunk
                // can write concurrently to the same channel without coordination.
                var written = 0
                while (written < read) {
                    written += channel.write(
                        ByteBuffer.wrap(buffer, written, read - written),
                        position + written,
                    )
                }
                position += read
                chunk.completed.addAndGet(read.toLong())
                downloaded.addAndGet(read.toLong())
            }
        }

        // A server that hangs up mid-range, or answers a range with fewer bytes than it
        // promised, otherwise leaves a hole in the file that nothing downstream would notice -
        // the download "succeeds" and produces a corrupt result. Treat a short read as the
        // failure it is so the retry loop resumes from where the bytes actually stopped.
        if (chunk.size >= 0 && !chunk.isComplete) {
            error(
                "short read for ${spec.url}: got ${chunk.completed.get()} of ${chunk.size} bytes",
            )
        }
    }

    /** Emits a throttled progress event with a rolling transfer rate. */
    private suspend fun kotlinx.coroutines.channels.SendChannel<DownloadProgress>.reportProgress(
        downloaded: AtomicLong,
        remote: RemoteFile,
        connections: Int,
    ) {
        var lastBytes = downloaded.get()
        var lastTick = System.nanoTime()
        while (true) {
            delay(config.progressIntervalMillis)
            val now = System.nanoTime()
            val bytes = downloaded.get()
            val elapsedNanos = (now - lastTick).coerceAtLeast(1)
            // A short window, so the number reacts to a stall instead of averaging it away.
            val rate = (bytes - lastBytes) * NANOS_PER_SECOND / elapsedNanos
            lastBytes = bytes
            lastTick = now
            send(
                DownloadProgress.Running(
                    downloadedBytes = bytes,
                    totalBytes = remote.totalBytes,
                    bytesPerSecond = rate.coerceAtLeast(0),
                    connections = connections,
                ),
            )
        }
    }

    private suspend fun persistPeriodically(
        stateFile: File,
        spec: DownloadSpec,
        remote: RemoteFile,
        chunks: List<Chunk>,
    ) {
        while (true) {
            delay(RESUME_WRITE_INTERVAL_MILLIS)
            writeResumeState(stateFile, spec, remote, chunks)
        }
    }

    private fun writeResumeState(
        stateFile: File,
        spec: DownloadSpec,
        remote: RemoteFile,
        chunks: List<Chunk>,
    ) {
        val total = remote.totalBytes ?: return
        if (!remote.supportsRanges) return
        runCatching {
            val state = ResumeState(
                url = spec.url,
                totalBytes = total,
                chunks = chunks.map { ChunkState(it.start, it.endInclusive, it.completed.get()) },
            )
            stateFile.writeText(json.encodeToString(ResumeState.serializer(), state))
        }
    }

    private fun loadResumeState(stateFile: File, url: String, totalBytes: Long?): ResumeState? {
        if (!stateFile.exists() || totalBytes == null) return null
        val state = runCatching {
            json.decodeFromString(ResumeState.serializer(), stateFile.readText())
        }.getOrNull() ?: return null
        // A different URL or a changed size means the remote file is not the one we started.
        return state.takeIf { it.url == url && it.totalBytes == totalBytes }
    }

    private fun Request.Builder.headers(spec: DownloadSpec) = apply {
        header("User-Agent", UserAgents.DESKTOP)
        header("Accept-Encoding", "identity")
        spec.headers.forEach { (name, value) -> header(name, value) }
    }

    /** One byte range and how much of it is already on disk. */
    internal class Chunk(
        val start: Long,
        /** Inclusive end, or -1 when the size is unknown and the chunk runs to EOF. */
        val endInclusive: Long,
        initialCompleted: Long,
    ) {
        val completed = AtomicLong(initialCompleted)
        val size: Long get() = if (endInclusive < 0) -1 else endInclusive - start + 1
        val resumeFrom: Long get() = start + completed.get()
        val isComplete: Boolean get() = size >= 0 && completed.get() >= size
    }

    internal data class RemoteFile(
        val totalBytes: Long?,
        val supportsRanges: Boolean,
    )

    private companion object {
        const val PART_SUFFIX = ".part"
        const val RESUME_SUFFIX = ".resume"
        const val RETRY_BASE_DELAY_MILLIS = 400L
        const val RESUME_WRITE_INTERVAL_MILLIS = 2_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
