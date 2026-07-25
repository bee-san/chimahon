package eu.kanade.tachiyomi.ui.player.scene

import android.graphics.Bitmap
import chimahon.anki.AnkiMediaSource
import chimahon.anki.AnkiScreenshotMode
import chimahon.anki.AnkiScreenshotPreparation
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.Closeable

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSceneMiningCoordinatorTest {
    @Test
    fun `only one job is accepted and its lease closes`() = runTest {
        val coordinator = coordinator()
        val release = CompletableDeferred<Unit>()
        var closedLeases = 0

        assertTrue(
            coordinator.launchWithLease(
                acquireLease = { Closeable { closedLeases++ } },
                block = { release.await() },
            ),
        )
        assertFalse(
            coordinator.launchWithLease(
                acquireLease = { Closeable { closedLeases++ } },
                block = {},
            ),
        )
        assertEquals(PlayerSceneMiningProgress.Preparing, coordinator.progress.value)

        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, closedLeases)
        assertEquals(PlayerSceneMiningProgress.Idle, coordinator.progress.value)
    }

    @Test
    fun `precommit cancellation stops work`() = runTest {
        val coordinator = coordinator()
        val neverCompletes = CompletableDeferred<Unit>()
        var closedLeases = 0

        coordinator.launchWithLease(
            acquireLease = { Closeable { closedLeases++ } },
            block = { neverCompletes.await() },
        )
        runCurrent()
        coordinator.cancelPreCommit()
        advanceUntilIdle()

        assertEquals(1, closedLeases)
        assertEquals(PlayerSceneMiningProgress.Idle, coordinator.progress.value)
    }

    @Test
    fun `commit boundary prevents cancellation`() = runTest {
        val coordinator = coordinator()
        val commitCompletes = CompletableDeferred<Unit>()

        coordinator.launchWithLease(
            acquireLease = { Closeable {} },
            block = { commitCompletes.await() },
        )
        runCurrent()
        coordinator.markCommitStarted()
        coordinator.cancelPreCommit()

        assertEquals(PlayerSceneMiningProgress.Committing, coordinator.progress.value)
        commitCompletes.complete(Unit)
        advanceUntilIdle()
        assertEquals(PlayerSceneMiningProgress.Idle, coordinator.progress.value)
    }

    @Test
    fun `cancellation winning the boundary prevents commit`() = runTest {
        val coordinator = coordinator()
        coordinator.launchWithLease(
            acquireLease = { Closeable {} },
            block = { CompletableDeferred<Unit>().await() },
        )
        runCurrent()

        coordinator.cancelPreCommit()

        assertThrows<CancellationException> {
            coordinator.markCommitStarted()
        }
        advanceUntilIdle()
    }

    @Test
    fun `AVIF failure uses captured WebP fallback`() = runTest {
        val fallback = AnkiMediaSource.Bytes(
            data = byteArrayOf(1, 2, 3),
            preferredBaseName = "still",
            extension = "webp",
        )
        val coordinator = PlayerSceneMiningCoordinator(
            scope = this,
            sceneCaptureService = {
                SceneCaptureService { AnkiScreenshotPreparation.Failed(null) }
            },
            stillEncoder = SceneStillFallbackEncoder { fallback },
        )

        val result = coordinator.prepareScreenshot(request(), AnkiScreenshotMode.ANIMATED_SCENE)

        assertEquals(
            AnkiScreenshotPreparation.Failed(fallback),
            result,
        )
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(): PlayerSceneMiningCoordinator {
        return PlayerSceneMiningCoordinator(
            scope = this,
            sceneCaptureService = {
                SceneCaptureService { AnkiScreenshotPreparation.Failed(null) }
            },
            stillEncoder = SceneStillFallbackEncoder { null },
        )
    }

    private fun request(): SceneCaptureRequest {
        val input = SceneVideoInputSpec(
            value = "/video.mp4",
            kind = SceneVideoInputKind.LOCAL_FILE,
            headers = emptyList(),
        )
        val bitmap = mockk<Bitmap>(relaxed = true)
        every { bitmap.isRecycled } returns false
        return SceneCaptureRequest(
            videoInput = input,
            sentenceAudioInput = input,
            resolvedTiming = SceneResolvedTiming(
                animationRange = SceneTimeRange(0.0, 1.0),
                audioRange = SceneTimeRange(0.0, 1.0),
            ),
            stillFallback = OwnedBitmap(bitmap),
        )
    }
}
