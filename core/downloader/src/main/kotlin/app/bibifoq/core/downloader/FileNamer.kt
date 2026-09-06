package app.bibifoq.core.downloader

import app.bibifoq.core.model.MediaFormat
import app.bibifoq.core.model.MediaInfo
import java.io.File

/**
 * Builds a filename a human would recognise and a filesystem will accept.
 *
 * Android's shared storage sits on FAT-derived filesystems, so the character set is the
 * restrictive one, names have a hard length limit, and a handful of names are reserved
 * outright. Getting this wrong shows up as downloads that silently fail to save.
 */
object FileNamer {

    /** Illegal on FAT/exFAT, and on the Windows machines these files often get copied to. */
    private val ILLEGAL = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    /** Reserved device names on Windows, case-insensitive, with or without an extension. */
    private val RESERVED = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )

    /** Leaves room for a `.part` suffix and a de-duplicating counter inside the 255-byte limit. */
    const val MAX_STEM_LENGTH: Int = 180

    /**
     * Renders [template] with [info]'s fields.
     *
     * Supported placeholders: `%(title)s`, `%(uploader)s`, `%(id)s`, `%(date)s`,
     * `%(resolution)s`, `%(ext)s`.
     */
    fun render(
        info: MediaInfo,
        format: MediaFormat?,
        template: String = DEFAULT_TEMPLATE,
    ): String {
        val extension = format?.container ?: "mp4"
        val resolution = format?.height?.let { "${it}p" } ?: ""

        val substituted = template
            .replace("%(title)s", info.title)
            .replace("%(uploader)s", info.uploader.orEmpty())
            .replace("%(id)s", info.id)
            .replace("%(date)s", info.uploadDate.orEmpty())
            .replace("%(resolution)s", resolution)
            .replace("%(ext)s", extension)

        val stem = sanitize(substituted.removeSuffix(".$extension"))
        return "$stem.$extension"
    }

    /** Strips everything a filesystem would reject and collapses the leftovers. */
    fun sanitize(raw: String): String {
        val cleaned = raw
            .map { char -> if (char in ILLEGAL || char.code < 0x20) ' ' else char }
            .joinToString("")
            .replace(WHITESPACE_RUN, " ")
            // A leading dot hides the file on every unix-like system, including Android.
            .trim(' ', '.', '-', '_')

        val bounded = cleaned.take(MAX_STEM_LENGTH).trimEnd(' ', '.')
        return when {
            bounded.isBlank() -> FALLBACK_NAME
            bounded.lowercase().substringBefore('.') in RESERVED -> "_$bounded"
            else -> bounded
        }
    }

    /**
     * Returns a path that does not exist yet, appending ` (2)`, ` (3)` and so on.
     *
     * Checked rather than assumed: re-downloading a video should sit beside the first copy,
     * not overwrite it.
     */
    fun uniqueIn(directory: File, filename: String): File {
        val candidate = File(directory, filename)
        if (!candidate.exists()) return candidate

        val stem = filename.substringBeforeLast('.', filename)
        val extension = filename.substringAfterLast('.', "")
        val suffix = if (extension.isEmpty()) "" else ".$extension"

        var counter = 2
        while (true) {
            val next = File(directory, "$stem ($counter)$suffix")
            if (!next.exists()) return next
            counter++
        }
    }

    const val DEFAULT_TEMPLATE: String = "%(title)s.%(ext)s"
    const val FALLBACK_NAME: String = "download"

    private val WHITESPACE_RUN = Regex("""\s+""")
}
