package chimahon.anki

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class AnkiWriteCoordinatorTest {

    @Test
    fun `commit hook runs immediately before first mutation only`() = runBlocking {
        val events = mutableListOf<String>()
        val transition = AnkiCommitTransition(currentCoroutineContext()[Job])

        transition.commit(
            onStarted = { events += "commit-started" },
        ) {
            events += "media-mutation"
            events += "note-mutation"
            AnkiResult.Success(1L)
        }

        assertEquals(
            listOf("commit-started", "media-mutation", "note-mutation"),
            events,
        )
    }

    @Test
    fun `duplicate non-write decisions do not prepare or commit`() = runBlocking {
        val scenarios = listOf(
            Scenario(forceOpen = false, action = "prevent", expected = AnkiResult.CardExists::class.java),
            Scenario(forceOpen = false, action = "open", expected = AnkiResult.OpenCard::class.java),
            Scenario(forceOpen = true, action = "overwrite", expected = AnkiResult.OpenCard::class.java),
        )

        scenarios.forEach { scenario ->
            var prepareCalls = 0
            var commitCalls = 0
            val result = AnkiWriteCoordinator().execute(
                lockKey = "same-card",
                duplicateCheck = true,
                duplicateAction = scenario.action,
                forceOpen = scenario.forceOpen,
                findExisting = { listOf(42L) },
                prepareWrite = {
                    prepareCalls++
                    AnkiWritePreparation.Ready(Unit)
                },
                commitWrite = { _, _, transition ->
                    commitCalls++
                    transition.commit({}) { AnkiResult.Success(1L) }
                },
            )

            assertInstanceOf(scenario.expected, result)
            assertEquals(0, prepareCalls)
            assertEquals(0, commitCalls)
        }
    }

    @Test
    fun `duplicate lookup failure propagates before preparation`() {
        var prepareCalls = 0
        var commitCalls = 0

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                AnkiWriteCoordinator().execute(
                    lockKey = "failed-lookup",
                    duplicateCheck = true,
                    duplicateAction = "prevent",
                    forceOpen = false,
                    findExisting = { throw IllegalStateException("provider query failed") },
                    prepareWrite = {
                        prepareCalls++
                        AnkiWritePreparation.Ready(Unit)
                    },
                    commitWrite = { _, _, transition ->
                        commitCalls++
                        transition.commit({}) { AnkiResult.Success(1L) }
                    },
                )
            }
        }

        assertEquals(0, prepareCalls)
        assertEquals(0, commitCalls)
    }

    @Test
    fun `add prepares and commits exactly once`() = runBlocking {
        var prepareCalls = 0
        var commitCalls = 0
        var target: AnkiWriteTarget? = null

        val result = AnkiWriteCoordinator().execute(
            lockKey = "new-card",
            duplicateCheck = true,
            duplicateAction = "prevent",
            forceOpen = false,
            findExisting = { emptyList() },
            prepareWrite = {
                prepareCalls++
                AnkiWritePreparation.Ready("prepared")
            },
            commitWrite = { resolved, preparation, transition ->
                commitCalls++
                target = resolved
                assertEquals("prepared", preparation)
                transition.commit({}) { AnkiResult.Success(7L) }
            },
        )

        assertInstanceOf(AnkiResult.Success::class.java, result)
        assertEquals(1, prepareCalls)
        assertEquals(1, commitCalls)
        assertEquals(AnkiWriteTarget.Add, target)
    }

    @Test
    fun `overwrite prepares and commits exactly once`() = runBlocking {
        var prepareCalls = 0
        var commitCalls = 0
        var target: AnkiWriteTarget? = null

        val result = AnkiWriteCoordinator().execute(
            lockKey = "existing-card",
            duplicateCheck = true,
            duplicateAction = "overwrite",
            forceOpen = false,
            findExisting = { listOf(99L) },
            prepareWrite = {
                prepareCalls++
                AnkiWritePreparation.Ready(Unit)
            },
            commitWrite = { resolved, _, transition ->
                commitCalls++
                target = resolved
                transition.commit({}) { AnkiResult.Success(99L) }
            },
        )

        assertInstanceOf(AnkiResult.Success::class.java, result)
        assertEquals(1, prepareCalls)
        assertEquals(1, commitCalls)
        assertEquals(AnkiWriteTarget.Overwrite(99L), target)
    }

    @Test
    fun `three requests for one card serialize preparation and commit`() = runBlocking {
        val coordinator = AnkiWriteCoordinator(StripedAnkiWriteLocks(stripeCount = 1))
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()

        coroutineScope {
            (1..3).map {
                async {
                    coordinator.execute(
                        lockKey = "same-card",
                        duplicateCheck = false,
                        duplicateAction = "allow",
                        forceOpen = false,
                        findExisting = { error("Duplicate lookup should be skipped") },
                        prepareWrite = {
                            val nowActive = active.incrementAndGet()
                            maximumActive.accumulateAndGet(nowActive) { current, candidate ->
                                maxOf(current, candidate)
                            }
                            delay(20)
                            AnkiWritePreparation.Ready(Unit)
                        },
                        commitWrite = { _, _, transition ->
                            delay(20)
                            transition.commit({}) {
                                active.decrementAndGet()
                                AnkiResult.Success(1L)
                            }
                        },
                    )
                }
            }.forEach { it.await() }
        }

        assertEquals(1, maximumActive.get())
        assertEquals(0, active.get())
    }

    @Test
    fun `cancellation before commit performs no mutation`() = runBlocking {
        val preparationStarted = CompletableDeferred<Unit>()
        val commitCalls = AtomicInteger()
        val job = launch {
            AnkiWriteCoordinator().execute<Unit>(
                lockKey = "cancel-before-commit",
                duplicateCheck = false,
                duplicateAction = "allow",
                forceOpen = false,
                findExisting = { emptyList() },
                prepareWrite = {
                    preparationStarted.complete(Unit)
                    awaitCancellation()
                },
                commitWrite = { _, _, transition ->
                    commitCalls.incrementAndGet()
                    transition.commit({}) { AnkiResult.Success(1L) }
                },
            )
        }

        preparationStarted.await()
        job.cancelAndJoin()

        assertEquals(0, commitCalls.get())
    }

    @Test
    fun `cancellation during commit setup never crosses transition`() = runBlocking {
        val setupStarted = CompletableDeferred<Unit>()
        val releaseSetup = CompletableDeferred<Unit>()
        val commitHookCalls = AtomicInteger()
        val mutations = AtomicInteger()
        val job = launch {
            AnkiWriteCoordinator().execute<Unit>(
                lockKey = "cancel-during-commit-setup",
                duplicateCheck = false,
                duplicateAction = "allow",
                forceOpen = false,
                findExisting = { emptyList() },
                prepareWrite = { AnkiWritePreparation.Ready(Unit) },
                commitWrite = { _, _, transition ->
                    setupStarted.complete(Unit)
                    releaseSetup.await()
                    transition.commit(
                        onStarted = { commitHookCalls.incrementAndGet() },
                    ) {
                        mutations.incrementAndGet()
                        AnkiResult.Success(1L)
                    }
                },
            )
        }

        setupStarted.await()
        job.cancel()
        releaseSetup.complete(Unit)
        job.join()

        assertEquals(0, commitHookCalls.get())
        assertEquals(0, mutations.get())
    }

    @Test
    fun `cancellation after first commit mutation still completes note mutation`() = runBlocking {
        val firstMutationCompleted = CompletableDeferred<Unit>()
        val allowNoteMutation = CompletableDeferred<Unit>()
        val mutations = mutableListOf<String>()
        val job = launch {
            AnkiWriteCoordinator().execute(
                lockKey = "cancel-after-commit",
                duplicateCheck = false,
                duplicateAction = "allow",
                forceOpen = false,
                findExisting = { emptyList() },
                prepareWrite = { AnkiWritePreparation.Ready(Unit) },
                commitWrite = { _, _, transition ->
                    transition.commit(onStarted = {}) {
                        mutations += "media"
                        firstMutationCompleted.complete(Unit)
                        allowNoteMutation.await()
                        mutations += "note"
                        AnkiResult.Success(1L)
                    }
                },
            )
        }

        firstMutationCompleted.await()
        job.cancel()
        allowNoteMutation.complete(Unit)
        job.join()

        assertEquals(listOf("media", "note"), mutations)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `explicit cancelled preparation returns cancelled without commit`() = runBlocking {
        var commitCalls = 0

        val result = AnkiWriteCoordinator().execute<Unit>(
            lockKey = "provider-cancelled",
            duplicateCheck = false,
            duplicateAction = "allow",
            forceOpen = false,
            findExisting = { emptyList() },
            prepareWrite = { AnkiWritePreparation.Cancelled },
            commitWrite = { _, _, transition ->
                commitCalls++
                transition.commit({}) { AnkiResult.Success(1L) }
            },
        )

        assertEquals(AnkiResult.Cancelled, result)
        assertEquals(0, commitCalls)
    }

    private data class Scenario(
        val forceOpen: Boolean,
        val action: String,
        val expected: Class<out AnkiResult>,
    )
}
