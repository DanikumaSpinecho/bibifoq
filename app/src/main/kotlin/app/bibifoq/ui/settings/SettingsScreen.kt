package app.bibifoq.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import app.bibifoq.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settings: SettingsStore,
    scope: CoroutineScope,
    engineDescription: String,
    onUpdateEngine: () -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by settings.settings.collectAsStateWithLifecycle(
        initialValue = SettingsStore.Settings(),
    )
    var connections by remember(current.maxConnections) {
        mutableStateOf(current.maxConnections.toFloat())
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(stringResource(R.string.settings_engine), style = MaterialTheme.typography.titleSmall)
            Text(engineDescription, style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onUpdateEngine) { Text(stringResource(R.string.settings_update_engine)) }
            Button(onClick = onClearCache) { Text(stringResource(R.string.settings_clear_cache)) }
        }

        Column {
            Text(
                "${stringResource(R.string.settings_max_connections)}: ${connections.toInt()}",
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(
                value = connections,
                onValueChange = { connections = it },
                onValueChangeFinished = {
                    scope.launch { settings.setMaxConnections(connections.toInt()) }
                },
                valueRange = 1f..12f,
                steps = 10,
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_prefetch),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.settings_prefetch_summary),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = current.prefetchFromClipboard,
                onCheckedChange = { enabled -> scope.launch { settings.setPrefetch(enabled) } },
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.audio_only), style = MaterialTheme.typography.titleSmall)
            Switch(
                checked = current.audioOnly,
                onCheckedChange = { enabled -> scope.launch { settings.setAudioOnly(enabled) } },
            )
        }
    }
}
