// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import com.canopus.chimareader.stats.capture.NovelReconciliationReport
import com.canopus.chimareader.stats.capture.NovelReconciliationScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.feature.stats.capture.MangaReconciliationReport
import mihon.feature.stats.capture.MangaReconciliationScope
import tachiyomi.domain.immersion.service.ImmersionCaptureAdapter
import tachiyomi.domain.immersion.service.ImmersionExportDocument
import tachiyomi.domain.immersion.service.ImmersionShadowResult
import tachiyomi.domain.immersion.service.ImmersionStatsDiagnostics

@Serializable
internal data class StatsHealthParityExport(
    val schemaVersion: Int = STATS_HEALTH_PARITY_SCHEMA_VERSION,
    val createdAtEpochMillis: Long,
    val diagnostics: StatsHealthDiagnosticsExport,
    val parity: List<StatsParityAggregateExport>,
)

@Serializable
internal data class StatsHealthDiagnosticsExport(
    val queueDepth: Int,
    val maximumQueueDepth: Int,
    val lastWriteLatencyMillis: Long?,
    val lastWriteErrorCode: String?,
    val lastIndexErrorCode: String?,
    val lastRollupErrorCode: String?,
    val lastRepairAtEpochMillis: Long?,
    val droppedCommandCount: Long,
    val abandonedRecoveryCount: Long,
    val rollupBacklogRangeCount: Long,
    val rollupBacklogEventCount: Long,
    val adapters: List<StatsAdapterDiagnosticsExport>,
)

@Serializable
internal data class StatsAdapterDiagnosticsExport(
    val adapter: StatsCaptureAdapter,
    val droppedSnapshotCount: Long,
    val droppedSemanticCommandCount: Long,
    val workerFailureCount: Long,
)

@Serializable
internal data class StatsParityAggregateExport(
    val media: StatsParityMedia,
    val scope: StatsParityScope,
    val observations: Int,
    val evidenceAvailable: Boolean,
    val matched: Int,
    val diverged: Int,
    val nonComparable: Int,
)

@Serializable
internal enum class StatsParityMedia {
    NOVEL,
    MANGA,
    VIDEO,
}

@Serializable
internal enum class StatsParityScope {
    SESSION,
    DAY,
}

@Serializable
internal enum class StatsCaptureAdapter {
    NOVEL,
    MANGA,
    VIDEO,
}

internal fun statsHealthParityExport(
    diagnostics: ImmersionStatsDiagnostics,
    rollupBacklogRangeCount: Long,
    rollupBacklogEventCount: Long,
    novelReport: NovelReconciliationReport,
    mangaReport: MangaReconciliationReport,
    createdAtEpochMillis: Long,
): StatsHealthParityExport {
    require(createdAtEpochMillis >= 0)
    require(rollupBacklogRangeCount >= 0)
    require(rollupBacklogEventCount >= 0)
    val observations = buildList {
        novelReport.entries.forEach { entry ->
            add(
                StatsParityObservation(
                    media = StatsParityMedia.NOVEL,
                    scope = when (entry.scope) {
                        NovelReconciliationScope.SESSION -> StatsParityScope.SESSION
                        NovelReconciliationScope.DAY -> StatsParityScope.DAY
                    },
                    outcome = parityOutcome(entry.legacyComparable, entry.result),
                ),
            )
        }
        mangaReport.entries.forEach { entry ->
            add(
                StatsParityObservation(
                    media = StatsParityMedia.MANGA,
                    scope = when (entry.scope) {
                        MangaReconciliationScope.SESSION -> StatsParityScope.SESSION
                        MangaReconciliationScope.DAY -> StatsParityScope.DAY
                    },
                    outcome = parityOutcome(entry.legacyComparable, entry.result),
                ),
            )
        }
    }
    return StatsHealthParityExport(
        createdAtEpochMillis = createdAtEpochMillis,
        diagnostics = diagnostics.toExport(
            rollupBacklogRangeCount = rollupBacklogRangeCount,
            rollupBacklogEventCount = rollupBacklogEventCount,
        ),
        parity = StatsParityMedia.entries.flatMap { media ->
            StatsParityScope.entries.map { scope ->
                val scoped = observations.filter { it.media == media && it.scope == scope }
                StatsParityAggregateExport(
                    media = media,
                    scope = scope,
                    observations = scoped.size,
                    evidenceAvailable = scoped.isNotEmpty(),
                    matched = scoped.count { it.outcome == StatsParityOutcome.MATCHED },
                    diverged = scoped.count { it.outcome == StatsParityOutcome.DIVERGED },
                    nonComparable = scoped.count {
                        it.outcome == StatsParityOutcome.NON_COMPARABLE
                    },
                )
            }
        },
    )
}

