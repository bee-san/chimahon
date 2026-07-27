package eu.kanade.presentation.more.stats

import android.content.Intent
import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.ui.dictionary.ProcessTextLookupActivity
import eu.kanade.tachiyomi.ui.stats.ACTIVE_TIME_GOAL_METRIC
import eu.kanade.tachiyomi.ui.stats.SOURCE_UNITS_GOAL_METRIC
import eu.kanade.tachiyomi.ui.stats.StatsComparisonDirection
import eu.kanade.tachiyomi.ui.stats.StatsGoalDisplayKind
import eu.kanade.tachiyomi.ui.stats.StatsGoalEditMode
import eu.kanade.tachiyomi.ui.stats.StatsGoalEditorValues
import eu.kanade.tachiyomi.ui.stats.StatsGoalForecastPresentation
import eu.kanade.tachiyomi.ui.stats.StatsGoalKind
import eu.kanade.tachiyomi.ui.stats.StatsSourceNavigator
import eu.kanade.tachiyomi.ui.stats.StatsTitleLinkState
import eu.kanade.tachiyomi.ui.stats.StatsTitlePresentationMetadata
import eu.kanade.tachiyomi.ui.stats.activeTimeComparison
import eu.kanade.tachiyomi.ui.stats.ankiPresentationCapabilityState
import eu.kanade.tachiyomi.ui.stats.characterCoverageTarget
import eu.kanade.tachiyomi.ui.stats.characterDisplayText
import eu.kanade.tachiyomi.ui.stats.characterFrequencyLevel
import eu.kanade.tachiyomi.ui.stats.durationMillis
import eu.kanade.tachiyomi.ui.stats.enabledStatsTabs
import eu.kanade.tachiyomi.ui.stats.overviewIndexedGrowthMetricValue
import eu.kanade.tachiyomi.ui.stats.sessionRelinkTargets
import eu.kanade.tachiyomi.ui.stats.statsDurationParts
import eu.kanade.tachiyomi.ui.stats.statsGoalDisplayValue
import eu.kanade.tachiyomi.ui.stats.statsGoalForecastPresentation
import eu.kanade.tachiyomi.ui.stats.statsOccurrenceKey
import eu.kanade.tachiyomi.ui.stats.suggestedStatsGoalWeekdayMultipliers
import eu.kanade.tachiyomi.ui.stats.titleMutationBlockerLabel
import eu.kanade.tachiyomi.ui.stats.toStatsGoalEditorValues
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.domain.immersion.model.AnalyticsActivityTotals
import tachiyomi.domain.immersion.model.AnalyticsAnkiCapabilityReason
import tachiyomi.domain.immersion.model.AnalyticsAnkiReport
import tachiyomi.domain.immersion.model.AnalyticsBucketScale
import tachiyomi.domain.immersion.model.AnalyticsCharacterFilter
import tachiyomi.domain.immersion.model.AnalyticsCharacterPriorityFormula
import tachiyomi.domain.immersion.model.AnalyticsCharacterPriorityMode
import tachiyomi.domain.immersion.model.AnalyticsCharacterRange
import tachiyomi.domain.immersion.model.AnalyticsCharacterRow
import tachiyomi.domain.immersion.model.AnalyticsCharacterScript
import tachiyomi.domain.immersion.model.AnalyticsCharacterSummary
import tachiyomi.domain.immersion.model.AnalyticsDataQuality
import tachiyomi.domain.immersion.model.AnalyticsEstimateConfidence
import tachiyomi.domain.immersion.model.AnalyticsEstimateUnit
import tachiyomi.domain.immersion.model.AnalyticsGoalProgress
import tachiyomi.domain.immersion.model.AnalyticsOverview
import tachiyomi.domain.immersion.model.AnalyticsPage
import tachiyomi.domain.immersion.model.AnalyticsResult
import tachiyomi.domain.immersion.model.AnalyticsSessionDetail
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.AnalyticsSourceOccurrence
import tachiyomi.domain.immersion.model.AnalyticsTemporalActivity
import tachiyomi.domain.immersion.model.AnalyticsTitleAcquisitionBucketSize
import tachiyomi.domain.immersion.model.AnalyticsTitleCompletedUnit
import tachiyomi.domain.immersion.model.AnalyticsTitleCoverageFilter
import tachiyomi.domain.immersion.model.AnalyticsTitleEstimate
import tachiyomi.domain.immersion.model.AnalyticsTitleFilter
import tachiyomi.domain.immersion.model.AnalyticsTitleRow
import tachiyomi.domain.immersion.model.AnalyticsTitleSeriesSelection
import tachiyomi.domain.immersion.model.AnalyticsTitleSort
import tachiyomi.domain.immersion.model.AnalyticsTitleStateFilter
import tachiyomi.domain.immersion.model.AnalyticsTitleTrendSeries
import tachiyomi.domain.immersion.model.AnalyticsTitleTrends
import tachiyomi.domain.immersion.model.AnalyticsTitleWordAcquisition
import tachiyomi.domain.immersion.model.AnalyticsTrendPoint
import tachiyomi.domain.immersion.model.AnalyticsTrends
import tachiyomi.domain.immersion.model.AnalyticsVocabularyFirstSeen
import tachiyomi.domain.immersion.model.AnalyticsWordRow
import tachiyomi.domain.immersion.model.AnkiMatchConfidence
import tachiyomi.domain.immersion.model.CapabilityState
import tachiyomi.domain.immersion.model.CharacterMetric
import tachiyomi.domain.immersion.model.EventType
import tachiyomi.domain.immersion.model.ImmersionAnkiItem
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.ImmersionTitleMutationPreview
import tachiyomi.domain.immersion.model.MaturityTier
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.ProvenanceState
import tachiyomi.domain.immersion.model.ReadingMetrics
import tachiyomi.domain.immersion.model.SessionPage
import tachiyomi.domain.immersion.model.SessionStatus
import tachiyomi.domain.immersion.model.SourceKind
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.model.VocabularyCategory
import tachiyomi.domain.immersion.model.VocabularyExclusion
import tachiyomi.domain.immersion.model.VocabularyFilter
import tachiyomi.domain.immersion.model.VocabularyKnownness
import tachiyomi.domain.immersion.model.VocabularyScript
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun StatsScreenContent(
    state: StatsScreenState.Success,
    paddingValues: PaddingValues,
    onTabSelect: (StatsTab) -> Unit,
    onSectionRetry: (StatsSection) -> Unit,
    onRangeSelect: (StatsRangePreset) -> Unit,
    onPeriodMove: (Int) -> Unit,
    onCustomRange: (String, String) -> Boolean,
    onMediaSelect: (MediaKind?) -> Unit,
    onTitleFilterSelect: (String?) -> Unit,
    titleFilterLocked: Boolean,
    onProfileSelect: (String?) -> Unit,
    onCharacterMetricSelect: (CharacterMetric) -> Unit,
    onIncludeLegacyChange: (Boolean) -> Unit,
    onIncludeRereadsChange: (Boolean) -> Unit,
    onMaturityTiersSelect: (Set<MaturityTier>) -> Unit,
    onProvenanceStatesSelect: (Set<ProvenanceState>) -> Unit,
    onTrendScaleSelect: (AnalyticsBucketScale) -> Unit,
    onTrendMetricSelect: (StatsTrendMetric) -> Unit,
    onTitleTrendSelectionSelect: (AnalyticsTitleSeriesSelection) -> Unit,
    onTitleSortSelect: (AnalyticsTitleSort) -> Unit,
    onTitleFilterChange: (AnalyticsTitleFilter) -> Unit,
    onVocabularySortSelect: (AnalyticsSort) -> Unit,
    onVocabularyFilterChange: (VocabularyFilter) -> Unit,
    onVocabularyWordSelectionChange: (String, Boolean) -> Unit,
    onVocabularySelectionClear: () -> Unit,
    onVocabularyExclusionChange: (Boolean) -> Unit,
    onVocabularyExport: () -> Unit,
    onCharacterSortSelect: (AnalyticsSort) -> Unit,
    onCharacterFilterChange: (AnalyticsCharacterFilter) -> Unit,
    onCharacterGridModeSelect: (StatsCharacterGridMode) -> Unit,
    onCharacterLayoutSelect: (StatsCharacterLayout) -> Unit,
    onCharacterCoverageTargetChange: (Int) -> Unit,
    onCharacterSelectionChange: (Int, Boolean) -> Unit,
    onCharacterSelectionClear: () -> Unit,
    onCharacterExport: () -> Unit,
    onTitleSearch: (String) -> Unit,
    onVocabularySearch: (String) -> Unit,
    onCharacterSearch: (String) -> Unit,
    onSourceSearch: (String) -> Unit,
    onTitleSelect: (AnalyticsTitleRow?) -> Unit,
    onTitleOpen: (AnalyticsTitleRow) -> Unit,
    onTitleManage: (AnalyticsTitleRow) -> Unit,
    onTitleUnlink: () -> Unit,
    onTitleDeleteStats: (AnalyticsTitleRow) -> Unit,
    onTitleDeleteRawText: (AnalyticsTitleRow) -> Unit,
    onTitleCaptureExclusionChange: (Boolean) -> Unit,
    onTitleAcquisitionBucketSizeSelect: (AnalyticsTitleAcquisitionBucketSize) -> Unit,
    onWordSelect: (AnalyticsWordRow?) -> Unit,
    onCharacterSelect: (AnalyticsCharacterRow?) -> Unit,
    onSessionSelect: (ImmersionSession?) -> Unit,
    onSessionDelete: (ImmersionSession) -> Unit,
    onSessionRelinkPreview: (TitleId) -> Unit,
    onSessionRelinkPreviewClear: () -> Unit,
    onSessionRelinkApply: () -> Unit,
    onLoadMoreVocabulary: () -> Unit,
    onLoadMoreTitles: () -> Unit,
    onLoadMoreTitleSessions: () -> Unit,
    onLoadMoreTitleCompletedUnits: () -> Unit,
    onLoadMoreTitleSources: () -> Unit,
    onLoadMoreWordOccurrences: () -> Unit,
    onLoadMoreCharacters: () -> Unit,
    onLoadMoreCharacterOccurrences: () -> Unit,
    onLoadMoreCharacterContainingWords: () -> Unit,
    onLoadMoreSourceSearch: () -> Unit,
    onLoadMoreSessions: () -> Unit,
    onSaveGoal: (StatsGoalEditorValues, ImmersionGoal?) -> Boolean,
    onArchiveGoal: (ImmersionGoal) -> Unit,
    onCheckInGoal: (String, String?) -> Unit,
    onAnkiRefresh: () -> Unit,
    onAnkiWordCoverageTargetChange: (Int) -> Unit,
    onOpenMissingAnkiWords: () -> Unit,
    onOpenMissingAnkiCharacters: () -> Unit,
) {
    var filtersExpanded by remember { mutableStateOf(false) }
    var showDefinitions by remember { mutableStateOf(false) }
    var showCustomRange by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
    ) {
        FilterSummary(
            state = state,
            expanded = filtersExpanded,
            onExpandedChange = { filtersExpanded = !filtersExpanded },
            onRangeSelect = {
                if (it == StatsRangePreset.CUSTOM) showCustomRange = true else onRangeSelect(it)
            },
            onPeriodMove = onPeriodMove,
            onMediaSelect = onMediaSelect,
            onTitleFilterSelect = onTitleFilterSelect,
            titleFilterLocked = titleFilterLocked,
            onProfileSelect = onProfileSelect,
            onCharacterMetricSelect = onCharacterMetricSelect,
            onIncludeLegacyChange = onIncludeLegacyChange,
            onIncludeRereadsChange = onIncludeRereadsChange,
            onMaturityTiersSelect = onMaturityTiersSelect,
            onProvenanceStatesSelect = onProvenanceStatesSelect,
            onDefinitions = { showDefinitions = true },
        )
        if (state.isRefreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        StatsTabs(
            selected = state.selectedTab,
            goalsEnabled = state.goalsEnabled,
            ankiEnabled = state.ankiEnabled,
            onSelect = onTabSelect,
        )
        when (state.selectedTab) {
            StatsTab.OVERVIEW -> OverviewTab(
                state = state,
                onTabSelect = onTabSelect,
                onSessionSelect = onSessionSelect,
                onSectionRetry = onSectionRetry,
            )
            StatsTab.ACTIVITY -> ActivityTab(
                state,
                onTrendScaleSelect,
                onTrendMetricSelect,
                onTitleTrendSelectionSelect,
                onSectionRetry,
            )
            StatsTab.TITLES -> TitlesTab(
                state,
                onTitleSortSelect,
                onTitleSearch,
                onTitleFilterChange,
                onTitleSelect,
                onTitleOpen,
                onTitleManage,
                onTitleUnlink,
                onTitleDeleteStats,
                onTitleDeleteRawText,
                onTitleCaptureExclusionChange,
                onTitleAcquisitionBucketSizeSelect,
                onSessionOpen = { session ->
                    onTabSelect(StatsTab.SESSIONS)
                    onSessionSelect(session)
                },
                onLoadMoreTitles,
                onLoadMoreTitleSessions,
                onLoadMoreTitleCompletedUnits,
                onLoadMoreTitleSources,
                onSectionRetry,
            )
            StatsTab.VOCABULARY -> VocabularyTab(
                state,
                onVocabularySortSelect,
                onVocabularySearch,
                onWordSelect,
                onLoadMoreVocabulary,
                onLoadMoreWordOccurrences,
                onVocabularyFilterChange,
                onVocabularyWordSelectionChange,
                onVocabularySelectionClear,
                onVocabularyExclusionChange,
                onVocabularyExport,
                onSectionRetry,
            )
            StatsTab.CHARACTERS -> CharactersTab(
                state,
                onCharacterSortSelect,
                onCharacterFilterChange,
                onCharacterGridModeSelect,
                onCharacterLayoutSelect,
                onCharacterCoverageTargetChange,
                onCharacterSearch,
                onCharacterSelect,
                onCharacterSelectionChange,
                onCharacterSelectionClear,
                onCharacterExport,
                onLoadMoreCharacters,
                onLoadMoreCharacterOccurrences,
                onLoadMoreCharacterContainingWords,
                onContainingWordSelect = { word ->
                    onTabSelect(StatsTab.VOCABULARY)
                    onWordSelect(word)
                },
                onSectionRetry = onSectionRetry,
            )
            StatsTab.SESSIONS -> SessionsTab(
                state,
                onSessionSelect,
                onSessionDelete,
                onSessionRelinkPreview,
                onSessionRelinkPreviewClear,
                onSessionRelinkApply,
                onSourceSearch,
                onLoadMoreSourceSearch,
                onLoadMoreSessions,
                onSectionRetry,
            )
            StatsTab.GOALS -> GoalsTab(
                state,
                onSaveGoal,
                onArchiveGoal,
                onCheckInGoal,
                onSectionRetry,
            )
            StatsTab.ANKI -> AnkiTab(
                state = state,
                onRefresh = onAnkiRefresh,
                onSectionRetry = onSectionRetry,
                onWordCoverageTargetChange = onAnkiWordCoverageTargetChange,
                onOpenMissingWords = onOpenMissingAnkiWords,
                onOpenMissingCharacters = onOpenMissingAnkiCharacters,
            )
        }
    }

    if (showDefinitions) {
        AlertDialog(
            onDismissRequest = { showDefinitions = false },
            icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            title = { Text(stringResource(KMR.strings.stats_metric_definitions)) },
            text = { Text(stringResource(KMR.strings.stats_metric_definitions_body)) },
            confirmButton = {
                TextButton(onClick = { showDefinitions = false }) {
                    Text(stringResource(KMR.strings.stats_close))
                }
            },
        )
    }
    if (showCustomRange) {
        CustomRangeDialog(
            initialStart = state.filter.customStart?.toString().orEmpty(),
            initialEnd = state.filter.customEnd?.toString().orEmpty(),
            onDismiss = { showCustomRange = false },
            onApply = { start, end ->
                if (onCustomRange(start, end)) {
                    showCustomRange = false
                    true
                } else {
                    false
                }
            },
        )
    }
}

@Composable
private fun FilterSummary(
    state: StatsScreenState.Success,
    expanded: Boolean,
    onExpandedChange: () -> Unit,
    onRangeSelect: (StatsRangePreset) -> Unit,
    onPeriodMove: (Int) -> Unit,
    onMediaSelect: (MediaKind?) -> Unit,
    onTitleFilterSelect: (String?) -> Unit,
    titleFilterLocked: Boolean,
    onProfileSelect: (String?) -> Unit,
    onCharacterMetricSelect: (CharacterMetric) -> Unit,
    onIncludeLegacyChange: (Boolean) -> Unit,
    onIncludeRereadsChange: (Boolean) -> Unit,
    onMaturityTiersSelect: (Set<MaturityTier>) -> Unit,
    onProvenanceStatesSelect: (Set<ProvenanceState>) -> Unit,
    onDefinitions: () -> Unit,
) {
    val range = rangeLabel(state.filter.rangePreset)
    val media = mediaLabel(state.filter.mediaKind)
    val basis = characterMetricLabel(state.filter.characterMetric)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpandedChange)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(KMR.strings.stats_filters),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(KMR.strings.stats_filter_summary, range, media, basis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Outlined.ArrowDropDown,
                contentDescription = null,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterMenuChip(
                    label = range,
                    options = StatsRangePreset.entries,
                    optionLabel = { rangeLabel(it) },
                    onSelect = onRangeSelect,
                )
                if (state.filter.rangePreset !in setOf(StatsRangePreset.ALL, StatsRangePreset.CUSTOM)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { onPeriodMove(state.filter.periodOffset - 1) }) {
                            Icon(
                                Icons.Outlined.ChevronLeft,
                                contentDescription = stringResource(KMR.strings.stats_previous_period),
                            )
                        }
                        Text(
                            text = if (state.filter.periodOffset == 0) {
                                range
                            } else {
                                stringResource(KMR.strings.stats_previous_period)
                            },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        IconButton(
                            onClick = { onPeriodMove(state.filter.periodOffset + 1) },
                            enabled = state.filter.periodOffset < 0,
                        ) {
                            Icon(
                                Icons.Outlined.ChevronRight,
                                contentDescription = stringResource(KMR.strings.stats_next_period),
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterMenuChip(
                        label = media,
                        options = listOf(null) + MediaKind.entries,
                        optionLabel = { mediaLabel(it) },
                        onSelect = onMediaSelect,
                    )
                    if (!titleFilterLocked) {
                        FilterMenuChip(
                            label = state.titleOptions
                                .find { it.titleId.value == state.filter.titleId }
                                ?.displayTitle
                                ?: stringResource(KMR.strings.stats_titles_all),
                            options = listOf(null) + state.titleOptions.map { it.titleId.value },
                            optionLabel = { titleId ->
                                state.titleOptions
                                    .find { it.titleId.value == titleId }
                                    ?.displayTitle
                                    ?: stringResource(KMR.strings.stats_titles_all)
                            },
                            onSelect = onTitleFilterSelect,
                        )
                    }
                    if (!titleFilterLocked || state.filter.titleId == null) {
                        FilterMenuChip(
                            label = state.profiles.find { it.id == state.filter.profileId }?.name
                                ?: stringResource(KMR.strings.stats_profiles_all),
                            options = listOf(null) + state.profiles.map { it.id },
                            optionLabel = { profileId ->
                                state.profiles.find { it.id == profileId }?.name
                                    ?: stringResource(KMR.strings.stats_profiles_all)
                            },
                            onSelect = onProfileSelect,
                        )
                    }
                    FilterMenuChip(
                        label = basis,
                        options = CharacterMetric.entries,
                        optionLabel = { characterMetricLabel(it) },
                        onSelect = onCharacterMetricSelect,
                    )
                    MultiSelectFilterMenuChip(
                        label = stringResource(
                            KMR.strings.stats_filter_maturity,
                            filterSelectionLabel(
                                selected = state.filter.maturityTiers,
                                optionLabel = { maturityLabel(it) },
                            ),
                        ),
                        selected = state.filter.maturityTiers,
                        options = statsMaturityFilterOptions,
                        optionLabel = { maturityLabel(it) },
                        onSelectionChange = onMaturityTiersSelect,
                    )
                    MultiSelectFilterMenuChip(
                        label = stringResource(
                            KMR.strings.stats_filter_provenance,
                            filterSelectionLabel(
                                selected = state.filter.provenanceStates,
                                optionLabel = { provenanceLabel(it) },
                            ),
                        ),
                        selected = state.filter.provenanceStates,
                        options = statsProvenanceFilterOptions,
                        optionLabel = { provenanceLabel(it) },
                        onSelectionChange = onProvenanceStatesSelect,
                    )
                }
                ToggleRow(
                    text = stringResource(KMR.strings.stats_include_legacy),
                    checked = state.filter.includeLegacy,
                    onCheckedChange = onIncludeLegacyChange,
                )
                ToggleRow(
                    text = stringResource(KMR.strings.stats_include_rereads),
                    checked = state.filter.includeRereadsAndReplays,
                    onCheckedChange = onIncludeRereadsChange,
                )
                TextButton(onClick = onDefinitions) {
                    Icon(Icons.Outlined.Info, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(KMR.strings.stats_metric_definitions))
                }
            }
        }
    }
}

