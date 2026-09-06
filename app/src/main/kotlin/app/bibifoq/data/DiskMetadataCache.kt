package app.bibifoq.data

import app.bibifoq.core.model.MediaInfo
import app.bibifoq.core.resolver.CacheTtl
import app.bibifoq.core.resolver.CachedInfo
import app.bibifoq.core.resolver.InMemoryMetadataCache
import app.bibifoq.core.resolver.MetadataCache
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Two-level metadata cache: memory in front, disk behind.
 *
 * The disk level is what makes the app fast on a *cold start*, which is the case that actually
 * matters - people share a link, look at it, kill the app, and share the same link again an
 * hour later. An in-memory cache alone would miss every one of those.
 */
class DiskMetadataCache(
    private val directory: File,
    private val memory: InMemoryMetadataCache = InMemoryMetadataCache(),
    private val maxEntries: Int = 512,
    private val clock: () -> Long = System::currentTimeMillis,
) : MetadataCache {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun get(key: String): CachedInfo? {
        memory.get(key)?.let { return it }

        val file = fileFor(key)
        val entry = withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext null
            runCatching { json.decodeFromString(DiskEntry.serializer(), file.readText()) }.getOrNull()
        } ?: return null

        val cached = CachedInfo(entry.info, entry.storedAtMillis)
        if (!cached.isFresh(clock(), ttlFor(entry.info))) {
            withContext(Dispatchers.IO) { file.delete() }
            return null
        }
        // Promote so the next read in this session does not touch the disk at all.
        memory.put(key, entry.info)
        return cached
    }

    override suspend fun put(key: String, info: MediaInfo) {
        memory.put(key, info)
        withContext(Dispatchers.IO) {
            runCatching {
                directory.mkdirs()
                val payload = json.encodeToString(
                    DiskEntry.serializer(),
                    DiskEntry(info = info, storedAtMillis = clock()),
                )
                // Write then rename, so a kill mid-write cannot leave a half-written entry
                // that would fail to parse on the next read.
                val temp = File(directory, "${hash(key)}.tmp")
                temp.writeText(payload)
                temp.renameTo(fileFor(key))
                trimToLimit()
            }
        }
    }

    override suspend fun invalidate(key: String) {
        memory.invalidate(key)
        withContext(Dispatchers.IO) { fileFor(key).delete() }
    }

    override suspend fun clear() {
        memory.clear()
        withContext(Dispatchers.IO) { directory.listFiles()?.forEach { it.delete() } }
    }

    /** Total bytes the cache occupies, for the settings screen. */
    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        directory.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun trimToLimit() {
        val files = directory.listFiles()?.filter { it.extension == "json" } ?: return
        if (files.size <= maxEntries) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxEntries)
            .forEach { it.delete() }
    }

    private fun ttlFor(info: MediaInfo) = when {
        info.isLive -> CacheTtl.LIVE
        info.isDownloadable -> CacheTtl.COMPLETE
        else -> CacheTtl.PREVIEW
    }

    private fun fileFor(key: String) = File(directory, "${hash(key)}.json")

    /** URLs contain characters a filename cannot, so the key is hashed rather than escaped. */
    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

    @Serializable
    private data class DiskEntry(
        val info: MediaInfo,
        val storedAtMillis: Long,
    )
}
