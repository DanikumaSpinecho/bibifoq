package app.bibifoq.core.downloader

import app.bibifoq.core.net.HttpEngine
import app.bibifoq.core.net.UserAgents
import app.bibifoq.core.net.runCatchingCancellable
import app.bibifoq.core.resolver.manifest.HlsKey
import app.bibifoq.core.resolver.manifest.HlsParser
import app.bibifoq.core.resolver.manifest.HlsPlaylist
import app.bibifoq.core.resolver.manifest.HlsSegment
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.TimeSource
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Downloads an HLS stream by fetching its segments and concatenating them.
 *
 * Segments are fetched several at a time but written strictly in order, because the output is
 * a byte stream that only makes sense in sequence. The in-flight window is bounded by the
 * channel's capacity, so a stream with ten thousand segments uses the same memory as one with
 * ten - which matters, because the naive "launch them all" version runs a phone out of memory
 * on any feature-length video.
 *
 * AES-128 segment encryption is handled here: it is part of the HLS specification and extremely
 * common, and a downloader that ignores it produces files that will not play.
 */
class HlsDownloader(
    private val engine: HttpEngine,
    private val config: DownloadConfig = DownloadConfig(),
) {

    fun download(spec: DownloadSpec): Flow<DownloadProgress> = channelFlow {
        val clock = TimeSource.Monotonic.markNow()
        val partFile = File(spec.destination.path + PART_SUFFIX)
        spec.destination.parentFile?.mkdirs()

        val playlistText = fetchText(spec.url, spec.headers)
            ?: run {
                send(DownloadProgress.Failed(IllegalStateException("could not fetch ${spec.url}")))
                return@channelFlow
            }

        val media = HlsParser.parse(playlistText, spec.url.toHttpUrl()) as? HlsPlaylist.Media
            ?: run {
                send(
                    DownloadProgress.Failed(
                        IllegalStateException("${spec.url} is not a media playlist"),
                    ),
                )
                return@channelFlow
            }

        val unsupported = media.segments.firstOrNull { it.key != null && !it.key!!.isAes128 }
        if (unsupported != null) {
            send(
                DownloadProgress.Failed(
                    UnsupportedOperationException(
                        "segment encryption ${unsupported.key?.method} is not supported",
                    ),
                ),
            )
            return@channelFlow
        }

        val downloaded = AtomicLong(0)
        send(
            DownloadProgress.Started(
                // Segment sizes are not published, so the total is genuinely unknown until the
                // end. Reporting null beats reporting a guess the UI would render as a bar.
                totalBytes = null,
                connections = config.hlsConcurrency,
                resumedBytes = 0,
            ),
        )

        val outcome = runCatchingCancellable {
            withContext(Dispatchers.IO) {
                if (partFile.exists()) partFile.delete()
                partFile.outputStream().buffered().use { sink ->
                    coroutineScope {
                        val reporter = launch { reportProgress(downloaded) }
                        val keyCache = KeyCache(engine, spec.headers)
                        val gate = Semaphore(config.hlsConcurrency)

                        // Capacity bounds how far the fetchers may run ahead of the writer, so
                        // memory stays flat no matter how long the stream is.
                        val pipeline = Channel<Deferred<ByteArray>>(capacity = config.hlsConcurrency)

                        launch {
                            media.segments.forEachIndexed { index, segment ->
                                val fetch = async {
                                    gate.withPermit {
                                        fetchSegment(
                                            segment = segment,
                                            sequence = media.mediaSequence + index,
                                            headers = spec.headers,
                                            keyCache = keyCache,
                                        )
                                    }
                                }
                                pipeline.send(fetch)
                            }
                            pipeline.close()
                        }

                        for (pending in pipeline) {
                            val bytes = pending.await()
                            sink.write(bytes)
                            downloaded.addAndGet(bytes.size.toLong())
                        }
                        reporter.cancel()
                    }
                }
            }
        }

        outcome.fold(
            onSuccess = {
                if (spec.destination.exists()) spec.destination.delete()
                if (!partFile.renameTo(spec.destination)) {
                    partFile.copyTo(spec.destination, overwrite = true)
                    partFile.delete()
                }
                send(
                    DownloadProgress.Completed(
                        file = spec.destination,
                        totalBytes = spec.destination.length(),
                        elapsed = clock.elapsedNow(),
                    ),
                )
            },
            onFailure = { failure ->
                partFile.delete()
                send(DownloadProgress.Failed(failure))
            },
        )
    }

    private suspend fun fetchSegment(
        segment: HlsSegment,
        sequence: Long,
        headers: Map<String, String>,
        keyCache: KeyCache,
    ): ByteArray {
        val raw = fetchBytes(segment.url, headers)
            ?: error("could not fetch segment ${segment.url}")
        val key = segment.key ?: return raw
        return decryptAes128(raw, keyCache.keyFor(key), ivFor(key, sequence))
    }

    /**
     * The IV is either written in the playlist or, when it is not, defined by the
     * specification as the segment's media sequence number as a 128-bit big-endian value.
     */
    internal fun ivFor(key: HlsKey, sequence: Long): ByteArray {
        val explicit = key.iv
        if (explicit != null) {
            val hex = explicit.removePrefix("0x").removePrefix("0X")
            val bytes = ByteArray(IV_BYTES)
            val padded = hex.padStart(IV_BYTES * 2, '0').takeLast(IV_BYTES * 2)
            for (index in 0 until IV_BYTES) {
                bytes[index] = padded.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            return bytes
        }
        return ByteBuffer.allocate(IV_BYTES).putLong(0).putLong(sequence).array()
    }

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private suspend fun kotlinx.coroutines.channels.SendChannel<DownloadProgress>.reportProgress(
        downloaded: AtomicLong,
    ) {
        var lastBytes = 0L
        var lastTick = System.nanoTime()
        while (true) {
            delay(config.progressIntervalMillis)
            val now = System.nanoTime()
            val bytes = downloaded.get()
            val rate = (bytes - lastBytes) * NANOS_PER_SECOND / (now - lastTick).coerceAtLeast(1)
            lastBytes = bytes
            lastTick = now
            send(
                DownloadProgress.Running(
                    downloadedBytes = bytes,
                    totalBytes = null,
                    bytesPerSecond = rate.coerceAtLeast(0),
                    connections = config.hlsConcurrency,
                ),
            )
        }
    }

    private suspend fun fetchText(url: String, headers: Map<String, String>): String? =
        fetchBytes(url, headers)?.decodeToString()

    private suspend fun fetchBytes(url: String, headers: Map<String, String>): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UserAgents.DESKTOP)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val response = runCatchingCancellable { engine.execute(request) }.getOrNull() ?: return null
        return response.use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    }

    /**
     * Keys are per-playlist, not per-segment, so fetching one per segment would add a request
     * per segment for no reason.
     */
    private class KeyCache(
        private val engine: HttpEngine,
        private val headers: Map<String, String>,
    ) {
        private val cached = mutableMapOf<String, ByteArray>()
        private val mutex = kotlinx.coroutines.sync.Mutex()

        suspend fun keyFor(key: HlsKey): ByteArray {
            val uri = key.uri ?: error("AES-128 segment with no key URI")
            mutex.lock()
            try {
                cached[uri]?.let { return it }
            } finally {
                mutex.unlock()
            }

            val request = Request.Builder()
                .url(uri)
                .header("User-Agent", UserAgents.DESKTOP)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            val bytes = engine.execute(request).use { resp ->
                if (!resp.isSuccessful) error("key fetch failed: HTTP ${resp.code}")
                resp.body?.bytes() ?: error("empty key response")
            }
            require(bytes.size == KEY_BYTES) { "AES-128 key must be $KEY_BYTES bytes" }

            mutex.lock()
            try {
                cached[uri] = bytes
            } finally {
                mutex.unlock()
            }
            return bytes
        }
    }

    private companion object {
        const val PART_SUFFIX = ".part"
        const val IV_BYTES = 16
        const val KEY_BYTES = 16
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
