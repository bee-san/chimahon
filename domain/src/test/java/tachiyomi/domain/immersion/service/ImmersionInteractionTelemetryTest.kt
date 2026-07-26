// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.service

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.AnkiOperationStatus
import tachiyomi.domain.immersion.model.AnkiOperationType
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.LookupStatus
import tachiyomi.domain.immersion.model.RawTextRetention
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SourceUnitId
import java.util.UUID

class ImmersionInteractionTelemetryTest {

    @Test
    fun `fallback provenance snapshots recorder state and one lookup intent records exactly once`() {
        val recorder = FakeRecorder()
        val telemetry = DefaultLookupTelemetry(recorder) { RawTextRetention.NEVER }
        val intentId = UUID.randomUUID().toString()

        val first = telemetry.begin(intentId, " 読む ")
        val repeated = telemetry.begin(intentId, "読む")
        first shouldBe repeated

        telemetry.complete(
            token = first,
            status = LookupStatus.SUCCESS,
            normalizedHeadword = "読む",
            normalizedReading = "よむ",
        ).shouldBeInstanceOf<RecordResult.Enqueued>()
        telemetry.complete(first, LookupStatus.FAILED) shouldBe RecordResult.Enqueued(0)

        recorder.commands.shouldHaveSize(1)
        recorder.commands.single().shouldBeInstanceOf<CaptureCommand.Lookup>().let { command ->
            command.rawQuery shouldBe null
            command.status shouldBe LookupStatus.SUCCESS
            command.sourceUnitId shouldBe recorder.snapshot.value.sourceUnitId
        }
        recorder.handles.single().sessionId shouldBe recorder.snapshot.value.sessionId
    }

    @Test
    fun `lookup completion uses canonical pending provenance`() {
        val recorder = FakeRecorder()
        val telemetry = DefaultLookupTelemetry(recorder) { RawTextRetention.UNTIL_DELETED }
        val canonical = telemetry.begin(UUID.randomUUID().toString(), "読む")
        val forged = canonical.copy(
            sessionId = SessionId(UUID.randomUUID().toString()),
            sourceUnitId = SourceUnitId(UUID.randomUUID().toString()),
            queryHash = "forged-query-hash",
            rawQuery = "forged raw query",
        )

        telemetry.complete(forged, LookupStatus.SUCCESS) shouldBe RecordResult.Enqueued(1)

        recorder.handles.single().sessionId shouldBe canonical.sessionId
        recorder.commands.single().shouldBeInstanceOf<CaptureCommand.Lookup>().let { command ->
            command.sourceUnitId shouldBe canonical.sourceUnitId
            command.queryHash shouldBe canonical.queryHash
            command.rawQuery shouldBe canonical.rawQuery
        }
    }

    @Test
    fun `explicit provenance remains immutable when the recorder source advances`() {
        val sessionId = SessionId(UUID.randomUUID().toString())
        val initialSourceId = SourceUnitId(UUID.randomUUID().toString())
        val selectedSourceId = SourceUnitId(UUID.randomUUID().toString())
        val laterSourceId = SourceUnitId(UUID.randomUUID().toString())
        val recorder = FakeRecorder(
            initialSnapshot = ImmersionRecorderSnapshot(
                sessionId = sessionId,
                sourceUnitId = initialSourceId,
                state = ImmersionSessionState.ACTIVE,
            ),
        )
        val provenance = InteractionProvenance(sessionId, selectedSourceId)
        val lookup = DefaultLookupTelemetry(recorder) { RawTextRetention.UNTIL_DELETED }
        val anki = DefaultAnkiOperationRecorder(recorder)
        val lookupToken = lookup.begin(UUID.randomUUID().toString(), "読む", provenance)
        val ankiToken = anki.begin("読む", "よむ", provenance)

        recorder.snapshot.value = recorder.snapshot.value.copy(sourceUnitId = laterSourceId)
        lookup.complete(lookupToken, LookupStatus.SUCCESS)
        anki.complete(
            token = ankiToken,
            operationType = AnkiOperationType.CREATE,
            status = AnkiOperationStatus.SUCCESS,
            noteId = 42,
        )

        recorder.commands.filterIsInstance<CaptureCommand.Lookup>().single().sourceUnitId shouldBe selectedSourceId
        recorder.commands.filterIsInstance<CaptureCommand.AnkiOperation>().single().sourceUnitId shouldBe selectedSourceId
        recorder.handles.map { it.sessionId } shouldBe listOf(sessionId, sessionId)
    }

