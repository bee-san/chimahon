package eu.kanade.presentation.more.stats

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalFireDepartment
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tachiyomi.domain.immersion.model.AnalyticsBucketScale
import tachiyomi.domain.immersion.model.AnalyticsCharacterRow
import tachiyomi.domain.immersion.model.AnalyticsDataQuality
import tachiyomi.domain.immersion.model.AnalyticsGoalProgress
import tachiyomi.domain.immersion.model.AnalyticsOverview
import tachiyomi.domain.immersion.model.AnalyticsResult
import tachiyomi.domain.immersion.model.AnalyticsSessionDetail
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.AnalyticsSourceOccurrence
import tachiyomi.domain.immersion.model.AnalyticsTitleRow
import tachiyomi.domain.immersion.model.AnalyticsTrendPoint
import tachiyomi.domain.immersion.model.AnalyticsTrends
import tachiyomi.domain.immersion.model.AnalyticsWordRow
import tachiyomi.domain.immersion.model.CapabilityState
import tachiyomi.domain.immersion.model.CharacterMetric
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.ProvenanceState
import tachiyomi.domain.immersion.model.ReadingMetrics
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun StatsScreenContent(
    state: StatsScreenState.Success,
    paddingValues: PaddingValues,
    onTabSelect: (StatsTab) -> Unit,
    onRangeSelect: (StatsRangePreset) -> Unit,
    onPeriodMove: (Int) -> Unit,
    onCustomRange: (String, String) -> Boolean,
    onMediaSelect: (MediaKind?) -> Unit,
    onProfileSelect: (String?) -> Unit,
    onCharacterMetricSelect: (CharacterMetric) -> Unit,
    onIncludeLegacyChange: (Boolean) -> Unit,
    onIncludeRereadsChange: (Boolean) -> Unit,
    onTrendScaleSelect: (AnalyticsBucketScale) -> Unit,
    onTitleSortSelect: (AnalyticsSort) -> Unit,
    onVocabularySortSelect: (AnalyticsSort) -> Unit,
    onCharacterSortSelect: (AnalyticsSort) -> Unit,
    onTitleSearch: (String) -> Unit,
    onVocabularySearch: (String) -> Unit,
    onCharacterSearch: (String) -> Unit,
    onSourceSearch: (String) -> Unit,
    onTitleSelect: (AnalyticsTitleRow?) -> Unit,
    onWordSelect: (AnalyticsWordRow?) -> Unit,
    onCharacterSelect: (AnalyticsCharacterRow?) -> Unit,
    onSessionSelect: (ImmersionSession?) -> Unit,
    onLoadMoreVocabulary: () -> Unit,
    onLoadMoreCharacters: () -> Unit,
    onLoadMoreSessions: () -> Unit,
    onCreateGoal: (String, Double, Boolean) -> Boolean,
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
            onProfileSelect = onProfileSelect,
            onCharacterMetricSelect = onCharacterMetricSelect,
            onIncludeLegacyChange = onIncludeLegacyChange,
            onIncludeRereadsChange = onIncludeRereadsChange,
            onDefinitions = { showDefinitions = true },
        )
        if (state.isRefreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        StatsTabs(state.selectedTab, onTabSelect)
        when (state.selectedTab) {
            StatsTab.OVERVIEW -> OverviewTab(
                state = state,
                onTabSelect = onTabSelect,
                onSessionSelect = onSessionSelect,
            )
            StatsTab.ACTIVITY -> ActivityTab(state, onTrendScaleSelect)
            StatsTab.TITLES -> TitlesTab(
                state,
                onTitleSortSelect,
                onTitleSearch,
                onTitleSelect,
            )
            StatsTab.VOCABULARY -> VocabularyTab(
                state,
                onVocabularySortSelect,
                onVocabularySearch,
                onWordSelect,
                onLoadMoreVocabulary,
            )
            StatsTab.CHARACTERS -> CharactersTab(
                state,
                onCharacterSortSelect,
                onCharacterSearch,
                onCharacterSelect,
                onLoadMoreCharacters,
            )
            StatsTab.SESSIONS -> SessionsTab(
                state,
                onSessionSelect,
                onSourceSearch,
                onLoadMoreSessions,
            )
            StatsTab.GOALS -> GoalsTab(
                state,
                onCreateGoal,
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
                    if (state.filter.titleId == null) {
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
private fun StatsTabs(selected: StatsTab, onSelect: (StatsTab) -> Unit) {
    PrimaryScrollableTabRow(
        selectedTabIndex = selected.ordinal,
        edgePadding = 8.dp,
        divider = {},
    ) {
        StatsTab.entries.forEach { tab ->
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
                    onTabSelect,
                )
            }
        }
        item {
            SectionFrame(state.sections.sessions) { result ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(KMR.strings.stats_recent_sessions))
                    result.value.items.take(3).forEach { session ->
                        SessionRow(session) { onSessionSelect(session) }
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
private fun OverviewSummary(
    result: AnalyticsResult<AnalyticsOverview>,
    metric: CharacterMetric,
    onTabSelect: (StatsTab) -> Unit,
) {
    val overview = result.value
    val metrics = overview.comparison.current
    val previous = overview.comparison.previous
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
                formatCount(metrics.newWords.value),
                stringResource(KMR.strings.stats_new_words),
                Icons.Outlined.Translate,
                StatsTab.VOCABULARY,
            ),
            DashboardMetric(
                formatCount(metrics.newCharacters.value),
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
                        onClick = { onTabSelect(card.destination) },
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
        if (previous != null) {
            Text(
                text = stringResource(
                    KMR.strings.stats_change_same,
                    formatDuration(previous.activeTime.value),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ComparisonCard(overview: AnalyticsOverview) {
    val ratio = overview.comparison.activeTimeChangeRatio
    val previous = formatDuration(overview.comparison.previous?.activeTime?.value ?: 0)
    val text = when {
        ratio == null -> stringResource(KMR.strings.stats_change_same, previous)
        ratio > 0 -> stringResource(
            KMR.strings.stats_change_up,
            formatPercent(abs(ratio)),
            previous,
        )
        ratio < 0 -> stringResource(
            KMR.strings.stats_change_down,
            formatPercent(abs(ratio)),
            previous,
        )
        else -> stringResource(KMR.strings.stats_change_same, previous)
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun ActivityTab(
    state: StatsScreenState.Success,
    onTrendScaleSelect: (AnalyticsBucketScale) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            SectionFrame(state.sections.trends) { result ->
                TrendsContent(result.value, state.filter.characterMetric)
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
private fun TrendsContent(trends: AnalyticsTrends, metric: CharacterMetric) {
    val points = trends.points
    val values = points.map { it.metrics.characterValue(metric) }
    val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val total = values.sum()
    val barColor = MaterialTheme.colorScheme.primary
    val summary = stringResource(
        KMR.strings.stats_activity_chart_summary,
        points.size,
        formatCount(total),
        formatCount(max),
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
                val height = size.height * value.toFloat() / max.toFloat()
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(index * spacing + (spacing - width) / 2, size.height - height),
                    size = Size(width, height.coerceAtLeast(2.dp.toPx())),
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
        movingAverage(points, metric).takeLast(10).forEach { (point, average) ->
            MetricLine(point.range.endInclusive.toString(), formatRate(average))
        }
    }
}

@Composable
private fun TitlesTab(
    state: StatsScreenState.Success,
    onSortSelect: (AnalyticsSort) -> Unit,
    onSearch: (String) -> Unit,
    onSelect: (AnalyticsTitleRow?) -> Unit,
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
            item { TitleDetail(selected) { onSelect(null) } }
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
                    unique = it.value.items.size.toLong(),
                    quality = it.quality,
                )
            }
        }
        state.selection.word?.let { word ->
            item {
                WordDetail(
                    word = word,
                    occurrences = state.details.wordOccurrences,
                    onClose = { onSelect(null) },
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
                    onClose = { onSelect(null) },
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
    onSourceSearch: (String) -> Unit,
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
            if (state.details.sourceSearch.error) {
                item { SectionError() }
            } else if (sourceResults.isEmpty() && !state.details.sourceSearch.refreshing) {
                item { EmptyState() }
            } else {
                items(sourceResults, key = { "${it.sourceUnitId.value}:${it.sessionId.value}" }) {
                    SourceOccurrenceRow(it)
                }
            }
            item { HorizontalDivider() }
        }
        state.selection.session?.let { session ->
            item {
                SessionDetail(
                    fallback = session,
                    detail = state.details.session,
                    onClose = { onSelect(null) },
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
    onCreate: (String, Double, Boolean) -> Boolean,
    onArchive: (ImmersionGoal) -> Unit,
    onCheckIn: (String) -> Unit,
) {
    val goals = state.sections.goals.value?.value.orEmpty()
    var showCreate by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Button(onClick = { showCreate = true }) {
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
                    onArchive = { onArchive(goal.goal) },
                    onCheckIn = { onCheckIn(goal.goal.id) },
                )
            }
        }
    }
    if (showCreate) {
        GoalEditorDialog(
            onDismiss = { showCreate = false },
            onCreate = { metric, target, daily ->
                if (onCreate(metric, target, daily)) {
                    showCreate = false
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
                            capabilityLabel(snapshot.capabilityState),
                        )
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
                            MetricLine(tier.name, formatCount(count))
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
                stringResource(KMR.strings.stats_last_active, title.lastActiveDate.toString()),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TitleDetail(title: AnalyticsTitleRow, onClose: () -> Unit) {
    DetailCard(
        title = stringResource(KMR.strings.stats_title_detail),
        onClose = onClose,
    ) {
        Text(title.displayTitle, style = MaterialTheme.typography.headlineSmall)
        MetricLine(stringResource(KMR.strings.stats_active_time), formatDuration(title.metrics.activeTime.value))
        MetricLine(stringResource(KMR.strings.stats_characters), formatCount(title.metrics.characters.gross.value))
        MetricLine(
            stringResource(KMR.strings.stats_reading_speed),
            title.metrics.readingSpeedPerHour(CharacterMetric.GROSS)?.let(::formatRate)
                ?: stringResource(KMR.strings.stats_unavailable),
        )
        MetricLine(stringResource(KMR.strings.stats_sessions), formatCount(title.metrics.sessions.value))
        MetricLine(stringResource(KMR.strings.stats_lookups), formatCount(title.metrics.successfulLookups.value))
        MetricLine(stringResource(KMR.strings.stats_cards_created), formatCount(title.metrics.cardsCreated.value))
        MetricLine(stringResource(KMR.strings.stats_unique_words), formatCount(title.metrics.uniqueWords.value))
        MetricLine(stringResource(KMR.strings.stats_new_words), formatCount(title.metrics.newWords.value))
        Text(stringResource(KMR.strings.stats_first_active, title.firstActiveDate.toString()))
        Text(stringResource(KMR.strings.stats_last_active, title.lastActiveDate.toString()))
        title.progress?.let {
            MetricLine(stringResource(KMR.strings.stats_progress), formatPercent(it))
        } ?: MetricLine(
            stringResource(KMR.strings.stats_progress),
            stringResource(KMR.strings.stats_unavailable),
        )
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
                    word.maturity.name.lowercase().replaceFirstChar(Char::titlecase),
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
) {
    DetailCard(stringResource(KMR.strings.stats_word_detail), onClose) {
        Text(word.headword, style = MaterialTheme.typography.headlineMedium)
        word.reading?.let { Text(stringResource(KMR.strings.stats_reading, it)) }
        word.partOfSpeech?.let { Text(stringResource(KMR.strings.stats_part_of_speech, it)) }
        MetricLine(
            stringResource(KMR.strings.stats_occurrences, ""),
            formatCount(word.occurrenceCount),
        )
        MetricLine(stringResource(KMR.strings.stats_tab_titles), formatCount(word.titleCount))
        Text(stringResource(KMR.strings.stats_first_seen, formatInstant(word.firstSeenAtEpochMillis)))
        Text(stringResource(KMR.strings.stats_last_seen, formatInstant(word.lastSeenAtEpochMillis)))
        Text(stringResource(KMR.strings.stats_maturity, word.maturity.name))
        word.matchConfidence?.let {
            Text(stringResource(KMR.strings.stats_match_confidence, it.name))
        }
        SectionTitle(stringResource(KMR.strings.stats_source_occurrences))
        SectionFrame(occurrences) { result ->
            if (result.value.items.isEmpty()) {
                EmptyState()
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    result.value.items.take(10).forEach { SourceOccurrenceRow(it) }
                }
            }
        }
    }
}

@Composable
private fun CharacterCell(character: AnalyticsCharacterRow, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .height(84.dp)
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
    onClose: () -> Unit,
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
        MetricLine(stringResource(KMR.strings.stats_characters), formatCount(character.occurrenceCount))
        MetricLine(stringResource(KMR.strings.stats_unique_words), formatCount(character.wordCount))
        MetricLine(stringResource(KMR.strings.stats_tab_titles), formatCount(character.titleCount))
        Text(stringResource(KMR.strings.stats_first_seen, formatInstant(character.firstSeenAtEpochMillis)))
        Text(stringResource(KMR.strings.stats_last_seen, formatInstant(character.lastSeenAtEpochMillis)))
        Text(stringResource(KMR.strings.stats_maturity, character.maturity.name))
        SectionTitle(stringResource(KMR.strings.stats_source_occurrences))
        SectionFrame(occurrences) { result ->
            if (result.value.items.isEmpty()) {
                EmptyState()
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    result.value.items.take(10).forEach { SourceOccurrenceRow(it) }
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
) {
    val session = detail.value?.value?.session ?: fallback
    DetailCard(stringResource(KMR.strings.stats_session_detail), onClose) {
        detail.value?.value?.displayTitle?.let {
            Text(it, style = MaterialTheme.typography.titleLarge)
        }
        Text(stringResource(KMR.strings.stats_started_at, formatInstant(session.startedAtEpochMillis)))
        Text(stringResource(KMR.strings.stats_session_status, session.status.name))
        Text(stringResource(KMR.strings.stats_profile, session.profileId))
        MetricLine(stringResource(KMR.strings.stats_active_time), formatDuration(session.activeDuration.value))
        MetricLine(stringResource(KMR.strings.stats_elapsed_time), formatDuration(session.elapsedDuration.value))
        MetricLine(stringResource(KMR.strings.stats_basis_gross), formatCount(session.grossCharacters.value))
        MetricLine(stringResource(KMR.strings.stats_basis_unique), formatCount(session.uniqueSourceCharacters.value))
        MetricLine(stringResource(KMR.strings.stats_basis_net), formatCount(session.netCharacters.value))
        MetricLine(stringResource(KMR.strings.stats_source_units), formatCount(session.sourceUnitCount.value))
        if (session.legacyImport) NoticeCard(stringResource(KMR.strings.stats_legacy))
        detail.value?.value?.let { value ->
            SectionTitle(stringResource(KMR.strings.stats_session_timeline))
            TimelineSummary(value)
            SectionTitle(stringResource(KMR.strings.stats_source_occurrences))
            if (value.sources.isEmpty()) {
                EmptyState()
            } else {
                value.sources.take(20).forEach { SourceOccurrenceRow(it) }
            }
        }
        if (detail.refreshing) Text(stringResource(KMR.strings.stats_loading_section))
        if (detail.error) SectionError()
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
    val color = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(summary, style = MaterialTheme.typography.bodySmall)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .semantics { contentDescription = summary },
        ) {
            if (detail.timeline.isEmpty()) return@Canvas
            val width = size.width / detail.timeline.size
            detail.timeline.forEachIndexed { index, bucket ->
                val height = size.height * bucket.grossCharacters.toFloat() / max.toFloat()
                drawRect(
                    color = color,
                    topLeft = Offset(index * width, size.height - height),
                    size = Size((width - 1.dp.toPx()).coerceAtLeast(1f), height.coerceAtLeast(2.dp.toPx())),
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
                    occurrence.sourceKind.name,
                    formatInstant(occurrence.occurredAtEpochMillis),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GoalCard(
    goal: AnalyticsGoalProgress,
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
            Text(goal.goal.type, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    KMR.strings.stats_goal_progress,
                    formatDecimal(goal.achieved),
                    formatDecimal(goal.targetToDate),
                ),
            )
            LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
            goal.pacePerDay?.let {
                Text(stringResource(KMR.strings.stats_goal_pace, formatDecimal(it)))
            }
            goal.projectedCompletionDate?.let {
                Text(stringResource(KMR.strings.stats_goal_projection, it.toString()))
            }
            goal.requiredPacePerActiveDay?.let {
                Text(stringResource(KMR.strings.stats_goal_required_pace, formatDecimal(it)))
            }
            goal.rollingSevenDayPace?.let {
                Text(stringResource(KMR.strings.stats_goal_rolling_seven, formatDecimal(it)))
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
                TextButton(onClick = onArchive) {
                    Text(stringResource(KMR.strings.stats_goal_archive))
                }
            }
        }
    }
}

@Composable
private fun GoalEditorDialog(
    onDismiss: () -> Unit,
    onCreate: (String, Double, Boolean) -> Boolean,
) {
    val metrics = listOf(
        "active_time_ms",
        "gross_characters",
        "unique_source_characters",
        "net_characters",
        "sessions",
        "lookups",
        "cards",
        "new_words",
        "new_characters",
        "manual",
    )
    var metric by remember { mutableStateOf(metrics.first()) }
    var target by remember { mutableStateOf("") }
    var daily by remember { mutableStateOf(true) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(KMR.strings.stats_goal_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterMenuChip(
                    label = goalMetricLabel(metric),
                    options = metrics,
                    optionLabel = { goalMetricLabel(it) },
                    onSelect = { metric = it },
                )
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text(stringResource(KMR.strings.stats_goal_target)) },
                    isError = invalid,
                    singleLine = true,
                )
                ToggleRow(
                    text = stringResource(KMR.strings.stats_goal_daily),
                    checked = daily,
                    onCheckedChange = { daily = it },
                )
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
                    invalid = !onCreate(metric, target.toDoubleOrNull() ?: Double.NaN, daily)
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
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.height(116.dp).clickable(onClick = onClick),
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
private fun InventoryCoverageCard(unique: Long, quality: AnalyticsDataQuality) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MetricLine(stringResource(KMR.strings.stats_unique_words), formatCount(unique))
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
    "sessions" -> stringResource(KMR.strings.stats_sessions)
    "lookups" -> stringResource(KMR.strings.stats_lookups)
    "cards" -> stringResource(KMR.strings.stats_cards_created)
    "new_words" -> stringResource(KMR.strings.stats_new_words)
    "new_characters" -> stringResource(KMR.strings.stats_new_characters)
    "manual" -> stringResource(KMR.strings.stats_goal_manual)
    else -> stringResource(KMR.strings.stats_unknown)
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

private fun ReadingMetrics.characterValue(metric: CharacterMetric): Long =
    characters.valueFor(metric)

private fun movingAverage(
    points: List<AnalyticsTrendPoint>,
    metric: CharacterMetric,
    window: Int = 7,
): List<Pair<AnalyticsTrendPoint, Double>> =
    points.mapIndexed { index, point ->
        val start = (index - window + 1).coerceAtLeast(0)
        val values = points.subList(start, index + 1).map { it.metrics.characterValue(metric) }
        point to values.average()
    }

private fun formatCount(value: Long): String = NumberFormat.getIntegerInstance().format(value)

private fun formatRate(value: Double): String = NumberFormat.getIntegerInstance().format(value.roundToInt())

private fun formatDecimal(value: Double): String =
    NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(value)

private fun formatPercent(value: Double): String =
    NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }.format(value)

private fun formatDuration(millis: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun formatInstant(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
