package app.bibifoq.core.model

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class FormatSelectionTest {

    private fun video(id: String, height: Int, container: String = "mp4", fps: Double? = null) =
        MediaFormat(
            id = id,
            url = "https://e/$id",
            protocol = Protocol.HTTPS,
            container = container,
            videoCodec = "avc1",
            width = height * 16 / 9,
            height = height,
            frameRate = fps,
            bitrateBps = height * 3000L,
        )

    private fun audio(id: String, container: String = "m4a", bitrate: Long = 128_000) =
        MediaFormat(
            id = id,
            url = "https://e/$id",
            protocol = Protocol.HTTPS,
            container = container,
            audioCodec = "mp4a",
            bitrateBps = bitrate,
        )

    private fun muxed(id: String, height: Int) = video(id, height).copy(audioCodec = "mp4a")

    @Test
    fun `prefers an adaptive pair when it beats the progressive stream`() {
        val formats = listOf(muxed("m360", 360), video("v1080", 1080), audio("a128"))

        val selection = assertNotNull(FormatSelection.choose(formats, FormatPreference()))

        assertEquals("v1080", selection.video?.id)
        assertEquals("a128", selection.audio?.id)
        assertTrue(selection.requiresMuxing)
    }

    @Test
    fun `stays with the progressive stream when muxing would not gain height`() {
        val formats = listOf(muxed("m1080", 1080), video("v720", 720), audio("a128"))

        val selection = assertNotNull(FormatSelection.choose(formats, FormatPreference()))

        assertEquals("m1080", selection.video?.id)
        assertNull(selection.audio)
        assertTrue(!selection.requiresMuxing)
    }

    @Test
    fun `respects a resolution cap`() {
        val formats = listOf(video("v2160", 2160), video("v720", 720), audio("a128"))

        val selection = assertNotNull(
            FormatSelection.choose(formats, FormatPreference(maxHeight = 1080)),
        )

        assertEquals("v720", selection.video?.id)
    }

    @Test
    fun `falls back above the cap rather than refusing to download`() {
        val formats = listOf(video("v2160", 2160))

        val selection = assertNotNull(
            FormatSelection.choose(formats, FormatPreference(maxHeight = 480)),
        )

        assertEquals("v2160", selection.video?.id)
    }

    @Test
    fun `audio-only mode picks the best audio stream`() {
        val formats = listOf(video("v1080", 1080), audio("opus", "opus", 160_000), audio("aac", "m4a", 128_000))

        val selection = assertNotNull(
            FormatSelection.choose(formats, FormatPreference(mode = FormatPreference.Mode.AUDIO_ONLY)),
        )

        assertNull(selection.video)
        assertEquals("aac", selection.audio?.id, "m4a is first in the default container preference")
    }

    @Test
    fun `audio-only mode falls back to a muxed stream when there is no audio-only one`() {
        val selection = assertNotNull(
            FormatSelection.choose(
                listOf(muxed("m720", 720)),
                FormatPreference(mode = FormatPreference.Mode.AUDIO_ONLY),
            ),
        )
        assertEquals("m720", selection.audio?.id)
    }

    @Test
    fun `prefers a higher frame rate at equal height`() {
        val formats = listOf(video("v1080", 1080, fps = 30.0), video("v1080-60", 1080, fps = 60.0), audio("a"))
        val selection = assertNotNull(FormatSelection.choose(formats, FormatPreference()))
        assertEquals("v1080-60", selection.video?.id)
    }

    @Test
    fun `returns null only for an empty list`() {
        assertNull(FormatSelection.choose(emptyList(), FormatPreference()))
        assertNotNull(
            FormatSelection.choose(
                listOf(MediaFormat(id = "mystery", url = "https://e/x", protocol = Protocol.HTTPS)),
                FormatPreference(),
            ),
        )
    }

    @Test
    fun `totals the size of both streams`() {
        val selection = FormatSelection(
            video = video("v", 1080).copy(filesizeBytes = 1000),
            audio = audio("a").copy(filesizeBytes = 200),
        )
        assertEquals(1200, selection.totalBytes)
    }

    @Test
    fun `reports an unknown total when either stream size is missing`() {
        val selection = FormatSelection(
            video = video("v", 1080).copy(filesizeBytes = 1000),
            audio = audio("a"),
        )
        assertNull(selection.totalBytes)
    }

    @Test
    fun `labels a format the way the picker shows it`() {
        assertEquals("1080p60 · avc1 · mp4", video("v", 1080, fps = 60.0).label())
        assertEquals("720p · avc1 · mp4", video("v", 720, fps = 30.0).label())
    }
}