    @Test
    fun `mismatched explicit session is suppressed without falling back to current provenance`() {
        val recorder = FakeRecorder()
        val currentSnapshot = recorder.snapshot.value
        val staleProvenance = InteractionProvenance(
            sessionId = SessionId(UUID.randomUUID().toString()),
            sourceUnitId = SourceUnitId(UUID.randomUUID().toString()),
        )
        val lookup = DefaultLookupTelemetry(recorder) { RawTextRetention.UNTIL_DELETED }
        val anki = DefaultAnkiOperationRecorder(recorder)
        val lookupToken = lookup.begin(UUID.randomUUID().toString(), "読む", staleProvenance)
        val ankiToken = anki.begin("読む", provenance = staleProvenance)

        lookupToken.sessionId shouldBe null
        lookupToken.sourceUnitId shouldBe null
        ankiToken.sessionId shouldBe null
        ankiToken.sourceUnitId shouldBe null
        lookup.complete(lookupToken, LookupStatus.SUCCESS) shouldBe
            RecordResult.Suppressed(CaptureSuppressionReason.NO_ACTIVE_SESSION)
        anki.complete(
            ankiToken,
            AnkiOperationType.CREATE,
            AnkiOperationStatus.SUCCESS,
            noteId = 42,
        ) shouldBe RecordResult.Suppressed(CaptureSuppressionReason.NO_ACTIVE_SESSION)
        recorder.commands shouldBe emptyList()
        recorder.handles shouldBe emptyList()
        recorder.snapshot.value shouldBe currentSnapshot
    }

    @Test
    fun `lookup caller can suppress ambient attribution when exact provenance is unavailable`() {
        val recorder = FakeRecorder()
        val lookup = DefaultLookupTelemetry(recorder) { RawTextRetention.UNTIL_DELETED }

        val token = lookup.begin(
            intentId = UUID.randomUUID().toString(),
            query = "読む",
            provenance = null,
            allowAmbientFallback = false,
        )

        token.sessionId shouldBe null
        token.sourceUnitId shouldBe null
        lookup.complete(token, LookupStatus.SUCCESS) shouldBe
            RecordResult.Suppressed(CaptureSuppressionReason.NO_ACTIVE_SESSION)
        recorder.commands shouldBe emptyList()
        recorder.handles shouldBe emptyList()
    }

    @Test
    fun `empty failed and cancelled lookups retain distinct status`() {
        val recorder = FakeRecorder()
        val telemetry = DefaultLookupTelemetry(recorder) { RawTextRetention.UNTIL_DELETED }

        listOf(LookupStatus.EMPTY, LookupStatus.FAILED, LookupStatus.CANCELLED).forEach { status ->
            telemetry.complete(
                telemetry.begin(UUID.randomUUID().toString(), "見る"),
                status,
            )
        }

        recorder.commands.filterIsInstance<CaptureCommand.Lookup>().map { it.status } shouldBe
            listOf(LookupStatus.EMPTY, LookupStatus.FAILED, LookupStatus.CANCELLED)
        recorder.commands.filterIsInstance<CaptureCommand.Lookup>().all { it.rawQuery == "見る" } shouldBe true
    }

