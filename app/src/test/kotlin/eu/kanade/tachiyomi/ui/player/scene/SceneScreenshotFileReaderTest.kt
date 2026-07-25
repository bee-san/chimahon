package eu.kanade.tachiyomi.ui.player.scene

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SceneScreenshotFileReaderTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `partial screenshot decode is retried until the stable complete file decodes`() = runTest {
        val screenshot = File(directory, "frame.png").apply { writeText("partial") }
        var decodeAttempts = 0
        val decoded = async {
            SceneScreenshotFileReader.await(
                file = screenshot,
                maxAttempts = 8,
                pollDelayMillis = 25L,
            ) { file ->
                decodeAttempts++
                file.readText().takeIf { it == "complete" }
            }
        }

        runCurrent()
        advanceTimeBy(25L)
        runCurrent()
        assertEquals(1, decodeAttempts)
        screenshot.writeText("complete")
        advanceTimeBy(50L)
        runCurrent()

        assertEquals("complete", decoded.await())
        assertTrue(decodeAttempts >= 2)
    }

    @Test
    fun `stable undecodable screenshot exhausts retries instead of returning early`() = runTest {
        val screenshot = File(directory, "frame.png").apply { writeText("not-an-image") }
        var decodeAttempts = 0

        val decoded = SceneScreenshotFileReader.await(
            file = screenshot,
            maxAttempts = 4,
            pollDelayMillis = 1L,
        ) {
            decodeAttempts++
            null
        }

        assertNull(decoded)
        assertEquals(4, decodeAttempts)
    }
}
