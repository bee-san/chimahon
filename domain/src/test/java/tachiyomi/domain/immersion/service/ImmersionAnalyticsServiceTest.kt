// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.service

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.AnalyticsBucketInventory
import tachiyomi.domain.immersion.model.AnalyticsBucketScale
import tachiyomi.domain.immersion.model.AnalyticsDataQuality
import tachiyomi.domain.immersion.model.AnalyticsInventoryMetrics
import tachiyomi.domain.immersion.model.CharacterVolume
import tachiyomi.domain.immersion.model.ImmersionDailyRollup
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.LocalDateRange
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.MillisecondDuration
import tachiyomi.domain.immersion.model.NetCharacterProgress
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.ProvenanceState
import tachiyomi.domain.immersion.model.ReadingMetrics
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.repository.ImmersionAnalyticsRepository
import tachiyomi.domain.immersion.repository.ImmersionGoalRepository
import java.time.Instant

class ImmersionAnalyticsServiceTest {
    private val repository = mockk<ImmersionAnalyticsRepository>()
    private val goalRepository = mockk<ImmersionGoalRepository>()

    @Test
    fun `trends zero-fill dates and apply replay filtering before cumulative totals`() = runTest {
        val range = LocalDateRange(date("2026-07-01"), date("2026-07-03"))
        val rows = listOf(
            rollup("2026-07-01", 100, replay = false),
            rollup("2026-07-01", 40, replay = true),
            rollup("2026-07-03", 300, replay = false),
        )
        stub(rows)
        val service = ImmersionAnalyticsService(repository, goalRepository)

        val result = service.trends(
            StatsFilter(dateRange = range, includeRereadsAndReplays = false),
            AnalyticsBucketScale.DAY,
        )

        result.value.points shouldHaveSize 3
        result.value.points.map { it.metrics.characters.gross.value } shouldBe listOf(100, 0, 300)
        result.value.points.map { it.cumulativeMetrics.characters.gross.value } shouldBe
            listOf(100, 100, 400)
        result.diagnostics.rowCount shouldBe 2
    }

    @Test
    fun `trends replace poisoned rollup inventory with query-time bucket and cumulative values`() = runTest {
        val firstDay = LocalDateRange(date("2026-07-01"), date("2026-07-01"))
        val secondDay = LocalDateRange(date("2026-07-02"), date("2026-07-02"))
        val range = LocalDateRange(firstDay.start, secondDay.endInclusive)
        val rows = listOf(
            rollup("2026-07-01", 100, titleId = TITLE, poisonedInventory = 100),
            rollup("2026-07-01", 40, titleId = TITLE_TWO, poisonedInventory = 200),
            rollup("2026-07-02", 300, titleId = TITLE, poisonedInventory = 300),
        )
        stub(rows)
        coEvery {
            repository.bucketInventoryMetrics(
                StatsFilter(dateRange = range),
                listOf(firstDay, secondDay),
            )
        } returns listOf(
            AnalyticsBucketInventory(
                metrics = inventory(distinct = 3, new = 3),
                cumulative = inventory(distinct = 3, new = 3),
            ),
            AnalyticsBucketInventory(
                metrics = inventory(distinct = 2, new = 1),
                cumulative = inventory(distinct = 4, new = 4),
            ),
        )
        val service = ImmersionAnalyticsService(repository, goalRepository)

        val points = service.trends(
            StatsFilter(dateRange = range),
            AnalyticsBucketScale.DAY,
        ).value.points

        points.map { it.metrics.characters.gross.value } shouldBe listOf(140, 300)
        points.map { it.cumulativeMetrics.characters.gross.value } shouldBe listOf(140, 440)
        points.map { it.metrics.distinctCharacters.value } shouldBe listOf(3, 2)
        points.map { it.metrics.newCharacters.value } shouldBe listOf(3, 1)
        points.map { it.metrics.uniqueWords.value } shouldBe listOf(3, 2)
        points.map { it.metrics.newWords.value } shouldBe listOf(3, 1)
        points.map { it.cumulativeMetrics.distinctCharacters.value } shouldBe listOf(3, 4)
        points.map { it.cumulativeMetrics.newCharacters.value } shouldBe listOf(3, 4)
        points.map { it.cumulativeMetrics.uniqueWords.value } shouldBe listOf(3, 4)
        points.map { it.cumulativeMetrics.newWords.value } shouldBe listOf(3, 4)
    }

