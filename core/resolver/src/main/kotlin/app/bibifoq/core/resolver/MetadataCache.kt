package app.bibifoq.core.resolver

import app.bibifoq.core.model.MediaInfo
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Remembers what a URL resolved to.
 *
 * Re-resolving a URL the user already looked at a minute ago is the single most common
 * redundant work in a downloader: people paste, back out, re-share, retry. A cache hit turns a
 * multi-second extraction into a few microseconds.
 */
interface MetadataCache {
    suspend fun get(key: String): CachedInfo?
    suspend fun put(key: String, info: MediaInfo)
    suspend fun invalidate(key: String)
    suspend fun clear()
}

data class CachedInfo(
    val info: MediaInfo,
    val storedAtMillis: Long,
) {
    fun isFresh(now: Long, ttl: Duration): Boolean = now - storedAtMillis < ttl.inWholeMilliseconds
}

/**
 * Default TTLs.
 *
 * Format URLs are usually signed and expire, so a complete entry is trusted for less time than
 * the descriptive metadata a preview holds - a stale title is harmless, a stale format URL is a
 * failed download.
 */
object CacheTtl {
    val COMPLETE: Duration = 30.minutes
    val PREVIEW: Duration = 6.hours
    val LIVE: Duration = 1.minutes
}

/**
 * Bounded in-memory cache with least-recently-used eviction.
 *
 * Backed by a [ConcurrentHashMap] plus a separate access-order list, so reads do not contend
 * on a single lock; only the bookkeeping does.
 */
class InMemoryMetadataCache(
    private val maxEntries: Int = 128,
    private val clock: () -> Long = System::currentTimeMillis,
) : MetadataCache {

    private val entries = ConcurrentHashMap<String, CachedInfo>()
    private val accessOrder = LinkedHashSet<String>()
    private val lock = Any()

    override suspend fun get(key: String): CachedInfo? {
        val hit = entries[key] ?: return null
        val ttl = ttlFor(hit.info)
        if (!hit.isFresh(clock(), ttl)) {
            invalidate(key)
            return null
        }
        synchronized(lock) {
            accessOrder.remove(key)
            accessOrder.add(key)
        }
        return hit
    }

    override suspend fun put(key: String, info: MediaInfo) {
        entries[key] = CachedInfo(info, clock())
        synchronized(lock) {
            accessOrder.remove(key)
            accessOrder.add(key)
            while (accessOrder.size > maxEntries) {
                val oldest = accessOrder.firstOrNull() ?: break
                accessOrder.remove(oldest)
                entries.remove(oldest)
            }
        }
    }

    override suspend fun invalidate(key: String) {
        entries.remove(key)
        synchronized(lock) { accessOrder.remove(key) }
    }

    override suspend fun clear() {
        entries.clear()
        synchronized(lock) { accessOrder.clear() }
    }

    val size: Int get() = entries.size

    private fun ttlFor(info: MediaInfo): Duration = when {
        info.isLive -> CacheTtl.LIVE
        info.isDownloadable -> CacheTtl.COMPLETE
        else -> CacheTtl.PREVIEW
    }
}

/** Cache implementation for tests and for builds that want resolution never to be reused. */
object NoOpMetadataCache : MetadataCache {
    override suspend fun get(key: String): CachedInfo? = null
    override suspend fun put(key: String, info: MediaInfo) = Unit
    override suspend fun invalidate(key: String) = Unit
    override suspend fun clear() = Unit
}
