package eu.kanade.presentation.more.stats

import android.content.Intent
import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import dev.icerock.moko.resources.PluralsResource
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
import tachiyomi.domain.immersion.model.AnalyticsTrendPoint
import tachiyomi.domain.immersion.model.AnalyticsTrends
import tachiyomi.domain.immersion.model.CapabilityState
import tachiyomi.domain.immersion.model.CharacterMetric
import tachiyomi.domain.immersion.model.ImmersionAnkiItem
import tachiyomi.domain.immersion.model.ImmersionDeletionPreview
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
    onCharacterSortSelect: (AnalyticsSort) -> Unit,
    onCharacterFilterChange: (AnalyticsCharacterFilter) -> Unit,
    onCharacterGridModeSelect: (StatsCharacterGridMode) -> Unit,
    onCharacterLayoutSelect: (StatsCharacterLayout) -> Unit,
    onCharacterCoverageTargetChange: (Int) -> Unit,
    onCharacterSelectionChange: (Int, Boolean) -> Unit,
    onCharacterSelectionClear: () -> Unit,
    onCharacterExport: () -> Unit,
    onTitleSearch: (String) -> Unit,
    onCharacterSearch: (String) -> Unit,
    onTitleSelect: (AnalyticsTitleRow?) -> Unit,
    onTitleOpen: (AnalyticsTitleRow) -> Unit,
    onTitleManage: (AnalyticsTitleRow) -> Unit,
    onTitleUnlink: () -> Unit,
    onTitleDeleteStats: (AnalyticsTitleRow) -> Unit,
    onTitleDeleteRawText: (AnalyticsTitleRow) -> Unit,
    onTitleCaptureExclusionChange: (Boolean) -> Unit,
    onCharacterSelect: (AnalyticsCharacterRow?) -> Unit,
    onSessionSelect: (ImmersionSession?) -> Unit,
    onSessionDelete: (ImmersionSession) -> Unit,
    onSessionRelinkPreview: (TitleId) -> Unit,
    onSessionRelinkPreviewClear: () -> Unit,
    onSessionRelinkApply: () -> Unit,
    onLoadMoreTitles: () -> Unit,
    onLoadMoreTitleSessions: () -> Unit,
    onLoadMoreTitleCompletedUnits: () -> Unit,
    onLoadMoreTitleSources: () -> Unit,
    onLoadMoreCharacters: () -> Unit,
    onLoadMoreCharacterOccurrences: () -> Unit,
    onLoadMoreSessions: () -> Unit,
    onSaveGoal: (StatsGoalEditorValues, ImmersionGoal?) -> Boolean,
    onArchiveGoal: (ImmersionGoal) -> Unit,
    onCheckInGoal: (String, String?) -> Unit,
    onAnkiRefresh: () -> Unit,
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
                onSectionRetry = onSectionRetry,
            )
            StatsTab.SESSIONS -> SessionsTab(
                state,
                onSessionSelect,
                onSessionDelete,
                onSessionRelinkPreview,
                onSessionRelinkPreviewClear,
                onSessionRelinkApply,
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
                imageVector = Icons.Outlined.ArrowDropDown,
                contentDescription = stringResource(
                    if (expanded) KMR.strings.stats_filters_collapse else KMR.strings.stats_filters_expand,
                ),
                // The row is a toggle, so the chevron has to say which way it will go.
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
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
                // Skip the heading entirely rather than leaving it stranded above nothing.
                if (result.value.items.isEmpty()) return@SectionFrame
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(KMR.strings.stats_recent_sessions))
                    result.value.items.take(3).forEach { session ->
                        SessionRow(session, state.sessionTitleName(session)) {
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
        // Declared conditionally rather than emitting an empty item: LazyColumn still
        // spaces an item that draws nothing, leaving a stray gap at the foot of the list.
        section.value?.quality?.let { quality ->
            item { DataQualityCard(quality) }
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
        // With no active day the summary is a sentence built entirely from zeros and the
        // grid is a lone blank cell, so the section says plainly that there is nothing yet.
        if (activeDays == 0) {
            EmptyState()
            return@Column
        }
        NoteText(summary)
        // The grid runs oldest week first and a year of them is far wider than any phone, so
        // the default left-anchored position shows a wall of blank pre-history while the recent
        // weeks — the part anyone actually looks at — sit off the right edge. Anchoring to the
        // end puts today under the thumb and leaves history a scroll away.
        val scrollState = rememberScrollState()
        LaunchedEffect(scrollState.maxValue) { scrollState.scrollTo(scrollState.maxValue) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            cells.chunked(7).forEach { week ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    week.forEach { point ->
                        if (point == null) {
                            Spacer(Modifier.size(HEATMAP_CELL_SIZE))
                        } else {
                            val value = point.metrics.characterValue(metric)
                            val description = stringResource(
                                KMR.strings.stats_heatmap_day,
                                formatLocalDate(point.range.start),
                                pluralStringResource(
                                    KMR.plurals.stats_character_count,
                                    value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                    formatCount(value),
                                ),
                            )
                            HeatmapCell(
                                level = characterFrequencyLevel(value, maximum, HEATMAP_LEVELS),
                                modifier = Modifier.semantics { contentDescription = description },
                            )
                        }
                    }
                }
            }
        }
        HeatmapLegend()
    }
}

private val HEATMAP_CELL_SIZE = 14.dp

/** Shaded steps a heatmap cell can take, not counting the unshaded step a blank day gets. */
private const val HEATMAP_LEVELS = 4

@Composable
private fun HeatmapCell(
    level: Int,
    modifier: Modifier = Modifier,
) {
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val filled = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(HEATMAP_CELL_SIZE)
            .background(
                color = if (level == 0) empty else lerp(empty, filled, 0.25f + 0.25f * (level - 1)),
                shape = RoundedCornerShape(3.dp),
            ),
    )
}

