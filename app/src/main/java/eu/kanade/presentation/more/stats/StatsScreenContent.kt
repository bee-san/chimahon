package eu.kanade.presentation.more.stats

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.dictionary.ProcessTextLookupActivity
import eu.kanade.tachiyomi.ui.stats.ACTIVE_TIME_GOAL_METRIC
import eu.kanade.tachiyomi.ui.stats.SOURCE_UNITS_GOAL_METRIC
import eu.kanade.tachiyomi.ui.stats.StatsComparisonDirection
import eu.kanade.tachiyomi.ui.stats.StatsGoalDisplayKind
import eu.kanade.tachiyomi.ui.stats.StatsGoalEditorValues
import eu.kanade.tachiyomi.ui.stats.StatsGoalForecastPresentation
import eu.kanade.tachiyomi.ui.stats.StatsGoalKind
import eu.kanade.tachiyomi.ui.stats.StatsSourceNavigator
import eu.kanade.tachiyomi.ui.stats.activeTimeComparison
import eu.kanade.tachiyomi.ui.stats.ankiPresentationCapabilityState
import eu.kanade.tachiyomi.ui.stats.durationMillis
import eu.kanade.tachiyomi.ui.stats.enabledStatsTabs
import eu.kanade.tachiyomi.ui.stats.overviewIndexedGrowthMetricValue
import eu.kanade.tachiyomi.ui.stats.statsDurationParts
import eu.kanade.tachiyomi.ui.stats.statsGoalDisplayValue
import eu.kanade.tachiyomi.ui.stats.statsGoalForecastPresentation
import eu.kanade.tachiyomi.ui.stats.statsOccurrenceKey
import eu.kanade.tachiyomi.ui.stats.suggestedStatsGoalWeekdayMultipliers
import eu.kanade.tachiyomi.ui.stats.toStatsGoalEditorValues
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.domain.immersion.model.AnalyticsActivityTotals
import tachiyomi.domain.immersion.model.AnalyticsBucketScale
import tachiyomi.domain.immersion.model.AnalyticsCharacterRow
import tachiyomi.domain.immersion.model.AnalyticsDataQuality
import tachiyomi.domain.immersion.model.AnalyticsGoalProgress
import tachiyomi.domain.immersion.model.AnalyticsOverview
import tachiyomi.domain.immersion.model.AnalyticsResult
import tachiyomi.domain.immersion.model.AnalyticsSessionDetail
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.AnalyticsSourceOccurrence
import tachiyomi.domain.immersion.model.AnalyticsTemporalActivity
import tachiyomi.domain.immersion.model.AnalyticsTitleRow
import tachiyomi.domain.immersion.model.AnalyticsTitleSeriesSelection
import tachiyomi.domain.immersion.model.AnalyticsTitleTrendSeries
import tachiyomi.domain.immersion.model.AnalyticsTitleTrends
import tachiyomi.domain.immersion.model.AnalyticsTrendPoint
import tachiyomi.domain.immersion.model.AnalyticsTrends
import tachiyomi.domain.immersion.model.AnalyticsVocabularyFirstSeen
import tachiyomi.domain.immersion.model.AnalyticsWordRow
import tachiyomi.domain.immersion.model.AnkiMatchConfidence
import tachiyomi.domain.immersion.model.CapabilityState
import tachiyomi.domain.immersion.model.CharacterMetric
import tachiyomi.domain.immersion.model.EventType
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.MaturityTier
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.ProvenanceState
import tachiyomi.domain.immersion.model.ReadingMetrics
import tachiyomi.domain.immersion.model.SessionStatus
import tachiyomi.domain.immersion.model.SourceKind
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
    onTrendScaleSelect: (AnalyticsBucketScale) -> Unit,
    onTrendMetricSelect: (StatsTrendMetric) -> Unit,
    onTitleTrendSelectionSelect: (AnalyticsTitleSeriesSelection) -> Unit,
    onTitleSortSelect: (AnalyticsSort) -> Unit,
    onVocabularySortSelect: (AnalyticsSort) -> Unit,
    onCharacterSortSelect: (AnalyticsSort) -> Unit,
    onTitleSearch: (String) -> Unit,
    onVocabularySearch: (String) -> Unit,
    onCharacterSearch: (String) -> Unit,
    onSourceSearch: (String) -> Unit,
    onTitleSelect: (AnalyticsTitleRow?) -> Unit,
    onTitleCaptureExclusionChange: (Boolean) -> Unit,
    onWordSelect: (AnalyticsWordRow?) -> Unit,
    onCharacterSelect: (AnalyticsCharacterRow?) -> Unit,
    onSessionSelect: (ImmersionSession?) -> Unit,
    onSessionDelete: (ImmersionSession) -> Unit,
    onLoadMoreVocabulary: () -> Unit,
    onLoadMoreWordOccurrences: () -> Unit,
    onLoadMoreCharacters: () -> Unit,
    onLoadMoreCharacterOccurrences: () -> Unit,
    onLoadMoreCharacterContainingWords: () -> Unit,
    onLoadMoreSourceSearch: () -> Unit,
    onLoadMoreSessions: () -> Unit,
    onSaveGoal: (StatsGoalEditorValues, ImmersionGoal?) -> Boolean,
    onArchiveGoal: (ImmersionGoal) -> Unit,
    onCheckInGoal: (String) -> Unit,
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
            )
            StatsTab.ACTIVITY -> ActivityTab(
                state,
                onTrendScaleSelect,
                onTrendMetricSelect,
                onTitleTrendSelectionSelect,
            )
            StatsTab.TITLES -> TitlesTab(
                state,
                onTitleSortSelect,
                onTitleSearch,
                onTitleSelect,
                onTitleCaptureExclusionChange,
            )
            StatsTab.VOCABULARY -> VocabularyTab(
                state,
                onVocabularySortSelect,
                onVocabularySearch,
                onWordSelect,
                onLoadMoreVocabulary,
                onLoadMoreWordOccurrences,
            )
            StatsTab.CHARACTERS -> CharactersTab(
                state,
                onCharacterSortSelect,
                onCharacterSearch,
                onCharacterSelect,
                onLoadMoreCharacters,
                onLoadMoreCharacterOccurrences,
                onLoadMoreCharacterContainingWords,
                onContainingWordSelect = { word ->
                    onTabSelect(StatsTab.VOCABULARY)
                    onWordSelect(word)
                },
            )
            StatsTab.SESSIONS -> SessionsTab(
                state,
                onSessionSelect,
                onSessionDelete,
                onSourceSearch,
                onLoadMoreSourceSearch,
                onLoadMoreSessions,
            )
            StatsTab.GOALS -> GoalsTab(
                state,
                onSaveGoal,
                onArchiveGoal,
                onCheckInGoal,
            )
            StatsTab.ANKI -> AnkiTab(state)
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
) {
    val section = state.sections.overview
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionFrame(section) { result ->
                OverviewSummary(
                    result,
                    state.filter.characterMetric,
                    state.ankiEnabled,
                    onTabSelect,
                )
            }
        }
        item {
            SectionFrame(state.sections.heatmap) { result ->
                ActivityHeatmap(
                    trends = result.value,
                    metric = state.filter.characterMetric,
                )
            }
        }
        item {
            SectionFrame(state.sections.sessions) { result ->
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
        item {
            val quality = section.value?.quality
            if (quality != null) DataQualityCard(quality)
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
                            Spacer(Modifier.size(14.dp))
                        } else {
                            val value = point.metrics.characterValue(metric)
                            val fraction = value.toFloat() / maximum.toFloat()
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
                                    .size(14.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primary.copy(
                                            alpha = 0.12f + (0.88f * fraction),
                                        ),
                                        shape = RoundedCornerShape(3.dp),
                                    )
                                    .semantics { contentDescription = description },
                            )
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
            SectionFrame(state.sections.trends) { result ->
                TrendsContent(
                    trends = result.value,
                    metric = state.filter.characterMetric,
                    trendMetric = state.trendMetric,
                )
            }
        }
        item {
            SectionFrame(state.sections.temporalActivity) { result ->
                TemporalPatternsContent(
                    activity = result.value,
                    metric = state.filter.characterMetric,
                )
            }
        }
        item {
            SectionFrame(state.sections.titleTrends) { result ->
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
    onSortSelect: (AnalyticsSort) -> Unit,
    onSearch: (String) -> Unit,
    onSelect: (AnalyticsTitleRow?) -> Unit,
    onCaptureExclusionChange: (Boolean) -> Unit,
) {
    val selected = state.selection.title
    val rows = state.sections.titles.value?.value.orEmpty()
        .filter { it.displayTitle.contains(state.titleSearch, ignoreCase = true) }
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
                    AnalyticsSort.MOST_RECENT,
                    AnalyticsSort.MOST_TIME,
                    AnalyticsSort.MOST_CHARACTERS,
                    AnalyticsSort.ALPHABETICAL,
                ),
            )
        }
        if (selected != null) {
            item {
                TitleDetail(
                    title = selected,
                    metric = state.filter.characterMetric,
                    captureExcluded = state.details.titleCaptureExcluded,
                    onCaptureExclusionChange = onCaptureExclusionChange,
                    onClose = { onSelect(null) },
                )
            }
        }
        if (state.sections.titles.error && rows.isEmpty()) {
            item { SectionError() }
        } else if (rows.isEmpty() && !state.sections.titles.refreshing) {
            item { EmptyState() }
        } else {
            items(rows, key = { it.titleId.value }) { title ->
                TitleRow(title, state.filter.characterMetric) { onSelect(title) }
            }
        }
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
) {
    val result = state.sections.vocabulary.value
    val rows = result?.value?.items.orEmpty()
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
            )
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
            SectionFrame(state.sections.vocabularyGrowth) { result ->
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
        if (state.sections.vocabulary.error && rows.isEmpty()) {
            item { SectionError() }
        } else if (rows.isEmpty() && !state.sections.vocabulary.refreshing) {
            item { EmptyState() }
        } else {
            items(rows, key = { it.id }) { word ->
                WordRow(word) { onSelect(word) }
            }
            if (result?.value?.nextOffset != null) {
                item { LoadMoreButton(onLoadMore) }
            }
        }
    }
}