    @Test
    fun `Anki outcome distinguishes update duplicate and open without conflating create`() {
        val recorder = FakeRecorder()
        val operations = DefaultAnkiOperationRecorder(recorder)

        operations.complete(
            token = operations.begin("読む", "よむ"),
            operationType = AnkiOperationType.UPDATE,
            status = AnkiOperationStatus.SUCCESS,
            noteId = 42,
        )
        operations.complete(
            token = operations.begin("読む", "よむ"),
            operationType = AnkiOperationType.DUPLICATE,
            status = AnkiOperationStatus.DUPLICATE,
            noteId = 42,
        )
        operations.complete(
            token = operations.begin("読む", "よむ"),
            operationType = AnkiOperationType.OPEN,
            status = AnkiOperationStatus.OPENED,
            noteId = 42,
        )

        val recordedOperations = recorder.commands.filterIsInstance<CaptureCommand.AnkiOperation>()
        recordedOperations.map {
            it.operationType to it.status
        } shouldBe listOf(
            AnkiOperationType.UPDATE to AnkiOperationStatus.SUCCESS,
            AnkiOperationType.DUPLICATE to AnkiOperationStatus.DUPLICATE,
            AnkiOperationType.OPEN to AnkiOperationStatus.OPENED,
        )
        recordedOperations.all { it.sourceUnitId == recorder.snapshot.value.sourceUnitId } shouldBe true
        recorder.handles.all { it.sessionId == recorder.snapshot.value.sessionId } shouldBe true
    }

    @Test
    fun `externally successful Anki operation remains repairable when enqueue fails`() = runTest {
        val recorder = FakeRecorder(nextResult = RecordResult.QueueFull)
        val repairStore = InMemoryRepairStore()
        val repaired = mutableListOf<PendingAnkiOperation>()
        val operations = DefaultAnkiOperationRecorder(
            recorder = recorder,
            repairStore = repairStore,
            repairWriter = AnkiOperationRepairWriter {
                repaired += it
                true
            },
        )
        val token = operations.begin("読む", "よむ")

        operations.complete(
            token = token,
            operationType = AnkiOperationType.CREATE,
            status = AnkiOperationStatus.SUCCESS,
            noteId = 99,
        ) shouldBe RecordResult.QueueFull
        repairStore.all().single().token.operationId shouldBe token.operationId

        operations.retryPending() shouldBe 1
        repairStore.all() shouldBe emptyList()
        repaired.single().token.operationId shouldBe token.operationId
    }

    @Test
    fun `Anki completion is idempotent and preserves the first outcome`() {
        val recorder = FakeRecorder()
        val repairStore = InMemoryRepairStore()
        val operations = DefaultAnkiOperationRecorder(recorder, repairStore)
        val token = operations.begin("読む", "よむ")

        operations.complete(
            token = token,
            operationType = AnkiOperationType.CREATE,
            status = AnkiOperationStatus.SUCCESS,
            noteId = 42,
        ) shouldBe RecordResult.Enqueued(1)
        operations.complete(
            token = token,
            operationType = AnkiOperationType.UPDATE,
            status = AnkiOperationStatus.FAILED,
            noteId = 99,
        ) shouldBe RecordResult.Enqueued(0)

        recorder.commands.filterIsInstance<CaptureCommand.AnkiOperation>().single().let { command ->
            command.operationType shouldBe AnkiOperationType.CREATE
            command.status shouldBe AnkiOperationStatus.SUCCESS
            command.noteId shouldBe 42
        }
        repairStore.all().single().let { pending ->
            pending.operationType shouldBe AnkiOperationType.CREATE
            pending.status shouldBe AnkiOperationStatus.SUCCESS
            pending.noteId shouldBe 42
        }
    }

    @Test
    fun `abandon is idempotent and prevents a cancelled operation from completing later`() {
        val recorder = FakeRecorder()
        val operations = DefaultAnkiOperationRecorder(recorder)
        val token = operations.begin("読む", "よむ")

        operations.abandon(token) shouldBe true
        operations.abandon(token) shouldBe false
        operations.complete(
            token = token,
            operationType = AnkiOperationType.CREATE,
            status = AnkiOperationStatus.SUCCESS,
            noteId = 42,
        ) shouldBe RecordResult.Enqueued(0)

        recorder.commands shouldBe emptyList()
        recorder.handles shouldBe emptyList()
    }

