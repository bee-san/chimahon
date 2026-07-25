package tachiyomi.domain.immersion.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tachiyomi.domain.immersion.model.NonNegativeCounter

enum class ImmersionDiagnosticErrorCode {
    QUEUE_FULL,
    DATABASE_BUSY,
    DATABASE_UNAVAILABLE,
    CONSTRAINT_VIOLATION,
    CORRUPT_VALUE,
    INDEXING_FAILED,
    UNSUPPORTED_LANGUAGE,
    ROLLUP_FAILED,
    UNKNOWN,
}

enum class ImmersionDiagnosticStage {
    WRITE,
    INDEX,
    ROLLUP,
}

data class ImmersionStatsDiagnostics(
    val queueDepth: Int = 0,
    val lastWriteError: ImmersionDiagnosticErrorCode? = null,
    val lastIndexError: ImmersionDiagnosticErrorCode? = null,
    val lastRollupError: ImmersionDiagnosticErrorCode? = null,
    val lastRepairAtEpochMillis: Long? = null,
    val droppedCommandCount: NonNegativeCounter = NonNegativeCounter.ZERO,
) {
    init {
        require(queueDepth >= 0) { "Queue depth cannot be negative" }
        require(lastRepairAtEpochMillis == null || lastRepairAtEpochMillis >= 0) {
            "Repair timestamp cannot be negative"
        }
    }
}

class ImmersionStatsDiagnosticsStore {
    private val mutableState = MutableStateFlow(ImmersionStatsDiagnostics())
    val state: StateFlow<ImmersionStatsDiagnostics> = mutableState.asStateFlow()

    fun setQueueDepth(depth: Int) {
        require(depth >= 0) { "Queue depth cannot be negative" }
        mutableState.update { it.copy(queueDepth = depth) }
    }

    fun recordError(
        stage: ImmersionDiagnosticStage,
        code: ImmersionDiagnosticErrorCode,
    ) {
        mutableState.update {
            when (stage) {
                ImmersionDiagnosticStage.WRITE -> it.copy(lastWriteError = code)
                ImmersionDiagnosticStage.INDEX -> it.copy(lastIndexError = code)
                ImmersionDiagnosticStage.ROLLUP -> it.copy(lastRollupError = code)
            }
        }
    }

    fun recordDroppedCommand() {
        mutableState.update {
            it.copy(droppedCommandCount = it.droppedCommandCount + NonNegativeCounter(1))
        }
    }

    fun recordRepair(epochMillis: Long) {
        require(epochMillis >= 0) { "Repair timestamp cannot be negative" }
        mutableState.update { it.copy(lastRepairAtEpochMillis = epochMillis) }
    }
}
