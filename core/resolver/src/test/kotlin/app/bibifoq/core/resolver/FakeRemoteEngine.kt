package app.bibifoq.core.resolver

import app.bibifoq.core.model.Completeness
import app.bibifoq.core.model.MediaFormat
import app.bibifoq.core.model.MediaInfo
import app.bibifoq.core.model.Protocol
import app.bibifoq.core.model.Provenance
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlinx.coroutines.delay

/**
 * Stand-in for the embedded extraction engine.
 *
 * It counts calls, which is how the tiering tests assert the thing that actually matters:
 * that the expensive tier does not run when the cheap one already answered.
 */
class FakeRemoteEngine(
    private val latency: Duration = ZERO,
    override val isWarm: Boolean = false,
    private val failWith: Throwable? = null,
    private val response: (String) -> MediaInfo = ::defaultResponse,
) : RemoteEngine {

    private val calls = AtomicInteger(0)

    /** How many times the engine was actually asked to extract something. */
    val callCount: Int get() = calls.get()

    override suspend fun describe(): String = "fake-engine"

    override suspend fun warmUp() = Unit

    override suspend fun fetchInfo(url: String, options: RemoteEngineOptions): MediaInfo {
        calls.incrementAndGet()
        if (latency > ZERO) delay(latency)
        failWith?.let { throw it }
        return response(url)
    }

    companion object {
        fun defaultResponse(url: String): MediaInfo = MediaInfo(
            sourceUrl = url,
            id = "engine-id",
            title = "Title from the engine",
            uploader = "Engine uploader",
            durationMs = 120_000,
            formats = listOf(
                MediaFormat(
                    id = "137",
                    url = "https://cdn.example.com/video-1080.mp4",
                    protocol = Protocol.HTTPS,
                    container = "mp4",
                    videoCodec = "avc1.640028",
                    width = 1920,
                    height = 1080,
                    bitrateBps = 4_500_000,
                ),
                MediaFormat(
                    id = "140",
                    url = "https://cdn.example.com/audio.m4a",
                    protocol = Protocol.HTTPS,
                    container = "m4a",
                    audioCodec = "mp4a.40.2",
                    bitrateBps = 128_000,
                ),
            ),
            extractor = "fake-engine",
            provenance = Provenance.YTDLP,
            completeness = Completeness.COMPLETE,
        )
    }
}
