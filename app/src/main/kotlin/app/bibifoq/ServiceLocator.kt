package app.bibifoq

import android.content.Context
import androidx.room.Room
import app.bibifoq.core.net.FileCookieStore
import app.bibifoq.core.net.HttpEngine
import app.bibifoq.core.resolver.MediaResolver
import app.bibifoq.core.resolver.NativeExtractor
import app.bibifoq.core.resolver.ResolverConfig
import app.bibifoq.core.resolver.extractors.DirectMediaExtractor
import app.bibifoq.core.resolver.extractors.OEmbedExtractor
import app.bibifoq.core.resolver.extractors.StructuredDataExtractor
import app.bibifoq.data.BibifoqDatabase
import app.bibifoq.data.DiskMetadataCache
import app.bibifoq.data.SettingsStore
import app.bibifoq.download.DownloadCoordinator
import app.bibifoq.download.EngineDownloader
import app.bibifoq.engine.YtDlpEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled dependency graph.
 *
 * A framework would earn its keep on a larger surface; here the whole graph is a dozen objects
 * with obvious lifetimes, and keeping it explicit means the build has no annotation processor
 * in the critical path and the wiring is readable in one screen.
 */
class ServiceLocator(context: Context) {

    private val appContext = context.applicationContext

    /** Outlives any screen: prefetches and downloads must survive navigation. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Sessions for sites that will not serve video to a signed-out visitor.
     *
     * Deliberately one store for the whole app: the same file backs the HTTP client's cookie
     * jar and the engine's `--cookies`, so signing in once works on both paths.
     */
    val cookies: FileCookieStore by lazy {
        FileCookieStore(File(appContext.filesDir, "cookies/cookies.txt"))
    }

    val httpEngine: HttpEngine by lazy {
        HttpEngine(HttpEngine.defaultClient(cookieJar = cookies))
    }

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    val database: BibifoqDatabase by lazy {
        Room.databaseBuilder(appContext, BibifoqDatabase::class.java, "bibifoq.db")
            .addMigrations(BibifoqDatabase.MIGRATION_1_2)
            // Only as a last resort, if a future schema arrives without a migration.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    val metadataCache: DiskMetadataCache by lazy {
        DiskMetadataCache(File(appContext.filesDir, "metadata-cache"))
    }

    val ytDlpEngine: YtDlpEngine by lazy { YtDlpEngine(appContext, cookies) }

    private val extractors: List<NativeExtractor> by lazy {
        listOf(
            DirectMediaExtractor(httpEngine),
            OEmbedExtractor(httpEngine),
            StructuredDataExtractor(),
        )
    }

    val resolver: MediaResolver by lazy {
        MediaResolver(
            httpEngine = httpEngine,
            extractors = extractors,
            cache = metadataCache,
            remoteEngine = ytDlpEngine,
            config = ResolverConfig(),
            backgroundScope = applicationScope,
        )
    }

    val downloads: DownloadCoordinator by lazy {
        DownloadCoordinator(
            context = appContext,
            httpEngine = httpEngine,
            dao = database.downloads(),
            settings = settings,
            scope = applicationScope,
            engineDownloader = EngineDownloader(ytDlpEngine, cookies),
        )
    }
}
