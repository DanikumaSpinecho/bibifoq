package app.bibifoq.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bibifoq.R
import app.bibifoq.data.DownloadRecord
import app.bibifoq.data.DownloadState
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.Flow

@Composable
fun DownloadsScreen(
    downloads: Flow<List<DownloadRecord>>,
    onCancel: (String) -> Unit,
    onDeleteFile: (String) -> Unit,
    onRemoveEntry: (String) -> Unit,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val records by downloads.collectAsStateWithLifecycle(initialValue = emptyList())

    if (records.isEmpty()) {
        EmptyState(modifier)
        return
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onClearFinished) {
                Text(stringResource(R.string.clear_finished))
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(records, key = { it.id }) { record ->
                DownloadRow(record, onCancel, onDeleteFile, onRemoveEntry)
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Default.Inbox,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.downloads_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.downloads_empty_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DownloadRow(
    record: DownloadRecord,
    onCancel: (String) -> Unit,
    onDeleteFile: (String) -> Unit,
    onRemoveEntry: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val running = record.state == DownloadState.RUNNING || record.state == DownloadState.QUEUED

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                record.thumbnailUrl?.let { thumbnail ->
                    AsyncImage(
                        model = thumbnail,
                        contentDescription = null,
                        modifier = Modifier
                            .width(88.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }

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
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (running) {
                    IconButton(onClick = { onCancel(record.id) }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                    }
                } else {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            // The destructive one first because it is the one people come for:
                            // the copy that matters is the one in shared storage.
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_file)) },
                                leadingIcon = {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = null)
                                },
                                onClick = {
                                    menuOpen = false
                                    onDeleteFile(record.id)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.remove_from_list)) },
                                onClick = {
                                    menuOpen = false
                                    onRemoveEntry(record.id)
                                },
                            )
                        }
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
                    // No known total means no honest percentage; an indeterminate bar says so.
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun statusLine(record: DownloadRecord): String {
    val megabytes = { bytes: Long -> "%.1f MB".format(bytes / 1024.0 / 1024.0) }
    val total = record.totalBytes
    return when (record.state) {
        DownloadState.QUEUED -> "Queued · ${record.formatLabel}"
        DownloadState.RUNNING -> if (total != null) {
            "${megabytes(record.downloadedBytes)} of ${megabytes(total)} · ${record.formatLabel}"
        } else {
            "${megabytes(record.downloadedBytes)} · ${record.formatLabel}"
        }
        DownloadState.COMPLETED ->
            "Saved · ${megabytes(record.downloadedBytes)} · ${record.formatLabel}"
        DownloadState.CANCELLED -> "Cancelled"
        DownloadState.FAILED -> record.errorMessage ?: "Failed"
    }
}