internal fun statsHealthParityDocument(
    diagnostics: ImmersionStatsDiagnostics,
    rollupBacklogRangeCount: Long,
    rollupBacklogEventCount: Long,
    novelReport: NovelReconciliationReport,
    mangaReport: MangaReconciliationReport,
    createdAtEpochMillis: Long = System.currentTimeMillis(),
): ImmersionExportDocument {
    val payload = statsHealthParityExport(
        diagnostics = diagnostics,
        rollupBacklogRangeCount = rollupBacklogRangeCount,
        rollupBacklogEventCount = rollupBacklogEventCount,
        novelReport = novelReport,
        mangaReport = mangaReport,
        createdAtEpochMillis = createdAtEpochMillis,
    )
    return ImmersionExportDocument(
        fileName = "chimahon-stats-health-parity.json",
        mimeType = "application/json",
        bytes = STATS_HEALTH_PARITY_JSON.encodeToString(payload).encodeToByteArray(),
    )
}

private fun ImmersionStatsDiagnostics.toExport(
    rollupBacklogRangeCount: Long,
    rollupBacklogEventCount: Long,
) = StatsHealthDiagnosticsExport(
    queueDepth = queueDepth,
    maximumQueueDepth = maximumQueueDepth,
    lastWriteLatencyMillis = lastWriteLatencyMillis,
    lastWriteErrorCode = lastWriteError?.name,
    lastIndexErrorCode = lastIndexError?.name,
    lastRollupErrorCode = lastRollupError?.name,
    lastRepairAtEpochMillis = lastRepairAtEpochMillis,
    droppedCommandCount = droppedCommandCount.value,
    abandonedRecoveryCount = abandonedRecoveryCount.value,
    rollupBacklogRangeCount = rollupBacklogRangeCount,
    rollupBacklogEventCount = rollupBacklogEventCount,
    adapters = ImmersionCaptureAdapter.entries.map { adapter ->
        val counters = adapterDiagnostics.getValue(adapter)
        StatsAdapterDiagnosticsExport(
            adapter = StatsCaptureAdapter.valueOf(adapter.name),
            droppedSnapshotCount = counters.droppedSnapshotCount.value,
            droppedSemanticCommandCount = counters.droppedSemanticCommandCount.value,
            workerFailureCount = counters.workerFailureCount.value,
        )
    },
)

private fun parityOutcome(
    legacyComparable: Boolean,
    result: ImmersionShadowResult?,
): StatsParityOutcome {
    if (!legacyComparable || result == null) return StatsParityOutcome.NON_COMPARABLE
    return when (result) {
        ImmersionShadowResult.Matched -> StatsParityOutcome.MATCHED
        is ImmersionShadowResult.Diverged -> StatsParityOutcome.DIVERGED
    }
}

private data class StatsParityObservation(
    val media: StatsParityMedia,
    val scope: StatsParityScope,
    val outcome: StatsParityOutcome,
)

private enum class StatsParityOutcome {
    MATCHED,
    DIVERGED,
    NON_COMPARABLE,
}

private const val STATS_HEALTH_PARITY_SCHEMA_VERSION = 3

private val STATS_HEALTH_PARITY_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    prettyPrint = true
}