@Composable
private fun HeatmapLegend() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(KMR.strings.stats_heatmap_legend_less),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        repeat(HEATMAP_LEVELS + 1) { level -> HeatmapCell(level = level) }
        Text(
            text = stringResource(KMR.strings.stats_heatmap_legend_more),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    val hasActivity = metrics.activeTime.value > 0L || metrics.sessions.value > 0L
    val hadPreviousActivity = (overview.comparison.previous?.activeTime?.value ?: 0L) > 0L
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // A comparison needs a baseline. With nothing in the previous period the card can only
        // report a first period at length — "Up 39 hours 48 minutes from 0 minutes; percentage
        // unavailable because the previous value was zero" — which is true, wordy, and says less
        // than its own absence; on an all-time range the previous period is empty by definition,
        // so it would sit at the top of the screen permanently. The partial-day notice exists only
        // to qualify that comparison, so it waits for the same condition.
        if (hadPreviousActivity) {
            if (overview.period.isPartialCurrentDay && hasActivity) {
                NoticeCard(stringResource(KMR.strings.stats_partial_day))
            }
            ComparisonCard(overview)
        }
        val cards = listOf(
            DashboardMetric(
                formatDurationCompact(metrics.activeTime.value),
                stringResource(KMR.strings.stats_active_time),
                Icons.Outlined.Schedule,
                StatsTab.ACTIVITY,
                spokenValue = formatDuration(metrics.activeTime.value),
            ),
            DashboardMetric(
                formatCount(metrics.characterValue(metric)),
                characterMetricLabel(metric),
                Icons.Outlined.TextFields,
                StatsTab.CHARACTERS,
            ),
            // A bare "5,398" under "Reading speed" is ambiguous between per-hour and per-session,
            // which is the whole content of the measure, so the rate carries its unit.
            DashboardMetric(
                metrics.readingSpeedPerHour(metric)
                    ?.let { stringResource(KMR.strings.stats_per_hour, formatRate(it)) },
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
                formatCount(metrics.cardsCreated.value),
                stringResource(KMR.strings.stats_cards_created),
                Icons.Outlined.Style,
                StatsTab.ANKI,
            ),
            DashboardMetric(
                overviewIndexedGrowthMetricValue(metrics.newCharacters.value, result.quality)
                    ?.let(::formatCount),
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
        modifier = Modifier.fillMaxWidth(),
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
        // The threshold caveat qualifies the speed figures above it. With nothing recorded
        // there are no such figures, so it would sit alone at the foot of the tab explaining
        // a rule about numbers that are not on screen.
        val hasRecordedActivity = state.sections.trends.value
            ?.value
            ?.points
            ?.any { it.metrics.activeTime.value > 0L || it.metrics.sessions.value > 0L }
            ?: false
        if (hasRecordedActivity) {
            item {
                NoteText(stringResource(KMR.strings.stats_minimum_threshold))
            }
        }
    }
}

@Composable
private fun TrendsContent(
    trends: AnalyticsTrends,
    metric: CharacterMetric,
    trendMetric: StatsTrendMetric,
    title: String = stringResource(KMR.strings.stats_activity_chart),
) {
    val points = trends.points
    val values = points.map { it.metrics.trendValue(trendMetric, metric) }
    val max = values.maxOrNull() ?: 0L
    val total = values.sum()
    val barColor = MaterialTheme.colorScheme.primary
    val bucketCount = pluralStringResource(KMR.plurals.stats_bucket_count, points.size, points.size)
    val summary = stringResource(
        KMR.strings.stats_activity_chart_summary,
        bucketCount,
        formatTrendValue(total, trendMetric),
        formatTrendValue(max, trendMetric),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(title)
        if (max <= 0L) {
            EmptyState()
            return@Column
        }
        NoteText(summary)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .semantics { contentDescription = summary },
        ) {
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
        MetricLine(stringResource(KMR.strings.stats_sessions), formatCount(cumulative.sessions.value))
        SectionTitle(stringResource(KMR.strings.stats_efficiency))
        MetricLine(
            stringResource(KMR.strings.stats_reading_speed),
            cumulative.readingSpeedPerHour(metric)
                ?.let { stringResource(KMR.strings.stats_per_hour, formatRate(it)) }
                ?: stringResource(KMR.strings.stats_unavailable),
        )
        MetricLine(
            stringResource(KMR.strings.stats_mining_rate),
            cumulative.miningRatePerTenThousandGrossCharacters()?.let(::formatDecimal)
                ?: stringResource(KMR.strings.stats_unavailable),
        )
        SectionTitle(stringResource(KMR.strings.stats_moving_average))
        val recentAverages = movingAverage(points, trendMetric, metric).takeLast(10)
        if (recentAverages.all { it.second <= 0.0 }) {
            // Ten dated rows of zero say one thing ten times and read as a metric that failed rather
            // than as a quiet stretch. The temporal-pattern charts suppress their all-zero axes the
            // same way.
            EmptyState()
        } else {
            recentAverages.forEach { (point, average) ->
                MetricLine(
                    formatLocalDate(point.range.endInclusive),
                    formatTrendValue(average.roundToLong(), trendMetric),
                )
            }
        }
    }
}

@Composable
private fun TemporalPatternsContent(
    activity: AnalyticsTemporalActivity,
    metric: CharacterMetric,
) {
    // Only hours that hold something are drawn. A day has 24 of them and a typical reader uses a
    // handful, so the full grid is mostly identical zero tiles; the hours left out are zero by
    // construction, which the eye reads faster from their absence than from eighteen empty cards.
    // Weekdays keep all seven: that axis is short enough to stay compact and a gap in a full week
    // is itself the finding.
    val hours = activity.hours
        .filter { it.totals.hasActivity(metric) }
        .sortedBy { it.hourOfDay }
    val weekdays = activity.weekdays.sortedBy { it.isoDayOfWeek }
    val maximumHourlyCharacters = hours
        .maxOfOrNull { it.totals.characterValue(metric).coerceAtLeast(0L) }
        ?.coerceAtLeast(1L)
        ?: 1L
    val maximumWeekdayCharacters = weekdays
        .maxOfOrNull { it.totals.characterValue(metric).coerceAtLeast(0L) }
        ?.coerceAtLeast(1L)
        ?: 1L

    // The backend returns a full grid of buckets whether or not anything happened in them,
    // so emptiness is the wrong test for the weekday axis: without this, an idle period renders
    // seven zero bars, which is noise carrying no information.
    val hasHourlyActivity = hours.isNotEmpty()
    val hasWeekdayActivity = weekdays.any { it.totals.hasActivity(metric) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(KMR.strings.stats_time_patterns))
        if (!hasHourlyActivity && !hasWeekdayActivity) {
            EmptyState()
            return@Column
        }
        NoteText(stringResource(KMR.strings.stats_time_patterns_explanation))
        if (hasHourlyActivity) {
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
        }
        if (hasWeekdayActivity) {
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
    // The tile shows a dash where the screen reader says "Unavailable": in a 24-tile grid
    // the repeated word is what the eye reads instead of the numbers.
    val speedLabel = speed?.let { stringResource(KMR.strings.stats_per_hour, formatRate(it)) } ?: "—"
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
                text = speedLabel,
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
            NoteText(
                stringResource(
                    KMR.strings.stats_title_series_limit,
                    trends.series.size,
                ),
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
    // The detail card sits above the rows, so a selection made further down the list would otherwise
    // appear to do nothing. Scroll it into view.
    val listState = rememberLazyListState()
    LaunchedEffect(selected?.titleId) {
        if (selected != null) listState.animateScrollToItem(0)
    }
    LazyColumn(
        state = listState,
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
                NoteText(stringResource(KMR.strings.stats_minimum_threshold))
            }
        }
        if (selected != null) {
            item {
                TitleDetail(
                    title = selected,
                    metadata = state.titleMetadata[selected.titleId],
                    metric = state.filter.characterMetric,
                    details = state.details,
                    captureExcluded = state.details.titleCaptureExcluded,
                    mutationInProgress = state.details.titleMutationInProgress,
                    mutationError = state.details.titleMutationError,
                    onOpen = { onOpen(selected) },
                    onManage = { onManage(selected) },
                    onUnlink = { showUnlinkConfirmation = true },
                    onDeleteStats = { onDeleteStats(selected) },
                    onDeleteRawText = { onDeleteRawText(selected) },
                    onCaptureExclusionChange = onCaptureExclusionChange,
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
                    sort = state.titleSort,
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
    // Tapping a tile deep in the grid opens a detail card above the overview, several screens up. The
    // prev/next buttons inside that card also change the selection, so this keeps it in view too.
    val gridState = rememberLazyGridState()
    LaunchedEffect(state.selection.character?.codePoint) {
        if (state.selection.character != null) gridState.animateScrollToItem(0)
    }
    LazyVerticalGrid(
        columns = when (state.characterLayout) {
            StatsCharacterLayout.GRID -> GridCells.Adaptive(72.dp)
            StatsCharacterLayout.LIST -> GridCells.Fixed(1)
        },
        state = gridState,
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
                        pluralStringResource(
                            KMR.plurals.stats_character_selection_count,
                            selectedCodePoints.size,
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
                    ankiItems = state.details.characterAnkiItems,
                    previous = previous,
                    next = next,
                    onClose = { onSelect(null) },
                    onSelect = onSelect,
                    onLoadMoreOccurrences = onLoadMoreOccurrences,
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
                // This counts characters first seen *inside the selected range*, which the query
                // can only answer when a range is bound; on an all-time view it returns 0 by
                // construction. Showing "New characters 0" beside a distinct-character total of 28
                // reads as a contradiction, and the honest all-time answer — every character is new
                // — is just the total again, so the line waits for a range that gives it meaning.
                if (state.filter.rangePreset != StatsRangePreset.ALL) {
                    MetricLine(
                        stringResource(KMR.strings.stats_new_characters),
                        formatCount(value.firstSeenInRange),
                    )
                }
                MetricLine(
                    stringResource(KMR.strings.stats_character_gross_exposure),
                    formatCount(value.grossOccurrenceExposure),
                )
                // The per-script counts are a breakdown of the distinct-character total above,
                // not three more totals of their own. Flush against the top-level metrics they
                // read as one flat list of nine unrelated numbers, so they get a heading that
                // says what they are a breakdown of.
                if (value.scripts.isNotEmpty()) {
                    SubsectionTitle(stringResource(KMR.strings.stats_character_scripts))
                    value.scripts.forEach { script ->
                        MetricLine(
                            characterScriptLabel(script.script),
                            formatCount(script.distinctCharacters),
                        )
                    }
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
                    // Flush against the per-script rows above, these two read as two more scripts.
                    // They measure something else entirely — how much of the character set Anki
                    // already holds — so they get their own subheading.
                    SubsectionTitle(stringResource(KMR.strings.stats_tab_anki))
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
                    NoteText(
                        stringResource(
                            KMR.strings.stats_character_daily_suggestion,
                            formatCount(target.dailyPlanningSuggestion),
                            formatCount(target.remainingCharacters),
                        ),
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
            TrendsContent(
                trends = result.value,
                metric = state.filter.characterMetric,
                trendMetric = StatsTrendMetric.NEW_CHARACTERS,
                title = stringResource(KMR.strings.stats_character_growth),
            )
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
        NoteText(stringResource(KMR.strings.stats_character_priority_formula))
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
            NoteText(stringResource(KMR.strings.stats_character_frequency_legend))
        }
        if (layout == StatsCharacterLayout.GRID) {
            // Grid tiles have no room for a checkbox, so selection is a long-press — a gesture with
            // no affordance to find it by. The list layout shows its checkboxes and needs no hint.
            NoteText(stringResource(KMR.strings.stats_character_select_hint))
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
    onLoadMore: () -> Unit,
    onSectionRetry: (StatsSection) -> Unit,
) {
    val result = state.sections.sessions.value
    val sessions = result?.value?.items.orEmpty()
    val loadState = state.sections.sessions.collectionLoadState(sessions.isNotEmpty())
    // The detail card is prepended to the list, so selecting a row further down changed nothing the
    // reader could see. Bring the card into view whenever the selection changes.
    val listState = rememberLazyListState()
    LaunchedEffect(state.selection.session?.id) {
        if (state.selection.session != null) listState.animateScrollToItem(0)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
                SessionRow(session, state.sessionTitleName(session)) { onSelect(session) }
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
            item { EmptyState(stringResource(KMR.strings.stats_no_goals)) }
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
                            NoteText(
                                stringResource(
                                    KMR.strings.stats_anki_snapshot_completed,
                                    formatInstant(it),
                                ),
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
                        NoteText(
                            stringResource(
                                KMR.strings.stats_anki_report_generated,
                                formatInstant(it),
                            ),
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
                        NoteText(stringResource(KMR.strings.stats_anki_refresh_requested))
                    }
                    if (summary.capabilities.isNotEmpty()) {
                        SectionTitle(stringResource(KMR.strings.stats_anki_capabilities))
                        // Up to nine of these stack up, and pairing each state with its reason in the
                        // value column ran a clause like "Provider support exists, but review records
                        // are not collected" edge to edge in bold, crowding out the report name. The
                        // row keeps the one-word state and the reason drops to a note beneath it.
                        summary.capabilities.forEach { capability ->
                            MetricLine(
                                ankiReportLabel(capability.report),
                                capabilityLabel(capability.state),
                            )
                            ankiCapabilityReason(capability.reason)?.let {
                                NoteText(it)
                            }
                        }
                    }
                    CoverageLine(
                        stringResource(KMR.strings.stats_character_coverage),
                        summary.characterCoverageKnown,
                        summary.characterCoverageEncountered,
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
                                NoteText(
                                    stringResource(
                                        KMR.strings.stats_cards_per_ten_thousand,
                                        formatDecimal(it),
                                    ),
                                )
                            }
                        }
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
                    // One card, not three: these caveats always apply together, and stacking
                    // them turned the foot of the tab into a wall of identical banners.
                    NoticeCard(
                        listOfNotNull(
                            stringResource(
                                KMR.strings.stats_anki_sample_limit,
                                summary.minimumComparisonSampleSize,
                            ),
                            stringResource(KMR.strings.stats_anki_observational),
                            stringResource(KMR.strings.stats_review_history_unavailable)
                                .takeIf { !summary.reviewHistoryAvailable },
                        ),
                    )
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
            // Each measure is omitted rather than printed as "Unavailable": a column of
            // five identical placeholders reads as broken, where saying nothing is honest.
            val measured = listOfNotNull(
                quality.legacyShare?.let { stringResource(KMR.strings.stats_legacy_share) to it },
                quality.indexingCompletion?.let { stringResource(KMR.strings.stats_indexing_coverage) to it },
                quality.textCoverage?.let { stringResource(KMR.strings.stats_text_coverage) to it },
                quality.ocrTextCoverage?.let { stringResource(KMR.strings.stats_ocr_coverage) to it },
            )
            // The two capability states are suppressed on the same rule as the percentages: an
            // UNAVAILABLE state carries no more than the line's own absence does.
            val ankiKnown = quality.ankiState != CapabilityState.UNAVAILABLE
            val provenanceKnown = quality.provenanceState != ProvenanceState.UNAVAILABLE
            if (measured.isEmpty() && !ankiKnown && !provenanceKnown) {
                NoteText(stringResource(KMR.strings.stats_data_quality_unavailable))
                return@Column
            }
            measured.forEach { (label, value) -> MetricLine(label, formatPercent(value)) }
            if (ankiKnown) {
                MetricLine(stringResource(KMR.strings.stats_anki_state), capabilityLabel(quality.ankiState))
                if (quality.ankiState == CapabilityState.STALE) {
                    NoteText(stringResource(KMR.strings.stats_anki_snapshot_stale))
                }
            }
            if (provenanceKnown) {
                MetricLine(
                    stringResource(KMR.strings.stats_provenance_state),
                    provenanceLabel(quality.provenanceState),
                )
            }
        }
    }
}

@Composable
private fun TitleRow(
    title: AnalyticsTitleRow,
    metadata: StatsTitlePresentationMetadata?,
    metric: CharacterMetric,
    sort: AnalyticsTitleSort,
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
                NoteText(
                    stringResource(
                        KMR.strings.stats_media_and_language,
                        mediaLabel(title.mediaKind),
                        title.languageTag?.value ?: stringResource(KMR.strings.stats_unknown),
                    ),
                )
                // A list row is for scanning and comparing, not for reading a title's full
                // record — that is what tapping through to the detail card is for. Nine metric
                // lines per row made each entry a screenful, so the row keeps the three that
                // describe any title plus whichever measure the list is currently sorted by,
                // which is the one the reader chose to compare on.
                MetricLine(
                    stringResource(KMR.strings.stats_active_time),
                    formatDuration(title.metrics.activeTime.value),
                )
                MetricLine(characterMetricLabel(metric), formatCount(title.metrics.characterValue(metric)))
                titleSortMetric(title, metric, sort)?.let { (label, value) ->
                    MetricLine(label, value)
                }
                NoteText(stringResource(KMR.strings.stats_last_active, formatLocalDate(title.lastActiveDate)))
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
    captureExcluded: StatsLoadable<Boolean>,
    mutationInProgress: Boolean,
    mutationError: Boolean,
    onOpen: () -> Unit,
    onManage: () -> Unit,
    onUnlink: () -> Unit,
    onDeleteStats: () -> Unit,
    onDeleteRawText: () -> Unit,
    onCaptureExclusionChange: (Boolean) -> Unit,
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
        // This card carries around thirty measures. Left as one flat list they read as an
        // undifferentiated wall of numbers, so they are grouped under the same section headings the
        // rest of the file uses, and the actions are lifted out from between the data rows.
        HorizontalDivider()
        SectionTitle(stringResource(KMR.strings.stats_section_identity))
        // The two unlinked states are explanations, not values; as MetricLine values they filled the
        // row edge to edge in bold and squeezed out their own label. The row keeps a short status and
        // the sentence moves to the note that the rest of the screen uses for caveats.
        MetricLine(
            stringResource(KMR.strings.stats_title_link),
            titleLinkStatus(metadata?.linkState),
        )
        MetricLine(
            stringResource(KMR.strings.stats_title_identity),
            title.sourceKey,
        )
        MetricLine(
            stringResource(KMR.strings.stats_title_status),
            titleStateLabel(title.completed),
        )
        if (title.profileId.isNotBlank()) {
            MetricLine(stringResource(KMR.strings.stats_profile_label), title.profileId)
        }
        titleLinkExplanation(metadata?.linkState)?.let { NoticeCard(it) }
        HorizontalDivider()
        SectionTitle(stringResource(KMR.strings.stats_section_manage))
        // Five stacked full-width buttons filled a screen on their own. Flowing them keeps the
        // destructive pair visibly subordinate to the primary actions without hiding either.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
        }
        if (mutationInProgress) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (mutationError) {
            NoticeCard(stringResource(KMR.strings.stats_title_mutation_error))
        }
        HorizontalDivider()
        SectionTitle(stringResource(KMR.strings.stats_section_reading))
        MetricLine(stringResource(KMR.strings.stats_active_time), formatDuration(title.metrics.activeTime.value))
        MetricLine(characterMetricLabel(metric), formatCount(title.metrics.characterValue(metric)))
        MetricLine(
            stringResource(KMR.strings.stats_reading_speed),
            title.metrics.readingSpeedPerHour(metric)
                ?.let { stringResource(KMR.strings.stats_per_hour, formatRate(it)) }
                ?: stringResource(KMR.strings.stats_unavailable),
        )
        // The label already names the unit, so a value of "56 source units" says it twice; every
        // other count in this section is a bare number.
        MetricLine(
            stringResource(KMR.strings.stats_source_units_exposed),
            formatCount(title.metrics.sourceUnits.value),
        )
        MetricLine(stringResource(KMR.strings.stats_sessions), formatCount(title.metrics.sessions.value))
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
        HorizontalDivider()
        SectionTitle(stringResource(KMR.strings.stats_section_mining))
        MetricLine(stringResource(KMR.strings.stats_cards_created), formatCount(title.metrics.cardsCreated.value))
        MetricLine(stringResource(KMR.strings.stats_cards_updated), formatCount(title.metrics.cardsUpdated.value))
        MetricLine(
            stringResource(KMR.strings.stats_mining_rate),
            title.metrics.miningRatePerTenThousandGrossCharacters()?.let(::formatDecimal)
                ?: stringResource(KMR.strings.stats_unavailable),
        )
        HorizontalDivider()
        SectionTitle(stringResource(KMR.strings.stats_section_daily_pattern))
        MetricLine(stringResource(KMR.strings.stats_first_active_label), formatLocalDate(title.firstActiveDate))
        MetricLine(stringResource(KMR.strings.stats_last_active_label), formatLocalDate(title.lastActiveDate))
        // "Active days: 18 active days" says it twice. The neighbouring calendar span keeps its unit
        // because its own label does not name one.
        MetricLine(
            stringResource(KMR.strings.stats_active_days),
            formatCount(title.activeDays.toLong()),
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
                    stringResource(KMR.strings.stats_per_hour, formatRate(highlight.value)),
                ),
            )
        }
        HorizontalDivider()
        SectionTitle(stringResource(KMR.strings.stats_section_completion))
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
            NoteText(
                stringResource(
                    if (title.unitProgress.identityAvailable) {
                        KMR.strings.stats_title_completed_units_incomplete
                    } else {
                        KMR.strings.stats_title_completed_units_unavailable
                    },
                ),
            )
        }
        MetricLine(
            stringResource(KMR.strings.stats_progress),
            title.progress?.let(::formatPercent) ?: stringResource(KMR.strings.stats_unavailable),
        )
        title.estimate?.let { estimate ->
            MetricLine(
                stringResource(KMR.strings.stats_estimated_remaining),
                formatDuration(estimate.estimatedActiveTimeMillis),
            )
            NoteText(estimateRemainingAmount(title.mediaKind, estimate))
            NoteText(
                stringResource(
                    KMR.strings.stats_estimate_confidence,
                    estimateConfidenceLabel(estimate.confidence),
                    pluralCount(KMR.plurals.stats_qualifying_day_count, estimate.qualifyingDayCount),
                ),
            )
        } ?: Column {
            // The reason an estimate is missing is a sentence, and in MetricLine's bold right-hand
            // column it wrapped to three lines and pushed out its own label. The row states the plain
            // status and the condition follows as a note, matching the branch just above.
            MetricLine(
                stringResource(KMR.strings.stats_estimated_remaining),
                stringResource(KMR.strings.stats_unavailable),
            )
            NoteText(stringResource(KMR.strings.stats_estimate_unavailable))
        }
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
                    NoteText(stringResource(KMR.strings.stats_capture_title_toggle_summary))
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
            NoteText(
                stringResource(
                    KMR.strings.stats_title_unit_completed_on,
                    formatLocalDate(unit.firstCompletedDate),
                    pluralStringResource(
                        KMR.plurals.stats_completion_event_count,
                        unit.completionEventCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        formatCount(unit.completionEventCount),
                    ),
                ),
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
                    NoteText(sessionStatusLabel(session.status))
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
        if (result.value.items.all { it.excerpt.isNullOrBlank() }) {
            NoteText(stringResource(KMR.strings.stats_source_text_unavailable))
        }
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
private fun titleLinkStatus(state: StatsTitleLinkState?): String = when (state) {
    StatsTitleLinkState.AVAILABLE -> stringResource(KMR.strings.stats_title_link_available)
    StatsTitleLinkState.LEGACY_ONLY -> stringResource(KMR.strings.stats_title_link_legacy_only)
    StatsTitleLinkState.UNAVAILABLE, null ->
        stringResource(KMR.strings.stats_title_link_unavailable)
}

/**
 * Why a title has no local item, for the states where that needs saying. An available link is the
 * ordinary case and explaining it would be noise, so it returns null.
 */
@Composable
private fun titleLinkExplanation(state: StatsTitleLinkState?): String? = when (state) {
    StatsTitleLinkState.AVAILABLE -> null
    StatsTitleLinkState.LEGACY_ONLY -> stringResource(KMR.strings.stats_title_link_legacy_only_detail)
    StatsTitleLinkState.UNAVAILABLE, null ->
        stringResource(KMR.strings.stats_title_link_unavailable_detail)
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
    // A grid tile is 72dp wide, which fits none of the long-form mode captions: they clipped to a
    // bare "Frequency" or a truncated date, so every tile carried the same meaningless word. The
    // grid gets abbreviated captions and the full phrasings stay in the list layout, the detail
    // card, and the accessibility description, all of which have room for them.
    val compactModeValue = when (mode) {
        StatsCharacterGridMode.FREQUENCY -> stringResource(
            KMR.strings.stats_character_frequency_level_compact,
            level,
            5,
        )
        StatsCharacterGridMode.FIRST_SEEN -> formatLocalDate(
            ImmersionLocalDate.from(
                Instant.ofEpochMilli(character.firstSeenAtEpochMillis)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate(),
            ),
        )
        StatsCharacterGridMode.MATURITY,
        StatsCharacterGridMode.METADATA,
        StatsCharacterGridMode.PRIORITY,
        -> modeValue
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
            .then(
                if (layout == StatsCharacterLayout.GRID) {
                    // The grid dropped its per-tile checkbox for space, so selection moves to
                    // long-press — the selected border already shows the result.
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = { onSelectedChange(!selected) },
                    )
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            ),
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
                // The glyph is the whole point of a grid tile, and a Material checkbox is nearly as
                // wide as the 72dp cell — it took the top third of every tile and drew an empty box
                // that reads as a missing character. Long-pressing the tile selects it instead; the
                // list layout keeps its checkboxes, where a full-width row has space for one.
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
                // The legend says every cell states its level *and* count. Only the list layout did.
                Text(
                    formatCount(character.occurrenceCount),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    compactModeValue,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                    NoteText(modeValue)
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
    occurrences: StatsLoadable<AnalyticsResult<AnalyticsPage<AnalyticsSourceOccurrence>>>,
    ankiItems: StatsLoadable<List<ImmersionAnkiItem>>,
    previous: AnalyticsCharacterRow?,
    next: AnalyticsCharacterRow?,
    onClose: () -> Unit,
    onSelect: (AnalyticsCharacterRow) -> Unit,
    onLoadMoreOccurrences: () -> Unit,
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
        // Half this card used to be "Label: value" sentences and half aligned label/value rows, so
        // the values never lined up and nothing said which of the eleven measures belonged together.
        // Every row is a MetricLine now, grouped under headings by what it describes.
        SectionTitle(stringResource(KMR.strings.stats_section_unicode))
        MetricLine(
            stringResource(KMR.strings.stats_code_point_label),
            "U+%04X".format(Locale.ROOT, character.codePoint.value),
        )
        character.unicodeName?.let {
            MetricLine(stringResource(KMR.strings.stats_unicode_name_label), it)
        }
        MetricLine(
            stringResource(KMR.strings.stats_script_label),
            unicodeScriptLabel(character.unicodeScript),
        )
        MetricLine(stringResource(KMR.strings.stats_unicode_category_label), character.unicodeCategory)
        SectionTitle(stringResource(KMR.strings.stats_section_usage))
        MetricLine(
            stringResource(KMR.strings.stats_character_gross_exposure),
            formatCount(character.occurrenceCount),
        )
        MetricLine(
            stringResource(KMR.strings.stats_source_units),
            formatCount(character.sourceUnitCount),
        )
        MetricLine(stringResource(KMR.strings.stats_tab_titles), formatCount(character.titleCount))
        MetricLine(
            stringResource(KMR.strings.stats_first_seen_label),
            formatInstant(character.firstSeenAtEpochMillis),
        )
        MetricLine(
            stringResource(KMR.strings.stats_last_seen_label),
            formatInstant(character.lastSeenAtEpochMillis),
        )
        MetricLine(stringResource(KMR.strings.stats_maturity_label), maturityLabel(character.maturity))
        // Readings, rank, JLPT and grade are all study metadata and are all absent for most
        // characters, so the heading only appears when at least one of them has a value — an empty
        // "Study metadata" heading would read as data that failed to load.
        val hasStudyMetadata = character.japaneseReadings != null ||
            character.frequencyRank != null ||
            character.jlptLevel != null ||
            character.gradeLevel != null
        if (hasStudyMetadata) {
            SectionTitle(stringResource(KMR.strings.stats_section_study))
        }
        character.japaneseReadings?.let {
            MetricLine(stringResource(KMR.strings.stats_readings_label), it)
        }
        // Each of these is suppressed when absent on the same rule as the others beside it: measures
        // in one group should not disagree about how they report nothing.
        character.frequencyRank?.let {
            MetricLine(stringResource(KMR.strings.stats_frequency_rank), formatCount(it))
        }
        character.jlptLevel?.let {
            MetricLine(stringResource(KMR.strings.stats_jlpt_level), "N$it")
        }
        character.gradeLevel?.let {
            MetricLine(stringResource(KMR.strings.stats_grade_level), formatCount(it.toLong()))
        }
        // Every component is derived from the three metadata fields above, so a character with
        // none of them scores five rows of 0.00 — arithmetic about nothing. The breakdown also
        // gets a heading: it was six lines of formula internals flush against the character's own
        // measures, reading as one flat list of unrelated numbers.
        if (character.frequencyRank != null || character.jlptLevel != null || character.gradeLevel != null) {
            SectionTitle(stringResource(KMR.strings.stats_character_priority_breakdown))
            MetricLine(
                stringResource(KMR.strings.stats_character_priority_score),
                formatDecimal(character.priorityScore),
            )
            val priorityComponents = AnalyticsCharacterPriorityFormula.components(
                frequencyRank = character.frequencyRank,
                jlptLevel = character.jlptLevel,
                gradeLevel = character.gradeLevel,
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
            NoteText(
                stringResource(
                    KMR.strings.stats_character_priority_formula_version,
                    AnalyticsCharacterPriorityFormula.VERSION,
                ),
            )
        }
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
        SectionTitle(stringResource(KMR.strings.stats_source_occurrences))
        SectionFrame(occurrences) { result ->
            if (result.value.items.isEmpty()) {
                EmptyState()
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (result.value.items.all { it.excerpt.isNullOrBlank() }) {
                        NoteText(stringResource(KMR.strings.stats_source_text_unavailable))
                    }
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
        Text(
            stringResource(KMR.strings.stats_maturity, maturityLabel(item.maturityTier)),
            fontWeight = FontWeight.SemiBold,
        )
        // Maturity alone was the whole card, so a character on several cards showed the same line
        // repeated with nothing to tell one from another. The scheduling figures are what differ,
        // and each is omitted when the provider did not supply it rather than shown as a blank.
        val scheduling = listOfNotNull(
            item.intervalDays?.let {
                stringResource(
                    KMR.strings.stats_anki_card_interval,
                    pluralStringResource(KMR.plurals.stats_day_count, it, formatCount(it.toLong())),
                )
            },
            item.repetitions?.let { stringResource(KMR.strings.stats_anki_card_reviews, formatCount(it.toLong())) },
            item.lapses?.let { stringResource(KMR.strings.stats_anki_card_lapses, formatCount(it.toLong())) },
        )
        if (scheduling.isNotEmpty()) {
            Text(
                scheduling.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The name to show for a session's title, preferring the reader's local library name over the
 * recorded one. Null when the title is no longer resolvable, which the row falls back for.
 */
private fun StatsScreenState.Success.sessionTitleName(session: ImmersionSession): String? =
    titleMetadata[session.titleId]?.localDisplayTitle
        ?: titleOptions.firstOrNull { it.titleId == session.titleId }?.displayTitle

@Composable
private fun SessionRow(
    session: ImmersionSession,
    displayTitle: String?,
    onClick: () -> Unit,
) {
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
                // What was read is the one thing that distinguishes one session from another,
                // and it was missing: every row's second line said "Manga · ja", so a whole
                // screen of sessions looked identical. Media and language stay as the fallback
                // for a session whose title has since been unlinked.
                Text(
                    displayTitle ?: stringResource(
                        KMR.strings.stats_media_and_language,
                        mediaLabel(session.mediaKind),
                        session.languageTag?.value ?: stringResource(KMR.strings.stats_unknown),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(formatDuration(session.activeDuration.value))
        }
    }
}

@Composable
private fun SessionDetail(
    fallback: ImmersionSession,
    detail: StatsLoadable<AnalyticsResult<AnalyticsSessionDetail?>>,
    deletionPreview: StatsLoadable<ImmersionDeletionPreview>,
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
        // The three basis figures are named almost alike — gross, unique, net — so ungrouped they
        // read as one run of six interchangeable numbers. Headings tell the eye which three belong
        // together, and the "Started:"/"Status:" sentences become label/value rows so the whole card
        // is one aligned column rather than prose above a table.
        SectionTitle(stringResource(KMR.strings.stats_section_identity))
        MetricLine(
            stringResource(KMR.strings.stats_started_label),
            formatInstant(session.startedAtEpochMillis),
        )
        MetricLine(stringResource(KMR.strings.stats_status_label), sessionStatusLabel(session.status))
        // An unset profile is the default, and an empty "Profile" row reads as missing data rather
        // than as the ordinary single-profile case. The title card gates the same line.
        if (session.profileId.isNotBlank()) {
            MetricLine(stringResource(KMR.strings.stats_profile_label), session.profileId)
        }
        SectionTitle(stringResource(KMR.strings.stats_section_reading))
        MetricLine(stringResource(KMR.strings.stats_active_time), formatDuration(session.activeDuration.value))
        MetricLine(stringResource(KMR.strings.stats_elapsed_time), formatDuration(session.elapsedDuration.value))
        MetricLine(stringResource(KMR.strings.stats_source_units), formatCount(session.sourceUnitCount.value))
        SectionTitle(stringResource(KMR.strings.stats_section_characters))
        MetricLine(stringResource(KMR.strings.stats_basis_gross), formatCount(session.grossCharacters.value))
        MetricLine(stringResource(KMR.strings.stats_basis_unique), formatCount(session.uniqueSourceCharacters.value))
        MetricLine(stringResource(KMR.strings.stats_basis_net), formatCount(session.netCharacters.value))
        // Stacked full-width text buttons left both actions floating in their own band of whitespace.
        // Side by side they read as the pair of actions they are, with the destructive one tinted.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { confirmDelete = true }) {
                Text(
                    stringResource(KMR.strings.stats_delete_session),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!session.legacyImport && session.status != SessionStatus.ACTIVE) {
                TextButton(onClick = { correctTitle = true }) {
                    Text(stringResource(KMR.strings.stats_session_correct_title))
                }
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
                if (value.sources.all { it.excerpt.isNullOrBlank() }) {
                    NoteText(stringResource(KMR.strings.stats_source_text_unavailable))
                }
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
    val events = detail.timeline.sumOf { it.eventCount }
    val summary = stringResource(
        KMR.strings.stats_timeline_summary,
        pluralStringResource(
            KMR.plurals.stats_timeline_bucket_count,
            detail.timeline.size,
            formatCount(detail.timeline.size.toLong()),
        ),
        pluralStringResource(
            KMR.plurals.stats_event_count,
            events.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            formatCount(events),
        ),
    )
    val color = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(summary, style = MaterialTheme.typography.bodySmall)
        if (detail.timeline.none { it.grossCharacters > 0 }) {
            // Nothing to plot draws an empty 72dp canvas, which reads as a chart that failed to load
            // rather than as a session with no character exposure. The trend chart guards the same way.
            EmptyState()
            return@Column
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .semantics { contentDescription = summary },
        ) {
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
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        // These sit inside a DetailCard, so they lift off its surfaceVariant panel rather than
        // matching it.
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
        // Only cards that actually have an excerpt say anything here. When retention is off no card
        // has one, and repeating the same full sentence down every card said one thing many times;
        // the list states it once above instead.
        occurrence.excerpt?.let {
            Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
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
            // titleLarge, matching DetailCard: the Pace and Streaks headings below are titleMedium, and
            // at the same size as them the card's own name read as a third peer section rather than as
            // the heading they sit under.
            Text(goalTypeLabel(goal.goal.type), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(
                    KMR.strings.stats_goal_progress,
                    formatGoalValue(goal.goal.metric, goal.achieved),
                    formatGoalValue(goal.goal.metric, goal.targetToDate),
                ),
            )
            LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
            val forecast = statsGoalForecastPresentation(goal)
            // Up to four pace figures and two streaks. Ungrouped they read as one list of six
            // unrelated numbers, and the forecast footnote sitting between them split the data it
            // was explaining. Pace and streaks each get a heading, and every note follows the rows
            // it annotates rather than interrupting them.
            val hasPace = goal.pacePerDay != null ||
                goal.requiredPacePerActiveDay != null ||
                goal.rollingSevenDayPace != null ||
                goal.rollingThirtyDayPace != null ||
                forecast != StatsGoalForecastPresentation.NONE
            if (hasPace) {
                SectionTitle(stringResource(KMR.strings.stats_section_pace))
            }
            goal.pacePerDay?.let {
                MetricLine(
                    stringResource(KMR.strings.stats_goal_pace_label),
                    stringResource(KMR.strings.stats_per_active_day, formatGoalValue(goal.goal.metric, it)),
                )
            }
            goal.rollingSevenDayPace?.let {
                MetricLine(
                    stringResource(KMR.strings.stats_goal_rolling_seven_label),
                    stringResource(KMR.strings.stats_per_active_day, formatGoalValue(goal.goal.metric, it)),
                )
            }
            goal.rollingThirtyDayPace?.let {
                MetricLine(
                    stringResource(KMR.strings.stats_goal_rolling_thirty_label),
                    stringResource(KMR.strings.stats_per_active_day, formatGoalValue(goal.goal.metric, it)),
                )
            }
            // The required pace carries both a rate and the window it has to hold over, so it is too
            // long for MetricLine's right-hand column — squeezed in there it wraps to three lines and
            // crowds out its own label. Stacked, the label stays readable and the value gets the width.
            goal.requiredPacePerActiveDay?.let {
                Column {
                    Text(
                        stringResource(KMR.strings.stats_goal_required_pace_label),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(
                            KMR.strings.stats_goal_required_pace_value,
                            formatGoalValue(goal.goal.metric, it),
                            pluralCount(KMR.plurals.stats_remaining_active_day_count, goal.remainingActiveDays ?: 0),
                        ),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            when (forecast) {
                StatsGoalForecastPresentation.AVAILABLE -> {
                    MetricLine(
                        stringResource(KMR.strings.stats_goal_projection_label),
                        formatLocalDate(requireNotNull(goal.projectedCompletionDate)),
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
            if (forecast != StatsGoalForecastPresentation.NONE) {
                NoteText(
                    stringResource(
                        KMR.strings.stats_goal_forecast_assumptions,
                        goal.forecastWindowDays,
                        pluralCount(KMR.plurals.stats_qualifying_active_day_count, goal.forecastSampleDays),
                    ),
                )
            }
            SectionTitle(stringResource(KMR.strings.stats_section_streaks))
            MetricLine(
                stringResource(KMR.strings.stats_current_streak),
                pluralCount(KMR.plurals.stats_day_count, goal.currentStreakDays),
            )
            MetricLine(
                stringResource(KMR.strings.stats_longest_streak),
                pluralCount(KMR.plurals.stats_day_count, goal.longestStreakDays),
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
        "cards",
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
                                // MANUAL needs no test: the whole field is inside `kind != MANUAL`.
                                if (
                                    kind != StatsGoalKind.FINISH_TITLE_BY_DATE &&
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
                NoteText(stringResource(KMR.strings.stats_goal_timezone_policy))
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
                    NoteText(
                        stringResource(
                            if (editMode == StatsGoalEditMode.RESTART_HISTORY) {
                                KMR.strings.stats_goal_edit_restart_summary
                            } else {
                                KMR.strings.stats_goal_edit_prospective
                            },
                        ),
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
                NoteText(stringResource(KMR.strings.stats_goal_check_in_privacy))
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
    // The tertiary role is the theme's accent, not its alarm: under some palettes it renders as
    // a bright green banner, which reads as success for a message that reports a failure.
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
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
                color = MaterialTheme.colorScheme.onErrorContainer,
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
private fun EmptyState(text: String = stringResource(KMR.strings.stats_no_data)) {
    Text(
        text = text,
        modifier = Modifier.padding(16.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NoticeCard(text: String) = NoticeCard(listOf(text))

/** Groups caveats that always apply together into one card instead of a stack of banners. */
@Composable
private fun NoticeCard(lines: List<String>) {
    if (lines.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                lines.forEach { line ->
                    Text(
                        text = line,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailCard(
    title: String,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // A detail card is tall — the character card alone runs to several screens of occurrences — so a
    // saturated primaryContainer fill floods the view and reads as a selection highlight rather than
    // as a panel. A plain surface with an outline marks the same boundary without shouting.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
    val value: String?,
    val label: String,
    val icon: ImageVector,
    val destination: StatsTab,
    /** What a screen reader says where the tile abbreviates to fit. Defaults to the tile text. */
    val spokenValue: String? = null,
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
                // The tile is abbreviated to fit; the screen reader gets the unshortened wording,
                // and an absent measure is spoken as a word rather than as a lone dash.
                val spokenValue = data.spokenValue
                    ?: data.value
                    ?: stringResource(KMR.strings.stats_unavailable)
                // A measure with no value shows an em dash rather than the word "Unavailable":
                // at title size that word dominates the tile and reads as a failure, where the
                // dash is the ordinary typographic "nothing here yet".
                Text(
                    data.value ?: "—",
                    modifier = Modifier.semantics { contentDescription = spokenValue },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (data.value == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        Color.Unspecified
                    },
                )
                Text(
                    data.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    // The tile is a fixed 116dp, so a long or translated label would otherwise be cut
                    // mid-word with no sign that anything was dropped.
                    overflow = TextOverflow.Ellipsis,
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

/**
 * A heading for a group of rows that breaks down a metric already shown above it, so it has to read
 * as subordinate to the nearest [SectionTitle] rather than as a peer of it.
 */
@Composable
private fun SubsectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * A caveat or explanation attached to the rows above it — a caption, not a metric. Subdued so it reads
 * as annotation rather than as one more value in the column it follows.
 */
@Composable
private fun NoteText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

/**
 * A script name for display. Indexing stores Java's raw [Character.UnicodeScript] name, so the value
 * is SCREAMING_SNAKE_CASE and covers far more scripts than the six the filter chips offer. The six get
 * the same localized labels the chips use, so the detail card and the filter above it agree; anything
 * else is title-cased rather than shown as a raw enum.
 */
@Composable
private fun unicodeScriptLabel(rawScript: String): String {
    val known = AnalyticsCharacterScript.entries.firstOrNull { it.name == rawScript }
    if (known != null && known != AnalyticsCharacterScript.OTHER) {
        return characterScriptLabel(known)
    }
    return rawScript.split('_').joinToString(" ") { word ->
        word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
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
    AnalyticsTitleSort.MINING_RATE -> stringResource(KMR.strings.stats_sort_mining)
    AnalyticsTitleSort.PROGRESS -> stringResource(KMR.strings.stats_sort_progress)
}

/**
 * The measure a title list is ordered by, labelled and formatted, so a row can show the number it
 * was ranked on. Null for the two orderings that rank on something the row already shows: the
 * recency sort on the last-active line, and the alphabetical sort on the title itself.
 *
 * Label and value come from one `when` so they cannot disagree about which sorts have a row metric.
 */
@Composable
private fun titleSortMetric(
    title: AnalyticsTitleRow,
    metric: CharacterMetric,
    sort: AnalyticsTitleSort,
): Pair<String, String>? = when (sort) {
    AnalyticsTitleSort.MOST_RECENT, AnalyticsTitleSort.ALPHABETICAL -> null
    // Time and characters are already on every row, so those sorts add the session count
    // instead: it is the context that makes the two visible figures comparable between titles.
    AnalyticsTitleSort.MOST_TIME, AnalyticsTitleSort.MOST_CHARACTERS ->
        stringResource(KMR.strings.stats_sessions) to formatCount(title.metrics.sessions.value)
    AnalyticsTitleSort.READING_SPEED -> stringResource(KMR.strings.stats_reading_speed) to
        (
            title.metrics.readingSpeedPerHour(metric)
                ?.let { stringResource(KMR.strings.stats_per_hour, formatRate(it)) }
                ?: stringResource(KMR.strings.stats_unavailable)
            )
    AnalyticsTitleSort.MINING_RATE -> stringResource(KMR.strings.stats_mining_rate) to
        (
            title.metrics.miningRatePerTenThousandGrossCharacters()
                ?.let(::formatDecimal)
                ?: stringResource(KMR.strings.stats_unavailable)
            )
    AnalyticsTitleSort.PROGRESS -> stringResource(KMR.strings.stats_progress) to
        (title.progress?.let(::formatPercent) ?: stringResource(KMR.strings.stats_unavailable))
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
private fun goalMetricLabel(value: String): String = when (value) {
    "active_time_ms" -> stringResource(KMR.strings.stats_active_time)
    "gross_characters" -> stringResource(KMR.strings.stats_basis_gross)
    "unique_source_characters" -> stringResource(KMR.strings.stats_basis_unique)
    "net_characters" -> stringResource(KMR.strings.stats_basis_net)
    SOURCE_UNITS_GOAL_METRIC -> stringResource(KMR.strings.stats_source_units)
    "sessions" -> stringResource(KMR.strings.stats_sessions)
    "cards" -> stringResource(KMR.strings.stats_cards_created)
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

/**
 * Why a report is unavailable or degraded. An available capability needs no explanation — the state
 * already said it — so it returns null and the row stands alone.
 */
@Composable
private fun ankiCapabilityReason(value: AnalyticsAnkiCapabilityReason): String? = when (value) {
    AnalyticsAnkiCapabilityReason.AVAILABLE -> null
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

/** Whether a bucket holds anything worth drawing, as opposed to merely existing. */
private fun AnalyticsActivityTotals.hasActivity(metric: CharacterMetric): Boolean =
    characterValue(metric) > 0L || activeDurationMillis > 0L

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
    StatsTrendMetric.CARDS -> cardsCreated.value
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
    StatsTrendMetric.CARDS -> stringResource(KMR.strings.stats_cards_created)
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

/**
 * A grouped count with its noun in agreement, for the prose lines where "1 days" would read as a
 * bug. Plural selection takes an `Int` while these counts are already `Int`-sized, so the quantity
 * and the substituted number describe the same value.
 */
@Composable
private fun pluralCount(resource: PluralsResource, count: Int): String =
    pluralStringResource(resource, count, formatCount(count.toLong()))

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

/**
 * A duration short enough for a half-width tile. The prose form ("39 hours 48 minutes") is
 * correct but does not fit, and a truncated hero value ("39 hours 48 …") loses the minutes
 * entirely; the abbreviated units keep both numbers.
 */
@Composable
private fun formatDurationCompact(millis: Long): String {
    val parts = statsDurationParts(millis)
    if (parts.lessThanSecond) {
        return stringResource(KMR.strings.stats_duration_less_than_second)
    }
    return when {
        parts.hours > 0L -> stringResource(
            KMR.strings.stats_duration_compact_hours_minutes,
            parts.hours,
            parts.minutes,
        )
        parts.minutes > 0L -> stringResource(KMR.strings.stats_duration_compact_minutes, parts.minutes)
        else -> stringResource(KMR.strings.stats_duration_compact_seconds, parts.seconds)
    }
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
    start: ImmersionLocalDate,
    endInclusive: ImmersionLocalDate,
): String = if (start == endInclusive) {
    formatLocalDate(start)
} else {
    stringResource(
        KMR.strings.stats_date_range,
        formatLocalDate(start),
        formatLocalDate(endInclusive),
    )
}

private fun formatLocalDate(date: ImmersionLocalDate): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(date.toLocalDate())

private fun String.optionalPositiveLong(): Long? {
    if (isBlank()) return null
    return toLongOrNull()?.takeIf { it > 0 }
        ?: throw IllegalArgumentException("Expected a positive integer")
}

private val GOAL_MULTIPLIER_OPTIONS = listOf(0.0, 0.5, 1.0)
private const val DEFAULT_GOAL_WINDOW_DAYS = 30L
private const val MAX_COMPACT_GOALS = 3
private const val MAX_GOAL_CHECK_IN_NOTE_LENGTH = 500
private const val SESSION_RELINK_TARGET_LIMIT = 20
