package chimahon.anki

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class AnkiMediaTest {

    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `animated storage failure falls back to still and reports warning`() = runBlocking {
        val animation = mediaFile("scene.webp")
        val fallback = mediaBytes("still")
        val attempts = mutableListOf<AnkiMediaSource>()
        val result = AnkiScreenshotMediaCommitter { source ->
            attempts += source
            if (source is AnkiMediaSource.FileSource) {
                throw IllegalStateException("animation insert failed")
            }
            "stored-still.webp"
        }.store(AnkiScreenshotPreparation.Animated(animation, fallback))

        assertEquals("stored-still.webp", result.filename)
        assertEquals(listOf(animation, fallback), attempts)
        assertEquals(listOf(AnkiMediaWarning.AnimatedStorageFailed), result.warnings)
    }

    @Test
    fun `both animated and still storage failures leave screenshot empty`() = runBlocking {
        val result = AnkiScreenshotMediaCommitter {
            throw IllegalStateException("provider failure")
        }.store(
            AnkiScreenshotPreparation.Animated(
                animation = mediaFile("scene.webp"),
                stillFallback = mediaBytes("still"),
            ),
        )

        assertNull(result.filename)
        assertEquals(
            listOf(
                AnkiMediaWarning.AnimatedStorageFailed,
                AnkiMediaWarning.StillStorageFailed,
            ),
            result.warnings,
        )
    }

    @Test
    fun `generation failure stores still and preserves generation warning`() = runBlocking {
        val result = AnkiScreenshotMediaCommitter { "still.webp" }
            .store(AnkiScreenshotPreparation.GenerationFailed(mediaBytes("still")))

        assertEquals("still.webp", result.filename)
        assertEquals(listOf(AnkiMediaWarning.SceneGenerationFailed), result.warnings)
    }

    @Test
    fun `unsupported video without fallback is warning not storage failure`() = runBlocking {
        var storeCalls = 0
        val result = AnkiScreenshotMediaCommitter {
            storeCalls++
            "unused"
        }.store(
            AnkiScreenshotPreparation.UnsupportedVideo(
                reason = AnkiUnsupportedVideoReason.DRM,
                stillFallback = null,
            ),
        )

        assertNull(result.filename)
        assertEquals(0, storeCalls)
        assertEquals(
            listOf(AnkiMediaWarning.UnsupportedVideo(AnkiUnsupportedVideoReason.DRM)),
            result.warnings,
        )
    }

    @Test
    fun `storage cancellation propagates without falling back`() {
        val attempts = AtomicInteger()

        assertThrows(CancellationException::class.java) {
            runBlocking {
                AnkiScreenshotMediaCommitter {
                    attempts.incrementAndGet()
                    throw CancellationException("cancelled")
                }.store(
                    AnkiScreenshotPreparation.Animated(
                        animation = mediaFile("scene.webp"),
                        stillFallback = mediaBytes("still"),
                    ),
                )
            }
        }
        assertEquals(1, attempts.get())
    }

    @Test
    fun `request finish hook runs exactly once`() {
        val finishCalls = AtomicInteger()
        val request = AnkiMediaRequest(
            screenshotMode = AnkiScreenshotMode.ANIMATED_SCENE,
            onFinished = { finishCalls.incrementAndGet() },
        )

        request.finish()
        request.finish()

        assertEquals(1, finishCalls.get())
    }

    @Test
    fun `scene source uses streaming digest and owned release deletes file`() = runBlocking {
        val file = temporaryDirectory.resolve("scene.webp")
        file.writeBytes("abc".encodeToByteArray())

        val source = AnkiMediaNaming.sceneFileSource(file)

        assertEquals(
            "chimahon_scene_ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            source.preferredBaseName,
        )
        assertEquals("webp", source.extension)
        assertEquals(AnkiMediaFileOwnership.DELETE_AFTER_STORE_ATTEMPT, source.ownership)
        source.releaseOwnedFile()
        assertFalse(file.exists())
    }

    @Test
    fun `caller retained file survives release`() {
        val file = mediaFile("retained.webp").file
        val source = AnkiMediaSource.FileSource(
            file = file,
            preferredBaseName = "retained",
            extension = "webp",
            ownership = AnkiMediaFileOwnership.CALLER_RETAINS,
        )

        source.releaseOwnedFile()

        assertTrue(file.exists())
    }

    private fun mediaFile(name: String): AnkiMediaSource.FileSource {
        val file = temporaryDirectory.resolve(name)
        file.writeBytes(byteArrayOf(1, 2, 3))
        return AnkiMediaSource.FileSource(
            file = file,
            preferredBaseName = name.substringBeforeLast('.'),
            extension = name.substringAfterLast('.'),
            ownership = AnkiMediaFileOwnership.DELETE_AFTER_STORE_ATTEMPT,
        )
    }

    private fun mediaBytes(name: String): AnkiMediaSource.Bytes {
        return AnkiMediaSource.Bytes(
            data = byteArrayOf(4, 5, 6),
            preferredBaseName = name,
            extension = "webp",
        )
    }
}
