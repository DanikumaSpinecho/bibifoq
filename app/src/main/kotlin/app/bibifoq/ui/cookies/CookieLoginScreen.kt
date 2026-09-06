package app.bibifoq.ui.cookies

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.bibifoq.R
import app.bibifoq.core.net.FileCookieStore
import app.bibifoq.core.net.NetscapeCookies
import app.bibifoq.core.resolver.UrlNormalizer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Signs in to a site inside the app and keeps the session.
 *
 * Some sites will not serve video to a signed-out visitor at all, and there is no way to get a
 * session other than actually logging in - so the app hosts a browser for it. What is kept is
 * only what the browser would send back to that site: name/value pairs, scoped to its domain.
 *
 * The saved session goes to the same `cookies.txt` the extraction engine reads, so signing in
 * once works on both the native path and the engine path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CookieLoginScreen(
    cookies: FileCookieStore,
    onClose: () -> Unit,
    onSaved: (String) -> Unit,
    onNothingFound: () -> Unit,
) {
    val context = LocalContext.current
    var address by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    // A WebView with nothing loaded paints a blank rectangle - black on a dark theme - which
    // reads as a broken screen rather than as "type an address". Keep it out of the tree until
    // there is something to show.
    var hasNavigated by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(context).apply {
            // A login page is a modern web app; without these it simply will not work.
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    loading = true
                    currentUrl = url
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loading = false
                    currentUrl = url
                }
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    BackHandler(enabled = webView.canGoBack()) { webView.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cookie_login_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val url = currentUrl ?: return@TextButton
                            val saved = saveSession(cookies, url)
                            if (saved != null) onSaved(saved) else onNothingFound()
                        },
                        enabled = currentUrl != null,
                    ) {
                        Text(stringResource(R.string.cookie_login_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.cookie_login_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text(stringResource(R.string.cookie_login_url)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = ImeAction.Go,
                        ),
                    )
                    Button(
                        onClick = {
                            UrlNormalizer.normalize(address)?.let { url ->
                                hasNavigated = true
                                webView.loadUrl(url)
                            }
                        },
                        enabled = address.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.cookie_login_open))
                    }
                }
            }

            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (hasNavigated) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                StartingPoint(onPick = { suggestion ->
                    address = suggestion
                    hasNavigated = true
                    UrlNormalizer.normalize(suggestion)?.let(webView::loadUrl)
                })
            }
        }
    }
}

/**
 * What the screen shows before anything is loaded.
 *
 * An empty browser is not a useful starting point, so this explains the flow and offers to
 * start from the address already in the clipboard-free case: the site the user came here for.
 */
@Composable
private fun StartingPoint(onPick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.Language,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.cookie_login_start_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.cookie_login_start_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Harvests the session for [url]'s host and stores it.
 *
 * @return the domain signed in to, or null when the page carried no cookies at all.
 */
private fun saveSession(cookies: FileCookieStore, url: String): String? {
    val manager = CookieManager.getInstance()
    // Cookies can still be sitting in the in-memory store; without this the newest ones
    // - which is to say the session that was just established - are missed.
    manager.flush()

    val host = url.toHttpUrlOrNull()?.host ?: return null
    val header = manager.getCookie(url)?.takeIf { it.isNotBlank() } ?: return null

    val stored = NetscapeCookies.fromBrowserCookieHeader(
        host = host,
        header = header,
        // A WebView reports no expiry, so one is chosen: long enough to be useful, finite so a
        // stale session eventually stops being offered as if it were current.
        expiresAtSeconds = System.currentTimeMillis() / 1000 + SESSION_LIFETIME_SECONDS,
    )
    if (stored.isEmpty()) return null

    val domain = host.removePrefix("www.")
    cookies.replaceForDomain(domain, stored)
    return domain
}

/** Six months: past what most sessions live, short of pretending they last forever. */
private const val SESSION_LIFETIME_SECONDS = 180L * 24 * 60 * 60
