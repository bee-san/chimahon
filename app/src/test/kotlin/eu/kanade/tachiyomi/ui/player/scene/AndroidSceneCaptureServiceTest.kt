package eu.kanade.tachiyomi.ui.player.scene

import chimahon.anki.AnkiMediaFileOwnership
import chimahon.anki.AnkiScreenshotPreparation
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class AndroidSceneCaptureServiceTest {
    @Test
    fun `successful output ownership is transferred to Anki source exactly once`() = runTest {
        val capturedFile = Files.createTempFile("scene-service", ".webp").toFile().apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val output = SceneCapturedFile(
            file = capturedFile,
            digest = "digest",
            preferredBaseName = "chimahon_scene_digest",
        )
        val input = input()
        val range = SceneTimeRange(2.0, 3.0)
        val recordedMetrics = mutableListOf<SceneCaptureMetrics>()
        var pipelineRequest: SceneCapturePipelineRequest? = null
        val service = AndroidSceneCaptureService.forTests(
            recordMetrics = recordedMetrics::add,
        ) {
            SceneCaptureRunner { request ->
                pipelineRequest = request
                SceneCaptureResult.Success(
                    output = output,
                    info = AnimatedWebpInfo(320, 180, 2, 1_000L, 0),
                    metrics = SceneCaptureMetrics(2, 3L, 1_000L, 20L),
                )
            }
        }
        val request = mockk<SceneCaptureRequest>()
        every { request.videoInput } returns SceneVideoInputResolution.Supported(input)
        every { request.resolvedTiming } returns SceneResolvedTiming(
            sourceRange = range,
            animationRange = range,
            audioRange = range,
            provenance = SceneRangeProvenance.PLAYBACK_POSITION,
        )

        val preparation = service.prepare(request) {}

        val animated = preparation as AnkiScreenshotPreparation.Animated
        assertSame(input, pipelineRequest?.input)
        assertEquals(range, pipelineRequest?.animationRange)
        assertSame(capturedFile, animated.animation.file)
        assertEquals(AnkiMediaFileOwnership.DELETE_AFTER_STORE_ATTEMPT, animated.animation.ownership)
        assertTrue(capturedFile.exists())
        assertNull(output.takeFile())
        assertEquals(
            listOf(SceneCaptureMetrics(2, 3L, 1_000L, 20L)),
            recordedMetrics,
        )

        capturedFile.delete()
    }

    @Test
    fun `pipeline unsupported reason is mapped without losing its type`() = runTest {
        val service = AndroidSceneCaptureService.forTests {
            SceneCaptureRunner {
                SceneCaptureResult.Unsupported(SceneCaptureUnsupportedReason.HDR_OR_TEN_BIT)
            }
        }
        val request = mockk<SceneCaptureRequest>()
        every { request.videoInput } returns SceneVideoInputResolution.Supported(input())
        every { request.resolvedTiming } returns SceneResolvedTiming(
            sourceRange = SceneTimeRange(1.0, 2.0),
            animationRange = SceneTimeRange(1.0, 2.0),
            audioRange = SceneTimeRange(1.0, 2.0),
            provenance = SceneRangeProvenance.PLAYBACK_POSITION,
        )

        val preparation = service.prepare(request) {}

        assertTrue(preparation is AnkiScreenshotPreparation.UnsupportedVideo)
    }

    private fun input(): SceneVideoInputSpec {
        return SceneVideoInputSpec(
            value = "/video.mp4",
            kind = SceneVideoInputKind.LOCAL_FILE,
            headers = emptyList(),
            inputOptions = emptyList(),
            externalAudioValue = null,
            identity = SceneVideoIdentity(1L, 2L, "1080p", "digest"),
        )
    }
}