    @Test
    fun `repair retry remains queued behind the privacy barrier and runs after it lifts`() = runTest {
        val recorder = FakeRecorder(nextResult = RecordResult.QueueFull)
        val repairStore = InMemoryRepairStore()
        val repaired = mutableListOf<PendingAnkiOperation>()
        var repairAllowed = true
        val operations = DefaultAnkiOperationRecorder(
            recorder = recorder,
            repairStore = repairStore,
            repairWriter = AnkiOperationRepairWriter {
                repaired += it
                true
            },
            repairAllowed = { repairAllowed },
        )
        val token = operations.begin("読む", "よむ")
        operations.complete(
            token = token,
            operationType = AnkiOperationType.CREATE,
            status = AnkiOperationStatus.SUCCESS,
            noteId = 42,
        ) shouldBe RecordResult.QueueFull

        repairAllowed = false
        operations.retryPending() shouldBe 0
        repairStore.all().single().token.operationId shouldBe token.operationId
        repaired shouldBe emptyList()

        repairAllowed = true
        operations.retryPending() shouldBe 1
        repairStore.all() shouldBe emptyList()
        repaired.single().token.operationId shouldBe token.operationId
    }

    @Test
    fun `repair retry stops before the next operation when the privacy barrier activates`() = runTest {
        val recorder = FakeRecorder(nextResult = RecordResult.QueueFull)
        val repairStore = InMemoryRepairStore()
        val repaired = mutableListOf<PendingAnkiOperation>()
        var repairAllowed = true
        val operations = DefaultAnkiOperationRecorder(
            recorder = recorder,
            repairStore = repairStore,
            repairWriter = AnkiOperationRepairWriter {
                repaired += it
                repairAllowed = false
                true
            },
            repairAllowed = { repairAllowed },
        )
        val tokens = List(2) { index ->
            val token = operations.begin("読む$index", "よむ")
            operations.complete(
                token = token,
                operationType = AnkiOperationType.CREATE,
                status = AnkiOperationStatus.SUCCESS,
                noteId = index.toLong(),
            ) shouldBe RecordResult.QueueFull
            token
        }

        operations.retryPending() shouldBe 1

        repaired.shouldHaveSize(1)
        repairStore.all().shouldHaveSize(1)
        repaired.single().token.operationId shouldBe tokens.first().operationId
        repairStore.all().single().token.operationId shouldBe tokens.last().operationId
    }

    @Test
    fun `privacy barrier prevents successful operation from entering durable repair storage`() {
        val recorder = FakeRecorder(
            nextResult = RecordResult.Suppressed(CaptureSuppressionReason.INCOGNITO),
        )
        val repairStore = InMemoryRepairStore()
        val operations = DefaultAnkiOperationRecorder(
            recorder = recorder,
            repairStore = repairStore,
            repairAllowed = { false },
        )

        operations.complete(
            token = operations.begin("読む", "よむ"),
            operationType = AnkiOperationType.CREATE,
            status = AnkiOperationStatus.SUCCESS,
            noteId = 42,
        ) shouldBe RecordResult.Suppressed(CaptureSuppressionReason.INCOGNITO)

        repairStore.putCount shouldBe 0
        repairStore.all() shouldBe emptyList()
    }

    @Test
    fun `suppressed or rejected successful Anki completion is removed from repair storage`() {
        listOf(
            RecordResult.Suppressed(CaptureSuppressionReason.INCOGNITO),
            RecordResult.Suppressed(CaptureSuppressionReason.FEATURE_DISABLED),
            RecordResult.Rejected(ImmersionSessionState.FINALIZED),
        ).forEach { terminalResult ->
            val recorder = FakeRecorder(nextResult = terminalResult)
            val repairStore = InMemoryRepairStore()
            val operations = DefaultAnkiOperationRecorder(recorder, repairStore)

            operations.complete(
                token = operations.begin("読む", "よむ"),
                operationType = AnkiOperationType.CREATE,
                status = AnkiOperationStatus.SUCCESS,
                noteId = 42,
            ) shouldBe terminalResult
            repairStore.all() shouldBe emptyList()
        }
    }

