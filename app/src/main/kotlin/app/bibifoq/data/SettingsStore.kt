package app.bibifoq.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.bibifoq.core.downloader.FileNamer
import app.bibifoq.core.model.FormatPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** User preferences, read as a flow so the UI and the download engine never disagree. */
class SettingsStore(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { preferences ->
        Settings(
            maxHeight = preferences[MAX_HEIGHT]?.takeIf { it > 0 },
            audioOnly = preferences[AUDIO_ONLY] ?: false,
            audioContainer = preferences[AUDIO_CONTAINER] ?: DEFAULT_AUDIO_CONTAINER,
            filenameTemplate = preferences[TEMPLATE] ?: FileNamer.DEFAULT_TEMPLATE,
            maxConnections = preferences[MAX_CONNECTIONS] ?: DEFAULT_CONNECTIONS,
            concurrentDownloads = preferences[CONCURRENT] ?: DEFAULT_CONCURRENT,
            prefetchFromClipboard = preferences[PREFETCH] ?: true,
            theme = preferences[THEME]?.let { name ->
                runCatching { ThemeChoice.valueOf(name) }.getOrNull()
            } ?: ThemeChoice.SYSTEM,
            engineChannel = preferences[ENGINE_CHANNEL]?.let { name ->
                runCatching { EngineChannel.valueOf(name) }.getOrNull()
            } ?: EngineChannel.STABLE,
        )
    }

    suspend fun setMaxHeight(value: Int?) = context.dataStore.edit { it[MAX_HEIGHT] = value ?: 0 }

    suspend fun setAudioOnly(enabled: Boolean) = context.dataStore.edit { it[AUDIO_ONLY] = enabled }

    suspend fun setAudioContainer(container: String) = context.dataStore.edit {
        it[AUDIO_CONTAINER] = container
    }

    suspend fun setFilenameTemplate(template: String) = context.dataStore.edit {
        it[TEMPLATE] = template.ifBlank { FileNamer.DEFAULT_TEMPLATE }
    }

    suspend fun setMaxConnections(value: Int) = context.dataStore.edit {
        it[MAX_CONNECTIONS] = value.coerceIn(1, 16)
    }

    suspend fun setConcurrentDownloads(value: Int) = context.dataStore.edit {
        it[CONCURRENT] = value.coerceIn(1, 8)
    }

    suspend fun setPrefetch(enabled: Boolean) = context.dataStore.edit { it[PREFETCH] = enabled }

    suspend fun setTheme(choice: ThemeChoice) = context.dataStore.edit { it[THEME] = choice.name }

    suspend fun setEngineChannel(channel: EngineChannel) = context.dataStore.edit {
        it[ENGINE_CHANNEL] = channel.name
    }

    data class Settings(
        /** Null means "best available". */
        val maxHeight: Int? = null,
        val audioOnly: Boolean = false,
        val audioContainer: String = DEFAULT_AUDIO_CONTAINER,
        val filenameTemplate: String = FileNamer.DEFAULT_TEMPLATE,
        val maxConnections: Int = DEFAULT_CONNECTIONS,
        val concurrentDownloads: Int = DEFAULT_CONCURRENT,
        val prefetchFromClipboard: Boolean = true,
        val theme: ThemeChoice = ThemeChoice.SYSTEM,
        val engineChannel: EngineChannel = EngineChannel.STABLE,
    ) {
        fun formatPreference() = FormatPreference(
            mode = if (audioOnly) FormatPreference.Mode.AUDIO_ONLY else FormatPreference.Mode.VIDEO,
            maxHeight = maxHeight,
            // The chosen container goes first; the rest stay as fallbacks so a site that does
            // not offer it still yields something.
            preferredAudioContainers = listOf(audioContainer) +
                AUDIO_CONTAINERS.filterNot { it == audioContainer },
        )
    }

    companion object {
        const val DEFAULT_CONNECTIONS = 6
        const val DEFAULT_CONCURRENT = 2
        const val DEFAULT_AUDIO_CONTAINER = "m4a"

        /** Offered in settings, in the order they are preferred when the choice is unavailable. */
        val AUDIO_CONTAINERS = listOf("m4a", "opus", "mp3")

        /** Heights offered as a quality cap; null is "best available". */
        val QUALITY_STEPS: List<Int?> = listOf(null, 2160, 1440, 1080, 720, 480, 360)

        private val MAX_HEIGHT = intPreferencesKey("max_height")
        private val AUDIO_ONLY = booleanPreferencesKey("audio_only")
        private val AUDIO_CONTAINER = stringPreferencesKey("audio_container")
        private val TEMPLATE = stringPreferencesKey("filename_template")
        private val MAX_CONNECTIONS = intPreferencesKey("max_connections")
        private val CONCURRENT = intPreferencesKey("concurrent_downloads")
        private val PREFETCH = booleanPreferencesKey("prefetch_clipboard")
        private val THEME = stringPreferencesKey("theme")
        private val ENGINE_CHANNEL = stringPreferencesKey("engine_channel")
    }
}

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/** Which yt-dlp release stream the in-app update pulls from. */
enum class EngineChannel { STABLE, NIGHTLY, MASTER }