@Composable
private fun StatsTabs(
    selected: StatsTab,
    goalsEnabled: Boolean,
    ankiEnabled: Boolean,
    onSelect: (StatsTab) -> Unit,
) {
    val tabs = enabledStatsTabs(goalsEnabled, ankiEnabled)
    PrimaryScrollableTabRow(
        selectedTabIndex = tabs.indexOf(selected).coerceAtLeast(0),
        edgePadding = 8.dp,
        divider = {},
    ) {
        tabs.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                text = { Text(tabLabel(tab)) },
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun OverviewTab(
    state: StatsScreenState.Success,
    onTabSelect: (StatsTab) -> Unit,
    onSessionSelect: (ImmersionSession?) -> Unit,
    onSectionRetry: (StatsSection) -> Unit,
) {
    val section = state.sections.overview
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionFrame(
                section = section,
                onRetry = { onSectionRetry(StatsSection.OVERVIEW) },
            ) { result ->
                OverviewSummary(
                    result,
                    state.filter.characterMetric,
                    state.ankiEnabled,
                    onTabSelect,
                )
            }
        }
        item {
            SectionFrame(
                section = state.sections.heatmap,
                onRetry = { onSectionRetry(StatsSection.HEATMAP) },
            ) { result ->
                ActivityHeatmap(
                    trends = result.value,
                    metric = state.filter.characterMetric,
                )
            }
        }
        item {
            SectionFrame(
                section = state.sections.sessions,
                onRetry = { onSectionRetry(StatsSection.SESSIONS) },
            ) { result ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(KMR.strings.stats_recent_sessions))
                    result.value.items.take(3).forEach { session ->
                        SessionRow(session) {
                            onTabSelect(StatsTab.SESSIONS)
                            onSessionSelect(session)
                        }
                    }
                    if (result.value.items.size > 3) {
                        TextButton(onClick = { onTabSelect(StatsTab.SESSIONS) }) {
                            Text(stringResource(KMR.strings.stats_view_all))
                        }
                    }
                }
            }
        }
        if (state.goalsEnabled) {
            item {
                SectionFrame(
                    section = state.sections.goals,
                    onRetry = { onSectionRetry(StatsSection.GOALS) },
                ) { result ->
                    CompactTodayGoals(
                        goals = result.value,
                        onOpenGoals = { onTabSelect(StatsTab.GOALS) },
                    )
                }
            }
        }
        item {
            val quality = section.value?.quality
            if (quality != null) DataQualityCard(quality)
        }
    }
}

@Composable
private fun CompactTodayGoals(
    goals: List<AnalyticsGoalProgress>,
    onOpenGoals: () -> Unit,
) {
    val today = remember { ImmersionLocalDate.from(LocalDate.now()) }
    val activeToday = goals.filter { progress ->
        val goal = progress.goal
        val startDate = goal.startDate
        val endDate = goal.endDate
        (startDate == null || startDate <= today) &&
            (endDate == null || endDate >= today)
    }
    if (activeToday.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle(stringResource(KMR.strings.stats_goal_today))
            TextButton(onClick = onOpenGoals) {
                Text(stringResource(KMR.strings.stats_view_all))
            }
        }
        activeToday.take(MAX_COMPACT_GOALS).forEach { goal ->
            CompactTodayGoal(goal, onOpenGoals)
        }
    }
}

