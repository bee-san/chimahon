package tachiyomi.domain.immersion.service

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
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
    fun `overview compares an equal previous period and computes current streak`() = runTest {
        val current = LocalDateRange(date("2026-07-02"), date("2026-07-03"))
        val rows = listOf(
            rollup("2026-06-30", 50),
            rollup("2026-07-01", 50),
            rollup("2026-07-02", 100),
            rollup("2026-07-03", 200),
        )
        stub(rows)
        val service = ImmersionAnalyticsService(repository, goalRepository)

        val overview = service.overview(StatsFilter(dateRange = current)).value

        overview.comparison.current.characters.gross.value shouldBe 300
        overview.comparison.previous?.characters?.gross?.value shouldBe 100
        overview.comparison.characterChangeRatio shouldBe 2.0
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
        val service = ImmersionAnalyticsService(repository, goalRepository)

        val progress = service.goals(StatsFilter(dateRange = range)).value.single()

        progress.achieved shouldBe 150.0
        progress.targetToDate shouldBe 150.0
        progress.currentStreakDays shouldBe 2
        progress.longestStreakDays shouldBe 2
        progress.isRestDay shouldBe true
    }

    private fun stub(rows: List<ImmersionDailyRollup>) {
        coEvery { repository.dataQuality(any(), any()) } returns AnalyticsDataQuality()
        coEvery { repository.inventoryMetrics(any()) } returns AnalyticsInventoryMetrics()
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

    private fun rollup(
        date: String,
        characters: Long,
        replay: Boolean = false,
    ) = ImmersionDailyRollup(
        date = date(date),
        profileId = "default",
        languageTag = LanguageTag("ja"),
        mediaKind = MediaKind.NOVEL,
        titleId = TITLE,
        metrics = ReadingMetrics(
            activeTime = MillisecondDuration(characters * 10),
            characters = CharacterVolume(
                gross = NonNegativeCounter(characters),
                uniqueSource = NonNegativeCounter(characters),
                netProgress = NetCharacterProgress(characters),
            ),
            sessions = NonNegativeCounter(1),
        ),
        provenanceState = ProvenanceState.AVAILABLE,
        replay = replay,
        rollupVersion = 2,
    )

    private fun date(value: String) = ImmersionLocalDate.parse(value)

    companion object {
        private val TITLE = TitleId("00000000-0000-0000-0000-000000000001")
    }
}
