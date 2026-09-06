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
            maxConnections = preferences[MAX_CONNECTIONS] ?: DEFAULT_CONNECTIONS,
            prefetchFromClipboard = preferences[PREFETCH] ?: true,
            maxHeight = preferences[MAX_HEIGHT]?.takeIf { it > 0 },
            audioOnly = preferences[AUDIO_ONLY] ?: false,
            filenameTemplate = preferences[TEMPLATE] ?: FileNamer.DEFAULT_TEMPLATE,
        )
    }

    suspend fun setMaxConnections(value: Int) = context.dataStore.edit {
        it[MAX_CONNECTIONS] = value.coerceIn(1, 16)
    }

    suspend fun setPrefetch(enabled: Boolean) = context.dataStore.edit { it[PREFETCH] = enabled }

    suspend fun setMaxHeight(value: Int?) = context.dataStore.edit {
        it[MAX_HEIGHT] = value ?: 0
    }

    suspend fun setAudioOnly(enabled: Boolean) = context.dataStore.edit { it[AUDIO_ONLY] = enabled }

    suspend fun setFilenameTemplate(template: String) = context.dataStore.edit {
        it[TEMPLATE] = template.ifBlank { FileNamer.DEFAULT_TEMPLATE }
    }

    data class Settings(
        val maxConnections: Int = DEFAULT_CONNECTIONS,
        val prefetchFromClipboard: Boolean = true,
        val maxHeight: Int? = null,
        val audioOnly: Boolean = false,
        val filenameTemplate: String = FileNamer.DEFAULT_TEMPLATE,
    ) {
        fun formatPreference() = FormatPreference(
            mode = if (audioOnly) FormatPreference.Mode.AUDIO_ONLY else FormatPreference.Mode.VIDEO,
            maxHeight = maxHeight,
        )
    }

    private companion object {
        const val DEFAULT_CONNECTIONS = 6
        val MAX_CONNECTIONS = intPreferencesKey("max_connections")
        val PREFETCH = booleanPreferencesKey("prefetch_clipboard")
        val MAX_HEIGHT = intPreferencesKey("max_height")
        val AUDIO_ONLY = booleanPreferencesKey("audio_only")
        val TEMPLATE = stringPreferencesKey("filename_template")
    }
}
