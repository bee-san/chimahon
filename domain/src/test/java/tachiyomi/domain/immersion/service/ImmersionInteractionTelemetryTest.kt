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
    fun `one lookup intent records exactly once and raw text obeys retention`() {
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

        recorder.commands.filterIsInstance<CaptureCommand.AnkiOperation>().map {
            it.operationType to it.status
        } shouldBe listOf(
            AnkiOperationType.UPDATE to AnkiOperationStatus.SUCCESS,
            AnkiOperationType.DUPLICATE to AnkiOperationStatus.DUPLICATE,
            AnkiOperationType.OPEN to AnkiOperationStatus.OPENED,
        )
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

        override fun put(operation: PendingAnkiOperation) {
            operations[operation.token.operationId] = operation
        }

        override fun remove(operationId: tachiyomi.domain.immersion.model.AnkiOperationId) {
            operations.remove(operationId)
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
