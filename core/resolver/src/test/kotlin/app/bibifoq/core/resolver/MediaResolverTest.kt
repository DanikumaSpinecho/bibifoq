package app.bibifoq.core.resolver

import app.bibifoq.core.model.Completeness
import app.bibifoq.core.model.Provenance
import app.bibifoq.core.net.HttpEngine
import app.bibifoq.core.resolver.extractors.DirectMediaExtractor
import app.bibifoq.core.resolver.extractors.OEmbedExtractor
import app.bibifoq.core.resolver.extractors.StructuredDataExtractor
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Exercises the tiering itself: which tier answers, which tier is spared, and what the caller
 * sees along the way.
 *
 * These run on real dispatchers and real time rather than a test scheduler, because the whole
 * mechanism under test is a race between a cheap tier and a delayed expensive one - virtual
 * time would fast-forward the head start and erase the behaviour being asserted.
 */
class MediaResolverTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var httpEngine: HttpEngine

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob())
        httpEngine = HttpEngine(
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build(),
        )
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun resolver(
        remoteEngine: RemoteEngine? = null,
        cache: MetadataCache = InMemoryMetadataCache(),
        config: ResolverConfig = ResolverConfig(),
    ) = MediaResolver(
        httpEngine = httpEngine,
        extractors = listOf(
            DirectMediaExtractor(httpEngine),
            OEmbedExtractor(httpEngine),
            StructuredDataExtractor(),
        ),
        cache = cache,
        remoteEngine = remoteEngine,
        config = config,
        backgroundScope = scope,
    )

    @Test
    fun `a direct media URL is answered natively and never reaches the engine`() = runBlocking {
        server.dispatch { MockResponse().setResponseCode(200).setHeader("Content-Type", "video/mp4").setHeader("Content-Length", "10485760").setHeader("Accept-Ranges", "bytes") }
        val engine = FakeRemoteEngine(latency = 5.seconds)

        val updates = resolver(engine).resolve(server.url("/clip.mp4").toString()).toList()

        val complete = assertIs<ResolveUpdate.Complete>(updates.last())
        assertEquals(Provenance.NATIVE, complete.winner)
        assertEquals(10_485_760, complete.info.formats.single().filesizeBytes)
        // This is the whole point of the tiering: the expensive path was never started.
        assertEquals(0, engine.callCount)
    }

    @Test
    fun `an HLS manifest is turned into a quality ladder without the engine`() = runBlocking {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480,CODECS="avc1.4d401e,mp4a.40.2"
            480.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
            720.m3u8
        """.trimIndent()
        server.dispatch { MockResponse().setResponseCode(200).setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody(master) }
        val engine = FakeRemoteEngine(latency = 5.seconds)

        val updates = resolver(engine).resolve(server.url("/live/master.m3u8").toString()).toList()

        val complete = assertIs<ResolveUpdate.Complete>(updates.last())
        assertEquals(2, complete.info.formats.size)
        assertEquals(720, complete.info.formats.maxOf { it.height ?: 0 })
        assertEquals(0, engine.callCount)
    }

    @Test
    fun `a page shows a native preview first and the engine's formats second`() = runBlocking {
        server.dispatch {
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody(
                """
                <html><head>
                  <meta property="og:title" content="Preview title">
                  <meta property="og:image" content="https://cdn.example.com/thumb.jpg">
                </head><body>...</body></html>
                """.trimIndent(),
            )
        }
        val engine = FakeRemoteEngine(latency = 300.milliseconds)

        val updates = resolver(engine).resolve(server.url("/watch/42").toString()).toList()

        assertIs<ResolveUpdate.Started>(updates.first())
        val partial = updates.filterIsInstance<ResolveUpdate.Partial>().first()
        assertEquals("Preview title", partial.info.title)
        assertEquals(Completeness.PREVIEW, partial.info.completeness)
        assertTrue(partial.info.formats.isEmpty())

        val complete = assertIs<ResolveUpdate.Complete>(updates.last())
        assertEquals(Provenance.YTDLP, complete.winner)
        assertEquals(2, complete.info.formats.size)
        // The preview's thumbnail survives the upgrade even though the engine did not send one.
        assertEquals("https://cdn.example.com/thumb.jpg", complete.info.thumbnailUrl)
        assertEquals("Title from the engine", complete.info.title)
        assertEquals(1, engine.callCount)
    }

    @Test
    fun `a cached complete result skips every tier`() = runBlocking {
        server.dispatch { MockResponse().setResponseCode(500) }
        val cache = InMemoryMetadataCache()
        val url = server.url("/watch/42").toString()
        val normalized = requireNotNull(UrlNormalizer.normalize(url))
        cache.put(normalized, FakeRemoteEngine.defaultResponse(normalized))
        val engine = FakeRemoteEngine()

        val updates = resolver(engine, cache).resolve(url).toList()

        val complete = assertIs<ResolveUpdate.Complete>(updates.last())
        assertEquals(Provenance.CACHE, complete.winner)
        assertEquals(0, engine.callCount)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a native preview carrying a stream survives an engine failure`() = runBlocking {
        server.dispatch {
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody(
                """
                <html><head>
                  <meta property="og:title" content="Still downloadable">
                  <meta property="og:video:secure_url" content="https://cdn.example.com/direct.mp4">
                </head></html>
                """.trimIndent(),
            )
        }
        val engine = FakeRemoteEngine(failWith = IllegalStateException("engine exploded"))

        val updates = resolver(engine).resolve(server.url("/watch/7").toString()).toList()

        val complete = assertIs<ResolveUpdate.Complete>(updates.last())
        assertEquals(Provenance.NATIVE, complete.winner)
        assertEquals("https://cdn.example.com/direct.mp4", complete.info.formats.single().url)
    }

    @Test
    fun `a page with nothing usable and a failing engine reports failure`() = runBlocking {
        server.dispatch { MockResponse().setResponseCode(404) }
        val engine = FakeRemoteEngine(failWith = IllegalStateException("unsupported url"))

        val updates = resolver(engine).resolve(server.url("/nope").toString()).toList()

        val failed = assertIs<ResolveUpdate.Failed>(updates.last())
        assertIs<ResolveError.EngineFailure>(failed.error)
    }

    @Test
    fun `input that is not a URL fails without touching the network`() = runBlocking {
        val updates = resolver(FakeRemoteEngine()).resolve("not a url at all").toList()
        assertIs<ResolveError.InvalidUrl>(assertIs<ResolveUpdate.Failed>(updates.single()).error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a URL pasted inside shared text still resolves`() = runBlocking {
        server.dispatch { MockResponse().setResponseCode(200).setHeader("Content-Type", "video/mp4").setHeader("Content-Length", "1024") }

        val shared = "look at this ${server.url("/clip.mp4")} amazing"
        val updates = resolver().resolve(shared).toList()

        assertIs<ResolveUpdate.Complete>(updates.last())
    }

    @Test
    fun `a completed resolution is served from cache the second time`() = runBlocking {
        server.dispatch { MockResponse().setResponseCode(200).setHeader("Content-Type", "video/mp4").setHeader("Content-Length", "2048") }
        val cache = InMemoryMetadataCache()
        val subject = resolver(cache = cache)
        val url = server.url("/clip.mp4").toString()

        subject.resolve(url).toList()
        val requestsAfterFirst = server.requestCount

        val second = subject.resolve(url).toList()

        assertEquals(Provenance.CACHE, assertIs<ResolveUpdate.Complete>(second.last()).winner)
        assertEquals(requestsAfterFirst, server.requestCount)
    }

    /** Answers every request with the same response, whatever the path. */
    private fun MockWebServer.dispatch(response: () -> MockResponse) {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = response()
        }
    }
}
