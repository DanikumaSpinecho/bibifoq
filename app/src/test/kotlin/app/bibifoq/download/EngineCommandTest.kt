package app.bibifoq.download

import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks in the behaviour that the resolve's work is not thrown away.
 *
 * Handing the engine a page URL makes it redo the whole extraction that tier 2 just finished -
 * slow, and enough extra traffic to trip a site's rate limiting at download time on a page that
 * resolved fine seconds earlier. Replaying the saved JSON is the point; a regression here would
 * be invisible except as "downloads got slower and flakier".
 */
class EngineCommandTest {

    private val destination = File("/tmp/downloads/Some Video.mp4")
    private val savedInfo = File("/tmp/cache/abc123.info.json")

    @Test
    fun `replays a saved extraction instead of visiting the page again`() {
        val command = EngineCommand
            .build("137+140", destination, "mp4", savedInfo, webpageUrl = null)
            .buildCommand()

        assertContains(command, "--load-info-json")
        assertEquals(savedInfo.absolutePath, command[command.indexOf("--load-info-json") + 1])
        // The decisive assertion: no URL, so the engine cannot go back to the site.
        assertTrue(
            command.none { it.startsWith("http://") || it.startsWith("https://") },
            "command must carry no URL, was $command",
        )
    }

    @Test
    fun `falls back to a fresh extraction when there is nothing saved`() {
        val command = EngineCommand
            .build("137+140", destination, "mp4", savedInfo = null, webpageUrl = "https://example.com/v/1")
            .buildCommand()

        assertContains(command, "https://example.com/v/1")
        assertTrue(command.none { it == "--load-info-json" })
    }

    @Test
    fun `always asks for the selected formats and the chosen output`() {
        listOf(
            EngineCommand.build("137+140", destination, "mp4", savedInfo, null),
            EngineCommand.build("137+140", destination, "mp4", null, "https://example.com/v/1"),
        ).forEach { request ->
            val command = request.buildCommand()
            assertEquals("137+140", command[command.indexOf("-f") + 1])
            assertEquals(destination.absolutePath, command[command.indexOf("-o") + 1])
            assertEquals("mp4", command[command.indexOf("--merge-output-format") + 1])
            // A config file on the device would make behaviour unpredictable between phones.
            assertContains(command, "--ignore-config")
            assertContains(command, "--no-playlist")
        }
    }

    @Test
    fun `refuses to build a command with neither an input nor a URL`() {
        assertFailsWith<IllegalArgumentException> {
            EngineCommand.build("137", destination, "mp4", savedInfo = null, webpageUrl = null)
        }
    }

    @Test
    fun `carries a single-stream format spec unchanged`() {
        val command = EngineCommand.build("22", destination, "mp4", savedInfo, null).buildCommand()
        assertEquals("22", command[command.indexOf("-f") + 1])
    }
}
