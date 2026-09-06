package app.bibifoq

import android.content.Context
import androidx.room.Room
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

    val httpEngine: HttpEngine by lazy { HttpEngine() }

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    val database: BibifoqDatabase by lazy {
        Room.databaseBuilder(appContext, BibifoqDatabase::class.java, "bibifoq.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    val metadataCache: DiskMetadataCache by lazy {
        DiskMetadataCache(File(appContext.filesDir, "metadata-cache"))
    }

    val ytDlpEngine: YtDlpEngine by lazy { YtDlpEngine(appContext) }

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
        )
    }
}
