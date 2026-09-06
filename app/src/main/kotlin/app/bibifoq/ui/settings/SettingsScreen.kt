package app.bibifoq.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bibifoq.R
import app.bibifoq.core.downloader.FileNamer
import app.bibifoq.core.model.Completeness
import app.bibifoq.core.model.MediaFormat
import app.bibifoq.core.model.MediaInfo
import app.bibifoq.core.model.Protocol
import app.bibifoq.core.model.Provenance
import app.bibifoq.data.EngineChannel
import app.bibifoq.data.SettingsStore
import app.bibifoq.data.ThemeChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settings: SettingsStore,
    scope: CoroutineScope,
    engineDescription: String,
    signedInSites: List<String>,
    cacheSize: String,
    onUpdateEngine: () -> Unit,
    onClearCache: () -> Unit,
    onAddAccount: () -> Unit,
    onSignOut: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by settings.settings.collectAsStateWithLifecycle(
        initialValue = SettingsStore.Settings(),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionHeader(stringResource(R.string.settings_quality))

        Setting(stringResource(R.string.settings_max_height)) {
            ChipRow(
                options = SettingsStore.QUALITY_STEPS,
                selected = current.maxHeight,
                label = { height ->
                    height?.let { "${it}p" } ?: stringResource(R.string.settings_quality_best)
                },
                onSelect = { height -> scope.launch { settings.setMaxHeight(height) } },
            )
        }

        SwitchSetting(
            title = stringResource(R.string.settings_audio_only),
            summary = stringResource(R.string.settings_audio_only_summary),
            checked = current.audioOnly,
            onChange = { enabled -> scope.launch { settings.setAudioOnly(enabled) } },
        )

        if (current.audioOnly) {
            Setting(stringResource(R.string.settings_audio_container)) {
                ChipRow(
                    options = SettingsStore.AUDIO_CONTAINERS,
                    selected = current.audioContainer,
                    label = { it },
                    onSelect = { container -> scope.launch { settings.setAudioContainer(container) } },
                )
            }
        }

        SectionHeader(stringResource(R.string.settings_files))
        FilenameSetting(current.filenameTemplate) { template ->
            scope.launch { settings.setFilenameTemplate(template) }
        }

        SectionHeader(stringResource(R.string.settings_transfer))
        SliderSetting(
            title = stringResource(R.string.settings_max_connections),
            summary = stringResource(R.string.settings_max_connections_summary),
            value = current.maxConnections,
            range = 1..12,
            onCommit = { value -> scope.launch { settings.setMaxConnections(value) } },
        )
        SliderSetting(
            title = stringResource(R.string.settings_concurrent),
            summary = null,
            value = current.concurrentDownloads,
            range = 1..6,
            onCommit = { value -> scope.launch { settings.setConcurrentDownloads(value) } },
        )
        SwitchSetting(
            title = stringResource(R.string.settings_prefetch),
            summary = stringResource(R.string.settings_prefetch_summary),
            checked = current.prefetchFromClipboard,
            onChange = { enabled -> scope.launch { settings.setPrefetch(enabled) } },
        )

        SectionHeader(stringResource(R.string.settings_accounts))
        Text(
            stringResource(R.string.settings_accounts_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (signedInSites.isEmpty()) {
            Text(
                stringResource(R.string.settings_no_accounts),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            signedInSites.forEach { site ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(site, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { onSignOut(site) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.settings_sign_out),
                        )
                    }
                }
            }
        }
        OutlinedButton(onClick = onAddAccount, modifier = Modifier.padding(top = 4.dp)) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  " + stringResource(R.string.settings_add_account))
        }

        SectionHeader(stringResource(R.string.settings_appearance))
        Setting(stringResource(R.string.settings_theme)) {
            ChipRow(
                options = ThemeChoice.entries.toList(),
                selected = current.theme,
                label = { choice ->
                    when (choice) {
                        ThemeChoice.SYSTEM -> stringResource(R.string.theme_system)
                        ThemeChoice.LIGHT -> stringResource(R.string.theme_light)
                        ThemeChoice.DARK -> stringResource(R.string.theme_dark)
                    }
                },
                onSelect = { choice -> scope.launch { settings.setTheme(choice) } },
            )
        }

        SectionHeader(stringResource(R.string.settings_engine))
        Text(engineDescription, style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.settings_engine_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Setting(stringResource(R.string.settings_engine_channel)) {
            ChipRow(
                options = EngineChannel.entries.toList(),
                selected = current.engineChannel,
                label = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                onSelect = { channel -> scope.launch { settings.setEngineChannel(channel) } },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = onUpdateEngine) { Text(stringResource(R.string.settings_update_engine)) }
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text(
            stringResource(R.string.settings_cache_size, cacheSize),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onClearCache, modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)) {
            Text(stringResource(R.string.settings_clear_cache))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Setting(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        content()
    }
}

@Composable
private fun SwitchSetting(
    title: String,
    summary: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            summary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderSetting(
    title: String,
    summary: String?,
    value: Int,
    range: IntRange,
    onCommit: (Int) -> Unit,
) {
    // Local state while dragging, committed on release: writing on every frame would hammer
    // the preference store for values the user is only passing through.
    var live by remember(value) { mutableStateOf(value.toFloat()) }

    Column(Modifier.padding(vertical = 6.dp)) {
        Text("$title: ${live.toInt()}", style = MaterialTheme.typography.bodyLarge)
        summary?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = live,
            onValueChange = { live = it },
            onValueChangeFinished = { onCommit(live.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
        )
    }
}

@Composable
private fun FilenameSetting(template: String, onCommit: (String) -> Unit) {
    var text by remember(template) { mutableStateOf(template) }

    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.settings_filename_template)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Showing the result beats explaining the syntax: a template is easier to judge from
        // the name it produces than from its placeholders.
        Text(
            stringResource(R.string.settings_filename_preview, previewFilename(text)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.settings_filename_help),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { onCommit(text) }, enabled = text != template) {
            Text(stringResource(R.string.save))
        }
    }
}

private fun previewFilename(template: String): String = runCatching {
    FileNamer.render(SAMPLE_INFO, SAMPLE_FORMAT, template)
}.getOrElse { "—" }

private val SAMPLE_INFO = MediaInfo(
    sourceUrl = "https://example.com/v/1",
    id = "abc123",
    title = "Le Voyage",
    uploader = "Studio Example",
    uploadDate = "2026-03-11",
    extractor = "sample",
    provenance = Provenance.NATIVE,
    completeness = Completeness.COMPLETE,
)

private val SAMPLE_FORMAT = MediaFormat(
    id = "137",
    url = "https://example.com/v.mp4",
    protocol = Protocol.HTTPS,
    container = "mp4",
    height = 1080,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
                leadingIcon = if (isSelected) {
                    {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}