    @Test
    fun `overview compares an equal previous period and computes current streak`() = runTest {
        val current = LocalDateRange(date("2026-07-02"), date("2026-07-03"))
        val rows = listOf(
            rollup("2026-06-30", 50),
            rollup("2026-07-01", 50),
            rollup("2026-07-02", 100),
            rollup("2026-07-03", 200),
        )
        stub(rows)
        val service = serviceAt("2026-07-03T12:00:00Z")

        val overview = service.overview(StatsFilter(dateRange = current)).value

        overview.comparison.current.characters.gross.value shouldBe 300
        overview.comparison.previous?.characters?.gross?.value shouldBe 100
        overview.comparison.characterChangeRatio shouldBe 2.0
        overview.streak.currentDays shouldBe 4
        overview.streak.longestDays shouldBe 4
    }

    @Test
    fun `overview keeps a streak through yesterday before todays first activity`() = runTest {
        val rows = listOf(
            rollup("2026-07-01", 100),
            rollup("2026-07-02", 100),
        )
        stub(rows)

        val overview = serviceAt("2026-07-03T12:00:00Z")
            .overview(StatsFilter(dateRange = LocalDateRange(date("2026-07-02"), date("2026-07-02"))))
            .value

        overview.streak.currentDays shouldBe 2
        overview.streak.longestDays shouldBe 2
    }

    @Test
    fun `daily goals apply weekday multipliers and rest days without breaking streaks`() = runTest {
        val range = LocalDateRange(date("2026-07-24"), date("2026-07-26"))
        stub(
            listOf(
                rollup("2026-07-24", 100),
                rollup("2026-07-25", 50),
            ),
        )
        coEvery { goalRepository.getGoals() } returns listOf(
            ImmersionGoal(
                id = "daily-characters",
                type = "PERPETUAL_DAILY",
                metric = "gross_characters",
                target = 100.0,
                period = "DAILY",
                startDate = range.start,
                endDate = null,
                mediaKind = null,
                profileId = null,
                languageTag = null,
                titleId = null,
                weekdayMultipliers = "SATURDAY=0.5,SUNDAY=0",
                restDayPolicy = "SKIP",
                state = "ACTIVE",
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 1,
            ),
        )
        coEvery { goalRepository.getCheckIns(any()) } returns emptyList()
        coEvery { goalRepository.getAchievements(any()) } returns emptyList()
        coJustRun { goalRepository.recordAchievement(any()) }
        val service = serviceAt("2026-07-26T12:00:00Z")

        val progress = service.goals(StatsFilter(dateRange = range)).value.single()

        progress.achieved shouldBe 150.0
        progress.targetToDate shouldBe 150.0
        progress.currentStreakDays shouldBe 2
        progress.longestStreakDays shouldBe 2
        progress.isRestDay shouldBe true
    }

    @Test
    fun `goals evaluate stored history instead of the dashboard date range`() = runTest {
        val range = LocalDateRange(date("2026-07-01"), date("2026-07-03"))
        stub(
            listOf(
                rollup("2026-07-01", 100),
                rollup("2026-07-02", 100),
                rollup("2026-07-03", 100),
            ),
        )
        stubGoals(
            goal(
                id = "history",
                startDate = range.start,
            ),
        )

        val progress = serviceAt("2026-07-03T12:00:00Z")
            .goals(StatsFilter(dateRange = LocalDateRange(range.endInclusive, range.endInclusive)))
            .value
            .single()

        progress.achieved shouldBe 300.0
        progress.targetToDate shouldBe 300.0
        progress.rollingSevenDayPace shouldBe 100.0
        progress.currentStreakDays shouldBe 3
        progress.longestStreakDays shouldBe 3
    }

