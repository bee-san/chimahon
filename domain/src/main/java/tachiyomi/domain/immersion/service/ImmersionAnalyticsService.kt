package tachiyomi.domain.immersion.service

import tachiyomi.domain.immersion.model.AnalyticsAnkiSummary
import tachiyomi.domain.immersion.model.AnalyticsBucketScale
import tachiyomi.domain.immersion.model.AnalyticsCharacterRow
import tachiyomi.domain.immersion.model.AnalyticsComparison
import tachiyomi.domain.immersion.model.AnalyticsGoalProgress
import tachiyomi.domain.immersion.model.AnalyticsOverview
import tachiyomi.domain.immersion.model.AnalyticsPage
import tachiyomi.domain.immersion.model.AnalyticsPeriod
import tachiyomi.domain.immersion.model.AnalyticsQueryDiagnostics
import tachiyomi.domain.immersion.model.AnalyticsQueryFamily
import tachiyomi.domain.immersion.model.AnalyticsResult
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.AnalyticsStreak
import tachiyomi.domain.immersion.model.AnalyticsTitleRow
import tachiyomi.domain.immersion.model.AnalyticsTrendPoint
import tachiyomi.domain.immersion.model.AnalyticsTrends
import tachiyomi.domain.immersion.model.AnalyticsWordRow
import tachiyomi.domain.immersion.model.CharacterCoverage
import tachiyomi.domain.immersion.model.CharacterVolume
import tachiyomi.domain.immersion.model.ImmersionDailyRollup
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.ImmersionRollupRebuildResult
import tachiyomi.domain.immersion.model.LocalDateRange
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.ReadingMetrics
import tachiyomi.domain.immersion.model.SessionCursor
import tachiyomi.domain.immersion.model.SessionPage
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.repository.ImmersionAnalyticsRepository
import tachiyomi.domain.immersion.repository.ImmersionGoalRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.ceil
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
            val current = currentRows.sumMetrics()
            val previousRange = filter.comparisonRange ?: calendar.previousEqualRange(range)
            val previousFilter = filter.copy(dateRange = previousRange, comparisonRange = null)
            val previous = analyticsRepository.dailyRollups(previousRange)
                .filter(previousFilter::matches)
                .sumMetrics()
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
            val result = rows.groupBy { it.titleId }.mapNotNull { (titleId, titleRows) ->
                val title = metadata[titleId] ?: return@mapNotNull null
                val metrics = titleRows.sumMetrics()
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
    ): AnalyticsResult<AnalyticsPage<AnalyticsWordRow>> =
        measured(AnalyticsQueryFamily.VOCABULARY, filter) {
            analyticsRepository.vocabularyPage(filter, sort, offset, limit).let {
                it to it.items.size
            }
        }

    suspend fun characters(
        filter: StatsFilter,
        sort: AnalyticsSort,
        offset: Long,
        limit: Int,
    ): AnalyticsResult<AnalyticsPage<AnalyticsCharacterRow>> =
        measured(AnalyticsQueryFamily.CHARACTERS, filter) {
            analyticsRepository.characterPage(filter, sort, offset, limit).let {
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

    suspend fun goals(filter: StatsFilter): AnalyticsResult<List<AnalyticsGoalProgress>> =
        measured(AnalyticsQueryFamily.GOALS, filter) {
            val range = effectiveRange(filter)
            val rows = analyticsRepository.dailyRollups(range).filter(filter::matches)
            goalRepository.getGoals().map { it.progress(rows, range) } to rows.size
        }

    suspend fun anki(filter: StatsFilter): AnalyticsResult<AnalyticsAnkiSummary> =
        measured(AnalyticsQueryFamily.ANKI, filter) {
            analyticsRepository.ankiSummary(filter) to 1
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
    }

private fun ImmersionGoal.progress(
    rows: List<ImmersionDailyRollup>,
    range: LocalDateRange,
): AnalyticsGoalProgress {
    val matchingRows = rows.filter {
        (mediaKind == null || it.mediaKind == mediaKind) &&
            (profileId == null || it.profileId == profileId) &&
            (languageTag == null || it.languageTag == languageTag) &&
            (titleId == null || it.titleId == titleId)
    }
    val achieved = when (metric) {
        "active_time_ms" -> matchingRows.sumOf { it.metrics.activeTime.value }.toDouble()
        "characters" -> matchingRows.sumOf { it.metrics.characters.gross.value }.toDouble()
        "words" -> matchingRows.sumOf { it.metrics.wordsEncountered.value }.toDouble()
        "sessions" -> matchingRows.sumOf { it.metrics.sessions.value }.toDouble()
        "lookups" -> matchingRows.sumOf { it.metrics.successfulLookups.value }.toDouble()
        "cards" -> matchingRows.sumOf { it.metrics.cardsCreated.value }.toDouble()
        else -> 0.0
    }
    val elapsedDays = (range.endInclusive.epochDay - range.start.epochDay + 1).coerceAtLeast(1)
    val pace = achieved / elapsedDays
    val remaining = (target - achieved).coerceAtLeast(0.0)
    val projected = if (remaining > 0 && pace > 0) {
        ImmersionLocalDate(range.endInclusive.epochDay + ceil(remaining / pace).toLong())
    } else {
        null
    }
    return AnalyticsGoalProgress(
        goal = this,
        achieved = achieved,
        target = target,
        pacePerDay = pace,
        projectedCompletionDate = projected,
        achievedAtEpochMillis = null,
    )
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
