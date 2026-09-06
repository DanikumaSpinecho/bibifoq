package app.bibifoq.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bibifoq.R
import app.bibifoq.core.model.MediaFormat
import app.bibifoq.core.model.MediaInfo
import app.bibifoq.core.model.Provenance
import coil3.compose.AsyncImage
import kotlin.time.Duration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onPasteRequested: () -> String?,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.lastEnqueuedTitle) {
        if (state.lastEnqueuedTitle != null) viewModel.acknowledgeEnqueued()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.url,
            onValueChange = viewModel::onUrlChanged,
            label = { Text(stringResourceCompat(R.string.paste_a_link)) },
            placeholder = { Text(stringResourceCompat(R.string.url_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { onPasteRequested()?.let(viewModel::onUrlChanged) }) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null)
                }
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.resolve() },
                enabled = state.url.isNotBlank() && !state.isBusy,
            ) {
                Text(stringResourceCompat(R.string.resolve))
            }
            if (state.info != null || state.error != null) {
                androidx.compose.material3.TextButton(onClick = viewModel::clear) {
                    Text(stringResourceCompat(R.string.clear))
                }
            }
        }

        when {
            state.phase == ResolvePhase.RESOLVING -> ResolvingRow()

            state.error != null -> Card(Modifier.fillMaxWidth()) {
                Text(
                    text = state.error!!,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            state.info != null -> MediaCard(
                info = state.info!!,
                isComplete = state.isComplete,
                winner = state.winner,
                previewElapsed = state.previewElapsed,
                completeElapsed = state.completeElapsed,
                selectedFormat = state.selectedFormat,
                onSelectFormat = viewModel::selectFormat,
                onDownload = viewModel::download,
            )
        }
    }
}

@Composable
private fun ResolvingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(Modifier.height(20.dp))
        Text(stringResourceCompat(R.string.resolving), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MediaCard(
    info: MediaInfo,
    isComplete: Boolean,
    winner: Provenance?,
    previewElapsed: Duration?,
    completeElapsed: Duration?,
    selectedFormat: MediaFormat?,
    onSelectFormat: (MediaFormat) -> Unit,
    onDownload: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            info.thumbnailUrl?.let { thumbnail ->
                AsyncImage(
                    model = thumbnail,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }

            Text(
                text = info.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            info.uploader?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            // The timing line is not decoration: it is how the tiering is verified on a real
            // device, and it makes a regression in resolve latency immediately visible.
            ProvenanceLine(winner, previewElapsed, completeElapsed, info.extractor)

            if (!isComplete) {
                Spacer(Modifier.height(2.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    stringResourceCompat(R.string.loading_formats),
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            if (info.formats.isNotEmpty()) {
                Text(
                    stringResourceCompat(R.string.choose_format),
                    style = MaterialTheme.typography.labelLarge,
                )
                FormatList(
                    formats = info.formats,
                    selected = selectedFormat,
                    onSelect = onSelectFormat,
                )
            }

            Button(
                onClick = onDownload,
                enabled = info.formats.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text("  " + stringResourceCompat(R.string.download))
            }
        }
    }
}

@Composable
private fun ProvenanceLine(
    winner: Provenance?,
    previewElapsed: Duration?,
    completeElapsed: Duration?,
    extractor: String,
) {
    val parts = buildList {
        previewElapsed?.let { add("preview ${it.inWholeMilliseconds} ms") }
        completeElapsed?.let { add("complete ${it.inWholeMilliseconds} ms") }
        winner?.let { add(it.name.lowercase()) }
        add(extractor)
    }
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatList(
    formats: List<MediaFormat>,
    selected: MediaFormat?,
    onSelect: (MediaFormat) -> Unit,
) {
    // Sorted best-first, because that is the order people scan and almost always the choice
    // they want.
    val ordered = formats.sortedWith(
        compareByDescending<MediaFormat> { it.height ?: 0 }.thenByDescending { it.bitrateBps ?: 0 },
    )
    LazyColumn(
        modifier = Modifier.height(180.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(ordered, key = { it.id + it.url.hashCode() }) { format ->
            FilterChip(
                selected = format.id == selected?.id,
                onClick = { onSelect(format) },
                label = { Text(format.label() + sizeSuffix(format)) },
                modifier = Modifier.clickable { onSelect(format) },
            )
        }
    }
}

private fun sizeSuffix(format: MediaFormat): String {
    val bytes = format.filesizeBytes ?: return ""
    val megabytes = bytes / 1024.0 / 1024.0
    val approximate = if (format.filesizeApproximate) "~" else ""
    return " · $approximate%.1f MB".format(megabytes)
}

@Composable
private fun stringResourceCompat(id: Int): String =
    androidx.compose.ui.res.stringResource(id)
