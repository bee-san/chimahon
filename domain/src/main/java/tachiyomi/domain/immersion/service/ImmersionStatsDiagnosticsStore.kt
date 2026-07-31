package tachiyomi.domain.immersion.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

data class ImmersionDurableDiagnostics(
    val maximumQueueDepth: Int = 0,
    val lastWriteLatencyMillis: Long? = null,
    val lastWriteError: ImmersionDiagnosticErrorCode? = null,
    val lastIndexError: ImmersionDiagnosticErrorCode? = null,
    val lastRollupError: ImmersionDiagnosticErrorCode? = null,
    val lastIndexAtEpochMillis: Long? = null,
    val lastRollupAtEpochMillis: Long? = null,
    val lastRepairAtEpochMillis: Long? = null,
    val droppedCommandCount: NonNegativeCounter = NonNegativeCounter.ZERO,
    val abandonedRecoveryCount: NonNegativeCounter = NonNegativeCounter.ZERO,
) {
    init {
        require(maximumQueueDepth >= 0)
        require(lastWriteLatencyMillis == null || lastWriteLatencyMillis >= 0)
        require(lastIndexAtEpochMillis == null || lastIndexAtEpochMillis >= 0)
        require(lastRollupAtEpochMillis == null || lastRollupAtEpochMillis >= 0)
        require(lastRepairAtEpochMillis == null || lastRepairAtEpochMillis >= 0)
    }
}

data class ImmersionStatsDiagnostics(
    val queueDepth: Int = 0,
    val maximumQueueDepth: Int = 0,
    val lastWriteLatencyMillis: Long? = null,
    val lastWriteError: ImmersionDiagnosticErrorCode? = null,
    val lastIndexError: ImmersionDiagnosticErrorCode? = null,
    val lastRollupError: ImmersionDiagnosticErrorCode? = null,
    val lastIndexAtEpochMillis: Long? = null,
    val lastRollupAtEpochMillis: Long? = null,
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
        require(lastIndexAtEpochMillis == null || lastIndexAtEpochMillis >= 0) {
            "Index timestamp cannot be negative"
        }
        require(lastRollupAtEpochMillis == null || lastRollupAtEpochMillis >= 0) {
            "Rollup timestamp cannot be negative"
        }
        require(lastRepairAtEpochMillis == null || lastRepairAtEpochMillis >= 0) {
            "Repair timestamp cannot be negative"
        }
    }
}

interface ImmersionStatsDiagnosticsPersistence {
    fun readDurableDiagnostics(): ImmersionDurableDiagnostics

    fun writeDurableDiagnostics(diagnostics: ImmersionDurableDiagnostics)

    fun readAdapterCounter(
        adapter: ImmersionCaptureAdapter,
        kind: ImmersionAdapterDiagnosticKind,
    ): Long

    fun writeAdapterCounter(
        adapter: ImmersionCaptureAdapter,
        kind: ImmersionAdapterDiagnosticKind,
        value: Long,
    )

    fun clear()
}

