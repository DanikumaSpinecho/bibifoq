package app.bibifoq.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bibifoq.ServiceLocator
import app.bibifoq.core.model.FormatSelection
import app.bibifoq.core.model.MediaFormat
import app.bibifoq.core.model.MediaInfo
import app.bibifoq.core.model.Provenance
import app.bibifoq.core.resolver.ResolveUpdate
import kotlin.time.Duration
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the home screen.
 *
 * The state deliberately keeps [HomeUiState.info] and [HomeUiState.isComplete] separate, so
 * the card renders from a preview while the format list is still being worked out. That
 * progressive display is the whole reason the resolver streams its results.
 */
class HomeViewModel(private val services: ServiceLocator) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var resolveJob: Job? = null

    fun onUrlChanged(url: String) {
        _state.update { it.copy(url = url) }
    }

    /**
     * Called when a URL merely appears - shared into the app, or spotted on the clipboard.
     *
     * Resolution starts before the user asks for it, so the answer is usually already cached
     * by the time they tap anything.
     */
    fun onUrlSeen(url: String) {
        _state.update { it.copy(url = url) }
        viewModelScope.launch {
            if (services.settings.settings.first().prefetchFromClipboard) {
                services.resolver.prefetch(url)
            }
        }
    }

    fun resolve(url: String = _state.value.url) {
        if (url.isBlank()) return
        resolveJob?.cancel()
        _state.update {
            it.copy(
                url = url,
                phase = ResolvePhase.RESOLVING,
                info = null,
                error = null,
                selectedFormat = null,
                previewElapsed = null,
                completeElapsed = null,
            )
        }

        resolveJob = viewModelScope.launch {
            services.resolver.resolve(url).collect { update ->
                when (update) {
                    is ResolveUpdate.Started -> Unit

                    is ResolveUpdate.Partial -> _state.update {
                        it.copy(
                            phase = ResolvePhase.PREVIEW,
                            info = update.info,
                            previewElapsed = it.previewElapsed ?: update.elapsed,
                        )
                    }

                    is ResolveUpdate.Complete -> _state.update {
                        it.copy(
                            phase = ResolvePhase.COMPLETE,
                            info = update.info,
                            winner = update.winner,
                            completeElapsed = update.elapsed,
                            previewElapsed = it.previewElapsed ?: update.elapsed,
                            selectedFormat = defaultFormat(update.info),
                        )
                    }

                    is ResolveUpdate.Failed -> _state.update {
                        it.copy(phase = ResolvePhase.FAILED, error = update.error.message)
                    }
                }
            }
        }
    }

    fun selectFormat(format: MediaFormat) {
        _state.update { it.copy(selectedFormat = format) }
    }

    fun download() {
        val current = _state.value
        val info = current.info ?: return
        val preferences = current.selectedFormat

        viewModelScope.launch {
            val settings = services.settings.settings.first()
            val selection = if (preferences != null) {
                // An explicit pick still needs an audio track when it is a video-only stream.
                val audio = info.formats.firstOrNull { it.kind == app.bibifoq.core.model.FormatKind.AUDIO_ONLY }
                if (preferences.hasVideo && !preferences.hasAudio && audio != null) {
                    FormatSelection(preferences, audio)
                } else if (preferences.hasVideo) {
                    FormatSelection(preferences, null)
                } else {
                    FormatSelection(null, preferences)
                }
            } else {
                FormatSelection.choose(info.formats, settings.formatPreference())
            }

            if (selection == null) {
                _state.update { it.copy(error = "This item exposes no downloadable stream.") }
                return@launch
            }

            // Enqueuing writes a database row and starts a coroutine; either can fail, and a
            // silent return here reads to the user as a button that does nothing.
            runCatching { services.downloads.enqueue(info, selection) }.fold(
                onSuccess = { _state.update { it.copy(lastEnqueuedTitle = info.title) } },
                onFailure = { failure ->
                    _state.update {
                        it.copy(error = failure.message ?: "Could not start the download.")
                    }
                },
            )
        }
    }

    fun clear() {
        resolveJob?.cancel()
        _state.value = HomeUiState()
    }

    fun acknowledgeEnqueued() {
        _state.update { it.copy(lastEnqueuedTitle = null) }
    }

    private fun defaultFormat(info: MediaInfo): MediaFormat? =
        FormatSelection.choose(info.formats, app.bibifoq.core.model.FormatPreference())
            ?.let { it.video ?: it.audio }
}

data class HomeUiState(
    val url: String = "",
    val phase: ResolvePhase = ResolvePhase.IDLE,
    val info: MediaInfo? = null,
    val selectedFormat: MediaFormat? = null,
    val error: String? = null,
    val winner: Provenance? = null,
    /** Time to the first renderable result; the number the user actually feels. */
    val previewElapsed: Duration? = null,
    /** Time to the authoritative format list. */
    val completeElapsed: Duration? = null,
    val lastEnqueuedTitle: String? = null,
) {
    val isComplete: Boolean get() = phase == ResolvePhase.COMPLETE
    val isBusy: Boolean get() = phase == ResolvePhase.RESOLVING || phase == ResolvePhase.PREVIEW
}

enum class ResolvePhase { IDLE, RESOLVING, PREVIEW, COMPLETE, FAILED }