@Composable
private fun CompactTodayGoal(
    goal: AnalyticsGoalProgress,
    onClick: () -> Unit,
) {
    val fraction = if (goal.todayTarget > 0.0) {
        (goal.todayAchieved / goal.todayTarget).coerceIn(0.0, 1.0)
    } else {
        0.0
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(goalTypeLabel(goal.goal.type), style = MaterialTheme.typography.labelLarge)
                Text(
                    NumberFormat.getPercentInstance().format(fraction),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            LinearProgressIndicator(
                progress = { fraction.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(
                    KMR.strings.stats_goal_progress,
                    formatGoalValue(goal.goal.metric, goal.todayAchieved),
                    formatGoalValue(goal.goal.metric, goal.todayTarget),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ActivityHeatmap(
    trends: AnalyticsTrends,
    metric: CharacterMetric,
) {
    val points = trends.points
    val values = points.map { it.metrics.characterValue(metric) }
    val maximum = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val activeDays = values.count { it > 0L }
    val summary = stringResource(
        KMR.strings.stats_heatmap_summary,
        pluralStringResource(
            KMR.plurals.stats_active_day_count,
            activeDays,
            formatCount(activeDays.toLong()),
        ),
        pluralStringResource(
            KMR.plurals.stats_day_count,
            points.size,
            formatCount(points.size.toLong()),
        ),
        (values.maxOrNull() ?: 0L).let { busiest ->
            pluralStringResource(
                KMR.plurals.stats_character_count,
                busiest.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                formatCount(busiest),
            )
        },
    )
    val leadingEmptyDays = points.firstOrNull()
        ?.range
        ?.start
        ?.toLocalDate()
        ?.dayOfWeek
        ?.value
        ?.minus(1)
        ?: 0
    val cells = List<AnalyticsTrendPoint?>(leadingEmptyDays) { null } + points

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(KMR.strings.stats_activity_heatmap))
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            cells.chunked(7).forEach { week ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    week.forEach { point ->
                        if (point == null) {
                            Spacer(Modifier.size(24.dp))
                        } else {
                            val value = point.metrics.characterValue(metric)
                            val fraction = value.toFloat() / maximum.toFloat()
                            val level = if (value == 0L) {
                                0
                            } else {
                                (fraction * 4).roundToInt().coerceIn(1, 4)
                            }
                            val description = stringResource(
                                KMR.strings.stats_heatmap_day,
                                formatLocalDate(point.range.start),
                                pluralStringResource(
                                    KMR.plurals.stats_character_count,
                                    value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                    formatCount(value),
                                ),
                            )
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(
                                        color = lerp(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            MaterialTheme.colorScheme.primaryContainer,
                                            fraction,
                                        ),
                                        shape = RoundedCornerShape(3.dp),
                                    )
                                    .border(
                                        width = if (level >= 3) 2.dp else 1.dp,
                                        color = MaterialTheme.colorScheme.outline,
                                        shape = RoundedCornerShape(3.dp),
                                    )
                                    .semantics { contentDescription = description },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = level.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (value == 0L) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewSummary(
    result: AnalyticsResult<AnalyticsOverview>,
    metric: CharacterMetric,
    ankiEnabled: Boolean,
    onTabSelect: (StatsTab) -> Unit,
) {
    val overview = result.value
    val metrics = overview.comparison.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (overview.period.isPartialCurrentDay) {
            NoticeCard(stringResource(KMR.strings.stats_partial_day))
        }
        ComparisonCard(overview)
        val cards = listOf(
            DashboardMetric(
                formatDuration(metrics.activeTime.value),
                stringResource(KMR.strings.stats_active_time),
                Icons.Outlined.Schedule,
                StatsTab.ACTIVITY,
            ),
            DashboardMetric(
                formatCount(metrics.characterValue(metric)),
                characterMetricLabel(metric),
                Icons.Outlined.TextFields,
                StatsTab.CHARACTERS,
            ),
            DashboardMetric(
                metrics.readingSpeedPerHour(metric)?.let(::formatRate)
                    ?: stringResource(KMR.strings.stats_unavailable),
                stringResource(KMR.strings.stats_reading_speed),
                Icons.Outlined.Speed,
                StatsTab.ACTIVITY,
            ),
            DashboardMetric(
                formatCount(metrics.sessions.value),
                stringResource(KMR.strings.stats_sessions),
                Icons.Outlined.BarChart,
                StatsTab.SESSIONS,
            ),
            DashboardMetric(
                formatCount(metrics.successfulLookups.value),
                stringResource(KMR.strings.stats_lookups),
                Icons.Outlined.Search,
                StatsTab.VOCABULARY,
            ),
            DashboardMetric(
                formatCount(metrics.cardsCreated.value),
                stringResource(KMR.strings.stats_cards_created),
                Icons.Outlined.Style,
                StatsTab.ANKI,
            ),
            DashboardMetric(
                overviewIndexedGrowthMetricValue(metrics.newWords.value, result.quality)
                    ?.let(::formatCount)
                    ?: stringResource(KMR.strings.stats_unavailable),
                stringResource(KMR.strings.stats_new_words),
                Icons.Outlined.Translate,
                StatsTab.VOCABULARY,
            ),
            DashboardMetric(
                overviewIndexedGrowthMetricValue(metrics.newCharacters.value, result.quality)
                    ?.let(::formatCount)
                    ?: stringResource(KMR.strings.stats_unavailable),
                stringResource(KMR.strings.stats_new_characters),
                Icons.Outlined.TextFields,
                StatsTab.CHARACTERS,
            ),
        )
        cards.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { card ->
                    MetricCard(
                        data = card,
                        modifier = Modifier.weight(1f),
                        onClick = if (card.destination != StatsTab.ANKI || ankiEnabled) {
                            { onTabSelect(card.destination) }
                        } else {
                            null
                        },
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                DashboardMetric(
                    overview.streak.currentDays.toString(),
                    stringResource(KMR.strings.stats_current_streak),
                    Icons.Outlined.LocalFireDepartment,
                    StatsTab.ACTIVITY,
                ),
                Modifier.weight(1f),
            ) { onTabSelect(StatsTab.ACTIVITY) }
            MetricCard(
                DashboardMetric(
                    overview.streak.longestDays.toString(),
                    stringResource(KMR.strings.stats_longest_streak),
                    Icons.Outlined.CalendarMonth,
                    StatsTab.ACTIVITY,
                ),
                Modifier.weight(1f),
            ) { onTabSelect(StatsTab.ACTIVITY) }
        }
    }
}

@Composable
private fun ComparisonCard(overview: AnalyticsOverview) {
    val comparison = overview.comparison
    val summary = activeTimeComparison(
        currentMillis = comparison.current.activeTime.value,
        previousMillis = comparison.previous?.activeTime?.value,
        changeRatio = comparison.activeTimeChangeRatio,
    )
    val icon: ImageVector
    val text: String
    when (summary.direction) {
        StatsComparisonDirection.NO_PREVIOUS -> {
            icon = Icons.Outlined.Info
            text = stringResource(KMR.strings.stats_comparison_no_previous)
        }
        StatsComparisonDirection.UP -> {
            icon = Icons.Outlined.KeyboardArrowUp
            val absoluteText = formatDuration(checkNotNull(summary.absoluteDeltaMillis))
            val previousText = formatDuration(checkNotNull(summary.previousMillis))
            text = if (summary.percentageChange == null) {
                stringResource(
                    KMR.strings.stats_change_up_absolute_no_percentage,
                    absoluteText,
                    previousText,
                )
            } else {
                stringResource(
                    KMR.strings.stats_change_up_absolute,
                    absoluteText,
                    formatPercent(summary.percentageChange),
                    previousText,
                )
            }
        }
        StatsComparisonDirection.DOWN -> {
            icon = Icons.Outlined.KeyboardArrowDown
            val absoluteText = formatDuration(checkNotNull(summary.absoluteDeltaMillis))
            val previousText = formatDuration(checkNotNull(summary.previousMillis))
            text = if (summary.percentageChange == null) {
                stringResource(
                    KMR.strings.stats_change_down_absolute_no_percentage,
                    absoluteText,
                    previousText,
                )
            } else {
                stringResource(
                    KMR.strings.stats_change_down_absolute,
                    absoluteText,
                    formatPercent(summary.percentageChange),
                    previousText,
                )
            }
        }
        StatsComparisonDirection.SAME -> {
            icon = Icons.Outlined.Remove
            text = stringResource(
                KMR.strings.stats_change_same_absolute,
                formatDuration(checkNotNull(summary.absoluteDeltaMillis)),
                formatDuration(checkNotNull(summary.previousMillis)),
            )
        }
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Column {
                Text(
                    text = stringResource(KMR.strings.stats_comparison_previous_period),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ActivityTab(
    state: StatsScreenState.Success,
    onTrendScaleSelect: (AnalyticsBucketScale) -> Unit,
    onTrendMetricSelect: (StatsTrendMetric) -> Unit,
    onTitleTrendSelectionSelect: (AnalyticsTitleSeriesSelection) -> Unit,
    onSectionRetry: (StatsSection) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnalyticsBucketScale.entries.forEach { scale ->
                    FilterChip(
                        selected = state.trendScale == scale,
                        onClick = { onTrendScaleSelect(scale) },
                        label = { Text(bucketLabel(scale)) },
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatsTrendMetric.entries.forEach { metric ->
                    FilterChip(
                        selected = state.trendMetric == metric,
                        onClick = { onTrendMetricSelect(metric) },
                        label = { Text(trendMetricLabel(metric, state.filter.characterMetric)) },
                    )
                }
            }
        }
        item {
            SectionFrame(
                section = state.sections.trends,
                onRetry = { onSectionRetry(StatsSection.TRENDS) },
            ) { result ->
                TrendsContent(
                    trends = result.value,
                    metric = state.filter.characterMetric,
                    trendMetric = state.trendMetric,
                )
            }
        }
        item {
            SectionFrame(
                section = state.sections.temporalActivity,
                onRetry = { onSectionRetry(StatsSection.TEMPORAL_ACTIVITY) },
            ) { result ->
                TemporalPatternsContent(
                    activity = result.value,
                    metric = state.filter.characterMetric,
                )
            }
        }
        item {
            SectionFrame(
                section = state.sections.titleTrends,
                onRetry = { onSectionRetry(StatsSection.TITLE_TRENDS) },
            ) { result ->
                TitleContributionsContent(
                    trends = result.value,
                    metric = state.filter.characterMetric,
                    trendMetric = state.trendMetric,
                    selection = state.titleTrendSelection,
                    onSelectionSelect = onTitleTrendSelectionSelect,
                )
            }
        }
        item {
            Text(
                text = stringResource(KMR.strings.stats_minimum_threshold),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrendsContent(
    trends: AnalyticsTrends,
    metric: CharacterMetric,
    trendMetric: StatsTrendMetric,
) {
    val points = trends.points
    val values = points.map { it.metrics.trendValue(trendMetric, metric) }
    val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val total = values.sum()
    val barColor = MaterialTheme.colorScheme.primary
    val summary = stringResource(
        KMR.strings.stats_activity_chart_summary,
        points.size,
        formatTrendValue(total, trendMetric),
        formatTrendValue(max, trendMetric),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(KMR.strings.stats_activity_chart))
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .semantics { contentDescription = summary },
        ) {
            if (values.isEmpty()) return@Canvas
            val spacing = size.width / values.size
            val width = (spacing * 0.62f).coerceAtLeast(1f)
            values.forEachIndexed { index, value ->
                if (value <= 0) return@forEachIndexed
                val height = size.height * value.toFloat() / max.toFloat()
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(index * spacing + (spacing - width) / 2, size.height - height),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
        }
        SectionTitle(stringResource(KMR.strings.stats_cumulative))
        val cumulative = points.lastOrNull()?.cumulativeMetrics ?: ReadingMetrics()
        MetricLine(stringResource(KMR.strings.stats_active_time), formatDuration(cumulative.activeTime.value))
        MetricLine(characterMetricLabel(metric), formatCount(cumulative.characterValue(metric)))
        MetricLine(stringResource(KMR.strings.stats_source_units), formatCount(cumulative.sourceUnits.value))
        MetricLine(stringResource(KMR.strings.stats_unique_words), formatCount(cumulative.uniqueWords.value))
        MetricLine(stringResource(KMR.strings.stats_sessions), formatCount(cumulative.sessions.value))
        SectionTitle(stringResource(KMR.strings.stats_efficiency))
        MetricLine(
            stringResource(KMR.strings.stats_reading_speed),
            cumulative.readingSpeedPerHour(metric)?.let(::formatRate)
                ?: stringResource(KMR.strings.stats_unavailable),
        )
        MetricLine(
            stringResource(KMR.strings.stats_lookup_rate),
            cumulative.lookupRatePerTenThousandGrossCharacters()?.let(::formatDecimal)
                ?: stringResource(KMR.strings.stats_unavailable),
        )
        MetricLine(
            stringResource(KMR.strings.stats_mining_rate),
            cumulative.miningRatePerTenThousandGrossCharacters()?.let(::formatDecimal)
                ?: stringResource(KMR.strings.stats_unavailable),
        )
        MetricLine(
            stringResource(KMR.strings.stats_novelty_rate),
            cumulative.noveltyRate()?.let(::formatPercent)
                ?: stringResource(KMR.strings.stats_unavailable),
        )
        SectionTitle(stringResource(KMR.strings.stats_moving_average))
        movingAverage(points, trendMetric, metric).takeLast(10).forEach { (point, average) ->
            MetricLine(
                formatLocalDate(point.range.endInclusive),
                formatTrendValue(average.roundToLong(), trendMetric),
            )
        }
    }
}

@Composable
private fun TemporalPatternsContent(
    activity: AnalyticsTemporalActivity,
    metric: CharacterMetric,
) {
    val hours = activity.hours.sortedBy { it.hourOfDay }
    val weekdays = activity.weekdays.sortedBy { it.isoDayOfWeek }
    val maximumHourlyCharacters = hours
        .maxOfOrNull { it.totals.characterValue(metric).coerceAtLeast(0L) }
        ?.coerceAtLeast(1L)
        ?: 1L
    val maximumWeekdayCharacters = weekdays
        .maxOfOrNull { it.totals.characterValue(metric).coerceAtLeast(0L) }
        ?.coerceAtLeast(1L)
        ?: 1L

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(KMR.strings.stats_time_patterns))
        Text(
            text = stringResource(KMR.strings.stats_time_patterns_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionTitle(stringResource(KMR.strings.stats_hourly_activity))
        hours.chunked(3).forEach { rowHours ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowHours.forEach { hour ->
                    TemporalPointCard(
                        label = formatHour(hour.hourOfDay),
                        totals = hour.totals,
                        metric = metric,
                        intensity = hour.totals.characterValue(metric)
                            .coerceAtLeast(0L)
                            .toFloat() / maximumHourlyCharacters.toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - rowHours.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        SectionTitle(stringResource(KMR.strings.stats_weekday_activity))
        weekdays.forEach { weekday ->
            TemporalWeekdayRow(
                label = formatWeekday(weekday.isoDayOfWeek),
                totals = weekday.totals,
                metric = metric,
                maximumCharacters = maximumWeekdayCharacters,
            )
        }
    }
}

@Composable
private fun TemporalPointCard(
    label: String,
    totals: AnalyticsActivityTotals,
    metric: CharacterMetric,
    intensity: Float,
    modifier: Modifier = Modifier,
) {
    val characters = totals.characterValue(metric).coerceAtLeast(0L)
    val speed = totals.readingSpeedPerHour(metric)
    val charactersText = pluralStringResource(
        KMR.plurals.stats_character_count,
        characters.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        formatCount(characters),
    )
    val speedText = speed?.let {
        stringResource(KMR.strings.stats_per_hour, formatRate(it))
    } ?: stringResource(KMR.strings.stats_unavailable)
    val description = stringResource(
        KMR.strings.stats_temporal_point_description,
        label,
        charactersText,
        formatDuration(totals.activeDurationMillis),
        speedText,
    )
    Surface(
        modifier = modifier.semantics { contentDescription = description },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(
            alpha = 0.18f + 0.62f * intensity.coerceIn(0f, 1f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(formatCount(characters), fontWeight = FontWeight.SemiBold)
            Text(
                text = speedText,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TemporalWeekdayRow(
    label: String,
    totals: AnalyticsActivityTotals,
    metric: CharacterMetric,
    maximumCharacters: Long,
) {
    val characters = totals.characterValue(metric).coerceAtLeast(0L)
    val speed = totals.readingSpeedPerHour(metric)
    val charactersText = pluralStringResource(
        KMR.plurals.stats_character_count,
        characters.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        formatCount(characters),
    )
    val speedText = speed?.let {
        stringResource(KMR.strings.stats_per_hour, formatRate(it))
    } ?: stringResource(KMR.strings.stats_unavailable)
    val description = stringResource(
        KMR.strings.stats_temporal_point_description,
        label,
        charactersText,
        formatDuration(totals.activeDurationMillis),
        speedText,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(
                    KMR.strings.stats_temporal_row_value,
                    formatCount(characters),
                    speedText,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(3.dp),
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(
                        characters.toFloat()
                            .div(maximumCharacters.toFloat())
                            .coerceIn(0f, 1f),
                    )
                    .height(6.dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(3.dp),
                    ),
            )
        }
    }
}

@Composable
private fun TitleContributionsContent(
    trends: AnalyticsTitleTrends,
    metric: CharacterMetric,
    trendMetric: StatsTrendMetric,
    selection: AnalyticsTitleSeriesSelection,
    onSelectionSelect: (AnalyticsTitleSeriesSelection) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(KMR.strings.stats_title_contributions))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnalyticsTitleSeriesSelection.entries.forEach { option ->
                FilterChip(
                    selected = option == selection,
                    onClick = { onSelectionSelect(option) },
                    label = { Text(titleSeriesSelectionLabel(option)) },
                )
            }
        }
        if (trends.series.isEmpty()) {
            EmptyState()
        } else {
            trends.series.forEach { series ->
                TitleContributionRow(
                    series = series,
                    metric = metric,
                    trendMetric = trendMetric,
                )
            }
            Text(
                text = stringResource(
                    KMR.strings.stats_title_series_limit,
                    trends.series.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TitleContributionRow(
    series: AnalyticsTitleTrendSeries,
    metric: CharacterMetric,
    trendMetric: StatsTrendMetric,
) {
    val values = series.points.map { it.metrics.trendValue(trendMetric, metric) }
    val maximum = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val total = series.points.lastOrNull()
        ?.cumulativeMetrics
        ?.trendValue(trendMetric, metric)
        ?: 0L
    val activeBuckets = values.count { it > 0L }
    val activeBucketsText = pluralStringResource(
        KMR.plurals.stats_active_bucket_count,
        activeBuckets,
        activeBuckets,
    )
    val totalText = formatTrendValue(total, trendMetric)
    val description = stringResource(
        KMR.strings.stats_title_contribution_description,
        series.displayTitle,
        totalText,
        trendMetricLabel(trendMetric, metric),
        activeBucketsText,
    )
    val barColor = MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = series.displayTitle,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(totalText, fontWeight = FontWeight.SemiBold)
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                if (values.isEmpty()) return@Canvas
                val spacing = size.width / values.size
                val width = (spacing * 0.65f).coerceAtLeast(1f)
                values.forEachIndexed { index, value ->
                    val height = size.height * value.toFloat() / maximum.toFloat()
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(
                            index * spacing + (spacing - width) / 2,
                            size.height - height,
                        ),
                        size = Size(width, height.coerceAtLeast(1.dp.toPx())),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
            }
            Text(
                text = activeBucketsText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VocabularyGrowthContent(growth: AnalyticsVocabularyFirstSeen) {
    val points = growth.points
    val values = points.map { it.newWords }
    val maximum = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val cumulative = points.lastOrNull()?.cumulativeNewWords ?: 0L
    val activeBuckets = values.count { it > 0L }
    val cumulativeText = pluralStringResource(
        KMR.plurals.stats_word_count,
        cumulative.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        formatCount(cumulative),
    )
    val summary = stringResource(
        KMR.strings.stats_vocabulary_growth_summary,
        cumulativeText,
        activeBuckets,
        points.size,
    )
    val barColor = MaterialTheme.colorScheme.primary

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(KMR.strings.stats_vocabulary_growth))
        Text(
            text = stringResource(KMR.strings.stats_vocabulary_growth_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .semantics { contentDescription = summary },
        ) {
            if (values.isEmpty()) return@Canvas
            val spacing = size.width / values.size
            val width = (spacing * 0.62f).coerceAtLeast(1f)
            values.forEachIndexed { index, value ->
                val height = size.height * value.toFloat() / maximum.toFloat()
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(
                        index * spacing + (spacing - width) / 2,
                        size.height - height,
                    ),
                    size = Size(width, height.coerceAtLeast(1.dp.toPx())),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }
        points.takeLast(8).forEach { point ->
            val newWords = pluralStringResource(
                KMR.plurals.stats_word_count,
                point.newWords.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                formatCount(point.newWords),
            )
            val cumulativeWords = pluralStringResource(
                KMR.plurals.stats_word_count,
                point.cumulativeNewWords.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                formatCount(point.cumulativeNewWords),
            )
            MetricLine(
                formatDateRange(point.range.start, point.range.endInclusive),
                stringResource(
                    KMR.strings.stats_vocabulary_growth_bucket,
                    newWords,
                    cumulativeWords,
                ),
            )
        }
    }
}

@Composable
private fun TitlesTab(
    state: StatsScreenState.Success,
    onSortSelect: (AnalyticsTitleSort) -> Unit,
    onSearch: (String) -> Unit,
    onFilterChange: (AnalyticsTitleFilter) -> Unit,
    onSelect: (AnalyticsTitleRow?) -> Unit,
    onOpen: (AnalyticsTitleRow) -> Unit,
    onManage: (AnalyticsTitleRow) -> Unit,
    onUnlink: () -> Unit,
    onDeleteStats: (AnalyticsTitleRow) -> Unit,
    onDeleteRawText: (AnalyticsTitleRow) -> Unit,
    onCaptureExclusionChange: (Boolean) -> Unit,
    onAcquisitionBucketSizeSelect: (AnalyticsTitleAcquisitionBucketSize) -> Unit,
    onSessionOpen: (ImmersionSession) -> Unit,
    onLoadMore: () -> Unit,
    onLoadMoreSessions: () -> Unit,
    onLoadMoreCompletedUnits: () -> Unit,
    onLoadMoreSources: () -> Unit,
    onSectionRetry: (StatsSection) -> Unit,
) {
    val selected = state.selection.title
    var showUnlinkConfirmation by remember(selected?.titleId) { mutableStateOf(false) }
    val result = state.sections.titles.value
    val rows = result?.value?.items.orEmpty()
    val loadState = state.sections.titles.collectionLoadState(rows.isNotEmpty())
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SearchAndSort(
                query = state.titleSearch,
                onQueryChange = onSearch,
                placeholder = stringResource(KMR.strings.stats_search_titles),
                selectedSort = state.titleSort,
                onSortSelect = onSortSelect,
                allowedSorts = listOf(
                    AnalyticsTitleSort.MOST_RECENT,
                    AnalyticsTitleSort.ALPHABETICAL,
                    AnalyticsTitleSort.MOST_TIME,
                    AnalyticsTitleSort.MOST_CHARACTERS,
                    AnalyticsTitleSort.READING_SPEED,
                    AnalyticsTitleSort.NOVELTY,
                    AnalyticsTitleSort.MINING_RATE,
                    AnalyticsTitleSort.PROGRESS,
                ),
                optionLabel = { titleSortLabel(it) },
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterMenuChip(
                    label = stringResource(
                        KMR.strings.stats_title_state_filter,
                        titleStateFilterLabel(state.titleFilter.state),
                    ),
                    options = AnalyticsTitleStateFilter.entries,
                    optionLabel = { titleStateFilterLabel(it) },
                    onSelect = { onFilterChange(state.titleFilter.copy(state = it)) },
                )
                FilterMenuChip(
                    label = stringResource(
                        KMR.strings.stats_title_coverage_filter,
                        titleCoverageFilterLabel(state.titleFilter.coverage),
                    ),
                    options = AnalyticsTitleCoverageFilter.entries,
                    optionLabel = { titleCoverageFilterLabel(it) },
                    onSelect = { onFilterChange(state.titleFilter.copy(coverage = it)) },
                )
            }
            if (state.titleSort == AnalyticsTitleSort.READING_SPEED) {
                Text(
                    stringResource(KMR.strings.stats_minimum_threshold),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected != null) {
            item {
                TitleDetail(
                    title = selected,
                    metadata = state.titleMetadata[selected.titleId],
                    metric = state.filter.characterMetric,
                    details = state.details,
                    acquisitionBucketSize = state.titleAcquisitionBucketSize,
                    captureExcluded = state.details.titleCaptureExcluded,
                    mutationInProgress = state.details.titleMutationInProgress,
                    mutationError = state.details.titleMutationError,
                    onOpen = { onOpen(selected) },
                    onManage = { onManage(selected) },
                    onUnlink = { showUnlinkConfirmation = true },
                    onDeleteStats = { onDeleteStats(selected) },
                    onDeleteRawText = { onDeleteRawText(selected) },
                    onCaptureExclusionChange = onCaptureExclusionChange,
                    onAcquisitionBucketSizeSelect = onAcquisitionBucketSizeSelect,
                    onSessionOpen = onSessionOpen,
                    onLoadMoreSessions = onLoadMoreSessions,
                    onLoadMoreCompletedUnits = onLoadMoreCompletedUnits,
                    onLoadMoreSources = onLoadMoreSources,
                    onClose = { onSelect(null) },
                )
            }
        }
        if (loadState.showLoading) {
            item { SectionLoading() }
        }
        if (loadState.showError) {
            item {
                SectionError {
                    onSectionRetry(StatsSection.TITLES)
                }
            }
        }
        if (loadState.showEmpty) {
            item { EmptyState() }
        }
        if (loadState.showContent) {
            items(rows, key = { it.titleId.value }) { title ->
                TitleRow(
                    title = title,
                    metadata = state.titleMetadata[title.titleId],
                    metric = state.filter.characterMetric,
                    onClick = { onSelect(title) },
                )
            }
            if (result?.value?.nextOffset != null) {
                item { LoadMoreButton(onLoadMore) }
            }
        }
    }
    if (showUnlinkConfirmation) {
        AlertDialog(
            onDismissRequest = { showUnlinkConfirmation = false },
            title = { Text(stringResource(KMR.strings.stats_title_unlink)) },
            text = { Text(stringResource(KMR.strings.stats_title_unlink_warning)) },
            confirmButton = {
                TextButton(
                    enabled = !state.details.titleMutationInProgress,
                    onClick = {
                        showUnlinkConfirmation = false
                        onUnlink()
                    },
                ) {
                    Text(stringResource(KMR.strings.stats_title_unlink))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnlinkConfirmation = false }) {
                    Text(stringResource(KMR.strings.stats_close))
                }
            },
        )
    }
}

@Composable
private fun VocabularyTab(
    state: StatsScreenState.Success,
    onSortSelect: (AnalyticsSort) -> Unit,
    onSearch: (String) -> Unit,
    onSelect: (AnalyticsWordRow?) -> Unit,
    onLoadMore: () -> Unit,
    onLoadMoreOccurrences: () -> Unit,
    onFilterChange: (VocabularyFilter) -> Unit,
    onWordSelectionChange: (String, Boolean) -> Unit,
    onSelectionClear: () -> Unit,
    onExclusionChange: (Boolean) -> Unit,
    onExport: () -> Unit,
    onSectionRetry: (StatsSection) -> Unit,
) {
    val result = state.sections.vocabulary.value
    val rows = result?.value?.items.orEmpty()
    val loadState = state.sections.vocabulary.collectionLoadState(rows.isNotEmpty())
    val selectedRows = rows.filter { it.id in state.selectedVocabularyWordIds }
    var showFilters by remember { mutableStateOf(false) }
    var pendingExclusion by remember { mutableStateOf<Boolean?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SearchAndSort(
                query = state.vocabularySearch,
                onQueryChange = onSearch,
                placeholder = stringResource(KMR.strings.stats_search_vocabulary),
                selectedSort = state.vocabularySort,
                onSortSelect = onSortSelect,
                allowedSorts = listOf(
                    AnalyticsSort.MOST_OCCURRENCES,
                    AnalyticsSort.MOST_RECENT,
                    AnalyticsSort.FIRST_SEEN,
                    AnalyticsSort.ALPHABETICAL,
                ),
                optionLabel = { sortLabel(it) },
            )
        }
        item {
            VocabularyWorkbenchControls(
                filter = state.vocabularyFilter,
                selectedCount = state.selectedVocabularyWordIds.size,
                mutationInProgress = state.vocabularyMutationInProgress,
                onShowFilters = { showFilters = true },
                onClearSelection = onSelectionClear,
                onExclude = { pendingExclusion = true },
                onInclude = { pendingExclusion = false },
                onExport = onExport,
            )
        }
        if (state.vocabularyMutationError) {
            item { NoticeCard(stringResource(KMR.strings.stats_vocabulary_exclusion_error)) }
        }
        result?.let {
            item {
                InventoryCoverageCard(
                    unique = state.sections.overview.value
                        ?.value
                        ?.comparison
                        ?.current
                        ?.uniqueWords
                        ?.value,
                    quality = it.quality,
                )
            }
        }
        item {
            SectionFrame(
                section = state.sections.vocabularyGrowth,
                onRetry = { onSectionRetry(StatsSection.VOCABULARY_GROWTH) },
            ) { result ->
                VocabularyGrowthContent(result.value)
            }
        }
        state.selection.word?.let { word ->
            item {
                WordDetail(
                    word = word,
                    occurrences = state.details.wordOccurrences,
                    onClose = { onSelect(null) },
                    onLoadMoreOccurrences = onLoadMoreOccurrences,
                )
            }
        }
        if (loadState.showLoading) {
            item { SectionLoading() }
        }
        if (loadState.showError) {
            item {
                SectionError {
                    onSectionRetry(StatsSection.VOCABULARY)
                }
            }
        }
        if (loadState.showEmpty) {
            item { EmptyState() }
        }
        if (loadState.showContent) {
            items(rows, key = { it.id }) { word ->
                WordRow(
                    word = word,
                    selected = word.id in state.selectedVocabularyWordIds,
                    onSelectedChange = { onWordSelectionChange(word.id, it) },
                    onClick = { onSelect(word) },
                )
            }
            if (result?.value?.nextOffset != null) {
                item { LoadMoreButton(onLoadMore) }
            }
        }
    }
    if (showFilters) {
        VocabularyFilterDialog(
            filter = state.vocabularyFilter,
            onDismiss = { showFilters = false },
            onApply = {
                onFilterChange(it)
                showFilters = false
            },
        )
    }
    pendingExclusion?.let { excluded ->
        VocabularyBulkActionDialog(
            excluded = excluded,
            rows = selectedRows,
            onDismiss = { pendingExclusion = null },
            onConfirm = {
                pendingExclusion = null
                onExclusionChange(excluded)
            },
        )
    }
}

@Composable
private fun VocabularyWorkbenchControls(
    filter: VocabularyFilter,
    selectedCount: Int,
    mutationInProgress: Boolean,
    onShowFilters: () -> Unit,
    onClearSelection: () -> Unit,
    onExclude: () -> Unit,
    onInclude: () -> Unit,
    onExport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = filter.activeFilterCount() > 0,
                onClick = onShowFilters,
                leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                label = {
                    Text(
                        if (filter.activeFilterCount() == 0) {
                            stringResource(KMR.strings.stats_vocabulary_filters)
                        } else {
                            stringResource(
                                KMR.strings.stats_vocabulary_filters_active,
                                filter.activeFilterCount(),
                            )
                        },
                    )
                },
            )
            TextButton(onClick = onExport) {
                Icon(Icons.Outlined.Download, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(KMR.strings.stats_export_filtered_vocabulary))
            }
        }
        if (selectedCount > 0) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(KMR.strings.stats_filter_selected_count, selectedCount),
                        fontWeight = FontWeight.SemiBold,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            enabled = !mutationInProgress,
                            onClick = onExclude,
                        ) {
                            Icon(Icons.Outlined.VisibilityOff, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(KMR.strings.stats_vocabulary_exclude))
                        }
                        TextButton(
                            enabled = !mutationInProgress,
                            onClick = onInclude,
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(KMR.strings.stats_vocabulary_include))
                        }
                        TextButton(
                            enabled = !mutationInProgress,
                            onClick = onClearSelection,
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(KMR.strings.stats_clear_selection))
                        }
                    }
                    if (mutationInProgress) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun VocabularyFilterDialog(
    filter: VocabularyFilter,
    onDismiss: () -> Unit,
    onApply: (VocabularyFilter) -> Unit,
) {
    var knownness by remember(filter) { mutableStateOf(filter.knownness) }
    var scripts by remember(filter) { mutableStateOf(filter.scripts) }
    var categories by remember(filter) { mutableStateOf(filter.categories) }
    var partOfSpeech by remember(filter) { mutableStateOf(filter.partOfSpeechQuery.orEmpty()) }
    var minimumOccurrences by remember(filter) {
        mutableStateOf(filter.minimumOccurrences?.toString().orEmpty())
    }
    var maximumOccurrences by remember(filter) {
        mutableStateOf(filter.maximumOccurrences?.toString().orEmpty())
    }
    var maximumFrequencyRank by remember(filter) {
        mutableStateOf(filter.maximumFrequencyRank?.toString().orEmpty())
    }
    var exclusion by remember(filter) { mutableStateOf(filter.exclusion) }
    var invalid by remember(filter) { mutableStateOf(false) }

    fun apply() {
        val parsed = runCatching {
            VocabularyFilter(
                knownness = knownness,
                scripts = scripts,
                categories = categories,
                partOfSpeechQuery = partOfSpeech.trim().takeIf(String::isNotEmpty),
                minimumOccurrences = minimumOccurrences.optionalPositiveLong(),
                maximumOccurrences = maximumOccurrences.optionalPositiveLong(),
                maximumFrequencyRank = maximumFrequencyRank.optionalPositiveLong(),
                exclusion = exclusion,
            )
        }.getOrNull()
        if (parsed == null) {
            invalid = true
        } else {
            onApply(parsed)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.stats_vocabulary_filters)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilterMenuChip(
                    label = stringResource(
                        KMR.strings.stats_vocabulary_knownness_filter,
                        vocabularyKnownnessLabel(knownness),
                    ),
                    options = VocabularyKnownness.entries,
                    optionLabel = { vocabularyKnownnessLabel(it) },
                    onSelect = { knownness = it },
                )
                MultiSelectFilterMenuChip(
                    label = stringResource(
                        KMR.strings.stats_vocabulary_script_filter,
                        filterSelectionLabel(scripts) { vocabularyScriptLabel(it) },
                    ),
                    selected = scripts,
                    options = VocabularyScript.entries,
                    optionLabel = { vocabularyScriptLabel(it) },
                    onSelectionChange = { scripts = it },
                )
                MultiSelectFilterMenuChip(
                    label = stringResource(
                        KMR.strings.stats_vocabulary_category_filter,
                        filterSelectionLabel(categories) { vocabularyCategoryLabel(it) },
                    ),
                    selected = categories,
                    options = VocabularyCategory.entries,
                    optionLabel = { vocabularyCategoryLabel(it) },
                    onSelectionChange = { categories = it },
                )
                FilterMenuChip(
                    label = stringResource(
                        KMR.strings.stats_vocabulary_inclusion_filter,
                        vocabularyExclusionLabel(exclusion),
                    ),
                    options = VocabularyExclusion.entries,
                    optionLabel = { vocabularyExclusionLabel(it) },
                    onSelect = { exclusion = it },
                )
                OutlinedTextField(
                    value = partOfSpeech,
                    onValueChange = {
                        partOfSpeech = it
                        invalid = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(KMR.strings.stats_part_of_speech_filter)) },
                )
                OutlinedTextField(
                    value = minimumOccurrences,
                    onValueChange = {
                        minimumOccurrences = it
                        invalid = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(KMR.strings.stats_minimum_occurrences)) },
                )
                OutlinedTextField(
                    value = maximumOccurrences,
                    onValueChange = {
                        maximumOccurrences = it
                        invalid = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(KMR.strings.stats_maximum_occurrences)) },
                )
                OutlinedTextField(
                    value = maximumFrequencyRank,
                    onValueChange = {
                        maximumFrequencyRank = it
                        invalid = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(KMR.strings.stats_maximum_frequency_rank)) },
                )
                if (invalid) {
                    Text(
                        stringResource(KMR.strings.stats_vocabulary_filter_invalid),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(
                    onClick = {
                        knownness = VocabularyKnownness.ALL
                        scripts = emptySet()
                        categories = emptySet()
                        partOfSpeech = ""
                        minimumOccurrences = ""
                        maximumOccurrences = ""
                        maximumFrequencyRank = ""
                        exclusion = VocabularyExclusion.INCLUDED
                        invalid = false
                    },
                ) {
                    Text(stringResource(KMR.strings.stats_reset_filters))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::apply) {
                Text(stringResource(KMR.strings.stats_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(KMR.strings.stats_close))
            }
        },
    )
}

@Composable
private fun VocabularyBulkActionDialog(
    excluded: Boolean,
    rows: List<AnalyticsWordRow>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val occurrenceCount = rows.sumOf { it.occurrenceCount }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (excluded) {
                        KMR.strings.stats_vocabulary_exclude
                    } else {
                        KMR.strings.stats_vocabulary_include
                    },
                ),
            )
        },
        text = {
            Text(
                stringResource(
                    KMR.strings.stats_vocabulary_bulk_preview,
                    rows.size,
                    formatCount(occurrenceCount),
                    rows.joinToString(limit = 6, truncated = "…") { it.headword },
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(KMR.strings.stats_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(KMR.strings.stats_close))
            }
        },
    )
}

@Composable
private fun CharactersTab(
    state: StatsScreenState.Success,
    onSortSelect: (AnalyticsSort) -> Unit,
    onFilterChange: (AnalyticsCharacterFilter) -> Unit,
    onGridModeSelect: (StatsCharacterGridMode) -> Unit,
    onLayoutSelect: (StatsCharacterLayout) -> Unit,
    onCoverageTargetChange: (Int) -> Unit,
    onSearch: (String) -> Unit,
    onSelect: (AnalyticsCharacterRow?) -> Unit,
    onSelectionChange: (Int, Boolean) -> Unit,
    onSelectionClear: () -> Unit,
    onExport: () -> Unit,
    onLoadMore: () -> Unit,
    onLoadMoreOccurrences: () -> Unit,
    onLoadMoreContainingWords: () -> Unit,
    onContainingWordSelect: (AnalyticsWordRow) -> Unit,
    onSectionRetry: (StatsSection) -> Unit,
) {
    val result = state.sections.characters.value
    val rows = result?.value?.items.orEmpty()
    val loadState = state.sections.characters.collectionLoadState(rows.isNotEmpty())
    val summary = state.sections.characterSummary.value?.value
    val selectedCodePoints = state.selectedCharacterCodePoints
    val selectedIndex = rows.indexOfFirst {
        it.codePoint == state.selection.character?.codePoint
    }
    val previous = rows.getOrNull(selectedIndex - 1)
    val next = rows.getOrNull(selectedIndex + 1)
    LazyVerticalGrid(
        columns = when (state.characterLayout) {
            StatsCharacterLayout.GRID -> GridCells.Adaptive(72.dp)
            StatsCharacterLayout.LIST -> GridCells.Fixed(1)
        },
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            CharacterOverview(
                state = state,
                summary = summary,
                onCoverageTargetChange = onCoverageTargetChange,
                onSectionRetry = onSectionRetry,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SearchAndSort(
                query = state.characterSearch,
                onQueryChange = onSearch,
                placeholder = stringResource(KMR.strings.stats_search_characters),
                selectedSort = state.characterSort,
                onSortSelect = onSortSelect,
                allowedSorts = listOf(
                    AnalyticsSort.MOST_OCCURRENCES,
                    AnalyticsSort.MOST_RECENT,
                    AnalyticsSort.FIRST_SEEN,
                    AnalyticsSort.ALPHABETICAL,
                    AnalyticsSort.FREQUENCY_RANK,
                    AnalyticsSort.PRIORITY,
                ),
                optionLabel = { sortLabel(it) },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            CharacterWorkbenchControls(
                filter = state.characterFilter,
                gridMode = state.characterGridMode,
                layout = state.characterLayout,
                onFilterChange = onFilterChange,
                onGridModeSelect = onGridModeSelect,
                onLayoutSelect = onLayoutSelect,
            )
        }
        if (selectedCodePoints.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(
                            KMR.strings.stats_character_selection_count,
                            selectedCodePoints.size,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onSelectionClear) {
                        Text(stringResource(KMR.strings.stats_clear_selection))
                    }
                    Button(onClick = onExport) {
                        Icon(Icons.Outlined.Download, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(KMR.strings.stats_export))
                    }
                }
            }
        }
        state.selection.character?.let { character ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                CharacterDetail(
                    character = character,
                    priorityMode = state.characterFilter.priorityMode,
                    occurrences = state.details.characterOccurrences,
                    containingWords = state.details.characterContainingWords,
                    ankiItems = state.details.characterAnkiItems,
                    previous = previous,
                    next = next,
                    onClose = { onSelect(null) },
                    onSelect = onSelect,
                    onContainingWordSelect = onContainingWordSelect,
                    onLoadMoreOccurrences = onLoadMoreOccurrences,
                    onLoadMoreContainingWords = onLoadMoreContainingWords,
                )
            }
        }
        if (loadState.showLoading) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLoading() }
        }
        if (loadState.showError) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionError {
                    onSectionRetry(StatsSection.CHARACTERS)
                }
            }
        }
        if (loadState.showEmpty) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyState() }
        }
        if (loadState.showContent) {
            items(rows, key = { it.codePoint.value }) { character ->
                CharacterCell(
                    character = character,
                    mode = state.characterGridMode,
                    layout = state.characterLayout,
                    maximumOccurrenceCount = summary?.maximumOccurrenceCount ?: 0,
                    selected = character.codePoint.value in selectedCodePoints,
                    onSelectedChange = {
                        onSelectionChange(character.codePoint.value, it)
                    },
                    onClick = { onSelect(character) },
                )
            }
            if (result?.value?.nextOffset != null) {
                item(span = { GridItemSpan(maxLineSpan) }) { LoadMoreButton(onLoadMore) }
            }
        }
    }
}

@Composable
private fun CharacterOverview(
    state: StatsScreenState.Success,
    summary: AnalyticsCharacterSummary?,
    onCoverageTargetChange: (Int) -> Unit,
    onSectionRetry: (StatsSection) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(KMR.strings.stats_character_overview))
        SectionFrame(
            section = state.sections.characterSummary,
            onRetry = { onSectionRetry(StatsSection.CHARACTER_SUMMARY) },
        ) { result ->
            val value = result.value
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MetricLine(
                    stringResource(KMR.strings.stats_distinct_characters),
                    formatCount(value.distinctCharacters),
                )
                MetricLine(
                    stringResource(KMR.strings.stats_new_characters),
                    formatCount(value.firstSeenInRange),
                )
                MetricLine(
                    stringResource(KMR.strings.stats_character_gross_exposure),
                    formatCount(value.grossOccurrenceExposure),
                )
                value.scripts.forEach { script ->
                    MetricLine(
                        characterScriptLabel(script.script),
                        formatCount(script.distinctCharacters),
                    )
                }
                val characterMappingConfigured = state.profiles
                    .filter { state.filter.profileId == null || it.id == state.filter.profileId }
                    .any { it.ankiStatsCharacterField.isNotBlank() }
                val snapshotState = state.sections.anki.value?.value?.snapshot?.capabilityState
                val coverageAvailable =
                    characterMappingConfigured &&
                        snapshotState in setOf(
                            CapabilityState.AVAILABLE,
                            CapabilityState.PARTIAL,
                            CapabilityState.STALE,
                        )
                if (coverageAvailable) {
                    CoverageLine(
                        stringResource(KMR.strings.stats_character_anki_coverage),
                        value.representedInAnki,
                        value.distinctCharacters,
                    )
                    CoverageLine(
                        stringResource(KMR.strings.stats_character_mature_coverage),
                        value.matureInAnki,
                        value.distinctCharacters,
                    )
                    val target = characterCoverageTarget(
                        value,
                        state.characterCoverageTargetPercent,
                    )
                    Text(
                        stringResource(
                            KMR.strings.stats_character_coverage_target,
                            state.characterCoverageTargetPercent,
                            formatCount(target.targetCharacters),
                        ),
                    )
                    Slider(
                        value = state.characterCoverageTargetPercent.toFloat(),
                        onValueChange = { onCoverageTargetChange(it.roundToInt()) },
                        valueRange = 50f..100f,
                        steps = 9,
                    )
                    Text(
                        stringResource(
                            KMR.strings.stats_character_daily_suggestion,
                            formatCount(target.dailyPlanningSuggestion),
                            formatCount(target.remainingCharacters),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    NoticeCard(stringResource(KMR.strings.stats_character_anki_unavailable))
                }
            }
        }
        SectionFrame(
            section = state.sections.trends,
            onRetry = { onSectionRetry(StatsSection.TRENDS) },
        ) { result ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(KMR.strings.stats_character_growth))
                TrendsContent(
                    trends = result.value,
                    metric = state.filter.characterMetric,
                    trendMetric = StatsTrendMetric.NEW_CHARACTERS,
                )
            }
        }
        val introducingTitles = state.titleOptions
            .asSequence()
            .filter { it.metrics.newCharacters.value > 0 }
            .sortedByDescending { it.metrics.newCharacters.value }
            .take(5)
            .toList()
        if (introducingTitles.isNotEmpty()) {
            SectionTitle(stringResource(KMR.strings.stats_character_top_titles))
            introducingTitles.forEach { title ->
                MetricLine(title.displayTitle, formatCount(title.metrics.newCharacters.value))
            }
        }
    }
}

@Composable
private fun CharacterWorkbenchControls(
    filter: AnalyticsCharacterFilter,
    gridMode: StatsCharacterGridMode,
    layout: StatsCharacterLayout,
    onFilterChange: (AnalyticsCharacterFilter) -> Unit,
    onGridModeSelect: (StatsCharacterGridMode) -> Unit,
    onLayoutSelect: (StatsCharacterLayout) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = filter.scripts.isEmpty(),
                onClick = { onFilterChange(filter.copy(scripts = emptySet())) },
                label = { Text(stringResource(KMR.strings.stats_all)) },
            )
            AnalyticsCharacterScript.entries.forEach { script ->
                FilterChip(
                    selected = script in filter.scripts,
                    onClick = {
                        val scripts = filter.scripts.toMutableSet().apply {
                            if (!add(script)) remove(script)
                        }
                        onFilterChange(filter.copy(scripts = scripts))
                    },
                    label = { Text(characterScriptLabel(script)) },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterMenuChip(
                label = characterRangeLabel(filter.range),
                options = AnalyticsCharacterRange.entries,
                optionLabel = { characterRangeLabel(it) },
                onSelect = { onFilterChange(filter.copy(range = it)) },
            )
            FilterMenuChip(
                label = characterPriorityModeLabel(filter.priorityMode),
                options = AnalyticsCharacterPriorityMode.entries,
                optionLabel = { characterPriorityModeLabel(it) },
                onSelect = { onFilterChange(filter.copy(priorityMode = it)) },
            )
        }
        Text(
            stringResource(KMR.strings.stats_character_priority_formula),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            StatsCharacterLayout.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = layout == option,
                    onClick = { onLayoutSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index,
                        StatsCharacterLayout.entries.size,
                    ),
                    icon = {
                        Icon(
                            if (option == StatsCharacterLayout.GRID) {
                                Icons.Outlined.GridView
                            } else {
                                Icons.AutoMirrored.Outlined.ViewList
                            },
                            contentDescription = null,
                        )
                    },
                    label = { Text(characterLayoutLabel(option)) },
                )
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            SingleChoiceSegmentedButtonRow {
                StatsCharacterGridMode.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = gridMode == option,
                        onClick = { onGridModeSelect(option) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index,
                            StatsCharacterGridMode.entries.size,
                        ),
                        label = { Text(characterGridModeLabel(option)) },
                    )
                }
            }
        }
        if (gridMode == StatsCharacterGridMode.FREQUENCY) {
            Text(
                stringResource(KMR.strings.stats_character_frequency_legend),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionsTab(
    state: StatsScreenState.Success,
    onSelect: (ImmersionSession?) -> Unit,
    onDelete: (ImmersionSession) -> Unit,
    onRelinkPreview: (TitleId) -> Unit,
    onRelinkPreviewClear: () -> Unit,
    onRelinkApply: () -> Unit,
    onSourceSearch: (String) -> Unit,
    onLoadMoreSourceSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onSectionRetry: (StatsSection) -> Unit,
) {
    val result = state.sections.sessions.value
    val sessions = result?.value?.items.orEmpty()
    val loadState = state.sections.sessions.collectionLoadState(sessions.isNotEmpty())
    val sourceResults = state.details.sourceSearch.value?.value?.items.orEmpty()
    val sourceLoadState = state.details.sourceSearch.collectionLoadState(sourceResults.isNotEmpty())
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = state.sourceSearch,
                onValueChange = onSourceSearch,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text(stringResource(KMR.strings.stats_source_search)) },
            )
        }
        if (state.sourceSearch.isNotBlank()) {
            if (sourceLoadState.showLoading) {
                item { SectionLoading() }
            }
            if (sourceLoadState.showContent) {
                items(sourceResults, key = { it.statsOccurrenceKey() }) {
                    SourceOccurrenceRow(it)
                }
                if (
                    state.details.sourceSearch.value?.value?.nextOffset != null &&
                    !state.details.sourceSearch.error
                ) {
                    item { LoadMoreButton(onLoadMoreSourceSearch) }
                }
            }
            if (sourceLoadState.showError) {
                item {
                    SectionError {
                        onSourceSearch(state.sourceSearch)
                    }
                }
            }
            if (sourceLoadState.showEmpty) {
                item { EmptyState() }
            }
            item { HorizontalDivider() }
        }
        state.selection.session?.let { session ->
            item {
                SessionDetail(
                    fallback = session,
                    detail = state.details.session,
                    deletionPreview = state.details.sessionDeletionPreview,
                    relinkPreview = state.details.sessionRelinkPreview,
                    titleOptions = state.titleOptions,
                    onClose = { onSelect(null) },
                    onDelete = { onDelete(session) },
                    onRelinkPreview = onRelinkPreview,
                    onRelinkPreviewClear = onRelinkPreviewClear,
                    onRelinkApply = onRelinkApply,
                )
            }
        }
        if (loadState.showLoading) {
            item { SectionLoading() }
        }
        if (loadState.showError) {
            item {
                SectionError {
                    onSectionRetry(StatsSection.SESSIONS)
                }
            }
        }
        if (loadState.showEmpty) {
            item { EmptyState() }
        }
        if (loadState.showContent) {
            items(sessions, key = { it.id.value }) { session ->
                SessionRow(session) { onSelect(session) }
            }
            if (result?.value?.nextCursor != null) {
                item { LoadMoreButton(onLoadMore) }
            }
        }
    }
}

