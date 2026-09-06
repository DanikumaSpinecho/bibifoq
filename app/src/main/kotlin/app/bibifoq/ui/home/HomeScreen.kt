package app.bibifoq.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bibifoq.R
import app.bibifoq.core.model.MediaFormat
import app.bibifoq.core.model.MediaInfo
import app.bibifoq.core.model.PlaylistEntry
import app.bibifoq.core.model.Provenance
import coil3.compose.AsyncImage
import kotlin.time.Duration

/**
 * The home screen.
 *
 * Layout note, learned the hard way: the download action lives in a bar pinned outside the
 * scrolling area. When it sat at the bottom of the content column, a thumbnail plus a title
 * plus a format list added up to more than a phone screen, and since the column did not
 * scroll, the button was simply clipped away - the formats were visible and tappable while the
 * only thing that starts a download was unreachable. Pinning it makes that failure impossible
 * regardless of how tall the content gets.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onPasteRequested: () -> String?,
    onDownloadEnqueued: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Enqueuing is otherwise invisible from this screen, so hand control to the downloads list
    // where the new row actually shows up.
    LaunchedEffect(state.lastEnqueuedTitle) {
        if (state.lastEnqueuedTitle != null) {
            onDownloadEnqueued()
            viewModel.acknowledgeEnqueued()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::onUrlChanged,
                label = { Text(stringResource(R.string.paste_a_link)) },
                placeholder = { Text(stringResource(R.string.url_hint)) },
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
                    Text(stringResource(R.string.resolve))
                }
                if (state.info != null || state.error != null) {
                    TextButton(onClick = viewModel::clear) {
                        Text(stringResource(R.string.clear))
                    }
                }
            }

            when {
                state.phase == ResolvePhase.IDLE && state.url.isBlank() -> HomeEmptyState()

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
                    degradedReason = state.degradedReason,
                    moreFormatsAvailable = state.moreFormatsAvailable,
                    isLoadingMoreFormats = state.isLoadingMoreFormats,
                    onSelectFormat = viewModel::selectFormat,
                    onFindMoreFormats = viewModel::findMoreFormats,
                    onOpenEntry = viewModel::resolve,
                )
            }
        }

        DownloadBar(
            selectedFormat = state.selectedFormat,
            canDownload = state.info?.isDownloadable == true,
            isResolving = state.isBusy,
            onDownload = viewModel::download,
        )
    }
}

/**
 * The pinned action area. Always on screen whenever there is anything to download, so the
 * primary action can never end up below the fold.
 */
@Composable
private fun DownloadBar(
    selectedFormat: MediaFormat?,
    canDownload: Boolean,
    isResolving: Boolean,
    onDownload: () -> Unit,
) {
    if (!canDownload) return

    Surface(tonalElevation = 3.dp) {
        Column {
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Say out loud what pressing the button will fetch; a chip's selected tint on
                // its own is too quiet to be the only feedback for a choice.
                Text(
                    text = selectedFormat
                        ?.let { stringResource(R.string.selected_format, it.label()) }
                        ?: stringResource(R.string.settings_quality_best),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onDownload,
                    enabled = !isResolving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.download))
                }
            }
        }
    }
}

@Composable
private fun HomeEmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.ContentPaste,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun ResolvingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(Modifier.size(20.dp))
        Text(stringResource(R.string.resolving), style = MaterialTheme.typography.bodyMedium)
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
    degradedReason: String?,
    moreFormatsAvailable: Boolean,
    isLoadingMoreFormats: Boolean,
    onSelectFormat: (MediaFormat) -> Unit,
    onFindMoreFormats: () -> Unit,
    onOpenEntry: (String) -> Unit,
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

            // Not decoration: this is how the tiering gets verified on a real device, and it
            // makes a latency regression visible without instrumentation.
            ProvenanceLine(winner, previewElapsed, completeElapsed, info.extractor)

            if (!isComplete) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.loading_formats),
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            degradedReason?.let { reason ->
                // Otherwise a single low-resolution stream reads as "this site has nothing
                // better", when the truth is that nothing ever looked.
                Text(
                    text = stringResource(R.string.degraded_result, reason),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (info.formats.isNotEmpty()) {
                Text(
                    stringResource(R.string.choose_format),
                    style = MaterialTheme.typography.labelLarge,
                )
                FormatChips(
                    formats = info.formats,
                    selected = selectedFormat,
                    onSelect = onSelectFormat,
                )
            }

            // Offered rather than spent: enumerating every resolution costs an interpreter
            // start, and the stream found cheaply is usually the one that was wanted.
            if (isLoadingMoreFormats) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp))
                    Text(
                        stringResource(R.string.searching_more_formats),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            } else if (moreFormatsAvailable) {
                OutlinedButton(onClick = onFindMoreFormats) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.find_more_formats))
                }
            }

            if (info.entries.isNotEmpty()) {
                Text(
                    stringResource(R.string.playlist_entries, info.entries.size),
                    style = MaterialTheme.typography.labelLarge,
                )
                info.entries.take(MAX_VISIBLE_ENTRIES).forEach { entry ->
                    PlaylistEntryRow(entry, onOpenEntry)
                }
            }
        }
    }
}

/** A page that resolved to several items: tapping one resolves that item on its own. */
@Composable
private fun PlaylistEntryRow(entry: PlaylistEntry, onOpen: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(entry.url) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        entry.thumbnailUrl?.let { thumbnail ->
            AsyncImage(
                model = thumbnail,
                contentDescription = null,
                modifier = Modifier
                    .width(72.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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

/**
 * Format choices as wrapping chips.
 *
 * A fixed-height scrolling list here was a mistake twice over: it ate 180dp whatever the format
 * count, and it put a scroll container inside a scroll container.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FormatChips(
    formats: List<MediaFormat>,
    selected: MediaFormat?,
    onSelect: (MediaFormat) -> Unit,
) {
    // Best first: that is the order people scan, and usually the one they want.
    val ordered = formats.sortedWith(
        compareByDescending<MediaFormat> { it.height ?: 0 }.thenByDescending { it.bitrateBps ?: 0 },
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ordered.forEach { format ->
            val isSelected = format.id == selected?.id && format.url == selected.url
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(format) },
                label = { Text(format.label() + sizeSuffix(format)) },
                // A tick, because the selected chip's tint alone is easy to miss and this is
                // the only signal that a tap did anything.
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

private fun sizeSuffix(format: MediaFormat): String {
    val bytes = format.filesizeBytes ?: return ""
    val megabytes = bytes / 1024.0 / 1024.0
    val approximate = if (format.filesizeApproximate) "~" else ""
    return " · $approximate%.0f MB".format(megabytes)
}

private const val MAX_VISIBLE_ENTRIES = 25
