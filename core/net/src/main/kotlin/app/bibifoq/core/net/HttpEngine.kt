package app.bibifoq.core.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * The single HTTP client the whole app shares.
 *
 * One client means one connection pool, which is the point: metadata resolution, thumbnail
 * loading and the downloader all hit the same hosts, and reusing a warm TLS connection saves
 * a full handshake (typically 100-300 ms on mobile) on every request after the first.
 */
class HttpEngine(
    /** Injectable so tests and the Android build can supply their own configured client. */
    val client: OkHttpClient = defaultClient(),
) {
    /** Runs [request], suspending until headers are available. The body is *not* read. */
    suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                // If the caller went away between dispatch and delivery, nobody will ever
                // close this body, so drop it here rather than leaking the connection.
                if (cont.isActive) cont.resume(response) else response.closeQuietly()
            }
        })
    }

    /**
     * Opens a connection to [url]'s host without asking for anything, so the DNS lookup and
     * TLS handshake are already done by the time the user actually triggers a resolve.
     *
     * Failures are swallowed on purpose: this is an optimisation, never a precondition.
     */
    suspend fun prewarm(url: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", UserAgents.DESKTOP)
                    .build()
                execute(request).closeQuietly()
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    fun cancelAll() = client.dispatcher.cancelAll()

    companion object {
        fun defaultClient(cookieJar: CookieJar = CookieJar.NO_COOKIES): OkHttpClient =
            OkHttpClient.Builder()
                .cookieJar(cookieJar)
                // Short connect timeout: on a flaky mobile link, failing over to the next tier
                // beats waiting out a stalled TCP handshake.
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                // Hold connections open long enough to be reused across a resolve-then-download
                // sequence, which is the common path.
                .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .dispatcher(
                    Dispatcher().apply {
                        maxRequests = 48
                        maxRequestsPerHost = 12
                    },
                )
                .build()
    }
}

/** Closing a response can itself throw; nothing useful can be done about it. */
fun Response.closeQuietly() {
    runCatching { close() }
}
