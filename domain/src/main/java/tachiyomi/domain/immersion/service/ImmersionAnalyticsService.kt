package tachiyomi.domain.immersion.service

import tachiyomi.domain.immersion.model.AnalyticsActivityTotals
import tachiyomi.domain.immersion.model.AnalyticsAnkiCapabilityReason
import tachiyomi.domain.immersion.model.AnalyticsAnkiReport
import tachiyomi.domain.immersion.model.AnalyticsAnkiReportCapability
import tachiyomi.domain.immersion.model.AnalyticsAnkiSummary
import tachiyomi.domain.immersion.model.AnalyticsBucketScale
import tachiyomi.domain.immersion.model.AnalyticsCharacterFilter
import tachiyomi.domain.immersion.model.AnalyticsCharacterRange
import tachiyomi.domain.immersion.model.AnalyticsCharacterRow
import tachiyomi.domain.immersion.model.AnalyticsCharacterSummary
import tachiyomi.domain.immersion.model.AnalyticsComparison
import tachiyomi.domain.immersion.model.AnalyticsEstimateConfidence
import tachiyomi.domain.immersion.model.AnalyticsEstimateUnit
import tachiyomi.domain.immersion.model.AnalyticsGoalProgress
import tachiyomi.domain.immersion.model.AnalyticsHourActivity
import tachiyomi.domain.immersion.model.AnalyticsInventoryMetrics
import tachiyomi.domain.immersion.model.AnalyticsOverview
import tachiyomi.domain.immersion.model.AnalyticsPage
import tachiyomi.domain.immersion.model.AnalyticsPeriod
import tachiyomi.domain.immersion.model.AnalyticsQueryDiagnostics
import tachiyomi.domain.immersion.model.AnalyticsQueryFamily
import tachiyomi.domain.immersion.model.AnalyticsResult
import tachiyomi.domain.immersion.model.AnalyticsSessionDetail
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.AnalyticsSourceOccurrence
import tachiyomi.domain.immersion.model.AnalyticsStreak
import tachiyomi.domain.immersion.model.AnalyticsTemporalActivity
import tachiyomi.domain.immersion.model.AnalyticsTitleCompletedUnit
import tachiyomi.domain.immersion.model.AnalyticsTitleCoverage
import tachiyomi.domain.immersion.model.AnalyticsTitleCoverageFilter
import tachiyomi.domain.immersion.model.AnalyticsTitleDayHighlight
import tachiyomi.domain.immersion.model.AnalyticsTitleDayHighlights
import tachiyomi.domain.immersion.model.AnalyticsTitleEstimate
import tachiyomi.domain.immersion.model.AnalyticsTitleFilter
import tachiyomi.domain.immersion.model.AnalyticsTitleMetadata
import tachiyomi.domain.immersion.model.AnalyticsTitleRow
import tachiyomi.domain.immersion.model.AnalyticsTitleSeriesSelection
import tachiyomi.domain.immersion.model.AnalyticsTitleSort
import tachiyomi.domain.immersion.model.AnalyticsTitleStateFilter
import tachiyomi.domain.immersion.model.AnalyticsTitleTrendSeries
import tachiyomi.domain.immersion.model.AnalyticsTitleTrends
import tachiyomi.domain.immersion.model.AnalyticsTitleUnitProgress
import tachiyomi.domain.immersion.model.AnalyticsTrendPoint
import tachiyomi.domain.immersion.model.AnalyticsTrends
import tachiyomi.domain.immersion.model.AnalyticsWeekdayActivity
import tachiyomi.domain.immersion.model.CapabilityState
import tachiyomi.domain.immersion.model.CharacterCoverage
import tachiyomi.domain.immersion.model.CharacterMetric
import tachiyomi.domain.immersion.model.CharacterVolume
import tachiyomi.domain.immersion.model.ImmersionDailyRollup
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionGoalAchievement
import tachiyomi.domain.immersion.model.ImmersionGoalCheckIn
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.ImmersionRollupRebuildResult
import tachiyomi.domain.immersion.model.LocalDateRange
import tachiyomi.domain.immersion.model.MaturityTier
import tachiyomi.domain.immersion.model.NetCharacterProgress
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.ReadingMetrics
import tachiyomi.domain.immersion.model.SessionCursor
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SessionPage
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.model.UnicodeCodePoint
import tachiyomi.domain.immersion.repository.ImmersionAnalyticsRepository
import tachiyomi.domain.immersion.repository.ImmersionGoalRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.DayOfWeek
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

