// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.canopus.chimareader.stats.capture.NovelCaptureReconciliationReporter
import eu.kanade.domain.sync.SyncPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.feature.stats.capture.MangaCaptureReconciliationReporter
import tachiyomi.domain.immersion.model.ImmersionDeletionPreview
import tachiyomi.domain.immersion.model.ImmersionIntegrityReport
import tachiyomi.domain.immersion.model.ImmersionMaintenanceSummary
import tachiyomi.domain.immersion.model.ImmersionReindexRequest
import tachiyomi.domain.immersion.model.ImmersionStatsDeletionScope
import tachiyomi.domain.immersion.model.RawTextRetention
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.repository.ImmersionAnkiRepository
import tachiyomi.domain.immersion.repository.ImmersionMaintenanceRepository
import tachiyomi.domain.immersion.service.ImmersionAnalyticsService
import tachiyomi.domain.immersion.service.ImmersionExportDocument
import tachiyomi.domain.immersion.service.ImmersionExportService
import tachiyomi.domain.immersion.service.ImmersionReindexController
import tachiyomi.domain.immersion.service.ImmersionStatsDiagnosticsStore
import tachiyomi.domain.immersion.service.ImmersionStatsPreferences
import tachiyomi.domain.immersion.service.ImmersionStatsVersions
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class StatsMaintenanceScreenModel(
    private val maintenance: ImmersionMaintenanceRepository = Injekt.get(),
    private val analytics: ImmersionAnalyticsService = Injekt.get(),
    private val exports: ImmersionExportService = Injekt.get(),
    private val reindexController: ImmersionReindexController = Injekt.get(),
    private val ankiRepository: ImmersionAnkiRepository = Injekt.get(),
    private val preferences: ImmersionStatsPreferences = Injekt.get(),
    private val diagnostics: ImmersionStatsDiagnosticsStore = Injekt.get(),
    private val syncPreferences: SyncPreferences = Injekt.get(),
) : ScreenModel {
    private val mutableState = MutableStateFlow(
        StatsMaintenanceState(
            retention = preferences.rawTextRetention().get(),
            captureEnabled = preferences.captureEnabled().get(),
            readerIdleTimeoutSeconds = normalizeStatsReaderIdleTimeoutSeconds(
                preferences.readerIdleTimeoutSeconds().get(),
            ),
            indexingEnabled = preferences.indexingEnabled().get(),
            uiEnabled = preferences.uiEnabled().get(),
            ankiSyncEnabled = preferences.ankiSyncEnabled().get(),
            goalsEnabled = preferences.goalsEnabled().get(),
            legacyWritesEnabled = preferences.legacyWritesEnabled().get(),
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
                StatsExportKind.HEALTH_PARITY_JSON -> statsHealthParityDocument(
                    diagnostics = diagnostics.state.value,
                    rollupBacklogRangeCount = maintenance.rollupBacklogCount(),
                    rollupBacklogEventCount = maintenance.rollupBacklogEventCount(
                        ImmersionStatsVersions.ROLLUP,
                    ),
                    novelReport = NovelCaptureReconciliationReporter.report.value,
                    mangaReport = MangaCaptureReconciliationReporter.report.value,
                )
            }
            mutableExportDocuments.emit(document)
            mutableState.update { it.copy(lastOperation = StatsMaintenanceOperation.EXPORT_READY) }
        }
    }

    fun setRetention(retention: RawTextRetention) {
        preferences.acknowledgeRawTextDisclosure(retention)
        mutableState.update { it.copy(retention = retention) }
    }

    fun setCaptureEnabled(enabled: Boolean) {
        preferences.captureEnabled().set(enabled)
        mutableState.update { it.copy(captureEnabled = enabled) }
    }

    fun setReaderIdleTimeoutSeconds(seconds: Int) {
        val validated = validatedStatsReaderIdleTimeoutSeconds(seconds) ?: return
        preferences.readerIdleTimeoutSeconds().set(validated)
        mutableState.update { it.copy(readerIdleTimeoutSeconds = validated) }
    }

    fun setIndexingEnabled(enabled: Boolean) {
        preferences.indexingEnabled().set(enabled)
        mutableState.update { it.copy(indexingEnabled = enabled) }
    }

    fun setUiEnabled(enabled: Boolean) {
        preferences.uiEnabled().set(enabled)
        mutableState.update { it.copy(uiEnabled = enabled) }
    }

    fun setAnkiSyncEnabled(enabled: Boolean) {
        preferences.ankiSyncEnabled().set(enabled)
        mutableState.update { it.copy(ankiSyncEnabled = enabled) }
    }

    fun setGoalsEnabled(enabled: Boolean) {
        preferences.goalsEnabled().set(enabled)
        mutableState.update { it.copy(goalsEnabled = enabled) }
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
            val rows = repairAllDirtyRollups()
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

    fun previewScopedStatsDeletion(scope: ImmersionStatsDeletionScope) {
        launchTask {
            val preview = maintenance.previewScopedStatsDeletion(scope)
            mutableState.update {
                it.copy(
                    scopedDeletionScope = scope,
                    scopedDeletionPreview = preview,
                )
            }
        }
    }

    fun clearScopedStatsDeletionPreview() {
        mutableState.update {
            it.copy(
                scopedDeletionScope = null,
                scopedDeletionPreview = null,
            )
        }
    }

    fun deletePreviewedScopedStats() {
        val snapshot = mutableState.value
        val scope = snapshot.scopedDeletionScope ?: return
        val expectedPreview = snapshot.scopedDeletionPreview ?: return
        launchTask {
            val deleted = maintenance.deleteScopedStats(scope, expectedPreview)
            repairAllDirtyRollups()
            mutableState.update {
                it.copy(
                    scopedDeletionScope = null,
                    scopedDeletionPreview = null,
                    lastAffectedRows = deleted.sessions,
                    lastOperation = StatsMaintenanceOperation.SCOPED_STATS_DELETED,
                )
            }
            refreshSnapshot()
        }
    }

    private suspend fun repairAllDirtyRollups(): Long {
        var rebuiltRows = 0L
        while (true) {
            val batch = analytics.repairDirtyRollups(ROLLUP_REPAIR_BATCH_SIZE)
            if (batch.isEmpty()) return rebuiltRows
            rebuiltRows += batch.sumOf { it.rowCount }
        }
    }

    fun clearAnkiCache(profileId: String) {
        launchTask {
            require(profileId.isNotBlank())
            val deleted = ankiRepository.clearSnapshots(profileId)
            mutableState.update {
                it.copy(
                    lastAffectedRows = deleted,
                    lastOperation = StatsMaintenanceOperation.ANKI_CACHE_DELETED,
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
                captureEnabled = preferences.captureEnabled().get(),
                readerIdleTimeoutSeconds = normalizeStatsReaderIdleTimeoutSeconds(
                    preferences.readerIdleTimeoutSeconds().get(),
                ),
                indexingEnabled = preferences.indexingEnabled().get(),
                uiEnabled = preferences.uiEnabled().get(),
                ankiSyncEnabled = preferences.ankiSyncEnabled().get(),
                goalsEnabled = preferences.goalsEnabled().get(),
                legacyWritesEnabled = preferences.legacyWritesEnabled().get(),
            )
        }
    }

    private fun launchTask(block: suspend () -> Unit) {
        screenModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null) }
            runCatching { block() }
                .onFailure {
                    mutableState.update {
                        it.copy(error = StatsMaintenanceError.OPERATION_FAILED)
                    }
                }
            mutableState.update { it.copy(busy = false) }
        }
    }
}

