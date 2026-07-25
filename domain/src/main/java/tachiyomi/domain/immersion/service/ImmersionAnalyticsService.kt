package tachiyomi.domain.immersion.service

import tachiyomi.domain.immersion.model.AnalyticsAnkiSummary
import tachiyomi.domain.immersion.model.AnalyticsBucketScale
import tachiyomi.domain.immersion.model.AnalyticsCharacterRow
import tachiyomi.domain.immersion.model.AnalyticsComparison
import tachiyomi.domain.immersion.model.AnalyticsGoalProgress
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
import tachiyomi.domain.immersion.model.AnalyticsTitleRow
import tachiyomi.domain.immersion.model.AnalyticsTrendPoint
import tachiyomi.domain.immersion.model.AnalyticsTrends
import tachiyomi.domain.immersion.model.AnalyticsWordRow
import tachiyomi.domain.immersion.model.CapabilityState
import tachiyomi.domain.immersion.model.CharacterCoverage
import tachiyomi.domain.immersion.model.CharacterVolume
import tachiyomi.domain.immersion.model.ImmersionDailyRollup
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionGoalAchievement
import tachiyomi.domain.immersion.model.ImmersionGoalCheckIn
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.ImmersionRollupRebuildResult
import tachiyomi.domain.immersion.model.LocalDateRange
import tachiyomi.domain.immersion.model.MaturityTier
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.ReadingMetrics
import tachiyomi.domain.immersion.model.SessionCursor
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SessionPage
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.model.UnicodeCodePoint
import tachiyomi.domain.immersion.repository.ImmersionAnalyticsRepository
import tachiyomi.domain.immersion.repository.ImmersionGoalRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.DayOfWeek
import java.util.UUID
import kotlin.math.max

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
            val currentFilter = filter.copy(dateRange = range)
            val current = currentRows.sumMetrics().withInventory(
                analyticsRepository.inventoryMetrics(currentFilter),
            )
            val previousRange = filter.comparisonRange ?: calendar.previousEqualRange(range)
            val previousFilter = filter.copy(dateRange = previousRange, comparisonRange = null)
            val previous = analyticsRepository.dailyRollups(previousRange)
                .filter(previousFilter::matches)
                .sumMetrics()
                .withInventory(analyticsRepository.inventoryMetrics(previousFilter))
            val activeDates = currentRows
                .filter { it.metrics.hasActivity() }
                .mapTo(sortedSetOf()) { it.date }
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
                        current.characters.gross.value,
                        previous.characters.gross.value,
                    ),
                ),
                streak = streak(activeDates, range.endInclusive),
            ) to currentRows.size
        }

    suspend fun trends(
        filter: StatsFilter,
        scale: AnalyticsBucketScale,
    ): AnalyticsResult<AnalyticsTrends> =
        measured(AnalyticsQueryFamily.TRENDS, filter) {
            val range = effectiveRange(filter)
            val rows = analyticsRepository.dailyRollups(range).filter(filter::matches)
            var cumulative = ReadingMetrics()
            val points = calendar.buckets(range, scale).map { bucket ->
                val metrics = rows.filter {
                    it.date >= bucket.start && it.date <= bucket.endInclusive
                }.sumMetrics()
                cumulative += metrics
                AnalyticsTrendPoint(bucket, metrics, cumulative)
            }
            AnalyticsTrends(scale, points) to rows.size
        }

    suspend fun titles(
        filter: StatsFilter,
        sort: AnalyticsSort,
    ): AnalyticsResult<List<AnalyticsTitleRow>> =
        measured(AnalyticsQueryFamily.TITLES, filter) {
            val range = effectiveRange(filter)
            val rows = analyticsRepository.dailyRollups(range).filter(filter::matches)
            val metadata = analyticsRepository.titleMetadata(rows.mapTo(mutableSetOf()) { it.titleId })
                .associateBy { it.titleId }
            val inventory = analyticsRepository.titleInventoryMetrics(filter.copy(dateRange = range))
            val result = rows.groupBy { it.titleId }.mapNotNull { (titleId, titleRows) ->
                val title = metadata[titleId] ?: return@mapNotNull null
                val metrics = titleRows.sumMetrics().withInventory(
                    inventory[titleId] ?: AnalyticsInventoryMetrics(),
                )
                AnalyticsTitleRow(
                    titleId = titleId,
                    displayTitle = title.displayTitle,
                    mediaKind = title.mediaKind,
                    languageTag = title.languageTag,
                    metrics = metrics,
                    firstActiveDate = titleRows.minOf { it.date },
                    lastActiveDate = titleRows.maxOf { it.date },
                    progress = title.totalCharacterEstimate
                        ?.takeIf { it > 0 }
                        ?.let { metrics.characters.netProgress.value.toDouble() / it.toDouble() }
                        ?.coerceIn(0.0, 1.0),
                    completed = title.completed,
                )
            }.sortedWith(titleComparator(sort))
            result to rows.size
        }

    suspend fun vocabulary(
        filter: StatsFilter,
        sort: AnalyticsSort,
        offset: Long,
        limit: Int,
        searchQuery: String? = null,
    ): AnalyticsResult<AnalyticsPage<AnalyticsWordRow>> =
        measured(AnalyticsQueryFamily.VOCABULARY, filter) {
            analyticsRepository.vocabularyPage(filter, sort, offset, limit, searchQuery).let {
                it to it.items.size
            }
        }

    suspend fun characters(
        filter: StatsFilter,
        sort: AnalyticsSort,
        offset: Long,
        limit: Int,
        searchQuery: String? = null,
    ): AnalyticsResult<AnalyticsPage<AnalyticsCharacterRow>> =
        measured(AnalyticsQueryFamily.CHARACTERS, filter) {
            analyticsRepository.characterPage(filter, sort, offset, limit, searchQuery).let {
                it to it.items.size
            }
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

    suspend fun sourceSearch(
        filter: StatsFilter,
        query: String,
        offset: Long,
        limit: Int,
    ): AnalyticsResult<AnalyticsPage<AnalyticsSourceOccurrence>> =
        measured(AnalyticsQueryFamily.SESSIONS, filter) {
            analyticsRepository.sourceSearch(filter, query, offset, limit).let {
                it to it.items.size
            }
        }

    suspend fun wordOccurrences(
        filter: StatsFilter,
        wordId: String,
        offset: Long,
        limit: Int,
    ): AnalyticsResult<AnalyticsPage<AnalyticsSourceOccurrence>> =
        measured(AnalyticsQueryFamily.VOCABULARY, filter) {
            analyticsRepository.wordOccurrences(filter, wordId, offset, limit).let {
                it to it.items.size
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
            val range = effectiveRange(filter)
            val rows = analyticsRepository.dailyRollups(range).filter(filter::matches)
            val goals = goalRepository.getGoals().filter { it.state != "ARCHIVED" }
            val progress = goals.map { goal ->
                val checkIns = goalRepository.getCheckIns(goal.id)
                goal.progress(rows, range, checkIns)
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
            val missingFilter = effectiveFilter.copy(maturityTiers = setOf(MaturityTier.UNKNOWN))
            val missingWords = analyticsRepository.vocabularyPage(
                missingFilter,
                AnalyticsSort.FREQUENCY_RANK,
                0,
                20,
            ).items
            val missingCharacters = analyticsRepository.characterPage(
                missingFilter,
                AnalyticsSort.FREQUENCY_RANK,
                0,
                20,
            ).items
            analyticsRepository.ankiSummary(effectiveFilter).copy(
                cardsCreated = rollups.sumOf { it.metrics.cardsCreated.value },
                cardsUpdated = rollups.sumOf { it.metrics.cardsUpdated.value },
                missingHighFrequencyWords = missingWords,
                missingHighFrequencyCharacters = missingCharacters,
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
        while (ImmersionLocalDate(cursor) in dates) {
            current++
            cursor--
        }
        return AnalyticsStreak(current, longest, dates)
    }
}

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

private fun ReadingMetrics.withInventory(inventory: AnalyticsInventoryMetrics): ReadingMetrics =
    copy(
        distinctCharacters = NonNegativeCounter(inventory.distinctCharacters),
        newCharacters = NonNegativeCounter(inventory.newCharacters),
        uniqueWords = NonNegativeCounter(inventory.uniqueWords),
        newWords = NonNegativeCounter(inventory.newWords),
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
        wordsEncountered = wordsEncountered + other.wordsEncountered,
        uniqueWords = uniqueWords + other.uniqueWords,
        newWords = newWords + other.newWords,
        sourceUnits = sourceUnits + other.sourceUnits,
        sessions = sessions + other.sessions,
        successfulLookups = successfulLookups + other.successfulLookups,
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
        successfulLookups.value > 0 ||
        cardsCreated.value > 0

private fun ratioChange(current: Long, previous: Long): Double? =
    if (previous == 0L) null else (current - previous).toDouble() / previous.toDouble()

private fun titleComparator(sort: AnalyticsSort): Comparator<AnalyticsTitleRow> =
    when (sort) {
        AnalyticsSort.MOST_RECENT -> compareByDescending<AnalyticsTitleRow> { it.lastActiveDate }
            .thenBy { it.displayTitle }
            .thenBy { it.titleId.value }
        AnalyticsSort.MOST_TIME -> compareByDescending<AnalyticsTitleRow> { it.metrics.activeTime.value }
            .thenByDescending { it.lastActiveDate }
            .thenBy { it.titleId.value }
        AnalyticsSort.MOST_CHARACTERS, AnalyticsSort.MOST_OCCURRENCES ->
            compareByDescending<AnalyticsTitleRow> { it.metrics.characters.gross.value }
                .thenByDescending { it.lastActiveDate }
                .thenBy { it.titleId.value }
        AnalyticsSort.FIRST_SEEN -> compareBy<AnalyticsTitleRow> { it.firstActiveDate }
            .thenBy { it.titleId.value }
        AnalyticsSort.ALPHABETICAL -> compareBy<AnalyticsTitleRow> { it.displayTitle }
            .thenBy { it.titleId.value }
        AnalyticsSort.FREQUENCY_RANK -> compareBy<AnalyticsTitleRow> {
            it.metrics.characters.gross.value
        }.thenBy { it.titleId.value }
    }

private fun ImmersionGoal.progress(
    rows: List<ImmersionDailyRollup>,
    range: LocalDateRange,
    checkIns: List<ImmersionGoalCheckIn>,
): AnalyticsGoalProgress {
    val matchingRows = rows.filter {
        (mediaKind == null || it.mediaKind == mediaKind) &&
            (profileId == null || it.profileId == profileId) &&
            (languageTag == null || it.languageTag == languageTag) &&
            (titleId == null || it.titleId == titleId)
    }
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

    val multipliers = weekdayMultipliers.parseWeekdayMultipliers()
    val rowsByDate = matchingRows.groupBy(ImmersionDailyRollup::date)
    val checkInsByDate = checkIns.associateBy(ImmersionGoalCheckIn::localDate)
    val dates = (effectiveStart.epochDay..effectiveEnd.epochDay).map(::ImmersionLocalDate)
    val achievedByDate = dates.associateWith { date ->
        if (type == "MANUAL" || metric == "manual") {
            if (checkInsByDate[date]?.status == "COMPLETED") 1.0 else 0.0
        } else {
            rowsByDate[date].orEmpty().sumOf { it.metrics.valueForGoal(metric) }
        }
    }
    val achieved = achievedByDate.values.sum()
    val dailyGoal = period == "DAILY" || type == "PERPETUAL_DAILY"
    val targetToDate = if (dailyGoal) {
        dates.sumOf { target * multipliers.multiplier(it) }
    } else {
        target
    }
    val activeDates = dates.filter { multipliers.multiplier(it) > 0.0 }
    val pace = if (activeDates.isEmpty()) null else achieved / activeDates.size
    val rollingSeven = achievedByDate.rollingPace(effectiveEnd, 7, multipliers)
    val rollingThirty = achievedByDate.rollingPace(effectiveEnd, 30, multipliers)
    val sampleDays = achievedByDate.count { (date, value) ->
        multipliers.multiplier(date) > 0.0 && value > 0.0
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
    val remainingActiveDays = endDate?.let { deadline ->
        if (deadline <= effectiveEnd) {
            0
        } else {
            (effectiveEnd.epochDay + 1..deadline.epochDay)
                .map(::ImmersionLocalDate)
                .count { multipliers.multiplier(it) > 0.0 }
        }
    }
    val requiredPace = remainingActiveDays
        ?.takeIf { it > 0 }
        ?.let { remaining / it }
    val (currentStreak, longestStreak) = if (dailyGoal) {
        dailyGoalStreaks(achievedByDate, target, multipliers, effectiveEnd)
    } else {
        0 to 0
    }
    return AnalyticsGoalProgress(
        goal = this,
        achieved = achieved,
        target = target,
        pacePerDay = pace,
        projectedCompletionDate = projected,
        achievedAtEpochMillis = if (targetToDate > 0 && achieved >= targetToDate) {
            matchingRows.maxOfOrNull { it.date.epochDay }?.let {
                ImmersionLocalDate(it).toLocalDate()
                    .atStartOfDay(java.time.ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            }
        } else {
            null
        },
        targetToDate = targetToDate,
        requiredPacePerActiveDay = requiredPace,
        rollingSevenDayPace = rollingSeven,
        rollingThirtyDayPace = rollingThirty,
        currentStreakDays = currentStreak,
        longestStreakDays = longestStreak,
        isRestDay = multipliers.multiplier(effectiveEnd) == 0.0,
        forecastConfidence = forecastConfidence,
    )
}

private fun ReadingMetrics.valueForGoal(metric: String): Double = when (metric) {
    "active_time_ms" -> activeTime.value.toDouble()
    "gross_characters", "characters" -> characters.gross.value.toDouble()
    "unique_source_characters" -> characters.uniqueSource.value.toDouble()
    "net_characters" -> characters.netProgress.value.toDouble()
    "words" -> wordsEncountered.value.toDouble()
    "new_words" -> newWords.value.toDouble()
    "new_characters" -> newCharacters.value.toDouble()
    "sessions" -> sessions.value.toDouble()
    "lookups" -> successfulLookups.value.toDouble()
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

private fun Map<ImmersionLocalDate, Double>.rollingPace(
    end: ImmersionLocalDate,
    days: Int,
    multipliers: Map<DayOfWeek, Double>,
): Double? {
    val start = end.epochDay - days + 1
    val relevant = filterKeys { it.epochDay in start..end.epochDay }
    val activeDayCount = relevant.keys.count { multipliers.multiplier(it) > 0.0 }
    return if (activeDayCount == 0) null else relevant.values.sum() / activeDayCount
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
    var firstActiveDay = true
    while (cursor in achievedByDate) {
        val multiplier = multipliers.multiplier(cursor)
        if (multiplier == 0.0) {
            cursor = ImmersionLocalDate(cursor.epochDay - 1)
            continue
        }
        val met = achievedByDate.getValue(cursor) >= target * multiplier
        if (!met && firstActiveDay) {
            firstActiveDay = false
            cursor = ImmersionLocalDate(cursor.epochDay - 1)
            continue
        }
        if (!met) break
        current++
        firstActiveDay = false
        cursor = ImmersionLocalDate(cursor.epochDay - 1)
    }
    return current to longest
}

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
        provenanceStates.sortedBy { it.name },
    ).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
