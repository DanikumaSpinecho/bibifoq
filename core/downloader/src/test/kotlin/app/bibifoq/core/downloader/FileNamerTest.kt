package app.bibifoq.core.downloader

import app.bibifoq.core.model.Completeness
import app.bibifoq.core.model.MediaFormat
import app.bibifoq.core.model.MediaInfo
import app.bibifoq.core.model.Protocol
import app.bibifoq.core.model.Provenance
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class FileNamerTest {

    private val info = MediaInfo(
        sourceUrl = "https://example.com/v/1",
        id = "abc123",
        title = "Le Voyage: Part 1/2 <2024>",
        uploader = "Studio Example",
        uploadDate = "2024-03-11",
        extractor = "test",
        provenance = Provenance.NATIVE,
        completeness = Completeness.COMPLETE,
    )

    private val format = MediaFormat(
        id = "137",
        url = "https://example.com/v.mp4",
        protocol = Protocol.HTTPS,
        container = "mp4",
        height = 1080,
    )

    @Test
    fun `removes characters a filesystem would reject`() {
        assertEquals("Le Voyage Part 1 2 2024.mp4", FileNamer.render(info, format))
    }

    @Test
    fun `fills in every placeholder`() {
        assertEquals(
            "2024-03-11 - Studio Example - Le Voyage Part 1 2 2024 [abc123] 1080p.mp4",
            FileNamer.render(
                info,
                format,
                "%(date)s - %(uploader)s - %(title)s [%(id)s] %(resolution)s.%(ext)s",
            ),
        )
    }

    @Test
    fun `collapses the whitespace left behind by stripped characters`() {
        assertEquals("a b c", FileNamer.sanitize("a??b///c"))
    }

    @Test
    fun `refuses to produce a hidden file`() {
        assertEquals("bashrc", FileNamer.sanitize(".bashrc"))
    }

    @Test
    fun `escapes reserved device names`() {
        assertEquals("_CON", FileNamer.sanitize("CON"))
        assertEquals("_nul.txt", FileNamer.sanitize("nul.txt"))
        assertEquals("console", FileNamer.sanitize("console"))
    }

    @Test
    fun `falls back when the title sanitises away to nothing`() {
        assertEquals(FileNamer.FALLBACK_NAME, FileNamer.sanitize("///"))
        assertEquals(FileNamer.FALLBACK_NAME, FileNamer.sanitize("   "))
    }

    @Test
    fun `keeps names inside the filesystem limit`() {
        val long = FileNamer.sanitize("x".repeat(500))
        assertTrue(long.length <= FileNamer.MAX_STEM_LENGTH, "was ${long.length}")
    }

    @Test
    fun `drops control characters`() {
        assertEquals("a b", FileNamer.sanitize("a\u0007b"))
        assertEquals("ab", FileNamer.sanitize("ab\u0000"))
    }

    @Test
    fun `numbers a name that is already taken instead of overwriting it`() {
        val directory: File = Files.createTempDirectory("namer").toFile()
        try {
            assertEquals(File(directory, "clip.mp4"), FileNamer.uniqueIn(directory, "clip.mp4"))

            File(directory, "clip.mp4").writeText("first")
            assertEquals(File(directory, "clip (2).mp4"), FileNamer.uniqueIn(directory, "clip.mp4"))

            File(directory, "clip (2).mp4").writeText("second")
            assertEquals(File(directory, "clip (3).mp4"), FileNamer.uniqueIn(directory, "clip.mp4"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
