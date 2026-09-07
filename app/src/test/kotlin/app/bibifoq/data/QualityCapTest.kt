package app.bibifoq.data

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The cap used to be a nullable Int travelling through a generic chip row, where "no cap" was
 * null - which is a poor thing to hang a selection comparison on, and the chip stopped showing
 * the chosen value. A closed set has one spelling and compares by identity.
 */
class QualityCapTest {

    @Test
    fun `offers best plus the resolutions people actually pick`() {
        assertEquals(QualityCap.BEST, QualityCap.entries.first())
        assertNull(QualityCap.BEST.height)
        assertEquals(
            listOf(2160, 1440, 1080, 720, 480, 360),
            QualityCap.entries.drop(1).map { it.height },
        )
    }

    @Test
    fun `round-trips through a stored name`() {
        QualityCap.entries.forEach { cap ->
            assertEquals(cap, QualityCap.valueOf(cap.name))
        }
    }

    @Test
    fun `maps a height back to its cap`() {
        assertEquals(QualityCap.P720, QualityCap.forHeight(720))
        assertEquals(QualityCap.BEST, QualityCap.forHeight(null))
        // A height nobody offers falls back rather than vanishing from the chip row.
        assertEquals(QualityCap.BEST, QualityCap.forHeight(999))
    }

    @Test
    fun `every cap compares equal only to itself`() {
        // The failure that started this: a selected value that never matched any option.
        QualityCap.entries.forEach { cap ->
            assertEquals(1, QualityCap.entries.count { it == cap })
        }
        assertTrue(QualityCap.entries.map { it.label }.distinct().size == QualityCap.entries.size)
    }
}