class PreferenceImmersionStatsDiagnosticsPersistence(
    preferenceStore: PreferenceStore,
) : ImmersionStatsDiagnosticsPersistence {
    private val schemaVersion = preferenceStore.getInt(
        Preference.appStateKey("immersion_stats_diagnostics_schema_version"),
        0,
    )
    private val maximumQueueDepth = preferenceStore.getInt(
        Preference.appStateKey("immersion_stats_diagnostics_maximum_queue_depth"),
        0,
    )
    private val lastWriteLatencyMillis = preferenceStore.getLong(
        Preference.appStateKey("immersion_stats_diagnostics_last_write_latency_ms"),
        NULL_LONG,
    )
    private val lastWriteError = preferenceStore.getString(
        Preference.appStateKey("immersion_stats_diagnostics_last_write_error"),
        "",
    )
    private val lastIndexError = preferenceStore.getString(
        Preference.appStateKey("immersion_stats_diagnostics_last_index_error"),
        "",
    )
    private val lastRollupError = preferenceStore.getString(
        Preference.appStateKey("immersion_stats_diagnostics_last_rollup_error"),
        "",
    )
    private val lastIndexAtEpochMillis = preferenceStore.getLong(
        Preference.appStateKey("immersion_stats_diagnostics_last_index_at"),
        NULL_LONG,
    )
    private val lastRollupAtEpochMillis = preferenceStore.getLong(
        Preference.appStateKey("immersion_stats_diagnostics_last_rollup_at"),
        NULL_LONG,
    )
    private val lastRepairAtEpochMillis = preferenceStore.getLong(
        Preference.appStateKey("immersion_stats_diagnostics_last_repair_at"),
        NULL_LONG,
    )
    private val droppedCommandCount = preferenceStore.getLong(
        Preference.appStateKey("immersion_stats_diagnostics_dropped_commands"),
        0L,
    )
    private val abandonedRecoveryCount = preferenceStore.getLong(
        Preference.appStateKey("immersion_stats_diagnostics_abandoned_recoveries"),
        0L,
    )
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

    override fun readDurableDiagnostics(): ImmersionDurableDiagnostics {
        if (schemaVersion.get() !in MIN_SUPPORTED_DIAGNOSTICS_SCHEMA_VERSION..DIAGNOSTICS_SCHEMA_VERSION) {
            return ImmersionDurableDiagnostics()
        }
        return ImmersionDurableDiagnostics(
            maximumQueueDepth = maximumQueueDepth.get().coerceAtLeast(0),
            lastWriteLatencyMillis = lastWriteLatencyMillis.get().nullableNonNegative(),
            lastWriteError = lastWriteError.get().diagnosticErrorOrNull(),
            lastIndexError = lastIndexError.get().diagnosticErrorOrNull(),
            lastRollupError = lastRollupError.get().diagnosticErrorOrNull(),
            lastIndexAtEpochMillis = lastIndexAtEpochMillis.get().nullableNonNegative(),
            lastRollupAtEpochMillis = lastRollupAtEpochMillis.get().nullableNonNegative(),
            lastRepairAtEpochMillis = lastRepairAtEpochMillis.get().nullableNonNegative(),
            droppedCommandCount = NonNegativeCounter(
                droppedCommandCount.get().coerceAtLeast(0L),
            ),
            abandonedRecoveryCount = NonNegativeCounter(
                abandonedRecoveryCount.get().coerceAtLeast(0L),
            ),
        )
    }

    override fun writeDurableDiagnostics(diagnostics: ImmersionDurableDiagnostics) {
        schemaVersion.set(DIAGNOSTICS_SCHEMA_VERSION)
        maximumQueueDepth.set(diagnostics.maximumQueueDepth)
        lastWriteLatencyMillis.set(diagnostics.lastWriteLatencyMillis ?: NULL_LONG)
        lastWriteError.set(diagnostics.lastWriteError?.name.orEmpty())
        lastIndexError.set(diagnostics.lastIndexError?.name.orEmpty())
        lastRollupError.set(diagnostics.lastRollupError?.name.orEmpty())
        lastIndexAtEpochMillis.set(diagnostics.lastIndexAtEpochMillis ?: NULL_LONG)
        lastRollupAtEpochMillis.set(diagnostics.lastRollupAtEpochMillis ?: NULL_LONG)
        lastRepairAtEpochMillis.set(diagnostics.lastRepairAtEpochMillis ?: NULL_LONG)
        droppedCommandCount.set(diagnostics.droppedCommandCount.value)
        abandonedRecoveryCount.set(diagnostics.abandonedRecoveryCount.value)
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

    override fun clear() {
        schemaVersion.delete()
        maximumQueueDepth.delete()
        lastWriteLatencyMillis.delete()
        lastWriteError.delete()
        lastIndexError.delete()
        lastRollupError.delete()
        lastIndexAtEpochMillis.delete()
        lastRollupAtEpochMillis.delete()
        lastRepairAtEpochMillis.delete()
        droppedCommandCount.delete()
        abandonedRecoveryCount.delete()
        adapterCounters.values.forEach { it.delete() }
    }

    private data class AdapterDiagnosticKey(
        val adapter: ImmersionCaptureAdapter,
        val kind: ImmersionAdapterDiagnosticKind,
    )

    private companion object {
        const val MIN_SUPPORTED_DIAGNOSTICS_SCHEMA_VERSION = 1
        const val DIAGNOSTICS_SCHEMA_VERSION = 2
        const val NULL_LONG = -1L
    }
}

class ImmersionStatsDiagnosticsStore(
    private val persistence: ImmersionStatsDiagnosticsPersistence =
        NoOpImmersionStatsDiagnosticsPersistence,
) {
    private val persistenceLock = Any()
    private val mutableState = MutableStateFlow(
        persistence.readDurableDiagnostics().toDiagnostics(
            adapterDiagnostics = loadAdapterDiagnostics(),
        ),
    )
    val state: StateFlow<ImmersionStatsDiagnostics> = mutableState.asStateFlow()

    fun setQueueDepth(depth: Int) {
        require(depth >= 0) { "Queue depth cannot be negative" }
        updateDurableState {
            it.copy(
                queueDepth = depth,
                maximumQueueDepth = maxOf(it.maximumQueueDepth, depth),
            )
        }
    }

    fun recordWriteLatency(durationMillis: Long) {
        require(durationMillis >= 0) { "Write latency cannot be negative" }
        updateDurableState { it.copy(lastWriteLatencyMillis = durationMillis) }
    }

    fun recordError(
        stage: ImmersionDiagnosticStage,
        code: ImmersionDiagnosticErrorCode,
    ) {
        updateDurableState {
            when (stage) {
                ImmersionDiagnosticStage.WRITE -> it.copy(lastWriteError = code)
                ImmersionDiagnosticStage.INDEX -> it.copy(lastIndexError = code)
                ImmersionDiagnosticStage.ROLLUP -> it.copy(lastRollupError = code)
            }
        }
    }

    fun recordDroppedCommand() {
        updateDurableState {
            it.copy(droppedCommandCount = it.droppedCommandCount + NonNegativeCounter(1))
        }
    }

    fun recordAbandonedRecovery(count: Long) {
        require(count >= 0) { "Recovered-session count cannot be negative" }
        updateDurableState {
            it.copy(
                abandonedRecoveryCount = it.abandonedRecoveryCount + NonNegativeCounter(count),
            )
        }
    }

    fun setRollupLag(eventCount: Long) {
        require(eventCount >= 0) { "Rollup lag cannot be negative" }
        updateVolatileState { it.copy(rollupLagEventCount = NonNegativeCounter(eventCount)) }
    }

    fun addRollupLag(eventCount: Long) {
        require(eventCount >= 0) { "Rollup lag cannot be negative" }
        updateVolatileState {
            it.copy(
                rollupLagEventCount = it.rollupLagEventCount + NonNegativeCounter(eventCount),
            )
        }
    }

    fun recordIndexSuccess(epochMillis: Long) {
        require(epochMillis >= 0) { "Index timestamp cannot be negative" }
        updateDurableState {
            it.copy(
                lastIndexAtEpochMillis = epochMillis,
                lastIndexError = null,
            )
        }
    }

    fun recordRollupSuccess(epochMillis: Long) {
        require(epochMillis >= 0) { "Rollup timestamp cannot be negative" }
        updateDurableState {
            it.copy(
                lastRollupAtEpochMillis = epochMillis,
                lastRollupError = null,
            )
        }
    }

    fun recordRepair(epochMillis: Long) {
        require(epochMillis >= 0) { "Repair timestamp cannot be negative" }
        updateDurableState { it.copy(lastRepairAtEpochMillis = epochMillis) }
    }

    fun recordAdapterDiagnostic(
        adapter: ImmersionCaptureAdapter,
        kind: ImmersionAdapterDiagnosticKind,
    ) {
        synchronized(persistenceLock) {
            val current = mutableState.value.adapterDiagnostics
                .getValue(adapter)
                .counter(kind)
                .value
            val updatedValue = if (current == Long.MAX_VALUE) Long.MAX_VALUE else current + 1L
            persistence.writeAdapterCounter(adapter, kind, updatedValue)
            val diagnostics = mutableState.value
            mutableState.value = diagnostics.copy(
                adapterDiagnostics = diagnostics.adapterDiagnostics + (
                    adapter to diagnostics.adapterDiagnostics
                        .getValue(adapter)
                        .withCounter(kind, NonNegativeCounter(updatedValue))
                    ),
            )
        }
    }

    fun clear() {
        synchronized(persistenceLock) {
            persistence.clear()
            mutableState.value = ImmersionStatsDiagnostics()
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

    private inline fun updateDurableState(
        transform: (ImmersionStatsDiagnostics) -> ImmersionStatsDiagnostics,
    ) {
        synchronized(persistenceLock) {
            val updated = transform(mutableState.value)
            persistence.writeDurableDiagnostics(updated.toDurableDiagnostics())
            mutableState.value = updated
        }
    }

    private inline fun updateVolatileState(
        transform: (ImmersionStatsDiagnostics) -> ImmersionStatsDiagnostics,
    ) {
        synchronized(persistenceLock) {
            mutableState.value = transform(mutableState.value)
        }
    }
}

private object NoOpImmersionStatsDiagnosticsPersistence : ImmersionStatsDiagnosticsPersistence {
    override fun readDurableDiagnostics() = ImmersionDurableDiagnostics()

    override fun writeDurableDiagnostics(diagnostics: ImmersionDurableDiagnostics) = Unit

    override fun readAdapterCounter(
        adapter: ImmersionCaptureAdapter,
        kind: ImmersionAdapterDiagnosticKind,
    ): Long = 0L

    override fun writeAdapterCounter(
        adapter: ImmersionCaptureAdapter,
        kind: ImmersionAdapterDiagnosticKind,
        value: Long,
    ) = Unit

    override fun clear() = Unit
}

private fun ImmersionDurableDiagnostics.toDiagnostics(
    adapterDiagnostics: Map<ImmersionCaptureAdapter, ImmersionAdapterDiagnostics>,
) = ImmersionStatsDiagnostics(
    maximumQueueDepth = maximumQueueDepth,
    lastWriteLatencyMillis = lastWriteLatencyMillis,
    lastWriteError = lastWriteError,
    lastIndexError = lastIndexError,
    lastRollupError = lastRollupError,
    lastIndexAtEpochMillis = lastIndexAtEpochMillis,
    lastRollupAtEpochMillis = lastRollupAtEpochMillis,
    lastRepairAtEpochMillis = lastRepairAtEpochMillis,
    droppedCommandCount = droppedCommandCount,
    abandonedRecoveryCount = abandonedRecoveryCount,
    adapterDiagnostics = adapterDiagnostics,
)

private fun ImmersionStatsDiagnostics.toDurableDiagnostics() = ImmersionDurableDiagnostics(
    maximumQueueDepth = maximumQueueDepth,
    lastWriteLatencyMillis = lastWriteLatencyMillis,
    lastWriteError = lastWriteError,
    lastIndexError = lastIndexError,
    lastRollupError = lastRollupError,
    lastIndexAtEpochMillis = lastIndexAtEpochMillis,
    lastRollupAtEpochMillis = lastRollupAtEpochMillis,
    lastRepairAtEpochMillis = lastRepairAtEpochMillis,
    droppedCommandCount = droppedCommandCount,
    abandonedRecoveryCount = abandonedRecoveryCount,
)

private fun Long.nullableNonNegative(): Long? = takeIf { it >= 0L }

private fun String.diagnosticErrorOrNull(): ImmersionDiagnosticErrorCode? =
    takeIf(String::isNotBlank)
        ?.let { stored -> runCatching { ImmersionDiagnosticErrorCode.valueOf(stored) }.getOrNull() }

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