@Composable
private fun CharactersTab(
    state: StatsScreenState.Success,
    onSortSelect: (AnalyticsSort) -> Unit,
    onSearch: (String) -> Unit,
    onSelect: (AnalyticsCharacterRow?) -> Unit,
    onLoadMore: () -> Unit,
    onLoadMoreOccurrences: () -> Unit,
    onLoadMoreContainingWords: () -> Unit,
    onContainingWordSelect: (AnalyticsWordRow) -> Unit,
) {
    val result = state.sections.characters.value
    val rows = result?.value?.items.orEmpty()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(72.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
                ),
            )
        }
        state.selection.character?.let { character ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                CharacterDetail(
                    character = character,
                    occurrences = state.details.characterOccurrences,
                    containingWords = state.details.characterContainingWords,
                    onClose = { onSelect(null) },
                    onContainingWordSelect = onContainingWordSelect,
                    onLoadMoreOccurrences = onLoadMoreOccurrences,
                    onLoadMoreContainingWords = onLoadMoreContainingWords,
                )
            }
        }
        if (state.sections.characters.error && rows.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionError() }
        } else if (rows.isEmpty() && !state.sections.characters.refreshing) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyState() }
        } else {
            items(rows, key = { it.codePoint.value }) { character ->
                CharacterCell(character) { onSelect(character) }
            }
            if (result?.value?.nextOffset != null) {
                item(span = { GridItemSpan(maxLineSpan) }) { LoadMoreButton(onLoadMore) }
            }
        }
    }
}

