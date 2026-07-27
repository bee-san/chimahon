// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.AnalyticsTitleRow
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.ImmersionTitleMutation
import tachiyomi.domain.immersion.model.ImmersionTitleMutationPreview
import tachiyomi.domain.immersion.model.ImmersionTitleMutationRequest
import tachiyomi.domain.immersion.model.LocalDateRange
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.repository.ImmersionMaintenanceRepository
import tachiyomi.domain.immersion.service.ImmersionAnalyticsService
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class StatsTitleMaintenanceScreenModel(
    private val titleId: TitleId,
    private val maintenance: ImmersionMaintenanceRepository = Injekt.get(),
    private val analytics: ImmersionAnalyticsService = Injekt.get(),
) : ScreenModel {
    private val mutableState = MutableStateFlow(StatsTitleMaintenanceState())
    val state: StateFlow<StatsTitleMaintenanceState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        launchTask {
            refreshSnapshot()
        }
    }

    fun previewRename(displayTitle: String) {
        val normalized = displayTitle.trim()
        if (normalized.isEmpty()) {
            mutableState.update { it.copy(error = StatsTitleMaintenanceError.INVALID_INPUT) }
            return
        }
        preview(ImmersionTitleMutationRequest.Rename(titleId, normalized))
    }

    fun previewMerge(targetTitleId: TitleId?) {
        if (targetTitleId == null) {
            mutableState.update { it.copy(error = StatsTitleMaintenanceError.INVALID_INPUT) }
            return
        }
        preview(ImmersionTitleMutationRequest.Merge(titleId, targetTitleId))
    }

    fun previewSplit(
        displayTitle: String,
        startDate: String,
        endDate: String,
    ) {
        val request = createTitleSplitRequest(
            sourceTitleId = titleId,
            displayTitle = displayTitle,
            startDate = startDate,
            endDate = endDate,
        )
        if (request == null) {
            mutableState.update { it.copy(error = StatsTitleMaintenanceError.INVALID_INPUT) }
            return
        }
        preview(request)
    }

    fun clearPreview() {
        mutableState.update { it.copy(preview = null, error = null) }
    }

    fun applyPreview() {
        val preview = mutableState.value.preview ?: return
        if (!preview.canApply) return
        launchTask {
            val operation = maintenance.applyTitleMutation(
                expectedPreview = preview,
                appliedAtEpochMillis = System.currentTimeMillis(),
            )
            repairAllDirtyRollups()
            mutableState.update {
                it.copy(
                    preview = null,
                    lastOperation = operation,
                )
            }
            refreshSnapshot()
        }
    }

    fun rollback(operationId: String) {
        launchTask {
            val operation = maintenance.rollbackTitleMutation(
                operationId = operationId,
                rolledBackAtEpochMillis = System.currentTimeMillis(),
            )
            repairAllDirtyRollups()
            mutableState.update { it.copy(lastOperation = operation) }
            refreshSnapshot()
        }
    }

    private fun preview(request: ImmersionTitleMutationRequest) {
        launchTask {
            val preview = maintenance.previewTitleMutation(request)
            mutableState.update { it.copy(preview = preview) }
        }
    }

    private suspend fun refreshSnapshot() {
        val titles = analytics.titles(
            filter = StatsFilter(),
            sort = AnalyticsSort.ALPHABETICAL,
        ).value
        val mutations = maintenance.titleMutations(titleId)
        mutableState.update { state ->
            state.copy(
                title = titles.find { it.titleId == titleId } ?: state.title,
                targets = titles.filterNot { it.titleId == titleId },
                mutations = mutations,
            )
        }
    }

    private suspend fun repairAllDirtyRollups() {
        while (analytics.repairDirtyRollups(ROLLUP_REPAIR_BATCH_SIZE).isNotEmpty()) {
            // Keep bounded repair batches until the title mutation is fully visible.
        }
    }

    private fun launchTask(block: suspend () -> Unit) {
        screenModelScope.launch {
            mutableState.update { it.copy(busy = true, error = null) }
            runCatching { block() }
                .onFailure {
                    mutableState.update {
                        it.copy(error = StatsTitleMaintenanceError.OPERATION_FAILED)
                    }
                }
            mutableState.update { it.copy(busy = false) }
        }
    }
}

data class StatsTitleMaintenanceState(
    val title: AnalyticsTitleRow? = null,
    val targets: List<AnalyticsTitleRow> = emptyList(),
    val preview: ImmersionTitleMutationPreview? = null,
    val mutations: List<ImmersionTitleMutation> = emptyList(),
    val lastOperation: ImmersionTitleMutation? = null,
    val busy: Boolean = false,
    val error: StatsTitleMaintenanceError? = null,
)

enum class StatsTitleMaintenanceError {
    INVALID_INPUT,
    OPERATION_FAILED,
}

internal fun createTitleSplitRequest(
    sourceTitleId: TitleId,
    displayTitle: String,
    startDate: String,
    endDate: String,
): ImmersionTitleMutationRequest.Split? {
    val title = displayTitle.trim()
    if (title.isEmpty()) return null
    val start = runCatching { ImmersionLocalDate.parse(startDate.trim()) }.getOrNull() ?: return null
    val end = runCatching { ImmersionLocalDate.parse(endDate.trim()) }.getOrNull() ?: return null
    if (start > end) return null
    return ImmersionTitleMutationRequest.Split.create(
        sourceTitleId = sourceTitleId,
        displayTitle = title,
        dateRange = LocalDateRange(start, end),
    )
}

private const val ROLLUP_REPAIR_BATCH_SIZE = 366
