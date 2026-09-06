package app.bibifoq.core.downloader

import app.bibifoq.core.net.HttpEngine
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SegmentedDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private val engine = HttpEngine()

    /** Big enough that the planner splits it, with a byte pattern that catches misordering. */
    private val content = Random(1234).nextBytes(9 * 1024 * 1024)

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        directory = Files.createTempDirectory("downloader").toFile()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        directory.deleteRecursively()
    }

    private fun downloader(config: DownloadConfig = DownloadConfig()) =
        SegmentedDownloader(engine, config)

    @Test
    fun `splits a large file across connections and reassembles it byte for byte`() = runBlocking {
        val origin = RangeServer(content)
        server.dispatcher = origin
        val destination = File(directory, "clip.mp4")

        val updates = downloader().download(
            DownloadSpec(url = server.url("/clip.mp4").toString(), destination = destination),
        ).toList()

        val completed = assertIs<DownloadProgress.Completed>(updates.last())
        assertContentEquals(content, destination.readBytes())
        assertEquals(content.size.toLong(), completed.totalBytes)

        val started = assertIs<DownloadProgress.Started>(updates.first())
        assertTrue(started.connections > 1, "expected a split, got ${started.connections}")
        // One probe plus one request per chunk.
        assertEquals(started.connections + 1, origin.requestCount)
    }

    @Test
    fun `downloads in one stream when the server refuses ranges`() = runBlocking {
        val origin = RangeServer(content, supportRanges = false)
        server.dispatcher = origin
        val destination = File(directory, "clip.mp4")

        val updates = downloader().download(
            DownloadSpec(url = server.url("/clip.mp4").toString(), destination = destination),
        ).toList()

        assertIs<DownloadProgress.Completed>(updates.last())
        assertEquals(1, assertIs<DownloadProgress.Started>(updates.first()).connections)
        assertContentEquals(content, destination.readBytes())
    }

    @Test
    fun `does not split a file too small for the extra requests to pay off`() = runBlocking {
        val small = Random(7).nextBytes(64 * 1024)
        server.dispatcher = RangeServer(small)
        val destination = File(directory, "small.mp4")

        val updates = downloader().download(
            DownloadSpec(url = server.url("/small.mp4").toString(), destination = destination),
        ).toList()

        assertEquals(1, assertIs<DownloadProgress.Started>(updates.first()).connections)
        assertContentEquals(small, destination.readBytes())
    }

    @Test
    fun `retries a range whose connection dropped and still writes the right bytes`() = runBlocking {
        server.dispatcher = RangeServer(content, failFirstRequests = 2)
        val destination = File(directory, "flaky.mp4")

        val updates = downloader().download(
            DownloadSpec(url = server.url("/flaky.mp4").toString(), destination = destination),
        ).toList()

        assertIs<DownloadProgress.Completed>(updates.last())
        assertContentEquals(content, destination.readBytes())
    }

    @Test
    fun `cleans up the part file and the ledger on success`() = runBlocking {
        server.dispatcher = RangeServer(content)
        val destination = File(directory, "clip.mp4")

        downloader().download(
            DownloadSpec(url = server.url("/clip.mp4").toString(), destination = destination),
        ).toList()

        assertTrue(destination.exists())
        assertFalse(File(destination.path + ".part").exists())
        assertFalse(File(destination.path + ".resume").exists())
    }

    @Test
    fun `reports progress that ends at the full size`() = runBlocking {
        server.dispatcher = RangeServer(content)
        val destination = File(directory, "clip.mp4")

        val updates = downloader(DownloadConfig(progressIntervalMillis = 20)).download(
            DownloadSpec(url = server.url("/clip.mp4").toString(), destination = destination),
        ).toList()

        val running = updates.filterIsInstance<DownloadProgress.Running>()
        assertTrue(running.isNotEmpty(), "expected at least one progress event")
        running.forEach { progress ->
            assertEquals(content.size.toLong(), progress.totalBytes)
            assertTrue(progress.fraction!! in 0f..1f)
        }
        assertEquals(content.size.toLong(), destination.length())
    }

    @Test
    fun `plans one chunk per connection budget`() {
        val subject = downloader(DownloadConfig(maxConnections = 4, minBytesPerConnection = 1024))
        val part = File(directory, "x.part")

        val chunks = subject.planChunks(
            SegmentedDownloader.RemoteFile(totalBytes = 10_000, supportsRanges = true),
            resumed = null,
            partFile = part,
        )

        assertEquals(4, chunks.size)
        assertEquals(0, chunks.first().start)
        assertEquals(9_999, chunks.last().endInclusive)
        // The ranges must tile the file exactly: no gaps, no overlap.
        chunks.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endInclusive + 1, right.start)
        }
        assertEquals(10_000, chunks.sumOf { it.size })
    }

    @Test
    fun `plans a single chunk when ranges are unavailable`() {
        val chunks = downloader().planChunks(
            SegmentedDownloader.RemoteFile(totalBytes = 100_000_000, supportsRanges = false),
            resumed = null,
            partFile = File(directory, "x.part"),
        )
        assertEquals(1, chunks.size)
    }

    @Test
    fun `resumes from a saved ledger instead of restarting`() = runBlocking {
        val origin = RangeServer(content)
        server.dispatcher = origin
        val destination = File(directory, "resumed.mp4")
        val partFile = File(destination.path + ".part")
        val stateFile = File(destination.path + ".resume")
        val url = server.url("/resumed.mp4").toString()

        // Simulate an interrupted run: the first half of chunk 0 is already on disk.
        val alreadyHave = 1_000_000L
        partFile.writeBytes(ByteArray(content.size))
        java.io.RandomAccessFile(partFile, "rw").use { raf ->
            raf.seek(0)
            raf.write(content, 0, alreadyHave.toInt())
        }
        val half = content.size.toLong() / 2
        stateFile.writeText(
            kotlinx.serialization.json.Json.encodeToString(
                ResumeState.serializer(),
                ResumeState(
                    url = url,
                    totalBytes = content.size.toLong(),
                    chunks = listOf(
                        ChunkState(0, half - 1, alreadyHave),
                        ChunkState(half, content.size - 1L, 0),
                    ),
                ),
            ),
        )

        val updates = downloader().download(
            DownloadSpec(url = url, destination = destination),
        ).toList()

        val started = assertIs<DownloadProgress.Started>(updates.first())
        assertEquals(alreadyHave, started.resumedBytes)
        assertIs<DownloadProgress.Completed>(updates.last())
        assertContentEquals(content, destination.readBytes())

        // The resumed chunk asked only for the bytes it was missing.
        val resumeRange = origin.rangeHeaders.filterNotNull().firstOrNull { it.startsWith("bytes=$alreadyHave-") }
        assertTrue(resumeRange != null, "expected a range starting at $alreadyHave, saw ${origin.rangeHeaders}")
    }

    @Test
    fun `ignores a ledger written for a different URL`() {
        val stateFile = File(directory, "x.resume")
        stateFile.writeText(
            kotlinx.serialization.json.Json.encodeToString(
                ResumeState.serializer(),
                ResumeState("https://elsewhere.example/other.mp4", 10_000, listOf(ChunkState(0, 9_999, 5_000))),
            ),
        )
        val partFile = File(directory, "x.part").apply { writeBytes(ByteArray(10_000)) }

        // A stale ledger must not be trusted into corrupting a different file.
        val chunks = downloader().planChunks(
            SegmentedDownloader.RemoteFile(10_000, supportsRanges = true),
            resumed = null,
            partFile = partFile,
        )
        assertEquals(0, chunks.first().completed.get())
    }
}
