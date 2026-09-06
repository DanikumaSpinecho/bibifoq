package app.bibifoq.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies a finished download into shared storage so it shows up in the gallery and the file
 * manager rather than only inside the app's private directory.
 *
 * Downloads land in app-specific storage first, deliberately: that is the only place the app
 * can do random-access, resumable writes without asking for a permission, and resume is worth
 * more than saving one local copy at the end.
 */
class MediaStorePublisher(private val context: Context) {

    /**
     * @return the shared-storage URI, or null when publishing failed. A failure is not fatal:
     *   the file is still in app storage and still playable from inside the app.
     */
    suspend fun publish(file: File, mimeType: String): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val collection = if (mimeType.startsWith("audio/")) {
                audioCollection()
            } else {
                videoCollection()
            }
            val relativePath = if (mimeType.startsWith("audio/")) {
                "${Environment.DIRECTORY_MUSIC}/$FOLDER"
            } else {
                "${Environment.DIRECTORY_MOVIES}/$FOLDER"
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                // Hide the entry from other apps until the bytes are actually all there.
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(collection, values)
                ?: return@runCatching null

            context.contentResolver.openOutputStream(uri)?.use { sink ->
                file.inputStream().use { source -> source.copyTo(sink, DEFAULT_BUFFER_SIZE) }
            } ?: return@runCatching null

            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            uri
        }.getOrNull()
    }

    private fun videoCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    private fun audioCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

    companion object {
        const val FOLDER = "Bibifoq"

        /** Best-effort MIME type from a container extension. */
        fun mimeTypeFor(extension: String?): String = when (extension?.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "opus", "ogg", "oga" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "ts" -> "video/mp2t"
            else -> "video/mp4"
        }
    }
}
