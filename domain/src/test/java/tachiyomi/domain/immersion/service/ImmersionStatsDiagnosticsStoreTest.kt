// SPDX-License-Identifier: MIT

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
        store.recordRepair(1234)

        store.state.value shouldBe ImmersionStatsDiagnostics(
            queueDepth = 4,
            maximumQueueDepth = 4,
            lastWriteLatencyMillis = 27,
            lastWriteError = ImmersionDiagnosticErrorCode.DATABASE_BUSY,
            lastIndexError = ImmersionDiagnosticErrorCode.UNSUPPORTED_LANGUAGE,
            lastRollupError = ImmersionDiagnosticErrorCode.ROLLUP_FAILED,
            lastRepairAtEpochMillis = 1234,
            droppedCommandCount = NonNegativeCounter(1),
            abandonedRecoveryCount = NonNegativeCounter(2),
            rollupLagEventCount = NonNegativeCounter(8),
        )
        shouldThrow<IllegalArgumentException> { store.setQueueDepth(-1) }
        shouldThrow<IllegalArgumentException> { store.recordWriteLatency(-1) }
        shouldThrow<IllegalArgumentException> { store.recordAbandonedRecovery(-1) }
        shouldThrow<IllegalArgumentException> { store.setRollupLag(-1) }
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

        val restored = ImmersionStatsDiagnosticsStore(persistence).state.value.adapterDiagnostics
        restored.getValue(ImmersionCaptureAdapter.NOVEL).droppedSnapshotCount shouldBe
            NonNegativeCounter(1)
        restored.getValue(ImmersionCaptureAdapter.MANGA).droppedSemanticCommandCount shouldBe
            NonNegativeCounter(1)
        restored.getValue(ImmersionCaptureAdapter.VIDEO).workerFailureCount shouldBe
            NonNegativeCounter(2)
        persistence.values.keys.all { (adapter, kind) ->
            adapter in ImmersionCaptureAdapter.entries &&
                kind in ImmersionAdapterDiagnosticKind.entries
        } shouldBe true
    }

    private class FakeDiagnosticsPersistence : ImmersionStatsDiagnosticsPersistence {
        val values = mutableMapOf<Pair<ImmersionCaptureAdapter, ImmersionAdapterDiagnosticKind>, Long>()

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
    }
}
