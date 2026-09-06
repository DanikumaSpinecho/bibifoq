package app.bibifoq.engine

import app.bibifoq.core.model.Completeness
import app.bibifoq.core.model.FormatKind
import app.bibifoq.core.model.Protocol
import app.bibifoq.core.model.Provenance
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The engine's JSON is loosely typed and full of sentinels, so this mapping is where quiet
 * bugs would live: a `"none"` codec read as a real one mislabels every audio stream, and a
 * bitrate read in the wrong unit makes the format picker sort backwards.
 */
class YtDlpInfoMapperTest {

    private val payload = """
        {
          "id": "xyz789",
          "title": "A Documentary",
          "uploader": "Some Channel",
          "description": "A description.",
          "duration": 3661.5,
          "thumbnail": "https://cdn.example.com/thumb.jpg",
          "upload_date": "20240311",
          "webpage_url": "https://example.com/watch/xyz789",
          "extractor_key": "ExampleSite",
          "is_live": false,
          "http_headers": { "Referer": "https://example.com/", "User-Agent": "engine" },
          "formats": [
            {
              "format_id": "140",
              "url": "https://cdn.example.com/audio.m4a",
              "ext": "m4a",
              "vcodec": "none",
              "acodec": "mp4a.40.2",
              "abr": 128.0,
              "asr": 44100,
              "filesize": 5242880,
              "protocol": "https"
            },
            {
              "format_id": "137",
              "url": "https://cdn.example.com/video.mp4",
              "ext": "mp4",
              "vcodec": "avc1.640028",
              "acodec": "none",
              "width": 1920,
              "height": 1080,
              "fps": 59.94,
              "tbr": 4500.0,
              "filesize_approx": 268435456,
              "protocol": "https"
            },
            {
              "format_id": "hls-720",
              "url": "https://cdn.example.com/720.m3u8",
              "ext": "mp4",
              "vcodec": "avc1.4d401f",
              "acodec": "mp4a.40.2",
              "height": 720,
              "protocol": "m3u8_native"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `maps the descriptive fields`() {
        val info = YtDlpInfoMapper.parse(payload, "https://example.com/watch/xyz789")

        assertEquals("xyz789", info.id)
        assertEquals("A Documentary", info.title)
        assertEquals("Some Channel", info.uploader)
        assertEquals(3_661_500, info.durationMs)
        assertEquals("2024-03-11", info.uploadDate)
        assertEquals("yt-dlp:ExampleSite", info.extractor)
        assertEquals(Provenance.YTDLP, info.provenance)
        assertEquals(Completeness.COMPLETE, info.completeness)
        assertEquals("https://example.com/", info.httpHeaders["Referer"])
    }

    @Test
    fun `reads the none sentinel as an absent track rather than a codec`() {
        val formats = YtDlpInfoMapper.parse(payload, "u").formats

        val audio = formats.single { it.id == "140" }
        assertNull(audio.videoCodec)
        assertEquals(FormatKind.AUDIO_ONLY, audio.kind)

        val video = formats.single { it.id == "137" }
        assertNull(video.audioCodec)
        assertEquals(FormatKind.VIDEO_ONLY, video.kind)

        assertEquals(FormatKind.MUXED, formats.single { it.id == "hls-720" }.kind)
    }

    @Test
    fun `converts bitrates from kilobits to bits`() {
        val formats = YtDlpInfoMapper.parse(payload, "u").formats
        assertEquals(128_000, formats.single { it.id == "140" }.bitrateBps)
        assertEquals(4_500_000, formats.single { it.id == "137" }.bitrateBps)
    }

    @Test
    fun `marks an approximate size as approximate`() {
        val formats = YtDlpInfoMapper.parse(payload, "u").formats

        val exact = formats.single { it.id == "140" }
        assertEquals(5_242_880, exact.filesizeBytes)
        assertTrue(!exact.filesizeApproximate)

        val estimated = formats.single { it.id == "137" }
        assertEquals(268_435_456, estimated.filesizeBytes)
        assertTrue(estimated.filesizeApproximate)
    }

    @Test
    fun `maps engine protocol names onto how we would fetch the stream`() {
        assertEquals(Protocol.HLS, YtDlpInfoMapper.protocolOf("m3u8_native", "mp4"))
        assertEquals(Protocol.HLS, YtDlpInfoMapper.protocolOf("m3u8", "mp4"))
        assertEquals(Protocol.DASH, YtDlpInfoMapper.protocolOf("http_dash_segments", "m4s"))
        assertEquals(Protocol.HTTPS, YtDlpInfoMapper.protocolOf("https", "mp4"))
        assertEquals(Protocol.UNSUPPORTED, YtDlpInfoMapper.protocolOf("rtmp", "flv"))
        // With no protocol field, the extension is the only clue.
        assertEquals(Protocol.HLS, YtDlpInfoMapper.protocolOf(null, "m3u8"))
        assertEquals(Protocol.HTTPS, YtDlpInfoMapper.protocolOf(null, "mp4"))
    }

    @Test
    fun `reads a flat playlist into entries`() {
        val playlist = """
            {
              "_type": "playlist",
              "id": "PL1",
              "title": "A Playlist",
              "entries": [
                {"id": "a", "title": "First", "url": "https://example.com/a", "duration": 60},
                {"id": "b", "title": "Second", "url": "https://example.com/b"}
              ]
            }
        """.trimIndent()

        val info = YtDlpInfoMapper.parse(playlist, "https://example.com/list/PL1")

        assertTrue(info.isPlaylist)
        assertEquals(2, info.entries.size)
        assertEquals("First", info.entries.first().title)
        assertEquals(60_000, info.entries.first().durationMs)
        assertNull(info.entries.last().durationMs)
    }

    @Test
    fun `falls back to a top-level url when there is no formats array`() {
        val single = """
            {"id": "s1", "title": "Single", "url": "https://cdn.example.com/only.mp4", "ext": "mp4"}
        """.trimIndent()

        val format = YtDlpInfoMapper.parse(single, "u").formats.single()

        assertEquals("https://cdn.example.com/only.mp4", format.url)
        assertEquals("mp4", format.container)
    }

    @Test
    fun `picks the largest thumbnail when only a list is given`() {
        val withThumbnails = """
            {
              "id": "t", "title": "T",
              "thumbnails": [
                {"url": "https://e/small.jpg", "width": 120},
                {"url": "https://e/large.jpg", "width": 1280},
                {"url": "https://e/medium.jpg", "width": 640}
              ]
            }
        """.trimIndent()

        assertEquals(
            "https://e/large.jpg",
            YtDlpInfoMapper.parse(withThumbnails, "u").thumbnailUrl,
        )
    }

    @Test
    fun `rejects a malformed upload date instead of mangling it`() {
        assertEquals("2024-03-11", YtDlpInfoMapper.formatUploadDate("20240311"))
        assertNull(YtDlpInfoMapper.formatUploadDate("2024-03-11"))
        assertNull(YtDlpInfoMapper.formatUploadDate("today"))
        assertNull(YtDlpInfoMapper.formatUploadDate(""))
    }

    @Test
    fun `survives a payload with almost nothing in it`() {
        val info = YtDlpInfoMapper.parse("""{"id":"x"}""", "https://example.com/x")
        assertEquals("Untitled", info.title)
        assertTrue(info.formats.isEmpty())
    }
}
