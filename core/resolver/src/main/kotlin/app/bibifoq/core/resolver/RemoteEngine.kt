package app.bibifoq.core.resolver

import app.bibifoq.core.model.MediaInfo

/**
 * The general-purpose extraction back end - on Android, the embedded yt-dlp runtime.
 *
 * It lives behind an interface so the resolver core stays a plain JVM module that can be unit
 * tested without an emulator, and so the expensive tier can be swapped or stubbed out.
 */
interface RemoteEngine {

    /** Human-readable engine name and version, for the diagnostics panel. */
    suspend fun describe(): String

    /**
     * True once [warmUp] has finished and a call will not pay start-up cost.
     *
     * The resolver uses this to decide how long to hold the expensive tier back: a cold engine
     * is worth delaying, a warm one is nearly free to start.
     */
    val isWarm: Boolean

    /**
     * Pays the engine's start-up cost ahead of time.
     *
     * For a Python-backed engine this is the interpreter boot plus the module import, which is
     * most of the latency a user perceives on their first download of a session. Doing it at
     * app launch, off the critical path, is the single largest win available here.
     */
    suspend fun warmUp()

    /** Extracts full metadata. Throws [ResolveError] subclasses on failure. */
    suspend fun fetchInfo(url: String, options: RemoteEngineOptions = RemoteEngineOptions()): MediaInfo
}

/**
 * Knobs for one engine call.
 *
 * The defaults are chosen for latency rather than completeness: the resolver asks for the
 * cheapest answer that is still authoritative, and only asks for more when the user does.
 */
data class RemoteEngineOptions(
    /**
     * List a playlist's entries without resolving each one.
     *
     * Resolving every entry up front is what makes pasting a 200-video playlist take minutes;
     * entries are resolved lazily when the user actually picks one.
     */
    val flatPlaylist: Boolean = true,

    /**
     * Skip the engine's per-format HEAD requests.
     *
     * Those exist to fill in exact byte sizes, which costs one request per format - easily
     * 20+ round trips - for information the UI only shows as an approximation anyway.
     */
    val checkFormats: Boolean = false,

    /** Treat a URL that is both a video and a playlist as just the video. */
    val noPlaylist: Boolean = true,

    /** Per-socket timeout in seconds. */
    val socketTimeoutSeconds: Int = 12,

    /** Extra raw engine arguments from the user's settings. */
    val extraArguments: List<String> = emptyList(),
)
