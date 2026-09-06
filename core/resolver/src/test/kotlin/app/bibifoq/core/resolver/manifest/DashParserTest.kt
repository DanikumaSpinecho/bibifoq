package app.bibifoq.core.resolver.manifest

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DashParserTest {

    private val base = "https://cdn.example.com/dash/manifest.mpd".toHttpUrl()

    private val manifest = """
        <?xml version="1.0" encoding="utf-8"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT9M56.46S">
          <Period>
            <AdaptationSet mimeType="video/mp4" lang="und">
              <Representation id="v720" codecs="avc1.4d401f" width="1280" height="720" bandwidth="1200000" frameRate="30000/1001">
                <BaseURL>video_720.mp4</BaseURL>
              </Representation>
              <Representation id="v1080" codecs="avc1.640028" width="1920" height="1080" bandwidth="4500000" frameRate="60">
                <BaseURL>video_1080.mp4</BaseURL>
              </Representation>
            </AdaptationSet>
            <AdaptationSet mimeType="audio/mp4" lang="en">
              <Representation id="a128" codecs="mp4a.40.2" bandwidth="128000" audioSamplingRate="44100">
                <BaseURL>audio_en.m4a</BaseURL>
              </Representation>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    @Test
    fun `reads video and audio representations`() {
        val parsed = assertNotNull(DashParser.parse(manifest, base))
        assertEquals(3, parsed.formats.size)
        assertEquals(596460, parsed.durationMs)
        assertTrue(!parsed.isLive)

        val best = parsed.formats.maxBy { it.bitrateBps ?: 0 }
        assertEquals(1080, best.height)
        assertEquals(60.0, best.frameRate)
        assertEquals("https://cdn.example.com/dash/video_1080.mp4", best.url)
        assertNull(best.audioCodec)

        val audio = parsed.formats.single { it.audioCodec != null }
        assertEquals("mp4a.40.2", audio.audioCodec)
        assertEquals(44100, audio.sampleRateHz)
        assertEquals("en", audio.language)
    }

    @Test
    fun `estimates size from bitrate and duration and says so`() {
        val parsed = assertNotNull(DashParser.parse(manifest, base))
        val format = parsed.formats.single { it.id == "v720" }
        // 1_200_000 bits/s / 8 * 596.46 s
        assertEquals(1200000L / 8 * 596460 / 1000, format.filesizeBytes)
        assertTrue(format.filesizeApproximate)
    }

    @Test
    fun `understands both frame rate spellings`() {
        assertEquals(30.0, DashParser.parseFrameRate("30"))
        assertEquals(24000.0 / 1001.0, DashParser.parseFrameRate("24000/1001"))
        assertNull(DashParser.parseFrameRate("30/0"))
        assertNull(DashParser.parseFrameRate("weird"))
    }

    @Test
    fun `parses ISO-8601 durations`() {
        assertEquals(596460, DashParser.parseIso8601Duration("PT9M56.46S"))
        assertEquals(3600_000, DashParser.parseIso8601Duration("PT1H"))
        assertNull(DashParser.parseIso8601Duration("nonsense"))
    }

    @Test
    fun `inherits mimeType and lang from the adaptation set`() {
        val inherited = """
            <MPD mediaPresentationDuration="PT10S">
              <Period><AdaptationSet mimeType="audio/mp4" codecs="mp4a.40.2" lang="fr">
                <Representation id="a" bandwidth="64000"><BaseURL>a.m4a</BaseURL></Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        val format = assertNotNull(DashParser.parse(inherited, base)).formats.single()
        assertEquals("mp4a.40.2", format.audioCodec)
        assertEquals("fr", format.language)
    }

    @Test
    fun `refuses to resolve external entities`() {
        // We parse XML fetched from arbitrary hosts, so an XXE payload has to be inert rather
        // than merely unlikely.
        val hostile = """
            <?xml version="1.0"?>
            <!DOCTYPE MPD [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <MPD mediaPresentationDuration="PT10S">
              <Period><AdaptationSet mimeType="video/mp4" codecs="avc1">
                <Representation id="&xxe;" bandwidth="1"><BaseURL>v.mp4</BaseURL></Representation>
              </AdaptationSet></Period>
            </MPD>
        """.trimIndent()
        // A parser with DOCTYPE declarations disabled rejects the document outright.
        assertNull(DashParser.parse(hostile, base))
    }

    @Test
    fun `returns null for XML that is not a manifest`() {
        assertNull(DashParser.parse("<html><body>nope</body></html>", base))
        assertNull(DashParser.parse("not xml at all", base))
    }
}
