package chimahon.anki

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AnkiProviderResultTest {

    @Test
    fun `positive provider update count is accepted`() {
        assertDoesNotThrow {
            requireProviderRowsUpdated(
                count = 1,
                operation = "configure Anki model template",
            )
        }
    }

    @Test
    fun `zero provider update count is explicit failure`() {
        val failure = assertThrows(Exception::class.java) {
            requireProviderRowsUpdated(
                count = 0,
                operation = "configure Anki model template",
            )
        }

        assertEquals(
            "AnkiDroid failed to configure Anki model template",
            failure.message,
        )
    }

    @Test
    fun `negative provider update count is explicit failure`() {
        val failure = assertThrows(Exception::class.java) {
            requireProviderRowsUpdated(
                count = -1,
                operation = "move Anki card 0 to deck",
            )
        }

        assertEquals(
            "AnkiDroid failed to move Anki card 0 to deck",
            failure.message,
        )
    }
}
