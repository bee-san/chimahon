// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.sync.SyncPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.immersion.model.ImmersionDeletionPreview
import tachiyomi.domain.immersion.model.ImmersionIntegrityReport
import tachiyomi.domain.immersion.model.ImmersionMaintenanceSummary
import tachiyomi.domain.immersion.model.ImmersionReindexRequest
import tachiyomi.domain.immersion.model.RawTextRetention
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.repository.ImmersionMaintenanceRepository
import tachiyomi.domain.immersion.service.ImmersionAnalyticsService
import tachiyomi.domain.immersion.service.ImmersionExportDocument
import tachiyomi.domain.immersion.service.ImmersionExportService
import tachiyomi.domain.immersion.service.ImmersionReindexController
import tachiyomi.domain.immersion.service.ImmersionStatsPreferences
import tachiyomi.domain.immersion.service.ImmersionStatsVersions
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class StatsMaintenanceScreenModel(
    private val maintenance: ImmersionMaintenanceRepository = Injekt.get(),
    private val analytics: ImmersionAnalyticsService = Injekt.get(),
    private val exports: ImmersionExportService = Injekt.get(),
    private val reindexController: ImmersionReindexController = Injekt.get(),
    private val preferences: ImmersionStatsPreferences = Injekt.get(),
    private val syncPreferences: SyncPreferences = Injekt.get(),
) : ScreenModel {
    private val mutableState = MutableStateFlow(
        StatsMaintenanceState(
            retention = preferences.rawTextRetention().get(),
        ),
    )
    val state: StateFlow<StatsMaintenanceState> = mutableState.asStateFlow()

    private val mutableExportDocuments = MutableSharedFlow<ImmersionExportDocument>()
    val exportDocuments: SharedFlow<ImmersionExportDocument> = mutableExportDocuments.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        launchTask {
            refreshSnapshot()
        }
    }

    fun export(kind: StatsExportKind) {
        launchTask {
            val document = when (kind) {
                StatsExportKind.AGGREGATE_JSON -> exports.aggregateJson(StatsFilter())
                StatsExportKind.AGGREGATE_CSV -> exports.aggregateCsv(StatsFilter())
                StatsExportKind.EVENT_JSON -> exports.eventJson(includeRawText = false)
                StatsExportKind.EVENT_JSON_WITH_RAW_TEXT -> exports.eventJson(includeRawText = true)
                StatsExportKind.VOCABULARY_CSV -> exports.vocabularyCsv(StatsFilter())
                StatsExportKind.CHARACTERS_CSV -> exports.charactersCsv(StatsFilter())
            }
            mutableExportDocuments.emit(document)
            mutableState.update { it.copy(lastOperation = StatsMaintenanceOperation.EXPORT_READY) }
        }
    }

    fun setRetention(retention: RawTextRetention) {
        preferences.acknowledgeRawTextDisclosure(retention)
        mutableState.update { it.copy(retention = retention) }
    }

    fun deleteRawText() {
        launchTask {
            val deleted = maintenance.deleteRawText(updatedAtEpochMillis = System.currentTimeMillis())
            mutableState.update {
                it.copy(
                    rawTextDeletionPreview = 0,
                    lastAffectedRows = deleted,
                    lastOperation = StatsMaintenanceOperation.RAW_TEXT_DELETED,
                )
            }
            refreshSnapshot()
        }
    }

    fun rebuildRollups() {
        launchTask {
            maintenance.beginRollupRebuild(
                rollupVersion = ImmersionStatsVersions.ROLLUP,
                repairCursor = "manual-maintenance",
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
            val rows = analytics.repairDirtyRollups(366).sumOf { it.rowCount }
            mutableState.update {
                it.copy(
                    lastAffectedRows = rows,
                    lastOperation = StatsMaintenanceOperation.ROLLUPS_REBUILT,
                )
            }
            refreshSnapshot()
        }
    }

    fun rebuildIndex() {
        launchTask {
            val progress = reindexController.reindex(ImmersionReindexRequest())
            mutableState.update {
                it.copy(
                    lastAffectedRows = progress.processed,
                    lastOperation = StatsMaintenanceOperation.INDEX_REBUILT,
                )
            }
            refreshSnapshot()
        }
    }

    fun resetAllStats() {
        launchTask {
            val preview = maintenance.resetAllStats(
                deviceId = syncPreferences.uniqueDeviceID(),
                deletedAtEpochMillis = System.currentTimeMillis(),
            )
            mutableState.update {
                it.copy(
                    deletionPreview = ImmersionDeletionPreview(0, 0, 0, 0, 0, 0),
                    rawTextDeletionPreview = 0,
                    lastAffectedRows = preview.sessions,
                    lastOperation = StatsMaintenanceOperation.ALL_STATS_RESET,
                )
            }
            refreshSnapshot()
        }
    }

    fun resolveMergeConflicts() {
        launchTask {
            val resolved = maintenance.resolveMergeConflictsKeepingLocal()
            mutableState.update {
                it.copy(
                    lastAffectedRows = resolved,
                    lastOperation = StatsMaintenanceOperation.CONFLICTS_RESOLVED,
                )
            }
            refreshSnapshot()
        }
    }

    private suspend fun refreshSnapshot() {
        val summary = maintenance.maintenanceSummary()
        val integrity = maintenance.validateInvariants(ImmersionStatsVersions.ROLLUP)
        val rawTextPreview = maintenance.previewRawTextDeletion()
        val deletionPreview = maintenance.previewAllStatsDeletion()
        mutableState.update {
            it.copy(
                summary = summary,
                integrity = integrity,
                rawTextDeletionPreview = rawTextPreview,
                deletionPreview = deletionPreview,
            )
        }
    }

    private fun launchTask(block: suspend () -> Unit) {
        screenModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null) }
            runCatching { block() }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(error = error.message ?: error::class.java.simpleName)
                    }
                }
            mutableState.update { it.copy(busy = false) }
        }
    }
}

data class StatsMaintenanceState(
    val summary: ImmersionMaintenanceSummary? = null,
    val integrity: ImmersionIntegrityReport? = null,
    val rawTextDeletionPreview: Long = 0,
    val deletionPreview: ImmersionDeletionPreview? = null,
    val retention: RawTextRetention,
    val busy: Boolean = false,
    val error: String? = null,
    val lastAffectedRows: Long = 0,
    val lastOperation: StatsMaintenanceOperation? = null,
)

enum class StatsExportKind {
    AGGREGATE_JSON,
    AGGREGATE_CSV,
    EVENT_JSON,
    EVENT_JSON_WITH_RAW_TEXT,
    VOCABULARY_CSV,
    CHARACTERS_CSV,
}

enum class StatsMaintenanceOperation {
    EXPORT_READY,
    RAW_TEXT_DELETED,
    ROLLUPS_REBUILT,
    INDEX_REBUILT,
    ALL_STATS_RESET,
    CONFLICTS_RESOLVED,
}
