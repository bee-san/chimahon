// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
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

enum class ImmersionCaptureAdapter {
    NOVEL,
    MANGA,
    VIDEO,
}

enum class ImmersionAdapterDiagnosticKind {
    SNAPSHOT_DROPPED,
    SEMANTIC_COMMAND_DROPPED,
    WORKER_FAILURE,
}

data class ImmersionAdapterDiagnostics(
    val droppedSnapshotCount: NonNegativeCounter = NonNegativeCounter.ZERO,
    val droppedSemanticCommandCount: NonNegativeCounter = NonNegativeCounter.ZERO,
    val workerFailureCount: NonNegativeCounter = NonNegativeCounter.ZERO,
)

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
    val adapterDiagnostics: Map<ImmersionCaptureAdapter, ImmersionAdapterDiagnostics> =
        emptyAdapterDiagnostics(),
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

interface ImmersionStatsDiagnosticsPersistence {
    fun readAdapterCounter(
        adapter: ImmersionCaptureAdapter,
        kind: ImmersionAdapterDiagnosticKind,
    ): Long

    fun writeAdapterCounter(
        adapter: ImmersionCaptureAdapter,
        kind: ImmersionAdapterDiagnosticKind,
        value: Long,
    )
}

class PreferenceImmersionStatsDiagnosticsPersistence(
    preferenceStore: PreferenceStore,
) : ImmersionStatsDiagnosticsPersistence {
    private val adapterCounters: Map<AdapterDiagnosticKey, Preference<Long>> =
        ImmersionCaptureAdapter.entries.flatMap { adapter ->
            ImmersionAdapterDiagnosticKind.entries.map { kind ->
                AdapterDiagnosticKey(adapter, kind)
            }
        }.associateWith { key ->
            preferenceStore.getLong(
                Preference.appStateKey(
                    "immersion_stats_diagnostics_${key.adapter.name}_${key.kind.name}",
                ),
                0L,
            )
        }

    override fun readAdapterCounter(
        adapter: ImmersionCaptureAdapter,
        kind: ImmersionAdapterDiagnosticKind,
    ): Long = adapterCounters.getValue(AdapterDiagnosticKey(adapter, kind)).get().coerceAtLeast(0L)

    override fun writeAdapterCounter(
        adapter: ImmersionCaptureAdapter,
        kind: ImmersionAdapterDiagnosticKind,
        value: Long,
    ) {
        require(value >= 0L) { "Adapter diagnostic counter cannot be negative" }
        adapterCounters.getValue(AdapterDiagnosticKey(adapter, kind)).set(value)
    }

    private data class AdapterDiagnosticKey(
        val adapter: ImmersionCaptureAdapter,
        val kind: ImmersionAdapterDiagnosticKind,
    )
}

class ImmersionStatsDiagnosticsStore(
    private val persistence: ImmersionStatsDiagnosticsPersistence =
        NoOpImmersionStatsDiagnosticsPersistence,
) {
    private val adapterCounterLock = Any()
    private val mutableState = MutableStateFlow(
        ImmersionStatsDiagnostics(adapterDiagnostics = loadAdapterDiagnostics()),
    )
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

    fun recordAdapterDiagnostic(
        adapter: ImmersionCaptureAdapter,
        kind: ImmersionAdapterDiagnosticKind,
    ) {
        synchronized(adapterCounterLock) {
            val current = mutableState.value.adapterDiagnostics
                .getValue(adapter)
                .counter(kind)
                .value
            val updatedValue = if (current == Long.MAX_VALUE) Long.MAX_VALUE else current + 1L
            persistence.writeAdapterCounter(adapter, kind, updatedValue)
            mutableState.update { diagnostics ->
                diagnostics.copy(
                    adapterDiagnostics = diagnostics.adapterDiagnostics + (
                        adapter to diagnostics.adapterDiagnostics
                            .getValue(adapter)
                            .withCounter(kind, NonNegativeCounter(updatedValue))
                        ),
                )
            }
        }
    }

    private fun loadAdapterDiagnostics(): Map<ImmersionCaptureAdapter, ImmersionAdapterDiagnostics> =
        ImmersionCaptureAdapter.entries.associateWith { adapter ->
            ImmersionAdapterDiagnostics(
                droppedSnapshotCount = persistedCounter(
                    adapter,
                    ImmersionAdapterDiagnosticKind.SNAPSHOT_DROPPED,
                ),
                droppedSemanticCommandCount = persistedCounter(
                    adapter,
                    ImmersionAdapterDiagnosticKind.SEMANTIC_COMMAND_DROPPED,
                ),
                workerFailureCount = persistedCounter(
                    adapter,
                    ImmersionAdapterDiagnosticKind.WORKER_FAILURE,
                ),
            )
        }

    private fun persistedCounter(
        adapter: ImmersionCaptureAdapter,
        kind: ImmersionAdapterDiagnosticKind,
    ) = NonNegativeCounter(persistence.readAdapterCounter(adapter, kind).coerceAtLeast(0L))
}

private object NoOpImmersionStatsDiagnosticsPersistence : ImmersionStatsDiagnosticsPersistence {
    override fun readAdapterCounter(
        adapter: ImmersionCaptureAdapter,
        kind: ImmersionAdapterDiagnosticKind,
    ): Long = 0L

    override fun writeAdapterCounter(
        adapter: ImmersionCaptureAdapter,
        kind: ImmersionAdapterDiagnosticKind,
        value: Long,
    ) = Unit
}

private fun emptyAdapterDiagnostics(): Map<ImmersionCaptureAdapter, ImmersionAdapterDiagnostics> =
    ImmersionCaptureAdapter.entries.associateWith { ImmersionAdapterDiagnostics() }

private fun ImmersionAdapterDiagnostics.counter(
    kind: ImmersionAdapterDiagnosticKind,
): NonNegativeCounter = when (kind) {
    ImmersionAdapterDiagnosticKind.SNAPSHOT_DROPPED -> droppedSnapshotCount
    ImmersionAdapterDiagnosticKind.SEMANTIC_COMMAND_DROPPED -> droppedSemanticCommandCount
    ImmersionAdapterDiagnosticKind.WORKER_FAILURE -> workerFailureCount
}

private fun ImmersionAdapterDiagnostics.withCounter(
    kind: ImmersionAdapterDiagnosticKind,
    value: NonNegativeCounter,
): ImmersionAdapterDiagnostics = when (kind) {
    ImmersionAdapterDiagnosticKind.SNAPSHOT_DROPPED -> copy(droppedSnapshotCount = value)
    ImmersionAdapterDiagnosticKind.SEMANTIC_COMMAND_DROPPED -> copy(
        droppedSemanticCommandCount = value,
    )
    ImmersionAdapterDiagnosticKind.WORKER_FAILURE -> copy(workerFailureCount = value)
}