    @Test
    fun `goal achievement timestamps never use activity after the goal deadline`() = runTest {
        val first = date("2026-07-01")
        val deadline = date("2026-07-02")
        val later = date("2026-07-03")
        stub(
            listOf(
                rollup("2026-07-01", 100),
                rollup("2026-07-03", 100),
            ),
        )
        stubGoals(
            goal(
                id = "historical",
                target = 100.0,
                period = "TOTAL",
                type = "DATE_BOUND_TOTAL",
                startDate = first,
                endDate = deadline,
            ),
            goal(
                id = "ongoing",
                target = 100.0,
                period = "TOTAL",
                type = "DATE_BOUND_TOTAL",
                startDate = first,
                endDate = later,
            ),
        )

        val progress = serviceAt("2026-07-03T12:00:00Z")
            .goals(StatsFilter())
            .value
            .associateBy { it.goal.id }

        progress.getValue("historical").achieved shouldBe 100.0
        progress.getValue("historical").achievedAtEpochMillis shouldBe
            Instant.parse("2026-07-01T00:00:00Z").toEpochMilli()
        progress.getValue("ongoing").achievedAtEpochMillis shouldBe
            Instant.parse("2026-07-01T00:00:00Z").toEpochMilli()
    }

    @Test
    fun `only the actual current day may be incomplete without breaking a goal streak`() = runTest {
        val first = date("2026-07-01")
        val second = date("2026-07-02")
        stub(
            listOf(
                rollup("2026-07-01", 100, titleId = TITLE),
                rollup("2026-07-02", 0, titleId = TITLE),
                rollup("2026-07-01", 100, titleId = TITLE_TWO),
                rollup("2026-07-02", 100, titleId = TITLE_TWO),
            ),
        )
        stubGoals(
            goal(
                id = "ongoing",
                startDate = first,
                titleId = TITLE_TWO,
            ),
            goal(
                id = "historical",
                startDate = first,
                endDate = second,
                titleId = TITLE,
            ),
        )

        val progress = serviceAt("2026-07-03T12:00:00Z")
            .goals(StatsFilter(dateRange = LocalDateRange(date("2026-07-03"), date("2026-07-03"))))
            .value
            .associateBy { it.goal.id }

        progress.getValue("ongoing").currentStreakDays shouldBe 2
        progress.getValue("ongoing").longestStreakDays shouldBe 2
        progress.getValue("historical").currentStreakDays shouldBe 0
        progress.getValue("historical").longestStreakDays shouldBe 1
    }

    @Test
    fun `novelty goals use cached query-time inventory over their full scoped range`() = runTest {
        val firstDay = LocalDateRange(date("2026-07-01"), date("2026-07-01"))
        val secondDay = LocalDateRange(date("2026-07-02"), date("2026-07-02"))
        val range = LocalDateRange(firstDay.start, secondDay.endInclusive)
        stub(
            listOf(
                rollup("2026-07-01", 100, poisonedInventory = 100),
                rollup("2026-07-02", 100, poisonedInventory = 100),
            ),
        )
        stubGoals(
            goal(
                id = "new-words",
                metric = "new_words",
                target = 20.0,
                period = "TOTAL",
                type = "DATE_BOUND_TOTAL",
                startDate = range.start,
                titleId = TITLE,
            ),
            goal(
                id = "new-characters",
                metric = "new_characters",
                target = 20.0,
                period = "TOTAL",
                type = "DATE_BOUND_TOTAL",
                startDate = range.start,
                titleId = TITLE,
            ),
        )
        val goalFilter = StatsFilter(dateRange = range, titleIds = setOf(TITLE))
        coEvery {
            repository.bucketInventoryMetrics(goalFilter, listOf(firstDay, secondDay))
        } returns listOf(
            AnalyticsBucketInventory(
                metrics = AnalyticsInventoryMetrics(
                    distinctCharacters = 2,
                    newCharacters = 2,
                    uniqueWords = 1,
                    newWords = 1,
                ),
                cumulative = AnalyticsInventoryMetrics(
                    distinctCharacters = 2,
                    newCharacters = 2,
                    uniqueWords = 1,
                    newWords = 1,
                ),
            ),
            AnalyticsBucketInventory(
                metrics = AnalyticsInventoryMetrics(
                    distinctCharacters = 1,
                    newCharacters = 1,
                    uniqueWords = 2,
                    newWords = 2,
                ),
                cumulative = AnalyticsInventoryMetrics(
                    distinctCharacters = 3,
                    newCharacters = 3,
                    uniqueWords = 3,
                    newWords = 3,
                ),
            ),
        )

        val progress = serviceAt("2026-07-02T12:00:00Z")
            .goals(StatsFilter(dateRange = secondDay))
            .value
            .associateBy { it.goal.id }

        progress.getValue("new-words").achieved shouldBe 3.0
        progress.getValue("new-characters").achieved shouldBe 3.0
        coVerify(exactly = 1) {
            repository.bucketInventoryMetrics(goalFilter, listOf(firstDay, secondDay))
        }
    }

