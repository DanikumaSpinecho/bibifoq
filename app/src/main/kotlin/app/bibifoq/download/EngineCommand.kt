package app.bibifoq.download

import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/**
 * Builds the engine's download command.
 *
 * A pure function on purpose: the difference between replaying a saved extraction and doing a
 * fresh one is the whole point of [EngineDownloader], and keeping it free of process handling
 * means it can be asserted in a unit test rather than only observed on a device.
 */
internal object EngineCommand {

    /**
     * Exactly one of [savedInfo] and [webpageUrl] is used: replay a saved extraction, or do a
     * fresh one.
     */
    fun build(
        formatSpec: String,
        destination: File,
        container: String,
        savedInfo: File?,
        webpageUrl: String?,
        cookiesFile: File? = null,
    ): YoutubeDLRequest {
        val request = if (savedInfo != null) {
            // With a saved extraction there is no URL argument: the JSON is the input, and
            // passing a URL as well would send the engine back to the site.
            YoutubeDLRequest(emptyList<String>()).apply {
                addOption("--load-info-json", savedInfo.absolutePath)
            }
        } else {
            YoutubeDLRequest(requireNotNull(webpageUrl) { "no URL and no saved extraction" })
        }

        return request.apply {
            addOption("--no-warnings")
            addOption("--ignore-config")
            addOption("--no-playlist")
            addOption("-f", formatSpec)
            addOption("-o", destination.absolutePath)
            // Ask for one container rather than whatever the streams happened to arrive in.
            addOption("--merge-output-format", container)
            addOption("--newline")
            // A replayed extraction already holds signed URLs, but a fresh one - and any
            // fragment fetch - still needs the session.
            cookiesFile?.let { addOption("--cookies", it.absolutePath) }
        }
    }
}
