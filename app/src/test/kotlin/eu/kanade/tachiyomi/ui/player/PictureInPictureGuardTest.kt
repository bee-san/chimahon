package eu.kanade.tachiyomi.ui.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PictureInPictureGuardTest {

    @Test
    fun `unavailable picture in picture skips framework calls`() {
        var calls = 0
        val guard = PictureInPictureGuard(initiallyAvailable = false)

        val completed = guard.runIfAvailable {
            calls++
            true
        }

        assertFalse(completed)
        assertEquals(0, calls)
    }

    @Test
    fun `available picture in picture runs framework calls`() {
        var calls = 0
        val guard = PictureInPictureGuard(initiallyAvailable = true)

        val completed = guard.runIfAvailable {
            calls++
            true
        }

        assertTrue(completed)
        assertTrue(guard.isAvailable)
        assertEquals(1, calls)
    }

    @Test
    fun `framework rejection disables later picture in picture calls`() {
        val rejection = IllegalStateException("Device doesn't support picture-in-picture mode")
        var reportedFailure: IllegalStateException? = null
        var calls = 0
        val guard = PictureInPictureGuard(
            initiallyAvailable = true,
            onRejected = { reportedFailure = it },
        )

        val firstCompleted = guard.runIfAvailable {
            calls++
            throw rejection
        }
        val secondCompleted = guard.runIfAvailable {
            calls++
            true
        }

        assertFalse(firstCompleted)
        assertFalse(secondCompleted)
        assertFalse(guard.isAvailable)
        assertSame(rejection, reportedFailure)
        assertEquals(1, calls)
    }

    @Test
    fun `framework false result is preserved without disabling later calls`() {
        val guard = PictureInPictureGuard(initiallyAvailable = true)

        val completed = guard.runIfAvailable { false }

        assertFalse(completed)
        assertTrue(guard.isAvailable)
    }

    @Test
    fun `unexpected failures are not hidden`() {
        val failure = IllegalArgumentException("Invalid picture-in-picture parameters")
        val guard = PictureInPictureGuard(initiallyAvailable = true)

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            guard.runIfAvailable { throw failure }
        }

        assertSame(failure, thrown)
        assertTrue(guard.isAvailable)
    }
}