@Composable
private fun GoalsTab(
    state: StatsScreenState.Success,
    onSave: (StatsGoalEditorValues, ImmersionGoal?) -> Boolean,
    onArchive: (ImmersionGoal) -> Unit,
    onCheckIn: (String, String?) -> Unit,
    onSectionRetry: (StatsSection) -> Unit,
) {
    val goals = state.sections.goals.value?.value.orEmpty()
    val loadState = state.sections.goals.collectionLoadState(goals.isNotEmpty())
    var showEditor by remember { mutableStateOf(false) }
    var editingGoal by remember { mutableStateOf<ImmersionGoal?>(null) }
    var checkingInGoal by remember { mutableStateOf<ImmersionGoal?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Button(
                onClick = {
                    editingGoal = null
                    showEditor = true
                },
            ) {
                Text(stringResource(KMR.strings.stats_goal_create))
            }
        }
        if (loadState.showLoading) {
            item { SectionLoading() }
        }
        if (loadState.showError) {
            item {
                SectionError {
                    onSectionRetry(StatsSection.GOALS)
                }
            }
        }
        if (loadState.showEmpty) {
            item { Text(stringResource(KMR.strings.stats_no_goals)) }
        }
        if (loadState.showContent) {
            items(goals, key = { it.goal.id }) { goal ->
                GoalCard(
                    goal = goal,
                    onEdit = {
                        editingGoal = goal.goal
                        showEditor = true
                    },
                    onArchive = { onArchive(goal.goal) },
                    onCheckIn = { checkingInGoal = goal.goal },
                )
            }
        }
    }
    if (showEditor) {
        GoalEditorDialog(
            original = editingGoal,
            hasTitleScope = editingGoal?.titleId != null || state.filter.titleId != null,
            onDismiss = { showEditor = false },
            onSave = { values ->
                if (onSave(values, editingGoal)) {
                    showEditor = false
                    true
                } else {
                    false
                }
            },
        )
    }
    checkingInGoal?.let { goal ->
        GoalCheckInDialog(
            onDismiss = { checkingInGoal = null },
            onConfirm = { note ->
                onCheckIn(goal.id, note)
                checkingInGoal = null
            },
        )
    }
}

