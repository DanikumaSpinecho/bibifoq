package app.bibifoq

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bibifoq.core.resolver.UrlNormalizer
import app.bibifoq.data.SettingsStore
import app.bibifoq.download.DownloadService
import app.bibifoq.ui.cookies.CookieLoginScreen
import app.bibifoq.ui.downloads.DownloadsScreen
import app.bibifoq.ui.home.HomeScreen
import app.bibifoq.ui.home.HomeViewModel
import app.bibifoq.ui.settings.SettingsScreen
import app.bibifoq.ui.theme.BibifoqTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val services by lazy { (application as BibifoqApplication).services }
    private var pendingSharedUrl by mutableStateOf<String?>(null)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingSharedUrl = extractSharedUrl(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val settings by services.settings.settings.collectAsStateWithLifecycle(
                initialValue = SettingsStore.Settings(),
            )
            BibifoqTheme(choice = settings.theme) {
                BibifoqApp(
                    services = services,
                    sharedUrl = pendingSharedUrl,
                    onSharedUrlConsumed = { pendingSharedUrl = null },
                    readClipboard = ::readClipboardUrl,
                    onDownloadStarted = { DownloadService.start(this) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // launchMode is singleTask, so a second share arrives here rather than in onCreate.
        extractSharedUrl(intent)?.let { pendingSharedUrl = it }
    }

    /** Pulls a URL out of a share-sheet intent, tolerating the surrounding text apps add. */
    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return UrlNormalizer.extractFirstUrl(text)
            ?: text.takeIf { UrlNormalizer.normalize(it) != null }
    }

    private fun readClipboardUrl(): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return null
        return UrlNormalizer.extractFirstUrl(text) ?: text
    }
}

private enum class Tab(val titleRes: Int) {
    HOME(R.string.tab_home),
    DOWNLOADS(R.string.tab_downloads),
    SETTINGS(R.string.tab_settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BibifoqApp(
    services: ServiceLocator,
    sharedUrl: String?,
    onSharedUrlConsumed: () -> Unit,
    readClipboard: () -> String?,
    onDownloadStarted: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var showingCookieLogin by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val homeViewModel: HomeViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(services) as T
        },
    )

    var engineDescription by remember { mutableStateOf("…") }
    var signedInSites by remember { mutableStateOf(services.cookies.domains()) }
    var cacheSize by remember { mutableStateOf("—") }

    LaunchedEffect(Unit) { engineDescription = services.ytDlpEngine.describe() }
    LaunchedEffect(tab) {
        if (tab == Tab.SETTINGS) {
            signedInSites = services.cookies.domains()
            cacheSize = formatBytes(services.metadataCache.sizeBytes())
        }
    }

    // A shared link should not need a second tap: resolution starts the moment it arrives.
    LaunchedEffect(sharedUrl) {
        val url = sharedUrl ?: return@LaunchedEffect
        tab = Tab.HOME
        homeViewModel.onUrlSeen(url)
        homeViewModel.resolve(url)
        onSharedUrlConsumed()
    }

    // Starting a download means keeping the process alive for it.
    LaunchedEffect(Unit) {
        services.downloads.active.collect { count -> if (count > 0) onDownloadStarted() }
    }

    val activeDownloads by services.downloads.active.collectAsStateWithLifecycle(initialValue = 0)

    if (showingCookieLogin) {
        val queuedMessage = stringResource(R.string.cookie_login_nothing)
        CookieLoginScreen(
            cookies = services.cookies,
            onClose = { showingCookieLogin = false },
            onSaved = { domain ->
                signedInSites = services.cookies.domains()
                showingCookieLogin = false
                scope.launch { snackbar.showSnackbar("Signed in to $domain") }
            },
            onNothingFound = { scope.launch { snackbar.showSnackbar(queuedMessage) } },
        )
        return
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text(stringResource(tab.titleRes)) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.HOME,
                    onClick = { tab = Tab.HOME },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_home)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.DOWNLOADS,
                    onClick = { tab = Tab.DOWNLOADS },
                    icon = {
                        // The badge is the only signal that work is happening once the user
                        // navigates away from the list.
                        BadgedBox(
                            badge = {
                                if (activeDownloads > 0) Badge { Text(activeDownloads.toString()) }
                            },
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                        }
                    },
                    label = { Text(stringResource(R.string.tab_downloads)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.SETTINGS,
                    onClick = { tab = Tab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                )
            }
        },
    ) { padding ->
        val queuedMessage = stringResource(R.string.queued_toast)
        val deletedMessage = stringResource(R.string.deleted_file)
        val cacheClearedMessage = stringResource(R.string.cache_cleared)

        when (tab) {
            Tab.HOME -> HomeScreen(
                viewModel = homeViewModel,
                onPasteRequested = readClipboard,
                onDownloadEnqueued = {
                    tab = Tab.DOWNLOADS
                    scope.launch { snackbar.showSnackbar(queuedMessage) }
                },
                modifier = Modifier.padding(padding),
            )

            Tab.DOWNLOADS -> DownloadsScreen(
                downloads = services.downloads.observeAll(),
                onCancel = services.downloads::cancel,
                onDeleteFile = { id ->
                    scope.launch {
                        services.downloads.remove(id, deleteFile = true)
                        snackbar.showSnackbar(deletedMessage)
                    }
                },
                onRemoveEntry = { id ->
                    scope.launch { services.downloads.remove(id, deleteFile = false) }
                },
                onClearFinished = { scope.launch { services.downloads.clearFinished() } },
                modifier = Modifier.padding(padding),
            )

            Tab.SETTINGS -> SettingsScreen(
                settings = services.settings,
                scope = scope,
                engineDescription = engineDescription,
                signedInSites = signedInSites,
                cacheSize = cacheSize,
                onUpdateEngine = {
                    scope.launch {
                        val channel = services.settings.settings.first().engineChannel
                        snackbar.showSnackbar(
                            runCatching { services.ytDlpEngine.update(channel) }
                                .getOrElse { "Update failed: ${it.message}" },
                        )
                        engineDescription = services.ytDlpEngine.describe()
                    }
                },
                onClearCache = {
                    scope.launch {
                        services.metadataCache.clear()
                        cacheSize = formatBytes(services.metadataCache.sizeBytes())
                        snackbar.showSnackbar(cacheClearedMessage)
                    }
                },
                onAddAccount = { showingCookieLogin = true },
                onSignOut = { site ->
                    services.cookies.removeDomain(site)
                    signedInSites = services.cookies.domains()
                },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f kB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
