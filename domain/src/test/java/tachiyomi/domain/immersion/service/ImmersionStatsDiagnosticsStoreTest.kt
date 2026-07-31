package tachiyomi.domain.immersion.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.NonNegativeCounter

class ImmersionStatsDiagnosticsStoreTest {
    @Test
    fun `diagnostics expose only bounded counters typed errors and repair time`() {
        val store = ImmersionStatsDiagnosticsStore()

        store.setQueueDepth(4)
        store.recordWriteLatency(27)
        store.recordError(ImmersionDiagnosticStage.WRITE, ImmersionDiagnosticErrorCode.DATABASE_BUSY)
        store.recordError(ImmersionDiagnosticStage.INDEX, ImmersionDiagnosticErrorCode.UNSUPPORTED_LANGUAGE)
        store.recordError(ImmersionDiagnosticStage.ROLLUP, ImmersionDiagnosticErrorCode.ROLLUP_FAILED)
        store.recordDroppedCommand()
        store.recordAbandonedRecovery(2)
        store.setRollupLag(8)
        store.recordIndexSuccess(1_100)
        store.recordRollupSuccess(1_200)
        store.recordRepair(1234)

        store.state.value shouldBe ImmersionStatsDiagnostics(
            queueDepth = 4,
            maximumQueueDepth = 4,
            lastWriteLatencyMillis = 27,
            lastWriteError = ImmersionDiagnosticErrorCode.DATABASE_BUSY,
            lastIndexAtEpochMillis = 1_100,
            lastRollupAtEpochMillis = 1_200,
            lastRepairAtEpochMillis = 1234,
            droppedCommandCount = NonNegativeCounter(1),
            abandonedRecoveryCount = NonNegativeCounter(2),
            rollupLagEventCount = NonNegativeCounter(8),
        )
        shouldThrow<IllegalArgumentException> { store.setQueueDepth(-1) }
        shouldThrow<IllegalArgumentException> { store.recordWriteLatency(-1) }
        shouldThrow<IllegalArgumentException> { store.recordAbandonedRecovery(-1) }
        shouldThrow<IllegalArgumentException> { store.setRollupLag(-1) }
        shouldThrow<IllegalArgumentException> { store.recordIndexSuccess(-1) }
        shouldThrow<IllegalArgumentException> { store.recordRollupSuccess(-1) }
        shouldThrow<IllegalArgumentException> { store.recordRepair(-1) }
    }

    @Test
    fun `typed adapter counters survive store recreation without identity data`() {
        val persistence = FakeDiagnosticsPersistence()
        val store = ImmersionStatsDiagnosticsStore(persistence)

        store.recordAdapterDiagnostic(
            ImmersionCaptureAdapter.NOVEL,
            ImmersionAdapterDiagnosticKind.SNAPSHOT_DROPPED,
        )
        store.recordAdapterDiagnostic(
            ImmersionCaptureAdapter.MANGA,
            ImmersionAdapterDiagnosticKind.SEMANTIC_COMMAND_DROPPED,
        )
        repeat(2) {
            store.recordAdapterDiagnostic(
                ImmersionCaptureAdapter.VIDEO,
                ImmersionAdapterDiagnosticKind.WORKER_FAILURE,
            )
        }
        store.setQueueDepth(6)
        store.recordWriteLatency(19)
        store.recordError(
            ImmersionDiagnosticStage.ROLLUP,
            ImmersionDiagnosticErrorCode.ROLLUP_FAILED,
        )
        store.recordDroppedCommand()
        store.recordAbandonedRecovery(3)
        store.recordIndexSuccess(4_000)
        store.recordRollupSuccess(4_500)
        store.recordRepair(5_000)

        val restored = ImmersionStatsDiagnosticsStore(persistence).state.value
        restored.adapterDiagnostics
            .getValue(ImmersionCaptureAdapter.NOVEL)
            .droppedSnapshotCount shouldBe
            NonNegativeCounter(1)
        restored.adapterDiagnostics
            .getValue(ImmersionCaptureAdapter.MANGA)
            .droppedSemanticCommandCount shouldBe
            NonNegativeCounter(1)
        restored.adapterDiagnostics
            .getValue(ImmersionCaptureAdapter.VIDEO)
            .workerFailureCount shouldBe
            NonNegativeCounter(2)
        restored.queueDepth shouldBe 0
        restored.maximumQueueDepth shouldBe 6
        restored.lastWriteLatencyMillis shouldBe 19
        restored.lastIndexAtEpochMillis shouldBe 4_000
        restored.lastRollupAtEpochMillis shouldBe 4_500
        restored.lastRollupError shouldBe null
        restored.droppedCommandCount shouldBe NonNegativeCounter(1)
        restored.abandonedRecoveryCount shouldBe NonNegativeCounter(3)
        restored.lastRepairAtEpochMillis shouldBe 5_000
        persistence.values.keys.all { (adapter, kind) ->
            adapter in ImmersionCaptureAdapter.entries &&
                kind in ImmersionAdapterDiagnosticKind.entries
        } shouldBe true

        store.clear()

        store.state.value shouldBe ImmersionStatsDiagnostics()
        ImmersionStatsDiagnosticsStore(persistence).state.value shouldBe
            ImmersionStatsDiagnostics()
    }

    private class FakeDiagnosticsPersistence : ImmersionStatsDiagnosticsPersistence {
        val values = mutableMapOf<Pair<ImmersionCaptureAdapter, ImmersionAdapterDiagnosticKind>, Long>()
        var durable = ImmersionDurableDiagnostics()

        override fun readDurableDiagnostics(): ImmersionDurableDiagnostics = durable

        override fun writeDurableDiagnostics(diagnostics: ImmersionDurableDiagnostics) {
            durable = diagnostics
        }

        override fun readAdapterCounter(
            adapter: ImmersionCaptureAdapter,
            kind: ImmersionAdapterDiagnosticKind,
        ): Long = values[adapter to kind] ?: 0L

        override fun writeAdapterCounter(
            adapter: ImmersionCaptureAdapter,
            kind: ImmersionAdapterDiagnosticKind,
            value: Long,
        ) {
            values[adapter to kind] = value
        }

        override fun clear() {
            values.clear()
            durable = ImmersionDurableDiagnostics()
        }
    }
}
