package app.bibifoq.core.downloader

import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.Serializable

/** One byte stream to fetch, already resolved down to a concrete URL. */
data class DownloadSpec(
    val url: String,
    val destination: File,
    val headers: Map<String, String> = emptyMap(),
    /** Size when the resolver already knows it; saved as one probe request. */
    val expectedBytes: Long? = null,
)

/** What the downloader reports while it works. */
sealed interface DownloadProgress {

    /** Emitted once the size and range support are known. */
    data class Started(
        val totalBytes: Long?,
        val connections: Int,
        val resumedBytes: Long,
    ) : DownloadProgress

    data class Running(
        val downloadedBytes: Long,
        val totalBytes: Long?,
        /** Bytes per second over a short rolling window, not since the start. */
        val bytesPerSecond: Long,
        val connections: Int,
    ) : DownloadProgress {
        val fraction: Float?
            get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }

        val eta: Duration?
            get() {
                if (totalBytes == null || bytesPerSecond <= 0) return null
                val remaining = (totalBytes - downloadedBytes).coerceAtLeast(0)
                return (remaining / bytesPerSecond).seconds
            }
    }

    data class Completed(val file: File, val totalBytes: Long, val elapsed: Duration) : DownloadProgress

    data class Failed(val error: Throwable) : DownloadProgress
}

/** Tuning for [SegmentedDownloader]. */
data class DownloadConfig(
    /**
     * Parallel range requests per file.
     *
     * More connections mostly help on CDNs that rate-limit a single stream, which is common.
     * Past a handful the returns vanish and some hosts start refusing, so this stays modest.
     */
    val maxConnections: Int = 6,

    /**
     * Do not split a file smaller than this.
     * Below it the extra requests cost more than the parallelism saves.
     */
    val minBytesPerConnection: Long = 2L * 1024 * 1024,

    val bufferBytes: Int = 128 * 1024,

    /** How often progress is reported. */
    val progressIntervalMillis: Long = 250,

    /** Retries per chunk before the whole download gives up. */
    val maxRetriesPerChunk: Int = 3,

    /** Parallel segment fetches for HLS. */
    val hlsConcurrency: Int = 6,
)

/**
 * On-disk record of a partially finished download.
 *
 * Written next to the `.part` file so an interrupted download resumes by re-requesting only
 * the ranges that never arrived, instead of starting over.
 */
@Serializable
data class ResumeState(
    val url: String,
    val totalBytes: Long,
    val chunks: List<ChunkState>,
) {
    val completedBytes: Long get() = chunks.sumOf { it.completedBytes }
}

@Serializable
data class ChunkState(
    val start: Long,
    val endInclusive: Long,
    val completedBytes: Long,
) {
    val size: Long get() = endInclusive - start + 1
    val isComplete: Boolean get() = completedBytes >= size
    /** Where the next range request for this chunk should begin. */
    val resumeFrom: Long get() = start + completedBytes
}
