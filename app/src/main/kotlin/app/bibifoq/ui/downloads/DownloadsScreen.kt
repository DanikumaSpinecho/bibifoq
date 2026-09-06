package app.bibifoq.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bibifoq.R
import app.bibifoq.data.DownloadRecord
import app.bibifoq.data.DownloadState
import kotlinx.coroutines.flow.Flow

@Composable
fun DownloadsScreen(
    downloads: Flow<List<DownloadRecord>>,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val records by downloads.collectAsStateWithLifecycle(initialValue = emptyList())

    if (records.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.downloads_empty))
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onClearFinished) { Text(stringResource(R.string.clear)) }
        }
        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(records, key = { it.id }) { record ->
                DownloadRow(record, onCancel, onRemove)
            }
        }
    }
}

@Composable
private fun DownloadRow(
    record: DownloadRecord,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        record.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = statusLine(record),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (record.state == DownloadState.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                when (record.state) {
                    DownloadState.RUNNING, DownloadState.QUEUED ->
                        IconButton(onClick = { onCancel(record.id) }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    else ->
                        IconButton(onClick = { onRemove(record.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                        }
                }
            }

            if (record.state == DownloadState.RUNNING) {
                val fraction = record.fraction
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    // No total means no honest percentage; an indeterminate bar says so.
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun statusLine(record: DownloadRecord): String {
    val size = record.totalBytes
    val megabytes = { bytes: Long -> "%.1f MB".format(bytes / 1024.0 / 1024.0) }
    return when (record.state) {
        DownloadState.QUEUED -> "Queued · ${record.formatLabel}"
        DownloadState.RUNNING -> if (size != null) {
            "${megabytes(record.downloadedBytes)} of ${megabytes(size)} · ${record.formatLabel}"
        } else {
            "${megabytes(record.downloadedBytes)} · ${record.formatLabel}"
        }
        DownloadState.COMPLETED -> "Done · ${megabytes(record.downloadedBytes)} · ${record.formatLabel}"
        DownloadState.CANCELLED -> "Cancelled"
        DownloadState.FAILED -> record.errorMessage ?: "Failed"
    }
}
