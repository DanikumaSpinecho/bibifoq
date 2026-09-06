package app.bibifoq.core.resolver.manifest

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class HlsParserTest {

    private val base = "https://cdn.example.com/media/master.m3u8".toHttpUrl()

    @Test
    fun `reads a quality ladder out of a master playlist`() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1400000,AVERAGE-BANDWIDTH=1200000,RESOLUTION=1280x720,FRAME-RATE=29.970,CODECS="avc1.4d401f,mp4a.40.2"
            720p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5200000,RESOLUTION=1920x1080,FRAME-RATE=59.940,CODECS="avc1.640028,mp4a.40.2"
            1080p.m3u8
        """.trimIndent()

        val parsed = HlsParser.parse(playlist, base)
        val master = assertIs<HlsPlaylist.Master>(parsed)
        assertEquals(2, master.variants.size)

        val best = master.variants.last()
        assertEquals(1920, best.width)
        assertEquals(1080, best.height)
        assertEquals(59.94, best.frameRate)
        assertEquals("avc1.640028", best.videoCodec)
        assertEquals("mp4a.40.2", best.audioCodec)
        assertEquals("https://cdn.example.com/media/1080p.m3u8", best.url)
    }

    @Test
    fun `does not split a quoted CODECS list on its internal comma`() {
        // The naive "split on comma" parser gets this wrong, and CODECS is the one attribute
        // where being wrong silently mislabels every format.
        val attrs = AttributeList.parse("""BANDWIDTH=100,CODECS="avc1.4d401f,mp4a.40.2",RESOLUTION=640x360""")
        assertEquals("avc1.4d401f,mp4a.40.2", attrs["CODECS"])
        assertEquals("100", attrs["BANDWIDTH"])
        assertEquals(640 to 360, attrs.resolution())
    }

    @Test
    fun `picks up separate audio renditions`() {
        val playlist = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",LANGUAGE="en",URI="audio_en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480,CODECS="avc1.4d401e",AUDIO="aac"
            480p.m3u8
        """.trimIndent()

        val master = assertIs<HlsPlaylist.Master>(HlsParser.parse(playlist, base))
        assertEquals(1, master.audioRenditions.size)
        assertEquals("en", master.audioRenditions.single().language)
        assertEquals("https://cdn.example.com/media/audio_en.m3u8", master.audioRenditions.single().url)
        // With a separate audio group the video variant really is video-only.
        assertEquals(null, master.variants.single().audioCodec)
    }

    @Test
    fun `totals segment durations for a media playlist`() {
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:9.009,
            seg0.ts
            #EXTINF:9.009,
            seg1.ts
            #EXTINF:3.003,
            seg2.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val media = assertIs<HlsPlaylist.Media>(HlsParser.parse(playlist, base))
        assertEquals(21021, media.durationMs)
        assertEquals(3, media.segmentCount)
        assertTrue(!media.isLive)
    }

    @Test
    fun `treats a playlist without an end list as live`() {
        val playlist = """
            #EXTM3U
            #EXTINF:4.0,
            seg0.ts
        """.trimIndent()
        assertTrue(assertIs<HlsPlaylist.Media>(HlsParser.parse(playlist, base)).isLive)
    }

    @Test
    fun `rejects text that is not a playlist`() {
        assertEquals(HlsPlaylist.Invalid, HlsParser.parse("<html><body>404</body></html>", base))
        assertNotNull(HlsParser.parse("#EXTM3U", base))
    }

    @Test
    fun `resolves absolute variant URLs on another host`() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=100,RESOLUTION=320x180
            https://other.example.net/low.m3u8
        """.trimIndent()
        val master = assertIs<HlsPlaylist.Master>(HlsParser.parse(playlist, base))
        assertEquals("https://other.example.net/low.m3u8", master.variants.single().url)
    }
}
