package app.bibifoq.engine

import android.content.Context
import android.util.Log
import app.bibifoq.core.model.MediaInfo
import app.bibifoq.core.net.FileCookieStore
import app.bibifoq.data.EngineChannel
import app.bibifoq.core.resolver.RemoteEngine
import app.bibifoq.core.resolver.RemoteEngineOptions
import app.bibifoq.core.resolver.ResolveError
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Tier 2 of the resolver: the embedded yt-dlp runtime.
 *
 * ## Where the time goes, and what this class does about it
 *
 * A cold call to a Python-backed extractor is dominated by two costs that have nothing to do
 * with the site being resolved:
 *
 *  1. **Runtime start-up** - unpacking and booting the interpreter, then importing the
 *     extractor tree. On a mid-range phone that is comfortably over a second, every time.
 *  2. **Optional work the caller never asked for** - probing every format with its own HTTP
 *     request, resolving every entry of a playlist, reading user config files.
 *
 * [warmUp] moves the first cost to app launch, where nobody is waiting on it, and the request
 * built in [fetchInfo] removes most of the second. What is left is the site-specific work that
 * genuinely has to happen.
 */
class YtDlpEngine(
    private val context: Context,
    private val cookies: FileCookieStore? = null,
) : RemoteEngine {

    private val initialised = AtomicBoolean(false)
    private val initLock = Mutex()

    @Volatile
    private var version: String? = null

    /**
     * Saved extractions live in the cache directory: they are an optimisation, and the system
     * is welcome to reclaim them.
     */
    private val infoJsonDir: File by lazy { File(context.cacheDir, "engine-info") }

    override val isWarm: Boolean get() = initialised.get()

    override suspend fun describe(): String {
        return runCatching {
            ensureInitialised()
            version ?: withContext(Dispatchers.IO) { YoutubeDL.getInstance().version(context) }
        }.getOrNull()?.let { "yt-dlp $it" } ?: "yt-dlp (not initialised)"
    }

    /**
     * Unpacks and boots the runtime.
     *
     * Safe to call repeatedly and from anywhere; the first caller does the work and the rest
     * wait on it. Call it from [android.app.Application.onCreate] so the cost is already paid
     * by the time a user pastes anything.
     */
    override suspend fun warmUp() {
        runCatching { ensureInitialised() }
            .onFailure { Log.w(TAG, "engine warm-up failed", it) }
    }

    /**
     * Boots the runtime if it is not up yet, and throws if it cannot be.
     *
     * [warmUp] deliberately swallows failures because it runs speculatively at launch. A
     * download cannot: it needs to fail loudly so the queue can show why.
     */
    suspend fun ensureReady() = ensureInitialised()

    private suspend fun ensureInitialised() {
        if (initialised.get()) return
        initLock.withLock {
            if (initialised.get()) return
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(context)
                version = runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()
            }
            initialised.set(true)
        }
    }

    override suspend fun fetchInfo(url: String, options: RemoteEngineOptions): MediaInfo {
        ensureInitialised()

        val processId = UUID.randomUUID().toString()
        val request = buildRequest(url, options)

        return withContext(Dispatchers.IO) {
            // The engine call is a blocking wait on a child process, so cancelling the
            // coroutine has to kill that process or it keeps running and holding the CPU.
            val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
                }
            }

            try {
                val response = YoutubeDL.getInstance().execute(request, processId)
                if (response.exitCode != 0) {
                    throw ResolveError.EngineFailure(
                        response.err.takeIf { it.isNotBlank() }?.trim()?.lastLine()
                            ?: "engine exited with ${response.exitCode}",
                    )
                }
                val payload = response.out.trim()
                if (payload.isEmpty()) throw ResolveError.EngineFailure("engine returned no data")

                val document = payload.firstJsonLine()
                // Keep the extraction so a download can replay it rather than repeating it.
                saveInfoJson(url, document)

                runCatching { YtDlpInfoMapper.parse(document, url) }
                    .getOrElse { failure ->
                        throw ResolveError.EngineFailure(
                            "could not read the engine's output: ${failure.message}",
                            failure,
                        )
                    }
            } finally {
                cancellationHandle?.dispose()
            }
        }
    }

    /**
     * Builds the cheapest request that still answers the question.
     *
     * Every option here removes work rather than adding it; the defaults exist for a
     * command-line tool whose user is not staring at a spinner.
     */
    private fun buildRequest(url: String, options: RemoteEngineOptions): YoutubeDLRequest =
        YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--no-warnings")
            addOption("--no-progress")
            // Never read a config file off the device: it makes behaviour unpredictable and
            // costs a filesystem walk on every call.
            addOption("--ignore-config")
            addOption("--socket-timeout", options.socketTimeoutSeconds.toString())

            // Sites that gate video behind a login need the session here too, not only at
            // download time - without it extraction fails before a format list even exists.
            cookies?.fileOrNull()?.let { file -> addOption("--cookies", file.absolutePath) }

            if (options.noPlaylist) addOption("--no-playlist")
            if (options.flatPlaylist) {
                // Without this, pasting a channel resolves every video in it before showing
                // anything, which turns a two-second action into a several-minute one.
                addOption("--flat-playlist")
            }
            if (!options.checkFormats) {
                // Format checking is one HEAD request per format - often 20+ round trips - to
                // firm up sizes the UI only ever shows as an approximation.
                addOption("--no-check-formats")
            }

            options.extraArguments.forEach { argument ->
                val parts = argument.split(' ', limit = 2)
                if (parts.size == 2) addOption(parts[0], parts[1]) else addOption(parts[0])
            }
        }

    /**
     * Refreshes the bundled extractor code, which is what keeps sites working over time.
     *
     * The version frozen into the APK ages badly - sites change, extractors follow - so this is
     * the difference between an app that keeps working and one that slowly stops.
     */
    suspend fun update(channel: EngineChannel = EngineChannel.STABLE): String = withContext(Dispatchers.IO) {
        ensureInitialised()
        val target = when (channel) {
            EngineChannel.STABLE -> YoutubeDL.UpdateChannel.STABLE
            EngineChannel.NIGHTLY -> YoutubeDL.UpdateChannel.NIGHTLY
            EngineChannel.MASTER -> YoutubeDL.UpdateChannel.MASTER
        }
        val status = YoutubeDL.getInstance().updateYoutubeDL(context, target)
        version = runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()
        // A null status means the library could not tell us what happened, which is not
        // the same as a failure - report it as unknown rather than inventing a result.
        status?.name ?: "UNKNOWN"
    }

    /**
     * The saved extraction for [url], if there is a usable one.
     *
     * A download replays this instead of extracting the page a second time - see
     * [app.bibifoq.download.EngineDownloader].
     */
    fun infoJsonFor(url: String): File? =
        infoJsonFile(url).takeIf { it.isFile && it.length() > 0 }

    private fun saveInfoJson(url: String, document: String) {
        // Purely an optimisation: a failure here costs a re-extraction, nothing more.
        runCatching {
            infoJsonDir.mkdirs()
            infoJsonFile(url).writeText(document)
            val stale = infoJsonDir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_SAVED_EXTRACTIONS)
                .orEmpty()
            stale.forEach { it.delete() }
        }
    }

    private fun infoJsonFile(url: String) = File(infoJsonDir, "${hash(url)}.info.json")

    /** URLs contain characters a filename cannot, so the key is hashed rather than escaped. */
    private fun hash(url: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

    /**
     * `--dump-single-json` writes one JSON document, but a noisy extractor can print
     * additional lines around it. Take the first line that is actually a JSON object.
     */
    private fun String.firstJsonLine(): String =
        lineSequence().firstOrNull { it.trimStart().startsWith("{") } ?: this

    private fun String.lastLine(): String = lineSequence().last { it.isNotBlank() }

    private companion object {
        const val TAG = "YtDlpEngine"

        /** Enough to cover a browsing session; each document can be several hundred KB. */
        const val MAX_SAVED_EXTRACTIONS = 16
    }
}
