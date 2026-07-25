package eu.kanade.tachiyomi.ui.player.scene

import chimahon.anki.AnkiScreenshotPreparation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
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
    fun `only one mining job is accepted and its lease closes on completion`() = runTest {
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
        assertEquals(PlayerSceneMiningProgress.CheckingDuplicate, coordinator.progress.value)

        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, closedLeases)
        assertEquals(PlayerSceneMiningProgress.Idle, coordinator.progress.value)
    }

    @Test
    fun `pre-commit lifecycle cancellation stops the job and closes its lease`() = runTest {
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
    fun `commit boundary prevents lifecycle cancellation`() = runTest {
        val coordinator = coordinator()
        val commitCompletes = CompletableDeferred<Unit>()
        var completed = false

        coordinator.launchWithLease(
            acquireLease = { Closeable {} },
            block = {
                commitCompletes.await()
                completed = true
            },
        )
        runCurrent()
        coordinator.markCommitStarted()
        coordinator.cancelPreCommit()
        runCurrent()

        assertFalse(completed)
        assertEquals(PlayerSceneMiningProgress.Committing, coordinator.progress.value)

        commitCompletes.complete(Unit)
        advanceUntilIdle()
        assertTrue(completed)
    }

    @Test
    fun `cancellation winning the boundary prevents commit`() = runTest {
        val coordinator = coordinator()
        val neverCompletes = CompletableDeferred<Unit>()

        coordinator.launchWithLease(
            acquireLease = { Closeable {} },
            block = { neverCompletes.await() },
        )
        runCurrent()

        coordinator.cancelPreCommit()

        assertThrows<CancellationException> {
            coordinator.markCommitStarted()
        }
        advanceUntilIdle()
        assertEquals(PlayerSceneMiningProgress.Idle, coordinator.progress.value)
    }

    @Test
    fun `shutdown drains a committed job and then closes its dedicated scope`() = runTest {
        val scopeJob = SupervisorJob()
        val miningScope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
        val coordinator = PlayerSceneMiningCoordinator(
            scope = miningScope,
            sceneCaptureService = {
                SceneCaptureService { _, _ -> AnkiScreenshotPreparation.Cancelled }
            },
            stillEncoder = SceneStillFallbackEncoder { null },
        )
        val allowCommitToFinish = CompletableDeferred<Unit>()
        var committedResultReported = false

        coordinator.launchWithLease(
            acquireLease = { Closeable {} },
            block = {
                allowCommitToFinish.await()
                committedResultReported = true
            },
        )
        runCurrent()
        coordinator.markCommitStarted()

        coordinator.shutdown()
        runCurrent()

        assertTrue(scopeJob.isActive)
        assertFalse(committedResultReported)
        assertFalse(
            coordinator.launchWithLease(
                acquireLease = { Closeable {} },
                block = {},
            ),
        )

        allowCommitToFinish.complete(Unit)
        advanceUntilIdle()

        assertTrue(committedResultReported)
        assertFalse(scopeJob.isActive)
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(): PlayerSceneMiningCoordinator {
        return PlayerSceneMiningCoordinator(
            scope = this,
            sceneCaptureService = {
                SceneCaptureService { _, _ -> AnkiScreenshotPreparation.Cancelled }
            },
            stillEncoder = SceneStillFallbackEncoder { null },
        )
    }
}