@Composable
private fun SessionsTab(
    state: StatsScreenState.Success,
    onSelect: (ImmersionSession?) -> Unit,
    onDelete: (ImmersionSession) -> Unit,
    onSourceSearch: (String) -> Unit,
    onLoadMoreSourceSearch: () -> Unit,
    onLoadMore: () -> Unit,
) {
    val result = state.sections.sessions.value
    val sessions = result?.value?.items.orEmpty()
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
        val sourceResults = state.details.sourceSearch.value?.value?.items.orEmpty()
        if (state.sourceSearch.isNotBlank()) {
            if (sourceResults.isNotEmpty()) {
                items(sourceResults, key = { it.statsOccurrenceKey() }) {
                    SourceOccurrenceRow(it)
                }
                if (
                    state.details.sourceSearch.value?.value?.nextOffset != null &&
                    !state.details.sourceSearch.error
                ) {
                    item { LoadMoreButton(onLoadMoreSourceSearch) }
                }
                if (state.details.sourceSearch.error) {
                    item { SectionError() }
                }
            } else if (state.details.sourceSearch.error) {
                item { SectionError() }
            } else if (!state.details.sourceSearch.refreshing) {
                item { EmptyState() }
            }
            item { HorizontalDivider() }
        }
        state.selection.session?.let { session ->
            item {
                SessionDetail(
                    fallback = session,
                    detail = state.details.session,
                    onClose = { onSelect(null) },
                    onDelete = { onDelete(session) },
                )
            }
        }
        if (state.sections.sessions.error && sessions.isEmpty()) {
            item { SectionError() }
        } else if (sessions.isEmpty() && !state.sections.sessions.refreshing) {
            item { EmptyState() }
        } else {
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
    onCheckIn: (String) -> Unit,
) {
    val goals = state.sections.goals.value?.value.orEmpty()
    var showEditor by remember { mutableStateOf(false) }
    var editingGoal by remember { mutableStateOf<ImmersionGoal?>(null) }
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
        if (state.sections.goals.error && goals.isEmpty()) {
            item { SectionError() }
        } else if (goals.isEmpty() && !state.sections.goals.refreshing) {
            item { Text(stringResource(KMR.strings.stats_no_goals)) }
        } else {
            items(goals, key = { it.goal.id }) { goal ->
                GoalCard(
                    goal = goal,
                    onEdit = {
                        editingGoal = goal.goal
                        showEditor = true
                    },
                    onArchive = { onArchive(goal.goal) },
                    onCheckIn = { onCheckIn(goal.goal.id) },
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
}

@Composable
private fun AnkiTab(state: StatsScreenState.Success) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionFrame(state.sections.anki) { result ->
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
                        Text(
                            stringResource(
                                KMR.strings.stats_anki_items,
                                formatCount(snapshot.itemCount.toLong()),
                                formatCount(snapshot.noteCount.toLong()),
                            ),
                        )
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
                    if (summary.missingHighFrequencyCharacters.isNotEmpty()) {
                        SectionTitle(stringResource(KMR.strings.stats_anki_missing_characters))
                        summary.missingHighFrequencyCharacters.take(10).forEach { character ->
                            MetricLine(
                                character.rendered,
                                character.frequencyRank?.let(::formatCount) ?: "—",
                            )
                        }
                    }
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
    metric: CharacterMetric,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title.displayTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(
                    KMR.strings.stats_media_and_language,
                    mediaLabel(title.mediaKind),
                    title.languageTag?.value ?: stringResource(KMR.strings.stats_unknown),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MetricLine(stringResource(KMR.strings.stats_active_time), formatDuration(title.metrics.activeTime.value))
            MetricLine(characterMetricLabel(metric), formatCount(title.metrics.characterValue(metric)))
            MetricLine(stringResource(KMR.strings.stats_sessions), formatCount(title.metrics.sessions.value))
            Text(
                stringResource(KMR.strings.stats_last_active, formatLocalDate(title.lastActiveDate)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TitleDetail(
    title: AnalyticsTitleRow,
    metric: CharacterMetric,
    captureExcluded: StatsLoadable<Boolean>,
    onCaptureExclusionChange: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    DetailCard(
        title = stringResource(KMR.strings.stats_title_detail),
        onClose = onClose,
    ) {
        Text(title.displayTitle, style = MaterialTheme.typography.headlineSmall)
        MetricLine(stringResource(KMR.strings.stats_active_time), formatDuration(title.metrics.activeTime.value))
        MetricLine(characterMetricLabel(metric), formatCount(title.metrics.characterValue(metric)))
        MetricLine(
            stringResource(KMR.strings.stats_reading_speed),
            title.metrics.readingSpeedPerHour(metric)?.let(::formatRate)
                ?: stringResource(KMR.strings.stats_unavailable),
        )
        MetricLine(stringResource(KMR.strings.stats_sessions), formatCount(title.metrics.sessions.value))
        MetricLine(stringResource(KMR.strings.stats_lookups), formatCount(title.metrics.successfulLookups.value))
        MetricLine(stringResource(KMR.strings.stats_cards_created), formatCount(title.metrics.cardsCreated.value))
        MetricLine(stringResource(KMR.strings.stats_unique_words), formatCount(title.metrics.uniqueWords.value))
        MetricLine(stringResource(KMR.strings.stats_new_words), formatCount(title.metrics.newWords.value))
        Text(stringResource(KMR.strings.stats_first_active, formatLocalDate(title.firstActiveDate)))
        Text(stringResource(KMR.strings.stats_last_active, formatLocalDate(title.lastActiveDate)))
        title.progress?.let {
            MetricLine(stringResource(KMR.strings.stats_progress), formatPercent(it))
        } ?: MetricLine(
            stringResource(KMR.strings.stats_progress),
            stringResource(KMR.strings.stats_unavailable),
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
private fun WordRow(word: AnalyticsWordRow, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(word.headword, style = MaterialTheme.typography.titleMedium)
                word.reading?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
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
        word.matchConfidence?.let {
            Text(stringResource(KMR.strings.stats_match_confidence, matchConfidenceLabel(it)))
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
private fun CharacterCell(character: AnalyticsCharacterRow, onClick: () -> Unit) {
    val description = stringResource(
        KMR.strings.stats_character_cell_description,
        character.rendered,
        character.unicodeName
            ?: "U+%04X".format(Locale.ROOT, character.codePoint.value),
        pluralStringResource(
            KMR.plurals.stats_occurrence_count,
            character.occurrenceCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            formatCount(character.occurrenceCount),
        ),
    )
    Surface(
        modifier = Modifier
            .height(84.dp)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(character.rendered, style = MaterialTheme.typography.headlineMedium)
            Text(formatCount(character.occurrenceCount), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CharacterDetail(
    character: AnalyticsCharacterRow,
    occurrences: StatsLoadable<AnalyticsResult<tachiyomi.domain.immersion.model.AnalyticsPage<AnalyticsSourceOccurrence>>>,
    containingWords: StatsLoadable<AnalyticsResult<tachiyomi.domain.immersion.model.AnalyticsPage<AnalyticsWordRow>>>,
    onClose: () -> Unit,
    onContainingWordSelect: (AnalyticsWordRow) -> Unit,
    onLoadMoreOccurrences: () -> Unit,
    onLoadMoreContainingWords: () -> Unit,
) {
    DetailCard(stringResource(KMR.strings.stats_character_detail), onClose) {
        Text(character.rendered, style = MaterialTheme.typography.displayMedium)
        Text(
            stringResource(
                KMR.strings.stats_unicode_code_point,
                "U+%04X".format(Locale.ROOT, character.codePoint.value),
            ),
        )
        character.unicodeName?.let { Text(stringResource(KMR.strings.stats_unicode_name, it)) }
        Text(stringResource(KMR.strings.stats_unicode_script, character.unicodeScript))
        MetricLine(stringResource(KMR.strings.stats_occurrence_label), formatCount(character.occurrenceCount))
        MetricLine(stringResource(KMR.strings.stats_unique_words), formatCount(character.wordCount))
        MetricLine(stringResource(KMR.strings.stats_tab_titles), formatCount(character.titleCount))
        Text(stringResource(KMR.strings.stats_first_seen, formatInstant(character.firstSeenAtEpochMillis)))
        Text(stringResource(KMR.strings.stats_last_seen, formatInstant(character.lastSeenAtEpochMillis)))
        Text(stringResource(KMR.strings.stats_maturity, maturityLabel(character.maturity)))
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
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
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
                Text(
                    stringResource(
                        KMR.strings.stats_delete_session_warning,
                        formatDuration(session.activeDuration.value),
                        formatCount(session.grossCharacters.value),
                    ),
                )
            },
            confirmButton = {
                TextButton(
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unavailableMessage = stringResource(KMR.strings.stats_source_open_unavailable)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
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
                        stringResource(KMR.strings.stats_goal_edit_prospective),
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
private fun SearchAndSort(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    selectedSort: AnalyticsSort,
    onSortSelect: (AnalyticsSort) -> Unit,
    allowedSorts: List<AnalyticsSort>,
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
            label = sortLabel(selectedSort),
            options = allowedSorts,
            optionLabel = { sortLabel(it) },
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
private fun <T> SectionFrame(section: StatsLoadable<T>, content: @Composable (T) -> Unit) {
    when {
        section.value != null -> {
            content(section.value)
            if (section.error) SectionError()
        }
        section.refreshing -> Text(stringResource(KMR.strings.stats_loading_section))
        section.error -> SectionError()
        else -> EmptyState()
    }
}

@Composable
private fun SectionError() {
    NoticeCard(stringResource(KMR.strings.stats_section_failed))
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
private fun sortLabel(value: AnalyticsSort): String = when (value) {
    AnalyticsSort.MOST_RECENT -> stringResource(KMR.strings.stats_sort_recent)
    AnalyticsSort.MOST_TIME -> stringResource(KMR.strings.stats_sort_time)
    AnalyticsSort.MOST_CHARACTERS -> stringResource(KMR.strings.stats_sort_characters)
    AnalyticsSort.MOST_OCCURRENCES -> stringResource(KMR.strings.stats_sort_occurrences)
    AnalyticsSort.FIRST_SEEN -> stringResource(KMR.strings.stats_sort_first_seen)
    AnalyticsSort.ALPHABETICAL -> stringResource(KMR.strings.stats_sort_alphabetical)
    AnalyticsSort.FREQUENCY_RANK -> stringResource(KMR.strings.stats_sort_frequency)
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

private val GOAL_MULTIPLIER_OPTIONS = listOf(0.0, 0.5, 1.0)
private const val DEFAULT_GOAL_WINDOW_DAYS = 30L
