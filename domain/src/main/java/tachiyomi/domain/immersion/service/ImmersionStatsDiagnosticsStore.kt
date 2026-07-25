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
    val maximumQueueDepth: Int = 0,
    val lastWriteLatencyMillis: Long? = null,
    val lastWriteError: ImmersionDiagnosticErrorCode? = null,
    val lastIndexError: ImmersionDiagnosticErrorCode? = null,
    val lastRollupError: ImmersionDiagnosticErrorCode? = null,
    val lastRepairAtEpochMillis: Long? = null,
    val droppedCommandCount: NonNegativeCounter = NonNegativeCounter.ZERO,
    val abandonedRecoveryCount: NonNegativeCounter = NonNegativeCounter.ZERO,
    val rollupLagEventCount: NonNegativeCounter = NonNegativeCounter.ZERO,
) {
    init {
        require(queueDepth >= 0) { "Queue depth cannot be negative" }
        require(maximumQueueDepth >= queueDepth) { "Maximum queue depth cannot be below current depth" }
        require(lastWriteLatencyMillis == null || lastWriteLatencyMillis >= 0) {
            "Write latency cannot be negative"
        }
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
        mutableState.update {
            it.copy(
                queueDepth = depth,
                maximumQueueDepth = maxOf(it.maximumQueueDepth, depth),
            )
        }
    }

    fun recordWriteLatency(durationMillis: Long) {
        require(durationMillis >= 0) { "Write latency cannot be negative" }
        mutableState.update { it.copy(lastWriteLatencyMillis = durationMillis) }
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

    fun recordAbandonedRecovery(count: Long) {
        require(count >= 0) { "Recovered-session count cannot be negative" }
        mutableState.update {
            it.copy(
                abandonedRecoveryCount = it.abandonedRecoveryCount + NonNegativeCounter(count),
            )
        }
    }

    fun setRollupLag(eventCount: Long) {
        require(eventCount >= 0) { "Rollup lag cannot be negative" }
        mutableState.update { it.copy(rollupLagEventCount = NonNegativeCounter(eventCount)) }
    }

    fun addRollupLag(eventCount: Long) {
        require(eventCount >= 0) { "Rollup lag cannot be negative" }
        mutableState.update {
            it.copy(
                rollupLagEventCount = it.rollupLagEventCount + NonNegativeCounter(eventCount),
            )
        }
    }

    fun recordRepair(epochMillis: Long) {
        require(epochMillis >= 0) { "Repair timestamp cannot be negative" }
        mutableState.update { it.copy(lastRepairAtEpochMillis = epochMillis) }
    }
}
