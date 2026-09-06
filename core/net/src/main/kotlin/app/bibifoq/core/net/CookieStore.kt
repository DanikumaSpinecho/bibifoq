package app.bibifoq.core.net

import java.io.File
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Cookies for sites that will not serve video without a session, backed by a single
 * `cookies.txt` on disk.
 *
 * One file, two consumers: this [CookieJar] for the app's own requests, and the extraction
 * engine via `--cookies`. Sharing the file is what keeps a signed-in site working on both the
 * fast native path and the engine path.
 */
class FileCookieStore(
    private val file: File,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : CookieJar {

    @Volatile
    private var cached: List<StoredCookie>? = null

    private val lock = Any()

    /** The file the extraction engine should be pointed at, or null when there is nothing yet. */
    fun fileOrNull(): File? = file.takeIf { it.isFile && all().isNotEmpty() }

    fun all(): List<StoredCookie> {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val parsed = runCatching {
                if (file.isFile) NetscapeCookies.parse(file.readText()) else emptyList()
            }.getOrDefault(emptyList())
            cached = parsed
            return parsed
        }
    }

    /** Distinct registrable-ish domains we hold cookies for, for the settings screen. */
    fun domains(): List<String> =
        all().map { it.domain.removePrefix(".") }.distinct().sorted()

    fun replaceForDomain(domain: String, cookies: List<StoredCookie>) {
        val owner = domain.removePrefix(".").lowercase()
        synchronized(lock) {
            val kept = all().filterNot { it.domain.removePrefix(".").lowercase() == owner }
            write(kept + cookies)
        }
    }

    fun removeDomain(domain: String) = replaceForDomain(domain, emptyList())

    fun clear() {
        synchronized(lock) { write(emptyList()) }
    }

    private fun write(cookies: List<StoredCookie>) {
        file.parentFile?.mkdirs()
        runCatching {
            // Write then rename: a half-written cookie file is worse than none, because it
            // silently signs the user out of everything.
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(NetscapeCookies.serialize(cookies))
            if (!temp.renameTo(file)) {
                file.writeText(NetscapeCookies.serialize(cookies))
                temp.delete()
            }
        }
        cached = cookies
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = clock()
        return all()
            .asSequence()
            .filter { !it.isExpired(now) }
            .filter { it.matchesHost(url.host) }
            .filter { it.matchesPath(url.encodedPath) }
            .filter { !it.secure || url.isHttps }
            .mapNotNull { stored ->
                Cookie.Builder()
                    .name(stored.name)
                    .value(stored.value)
                    .domain(stored.domain.removePrefix("."))
                    .path(stored.path)
                    .apply {
                        if (stored.includeSubdomains) {
                            // Builder.domain() already covers subdomains; hostOnlyDomain does not.
                        } else {
                            hostOnlyDomain(stored.domain.removePrefix("."))
                        }
                        if (stored.secure) secure()
                        if (stored.httpOnly) httpOnly()
                        if (stored.expiresAtSeconds > 0) expiresAt(stored.expiresAtSeconds * 1000)
                    }
                    .runCatching { build() }
                    .getOrNull()
            }
            .toList()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        // Only persist what a site asked us to keep. Session cookies stay in memory for the
        // life of the process via the returned list; writing them would outlive their meaning.
        val durable = cookies.filter { it.persistent }
        if (durable.isEmpty()) return

        synchronized(lock) {
            val incoming = durable.map { it.toStored() }
            val keys = incoming.map { it.domain to it.name }.toSet()
            val kept = all().filterNot { (it.domain to it.name) in keys }
            write(kept + incoming)
        }
    }

    private fun Cookie.toStored() = StoredCookie(
        domain = if (hostOnly) domain else ".$domain",
        includeSubdomains = !hostOnly,
        path = path,
        secure = secure,
        expiresAtSeconds = expiresAt / 1000,
        name = name,
        value = value,
        httpOnly = httpOnly,
    )
}