class ImmersionAnalyticsService(
    private val analyticsRepository: ImmersionAnalyticsRepository,
    private val goalRepository: ImmersionGoalRepository,
    private val calendar: ImmersionAnalyticsCalendar = ImmersionAnalyticsCalendar(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val currentOffsetSeconds: () -> Int = { 0 },
) {
    suspend fun overview(filter: StatsFilter): AnalyticsResult<AnalyticsOverview> =
        measured(AnalyticsQueryFamily.OVERVIEW, filter) {
            val range = effectiveRange(filter)
            val currentRows = analyticsRepository.dailyRollups(range).filter(filter::matches)
            val currentFilter = filter.copy(dateRange = range, comparisonRange = null)
            val current = currentRows.sumMetrics().withInventory(
                analyticsRepository.inventoryMetrics(currentFilter),
            )
            val previousRange = filter.comparisonRange ?: calendar.previousEqualRange(range)
            val previousFilter = filter.copy(dateRange = previousRange, comparisonRange = null)
            val previous = analyticsRepository.dailyRollups(previousRange)
                .filter(previousFilter::matches)
                .sumMetrics()
                .withInventory(analyticsRepository.inventoryMetrics(previousFilter))
            val historyFilter = filter.copy(dateRange = null, comparisonRange = null)
            val activeDates = analyticsRepository.availableDateRange(historyFilter)
                ?.let { historyRange ->
                    analyticsRepository.dailyRollups(historyRange)
                        .filter(historyFilter::matches)
                        .filter { it.metrics.hasActivity() }
                        .mapTo(sortedSetOf()) { it.date }
                }
                .orEmpty()
            AnalyticsOverview(
                period = AnalyticsPeriod(
                    range = range,
                    isPartialCurrentDay = range.endInclusive ==
                        calendar.localDate(clock(), currentOffsetSeconds()),
                ),
                comparison = AnalyticsComparison(
                    current = current,
                    previous = previous,
                    activeTimeChangeRatio = ratioChange(
                        current.activeTime.value,
                        previous.activeTime.value,
                    ),
                    characterChangeRatio = ratioChange(
                        current.characters.valueFor(filter.characterMetric),
                        previous.characters.valueFor(filter.characterMetric),
                    ),
                ),
                streak = streak(
                    dates = activeDates,
                    periodEnd = calendar.localDate(clock(), currentOffsetSeconds()),
                ),
            ) to currentRows.size
        }

    suspend fun trends(
        filter: StatsFilter,
        scale: AnalyticsBucketScale,
    ): AnalyticsResult<AnalyticsTrends> =
        measured(AnalyticsQueryFamily.TRENDS, filter) {
            val range = effectiveRange(filter)
            val rows = analyticsRepository.dailyRollups(range).filter(filter::matches)
            val buckets = calendar.buckets(range, scale)
            val inventories = analyticsRepository.bucketInventoryMetrics(
                filter.copy(dateRange = range, comparisonRange = null),
                buckets,
            )
            check(inventories.size == buckets.size) {
                "Bucket inventory results must align with requested buckets"
            }
            var cumulative = ReadingMetrics()
            val points = buckets.mapIndexed { index, bucket ->
                val metrics = rows.filter {
                    it.date >= bucket.start && it.date <= bucket.endInclusive
                }.sumMetrics()
                cumulative += metrics
                AnalyticsTrendPoint(
                    range = bucket,
                    metrics = metrics.withInventory(inventories[index].metrics),
                    cumulativeMetrics = cumulative.withInventory(inventories[index].cumulative),
                )
            }
            AnalyticsTrends(scale, points) to rows.size
        }

    suspend fun temporalActivity(
        filter: StatsFilter,
    ): AnalyticsResult<AnalyticsTemporalActivity> =
        measured(AnalyticsQueryFamily.TRENDS, filter) {
            val sparse = analyticsRepository.temporalActivity(filter)
            val hours = sparse.hours.associateBy { it.hourOfDay }
            val weekdays = sparse.weekdays.associateBy { it.isoDayOfWeek }
            AnalyticsTemporalActivity(
                hours = (0..23).map { hour ->
                    hours[hour] ?: AnalyticsHourActivity(
                        hourOfDay = hour,
                        totals = AnalyticsActivityTotals(),
                    )
                },
                weekdays = (1..7).map { day ->
                    weekdays[day] ?: AnalyticsWeekdayActivity(
                        isoDayOfWeek = day,
                        totals = AnalyticsActivityTotals(),
                    )
                },
            ) to (sparse.hours.size + sparse.weekdays.size)
        }

    suspend fun titleTrends(
        filter: StatsFilter,
        scale: AnalyticsBucketScale,
        selection: AnalyticsTitleSeriesSelection = AnalyticsTitleSeriesSelection.TOP_CHARACTERS,
        maxTitles: Int = DEFAULT_TITLE_TREND_LIMIT,
    ): AnalyticsResult<AnalyticsTitleTrends> {
        require(maxTitles in 1..MAX_TITLE_TREND_LIMIT) {
            "Title trend limit must be between 1 and $MAX_TITLE_TREND_LIMIT"
        }
        return measured(AnalyticsQueryFamily.TRENDS, filter) {
            val range = effectiveRange(filter)
            val buckets = calendar.buckets(range, scale)
            val rows = analyticsRepository.titleTrendDaily(
                filter.copy(dateRange = range, comparisonRange = null),
                selection,
                maxTitles,
            )
            val series = rows.groupByTo(linkedMapOf()) { it.titleId }
                .values
                .take(maxTitles)
                .map { titleRows ->
                    var cumulative = ReadingMetrics()
                    val points = buckets.map { bucket ->
                        val metrics = titleRows
                            .filter { it.date in bucket.start..bucket.endInclusive }
                            .map { it.metrics }
                            .sumReadingMetrics()
                        cumulative += metrics
                        AnalyticsTrendPoint(bucket, metrics, cumulative)
                    }
                    val title = titleRows.first()
                    AnalyticsTitleTrendSeries(
                        titleId = title.titleId,
                        displayTitle = title.displayTitle,
                        mediaKind = title.mediaKind,
                        languageTag = title.languageTag,
                        points = points,
                    )
                }
            AnalyticsTitleTrends(scale, selection, series) to rows.size
        }
    }

    suspend fun titles(
        filter: StatsFilter,
        sort: AnalyticsSort,
    ): AnalyticsResult<List<AnalyticsTitleRow>> =
        measured(AnalyticsQueryFamily.TITLES, filter) {
            val (rows, sourceRowCount) = titleRows(filter, AnalyticsTitleFilter())
            rows.sortedWith(
                titleComparator(sort.toTitleSort(), filter.characterMetric, AnalyticsTitleFilter()),
            ) to sourceRowCount
        }

    suspend fun titlePage(
        filter: StatsFilter,
        titleFilter: AnalyticsTitleFilter,
        sort: AnalyticsTitleSort,
        offset: Long,
        limit: Int,
    ): AnalyticsResult<AnalyticsPage<AnalyticsTitleRow>> {
        require(offset >= 0)
        require(limit in 1..MAX_TITLE_PAGE_SIZE)
        return measured(AnalyticsQueryFamily.TITLES, filter) {
            val (rows, sourceRowCount) = titleRows(filter, titleFilter)
            val sorted = rows.sortedWith(titleComparator(sort, filter.characterMetric, titleFilter))
            val start = offset.coerceAtMost(sorted.size.toLong()).toInt()
            val end = (start + limit).coerceAtMost(sorted.size)
            AnalyticsPage(
                items = sorted.subList(start, end),
                nextOffset = if (end < sorted.size) end.toLong() else null,
            ) to sourceRowCount
        }
    }

    suspend fun titleCompletedUnits(
        filter: StatsFilter,
        titleId: TitleId,
        offset: Long,
        limit: Int,
    ): AnalyticsResult<AnalyticsPage<AnalyticsTitleCompletedUnit>> {
        require(offset >= 0)
        require(limit in 1..MAX_TITLE_PAGE_SIZE)
        val scopedFilter = filter.copy(titleIds = setOf(titleId), comparisonRange = null)
        return measured(AnalyticsQueryFamily.TITLES, scopedFilter) {
            analyticsRepository.titleCompletedUnits(
                filter = scopedFilter,
                offset = offset,
                limit = limit,
            ).let { it to it.items.size }
        }
    }

    suspend fun titleSourceOccurrences(
        filter: StatsFilter,
        titleId: TitleId,
        offset: Long,
        limit: Int,
    ): AnalyticsResult<AnalyticsPage<AnalyticsSourceOccurrence>> {
        require(offset >= 0)
        require(limit in 1..MAX_TITLE_PAGE_SIZE)
        val scopedFilter = filter.copy(titleIds = setOf(titleId), comparisonRange = null)
        return measured(AnalyticsQueryFamily.TITLES, scopedFilter) {
            analyticsRepository.sourceOccurrences(
                filter = scopedFilter,
                offset = offset,
                limit = limit,
            ).let { it to it.items.size }
        }
    }

    private suspend fun titleRows(
        filter: StatsFilter,
        titleFilter: AnalyticsTitleFilter,
    ): Pair<List<AnalyticsTitleRow>, Int> {
        val range = effectiveRange(filter)
        val scopedFilter = filter.copy(dateRange = range, comparisonRange = null)
        val rows = analyticsRepository.dailyRollups(range).filter(scopedFilter::matches)
        val titleIds = rows.mapTo(mutableSetOf()) { it.titleId }
        val metadata = analyticsRepository.titleMetadata(titleIds).associateBy { it.titleId }
        val inventory = analyticsRepository.titleInventoryMetrics(scopedFilter)
        val coverage = analyticsRepository.titleCoverage(scopedFilter)
        val netProgress = analyticsRepository.titleNetProgress(
            scopedFilter.copy(dateRange = null),
        )
        val unitProgress = analyticsRepository.titleUnitProgress(
            scopedFilter.copy(dateRange = null),
        )
        val result = rows.groupBy { it.titleId }.mapNotNull { (titleId, groupedRows) ->
            val title = metadata[titleId] ?: return@mapNotNull null
            val metrics = groupedRows.sumMetrics().withInventory(
                inventory[titleId] ?: AnalyticsInventoryMetrics(),
            )
            val activeRows = groupedRows.filter { it.metrics.hasActivity() }
            if (activeRows.isEmpty()) return@mapNotNull null
            val firstActiveDate = activeRows.minOf { it.date }
            val lastActiveDate = activeRows.maxOf { it.date }
            val activeDays = activeRows.mapTo(mutableSetOf()) { it.date }.size
            val calendarSpanDays = (
                lastActiveDate.epochDay - firstActiveDate.epochDay + 1
                ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val titleCoverage = coverage[titleId] ?: AnalyticsTitleCoverage()
            val titleUnitProgress = unitProgress[titleId] ?: AnalyticsTitleUnitProgress()
            val row = AnalyticsTitleRow(
                titleId = titleId,
                displayTitle = title.displayTitle,
                mediaKind = title.mediaKind,
                sourceKey = title.sourceKey,
                profileId = title.profileId,
                languageTag = title.languageTag,
                libraryId = title.libraryId,
                trackerId = title.trackerId,
                mediaId = title.mediaId,
                status = title.status,
                totalUnits = title.totalUnits,
                totalCharacterEstimate = title.totalCharacterEstimate,
                deletedAtEpochMillis = title.deletedAtEpochMillis,
                metrics = metrics,
                coverage = titleCoverage,
                firstActiveDate = firstActiveDate,
                lastActiveDate = lastActiveDate,
                activeDays = activeDays,
                calendarSpanDays = calendarSpanDays,
                averageCharactersPerActiveDay =
                metrics.characters.gross.value.toDouble() / activeDays.toDouble(),
                averageActiveTimePerActiveDayMillis =
                metrics.activeTime.value.toDouble() / activeDays.toDouble(),
                dayHighlights = groupedRows.dayHighlights(filter.characterMetric, titleFilter),
                unitProgress = titleUnitProgress,
                estimate = estimateRemaining(
                    title = title,
                    rows = groupedRows,
                    currentNetProgress = netProgress[titleId],
                    unitProgress = titleUnitProgress,
                ),
                speedRankingEligible = metrics.qualifiesForSpeed(filter.characterMetric, titleFilter),
                progress = title.progress(netProgress[titleId], titleUnitProgress),
                completed = title.completed,
            )
            row.takeIf { titleFilter.matches(it) }
        }
        return result to rows.size
    }

    suspend fun characters(
        filter: StatsFilter,
        sort: AnalyticsSort,
        offset: Long,
        limit: Int,
        searchQuery: String? = null,
        characterFilter: AnalyticsCharacterFilter = AnalyticsCharacterFilter(),
    ): AnalyticsResult<AnalyticsPage<AnalyticsCharacterRow>> =
        measured(AnalyticsQueryFamily.CHARACTERS, filter) {
            analyticsRepository.characterPage(
                filter = filter.forCharacterRange(characterFilter.range),
                sort = sort,
                offset = offset,
                limit = limit,
                searchQuery = searchQuery,
                characterFilter = characterFilter,
            ).let {
                it to it.items.size
            }
        }

    suspend fun characterSummary(
        filter: StatsFilter,
        characterFilter: AnalyticsCharacterFilter = AnalyticsCharacterFilter(),
    ): AnalyticsResult<AnalyticsCharacterSummary> =
        measured(AnalyticsQueryFamily.CHARACTERS, filter) {
            analyticsRepository.characterSummary(
                filter = filter.forCharacterRange(characterFilter.range),
                characterFilter = characterFilter,
            ).let { it to it.scripts.size }
        }

    suspend fun sessions(
        filter: StatsFilter,
        cursor: SessionCursor?,
        limit: Int,
    ): AnalyticsResult<SessionPage> =
        measured(AnalyticsQueryFamily.SESSIONS, filter) {
            analyticsRepository.filteredSessionsPage(filter, cursor, limit).let {
                it to it.items.size
            }
        }

    suspend fun sessionDetail(
        filter: StatsFilter,
        sessionId: SessionId,
        maxTimelineBuckets: Int = 120,
    ): AnalyticsResult<AnalyticsSessionDetail?> =
        measured(AnalyticsQueryFamily.SESSIONS, filter) {
            analyticsRepository.sessionDetail(sessionId, maxTimelineBuckets).let {
                it to if (it == null) 0 else it.timeline.size
            }
        }

    suspend fun characterOccurrences(
        filter: StatsFilter,
        codePoint: UnicodeCodePoint,
        offset: Long,
        limit: Int,
    ): AnalyticsResult<AnalyticsPage<AnalyticsSourceOccurrence>> =
        measured(AnalyticsQueryFamily.CHARACTERS, filter) {
            analyticsRepository.characterOccurrences(filter, codePoint, offset, limit).let {
                it to it.items.size
            }
        }

    suspend fun goals(filter: StatsFilter): AnalyticsResult<List<AnalyticsGoalProgress>> =
        measured(AnalyticsQueryFamily.GOALS, filter) {
            val today = calendar.localDate(clock(), currentOffsetSeconds())
            val goals = goalRepository.getGoals().filter {
                it.state != "ARCHIVED" && it.matchesDashboardScope(filter)
            }
            val ranges = goals.associateWith { goal ->
                val start = goal.startDate ?: calendar.localDate(
                    goal.createdAtEpochMillis,
                    currentOffsetSeconds(),
                )
                val end = minOf(goal.endDate ?: today, today)
                if (start <= end) {
                    LocalDateRange(start, end)
                } else {
                    LocalDateRange(today, today)
                }
            }
            val rows = ranges.values
                .takeIf { it.isNotEmpty() }
                ?.let { goalRanges ->
                    analyticsRepository.dailyRollups(
                        LocalDateRange(
                            goalRanges.minOf(LocalDateRange::start),
                            goalRanges.maxOf(LocalDateRange::endInclusive),
                        ),
                    )
                }
                .orEmpty()
            val inventoryCache =
                mutableMapOf<StatsFilter, Map<ImmersionLocalDate, AnalyticsInventoryMetrics>>()
            val progress = goals.map { goal ->
                val range = ranges.getValue(goal)
                val goalFilter = goal.statsFilter(filter, range)
                val scopedRows = rows.filter(goalFilter::matches)
                val checkIns = goalRepository.getCheckIns(goal.id)
                val inventoryByDate = if (goal.metric in INVENTORY_GOAL_METRICS) {
                    inventoryCache.getOrPut(goalFilter) {
                        val buckets = range.dailyBuckets()
                        val inventories =
                            analyticsRepository.bucketInventoryMetrics(goalFilter, buckets)
                        check(inventories.size == buckets.size) {
                            "Bucket inventory results must align with requested buckets"
                        }
                        buckets.zip(inventories).associate { (bucket, inventory) ->
                            bucket.start to inventory.metrics
                        }
                    }
                } else {
                    emptyMap()
                }
                goal.progress(
                    rows = scopedRows,
                    range = range,
                    checkIns = checkIns,
                    inventoryByDate = inventoryByDate,
                    today = today,
                )
            }
            progress.forEach { item ->
                recordNewMilestones(item)
            }
            progress to rows.size
        }

    suspend fun anki(filter: StatsFilter): AnalyticsResult<AnalyticsAnkiSummary> =
        measured(AnalyticsQueryFamily.ANKI, filter) {
            val range = effectiveRange(filter)
            val effectiveFilter = filter.copy(dateRange = range)
            val rollups = analyticsRepository.dailyRollups(range).filter(effectiveFilter::matches)
            val summary = analyticsRepository.ankiSummary(effectiveFilter)
            val missingFilter = effectiveFilter.copy(maturityTiers = setOf(MaturityTier.UNKNOWN))
            val missingCharacters = if (summary.snapshot?.hasUsableInventory == true) {
                analyticsRepository.characterPage(
                    missingFilter,
                    AnalyticsSort.FREQUENCY_RANK,
                    0,
                    20,
                ).items
            } else {
                emptyList()
            }
            val today = calendar.localDate(clock(), currentOffsetSeconds())
            val weeklyImpact = summary.weeklyImpact.map { week ->
                week.copy(
                    partial = week.weekStart.epochDay < range.start.epochDay ||
                        week.weekEndInclusive.epochDay > range.endInclusive.epochDay ||
                        week.weekEndInclusive.epochDay > today.epochDay,
                )
            }
            summary.copy(
                cardsCreated = rollups.sumOf { it.metrics.cardsCreated.value },
                cardsUpdated = rollups.sumOf { it.metrics.cardsUpdated.value },
                missingHighFrequencyCharacters = missingCharacters,
                capabilities = summary.ankiReportCapabilities(),
                weeklyImpact = weeklyImpact,
                generatedAtEpochMillis = clock(),
                minimumComparisonSampleSize = MINIMUM_ANKI_COMPARISON_SAMPLE_SIZE,
            ) to rollups.size
        }

    suspend fun repairDirtyRollups(
        limit: Int = 31,
        rollupVersion: Int = ImmersionStatsVersions.ROLLUP,
    ): List<ImmersionRollupRebuildResult> {
        require(limit in 1..366)
        val now = clock()
        return analyticsRepository.dirtyRollupRanges(limit).map { dirty ->
            analyticsRepository.rebuildRollups(
                LocalDateRange(dirty.start, dirty.endInclusive),
                rollupVersion,
                now,
            )
        }
    }

    suspend fun rebuild(
        range: LocalDateRange,
        rollupVersion: Int = ImmersionStatsVersions.ROLLUP,
    ): ImmersionRollupRebuildResult =
        analyticsRepository.rebuildRollups(range, rollupVersion, clock())

    suspend fun saveGoal(goal: ImmersionGoal) {
        goalRepository.upsertGoal(goal)
    }

    suspend fun restartGoal(
        expectedGoal: ImmersionGoal,
        replacementGoal: ImmersionGoal,
        restartedAtEpochMillis: Long,
    ): Boolean = goalRepository.restartGoal(
        expectedGoal = expectedGoal,
        replacementGoal = replacementGoal,
        restartedAtEpochMillis = restartedAtEpochMillis,
    )

    suspend fun checkIn(goalId: String, date: ImmersionLocalDate, completed: Boolean, note: String?) {
        goalRepository.upsertCheckIn(
            ImmersionGoalCheckIn(
                goalId = goalId,
                localDate = date,
                status = if (completed) "COMPLETED" else "MISSED",
                note = note,
                occurredAtEpochMillis = clock(),
            ),
        )
    }

    private suspend fun recordNewMilestones(progress: AnalyticsGoalProgress) {
        if (progress.targetToDate <= 0) return
        val ratio = progress.achieved / progress.targetToDate
        val existing = goalRepository.getAchievements(progress.goal.id)
            .mapTo(mutableSetOf()) { it.milestoneKey }
        listOf(0.25 to "25", 0.5 to "50", 0.75 to "75", 1.0 to "100")
            .filter { (threshold, key) -> ratio >= threshold && key !in existing }
            .forEach { (_, key) ->
                goalRepository.recordAchievement(
                    ImmersionGoalAchievement(
                        id = UUID.nameUUIDFromBytes(
                            "${progress.goal.id}:$key".encodeToByteArray(),
                        ).toString(),
                        goalId = progress.goal.id,
                        milestoneKey = key,
                        earnedAtEpochMillis = clock(),
                        targetSnapshot = progress.targetToDate,
                    ),
                )
            }
    }

    private suspend fun effectiveRange(filter: StatsFilter): LocalDateRange =
        filter.dateRange ?: analyticsRepository.availableDateRange(filter) ?: todayRange()

    private suspend fun <T> measured(
        family: AnalyticsQueryFamily,
        filter: StatsFilter,
        query: suspend () -> Pair<T, Int>,
    ): AnalyticsResult<T> {
        val startedAt = System.nanoTime()
        val now = clock()
        val quality = analyticsRepository.dataQuality(filter, now)
        val (value, rowCount) = query()
        val duration = max(0, (System.nanoTime() - startedAt) / 1_000_000)
        return AnalyticsResult(
            value = value,
            quality = quality,
            diagnostics = AnalyticsQueryDiagnostics(
                family = family,
                filterHash = filter.stableHash(),
                rowCount = rowCount,
                durationMillis = duration,
            ),
        )
    }

    private fun todayRange(): LocalDateRange {
        val today = calendar.localDate(clock(), currentOffsetSeconds())
        return LocalDateRange(today, today)
    }

    private fun streak(
        dates: Set<ImmersionLocalDate>,
        periodEnd: ImmersionLocalDate,
    ): AnalyticsStreak {
        if (dates.isEmpty()) return AnalyticsStreak(0, 0, emptySet())
        var longest = 0
        var run = 0
        var previous: Long? = null
        dates.forEach { date ->
            run = if (previous != null && date.epochDay == previous + 1) run + 1 else 1
            longest = max(longest, run)
            previous = date.epochDay
        }
        var current = 0
        var cursor = periodEnd.epochDay
        if (periodEnd !in dates) cursor--
        while (ImmersionLocalDate(cursor) in dates) {
            current++
            cursor--
        }
        return AnalyticsStreak(current, longest, dates)
    }
}

private const val DEFAULT_TITLE_TREND_LIMIT = 8
private const val MAX_TITLE_TREND_LIMIT = 20

private fun StatsFilter.matches(row: ImmersionDailyRollup): Boolean =
    (mediaKinds.isEmpty() || row.mediaKind in mediaKinds) &&
        (profileIds.isEmpty() || row.profileId in profileIds) &&
        (languageTags.isEmpty() || row.languageTag in languageTags) &&
        (titleIds.isEmpty() || row.titleId in titleIds) &&
        (provenanceStates.isEmpty() || row.provenanceState in provenanceStates) &&
        (includeRereadsAndReplays || !row.replay) &&
        (
            includeLegacyAggregates ||
                row.provenanceState != tachiyomi.domain.immersion.model.ProvenanceState.LEGACY_AGGREGATE
            )

private fun List<ImmersionDailyRollup>.sumMetrics(): ReadingMetrics =
    fold(ReadingMetrics()) { total, row -> total + row.metrics }
        .withInventory(AnalyticsInventoryMetrics())

private fun List<ReadingMetrics>.sumReadingMetrics(): ReadingMetrics =
    fold(ReadingMetrics(), ReadingMetrics::plus)

private fun ReadingMetrics.withInventory(inventory: AnalyticsInventoryMetrics): ReadingMetrics =
    copy(
        distinctCharacters = NonNegativeCounter(inventory.distinctCharacters),
        newCharacters = NonNegativeCounter(inventory.newCharacters),
        characterCoverage = CharacterCoverage(
            encounteredTargetScriptCharacters = NonNegativeCounter(inventory.distinctCharacters),
            representedInAnki = NonNegativeCounter(inventory.charactersRepresentedInAnki),
        ),
    )

private operator fun ReadingMetrics.plus(other: ReadingMetrics): ReadingMetrics =
    ReadingMetrics(
        activeTime = activeTime + other.activeTime,
        characters = CharacterVolume(
            gross = characters.gross + other.characters.gross,
            uniqueSource = characters.uniqueSource + other.characters.uniqueSource,
            netProgress = characters.netProgress + other.characters.netProgress,
        ),
        distinctCharacters = distinctCharacters + other.distinctCharacters,
        newCharacters = newCharacters + other.newCharacters,
        sourceUnits = sourceUnits + other.sourceUnits,
        sessions = sessions + other.sessions,
        cardsCreated = cardsCreated + other.cardsCreated,
        cardsUpdated = cardsUpdated + other.cardsUpdated,
        characterCoverage = CharacterCoverage(
            encounteredTargetScriptCharacters =
            characterCoverage.encounteredTargetScriptCharacters +
                other.characterCoverage.encounteredTargetScriptCharacters,
            representedInAnki =
            characterCoverage.representedInAnki +
                other.characterCoverage.representedInAnki,
        ),
    )

private fun ReadingMetrics.hasActivity(): Boolean =
    activeTime.value > 0 ||
        characters.gross.value > 0 ||
        sourceUnits.value > 0 ||
        cardsCreated.value > 0

private fun ratioChange(current: Long, previous: Long): Double? =
    if (previous == 0L) null else (current - previous).toDouble() / previous.toDouble()

private fun titleComparator(
    sort: AnalyticsTitleSort,
    characterMetric: CharacterMetric,
    filter: AnalyticsTitleFilter,
): Comparator<AnalyticsTitleRow> =
    when (sort) {
        AnalyticsTitleSort.MOST_RECENT -> compareByDescending<AnalyticsTitleRow> { it.lastActiveDate }
            .thenBy { it.displayTitle }
            .thenBy { it.titleId.value }
        AnalyticsTitleSort.MOST_TIME ->
            compareByDescending<AnalyticsTitleRow> { it.metrics.activeTime.value }
                .thenByDescending { it.lastActiveDate }
                .thenBy { it.titleId.value }
        AnalyticsTitleSort.MOST_CHARACTERS ->
            compareByDescending<AnalyticsTitleRow> {
                it.metrics.characters.valueFor(characterMetric)
            }
                .thenByDescending { it.lastActiveDate }
                .thenBy { it.titleId.value }
        AnalyticsTitleSort.ALPHABETICAL -> compareBy<AnalyticsTitleRow> { it.displayTitle }
            .thenBy { it.titleId.value }
        AnalyticsTitleSort.READING_SPEED ->
            compareByDescending<AnalyticsTitleRow> { it.speedRankingEligible }
                .thenByDescending {
                    if (it.speedRankingEligible) {
                        it.metrics.readingSpeedPerHour(characterMetric)
                    } else {
                        null
                    }
                }
                .thenByDescending { it.metrics.characters.valueFor(characterMetric) }
                .thenBy { it.titleId.value }
        AnalyticsTitleSort.MINING_RATE ->
            compareByDescending<AnalyticsTitleRow> {
                it.metrics.characters.gross.value >= filter.minimumSpeedCharacters
            }
                .thenByDescending { it.metrics.miningRatePerTenThousandGrossCharacters() }
                .thenByDescending { it.metrics.cardsCreated.value }
                .thenBy { it.titleId.value }
        AnalyticsTitleSort.PROGRESS ->
            compareByDescending<AnalyticsTitleRow> { it.progress != null }
                .thenByDescending { it.progress }
                .thenByDescending { it.lastActiveDate }
                .thenBy { it.titleId.value }
    }

private fun AnalyticsSort.toTitleSort(): AnalyticsTitleSort = when (this) {
    AnalyticsSort.MOST_RECENT -> AnalyticsTitleSort.MOST_RECENT
    AnalyticsSort.MOST_TIME -> AnalyticsTitleSort.MOST_TIME
    AnalyticsSort.MOST_CHARACTERS,
    AnalyticsSort.MOST_OCCURRENCES,
    AnalyticsSort.FREQUENCY_RANK,
    AnalyticsSort.PRIORITY,
    -> AnalyticsTitleSort.MOST_CHARACTERS
    AnalyticsSort.FIRST_SEEN,
    AnalyticsSort.ALPHABETICAL,
    -> AnalyticsTitleSort.ALPHABETICAL
}

private fun AnalyticsTitleFilter.matches(row: AnalyticsTitleRow): Boolean {
    val query = searchQuery?.trim()?.takeIf(String::isNotEmpty)
    if (
        query != null &&
        !row.displayTitle.contains(query, ignoreCase = true) &&
        !row.sourceKey.contains(query, ignoreCase = true)
    ) {
        return false
    }
    val matchesState = when (state) {
        AnalyticsTitleStateFilter.ALL -> true
        AnalyticsTitleStateFilter.COMPLETED -> row.completed == true
        AnalyticsTitleStateFilter.IN_PROGRESS -> row.completed == false
        AnalyticsTitleStateFilter.UNKNOWN -> row.completed == null
    }
    if (!matchesState) return false
    return when (coverage) {
        AnalyticsTitleCoverageFilter.ALL -> true
        AnalyticsTitleCoverageFilter.COMPLETE ->
            row.coverage.sourceUnitCount > 0 &&
                row.coverage.indexedSourceUnitCount == row.coverage.sourceUnitCount
        AnalyticsTitleCoverageFilter.PARTIAL ->
            row.coverage.indexedSourceUnitCount > 0 &&
                row.coverage.indexedSourceUnitCount < row.coverage.sourceUnitCount
        AnalyticsTitleCoverageFilter.MISSING ->
            row.coverage.sourceUnitCount == 0L || row.coverage.indexedSourceUnitCount == 0L
    }
}

private fun ReadingMetrics.qualifiesForSpeed(
    metric: CharacterMetric,
    filter: AnalyticsTitleFilter,
): Boolean =
    activeTime.value >= filter.minimumSpeedActiveMillis &&
        characters.valueFor(metric) >= filter.minimumSpeedCharacters &&
        readingSpeedPerHour(metric) != null

private fun List<ImmersionDailyRollup>.dayHighlights(
    metric: CharacterMetric,
    filter: AnalyticsTitleFilter,
): AnalyticsTitleDayHighlights {
    val days = groupBy(ImmersionDailyRollup::date).map { (date, rows) ->
        date to rows.map(ImmersionDailyRollup::metrics).sumReadingMetrics()
    }
    val characters = days
        .filter { (_, metrics) -> metrics.characters.valueFor(metric) > 0 }
        .maxWithOrNull(
            compareBy<Pair<ImmersionLocalDate, ReadingMetrics>> {
                it.second.characters.valueFor(metric)
            }.thenByDescending { it.first.epochDay },
        )
        ?.let { (date, metrics) ->
            AnalyticsTitleDayHighlight(date, metrics.characters.valueFor(metric).toDouble())
        }
    val activeTime = days
        .filter { (_, metrics) -> metrics.activeTime.value > 0 }
        .maxWithOrNull(
            compareBy<Pair<ImmersionLocalDate, ReadingMetrics>> { it.second.activeTime.value }
                .thenByDescending { it.first.epochDay },
        )
        ?.let { (date, metrics) ->
            AnalyticsTitleDayHighlight(date, metrics.activeTime.value.toDouble())
        }
    val speed = days
        .filter { (_, metrics) -> metrics.qualifiesForSpeed(metric, filter) }
        .mapNotNull { (date, metrics) ->
            metrics.readingSpeedPerHour(metric)?.let { date to it }
        }
        .maxWithOrNull(
            compareBy<Pair<ImmersionLocalDate, Double>> { it.second }
                .thenByDescending { it.first.epochDay },
        )
        ?.let { (date, value) -> AnalyticsTitleDayHighlight(date, value) }
    return AnalyticsTitleDayHighlights(characters, activeTime, speed)
}

private fun estimateRemaining(
    title: AnalyticsTitleMetadata,
    rows: List<ImmersionDailyRollup>,
    currentNetProgress: NetCharacterProgress?,
    unitProgress: AnalyticsTitleUnitProgress,
): AnalyticsTitleEstimate? {
    val daily = rows.groupBy(ImmersionDailyRollup::date).map { (date, values) ->
        date to values.map(ImmersionDailyRollup::metrics).sumReadingMetrics()
    }
    val unitCompletionsByDay = unitProgress.firstCompletionsByDay.associate {
        it.date to it.completedUnits
    }
    val descriptor = title.totalCharacterEstimate
        ?.takeIf { it > 0 }
        ?.let { total ->
            currentNetProgress?.let { current ->
                EstimateDescriptor(
                    total = total,
                    current = current.value.coerceAtLeast(0),
                    unit = AnalyticsEstimateUnit.CHARACTERS,
                    minimumDailyProgress = MIN_ESTIMATE_CHARACTERS,
                    progress = { _, metrics -> metrics.characters.netProgress.value },
                )
            }
        }
        ?: title.totalUnits
            ?.takeIf { it > 0 && unitProgress.hasTrustworthyIdentity }
            ?.let { total ->
                EstimateDescriptor(
                    total = total,
                    current = unitProgress.completedUnits,
                    unit = AnalyticsEstimateUnit.MEDIA_UNITS,
                    minimumDailyProgress = 1,
                    progress = { date, _ -> unitCompletionsByDay[date] ?: 0 },
                )
            }
        ?: return null
    val qualifying = daily.mapNotNull { (date, metrics) ->
        val progress = descriptor.progress(date, metrics)
        if (
            metrics.activeTime.value < MIN_ESTIMATE_ACTIVE_MILLIS ||
            progress < descriptor.minimumDailyProgress
        ) {
            null
        } else {
            EstimateSample(progress, metrics.activeTime.value)
        }
    }
    if (qualifying.size < MIN_ESTIMATE_DAYS) return null
    val rates = qualifying.map { it.progress.toDouble() / it.activeMillis.toDouble() }
    val mean = rates.average()
    if (mean <= 0 || !mean.isFinite()) return null
    val variance = rates.sumOf { rate -> (rate - mean) * (rate - mean) } / rates.size.toDouble()
    val coefficientOfVariation = sqrt(variance) / mean
    if (!coefficientOfVariation.isFinite() || coefficientOfVariation > MAX_ESTIMATE_VARIATION) {
        return null
    }
    val totalProgress = qualifying.sumOf(EstimateSample::progress)
    val totalActive = qualifying.sumOf(EstimateSample::activeMillis)
    val weightedRate = totalProgress.toDouble() / totalActive.toDouble()
    val current = descriptor.current.coerceAtMost(descriptor.total)
    val remaining = if (title.completed == true) 0 else descriptor.total - current
    val confidence = when {
        qualifying.size >= HIGH_CONFIDENCE_DAYS &&
            coefficientOfVariation <= HIGH_CONFIDENCE_VARIATION ->
            AnalyticsEstimateConfidence.HIGH
        qualifying.size >= MEDIUM_CONFIDENCE_DAYS &&
            coefficientOfVariation <= MEDIUM_CONFIDENCE_VARIATION ->
            AnalyticsEstimateConfidence.MEDIUM
        else -> AnalyticsEstimateConfidence.LOW
    }
    return AnalyticsTitleEstimate(
        remainingAmount = remaining,
        unit = descriptor.unit,
        estimatedActiveTimeMillis = (remaining.toDouble() / weightedRate).roundToLong(),
        confidence = confidence,
        qualifyingDayCount = qualifying.size,
    )
}

private fun AnalyticsTitleMetadata.progress(
    netProgress: NetCharacterProgress?,
    unitProgress: AnalyticsTitleUnitProgress,
): Double? {
    val (current, total) = totalCharacterEstimate
        ?.takeIf { it > 0 }
        ?.let { total -> netProgress?.value?.coerceAtLeast(0)?.let { it to total } }
        ?: totalUnits
            ?.takeIf { it > 0 && unitProgress.hasTrustworthyIdentity }
            ?.let { unitProgress.completedUnits to it }
        ?: return null
    return current
        .toDouble()
        .div(total.toDouble())
        .coerceIn(0.0, 1.0)
}

private data class EstimateDescriptor(
    val total: Long,
    val current: Long,
    val unit: AnalyticsEstimateUnit,
    val minimumDailyProgress: Long,
    val progress: (ImmersionLocalDate, ReadingMetrics) -> Long,
)

private data class EstimateSample(
    val progress: Long,
    val activeMillis: Long,
)

private const val MAX_TITLE_PAGE_SIZE = 500
private const val MIN_ESTIMATE_DAYS = 3
private const val MIN_ESTIMATE_ACTIVE_MILLIS = 10 * 60 * 1_000L
private const val MIN_ESTIMATE_CHARACTERS = 1_000L
private const val MAX_ESTIMATE_VARIATION = 0.9
private const val MEDIUM_CONFIDENCE_DAYS = 5
private const val MEDIUM_CONFIDENCE_VARIATION = 0.6
private const val HIGH_CONFIDENCE_DAYS = 10
private const val HIGH_CONFIDENCE_VARIATION = 0.35

private fun ImmersionGoal.progress(
    rows: List<ImmersionDailyRollup>,
    range: LocalDateRange,
    checkIns: List<ImmersionGoalCheckIn>,
    inventoryByDate: Map<ImmersionLocalDate, AnalyticsInventoryMetrics>,
    today: ImmersionLocalDate,
): AnalyticsGoalProgress {
    val effectiveStart = maxOf(range.start, startDate ?: range.start)
    val effectiveEnd = minOf(range.endInclusive, endDate ?: range.endInclusive)
    if (effectiveStart > effectiveEnd) {
        return AnalyticsGoalProgress(
            goal = this,
            achieved = 0.0,
            target = target,
            pacePerDay = null,
            projectedCompletionDate = null,
            achievedAtEpochMillis = null,
        )
    }
    val matchingRows = rows.filter {
        it.date in effectiveStart..effectiveEnd &&
            (mediaKind == null || it.mediaKind == mediaKind) &&
            (profileId == null || it.profileId == profileId) &&
            (languageTag == null || it.languageTag == languageTag) &&
            (titleId == null || it.titleId == titleId)
    }

    val multipliers = weekdayMultipliers.parseWeekdayMultipliers()
    val rowsByDate = matchingRows.groupBy(ImmersionDailyRollup::date)
    val checkInsByDate = checkIns.associateBy(ImmersionGoalCheckIn::localDate)
    val dates = (effectiveStart.epochDay..effectiveEnd.epochDay).map(::ImmersionLocalDate)
    val achievedByDate = dates.associateWith { date ->
        if (type == "MANUAL" || metric == "manual") {
            if (checkInsByDate[date]?.status == "COMPLETED") 1.0 else 0.0
        } else {
            when (metric) {
                "new_characters" -> inventoryByDate[date]?.newCharacters?.toDouble() ?: 0.0
                else -> rowsByDate[date].orEmpty().sumOf { it.metrics.valueForGoal(metric) }
            }
        }
    }
    val achieved = achievedByDate.values.sum()
    val dailyGoal = period == "DAILY" || type == "PERPETUAL_DAILY"
    val targetToDate = if (dailyGoal) {
        dates.sumOf { target * multipliers.multiplier(it) }
    } else {
        target
    }
    val achievedAtDate = if (targetToDate > 0 && achieved >= targetToDate) {
        var cumulative = 0.0
        dates.firstOrNull { date ->
            cumulative += achievedByDate.getValue(date)
            cumulative >= targetToDate
        }
    } else {
        null
    }
    val activeWeight = dates.sumOf { multipliers.multiplier(it) }
    val pace = activeWeight.takeIf { it > 0.0 }?.let { achieved / it }
    val rollingSeven = achievedByDate.robustRollingPace(
        end = effectiveEnd,
        days = GOAL_SHORT_PACE_WINDOW_DAYS,
        multipliers = multipliers,
    )
    val rollingThirty = achievedByDate.robustRollingPace(
        end = effectiveEnd,
        days = GOAL_FORECAST_WINDOW_DAYS,
        multipliers = multipliers,
    )
    val forecastWindowStart = effectiveEnd.epochDay - GOAL_FORECAST_WINDOW_DAYS + 1
    val sampleDays = achievedByDate.count { (date, value) ->
        date.epochDay >= forecastWindowStart &&
            multipliers.multiplier(date) > 0.0 &&
            value > 0.0
    }
    val forecastConfidence = when {
        sampleDays >= 7 -> CapabilityState.AVAILABLE
        sampleDays >= 3 -> CapabilityState.PARTIAL
        else -> CapabilityState.UNAVAILABLE
    }
    val remaining = (targetToDate - achieved).coerceAtLeast(0.0)
    val forecastPace = rollingThirty?.takeIf { forecastConfidence != CapabilityState.UNAVAILABLE }
    val projected = if (!dailyGoal && remaining > 0 && forecastPace != null && forecastPace > 0) {
        projectCompletion(effectiveEnd, remaining, forecastPace, multipliers)
    } else {
        null
    }
    val remainingActiveDates = endDate?.let { deadline ->
        if (deadline <= effectiveEnd) {
            emptyList()
        } else {
            (effectiveEnd.epochDay + 1..deadline.epochDay)
                .map(::ImmersionLocalDate)
                .filter { multipliers.multiplier(it) > 0.0 }
        }
    }
    val remainingActiveWeight = remainingActiveDates
        ?.sumOf { multipliers.multiplier(it) }
        ?.takeIf { it > 0.0 }
    val requiredPace = remainingActiveWeight?.let { remaining / it }
    val (currentStreak, longestStreak) = if (dailyGoal) {
        dailyGoalStreaks(
            achievedByDate = achievedByDate,
            target = target,
            multipliers = multipliers,
            end = effectiveEnd,
            forgiveIncompleteEndDay = effectiveEnd == today,
        )
    } else {
        0 to 0
    }
    val todayAchieved = if (dailyGoal) {
        achievedByDate[effectiveEnd] ?: 0.0
    } else {
        achieved
    }.coerceAtLeast(0.0)
    val todayTarget = if (dailyGoal) {
        target * multipliers.multiplier(effectiveEnd)
    } else {
        targetToDate
    }
    return AnalyticsGoalProgress(
        goal = this,
        achieved = achieved,
        target = target,
        pacePerDay = pace,
        projectedCompletionDate = projected,
        achievedAtEpochMillis = achievedAtDate?.toLocalDate()
            ?.atStartOfDay(java.time.ZoneOffset.UTC)
            ?.toInstant()
            ?.toEpochMilli(),
        targetToDate = targetToDate,
        requiredPacePerActiveDay = requiredPace,
        rollingSevenDayPace = rollingSeven,
        rollingThirtyDayPace = rollingThirty,
        currentStreakDays = currentStreak,
        longestStreakDays = longestStreak,
        isRestDay = multipliers.multiplier(effectiveEnd) == 0.0,
        forecastConfidence = forecastConfidence,
        remainingActiveDays = remainingActiveDates?.size,
        forecastSampleDays = sampleDays,
        forecastWindowDays = GOAL_FORECAST_WINDOW_DAYS,
        todayAchieved = todayAchieved,
        todayTarget = todayTarget,
    )
}

private fun ReadingMetrics.valueForGoal(metric: String): Double = when (metric) {
    "active_time_ms" -> activeTime.value.toDouble()
    "gross_characters", "characters" -> characters.gross.value.toDouble()
    "unique_source_characters" -> characters.uniqueSource.value.toDouble()
    "net_characters" -> characters.netProgress.value.toDouble()
    "source_units" -> sourceUnits.value.toDouble()
    "new_characters" -> newCharacters.value.toDouble()
    "sessions" -> sessions.value.toDouble()
    "cards" -> cardsCreated.value.toDouble()
    else -> 0.0
}

private fun String?.parseWeekdayMultipliers(): Map<DayOfWeek, Double> {
    if (isNullOrBlank()) return DayOfWeek.entries.associateWith { 1.0 }
    val parsed = Regex(
        "(MONDAY|TUESDAY|WEDNESDAY|THURSDAY|FRIDAY|SATURDAY|SUNDAY)\\s*[:=]\\s*([0-9]+(?:\\.[0-9]+)?)",
    ).findAll(this).associate { match ->
        DayOfWeek.valueOf(match.groupValues[1]) to match.groupValues[2].toDouble()
    }
    return DayOfWeek.entries.associateWith { parsed[it] ?: 1.0 }
}

private fun Map<DayOfWeek, Double>.multiplier(date: ImmersionLocalDate): Double =
    getValue(date.toLocalDate().dayOfWeek)

private fun Map<ImmersionLocalDate, Double>.robustRollingPace(
    end: ImmersionLocalDate,
    days: Int,
    multipliers: Map<DayOfWeek, Double>,
): Double? {
    val start = end.epochDay - days + 1
    val normalizedActiveDays = entries
        .asSequence()
        .filter { (date) -> date.epochDay in start..end.epochDay }
        .mapNotNull { (date, value) ->
            multipliers.multiplier(date)
                .takeIf { it > 0.0 }
                ?.let { multiplier -> value / multiplier }
        }
        .toList()
    if (normalizedActiveDays.isEmpty()) return null
    val positiveDays = normalizedActiveDays.filter { it > 0.0 }
    if (positiveDays.size < MIN_ROBUST_PACE_DAYS) {
        return normalizedActiveDays.average()
    }
    val sorted = positiveDays.sorted()
    val cappedDayCount = max(1, sorted.size / 10)
    val upperBound = sorted[sorted.lastIndex - cappedDayCount]
    return normalizedActiveDays.sumOf { it.coerceAtMost(upperBound) } / normalizedActiveDays.size
}

private fun projectCompletion(
    from: ImmersionLocalDate,
    remaining: Double,
    pacePerActiveDay: Double,
    multipliers: Map<DayOfWeek, Double>,
): ImmersionLocalDate? {
    var accumulated = 0.0
    var cursor = from
    repeat(3_650) {
        cursor = ImmersionLocalDate(cursor.epochDay + 1)
        accumulated += pacePerActiveDay * multipliers.multiplier(cursor)
        if (accumulated >= remaining) return cursor
    }
    return null
}

private fun dailyGoalStreaks(
    achievedByDate: Map<ImmersionLocalDate, Double>,
    target: Double,
    multipliers: Map<DayOfWeek, Double>,
    end: ImmersionLocalDate,
    forgiveIncompleteEndDay: Boolean,
): Pair<Int, Int> {
    val sortedDates = achievedByDate.keys.sorted()
    var longest = 0
    var run = 0
    sortedDates.forEach { date ->
        val multiplier = multipliers.multiplier(date)
        if (multiplier == 0.0) return@forEach
        if (achievedByDate.getValue(date) >= target * multiplier) {
            run++
            longest = max(longest, run)
        } else {
            run = 0
        }
    }
    var current = 0
    var cursor = end
    var mayForgiveIncompleteDay =
        forgiveIncompleteEndDay && multipliers.multiplier(end) > 0.0
    while (cursor in achievedByDate) {
        val multiplier = multipliers.multiplier(cursor)
        if (multiplier == 0.0) {
            cursor = ImmersionLocalDate(cursor.epochDay - 1)
            continue
        }
        val met = achievedByDate.getValue(cursor) >= target * multiplier
        if (!met && mayForgiveIncompleteDay) {
            mayForgiveIncompleteDay = false
            cursor = ImmersionLocalDate(cursor.epochDay - 1)
            continue
        }
        if (!met) break
        current++
        mayForgiveIncompleteDay = false
        cursor = ImmersionLocalDate(cursor.epochDay - 1)
    }
    return current to longest
}

private fun ImmersionGoal.statsFilter(
    base: StatsFilter,
    range: LocalDateRange,
): StatsFilter =
    StatsFilter(
        dateRange = range,
        mediaKinds = mediaKind?.let(::setOf) ?: base.mediaKinds,
        profileIds = profileId?.let(::setOf) ?: base.profileIds,
        languageTags = languageTag?.let(::setOf) ?: base.languageTags,
        titleIds = titleId?.let(::setOf) ?: base.titleIds,
        includeLegacyAggregates = base.includeLegacyAggregates,
        characterMetric = base.characterMetric,
        includeRereadsAndReplays = base.includeRereadsAndReplays,
        maturityTiers = base.maturityTiers,
        ankiMaturityAggregation = base.ankiMaturityAggregation,
        provenanceStates = base.provenanceStates,
    )

private fun ImmersionGoal.matchesDashboardScope(base: StatsFilter): Boolean =
    mediaKind.matchesDashboardSelection(base.mediaKinds) &&
        profileId.matchesDashboardSelection(base.profileIds) &&
        languageTag.matchesDashboardSelection(base.languageTags) &&
        titleId.matchesDashboardSelection(base.titleIds)

private fun <T> T?.matchesDashboardSelection(selection: Set<T>): Boolean =
    selection.isEmpty() || (this != null && this in selection)

private fun LocalDateRange.dailyBuckets(): List<LocalDateRange> =
    (start.epochDay..endInclusive.epochDay).map { epochDay ->
        val date = ImmersionLocalDate(epochDay)
        LocalDateRange(date, date)
    }

private const val GOAL_SHORT_PACE_WINDOW_DAYS = 7
private const val GOAL_FORECAST_WINDOW_DAYS = 30
private const val MIN_ROBUST_PACE_DAYS = 5

private fun StatsFilter.forCharacterRange(range: AnalyticsCharacterRange): StatsFilter =
    when (range) {
        AnalyticsCharacterRange.ENCOUNTERED,
        AnalyticsCharacterRange.FIRST_SEEN_IN_RANGE,
        -> this
        AnalyticsCharacterRange.UNKNOWN,
        AnalyticsCharacterRange.MISSING_HIGH_FREQUENCY,
        -> copy(maturityTiers = setOf(MaturityTier.UNKNOWN))
        AnalyticsCharacterRange.NEW -> copy(maturityTiers = setOf(MaturityTier.NEW))
        AnalyticsCharacterRange.LEARNING -> copy(maturityTiers = setOf(MaturityTier.LEARNING))
        AnalyticsCharacterRange.YOUNG -> copy(maturityTiers = setOf(MaturityTier.YOUNG))
        AnalyticsCharacterRange.MATURE -> copy(maturityTiers = setOf(MaturityTier.MATURE))
    }

private fun AnalyticsAnkiSummary.ankiReportCapabilities(): List<AnalyticsAnkiReportCapability> {
    val inventory = when {
        snapshot == null || !snapshot.hasUsableInventory ->
            AnalyticsAnkiReportCapability(
                AnalyticsAnkiReport.INVENTORY,
                CapabilityState.UNAVAILABLE,
                AnalyticsAnkiCapabilityReason.NO_CURRENT_INVENTORY,
            )
        snapshot.isStale ->
            AnalyticsAnkiReportCapability(
                AnalyticsAnkiReport.INVENTORY,
                CapabilityState.STALE,
                AnalyticsAnkiCapabilityReason.STALE_INVENTORY,
            )
        snapshot.isPartial ->
            AnalyticsAnkiReportCapability(
                AnalyticsAnkiReport.INVENTORY,
                CapabilityState.PARTIAL,
                AnalyticsAnkiCapabilityReason.PARTIAL_INVENTORY,
            )
        else ->
            AnalyticsAnkiReportCapability(
                AnalyticsAnkiReport.INVENTORY,
                snapshot.capabilityState,
                AnalyticsAnkiCapabilityReason.AVAILABLE,
            )
    }
    val attribution = when {
        linkedOperationCount == 0L && unattributedOperationCount > 0L ->
            AnalyticsAnkiReportCapability(
                AnalyticsAnkiReport.SOURCE_ATTRIBUTION,
                CapabilityState.UNAVAILABLE,
                AnalyticsAnkiCapabilityReason.NO_LINKED_SAMPLE,
            )
        unattributedOperationCount > 0L ->
            AnalyticsAnkiReportCapability(
                AnalyticsAnkiReport.SOURCE_ATTRIBUTION,
                CapabilityState.PARTIAL,
                AnalyticsAnkiCapabilityReason.NO_LINKED_SAMPLE,
            )
        else ->
            AnalyticsAnkiReportCapability(
                AnalyticsAnkiReport.SOURCE_ATTRIBUTION,
                CapabilityState.AVAILABLE,
                AnalyticsAnkiCapabilityReason.AVAILABLE,
            )
    }
    val readingLag = AnalyticsAnkiReportCapability(
        report = AnalyticsAnkiReport.READING_TO_CARD_LAG,
        state = if (meanReadingToCardLagMillis == null) {
            CapabilityState.UNAVAILABLE
        } else {
            CapabilityState.AVAILABLE
        },
        reason = if (meanReadingToCardLagMillis == null) {
            AnalyticsAnkiCapabilityReason.NO_LINKED_SAMPLE
        } else {
            AnalyticsAnkiCapabilityReason.AVAILABLE
        },
    )
    val maturityLagAvailable = weeklyImpact.any { it.meanCardToMaturityLagMillis != null }
    val maturityLag = AnalyticsAnkiReportCapability(
        report = AnalyticsAnkiReport.CARD_TO_MATURITY_LAG,
        state = if (maturityLagAvailable) CapabilityState.AVAILABLE else CapabilityState.UNAVAILABLE,
        reason = if (maturityLagAvailable) {
            AnalyticsAnkiCapabilityReason.AVAILABLE
        } else {
            AnalyticsAnkiCapabilityReason.INSUFFICIENT_SAMPLE
        },
    )
    val reviewReason = when {
        snapshot == null -> AnalyticsAnkiCapabilityReason.NO_CURRENT_INVENTORY
        snapshot.supportsReviewHistory -> AnalyticsAnkiCapabilityReason.DATA_NOT_COLLECTED
        else -> AnalyticsAnkiCapabilityReason.PROVIDER_UNSUPPORTED
    }
    return listOf(
        inventory,
        AnalyticsAnkiReportCapability(
            AnalyticsAnkiReport.CARD_ACTIVITY,
            CapabilityState.AVAILABLE,
            AnalyticsAnkiCapabilityReason.AVAILABLE,
        ),
        attribution,
        readingLag,
        maturityLag,
        AnalyticsAnkiReportCapability(
            AnalyticsAnkiReport.WEEKLY_FLOW,
            CapabilityState.AVAILABLE,
            AnalyticsAnkiCapabilityReason.AVAILABLE,
        ),
        AnalyticsAnkiReportCapability(
            AnalyticsAnkiReport.REVIEW_HISTORY,
            CapabilityState.UNAVAILABLE,
            reviewReason,
        ),
        AnalyticsAnkiReportCapability(
            AnalyticsAnkiReport.RETENTION,
            CapabilityState.UNAVAILABLE,
            reviewReason,
        ),
        AnalyticsAnkiReportCapability(
            AnalyticsAnkiReport.REVIEW_TIME,
            CapabilityState.UNAVAILABLE,
            reviewReason,
        ),
    )
}

private val INVENTORY_GOAL_METRICS = setOf("new_characters")
private const val MINIMUM_ANKI_COMPARISON_SAMPLE_SIZE = 20

private fun StatsFilter.stableHash(): String {
    val value = listOf(
        dateRange,
        comparisonRange,
        mediaKinds.sortedBy { it.name },
        profileIds.sorted(),
        languageTags.sortedBy { it.value },
        titleIds.sortedBy { it.value },
        includeLegacyAggregates,
        characterMetric,
        includeRereadsAndReplays,
        maturityTiers.sortedBy { it.name },
        ankiMaturityAggregation,
        provenanceStates.sortedBy { it.name },
    ).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
