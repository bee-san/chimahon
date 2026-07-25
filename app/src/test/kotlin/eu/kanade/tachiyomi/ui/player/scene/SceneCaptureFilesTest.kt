package eu.kanade.tachiyomi.ui.player.scene

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.FileAlreadyExistsException

class SceneCaptureFilesTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `hash is streamed as full lowercase SHA-256`() = runTest {
        val file = directory.resolve("scene.webp").apply {
            writeText("abc")
        }

        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SceneCaptureFiles.sha256(file),
        )
    }

    @Test
    fun `atomic finalization preserves deterministic preferred base and caller ownership`() {
        val source = directory.resolve("candidate.webp").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val digest = "a".repeat(64)
        val output = SceneCaptureFiles.atomicallyFinalize(
            source = source,
            outputDirectory = directory.resolve("ready"),
            jobId = "job:1",
            digest = digest,
        )

        assertFalse(source.exists())
        assertTrue(output.file.isFile)
        assertEquals("job_1_chimahon_scene_$digest.webp", output.file.name)
        assertEquals("chimahon_scene_$digest", output.preferredBaseName)

        output.close()
        output.close()
        assertFalse(output.file.exists())
    }

    @Test
    fun `finalization never overwrites a colliding live output`() {
        val digest = "b".repeat(64)
        val outputDirectory = directory.resolve("ready")
        val first = directory.resolve("first.webp").apply { writeBytes(byteArrayOf(1)) }
        val second = directory.resolve("second.webp").apply { writeBytes(byteArrayOf(2)) }
        val finalized = SceneCaptureFiles.atomicallyFinalize(first, outputDirectory, "job", digest)

        assertThrows(FileAlreadyExistsException::class.java) {
            SceneCaptureFiles.atomicallyFinalize(second, outputDirectory, "job", digest)
        }
        assertTrue(second.exists())
        finalized.close()
    }

    @Test
    fun `taking the file transfers deletion responsibility exactly once`() {
        val file = directory.resolve("transferred.webp").apply {
            writeBytes(byteArrayOf(1))
        }
        val output = SceneCapturedFile(
            file = file,
            digest = "c".repeat(64),
            preferredBaseName = "chimahon_scene_${"c".repeat(64)}",
        )

        assertEquals(file, output.takeFile())
        assertEquals(null, output.takeFile())
        output.close()
        assertTrue(file.exists())

        assertTrue(file.delete())
    }
}
