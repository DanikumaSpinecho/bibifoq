package app.bibifoq.core.model

import kotlinx.serialization.Serializable

/** What the user asked for, independent of any particular site's format list. */
@Serializable
data class FormatPreference(
    val mode: Mode = Mode.VIDEO,
    /** Cap on vertical resolution; null means "best available". */
    val maxHeight: Int? = null,
    /** Preferred containers in order; the first that matches wins. */
    val preferredContainers: List<String> = listOf("mp4", "webm", "mkv"),
    val preferredAudioContainers: List<String> = listOf("m4a", "opus", "mp3"),
    /** Allow pairing a video-only and an audio-only stream (needs a mux step). */
    val allowMuxing: Boolean = true,
) {
    @Serializable
    enum class Mode { VIDEO, AUDIO_ONLY }
}

/** The concrete stream(s) chosen for a download. */
data class FormatSelection(
    val video: MediaFormat?,
    val audio: MediaFormat?,
) {
    init {
        require(video != null || audio != null) { "a selection needs at least one stream" }
    }

    /** True when the two streams have to be merged into one container after download. */
    val requiresMuxing: Boolean get() = video != null && audio != null

    val totalBytes: Long?
        get() {
            val v = video?.filesizeBytes
            val a = audio?.filesizeBytes
            return when {
                video != null && audio != null -> if (v != null && a != null) v + a else null
                else -> v ?: a
            }
        }

    companion object {
        /**
         * Picks streams out of [formats] for [preference].
         *
         * Deliberately total: it never throws for a non-empty list, because a resolver that
         * returned *something* should always be downloadable, even if the metadata is thin.
         */
        fun choose(formats: List<MediaFormat>, preference: FormatPreference): FormatSelection? {
            if (formats.isEmpty()) return null

            val audioOnly = formats.filter { it.kind == FormatKind.AUDIO_ONLY }
            val videoOnly = formats.filter { it.kind == FormatKind.VIDEO_ONLY }
            val muxed = formats.filter { it.kind == FormatKind.MUXED }

            if (preference.mode == FormatPreference.Mode.AUDIO_ONLY) {
                val best = audioOnly.maxWithOrNull(audioComparator(preference))
                    ?: muxed.maxWithOrNull(audioComparator(preference))
                    ?: formats.first()
                return FormatSelection(video = null, audio = best)
            }

            val cap = preference.maxHeight
            fun within(f: MediaFormat) = cap == null || (f.height ?: 0) <= cap

            val bestMuxed = muxed.filter(::within).maxWithOrNull(videoComparator(preference))
            val bestVideoOnly = videoOnly.filter(::within).maxWithOrNull(videoComparator(preference))

            // Prefer the adaptive pair when it is genuinely better than the muxed stream,
            // because video-only ladders usually go higher than progressive ones.
            if (preference.allowMuxing && bestVideoOnly != null) {
                val bestAudio = audioOnly.maxWithOrNull(audioComparator(preference))
                val muxedHeight = bestMuxed?.height ?: 0
                if (bestAudio != null && (bestVideoOnly.height ?: 0) > muxedHeight) {
                    return FormatSelection(bestVideoOnly, bestAudio)
                }
            }

            val fallback = bestMuxed
                ?: muxed.maxWithOrNull(videoComparator(preference))
                ?: bestVideoOnly
                ?: videoOnly.maxWithOrNull(videoComparator(preference))
                ?: formats.first()
            return FormatSelection(fallback, null)
        }

        private fun videoComparator(pref: FormatPreference): Comparator<MediaFormat> =
            compareBy<MediaFormat> { it.height ?: 0 }
                .thenBy { it.frameRate ?: 0.0 }
                .thenBy { containerRank(it.container, pref.preferredContainers) }
                .thenBy { it.bitrateBps ?: 0L }

        private fun audioComparator(pref: FormatPreference): Comparator<MediaFormat> =
            compareBy<MediaFormat> { containerRank(it.container, pref.preferredAudioContainers) }
                .thenBy { it.bitrateBps ?: 0L }
                .thenBy { it.sampleRateHz ?: 0 }

        /** Higher is better: an unlisted container ranks below every listed one. */
        private fun containerRank(container: String?, preferred: List<String>): Int {
            val index = preferred.indexOfFirst { it.equals(container, ignoreCase = true) }
            return if (index < 0) -1 else preferred.size - index
        }
    }
}
