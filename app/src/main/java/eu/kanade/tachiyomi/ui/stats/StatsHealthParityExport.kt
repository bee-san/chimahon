// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import com.canopus.chimareader.stats.capture.NovelReconciliationReport
import com.canopus.chimareader.stats.capture.NovelReconciliationScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.feature.stats.capture.MangaReconciliationReport
import mihon.feature.stats.capture.MangaReconciliationScope
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
    val rollupLagEventCount: Long,
)

@Serializable
internal data class StatsParityAggregateExport(
    val media: StatsParityMedia,
    val scope: StatsParityScope,
    val matched: Int,
    val diverged: Int,
    val nonComparable: Int,
)

@Serializable
internal enum class StatsParityMedia {
    NOVEL,
    MANGA,
}

@Serializable
internal enum class StatsParityScope {
    SESSION,
    DAY,
}

internal fun statsHealthParityExport(
    diagnostics: ImmersionStatsDiagnostics,
    novelReport: NovelReconciliationReport,
    mangaReport: MangaReconciliationReport,
    createdAtEpochMillis: Long,
): StatsHealthParityExport {
    require(createdAtEpochMillis >= 0)
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
        diagnostics = diagnostics.toExport(),
        parity = StatsParityMedia.entries.flatMap { media ->
            StatsParityScope.entries.map { scope ->
                val scoped = observations.filter { it.media == media && it.scope == scope }
                StatsParityAggregateExport(
                    media = media,
                    scope = scope,
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
    novelReport: NovelReconciliationReport,
    mangaReport: MangaReconciliationReport,
    createdAtEpochMillis: Long = System.currentTimeMillis(),
): ImmersionExportDocument {
    val payload = statsHealthParityExport(
        diagnostics = diagnostics,
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

private fun ImmersionStatsDiagnostics.toExport() = StatsHealthDiagnosticsExport(
    queueDepth = queueDepth,
    maximumQueueDepth = maximumQueueDepth,
    lastWriteLatencyMillis = lastWriteLatencyMillis,
    lastWriteErrorCode = lastWriteError?.name,
    lastIndexErrorCode = lastIndexError?.name,
    lastRollupErrorCode = lastRollupError?.name,
    lastRepairAtEpochMillis = lastRepairAtEpochMillis,
    droppedCommandCount = droppedCommandCount.value,
    abandonedRecoveryCount = abandonedRecoveryCount.value,
    rollupLagEventCount = rollupLagEventCount.value,
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

private const val STATS_HEALTH_PARITY_SCHEMA_VERSION = 1

private val STATS_HEALTH_PARITY_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    prettyPrint = true
}