    private fun stub(rows: List<ImmersionDailyRollup>) {
        coEvery { repository.dataQuality(any(), any()) } returns AnalyticsDataQuality()
        coEvery { repository.inventoryMetrics(any()) } returns AnalyticsInventoryMetrics()
        coEvery { repository.bucketInventoryMetrics(any(), any()) } answers {
            secondArg<List<LocalDateRange>>().map {
                AnalyticsBucketInventory(
                    metrics = AnalyticsInventoryMetrics(),
                    cumulative = AnalyticsInventoryMetrics(),
                )
            }
        }
        coEvery { repository.titleInventoryMetrics(any()) } returns emptyMap()
        coEvery { repository.availableDateRange(any()) } returns LocalDateRange(
            rows.minOf { it.date },
            rows.maxOf { it.date },
        )
        coEvery { repository.dailyRollups(any()) } answers {
            val range = firstArg<LocalDateRange>()
            rows.filter { it.date in range }
        }
    }

    private fun stubGoals(vararg goals: ImmersionGoal) {
        coEvery { goalRepository.getGoals() } returns goals.toList()
        coEvery { goalRepository.getCheckIns(any()) } returns emptyList()
        coEvery { goalRepository.getAchievements(any()) } returns emptyList()
        coJustRun { goalRepository.recordAchievement(any()) }
    }

    private fun goal(
        id: String,
        metric: String = "gross_characters",
        target: Double = 100.0,
        period: String = "DAILY",
        type: String = "PERPETUAL_DAILY",
        startDate: ImmersionLocalDate,
        endDate: ImmersionLocalDate? = null,
        titleId: TitleId? = null,
    ) = ImmersionGoal(
        id = id,
        type = type,
        metric = metric,
        target = target,
        period = period,
        startDate = startDate,
        endDate = endDate,
        mediaKind = null,
        profileId = null,
        languageTag = null,
        titleId = titleId,
        weekdayMultipliers = null,
        restDayPolicy = "SKIP",
        state = "ACTIVE",
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

    private fun serviceAt(instant: String) =
        ImmersionAnalyticsService(
            analyticsRepository = repository,
            goalRepository = goalRepository,
            clock = { Instant.parse(instant).toEpochMilli() },
            currentOffsetSeconds = { 0 },
        )

    private fun rollup(
        date: String,
        characters: Long,
        replay: Boolean = false,
        titleId: TitleId = TITLE,
        poisonedInventory: Long = 0,
    ) = ImmersionDailyRollup(
        date = date(date),
        profileId = "default",
        languageTag = LanguageTag("ja"),
        mediaKind = MediaKind.NOVEL,
        titleId = titleId,
        metrics = ReadingMetrics(
            activeTime = MillisecondDuration(characters * 10),
            characters = CharacterVolume(
                gross = NonNegativeCounter(characters),
                uniqueSource = NonNegativeCounter(characters),
                netProgress = NetCharacterProgress(characters),
            ),
            distinctCharacters = NonNegativeCounter(poisonedInventory),
            newCharacters = NonNegativeCounter(poisonedInventory),
            uniqueWords = NonNegativeCounter(poisonedInventory),
            newWords = NonNegativeCounter(poisonedInventory),
            sessions = NonNegativeCounter(1),
        ),
        provenanceState = ProvenanceState.AVAILABLE,
        replay = replay,
        rollupVersion = 2,
    )

    private fun inventory(
        distinct: Long,
        new: Long,
    ) = AnalyticsInventoryMetrics(
        distinctCharacters = distinct,
        newCharacters = new,
        uniqueWords = distinct,
        newWords = new,
    )

    private fun date(value: String) = ImmersionLocalDate.parse(value)

    companion object {
        private val TITLE = TitleId("00000000-0000-0000-0000-000000000001")
        private val TITLE_TWO = TitleId("00000000-0000-0000-0000-000000000002")
    }
}
