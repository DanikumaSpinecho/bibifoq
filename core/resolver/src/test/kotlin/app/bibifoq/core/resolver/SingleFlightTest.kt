package app.bibifoq.core.resolver

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class SingleFlightTest {

    @Test
    fun `concurrent callers for one key share a single execution`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val subject = SingleFlight<String, Int>(scope)
        val executions = AtomicInteger(0)

        val results = (1..20).map {
            async {
                subject.run("same-url") {
                    executions.incrementAndGet()
                    delay(100)
                    42
                }
            }
        }.awaitAll()

        assertEquals(List(20) { 42 }, results)
        assertEquals(1, executions.get())
        scope.cancel()
    }

    @Test
    fun `different keys run independently`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val subject = SingleFlight<String, String>(scope)
        val executions = AtomicInteger(0)

        val results = listOf("a", "b", "c").map { key ->
            async { subject.run(key) { executions.incrementAndGet(); key.uppercase() } }
        }.awaitAll()

        assertEquals(listOf("A", "B", "C"), results)
        assertEquals(3, executions.get())
        scope.cancel()
    }

    @Test
    fun `a failure reaches every waiter and does not poison the key`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val subject = SingleFlight<String, Int>(scope)

        assertFailsWith<IllegalStateException> {
            subject.run("k") { error("boom") }
        }
        // The key is released on failure, so a retry actually retries.
        assertEquals(7, subject.run("k") { 7 })
        assertFalse(subject.isRunning("k"))
        scope.cancel()
    }

    @Test
    fun `a caller giving up does not cancel the shared work`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val subject = SingleFlight<String, Int>(scope)
        val completed = AtomicInteger(0)

        val giverUpper = async {
            subject.run("k") {
                delay(200)
                completed.incrementAndGet()
                1
            }
        }
        delay(50)
        val patient = async { subject.run("k") { error("should not be reached") } }
        giverUpper.cancel()

        assertEquals(1, patient.await())
        assertEquals(1, completed.get())
        scope.cancel()
    }
}
