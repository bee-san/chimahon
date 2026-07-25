package tachiyomi.domain.immersion.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ImmersionStatsDiagnosticsStoreTest {
    @Test
    fun `diagnostics expose only bounded counters typed errors and repair time`() {
        val store = ImmersionStatsDiagnosticsStore()

        store.setQueueDepth(4)
        store.recordError(ImmersionDiagnosticStage.WRITE, ImmersionDiagnosticErrorCode.DATABASE_BUSY)
        store.recordError(ImmersionDiagnosticStage.INDEX, ImmersionDiagnosticErrorCode.UNSUPPORTED_LANGUAGE)
        store.recordError(ImmersionDiagnosticStage.ROLLUP, ImmersionDiagnosticErrorCode.ROLLUP_FAILED)
        store.recordDroppedCommand()
        store.recordRepair(1234)

        store.state.value shouldBe ImmersionStatsDiagnostics(
            queueDepth = 4,
            lastWriteError = ImmersionDiagnosticErrorCode.DATABASE_BUSY,
            lastIndexError = ImmersionDiagnosticErrorCode.UNSUPPORTED_LANGUAGE,
            lastRollupError = ImmersionDiagnosticErrorCode.ROLLUP_FAILED,
            lastRepairAtEpochMillis = 1234,
            droppedCommandCount = tachiyomi.domain.immersion.model.NonNegativeCounter(1),
        )
        shouldThrow<IllegalArgumentException> { store.setQueueDepth(-1) }
        shouldThrow<IllegalArgumentException> { store.recordRepair(-1) }
    }
}