    @Test
    fun `no active session suppresses lookup and Anki persistence`() {
        val recorder = FakeRecorder(
            initialSnapshot = ImmersionRecorderSnapshot(state = ImmersionSessionState.NOT_STARTED),
        )
        val lookup = DefaultLookupTelemetry(recorder) { RawTextRetention.UNTIL_DELETED }
        val anki = DefaultAnkiOperationRecorder(recorder)

        lookup.complete(
            lookup.begin(UUID.randomUUID().toString(), "読む"),
            LookupStatus.SUCCESS,
        ) shouldBe RecordResult.Suppressed(CaptureSuppressionReason.NO_ACTIVE_SESSION)
        anki.complete(
            anki.begin("読む"),
            AnkiOperationType.CREATE,
            AnkiOperationStatus.SUCCESS,
            noteId = 1,
        ) shouldBe RecordResult.Suppressed(CaptureSuppressionReason.NO_ACTIVE_SESSION)
        recorder.commands shouldBe emptyList()
    }

    private class InMemoryRepairStore : AnkiOperationRepairStore {
        private val operations = linkedMapOf<tachiyomi.domain.immersion.model.AnkiOperationId, PendingAnkiOperation>()
        var putCount = 0
            private set

        override fun put(operation: PendingAnkiOperation) {
            putCount++
            operations[operation.token.operationId] = operation
        }

        override fun remove(operationId: tachiyomi.domain.immersion.model.AnkiOperationId) {
            operations.remove(operationId)
        }

        override fun removeForSession(sessionId: SessionId): Int {
            val before = operations.size
            operations.entries.removeAll { it.value.token.sessionId == sessionId }
            return before - operations.size
        }

        override fun clear() {
            operations.clear()
        }

        override fun all(): List<PendingAnkiOperation> = operations.values.toList()
    }

    private class FakeRecorder(
        initialSnapshot: ImmersionRecorderSnapshot = ImmersionRecorderSnapshot(
            sessionId = SessionId(UUID.randomUUID().toString()),
            sourceUnitId = SourceUnitId(UUID.randomUUID().toString()),
            state = ImmersionSessionState.PAUSED,
        ),
        var nextResult: RecordResult = RecordResult.Enqueued(1),
    ) : ImmersionRecorder {
        val snapshot = MutableStateFlow(initialSnapshot)
        val commands = mutableListOf<CaptureCommand>()
        val handles = mutableListOf<SessionHandle>()

        override val state: StateFlow<ImmersionRecorderSnapshot> = snapshot

        override suspend fun startSession(context: SessionContext): SessionStartResult =
            SessionStartResult.Suppressed(CaptureSuppressionReason.FEATURE_DISABLED)

        override fun record(command: CaptureCommand): RecordResult {
            commands += command
            return nextResult
        }

        override fun record(
            handle: SessionHandle,
            command: CaptureCommand,
        ): RecordResult {
            handles += handle
            commands += command
            return nextResult
        }

        override suspend fun pause(reason: PauseReason) = Unit

        override suspend fun pause(
            handle: SessionHandle,
            reason: PauseReason,
        ) = Unit

        override suspend fun resume(reason: ResumeReason) = Unit

        override suspend fun resume(
            handle: SessionHandle,
            reason: ResumeReason,
        ) = Unit

        override suspend fun finalize(reason: FinalizeReason) = Unit

        override suspend fun finalize(
            handle: SessionHandle,
            reason: FinalizeReason,
        ): ImmersionSession? = null

        override suspend fun setIncognito(enabled: Boolean) = Unit

        override suspend fun recoverAbandonedSessions(): Long = 0

        override suspend fun hasSeenSource(sourceUnitId: SourceUnitId): Boolean = false
    }
}