private const val ROLLUP_REPAIR_BATCH_SIZE = 366

data class StatsMaintenanceState(
    val summary: ImmersionMaintenanceSummary? = null,
    val integrity: ImmersionIntegrityReport? = null,
    val rawTextDeletionPreview: Long = 0,
    val deletionPreview: ImmersionDeletionPreview? = null,
    val scopedDeletionScope: ImmersionStatsDeletionScope? = null,
    val scopedDeletionPreview: ImmersionDeletionPreview? = null,
    val retention: RawTextRetention,
    val captureEnabled: Boolean,
    val readerIdleTimeoutSeconds: Int,
    val indexingEnabled: Boolean,
    val uiEnabled: Boolean,
    val ankiSyncEnabled: Boolean,
    val goalsEnabled: Boolean,
    val legacyWritesEnabled: Boolean,
    val busy: Boolean = false,
    val error: StatsMaintenanceError? = null,
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
    HEALTH_PARITY_JSON,
}

enum class StatsMaintenanceOperation {
    EXPORT_READY,
    RAW_TEXT_DELETED,
    ROLLUPS_REBUILT,
    INDEX_REBUILT,
    ALL_STATS_RESET,
    CONFLICTS_RESOLVED,
    SCOPED_STATS_DELETED,
    ANKI_CACHE_DELETED,
}

enum class StatsMaintenanceError {
    OPERATION_FAILED,
}