@Composable
private fun AnkiTab(
    state: StatsScreenState.Success,
    onRefresh: () -> Unit,
    onSectionRetry: (StatsSection) -> Unit,
    onWordCoverageTargetChange: (Int) -> Unit,
    onOpenMissingWords: () -> Unit,
    onOpenMissingCharacters: () -> Unit,
) {
    var refreshRequested by remember(
        state.sections.anki.value?.value?.snapshot?.id,
    ) { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionFrame(
                section = state.sections.anki,
                onRetry = { onSectionRetry(StatsSection.ANKI) },
            ) { result ->
                val summary = result.value
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle(stringResource(KMR.strings.stats_anki_snapshot))
                    val snapshot = summary.snapshot
                    if (snapshot == null) {
                        EmptyState()
                    } else {
                        MetricLine(
                            stringResource(KMR.strings.stats_anki_state),
                            capabilityLabel(
                                ankiPresentationCapabilityState(
                                    capabilityState = snapshot.capabilityState,
                                    isStale = snapshot.isStale,
                                ),
                            ),
                        )
                        if (snapshot.isStale) {
                            NoticeCard(stringResource(KMR.strings.stats_anki_snapshot_stale))
                        }
                        snapshot.completedAtEpochMillis?.let {
                            Text(
                                stringResource(
                                    KMR.strings.stats_anki_snapshot_completed,
                                    formatInstant(it),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            stringResource(
                                KMR.strings.stats_anki_items,
                                formatCount(snapshot.itemCount.toLong()),
                                formatCount(snapshot.noteCount.toLong()),
                            ),
                        )
                        MetricLine(
                            stringResource(KMR.strings.stats_anki_maturity_threshold),
                            stringResource(
                                KMR.strings.stats_anki_mature_interval_days,
                                snapshot.matureIntervalDays,
                            ),
                        )
                    }
                    summary.generatedAtEpochMillis?.let {
                        SectionTitle(stringResource(KMR.strings.stats_anki_freshness))
                        Text(
                            stringResource(
                                KMR.strings.stats_anki_report_generated,
                                formatInstant(it),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = {
                            onRefresh()
                            refreshRequested = true
                        },
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(KMR.strings.stats_anki_refresh_inventory))
                    }
                    if (refreshRequested) {
                        Text(
                            stringResource(KMR.strings.stats_anki_refresh_requested),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (summary.capabilities.isNotEmpty()) {
                        SectionTitle(stringResource(KMR.strings.stats_anki_capabilities))
                        summary.capabilities.forEach { capability ->
                            MetricLine(
                                ankiReportLabel(capability.report),
                                stringResource(
                                    KMR.strings.stats_anki_capability_value,
                                    capabilityLabel(capability.state),
                                    ankiCapabilityReasonLabel(capability.reason),
                                ),
                            )
                        }
                    }
                    CoverageLine(
                        stringResource(KMR.strings.stats_word_coverage),
                        summary.wordCoverageKnown,
                        summary.wordCoverageEncountered,
                    )
                    CoverageLine(
                        stringResource(KMR.strings.stats_character_coverage),
                        summary.characterCoverageKnown,
                        summary.characterCoverageEncountered,
                    )
                    Text(
                        stringResource(
                            KMR.strings.stats_anki_word_coverage_target,
                            state.ankiWordCoverageTargetPercent,
                        ),
                    )
                    Slider(
                        value = state.ankiWordCoverageTargetPercent.toFloat(),
                        onValueChange = { onWordCoverageTargetChange(it.roundToInt()) },
                        valueRange = 50f..100f,
                        steps = 9,
                    )
                    Text(
                        stringResource(
                            KMR.strings.stats_anki_character_coverage_target,
                            state.characterCoverageTargetPercent,
                        ),
                    )
                    MetricLine(
                        stringResource(KMR.strings.stats_cards_created),
                        formatCount(summary.cardsCreated),
                    )
                    MetricLine(
                        stringResource(KMR.strings.stats_cards_updated),
                        formatCount(summary.cardsUpdated),
                    )
                    MetricLine(
                        stringResource(KMR.strings.stats_anki_linked_operations),
                        formatCount(summary.linkedOperationCount),
                    )
                    MetricLine(
                        stringResource(KMR.strings.stats_anki_unattributed_operations),
                        formatCount(summary.unattributedOperationCount),
                    )
                    summary.meanReadingToCardLagMillis?.let {
                        MetricLine(
                            stringResource(KMR.strings.stats_anki_reading_to_card_lag),
                            formatDuration(it),
                        )
                    }
                    if (summary.weeklyImpact.isNotEmpty()) {
                        SectionTitle(stringResource(KMR.strings.stats_anki_weekly_impact))
                        summary.weeklyImpact.asReversed().take(12).forEach { week ->
                            Text(
                                stringResource(
                                    KMR.strings.stats_anki_week_heading,
                                    formatDateRange(week.weekStart, week.weekEndInclusive),
                                    stringResource(
                                        if (week.partial) {
                                            KMR.strings.stats_anki_week_partial
                                        } else {
                                            KMR.strings.stats_anki_week_complete
                                        },
                                    ),
                                ),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(
                                    KMR.strings.stats_anki_week_volume,
                                    formatCount(week.grossCharacters),
                                    formatDuration(week.activeDurationMillis),
                                ),
                            )
                            Text(
                                stringResource(
                                    KMR.strings.stats_anki_week_cards,
                                    formatCount(week.cardsCreated),
                                    formatCount(week.cardsUpdated),
                                ),
                            )
                            MetricLine(
                                stringResource(KMR.strings.stats_anki_linked_operations),
                                formatCount(week.linkedOperations),
                            )
                            MetricLine(
                                stringResource(KMR.strings.stats_anki_unattributed_operations),
                                formatCount(week.unattributedOperations),
                            )
                            MetricLine(
                                stringResource(KMR.strings.stats_anki_same_week_flow),
                                formatCount(week.sameWeekReadingToCardOperations),
                            )
                            week.meanReadingToCardLagMillis?.let {
                                MetricLine(
                                    stringResource(KMR.strings.stats_anki_reading_to_card_lag),
                                    formatDuration(it),
                                )
                            }
                            if (week.maturedOperations > 0) {
                                MetricLine(
                                    stringResource(KMR.strings.stats_anki_matured_links),
                                    formatCount(week.maturedOperations),
                                )
                            }
                            week.meanCardToMaturityLagMillis?.let {
                                MetricLine(
                                    stringResource(KMR.strings.stats_anki_card_to_maturity_lag),
                                    formatDuration(it),
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                    if (summary.titleImpact.isNotEmpty()) {
                        SectionTitle(stringResource(KMR.strings.stats_anki_title_impact))
                        summary.titleImpact.take(10).forEach { title ->
                            Text(
                                title.displayTitle
                                    ?: stringResource(KMR.strings.stats_anki_unattributed),
                                fontWeight = FontWeight.SemiBold,
                            )
                            MetricLine(
                                if (title.titleId == null) {
                                    stringResource(KMR.strings.stats_anki_unattributed_operations)
                                } else {
                                    stringResource(KMR.strings.stats_anki_linked_operations)
                                },
                                formatCount(title.operationCount),
                            )
                            title.cardsPerTenThousandGrossCharacters()?.let {
                                Text(
                                    stringResource(
                                        KMR.strings.stats_cards_per_ten_thousand,
                                        formatDecimal(it),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (summary.maturityDistribution.isNotEmpty()) {
                        SectionTitle(stringResource(KMR.strings.stats_anki_maturity_distribution))
                        summary.maturityDistribution.forEach { (tier, count) ->
                            MetricLine(maturityLabel(tier), formatCount(count))
                        }
                    }
                    if (summary.missingHighFrequencyWords.isNotEmpty()) {
                        SectionTitle(stringResource(KMR.strings.stats_anki_missing_words))
                        summary.missingHighFrequencyWords.take(10).forEach { word ->
                            MetricLine(word.headword, word.frequencyRank?.let(::formatCount) ?: "—")
                        }
                    }
                    Button(onClick = onOpenMissingWords) {
                        Text(stringResource(KMR.strings.stats_anki_open_word_workbench))
                    }
                    if (summary.missingHighFrequencyCharacters.isNotEmpty()) {
                        SectionTitle(stringResource(KMR.strings.stats_anki_missing_characters))
                        summary.missingHighFrequencyCharacters.take(10).forEach { character ->
                            MetricLine(
                                character.rendered,
                                character.frequencyRank?.let(::formatCount) ?: "—",
                            )
                        }
                    }
                    Button(onClick = onOpenMissingCharacters) {
                        Text(stringResource(KMR.strings.stats_anki_open_character_workbench))
                    }
                    NoticeCard(
                        stringResource(
                            KMR.strings.stats_anki_sample_limit,
                            summary.minimumComparisonSampleSize,
                        ),
                    )
                    NoticeCard(stringResource(KMR.strings.stats_anki_observational))
                    if (!summary.reviewHistoryAvailable) {
                        NoticeCard(stringResource(KMR.strings.stats_review_history_unavailable))
                    }
                }
            }
        }
    }
}

@Composable
private fun DataQualityCard(quality: AnalyticsDataQuality) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle(stringResource(KMR.strings.stats_data_quality))
            MetricLine(
                stringResource(KMR.strings.stats_legacy_share),
                quality.legacyShare?.let(::formatPercent) ?: stringResource(KMR.strings.stats_unavailable),
            )
            MetricLine(
                stringResource(KMR.strings.stats_indexing_coverage),
                quality.indexingCompletion?.let(::formatPercent)
                    ?: stringResource(KMR.strings.stats_unavailable),
            )
            MetricLine(
                stringResource(KMR.strings.stats_text_coverage),
                quality.textCoverage?.let(::formatPercent) ?: stringResource(KMR.strings.stats_unavailable),
            )
            MetricLine(
                stringResource(KMR.strings.stats_ocr_coverage),
                quality.ocrTextCoverage?.let(::formatPercent)
                    ?: stringResource(KMR.strings.stats_unavailable),
            )
            MetricLine(stringResource(KMR.strings.stats_anki_state), capabilityLabel(quality.ankiState))
            if (quality.ankiState == CapabilityState.STALE) {
                Text(
                    text = stringResource(KMR.strings.stats_anki_snapshot_stale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MetricLine(
                stringResource(KMR.strings.stats_provenance_state),
                provenanceLabel(quality.provenanceState),
            )
        }
    }
}

@Composable
private fun TitleRow(
    title: AnalyticsTitleRow,
    metadata: StatsTitlePresentationMetadata?,
    metric: CharacterMetric,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TitleCover(
                title = metadata?.localDisplayTitle ?: title.displayTitle,
                coverLocation = metadata?.coverLocation,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    metadata?.localDisplayTitle ?: title.displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                metadata?.author?.let {
                    Text(
                        stringResource(KMR.strings.stats_title_author, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    stringResource(
                        KMR.strings.stats_media_and_language,
                        mediaLabel(title.mediaKind),
                        title.languageTag?.value ?: stringResource(KMR.strings.stats_unknown),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MetricLine(
                    stringResource(KMR.strings.stats_active_time),
                    formatDuration(title.metrics.activeTime.value),
                )
                MetricLine(characterMetricLabel(metric), formatCount(title.metrics.characterValue(metric)))
                MetricLine(
                    stringResource(KMR.strings.stats_reading_speed),
                    title.metrics.readingSpeedPerHour(metric)?.let(::formatRate)
                        ?: stringResource(KMR.strings.stats_unavailable),
                )
                MetricLine(stringResource(KMR.strings.stats_sessions), formatCount(title.metrics.sessions.value))
                MetricLine(stringResource(KMR.strings.stats_new_words), formatCount(title.metrics.newWords.value))
                MetricLine(
                    stringResource(KMR.strings.stats_new_characters),
                    formatCount(title.metrics.newCharacters.value),
                )
                MetricLine(
                    stringResource(KMR.strings.stats_cards_created),
                    formatCount(title.metrics.cardsCreated.value),
                )
                MetricLine(
                    stringResource(KMR.strings.stats_progress),
                    title.progress?.let(::formatPercent)
                        ?: stringResource(KMR.strings.stats_unavailable),
                )
                MetricLine(
                    stringResource(KMR.strings.stats_indexing_coverage),
                    title.coverage.indexingCompletion?.let(::formatPercent)
                        ?: stringResource(KMR.strings.stats_unavailable),
                )
                Text(
                    stringResource(KMR.strings.stats_last_active, formatLocalDate(title.lastActiveDate)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun TitleDetail(
    title: AnalyticsTitleRow,
    metadata: StatsTitlePresentationMetadata?,
    metric: CharacterMetric,
    details: StatsDetails,
    acquisitionBucketSize: AnalyticsTitleAcquisitionBucketSize,
    captureExcluded: StatsLoadable<Boolean>,
    mutationInProgress: Boolean,
    mutationError: Boolean,
    onOpen: () -> Unit,
    onManage: () -> Unit,
    onUnlink: () -> Unit,
    onDeleteStats: () -> Unit,
    onDeleteRawText: () -> Unit,
    onCaptureExclusionChange: (Boolean) -> Unit,
    onAcquisitionBucketSizeSelect: (AnalyticsTitleAcquisitionBucketSize) -> Unit,
    onSessionOpen: (ImmersionSession) -> Unit,
    onLoadMoreSessions: () -> Unit,
    onLoadMoreCompletedUnits: () -> Unit,
    onLoadMoreSources: () -> Unit,
    onClose: () -> Unit,
) {
    DetailCard(
        title = stringResource(KMR.strings.stats_title_detail),
        onClose = onClose,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TitleCover(
                title = metadata?.localDisplayTitle ?: title.displayTitle,
                coverLocation = metadata?.coverLocation,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    metadata?.localDisplayTitle ?: title.displayTitle,
                    style = MaterialTheme.typography.titleLarge,
                )
                metadata?.author?.let {
                    Text(
                        stringResource(KMR.strings.stats_title_author, it),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        MetricLine(
            stringResource(KMR.strings.stats_title_link),
            titleLinkLabel(metadata?.linkState),
        )
        MetricLine(
            stringResource(KMR.strings.stats_title_identity),
            title.sourceKey,
        )
        MetricLine(
            stringResource(KMR.strings.stats_title_status),
            titleStateLabel(title.completed),
        )
        title.totalUnits?.let { totalUnits ->
            MetricLine(
                stringResource(KMR.strings.stats_title_total_units),
                titleUnitCount(title.mediaKind, totalUnits),
            )
        }
        MetricLine(
            stringResource(KMR.strings.stats_title_completed_units),
            if (title.unitProgress.hasTrustworthyIdentity) {
                titleUnitCount(title.mediaKind, title.unitProgress.completedUnits)
            } else {
                stringResource(KMR.strings.stats_unavailable)
            },
        )
        if (!title.unitProgress.hasTrustworthyIdentity) {
            Text(
                stringResource(
                    if (title.unitProgress.identityAvailable) {
                        KMR.strings.stats_title_completed_units_incomplete
                    } else {
                        KMR.strings.stats_title_completed_units_unavailable
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (title.profileId.isNotBlank()) {
            Text(stringResource(KMR.strings.stats_profile, title.profileId))
        }
        if (metadata?.linkState == StatsTitleLinkState.AVAILABLE) {
            Button(onClick = onOpen) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(KMR.strings.stats_title_open))
            }
            TextButton(
                enabled = !mutationInProgress,
                onClick = onUnlink,
            ) {
                Icon(Icons.Outlined.LinkOff, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(KMR.strings.stats_title_unlink))
            }
        }
        Button(
            enabled = !mutationInProgress,
            onClick = onManage,
        ) {
            Icon(Icons.Outlined.Tune, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(KMR.strings.stats_title_manage))
        }
        TextButton(
            enabled = !mutationInProgress,
            onClick = onDeleteRawText,
        ) {
            Icon(Icons.Outlined.VisibilityOff, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(KMR.strings.stats_title_delete_raw_text))
        }
        TextButton(
            enabled = !mutationInProgress,
            onClick = onDeleteStats,
        ) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(KMR.strings.stats_title_delete_stats))
        }
        if (mutationInProgress) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (mutationError) {
            NoticeCard(stringResource(KMR.strings.stats_title_mutation_error))
        }
        MetricLine(stringResource(KMR.strings.stats_active_time), formatDuration(title.metrics.activeTime.value))
        MetricLine(characterMetricLabel(metric), formatCount(title.metrics.characterValue(metric)))
        MetricLine(
            stringResource(KMR.strings.stats_reading_speed),
            title.metrics.readingSpeedPerHour(metric)?.let(::formatRate)
                ?: stringResource(KMR.strings.stats_unavailable),
        )
        MetricLine(
            stringResource(KMR.strings.stats_source_units_exposed),
            pluralStringResource(
                KMR.plurals.stats_source_unit_count,
                title.metrics.sourceUnits.value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                formatCount(title.metrics.sourceUnits.value),
            ),
        )
        MetricLine(stringResource(KMR.strings.stats_sessions), formatCount(title.metrics.sessions.value))
        MetricLine(stringResource(KMR.strings.stats_lookups), formatCount(title.metrics.successfulLookups.value))
        MetricLine(stringResource(KMR.strings.stats_cards_created), formatCount(title.metrics.cardsCreated.value))
        MetricLine(stringResource(KMR.strings.stats_cards_updated), formatCount(title.metrics.cardsUpdated.value))
        MetricLine(stringResource(KMR.strings.stats_unique_words), formatCount(title.metrics.uniqueWords.value))
        MetricLine(stringResource(KMR.strings.stats_new_words), formatCount(title.metrics.newWords.value))
        MetricLine(
            stringResource(KMR.strings.stats_novelty_rate),
            title.metrics.noveltyRate()?.let(::formatPercent)
                ?: stringResource(KMR.strings.stats_unavailable),
        )
        MetricLine(
            stringResource(KMR.strings.stats_new_word_rate),
            title.metrics.newWordsPerTenThousandGrossCharacters()?.let(::formatDecimal)
                ?: stringResource(KMR.strings.stats_unavailable),
        )
        MetricLine(
            stringResource(KMR.strings.stats_lookup_rate),
            title.metrics.lookupRatePerTenThousandGrossCharacters()?.let(::formatDecimal)
                ?: stringResource(KMR.strings.stats_unavailable),
        )
        MetricLine(
            stringResource(KMR.strings.stats_mining_rate),
            title.metrics.miningRatePerTenThousandGrossCharacters()?.let(::formatDecimal)
                ?: stringResource(KMR.strings.stats_unavailable),
        )
        MetricLine(
            stringResource(KMR.strings.stats_distinct_characters),
            formatCount(title.metrics.distinctCharacters.value),
        )
        MetricLine(
            stringResource(KMR.strings.stats_new_characters),
            formatCount(title.metrics.newCharacters.value),
        )
        MetricLine(
            stringResource(KMR.strings.stats_character_coverage),
            title.metrics.characterCoverage.ratio()?.let(::formatPercent)
                ?: stringResource(KMR.strings.stats_unavailable),
        )
        Text(stringResource(KMR.strings.stats_first_active, formatLocalDate(title.firstActiveDate)))
        Text(stringResource(KMR.strings.stats_last_active, formatLocalDate(title.lastActiveDate)))
        MetricLine(
            stringResource(KMR.strings.stats_active_days),
            pluralStringResource(
                KMR.plurals.stats_active_day_count,
                title.activeDays,
                formatCount(title.activeDays.toLong()),
            ),
        )
        MetricLine(
            stringResource(KMR.strings.stats_calendar_span),
            pluralStringResource(
                KMR.plurals.stats_day_count,
                title.calendarSpanDays,
                formatCount(title.calendarSpanDays.toLong()),
            ),
        )
        MetricLine(
            stringResource(KMR.strings.stats_average_per_active_day),
            stringResource(
                KMR.strings.stats_day_value,
                formatCount(title.averageCharactersPerActiveDay.roundToLong()),
                formatDuration(title.averageActiveTimePerActiveDayMillis.roundToLong()),
            ),
        )
        title.dayHighlights.characters?.let { highlight ->
            MetricLine(
                stringResource(KMR.strings.stats_best_characters_day),
                stringResource(
                    KMR.strings.stats_day_value,
                    formatLocalDate(highlight.date),
                    formatCount(highlight.value.roundToLong()),
                ),
            )
        }
        title.dayHighlights.activeTime?.let { highlight ->
            MetricLine(
                stringResource(KMR.strings.stats_best_time_day),
                stringResource(
                    KMR.strings.stats_day_value,
                    formatLocalDate(highlight.date),
                    formatDuration(highlight.value.roundToLong()),
                ),
            )
        }
        title.dayHighlights.speed?.let { highlight ->
            MetricLine(
                stringResource(KMR.strings.stats_best_speed_day),
                stringResource(
                    KMR.strings.stats_day_value,
                    formatLocalDate(highlight.date),
                    formatRate(highlight.value),
                ),
            )
        }
        title.progress?.let {
            MetricLine(stringResource(KMR.strings.stats_progress), formatPercent(it))
        } ?: MetricLine(
            stringResource(KMR.strings.stats_progress),
            stringResource(KMR.strings.stats_unavailable),
        )
        title.estimate?.let { estimate ->
            MetricLine(
                stringResource(KMR.strings.stats_estimated_remaining),
                formatDuration(estimate.estimatedActiveTimeMillis),
            )
            Text(
                estimateRemainingAmount(title.mediaKind, estimate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    KMR.strings.stats_estimate_confidence,
                    estimateConfidenceLabel(estimate.confidence),
                    estimate.qualifyingDayCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } ?: MetricLine(
            stringResource(KMR.strings.stats_estimated_remaining),
            stringResource(KMR.strings.stats_estimate_unavailable),
        )
        HorizontalDivider()
        SectionTitle(stringResource(KMR.strings.stats_title_activity_history))
        SectionFrame(details.titleTrends) { result ->
            TrendsContent(
                trends = result.value,
                metric = metric,
                trendMetric = StatsTrendMetric.CHARACTERS,
            )
        }
        HorizontalDivider()
        SectionTitle(stringResource(KMR.strings.stats_word_acquisition))
        FilterMenuChip(
            label = titleAcquisitionBucketLabel(acquisitionBucketSize),
            options = AnalyticsTitleAcquisitionBucketSize.entries,
            optionLabel = { titleAcquisitionBucketLabel(it) },
            onSelect = onAcquisitionBucketSizeSelect,
        )
        SectionFrame(details.titleWordAcquisition) { result ->
            TitleWordAcquisitionContent(result.value)
        }
        HorizontalDivider()
        SectionTitle(stringResource(KMR.strings.stats_title_completed_unit_history))
        SectionFrame(details.titleCompletedUnits) { result ->
            TitleCompletedUnitsContent(
                result = result,
                onLoadMore = onLoadMoreCompletedUnits,
            )
        }
        HorizontalDivider()
        SectionTitle(stringResource(KMR.strings.stats_sessions))
        SectionFrame(details.titleSessions) { result ->
            TitleSessionsContent(
                result = result,
                onOpen = onSessionOpen,
                onLoadMore = onLoadMoreSessions,
            )
        }
        HorizontalDivider()
        SectionTitle(stringResource(KMR.strings.stats_source_occurrences))
        SectionFrame(details.titleSources) { result ->
            TitleSourcesContent(
                result = result,
                onLoadMore = onLoadMoreSources,
            )
        }
        HorizontalDivider()
        Text(stringResource(KMR.strings.stats_data_quality), fontWeight = FontWeight.SemiBold)
        MetricLine(
            stringResource(KMR.strings.stats_indexing_coverage),
            title.coverage.indexingCompletion?.let(::formatPercent)
                ?: stringResource(KMR.strings.stats_unavailable),
        )
        MetricLine(
            stringResource(KMR.strings.stats_text_coverage),
            title.coverage.textCoverage?.let(::formatPercent)
                ?: stringResource(KMR.strings.stats_unavailable),
        )
        MetricLine(
            stringResource(KMR.strings.stats_ocr_coverage),
            title.coverage.ocrTextCoverage?.let(::formatPercent)
                ?: stringResource(KMR.strings.stats_unavailable),
        )
        MetricLine(
            stringResource(KMR.strings.stats_provenance_state),
            provenanceLabel(title.coverage.provenanceState),
        )
        captureExcluded.value?.let { excluded ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !captureExcluded.refreshing) {
                        onCaptureExclusionChange(!excluded)
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(KMR.strings.stats_capture_title_toggle))
                    Text(
                        stringResource(KMR.strings.stats_capture_title_toggle_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = excluded,
                    enabled = !captureExcluded.refreshing,
                    onCheckedChange = onCaptureExclusionChange,
                )
            }
        }
        if (captureExcluded.refreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (captureExcluded.error) {
            NoticeCard(stringResource(KMR.strings.stats_capture_title_error_summary))
        }
    }
}

@Composable
private fun TitleWordAcquisitionContent(acquisition: AnalyticsTitleWordAcquisition) {
    if (acquisition.buckets.isEmpty()) {
        EmptyState()
        return
    }
    val maximum = acquisition.buckets.maxOf { it.newWords }.coerceAtLeast(1)
    val totalWords = acquisition.buckets.last().cumulativeNewWords
    val summary = stringResource(
        KMR.strings.stats_word_acquisition_summary,
        pluralStringResource(
            KMR.plurals.stats_character_count,
            acquisition.totalGrossCharacters.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            formatCount(acquisition.totalGrossCharacters),
        ),
        pluralStringResource(
            KMR.plurals.stats_word_count,
            totalWords.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            formatCount(totalWords),
        ),
        acquisition.buckets.size,
    )
    val color = MaterialTheme.colorScheme.tertiary
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .semantics { contentDescription = summary },
        ) {
            val visible = acquisition.buckets.takeLast(TITLE_ACQUISITION_VISIBLE_BUCKETS)
            if (visible.isEmpty()) return@Canvas
            val spacing = size.width / visible.size
            val width = (spacing * 0.62f).coerceAtLeast(1f)
            visible.forEachIndexed { index, bucket ->
                val height = size.height * bucket.newWords.toFloat() / maximum.toFloat()
                drawRoundRect(
                    color = color,
                    topLeft = Offset(
                        index * spacing + (spacing - width) / 2,
                        size.height - height,
                    ),
                    size = Size(width, height.coerceAtLeast(1.dp.toPx())),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }
        acquisition.buckets.takeLast(TITLE_ACQUISITION_VISIBLE_BUCKETS).forEach { bucket ->
            val newWords = pluralStringResource(
                KMR.plurals.stats_word_count,
                bucket.newWords.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                formatCount(bucket.newWords),
            )
            val cumulative = pluralStringResource(
                KMR.plurals.stats_word_count,
                bucket.cumulativeNewWords.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                formatCount(bucket.cumulativeNewWords),
            )
            MetricLine(
                stringResource(
                    KMR.strings.stats_character_bucket_range,
                    formatCount(bucket.startCharacter),
                    formatCount(bucket.endCharacterInclusive),
                ),
                stringResource(
                    KMR.strings.stats_word_acquisition_bucket_value,
                    newWords,
                    cumulative,
                ),
            )
        }
    }
}

@Composable
private fun TitleCompletedUnitsContent(
    result: AnalyticsResult<AnalyticsPage<AnalyticsTitleCompletedUnit>>,
    onLoadMore: () -> Unit,
) {
    if (result.value.items.isEmpty()) {
        EmptyState()
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        result.value.items.forEachIndexed { index, unit ->
            Text(
                unit.completionUnitId,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    KMR.strings.stats_title_unit_completed_on,
                    formatLocalDate(unit.firstCompletedDate),
                    pluralStringResource(
                        KMR.plurals.stats_completion_event_count,
                        unit.completionEventCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        formatCount(unit.completionEventCount),
                    ),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (index < result.value.items.lastIndex) HorizontalDivider()
        }
        if (result.value.nextOffset != null) LoadMoreButton(onLoadMore)
    }
}

@Composable
private fun TitleSessionsContent(
    result: AnalyticsResult<SessionPage>,
    onOpen: (ImmersionSession) -> Unit,
    onLoadMore: () -> Unit,
) {
    if (result.value.items.isEmpty()) {
        EmptyState()
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        result.value.items.forEachIndexed { index, session ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(session) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Schedule, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(formatInstant(session.startedAtEpochMillis), fontWeight = FontWeight.SemiBold)
                    Text(
                        sessionStatusLabel(session.status),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(formatDuration(session.activeDuration.value))
            }
            if (index < result.value.items.lastIndex) HorizontalDivider()
        }
        if (result.value.nextCursor != null) LoadMoreButton(onLoadMore)
    }
}

@Composable
private fun TitleSourcesContent(
    result: AnalyticsResult<AnalyticsPage<AnalyticsSourceOccurrence>>,
    onLoadMore: () -> Unit,
) {
    if (result.value.items.isEmpty()) {
        EmptyState()
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        result.value.items.forEachIndexed { index, occurrence ->
            SourceOccurrenceContent(occurrence)
            if (index < result.value.items.lastIndex) HorizontalDivider()
        }
        if (result.value.nextOffset != null) LoadMoreButton(onLoadMore)
    }
}

@Composable
private fun TitleCover(
    title: String,
    coverLocation: String?,
) {
    Surface(
        modifier = Modifier
            .width(56.dp)
            .height(80.dp)
            .clip(RoundedCornerShape(4.dp)),
        color = MaterialTheme.colorScheme.surface,
    ) {
        if (coverLocation == null) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Style,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            AsyncImage(
                model = coverLocation,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun titleLinkLabel(state: StatsTitleLinkState?): String = when (state) {
    StatsTitleLinkState.AVAILABLE -> stringResource(KMR.strings.stats_title_link_available)
    StatsTitleLinkState.LEGACY_ONLY -> stringResource(KMR.strings.stats_title_link_legacy_only)
    StatsTitleLinkState.UNAVAILABLE, null ->
        stringResource(KMR.strings.stats_title_link_unavailable)
}

@Composable
private fun estimateRemainingAmount(
    mediaKind: MediaKind,
    estimate: AnalyticsTitleEstimate,
): String {
    val quantity = estimate.remainingAmount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return when (estimate.unit) {
        AnalyticsEstimateUnit.CHARACTERS -> pluralStringResource(
            KMR.plurals.stats_character_count,
            quantity,
            formatCount(estimate.remainingAmount),
        )
        AnalyticsEstimateUnit.MEDIA_UNITS -> titleUnitCount(mediaKind, estimate.remainingAmount)
    }
}

@Composable
private fun titleStateLabel(completed: Boolean?): String = when (completed) {
    true -> stringResource(KMR.strings.stats_title_completed)
    false -> stringResource(KMR.strings.stats_title_in_progress)
    null -> stringResource(KMR.strings.stats_title_state_unknown)
}

@Composable
private fun titleUnitCount(
    mediaKind: MediaKind,
    count: Long,
): String {
    val quantity = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val formatted = formatCount(count)
    return when (mediaKind) {
        MediaKind.NOVEL -> pluralStringResource(KMR.plurals.stats_section_count, quantity, formatted)
        MediaKind.MANGA -> pluralStringResource(KMR.plurals.stats_chapter_count, quantity, formatted)
        MediaKind.VIDEO -> pluralStringResource(KMR.plurals.stats_episode_count, quantity, formatted)
    }
}

@Composable
private fun estimateConfidenceLabel(confidence: AnalyticsEstimateConfidence): String =
    when (confidence) {
        AnalyticsEstimateConfidence.LOW -> stringResource(KMR.strings.stats_confidence_low)
        AnalyticsEstimateConfidence.MEDIUM -> stringResource(KMR.strings.stats_confidence_medium)
        AnalyticsEstimateConfidence.HIGH -> stringResource(KMR.strings.stats_confidence_high)
    }

@Composable
private fun WordRow(
    word: AnalyticsWordRow,
    selected: Boolean? = null,
    onSelectedChange: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val description = stringResource(
        KMR.strings.stats_word_row_description,
        word.headword,
        maturityLabel(word.maturity),
        formatCount(word.occurrenceCount),
        wordKnownnessLabel(word.maturity),
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null && onSelectedChange != null) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = onSelectedChange,
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(word.headword, style = MaterialTheme.typography.titleMedium)
                word.reading?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                if (word.excluded) {
                    Text(
                        stringResource(KMR.strings.stats_vocabulary_excluded),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatCount(word.occurrenceCount), fontWeight = FontWeight.SemiBold)
                Text(
                    maturityLabel(word.maturity),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun WordDetail(
    word: AnalyticsWordRow,
    occurrences: StatsLoadable<AnalyticsResult<tachiyomi.domain.immersion.model.AnalyticsPage<AnalyticsSourceOccurrence>>>,
    onClose: () -> Unit,
    onLoadMoreOccurrences: () -> Unit,
) {
    val context = LocalContext.current
    DetailCard(stringResource(KMR.strings.stats_word_detail), onClose) {
        Text(word.headword, style = MaterialTheme.typography.headlineMedium)
        word.reading?.let { Text(stringResource(KMR.strings.stats_reading, it)) }
        word.partOfSpeech?.let { Text(stringResource(KMR.strings.stats_part_of_speech, it)) }
        MetricLine(
            stringResource(KMR.strings.stats_occurrence_label),
            formatCount(word.occurrenceCount),
        )
        MetricLine(stringResource(KMR.strings.stats_tab_titles), formatCount(word.titleCount))
        Text(stringResource(KMR.strings.stats_first_seen, formatInstant(word.firstSeenAtEpochMillis)))
        Text(stringResource(KMR.strings.stats_last_seen, formatInstant(word.lastSeenAtEpochMillis)))
        Text(stringResource(KMR.strings.stats_maturity, maturityLabel(word.maturity)))
        MetricLine(
            stringResource(KMR.strings.stats_frequency_rank),
            word.frequencyRank?.let(::formatCount) ?: stringResource(KMR.strings.stats_unavailable),
        )
        word.jlptLevel?.let {
            MetricLine(stringResource(KMR.strings.stats_jlpt_level), "N$it")
        }
        word.gradeLevel?.let {
            MetricLine(stringResource(KMR.strings.stats_grade_level), formatCount(it.toLong()))
        }
        MetricLine(
            stringResource(KMR.strings.stats_vocabulary_script),
            vocabularyScriptLabel(word.script),
        )
        MetricLine(
            stringResource(KMR.strings.stats_vocabulary_category),
            vocabularyCategoryLabel(word.category),
        )
        if (word.excluded) {
            NoticeCard(stringResource(KMR.strings.stats_vocabulary_excluded_summary))
        }
        TextButton(
            onClick = {
                context.startActivity(
                    Intent(context, ProcessTextLookupActivity::class.java).apply {
                        action = Intent.ACTION_PROCESS_TEXT
                        putExtra(Intent.EXTRA_PROCESS_TEXT, word.headword)
                    },
                )
            },
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(KMR.strings.stats_mine_again))
        }
        word.matchConfidence?.let {
            Text(stringResource(KMR.strings.stats_match_confidence, matchConfidenceLabel(it)))
        }
        SectionTitle(stringResource(KMR.strings.stats_source_occurrences))
        SectionFrame(occurrences) { result ->
            if (result.value.items.isEmpty()) {
                EmptyState()
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    result.value.items.groupBy { it.displayTitle }.forEach { (title, titleRows) ->
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        titleRows.forEach { SourceOccurrenceRow(it) }
                    }
                    if (result.value.nextOffset != null) {
                        LoadMoreButton(onLoadMoreOccurrences)
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterCell(
    character: AnalyticsCharacterRow,
    mode: StatsCharacterGridMode,
    layout: StatsCharacterLayout,
    maximumOccurrenceCount: Long,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val hasGlyph = remember(character.rendered) {
        Paint(Paint.ANTI_ALIAS_FLAG).hasGlyph(character.rendered)
    }
    val displayText = characterDisplayText(
        rendered = character.rendered,
        codePoint = character.codePoint,
        hasGlyph = { hasGlyph },
    )
    val level = characterFrequencyLevel(
        occurrenceCount = character.occurrenceCount,
        maximumOccurrenceCount = maximumOccurrenceCount,
    )
    val modeValue = when (mode) {
        StatsCharacterGridMode.FREQUENCY -> stringResource(
            KMR.strings.stats_character_frequency_level,
            level,
            5,
        )
        StatsCharacterGridMode.FIRST_SEEN -> formatInstant(character.firstSeenAtEpochMillis)
        StatsCharacterGridMode.MATURITY -> maturityLabel(character.maturity)
        StatsCharacterGridMode.METADATA -> characterMetadataBand(character)
        StatsCharacterGridMode.PRIORITY -> formatDecimal(character.priorityScore)
    }
    val description = stringResource(
        KMR.strings.stats_character_cell_description,
        displayText,
        character.unicodeName
            ?: "U+%04X".format(Locale.ROOT, character.codePoint.value),
        pluralStringResource(
            KMR.plurals.stats_occurrence_count,
            character.occurrenceCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            formatCount(character.occurrenceCount),
        ),
        maturityLabel(character.maturity),
        modeValue,
    )
    val surfaceColor = if (mode == StatsCharacterGridMode.FREQUENCY && level > 0) {
        lerp(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.primaryContainer,
            level / 5f,
        )
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(
        modifier = Modifier
            .height(if (layout == StatsCharacterLayout.GRID) 112.dp else 76.dp)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = surfaceColor,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        if (layout == StatsCharacterLayout.GRID) {
            Column(
                modifier = Modifier.padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = onSelectedChange,
                )
                Text(
                    displayText,
                    style = if (hasGlyph) {
                        MaterialTheme.typography.headlineMedium
                    } else {
                        MaterialTheme.typography.labelMedium
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(modeValue, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(checked = selected, onCheckedChange = onSelectedChange)
                Text(
                    displayText,
                    modifier = Modifier.width(56.dp),
                    style = if (hasGlyph) {
                        MaterialTheme.typography.headlineSmall
                    } else {
                        MaterialTheme.typography.labelMedium
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        character.unicodeName
                            ?: "U+%04X".format(Locale.ROOT, character.codePoint.value),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        modeValue,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(formatCount(character.occurrenceCount), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun CharacterDetail(
    character: AnalyticsCharacterRow,
    priorityMode: AnalyticsCharacterPriorityMode,
    occurrences: StatsLoadable<AnalyticsResult<tachiyomi.domain.immersion.model.AnalyticsPage<AnalyticsSourceOccurrence>>>,
    containingWords: StatsLoadable<AnalyticsResult<tachiyomi.domain.immersion.model.AnalyticsPage<AnalyticsWordRow>>>,
    ankiItems: StatsLoadable<List<ImmersionAnkiItem>>,
    previous: AnalyticsCharacterRow?,
    next: AnalyticsCharacterRow?,
    onClose: () -> Unit,
    onSelect: (AnalyticsCharacterRow) -> Unit,
    onContainingWordSelect: (AnalyticsWordRow) -> Unit,
    onLoadMoreOccurrences: () -> Unit,
    onLoadMoreContainingWords: () -> Unit,
) {
    val context = LocalContext.current
    val hasGlyph = remember(character.rendered) {
        Paint(Paint.ANTI_ALIAS_FLAG).hasGlyph(character.rendered)
    }
    val displayText = characterDisplayText(
        rendered = character.rendered,
        codePoint = character.codePoint,
        hasGlyph = { hasGlyph },
    )
    DetailCard(stringResource(KMR.strings.stats_character_detail), onClose) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { previous?.let(onSelect) }, enabled = previous != null) {
                Icon(Icons.Outlined.ChevronLeft, contentDescription = stringResource(KMR.strings.stats_previous))
            }
            Text(
                displayText,
                style = if (hasGlyph) {
                    MaterialTheme.typography.displayMedium
                } else {
                    MaterialTheme.typography.titleLarge
                },
            )
            IconButton(onClick = { next?.let(onSelect) }, enabled = next != null) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = stringResource(KMR.strings.stats_next))
            }
        }
        Text(
            stringResource(
                KMR.strings.stats_unicode_code_point,
                "U+%04X".format(Locale.ROOT, character.codePoint.value),
            ),
        )
        character.unicodeName?.let { Text(stringResource(KMR.strings.stats_unicode_name, it)) }
        Text(stringResource(KMR.strings.stats_unicode_script, character.unicodeScript))
        Text(stringResource(KMR.strings.stats_unicode_category, character.unicodeCategory))
        character.japaneseReadings?.let {
            Text(stringResource(KMR.strings.stats_character_readings, it))
        }
        MetricLine(
            stringResource(KMR.strings.stats_character_gross_exposure),
            formatCount(character.occurrenceCount),
        )
        MetricLine(
            stringResource(KMR.strings.stats_source_units),
            formatCount(character.sourceUnitCount),
        )
        MetricLine(stringResource(KMR.strings.stats_unique_words), formatCount(character.wordCount))
        MetricLine(stringResource(KMR.strings.stats_tab_titles), formatCount(character.titleCount))
        Text(stringResource(KMR.strings.stats_first_seen, formatInstant(character.firstSeenAtEpochMillis)))
        Text(stringResource(KMR.strings.stats_last_seen, formatInstant(character.lastSeenAtEpochMillis)))
        Text(stringResource(KMR.strings.stats_maturity, maturityLabel(character.maturity)))
        MetricLine(
            stringResource(KMR.strings.stats_frequency_rank),
            character.frequencyRank?.let(::formatCount)
                ?: stringResource(KMR.strings.stats_unavailable),
        )
        character.jlptLevel?.let {
            MetricLine(stringResource(KMR.strings.stats_jlpt_level), "N$it")
        }
        character.gradeLevel?.let {
            MetricLine(stringResource(KMR.strings.stats_grade_level), formatCount(it.toLong()))
        }
        MetricLine(
            stringResource(KMR.strings.stats_character_priority_score),
            formatDecimal(character.priorityScore),
        )
        val priorityComponents = AnalyticsCharacterPriorityFormula.components(
            frequencyRank = character.frequencyRank,
            jlptLevel = character.jlptLevel,
            gradeLevel = character.gradeLevel,
        )
        Text(
            stringResource(
                KMR.strings.stats_character_priority_formula_version,
                AnalyticsCharacterPriorityFormula.VERSION,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MetricLine(
            stringResource(KMR.strings.stats_character_priority_frequency_component),
            formatDecimal(priorityComponents.frequency),
        )
        MetricLine(
            stringResource(KMR.strings.stats_character_priority_jlpt_component),
            formatDecimal(priorityComponents.jlpt),
        )
        MetricLine(
            stringResource(KMR.strings.stats_character_priority_grade_component),
            formatDecimal(priorityComponents.grade),
        )
        MetricLine(
            characterPriorityModeLabel(priorityMode),
            formatDecimal(priorityComponents.score(priorityMode)),
        )
        TextButton(
            onClick = {
                context.startActivity(
                    Intent(context, ProcessTextLookupActivity::class.java).apply {
                        action = Intent.ACTION_PROCESS_TEXT
                        putExtra(Intent.EXTRA_PROCESS_TEXT, character.rendered)
                    },
                )
            },
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(KMR.strings.stats_open_dictionary))
        }
        SectionTitle(stringResource(KMR.strings.stats_character_anki_matches))
        SectionFrame(ankiItems) { items ->
            if (items.isEmpty()) {
                if (character.maturity == MaturityTier.UNAVAILABLE) {
                    Text(stringResource(KMR.strings.stats_character_anki_unavailable))
                } else {
                    EmptyState()
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEachIndexed { index, item ->
                        CharacterAnkiItem(item)
                        if (index < items.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        SectionTitle(stringResource(KMR.strings.stats_containing_words, character.rendered))
        SectionFrame(containingWords) { result ->
            if (result.value.items.isEmpty()) {
                EmptyState()
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    result.value.items.forEach { word ->
                        WordRow(word) { onContainingWordSelect(word) }
                    }
                    if (result.value.nextOffset != null) {
                        LoadMoreButton(onLoadMoreContainingWords)
                    }
                }
            }
        }
        SectionTitle(stringResource(KMR.strings.stats_source_occurrences))
        SectionFrame(occurrences) { result ->
            if (result.value.items.isEmpty()) {
                EmptyState()
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    result.value.items.forEach { SourceOccurrenceRow(it) }
                    if (result.value.nextOffset != null) {
                        LoadMoreButton(onLoadMoreOccurrences)
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterAnkiItem(item: ImmersionAnkiItem) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(item.normalizedWord, fontWeight = FontWeight.SemiBold)
        if (item.normalizedReading.isNotBlank()) {
            Text(stringResource(KMR.strings.stats_reading, item.normalizedReading))
        }
        Text(stringResource(KMR.strings.stats_maturity, maturityLabel(item.maturityTier)))
        Text(
            stringResource(
                KMR.strings.stats_match_confidence,
                matchConfidenceLabel(item.matchConfidence),
            ),
        )
    }
}

@Composable
private fun SessionRow(session: ImmersionSession, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Schedule, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(formatInstant(session.startedAtEpochMillis), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(
                        KMR.strings.stats_media_and_language,
                        mediaLabel(session.mediaKind),
                        session.languageTag?.value ?: stringResource(KMR.strings.stats_unknown),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(formatDuration(session.activeDuration.value))
        }
    }
}

@Composable
private fun SessionDetail(
    fallback: ImmersionSession,
    detail: StatsLoadable<AnalyticsResult<AnalyticsSessionDetail?>>,
    deletionPreview: StatsLoadable<tachiyomi.domain.immersion.model.ImmersionDeletionPreview>,
    relinkPreview: StatsLoadable<ImmersionTitleMutationPreview>,
    titleOptions: List<AnalyticsTitleRow>,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onRelinkPreview: (TitleId) -> Unit,
    onRelinkPreviewClear: () -> Unit,
    onRelinkApply: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var correctTitle by remember(fallback.id) { mutableStateOf(false) }
    val session = detail.value?.value?.session ?: fallback
    DetailCard(stringResource(KMR.strings.stats_session_detail), onClose) {
        detail.value?.value?.displayTitle?.let {
            Text(it, style = MaterialTheme.typography.titleLarge)
        }
        Text(stringResource(KMR.strings.stats_started_at, formatInstant(session.startedAtEpochMillis)))
        Text(stringResource(KMR.strings.stats_session_status, sessionStatusLabel(session.status)))
        Text(stringResource(KMR.strings.stats_profile, session.profileId))
        MetricLine(stringResource(KMR.strings.stats_active_time), formatDuration(session.activeDuration.value))
        MetricLine(stringResource(KMR.strings.stats_elapsed_time), formatDuration(session.elapsedDuration.value))
        MetricLine(stringResource(KMR.strings.stats_basis_gross), formatCount(session.grossCharacters.value))
        MetricLine(stringResource(KMR.strings.stats_basis_unique), formatCount(session.uniqueSourceCharacters.value))
        MetricLine(stringResource(KMR.strings.stats_basis_net), formatCount(session.netCharacters.value))
        MetricLine(stringResource(KMR.strings.stats_source_units), formatCount(session.sourceUnitCount.value))
        TextButton(onClick = { confirmDelete = true }) {
            Text(stringResource(KMR.strings.stats_delete_session))
        }
        if (!session.legacyImport && session.status != SessionStatus.ACTIVE) {
            TextButton(onClick = { correctTitle = true }) {
                Text(stringResource(KMR.strings.stats_session_correct_title))
            }
        }
        if (session.legacyImport) {
            NoticeCard(stringResource(KMR.strings.stats_legacy_session_detail))
        }
        detail.value?.value?.let { value ->
            SectionTitle(stringResource(KMR.strings.stats_session_timeline))
            TimelineSummary(value)
            SectionTitle(stringResource(KMR.strings.stats_source_occurrences))
            if (value.sources.isEmpty()) {
                EmptyState()
            } else {
                value.sources.forEach { SourceOccurrenceRow(it) }
            }
        }
        if (detail.refreshing) Text(stringResource(KMR.strings.stats_loading_section))
        if (detail.error) SectionError()
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(KMR.strings.stats_delete_session)) },
            text = {
                when {
                    deletionPreview.value != null -> {
                        val preview = requireNotNull(deletionPreview.value)
                        Text(
                            stringResource(
                                KMR.strings.stats_delete_scoped_preview,
                                formatCount(preview.sessions),
                                formatDuration(preview.activeDurationMillis),
                                formatCount(preview.grossCharacters),
                                formatCount(preview.sourceUnits),
                                formatCount(preview.words),
                                formatCount(preview.characters),
                                formatCount(preview.goals),
                            ),
                        )
                    }
                    deletionPreview.error -> {
                        Text(stringResource(KMR.strings.stats_delete_preview_failed))
                    }
                    else -> {
                        Text(stringResource(KMR.strings.stats_loading_section))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = deletionPreview.value != null,
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(KMR.strings.stats_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(KMR.strings.stats_close))
                }
            },
        )
    }
    if (correctTitle) {
        SessionTitleCorrectionDialog(
            session = session,
            titleOptions = titleOptions,
            preview = relinkPreview,
            onDismiss = {
                correctTitle = false
                onRelinkPreviewClear()
            },
            onPreview = onRelinkPreview,
            onApply = onRelinkApply,
            onClearPreview = onRelinkPreviewClear,
        )
    }
}

@Composable
private fun SessionTitleCorrectionDialog(
    session: ImmersionSession,
    titleOptions: List<AnalyticsTitleRow>,
    preview: StatsLoadable<ImmersionTitleMutationPreview>,
    onDismiss: () -> Unit,
    onPreview: (TitleId) -> Unit,
    onApply: () -> Unit,
    onClearPreview: () -> Unit,
) {
    var query by remember(session.id) { mutableStateOf("") }
    var target by remember(session.id) { mutableStateOf<AnalyticsTitleRow?>(null) }
    val targets = remember(session, titleOptions, query) {
        sessionRelinkTargets(session, titleOptions, query, SESSION_RELINK_TARGET_LIMIT)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.stats_session_correct_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(KMR.strings.stats_session_correct_title_summary))
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        target = null
                        onClearPreview()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    label = { Text(stringResource(KMR.strings.stats_title_choose_target)) },
                )
                if (target == null) {
                    targets.forEach { option ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                target = option
                                query = option.displayTitle
                                onClearPreview()
                            },
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    option.displayTitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    option.sourceKey,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                when {
                    preview.value != null -> SessionRelinkPreview(preview.value)
                    preview.error -> Text(
                        stringResource(KMR.strings.stats_title_mutation_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    preview.refreshing -> Text(stringResource(KMR.strings.stats_loading_section))
                }
            }
        },
        confirmButton = {
            val exactPreview = preview.value
            Button(
                enabled = !preview.refreshing &&
                    if (exactPreview == null) target != null else exactPreview.canApply,
                onClick = {
                    if (exactPreview == null) {
                        target?.let { onPreview(it.titleId) }
                    } else {
                        onApply()
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (exactPreview == null) {
                            KMR.strings.stats_title_preview_change
                        } else {
                            KMR.strings.stats_title_apply_change
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(KMR.strings.stats_close))
            }
        },
    )
}

@Composable
private fun SessionRelinkPreview(preview: ImmersionTitleMutationPreview) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(KMR.strings.stats_title_mutation_preview),
            style = MaterialTheme.typography.labelLarge,
        )
        MetricLine(stringResource(KMR.strings.stats_sessions), formatCount(preview.sessions))
        MetricLine(stringResource(KMR.strings.stats_title_mutation_events), formatCount(preview.events))
        MetricLine(stringResource(KMR.strings.stats_source_units), formatCount(preview.sourceUnits))
        MetricLine(stringResource(KMR.strings.stats_lookups), formatCount(preview.lookups))
        MetricLine(
            stringResource(KMR.strings.stats_title_mutation_anki_operations),
            formatCount(preview.ankiOperations),
        )
        Text(
            stringResource(
                if (preview.canApply) {
                    KMR.strings.stats_title_mutation_ready
                } else {
                    KMR.strings.stats_title_mutation_blocked
                },
            ),
            color = if (preview.canApply) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        preview.blockers.forEach { blocker ->
            Text(titleMutationBlockerLabel(blocker), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TimelineSummary(detail: AnalyticsSessionDetail) {
    val max = detail.timeline.maxOfOrNull { it.grossCharacters }?.coerceAtLeast(1) ?: 1
    val summary = stringResource(
        KMR.strings.stats_timeline_summary,
        detail.timeline.size,
        formatCount(detail.timeline.sumOf { it.eventCount }),
    )
    val inactiveEventTypes = setOf(
        EventType.PAUSED,
        EventType.IDLE,
        EventType.BACKGROUNDED,
    )
    val metrics = stringResource(
        KMR.strings.stats_timeline_metrics,
        formatDuration(detail.timeline.sumOf { it.activeDurationMillis }),
        formatCount(detail.timeline.sumOf { it.lookupCount }),
        formatCount(detail.timeline.sumOf { it.cardsCreated + it.cardsUpdated }),
        formatCount(
            detail.timeline.count { bucket -> bucket.eventTypes.any(inactiveEventTypes::contains) }.toLong(),
        ),
    )
    val color = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(summary, style = MaterialTheme.typography.bodySmall)
        Text(
            metrics,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .semantics { contentDescription = "$summary $metrics" },
        ) {
            if (detail.timeline.isEmpty()) return@Canvas
            val width = size.width / detail.timeline.size
            detail.timeline.forEachIndexed { index, bucket ->
                if (bucket.grossCharacters <= 0) return@forEachIndexed
                val height = size.height * bucket.grossCharacters.toFloat() / max.toFloat()
                drawRect(
                    color = color,
                    topLeft = Offset(index * width, size.height - height),
                    size = Size((width - 1.dp.toPx()).coerceAtLeast(1f), height),
                )
            }
        }
    }
}

@Composable
private fun SourceOccurrenceRow(occurrence: AnalyticsSourceOccurrence) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        SourceOccurrenceContent(
            occurrence = occurrence,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun SourceOccurrenceContent(
    occurrence: AnalyticsSourceOccurrence,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unavailableMessage = stringResource(KMR.strings.stats_source_open_unavailable)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(occurrence.displayTitle, fontWeight = FontWeight.SemiBold)
        occurrence.excerpt?.let {
            Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis)
        } ?: Text(
            stringResource(KMR.strings.stats_source_text_unavailable),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(
                KMR.strings.stats_source_meta,
                sourceKindLabel(occurrence.sourceKind),
                formatInstant(occurrence.occurredAtEpochMillis),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    scope.launch {
                        if (!StatsSourceNavigator.open(context, occurrence)) {
                            context.toast(unavailableMessage)
                        }
                    }
                },
            ) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(KMR.strings.stats_open_source))
            }
            if (occurrence.rawTextAvailable && !occurrence.excerpt.isNullOrBlank()) {
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(context, ProcessTextLookupActivity::class.java).apply {
                                action = Intent.ACTION_PROCESS_TEXT
                                putExtra(Intent.EXTRA_PROCESS_TEXT, occurrence.excerpt)
                            },
                        )
                    },
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(KMR.strings.stats_mine_again))
                }
            }
        }
    }
}

@Composable
private fun GoalCard(
    goal: AnalyticsGoalProgress,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onCheckIn: () -> Unit,
) {
    val progress = if (goal.targetToDate > 0) {
        (goal.achieved / goal.targetToDate).coerceIn(0.0, 1.0)
    } else {
        0.0
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(goalTypeLabel(goal.goal.type), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    KMR.strings.stats_goal_progress,
                    formatGoalValue(goal.goal.metric, goal.achieved),
                    formatGoalValue(goal.goal.metric, goal.targetToDate),
                ),
            )
            LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
            goal.pacePerDay?.let {
                Text(stringResource(KMR.strings.stats_goal_pace, formatGoalValue(goal.goal.metric, it)))
            }
            when (statsGoalForecastPresentation(goal)) {
                StatsGoalForecastPresentation.AVAILABLE -> {
                    Text(
                        stringResource(
                            KMR.strings.stats_goal_projection,
                            formatLocalDate(requireNotNull(goal.projectedCompletionDate)),
                        ),
                    )
                }
                StatsGoalForecastPresentation.PARTIAL -> {
                    NoticeCard(stringResource(KMR.strings.stats_goal_forecast_partial))
                }
                StatsGoalForecastPresentation.UNAVAILABLE -> {
                    NoticeCard(stringResource(KMR.strings.stats_goal_forecast_unavailable))
                }
                StatsGoalForecastPresentation.STALE -> {
                    NoticeCard(stringResource(KMR.strings.stats_goal_forecast_stale))
                }
                StatsGoalForecastPresentation.NONE -> Unit
            }
            goal.requiredPacePerActiveDay?.let {
                Text(
                    stringResource(
                        KMR.strings.stats_goal_required_pace,
                        formatGoalValue(goal.goal.metric, it),
                        goal.remainingActiveDays ?: 0,
                    ),
                )
            }
            goal.rollingSevenDayPace?.let {
                Text(
                    stringResource(
                        KMR.strings.stats_goal_rolling_seven,
                        formatGoalValue(goal.goal.metric, it),
                    ),
                )
            }
            goal.rollingThirtyDayPace?.let {
                Text(
                    stringResource(
                        KMR.strings.stats_goal_rolling_thirty,
                        formatGoalValue(goal.goal.metric, it),
                    ),
                )
            }
            if (statsGoalForecastPresentation(goal) != StatsGoalForecastPresentation.NONE) {
                Text(
                    stringResource(
                        KMR.strings.stats_goal_forecast_assumptions,
                        goal.forecastWindowDays,
                        goal.forecastSampleDays,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(
                    KMR.strings.stats_goal_streaks,
                    goal.currentStreakDays,
                    goal.longestStreakDays,
                ),
            )
            if (goal.isRestDay) {
                NoticeCard(stringResource(KMR.strings.stats_goal_rest_day))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (goal.goal.type == "MANUAL" || goal.goal.metric == "manual") {
                    TextButton(onClick = onCheckIn) {
                        Text(stringResource(KMR.strings.stats_goal_check_in))
                    }
                }
                TextButton(onClick = onEdit) {
                    Text(stringResource(KMR.strings.stats_goal_edit))
                }
                TextButton(onClick = onArchive) {
                    Text(stringResource(KMR.strings.stats_goal_archive))
                }
            }
        }
    }
}

@Composable
private fun GoalEditorDialog(
    original: ImmersionGoal?,
    hasTitleScope: Boolean,
    onDismiss: () -> Unit,
    onSave: (StatsGoalEditorValues) -> Boolean,
) {
    val metrics = listOf(
        ACTIVE_TIME_GOAL_METRIC,
        "gross_characters",
        "unique_source_characters",
        "net_characters",
        SOURCE_UNITS_GOAL_METRIC,
        "sessions",
        "lookups",
        "cards",
        "new_words",
        "new_characters",
    )
    val today = remember { ImmersionLocalDate.from(LocalDate.now()) }
    val initial = remember(original?.id, original?.updatedAtEpochMillis) {
        original?.toStatsGoalEditorValues(today)
    }
    var kind by remember(original?.id, original?.updatedAtEpochMillis) {
        mutableStateOf(initial?.kind ?: StatsGoalKind.DAILY)
    }
    var metric by remember(original?.id, original?.updatedAtEpochMillis) {
        mutableStateOf(initial?.metric?.takeIf { it in metrics } ?: metrics.first())
    }
    var target by remember(original?.id, original?.updatedAtEpochMillis) {
        mutableStateOf(initial?.inputTarget?.toString().orEmpty())
    }
    var startDate by remember(original?.id, original?.updatedAtEpochMillis) {
        mutableStateOf((initial?.startDate ?: today).toString())
    }
    var endDate by remember(original?.id, original?.updatedAtEpochMillis) {
        mutableStateOf(
            initial?.endDate?.toString()
                ?: LocalDate.now().plusDays(DEFAULT_GOAL_WINDOW_DAYS).toString(),
        )
    }
    var weekdayMultipliers by remember(original?.id, original?.updatedAtEpochMillis) {
        mutableStateOf(
            initial?.weekdayMultipliers ?: suggestedStatsGoalWeekdayMultipliers(),
        )
    }
    var editMode by remember(original?.id, original?.updatedAtEpochMillis) {
        mutableStateOf(StatsGoalEditMode.PROSPECTIVE)
    }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (original == null) {
                        KMR.strings.stats_goal_create
                    } else {
                        KMR.strings.stats_goal_edit
                    },
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterMenuChip(
                    label = goalKindLabel(kind),
                    options = StatsGoalKind.entries,
                    optionLabel = { goalKindLabel(it) },
                    onSelect = {
                        kind = it
                        invalid = false
                    },
                )
                if (kind !in setOf(StatsGoalKind.FINISH_TITLE_BY_DATE, StatsGoalKind.MANUAL)) {
                    FilterMenuChip(
                        label = goalMetricLabel(metric),
                        options = metrics,
                        optionLabel = { goalMetricLabel(it) },
                        onSelect = { metric = it },
                    )
                } else {
                    Text(
                        goalMetricLabel(
                            if (kind == StatsGoalKind.MANUAL) "manual" else SOURCE_UNITS_GOAL_METRIC,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (kind != StatsGoalKind.MANUAL) {
                    OutlinedTextField(
                        value = target,
                        onValueChange = {
                            target = it
                            invalid = false
                        },
                        label = {
                            Text(
                                if (
                                    kind != StatsGoalKind.FINISH_TITLE_BY_DATE &&
                                    kind != StatsGoalKind.MANUAL &&
                                    metric == ACTIVE_TIME_GOAL_METRIC
                                ) {
                                    stringResource(KMR.strings.stats_goal_target_minutes)
                                } else {
                                    stringResource(KMR.strings.stats_goal_target)
                                },
                            )
                        },
                        isError = invalid,
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = startDate,
                    onValueChange = {
                        startDate = it
                        invalid = false
                    },
                    label = { Text(stringResource(KMR.strings.stats_start_date)) },
                    supportingText = {
                        Text(stringResource(KMR.strings.stats_custom_range_hint))
                    },
                    isError = invalid,
                    singleLine = true,
                )
                if (kind in setOf(StatsGoalKind.DATE_BOUND_TOTAL, StatsGoalKind.FINISH_TITLE_BY_DATE)) {
                    OutlinedTextField(
                        value = endDate,
                        onValueChange = {
                            endDate = it
                            invalid = false
                        },
                        label = { Text(stringResource(KMR.strings.stats_end_date)) },
                        supportingText = {
                            Text(stringResource(KMR.strings.stats_custom_range_hint))
                        },
                        isError = invalid,
                        singleLine = true,
                    )
                }
                Text(
                    stringResource(KMR.strings.stats_goal_weekday_targets),
                    style = MaterialTheme.typography.labelLarge,
                )
                DayOfWeek.entries.forEach { day ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            day.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                            modifier = Modifier.weight(1f),
                        )
                        FilterMenuChip(
                            label = goalMultiplierLabel(weekdayMultipliers.getValue(day)),
                            options = GOAL_MULTIPLIER_OPTIONS,
                            optionLabel = { goalMultiplierLabel(it) },
                            onSelect = { multiplier ->
                                weekdayMultipliers = weekdayMultipliers + (day to multiplier)
                                invalid = false
                            },
                        )
                    }
                }
                Text(
                    stringResource(KMR.strings.stats_goal_timezone_policy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (original != null) {
                    Text(
                        stringResource(KMR.strings.stats_goal_edit_mode),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    FilterMenuChip(
                        label = goalEditModeLabel(editMode),
                        options = StatsGoalEditMode.entries,
                        optionLabel = { goalEditModeLabel(it) },
                        onSelect = { editMode = it },
                    )
                    Text(
                        stringResource(
                            if (editMode == StatsGoalEditMode.RESTART_HISTORY) {
                                KMR.strings.stats_goal_edit_restart_summary
                            } else {
                                KMR.strings.stats_goal_edit_prospective
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (kind == StatsGoalKind.FINISH_TITLE_BY_DATE && !hasTitleScope) {
                    Text(
                        stringResource(KMR.strings.stats_goal_title_required),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (invalid) {
                    Text(
                        stringResource(KMR.strings.stats_goal_invalid),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedStart = runCatching {
                        ImmersionLocalDate.from(LocalDate.parse(startDate.trim()))
                    }.getOrNull()
                    val parsedEnd = if (
                        kind in setOf(
                            StatsGoalKind.DATE_BOUND_TOTAL,
                            StatsGoalKind.FINISH_TITLE_BY_DATE,
                        )
                    ) {
                        runCatching {
                            ImmersionLocalDate.from(LocalDate.parse(endDate.trim()))
                        }.getOrNull()
                    } else {
                        null
                    }
                    val values = parsedStart?.let {
                        StatsGoalEditorValues(
                            kind = kind,
                            metric = when (kind) {
                                StatsGoalKind.FINISH_TITLE_BY_DATE -> SOURCE_UNITS_GOAL_METRIC
                                StatsGoalKind.MANUAL -> "manual"
                                else -> metric
                            },
                            inputTarget = if (kind == StatsGoalKind.MANUAL) {
                                1.0
                            } else {
                                target.toDoubleOrNull() ?: Double.NaN
                            },
                            startDate = it,
                            endDate = parsedEnd,
                            weekdayMultipliers = weekdayMultipliers,
                            editMode = editMode,
                        )
                    }
                    invalid = values == null ||
                        (kind == StatsGoalKind.FINISH_TITLE_BY_DATE && !hasTitleScope) ||
                        !onSave(values)
                },
            ) {
                Text(stringResource(KMR.strings.stats_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(KMR.strings.stats_close))
            }
        },
    )
}

@Composable
private fun GoalCheckInDialog(
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.stats_goal_check_in)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(MAX_GOAL_CHECK_IN_NOTE_LENGTH) },
                    label = { Text(stringResource(KMR.strings.stats_goal_check_in_note)) },
                    supportingText = {
                        Text(
                            stringResource(
                                KMR.strings.stats_goal_check_in_note_count,
                                note.length,
                                MAX_GOAL_CHECK_IN_NOTE_LENGTH,
                            ),
                        )
                    },
                    minLines = 2,
                    maxLines = 4,
                )
                Text(
                    stringResource(KMR.strings.stats_goal_check_in_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(note.trim().takeIf(String::isNotEmpty)) }) {
                Text(stringResource(KMR.strings.stats_goal_check_in))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(KMR.strings.stats_close))
            }
        },
    )
}

@Composable
private fun <T> SearchAndSort(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    selectedSort: T,
    onSortSelect: (T) -> Unit,
    allowedSorts: List<T>,
    optionLabel: @Composable (T) -> String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            placeholder = { Text(placeholder) },
        )
        FilterMenuChip(
            label = optionLabel(selectedSort),
            options = allowedSorts,
            optionLabel = optionLabel,
            onSelect = onSortSelect,
        )
    }
}

@Composable
private fun <T> FilterMenuChip(
    label: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = false,
            onClick = { expanded = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun <T> MultiSelectFilterMenuChip(
    label: String,
    selected: Set<T>,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelectionChange: (Set<T>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var pendingSelection by remember { mutableStateOf(selected) }

    fun applyAndDismiss() {
        expanded = false
        if (pendingSelection != selected) {
            onSelectionChange(pendingSelection)
        }
    }

    Box {
        FilterChip(
            selected = selected.isNotEmpty(),
            onClick = {
                pendingSelection = selected
                expanded = true
            },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = ::applyAndDismiss) {
            DropdownMenuItem(
                text = { Text(stringResource(KMR.strings.stats_filter_all)) },
                leadingIcon = {
                    Checkbox(
                        checked = pendingSelection.isEmpty(),
                        onCheckedChange = null,
                    )
                },
                onClick = { pendingSelection = emptySet() },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    leadingIcon = {
                        Checkbox(
                            checked = option in pendingSelection,
                            onCheckedChange = null,
                        )
                    },
                    onClick = {
                        pendingSelection = toggleStatsFilterSelection(
                            selected = pendingSelection,
                            option = option,
                            options = options,
                        )
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(KMR.strings.stats_apply)) },
                onClick = ::applyAndDismiss,
            )
        }
    }
}

@Composable
private fun <T> filterSelectionLabel(
    selected: Set<T>,
    optionLabel: @Composable (T) -> String,
): String =
    when (val summary = selected.statsFilterSelectionSummary()) {
        StatsFilterSelectionSummary.All -> stringResource(KMR.strings.stats_filter_all)
        is StatsFilterSelectionSummary.Single -> optionLabel(summary.value)
        is StatsFilterSelectionSummary.Multiple -> stringResource(
            KMR.strings.stats_filter_selected_count,
            summary.count,
        )
    }

@Composable
private fun ToggleRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun <T> SectionFrame(
    section: StatsLoadable<T>,
    onRetry: (() -> Unit)? = null,
    content: @Composable (T) -> Unit,
) {
    when {
        section.value != null -> {
            content(section.value)
            if (section.refreshing) SectionLoading()
            if (section.error) SectionError(onRetry)
        }
        section.refreshing -> SectionLoading()
        section.error -> SectionError(onRetry)
        else -> EmptyState()
    }
}

@Composable
private fun SectionLoading() {
    Text(
        text = stringResource(KMR.strings.stats_loading_section),
        modifier = Modifier.padding(16.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionError(onRetry: (() -> Unit)? = null) {
    if (onRetry == null) {
        NoticeCard(stringResource(KMR.strings.stats_section_failed))
        return
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(KMR.strings.stats_section_failed),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            TextButton(onClick = onRetry) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(KMR.strings.stats_retry_section))
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Text(
        text = stringResource(KMR.strings.stats_no_data),
        modifier = Modifier.padding(16.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NoticeCard(text: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(14.dp),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun DetailCard(
    title: String,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(KMR.strings.stats_close_detail),
                    )
                }
            }
            content()
        }
    }
}

private data class DashboardMetric(
    val value: String,
    val label: String,
    val icon: ImageVector,
    val destination: StatsTab,
)

@Composable
private fun MetricCard(
    data: DashboardMetric,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)?,
) {
    Surface(
        modifier = modifier
            .height(116.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            ),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(data.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(
                    data.value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    data.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun InventoryCoverageCard(unique: Long?, quality: AnalyticsDataQuality) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MetricLine(
                stringResource(KMR.strings.stats_unique_words),
                unique?.let(::formatCount) ?: stringResource(KMR.strings.stats_unavailable),
            )
            MetricLine(
                stringResource(KMR.strings.stats_indexing_coverage),
                quality.indexingCompletion?.let(::formatPercent)
                    ?: stringResource(KMR.strings.stats_unavailable),
            )
            MetricLine(stringResource(KMR.strings.stats_anki_state), capabilityLabel(quality.ankiState))
        }
    }
}

@Composable
private fun CoverageLine(label: String, known: Long, encountered: Long) {
    val ratio = if (encountered == 0L) null else known.toDouble() / encountered
    MetricLine(
        label,
        if (ratio == null) {
            stringResource(KMR.strings.stats_unavailable)
        } else {
            stringResource(
                KMR.strings.stats_coverage_value,
                formatCount(known),
                formatCount(encountered),
                formatPercent(ratio),
            )
        },
    )
}

@Composable
private fun LoadMoreButton(onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Button(onClick = onClick) {
            Text(stringResource(KMR.strings.stats_load_more))
        }
    }
}

@Composable
private fun CustomRangeDialog(
    initialStart: String,
    initialEnd: String,
    onDismiss: () -> Unit,
    onApply: (String, String) -> Boolean,
) {
    var start by remember(initialStart) { mutableStateOf(initialStart) }
    var end by remember(initialEnd) { mutableStateOf(initialEnd) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.stats_custom_range)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(KMR.strings.stats_custom_range_hint))
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text(stringResource(KMR.strings.stats_start_date)) },
                    singleLine = true,
                    isError = invalid,
                )
                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it },
                    label = { Text(stringResource(KMR.strings.stats_end_date)) },
                    singleLine = true,
                    isError = invalid,
                )
                if (invalid) {
                    Text(
                        stringResource(KMR.strings.stats_invalid_date_range),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { invalid = !onApply(start, end) }) {
                Text(stringResource(KMR.strings.stats_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(KMR.strings.stats_close))
            }
        },
    )
}

@Composable
private fun rangeLabel(value: StatsRangePreset): String = when (value) {
    StatsRangePreset.TODAY -> stringResource(KMR.strings.stats_range_today)
    StatsRangePreset.SEVEN_DAYS -> stringResource(KMR.strings.stats_range_seven_days)
    StatsRangePreset.THIRTY_DAYS -> stringResource(KMR.strings.stats_range_thirty_days)
    StatsRangePreset.NINETY_DAYS -> stringResource(KMR.strings.stats_range_ninety_days)
    StatsRangePreset.YEAR -> stringResource(KMR.strings.stats_range_year)
    StatsRangePreset.ALL -> stringResource(KMR.strings.stats_range_all)
    StatsRangePreset.CUSTOM -> stringResource(KMR.strings.stats_range_custom)
}

@Composable
private fun mediaLabel(value: MediaKind?): String = when (value) {
    null -> stringResource(KMR.strings.stats_media_all)
    MediaKind.NOVEL -> stringResource(KMR.strings.stats_media_novel)
    MediaKind.MANGA -> stringResource(KMR.strings.stats_media_manga)
    MediaKind.VIDEO -> stringResource(KMR.strings.stats_media_video)
}

@Composable
private fun characterMetricLabel(value: CharacterMetric): String = when (value) {
    CharacterMetric.GROSS -> stringResource(KMR.strings.stats_basis_gross)
    CharacterMetric.UNIQUE_SOURCE -> stringResource(KMR.strings.stats_basis_unique)
    CharacterMetric.NET_PROGRESS -> stringResource(KMR.strings.stats_basis_net)
}

@Composable
private fun tabLabel(value: StatsTab): String = when (value) {
    StatsTab.OVERVIEW -> stringResource(KMR.strings.stats_tab_overview)
    StatsTab.ACTIVITY -> stringResource(KMR.strings.stats_tab_activity)
    StatsTab.TITLES -> stringResource(KMR.strings.stats_tab_titles)
    StatsTab.VOCABULARY -> stringResource(KMR.strings.stats_tab_vocabulary)
    StatsTab.CHARACTERS -> stringResource(KMR.strings.stats_tab_characters)
    StatsTab.SESSIONS -> stringResource(KMR.strings.stats_tab_sessions)
    StatsTab.GOALS -> stringResource(KMR.strings.stats_tab_goals)
    StatsTab.ANKI -> stringResource(KMR.strings.stats_tab_anki)
}

@Composable
private fun bucketLabel(value: AnalyticsBucketScale): String = when (value) {
    AnalyticsBucketScale.DAY -> stringResource(KMR.strings.stats_daily)
    AnalyticsBucketScale.WEEK -> stringResource(KMR.strings.stats_weekly)
    AnalyticsBucketScale.MONTH -> stringResource(KMR.strings.stats_monthly)
}

@Composable
private fun titleAcquisitionBucketLabel(value: AnalyticsTitleAcquisitionBucketSize): String =
    pluralStringResource(
        KMR.plurals.stats_character_count,
        value.characters.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        formatCount(value.characters),
    )

@Composable
private fun sortLabel(value: AnalyticsSort): String = when (value) {
    AnalyticsSort.MOST_RECENT -> stringResource(KMR.strings.stats_sort_recent)
    AnalyticsSort.MOST_TIME -> stringResource(KMR.strings.stats_sort_time)
    AnalyticsSort.MOST_CHARACTERS -> stringResource(KMR.strings.stats_sort_characters)
    AnalyticsSort.MOST_OCCURRENCES -> stringResource(KMR.strings.stats_sort_occurrences)
    AnalyticsSort.FIRST_SEEN -> stringResource(KMR.strings.stats_sort_first_seen)
    AnalyticsSort.ALPHABETICAL -> stringResource(KMR.strings.stats_sort_alphabetical)
    AnalyticsSort.FREQUENCY_RANK -> stringResource(KMR.strings.stats_sort_frequency)
    AnalyticsSort.PRIORITY -> stringResource(KMR.strings.stats_sort_priority)
}

@Composable
private fun characterScriptLabel(value: AnalyticsCharacterScript): String = when (value) {
    AnalyticsCharacterScript.HAN -> stringResource(KMR.strings.stats_character_script_han)
    AnalyticsCharacterScript.HIRAGANA ->
        stringResource(KMR.strings.stats_character_script_hiragana)
    AnalyticsCharacterScript.KATAKANA ->
        stringResource(KMR.strings.stats_character_script_katakana)
    AnalyticsCharacterScript.HANGUL -> stringResource(KMR.strings.stats_character_script_hangul)
    AnalyticsCharacterScript.LATIN -> stringResource(KMR.strings.stats_character_script_latin)
    AnalyticsCharacterScript.OTHER -> stringResource(KMR.strings.stats_character_script_other)
}

@Composable
private fun characterRangeLabel(value: AnalyticsCharacterRange): String = when (value) {
    AnalyticsCharacterRange.ENCOUNTERED ->
        stringResource(KMR.strings.stats_character_range_encountered)
    AnalyticsCharacterRange.FIRST_SEEN_IN_RANGE ->
        stringResource(KMR.strings.stats_character_range_new_in_range)
    AnalyticsCharacterRange.UNKNOWN ->
        stringResource(KMR.strings.stats_character_range_unknown)
    AnalyticsCharacterRange.NEW -> stringResource(KMR.strings.stats_character_range_new)
    AnalyticsCharacterRange.LEARNING ->
        stringResource(KMR.strings.stats_character_range_learning)
    AnalyticsCharacterRange.YOUNG -> stringResource(KMR.strings.stats_character_range_young)
    AnalyticsCharacterRange.MATURE -> stringResource(KMR.strings.stats_character_range_mature)
    AnalyticsCharacterRange.MISSING_HIGH_FREQUENCY ->
        stringResource(KMR.strings.stats_character_range_missing)
}

@Composable
private fun characterPriorityModeLabel(value: AnalyticsCharacterPriorityMode): String =
    when (value) {
        AnalyticsCharacterPriorityMode.FREQUENCY ->
            stringResource(KMR.strings.stats_character_priority_frequency)
        AnalyticsCharacterPriorityMode.JLPT ->
            stringResource(KMR.strings.stats_character_priority_jlpt)
        AnalyticsCharacterPriorityMode.GRADE ->
            stringResource(KMR.strings.stats_character_priority_grade)
        AnalyticsCharacterPriorityMode.MIXED ->
            stringResource(KMR.strings.stats_character_priority_mixed)
    }

@Composable
private fun characterGridModeLabel(value: StatsCharacterGridMode): String = when (value) {
    StatsCharacterGridMode.FREQUENCY ->
        stringResource(KMR.strings.stats_character_mode_frequency)
    StatsCharacterGridMode.FIRST_SEEN ->
        stringResource(KMR.strings.stats_character_mode_first_seen)
    StatsCharacterGridMode.MATURITY ->
        stringResource(KMR.strings.stats_character_mode_maturity)
    StatsCharacterGridMode.METADATA ->
        stringResource(KMR.strings.stats_character_mode_metadata)
    StatsCharacterGridMode.PRIORITY ->
        stringResource(KMR.strings.stats_character_mode_priority)
}

@Composable
private fun characterLayoutLabel(value: StatsCharacterLayout): String = when (value) {
    StatsCharacterLayout.GRID -> stringResource(KMR.strings.stats_character_layout_grid)
    StatsCharacterLayout.LIST -> stringResource(KMR.strings.stats_character_layout_list)
}

@Composable
private fun characterMetadataBand(character: AnalyticsCharacterRow): String {
    val frequency = character.frequencyRank?.let(::formatCount)
        ?: stringResource(KMR.strings.stats_unavailable)
    val jlpt = character.jlptLevel?.let { "N$it" }
        ?: stringResource(KMR.strings.stats_unavailable)
    val grade = character.gradeLevel?.let { formatCount(it.toLong()) }
        ?: stringResource(KMR.strings.stats_unavailable)
    return if (
        character.frequencyRank == null &&
        character.jlptLevel == null &&
        character.gradeLevel == null
    ) {
        stringResource(KMR.strings.stats_character_metadata_unavailable)
    } else {
        stringResource(KMR.strings.stats_character_metadata_band, frequency, jlpt, grade)
    }
}

@Composable
private fun titleSortLabel(value: AnalyticsTitleSort): String = when (value) {
    AnalyticsTitleSort.MOST_RECENT -> stringResource(KMR.strings.stats_sort_recent)
    AnalyticsTitleSort.ALPHABETICAL -> stringResource(KMR.strings.stats_sort_alphabetical)
    AnalyticsTitleSort.MOST_TIME -> stringResource(KMR.strings.stats_sort_time)
    AnalyticsTitleSort.MOST_CHARACTERS -> stringResource(KMR.strings.stats_sort_characters)
    AnalyticsTitleSort.READING_SPEED -> stringResource(KMR.strings.stats_sort_speed)
    AnalyticsTitleSort.NOVELTY -> stringResource(KMR.strings.stats_sort_novelty)
    AnalyticsTitleSort.MINING_RATE -> stringResource(KMR.strings.stats_sort_mining)
    AnalyticsTitleSort.PROGRESS -> stringResource(KMR.strings.stats_sort_progress)
}

@Composable
private fun titleStateFilterLabel(value: AnalyticsTitleStateFilter): String = when (value) {
    AnalyticsTitleStateFilter.ALL -> stringResource(KMR.strings.stats_filter_all)
    AnalyticsTitleStateFilter.COMPLETED -> stringResource(KMR.strings.stats_title_completed)
    AnalyticsTitleStateFilter.IN_PROGRESS -> stringResource(KMR.strings.stats_title_in_progress)
    AnalyticsTitleStateFilter.UNKNOWN -> stringResource(KMR.strings.stats_title_state_unknown)
}

@Composable
private fun titleCoverageFilterLabel(value: AnalyticsTitleCoverageFilter): String = when (value) {
    AnalyticsTitleCoverageFilter.ALL -> stringResource(KMR.strings.stats_filter_all)
    AnalyticsTitleCoverageFilter.COMPLETE ->
        stringResource(KMR.strings.stats_title_coverage_complete)
    AnalyticsTitleCoverageFilter.PARTIAL ->
        stringResource(KMR.strings.stats_title_coverage_partial)
    AnalyticsTitleCoverageFilter.MISSING ->
        stringResource(KMR.strings.stats_title_coverage_missing)
}

@Composable
private fun vocabularyKnownnessLabel(value: VocabularyKnownness): String = when (value) {
    VocabularyKnownness.ALL -> stringResource(KMR.strings.stats_filter_all)
    VocabularyKnownness.UNKNOWN -> stringResource(KMR.strings.stats_vocabulary_unknown)
    VocabularyKnownness.KNOWN -> stringResource(KMR.strings.stats_vocabulary_known)
}

@Composable
private fun wordKnownnessLabel(value: MaturityTier): String = when (value) {
    MaturityTier.UNKNOWN -> stringResource(KMR.strings.stats_vocabulary_unknown)
    MaturityTier.UNAVAILABLE -> stringResource(KMR.strings.stats_unavailable)
    else -> stringResource(KMR.strings.stats_vocabulary_known)
}

@Composable
private fun vocabularyScriptLabel(value: VocabularyScript): String = when (value) {
    VocabularyScript.KANJI -> stringResource(KMR.strings.stats_script_kanji)
    VocabularyScript.KANA -> stringResource(KMR.strings.stats_script_kana)
    VocabularyScript.LATIN -> stringResource(KMR.strings.stats_script_latin)
    VocabularyScript.OTHER -> stringResource(KMR.strings.stats_script_other)
}

@Composable
private fun vocabularyCategoryLabel(value: VocabularyCategory): String = when (value) {
    VocabularyCategory.NAME -> stringResource(KMR.strings.stats_category_names)
    VocabularyCategory.KANA_ONLY -> stringResource(KMR.strings.stats_category_kana_only)
    VocabularyCategory.GRAMMAR -> stringResource(KMR.strings.stats_category_grammar)
    VocabularyCategory.OTHER -> stringResource(KMR.strings.stats_category_other)
}

@Composable
private fun vocabularyExclusionLabel(value: VocabularyExclusion): String = when (value) {
    VocabularyExclusion.INCLUDED -> stringResource(KMR.strings.stats_vocabulary_included)
    VocabularyExclusion.EXCLUDED -> stringResource(KMR.strings.stats_vocabulary_excluded)
    VocabularyExclusion.ALL -> stringResource(KMR.strings.stats_filter_all)
}

@Composable
private fun goalMetricLabel(value: String): String = when (value) {
    "active_time_ms" -> stringResource(KMR.strings.stats_active_time)
    "gross_characters" -> stringResource(KMR.strings.stats_basis_gross)
    "unique_source_characters" -> stringResource(KMR.strings.stats_basis_unique)
    "net_characters" -> stringResource(KMR.strings.stats_basis_net)
    SOURCE_UNITS_GOAL_METRIC -> stringResource(KMR.strings.stats_source_units)
    "sessions" -> stringResource(KMR.strings.stats_sessions)
    "lookups" -> stringResource(KMR.strings.stats_lookups)
    "cards" -> stringResource(KMR.strings.stats_cards_created)
    "new_words" -> stringResource(KMR.strings.stats_new_words)
    "new_characters" -> stringResource(KMR.strings.stats_new_characters)
    "manual" -> stringResource(KMR.strings.stats_goal_manual)
    else -> stringResource(KMR.strings.stats_unknown)
}

@Composable
private fun goalKindLabel(value: StatsGoalKind): String = when (value) {
    StatsGoalKind.DAILY -> stringResource(KMR.strings.stats_goal_type_daily)
    StatsGoalKind.DATE_BOUND_TOTAL -> stringResource(KMR.strings.stats_goal_type_date_bound)
    StatsGoalKind.FINISH_TITLE_BY_DATE ->
        stringResource(KMR.strings.stats_goal_type_finish_title)
    StatsGoalKind.MANUAL -> stringResource(KMR.strings.stats_goal_manual)
}

@Composable
private fun goalEditModeLabel(value: StatsGoalEditMode): String = when (value) {
    StatsGoalEditMode.PROSPECTIVE ->
        stringResource(KMR.strings.stats_goal_edit_mode_prospective)
    StatsGoalEditMode.RESTART_HISTORY ->
        stringResource(KMR.strings.stats_goal_edit_mode_restart)
}

@Composable
private fun goalMultiplierLabel(value: Double): String =
    if (value == 0.0) {
        stringResource(KMR.strings.stats_goal_rest)
    } else {
        stringResource(KMR.strings.stats_percent, (value * 100).roundToInt())
    }

@Composable
private fun goalTypeLabel(value: String): String = when (value) {
    "PERPETUAL_DAILY" -> stringResource(KMR.strings.stats_goal_type_daily)
    "DATE_BOUND_TOTAL" -> stringResource(KMR.strings.stats_goal_type_total)
    "FINISH_TITLE_BY_DATE" -> stringResource(KMR.strings.stats_goal_type_finish_title)
    "TOTAL" -> stringResource(KMR.strings.stats_goal_type_lifetime)
    "MANUAL" -> stringResource(KMR.strings.stats_goal_manual)
    else -> stringResource(KMR.strings.stats_goal_type_generic)
}

@Composable
private fun formatGoalValue(metric: String, value: Double): String {
    val displayValue = statsGoalDisplayValue(metric, value)
    return when (displayValue.kind) {
        StatsGoalDisplayKind.DURATION -> formatDuration(displayValue.durationMillis())
        StatsGoalDisplayKind.COUNT -> formatDecimal(displayValue.value)
    }
}

@Composable
private fun capabilityLabel(value: CapabilityState): String = when (value) {
    CapabilityState.AVAILABLE -> stringResource(KMR.strings.stats_available)
    CapabilityState.PARTIAL -> stringResource(KMR.strings.stats_partial)
    CapabilityState.UNAVAILABLE -> stringResource(KMR.strings.stats_unavailable)
    CapabilityState.STALE -> stringResource(KMR.strings.stats_stale)
}

@Composable
private fun ankiReportLabel(value: AnalyticsAnkiReport): String = when (value) {
    AnalyticsAnkiReport.INVENTORY -> stringResource(KMR.strings.stats_anki_report_inventory)
    AnalyticsAnkiReport.CARD_ACTIVITY -> stringResource(KMR.strings.stats_anki_report_card_activity)
    AnalyticsAnkiReport.SOURCE_ATTRIBUTION ->
        stringResource(KMR.strings.stats_anki_report_source_attribution)
    AnalyticsAnkiReport.READING_TO_CARD_LAG ->
        stringResource(KMR.strings.stats_anki_report_reading_card_lag)
    AnalyticsAnkiReport.CARD_TO_MATURITY_LAG ->
        stringResource(KMR.strings.stats_anki_report_card_maturity_lag)
    AnalyticsAnkiReport.WEEKLY_FLOW -> stringResource(KMR.strings.stats_anki_report_weekly_flow)
    AnalyticsAnkiReport.REVIEW_HISTORY ->
        stringResource(KMR.strings.stats_anki_report_review_history)
    AnalyticsAnkiReport.RETENTION -> stringResource(KMR.strings.stats_anki_report_retention)
    AnalyticsAnkiReport.REVIEW_TIME -> stringResource(KMR.strings.stats_anki_report_review_time)
}

@Composable
private fun ankiCapabilityReasonLabel(value: AnalyticsAnkiCapabilityReason): String = when (value) {
    AnalyticsAnkiCapabilityReason.AVAILABLE ->
        stringResource(KMR.strings.stats_anki_capability_available)
    AnalyticsAnkiCapabilityReason.NO_CURRENT_INVENTORY ->
        stringResource(KMR.strings.stats_anki_capability_no_inventory)
    AnalyticsAnkiCapabilityReason.STALE_INVENTORY ->
        stringResource(KMR.strings.stats_anki_capability_stale)
    AnalyticsAnkiCapabilityReason.PARTIAL_INVENTORY ->
        stringResource(KMR.strings.stats_anki_capability_partial)
    AnalyticsAnkiCapabilityReason.NO_LINKED_SAMPLE ->
        stringResource(KMR.strings.stats_anki_capability_no_linked_sample)
    AnalyticsAnkiCapabilityReason.INSUFFICIENT_SAMPLE ->
        stringResource(KMR.strings.stats_anki_capability_small_sample)
    AnalyticsAnkiCapabilityReason.PROVIDER_UNSUPPORTED ->
        stringResource(KMR.strings.stats_anki_capability_provider_unsupported)
    AnalyticsAnkiCapabilityReason.DATA_NOT_COLLECTED ->
        stringResource(KMR.strings.stats_anki_capability_not_collected)
}

@Composable
private fun provenanceLabel(value: ProvenanceState): String = when (value) {
    ProvenanceState.AVAILABLE -> stringResource(KMR.strings.stats_available)
    ProvenanceState.PARTIAL -> stringResource(KMR.strings.stats_partial)
    ProvenanceState.REMOVED -> stringResource(KMR.strings.stats_removed)
    ProvenanceState.UNAVAILABLE -> stringResource(KMR.strings.stats_unavailable)
    ProvenanceState.LEGACY_AGGREGATE -> stringResource(KMR.strings.stats_legacy)
}

@Composable
private fun maturityLabel(value: MaturityTier): String = when (value) {
    MaturityTier.UNKNOWN -> stringResource(KMR.strings.stats_maturity_unknown)
    MaturityTier.NEW -> stringResource(KMR.strings.stats_maturity_new)
    MaturityTier.LEARNING -> stringResource(KMR.strings.stats_maturity_learning)
    MaturityTier.YOUNG -> stringResource(KMR.strings.stats_maturity_young)
    MaturityTier.MATURE -> stringResource(KMR.strings.stats_maturity_mature)
    MaturityTier.UNAVAILABLE -> stringResource(KMR.strings.stats_unavailable)
    MaturityTier.STALE -> stringResource(KMR.strings.stats_stale)
}

@Composable
private fun matchConfidenceLabel(value: AnkiMatchConfidence): String = when (value) {
    AnkiMatchConfidence.READING_AWARE -> stringResource(KMR.strings.stats_match_reading_aware)
    AnkiMatchConfidence.HEADWORD_ONLY -> stringResource(KMR.strings.stats_match_headword_only)
    AnkiMatchConfidence.AMBIGUOUS -> stringResource(KMR.strings.stats_match_ambiguous)
}

@Composable
private fun sessionStatusLabel(value: SessionStatus): String = when (value) {
    SessionStatus.ACTIVE -> stringResource(KMR.strings.stats_session_active)
    SessionStatus.COMPLETED -> stringResource(KMR.strings.stats_session_completed)
    SessionStatus.ABANDONED -> stringResource(KMR.strings.stats_session_abandoned)
    SessionStatus.DELETED -> stringResource(KMR.strings.stats_session_deleted)
}

@Composable
private fun sourceKindLabel(value: SourceKind): String = when (value) {
    SourceKind.NOVEL_RANGE -> stringResource(KMR.strings.stats_source_kind_novel)
    SourceKind.MANGA_PAGE -> stringResource(KMR.strings.stats_source_kind_manga_page)
    SourceKind.MANGA_OCR_BLOCK -> stringResource(KMR.strings.stats_source_kind_manga_ocr)
    SourceKind.SUBTITLE_CUE -> stringResource(KMR.strings.stats_source_kind_subtitle)
    SourceKind.VIDEO_OCR_REGION -> stringResource(KMR.strings.stats_source_kind_video_ocr)
}

private fun ReadingMetrics.characterValue(metric: CharacterMetric): Long =
    characters.valueFor(metric)

private fun AnalyticsActivityTotals.characterValue(metric: CharacterMetric): Long = when (metric) {
    CharacterMetric.GROSS -> grossCharacters
    CharacterMetric.UNIQUE_SOURCE -> uniqueSourceCharacters
    CharacterMetric.NET_PROGRESS -> netCharacters
}

private fun movingAverage(
    points: List<AnalyticsTrendPoint>,
    trendMetric: StatsTrendMetric,
    metric: CharacterMetric,
    window: Int = 7,
): List<Pair<AnalyticsTrendPoint, Double>> =
    points.mapIndexed { index, point ->
        val start = (index - window + 1).coerceAtLeast(0)
        val values = points.subList(start, index + 1).map {
            it.metrics.trendValue(trendMetric, metric)
        }
        point to values.average()
    }

private fun ReadingMetrics.trendValue(
    trendMetric: StatsTrendMetric,
    characterMetric: CharacterMetric,
): Long = when (trendMetric) {
    StatsTrendMetric.ACTIVE_TIME -> activeTime.value
    StatsTrendMetric.CHARACTERS -> characterValue(characterMetric)
    StatsTrendMetric.SESSIONS -> sessions.value
    StatsTrendMetric.LOOKUPS -> successfulLookups.value
    StatsTrendMetric.CARDS -> cardsCreated.value
    StatsTrendMetric.NEW_WORDS -> newWords.value
    StatsTrendMetric.NEW_CHARACTERS -> newCharacters.value
}

@Composable
private fun trendMetricLabel(
    trendMetric: StatsTrendMetric,
    characterMetric: CharacterMetric,
): String = when (trendMetric) {
    StatsTrendMetric.ACTIVE_TIME -> stringResource(KMR.strings.stats_active_time)
    StatsTrendMetric.CHARACTERS -> characterMetricLabel(characterMetric)
    StatsTrendMetric.SESSIONS -> stringResource(KMR.strings.stats_sessions)
    StatsTrendMetric.LOOKUPS -> stringResource(KMR.strings.stats_lookups)
    StatsTrendMetric.CARDS -> stringResource(KMR.strings.stats_cards_created)
    StatsTrendMetric.NEW_WORDS -> stringResource(KMR.strings.stats_new_words)
    StatsTrendMetric.NEW_CHARACTERS -> stringResource(KMR.strings.stats_new_characters)
}

@Composable
private fun titleSeriesSelectionLabel(selection: AnalyticsTitleSeriesSelection): String = when (selection) {
    AnalyticsTitleSeriesSelection.TOP_CHARACTERS ->
        stringResource(KMR.strings.stats_title_series_top)
    AnalyticsTitleSeriesSelection.MOST_RECENT ->
        stringResource(KMR.strings.stats_title_series_recent)
}

@Composable
private fun formatTrendValue(value: Long, trendMetric: StatsTrendMetric): String =
    if (trendMetric == StatsTrendMetric.ACTIVE_TIME) {
        formatDuration(value)
    } else {
        formatCount(value)
    }

private fun formatCount(value: Long): String = NumberFormat.getIntegerInstance().format(value)

private fun formatRate(value: Double): String = NumberFormat.getIntegerInstance().format(value.roundToInt())

private fun formatDecimal(value: Double): String =
    NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(value)

private fun formatPercent(value: Double): String =
    NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }.format(value)

@Composable
private fun formatDuration(millis: Long): String {
    val parts = statsDurationParts(millis)
    if (parts.lessThanSecond) {
        return stringResource(KMR.strings.stats_duration_less_than_second)
    }
    if (parts.hours == 0L && parts.minutes == 0L && parts.seconds > 0L) {
        return pluralStringResource(
            KMR.plurals.stats_duration_seconds,
            parts.seconds.toInt(),
            parts.seconds,
        )
    }
    val minuteText = pluralStringResource(
        KMR.plurals.stats_duration_minutes,
        parts.minutes.toInt(),
        parts.minutes,
    )
    if (parts.hours == 0L) return minuteText
    return stringResource(
        KMR.strings.stats_duration_hours_minutes,
        pluralStringResource(KMR.plurals.stats_duration_hours, parts.hours.toInt(), parts.hours),
        minuteText,
    )
}

private fun formatInstant(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))

private fun formatHour(hourOfDay: Int): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .format(LocalTime.of(hourOfDay, 0))

private fun formatWeekday(isoDayOfWeek: Int): String =
    DayOfWeek.of(isoDayOfWeek).getDisplayName(TextStyle.FULL, Locale.getDefault())

@Composable
private fun formatDateRange(
    start: tachiyomi.domain.immersion.model.ImmersionLocalDate,
    endInclusive: tachiyomi.domain.immersion.model.ImmersionLocalDate,
): String = if (start == endInclusive) {
    formatLocalDate(start)
} else {
    stringResource(
        KMR.strings.stats_date_range,
        formatLocalDate(start),
        formatLocalDate(endInclusive),
    )
}

private fun formatLocalDate(date: tachiyomi.domain.immersion.model.ImmersionLocalDate): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(date.toLocalDate())

private fun VocabularyFilter.activeFilterCount(): Int =
    listOf(
        knownness != VocabularyKnownness.ALL,
        scripts.isNotEmpty(),
        categories.isNotEmpty(),
        !partOfSpeechQuery.isNullOrBlank(),
        minimumOccurrences != null,
        maximumOccurrences != null,
        maximumFrequencyRank != null,
        exclusion != VocabularyExclusion.INCLUDED,
    ).count { it }

private fun String.optionalPositiveLong(): Long? {
    if (isBlank()) return null
    return toLongOrNull()?.takeIf { it > 0 }
        ?: throw IllegalArgumentException("Expected a positive integer")
}

private val GOAL_MULTIPLIER_OPTIONS = listOf(0.0, 0.5, 1.0)
private const val TITLE_ACQUISITION_VISIBLE_BUCKETS = 12
private const val DEFAULT_GOAL_WINDOW_DAYS = 30L
private const val MAX_COMPACT_GOALS = 3
private const val MAX_GOAL_CHECK_IN_NOTE_LENGTH = 500
private const val SESSION_RELINK_TARGET_LIMIT = 20
