package app.bibifoq.core.downloader

import app.bibifoq.core.net.HttpEngine
import app.bibifoq.core.resolver.manifest.HlsKey
import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HlsDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private val engine = HttpEngine()

    private val segments = (0 until 12).map { index ->
        "segment-$index-".repeat(64).toByteArray()
    }

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        directory = Files.createTempDirectory("hls").toFile()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        directory.deleteRecursively()
    }

    @Test
    fun `writes segments in playlist order even though they are fetched concurrently`() = runBlocking {
        val playlist = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-TARGETDURATION:4")
            segments.indices.forEach { index ->
                appendLine("#EXTINF:4.0,")
                appendLine("seg$index.ts")
            }
            appendLine("#EXT-X-ENDLIST")
        }
        server.dispatcher = playlistDispatcher(playlist) { index -> segments[index] }
        val destination = File(directory, "stream.ts")

        val updates = HlsDownloader(engine).download(
            DownloadSpec(url = server.url("/media.m3u8").toString(), destination = destination),
        ).toList()

        assertIs<DownloadProgress.Completed>(updates.last())
        // Concurrency must not reorder the output; a stream is only valid in sequence.
        assertContentEquals(segments.reduce { a, b -> a + b }, destination.readBytes())
    }

    @Test
    fun `decrypts AES-128 segments using the key the playlist points at`() = runBlocking {
        val key = ByteArray(16) { (it + 1).toByte() }
        val iv = ByteArray(16) { (16 - it).toByte() }
        val ivHex = "0x" + iv.joinToString("") { "%02x".format(it) }

        val playlist = buildString {
            appendLine("#EXTM3U")
            appendLine("""#EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=$ivHex""")
            segments.indices.forEach { index ->
                appendLine("#EXTINF:4.0,")
                appendLine("seg$index.ts")
            }
            appendLine("#EXT-X-ENDLIST")
        }
        server.dispatcher = playlistDispatcher(
            playlist = playlist,
            key = key,
            segment = { index -> encrypt(segments[index], key, iv) },
        )
        val destination = File(directory, "encrypted.ts")

        val updates = HlsDownloader(engine).download(
            DownloadSpec(url = server.url("/media.m3u8").toString(), destination = destination),
        ).toList()

        assertIs<DownloadProgress.Completed>(updates.last())
        assertContentEquals(segments.reduce { a, b -> a + b }, destination.readBytes())
    }

    @Test
    fun `derives the IV from the media sequence when the playlist omits it`() {
        val subject = HlsDownloader(engine)
        val key = HlsKey(method = "AES-128", uri = "https://e/key.bin", iv = null)

        // The specification defines the default IV as the sequence number, big-endian, in 128 bits.
        val expected = ByteArray(16).also { it[15] = 5 }
        assertContentEquals(expected, subject.ivFor(key, sequence = 5))

        val large = subject.ivFor(key, sequence = 0x0102030405060708L)
        assertContentEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8),
            large,
        )
    }

    @Test
    fun `reads an explicit IV whatever its casing and padding`() {
        val subject = HlsDownloader(engine)
        val expected = ByteArray(16).also { it[14] = 0x0a; it[15] = 0x0b }
        assertContentEquals(expected, subject.ivFor(HlsKey("AES-128", "u", "0X0A0B"), sequence = 0))
        assertContentEquals(
            expected,
            subject.ivFor(HlsKey("AES-128", "u", "0x000000000000000000000000000" + "00A0B"), sequence = 0),
        )
    }

    @Test
    fun `refuses an encryption method it cannot honour rather than writing garbage`() = runBlocking {
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="key.bin"
            #EXTINF:4.0,
            seg0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        server.dispatcher = playlistDispatcher(playlist) { segments[0] }

        val updates = HlsDownloader(engine).download(
            DownloadSpec(
                url = server.url("/media.m3u8").toString(),
                destination = File(directory, "x.ts"),
            ),
        ).toList()

        val failed = assertIs<DownloadProgress.Failed>(updates.last())
        assertIs<UnsupportedOperationException>(failed.error)
        assertTrue(failed.error.message!!.contains("SAMPLE-AES"))
    }

    @Test
    fun `reports failure when the URL is not a media playlist`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(200).setBody("<html>not a playlist</html>")
        }

        val updates = HlsDownloader(engine).download(
            DownloadSpec(
                url = server.url("/media.m3u8").toString(),
                destination = File(directory, "x.ts"),
            ),
        ).toList()

        assertIs<DownloadProgress.Failed>(updates.last())
        assertEquals(0, File(directory, "x.ts.part").length())
    }

    private fun encrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }

    private fun playlistDispatcher(
        playlist: String,
        key: ByteArray? = null,
        segment: (Int) -> ByteArray,
    ) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                path.endsWith(".m3u8") -> MockResponse().setResponseCode(200).setBody(playlist)
                path.endsWith("key.bin") && key != null ->
                    MockResponse().setResponseCode(200).setBody(Buffer().write(key))
                path.contains("seg") -> {
                    val index = path.substringAfter("seg").substringBefore(".ts").toInt()
                    MockResponse().setResponseCode(200).setBody(Buffer().write(segment(index)))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
    }
}
