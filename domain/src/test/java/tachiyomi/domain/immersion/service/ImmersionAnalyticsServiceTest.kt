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
import tachiyomi.domain.immersion.model.AnalyticsActivityTotals
import tachiyomi.domain.immersion.model.AnalyticsAnkiCapabilityReason
import tachiyomi.domain.immersion.model.AnalyticsAnkiReport
import tachiyomi.domain.immersion.model.AnalyticsAnkiSummary
import tachiyomi.domain.immersion.model.AnalyticsAnkiWeeklyImpact
import tachiyomi.domain.immersion.model.AnalyticsBucketInventory
import tachiyomi.domain.immersion.model.AnalyticsBucketScale
import tachiyomi.domain.immersion.model.AnalyticsCharacterFilter
import tachiyomi.domain.immersion.model.AnalyticsCharacterRow
import tachiyomi.domain.immersion.model.AnalyticsDataQuality
import tachiyomi.domain.immersion.model.AnalyticsEstimateUnit
import tachiyomi.domain.immersion.model.AnalyticsHourActivity
import tachiyomi.domain.immersion.model.AnalyticsInventoryMetrics
import tachiyomi.domain.immersion.model.AnalyticsPage
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.AnalyticsSourceOccurrence
import tachiyomi.domain.immersion.model.AnalyticsTemporalActivity
import tachiyomi.domain.immersion.model.AnalyticsTitleAcquisitionBucketSize
import tachiyomi.domain.immersion.model.AnalyticsTitleCompletedUnit
import tachiyomi.domain.immersion.model.AnalyticsTitleCoverage
import tachiyomi.domain.immersion.model.AnalyticsTitleCoverageFilter
import tachiyomi.domain.immersion.model.AnalyticsTitleFilter
import tachiyomi.domain.immersion.model.AnalyticsTitleMetadata
import tachiyomi.domain.immersion.model.AnalyticsTitleSeriesSelection
import tachiyomi.domain.immersion.model.AnalyticsTitleSort
import tachiyomi.domain.immersion.model.AnalyticsTitleStateFilter
import tachiyomi.domain.immersion.model.AnalyticsTitleTrendDailyPoint
import tachiyomi.domain.immersion.model.AnalyticsTitleUnitCompletionDay
import tachiyomi.domain.immersion.model.AnalyticsTitleUnitProgress
import tachiyomi.domain.immersion.model.AnalyticsTitleWordAcquisition
import tachiyomi.domain.immersion.model.AnalyticsTitleWordAcquisitionBucket
import tachiyomi.domain.immersion.model.AnalyticsVocabularyFirstSeenDay
import tachiyomi.domain.immersion.model.AnalyticsWeekdayActivity
import tachiyomi.domain.immersion.model.AnalyticsWordRow
import tachiyomi.domain.immersion.model.AnkiMaturityAggregation
import tachiyomi.domain.immersion.model.AnkiSnapshotStatus
import tachiyomi.domain.immersion.model.CapabilityState
import tachiyomi.domain.immersion.model.CharacterMetric
import tachiyomi.domain.immersion.model.CharacterVolume
import tachiyomi.domain.immersion.model.ImmersionAnkiSnapshot
import tachiyomi.domain.immersion.model.ImmersionDailyRollup
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.LocalDateRange
import tachiyomi.domain.immersion.model.MaturityTier
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.MillisecondDuration
import tachiyomi.domain.immersion.model.NetCharacterProgress
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.ProvenanceState
import tachiyomi.domain.immersion.model.ReadingMetrics
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SourceKind
import tachiyomi.domain.immersion.model.SourceUnitId
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.model.UnicodeCodePoint
import tachiyomi.domain.immersion.model.VocabularyFilter
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
    fun `temporal activity zero-fills every local hour and ISO weekday`() = runTest {
        val filter = StatsFilter(
            dateRange = LocalDateRange(date("2026-07-20"), date("2026-07-21")),
        )
        stub(listOf(rollup("2026-07-20", 100), rollup("2026-07-21", 200)))
        coEvery { repository.temporalActivity(filter) } returns AnalyticsTemporalActivity(
            hours = listOf(
                AnalyticsHourActivity(9, AnalyticsActivityTotals(3_600_000, 100, 90, 80)),
            ),
            weekdays = listOf(
                AnalyticsWeekdayActivity(1, AnalyticsActivityTotals(3_600_000, 100, 90, 80)),
            ),
        )

        val result = ImmersionAnalyticsService(repository, goalRepository).temporalActivity(filter)

        result.value.hours shouldHaveSize 24
        result.value.hours.map { it.hourOfDay } shouldBe (0..23).toList()
        result.value.hours.single { it.hourOfDay == 9 }.totals
            .readingSpeedPerHour(filter.characterMetric) shouldBe 100.0
        result.value.hours.single { it.hourOfDay == 10 }.totals shouldBe AnalyticsActivityTotals()
        result.value.weekdays shouldHaveSize 7
        result.value.weekdays.map { it.isoDayOfWeek } shouldBe (1..7).toList()
        result.diagnostics.rowCount shouldBe 2
    }

    @Test
    fun `title trends retain repository ranking and zero-fill each bounded series`() = runTest {
        val range = LocalDateRange(date("2026-07-20"), date("2026-07-22"))
        val filter = StatsFilter(dateRange = range)
        stub(listOf(rollup("2026-07-20", 100), rollup("2026-07-22", 300)))
        coEvery {
            repository.titleTrendDaily(
                filter,
                AnalyticsTitleSeriesSelection.TOP_CHARACTERS,
                2,
            )
        } returns listOf(
            AnalyticsTitleTrendDailyPoint(
                titleId = TITLE_TWO,
                displayTitle = "Second",
                mediaKind = MediaKind.NOVEL,
                languageTag = LanguageTag("ja"),
                date = date("2026-07-22"),
                metrics = metrics(300),
            ),
            AnalyticsTitleTrendDailyPoint(
                titleId = TITLE,
                displayTitle = "First",
                mediaKind = MediaKind.NOVEL,
                languageTag = LanguageTag("ja"),
                date = date("2026-07-20"),
                metrics = metrics(100),
            ),
        )

        val result = ImmersionAnalyticsService(repository, goalRepository).titleTrends(
            filter,
            AnalyticsBucketScale.DAY,
            maxTitles = 2,
        )

        result.value.series.map { it.titleId } shouldBe listOf(TITLE_TWO, TITLE)
        result.value.series.first().points.map { it.metrics.characters.gross.value } shouldBe
            listOf(0, 0, 300)
        result.value.series.last().points.map { it.metrics.characters.gross.value } shouldBe
            listOf(100, 0, 0)
        result.value.series.last().points.map { it.cumulativeMetrics.characters.gross.value } shouldBe
            listOf(100, 100, 100)
    }

    @Test
    fun `vocabulary first seen zero-fills buckets and accumulates only true global novelty`() = runTest {
        val range = LocalDateRange(date("2026-07-20"), date("2026-07-22"))
        val filter = StatsFilter(dateRange = range, titleIds = setOf(TITLE))
        stub(listOf(rollup("2026-07-20", 100), rollup("2026-07-22", 300)))
        coEvery { repository.vocabularyFirstSeenByDate(filter) } returns listOf(
            AnalyticsVocabularyFirstSeenDay(date("2026-07-20"), 2),
            AnalyticsVocabularyFirstSeenDay(date("2026-07-22"), 3),
        )

        val result = ImmersionAnalyticsService(repository, goalRepository).vocabularyFirstSeen(
            filter,
            AnalyticsBucketScale.DAY,
        )

        result.value.points.map { it.newWords } shouldBe listOf(2, 0, 3)
        result.value.points.map { it.cumulativeNewWords } shouldBe listOf(2, 2, 5)
        result.diagnostics.rowCount shouldBe 2
    }

    @Test
    fun `title estimate requires known total and stable qualifying pace`() = runTest {
        val rows = listOf(rollup("2026-06-30", 100_000)) +
            (1..5).map { day ->
                rollup("2026-07-0$day", 60_000)
            }
        stub(rows)
        coEvery { repository.titleMetadata(setOf(TITLE)) } returns listOf(
            titleMetadata(totalCharacterEstimate = 600_000),
        )

        val row = ImmersionAnalyticsService(repository, goalRepository)
            .titlePage(
                filter = StatsFilter(
                    dateRange = LocalDateRange(date("2026-07-01"), date("2026-07-05")),
                ),
                titleFilter = AnalyticsTitleFilter(),
                sort = AnalyticsTitleSort.PROGRESS,
                offset = 0,
                limit = 20,
            )
            .value
            .items
            .single()

        row.progress shouldBe (400_000.0 / 600_000.0)
        row.estimate?.remainingAmount shouldBe 200_000
        row.estimate?.estimatedActiveTimeMillis shouldBe 2_000_000
        row.estimate?.qualifyingDayCount shouldBe 5
        row.estimate?.confidence shouldBe
            tachiyomi.domain.immersion.model.AnalyticsEstimateConfidence.MEDIUM
        row.activeDays shouldBe 5
        row.calendarSpanDays shouldBe 5
    }

    @Test
    fun `title unit progress uses stable completion identities instead of source exposure`() = runTest {
        val rows = (1..5).map { day ->
            rollup("2026-07-0$day", 60_000, sourceUnits = 100)
        }
        stub(rows)
        coEvery { repository.titleMetadata(setOf(TITLE)) } returns listOf(
            titleMetadata(totalUnits = 10),
        )
        coEvery { repository.titleUnitProgress(any()) } returns mapOf(
            TITLE to AnalyticsTitleUnitProgress(
                identityAvailable = true,
                completedUnits = 5,
                identifiedCompletionEvents = 5,
                firstCompletionsByDay = (1..5).map { day ->
                    AnalyticsTitleUnitCompletionDay(date("2026-07-0$day"), 1)
                },
            ),
        )

        val row = ImmersionAnalyticsService(repository, goalRepository)
            .titlePage(
                filter = StatsFilter(
                    dateRange = LocalDateRange(date("2026-07-01"), date("2026-07-05")),
                ),
                titleFilter = AnalyticsTitleFilter(),
                sort = AnalyticsTitleSort.PROGRESS,
                offset = 0,
                limit = 20,
            )
            .value
            .items
            .single()

        row.unitProgress.completedUnits shouldBe 5
        row.progress shouldBe 0.5
        row.estimate?.unit shouldBe AnalyticsEstimateUnit.MEDIA_UNITS
        row.estimate?.remainingAmount shouldBe 5
        row.estimate?.estimatedActiveTimeMillis shouldBe 3_000_000
        row.metrics.sourceUnits.value shouldBe 500
    }

    @Test
    fun `title estimate stays unavailable for unknown total or unstable pace`() = runTest {
        val rows = listOf(
            rollup("2026-07-01", 60_000),
            rollup("2026-07-02", 1_000),
            rollup("2026-07-03", 120_000),
        )
        stub(rows)
        coEvery { repository.titleMetadata(setOf(TITLE)) } returns listOf(
            titleMetadata(totalUnits = 12, totalCharacterEstimate = null),
        )

        val unknown = ImmersionAnalyticsService(repository, goalRepository)
            .titlePage(
                StatsFilter(dateRange = LocalDateRange(date("2026-07-01"), date("2026-07-03"))),
                AnalyticsTitleFilter(),
                AnalyticsTitleSort.PROGRESS,
                0,
                20,
            )
            .value
            .items
            .single()

        unknown.progress shouldBe null
        unknown.estimate shouldBe null

        coEvery { repository.titleMetadata(setOf(TITLE)) } returns listOf(
            titleMetadata(totalCharacterEstimate = 500_000),
        )
        val unstable = ImmersionAnalyticsService(repository, goalRepository)
            .titlePage(
                StatsFilter(dateRange = LocalDateRange(date("2026-07-01"), date("2026-07-03"))),
                AnalyticsTitleFilter(),
                AnalyticsTitleSort.PROGRESS,
                0,
                20,
            )
            .value
            .items
            .single()

        unstable.estimate shouldBe null
    }

    @Test
    fun `speed ranking places tiny outlier below qualifying title`() = runTest {
        val qualifying = rollup("2026-07-01", 60_000, titleId = TITLE)
        val tiny = rollup("2026-07-01", 900, titleId = TITLE_TWO).copy(
            metrics = metrics(900).copy(activeTime = MillisecondDuration(1_000)),
        )
        stub(listOf(qualifying, tiny))
        coEvery { repository.titleMetadata(setOf(TITLE, TITLE_TWO)) } returns listOf(
            titleMetadata(id = TITLE, displayTitle = "Qualifying"),
            titleMetadata(id = TITLE_TWO, displayTitle = "Tiny outlier"),
        )

        val rows = ImmersionAnalyticsService(repository, goalRepository)
            .titlePage(
                StatsFilter(dateRange = LocalDateRange(date("2026-07-01"), date("2026-07-01"))),
                AnalyticsTitleFilter(),
                AnalyticsTitleSort.READING_SPEED,
                0,
                20,
            )
            .value
            .items

        rows.map { it.titleId } shouldBe listOf(TITLE, TITLE_TWO)
        rows.map { it.speedRankingEligible } shouldBe listOf(true, false)
    }

    @Test
    fun `title state coverage search and paging are stable`() = runTest {
        val third = TitleId("00000000-0000-0000-0000-000000000003")
        val rows = listOf(
            rollup("2026-07-01", 100, titleId = TITLE),
            rollup("2026-07-01", 100, titleId = TITLE_TWO),
            rollup("2026-07-01", 100, titleId = third),
        )
        stub(rows)
        coEvery { repository.titleMetadata(setOf(TITLE, TITLE_TWO, third)) } returns listOf(
            titleMetadata(id = TITLE, displayTitle = "Alpha", completed = true),
            titleMetadata(id = TITLE_TWO, displayTitle = "Beta", completed = false),
            titleMetadata(id = third, displayTitle = "Gamma", completed = null),
        )
        coEvery { repository.titleCoverage(any()) } returns mapOf(
            TITLE to AnalyticsTitleCoverage(
                eventBackedSessionCount = 1,
                sourceUnitCount = 2,
                indexedSourceUnitCount = 2,
            ),
            TITLE_TWO to AnalyticsTitleCoverage(
                eventBackedSessionCount = 1,
                sourceUnitCount = 2,
                indexedSourceUnitCount = 1,
            ),
            third to AnalyticsTitleCoverage(eventBackedSessionCount = 1),
        )
        val service = ImmersionAnalyticsService(repository, goalRepository)
        val filter = StatsFilter(
            dateRange = LocalDateRange(date("2026-07-01"), date("2026-07-01")),
        )

        val partial = service.titlePage(
            filter,
            AnalyticsTitleFilter(
                searchQuery = "be",
                state = AnalyticsTitleStateFilter.IN_PROGRESS,
                coverage = AnalyticsTitleCoverageFilter.PARTIAL,
            ),
            AnalyticsTitleSort.ALPHABETICAL,
            0,
            1,
        ).value

        partial.items.map { it.titleId } shouldBe listOf(TITLE_TWO)
        partial.nextOffset shouldBe null

        val firstPage = service.titlePage(
            filter,
            AnalyticsTitleFilter(),
            AnalyticsTitleSort.ALPHABETICAL,
            0,
            2,
        ).value
        val secondPage = service.titlePage(
            filter,
            AnalyticsTitleFilter(),
            AnalyticsTitleSort.ALPHABETICAL,
            checkNotNull(firstPage.nextOffset),
            2,
        ).value

        firstPage.items.map { it.displayTitle } shouldBe listOf("Alpha", "Beta")
        secondPage.items.map { it.displayTitle } shouldBe listOf("Gamma")
        secondPage.nextOffset shouldBe null
    }

    @Test
    fun `title detail queries replace an existing title scope and retain stable paging`() = runTest {
        val range = LocalDateRange(date("2026-07-01"), date("2026-07-02"))
        val filter = StatsFilter(
            dateRange = range,
            titleIds = setOf(TITLE_TWO),
            comparisonRange = LocalDateRange(date("2026-06-01"), date("2026-06-02")),
        )
        val scoped = filter.copy(titleIds = setOf(TITLE), comparisonRange = null)
        val acquisition = AnalyticsTitleWordAcquisition(
            titleId = TITLE,
            bucketSize = AnalyticsTitleAcquisitionBucketSize.TEN_THOUSAND,
            totalGrossCharacters = 100,
            buckets = listOf(
                AnalyticsTitleWordAcquisitionBucket(
                    index = 0,
                    startCharacter = 0,
                    endCharacterInclusive = 99,
                    newWords = 2,
                    cumulativeNewWords = 2,
                ),
            ),
        )
        val completedUnit = AnalyticsTitleCompletedUnit(
            titleId = TITLE,
            completionUnitId = "section-1",
            firstCompletedAtEpochMillis = 1_000,
            lastCompletedAtEpochMillis = 2_000,
            firstCompletedDate = date("2026-07-01"),
            completionEventCount = 2,
        )
        val occurrence = AnalyticsSourceOccurrence(
            sourceUnitId = SourceUnitId("00000000-0000-0000-0000-000000000101"),
            titleId = TITLE,
            displayTitle = "Title",
            sessionId = SessionId("00000000-0000-0000-0000-000000000201"),
            mediaKind = MediaKind.NOVEL,
            sourceKind = SourceKind.NOVEL_RANGE,
            canonicalLocator = "section-1:0-100",
            occurredAtEpochMillis = 1_000,
            excerpt = "source",
            rawTextAvailable = true,
        )
        stub(listOf(rollup("2026-07-01", 100)))
        coEvery {
            repository.titleWordAcquisition(
                scoped,
                AnalyticsTitleAcquisitionBucketSize.TEN_THOUSAND,
            )
        } returns mapOf(TITLE to acquisition)
        coEvery { repository.titleCompletedUnits(scoped, 0, 10) } returns
            AnalyticsPage(listOf(completedUnit), 1)
        coEvery { repository.sourceOccurrences(scoped, 0, 10) } returns
            AnalyticsPage(listOf(occurrence), null)
        val service = serviceAt("2026-07-02T12:00:00Z")

        service.titleWordAcquisition(
            filter,
            TITLE,
            AnalyticsTitleAcquisitionBucketSize.TEN_THOUSAND,
        ).value shouldBe acquisition
        service.titleCompletedUnits(filter, TITLE, 0, 10).value.let {
            it.items shouldBe listOf(completedUnit)
            it.nextOffset shouldBe 1
        }
        service.titleSourceOccurrences(filter, TITLE, 0, 10).value.items shouldBe
            listOf(occurrence)

        coVerify(exactly = 1) {
            repository.titleWordAcquisition(
                scoped,
                AnalyticsTitleAcquisitionBucketSize.TEN_THOUSAND,
            )
            repository.titleCompletedUnits(scoped, 0, 10)
            repository.sourceOccurrences(scoped, 0, 10)
        }
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
    fun `overview preserves the complete filter across totals comparison inventory quality and streak`() =
        runTest {
            val previous = LocalDateRange(date("2026-07-01"), date("2026-07-01"))
            val current = LocalDateRange(date("2026-07-02"), date("2026-07-02"))
            val profileId = "target-profile"
            val languageTag = LanguageTag("ja")
            val filter = StatsFilter(
                dateRange = current,
                comparisonRange = previous,
                mediaKinds = setOf(MediaKind.NOVEL),
                profileIds = setOf(profileId),
                languageTags = setOf(languageTag),
                titleIds = setOf(TITLE),
                includeLegacyAggregates = false,
                characterMetric = CharacterMetric.UNIQUE_SOURCE,
                includeRereadsAndReplays = false,
                maturityTiers = setOf(MaturityTier.MATURE),
                ankiMaturityAggregation = AnkiMaturityAggregation.MIN_INTERVAL,
                provenanceStates = setOf(
                    ProvenanceState.AVAILABLE,
                    ProvenanceState.LEGACY_AGGREGATE,
                ),
            )
            val rows = listOf(
                rollup(
                    "2026-07-01",
                    100,
                    uniqueCharacters = 40,
                    profileId = profileId,
                    languageTag = languageTag,
                ),
                rollup(
                    "2026-07-02",
                    300,
                    uniqueCharacters = 120,
                    profileId = profileId,
                    languageTag = languageTag,
                ),
                rollup(
                    "2026-07-02",
                    1_000,
                    profileId = profileId,
                    languageTag = languageTag,
                    mediaKind = MediaKind.MANGA,
                ),
                rollup(
                    "2026-07-02",
                    1_000,
                    profileId = "other-profile",
                    languageTag = languageTag,
                ),
                rollup(
                    "2026-07-02",
                    1_000,
                    profileId = profileId,
                    languageTag = LanguageTag("en"),
                ),
                rollup(
                    "2026-07-02",
                    1_000,
                    titleId = TITLE_TWO,
                    profileId = profileId,
                    languageTag = languageTag,
                ),
                rollup(
                    "2026-07-02",
                    1_000,
                    profileId = profileId,
                    languageTag = languageTag,
                    provenanceState = ProvenanceState.PARTIAL,
                ),
                rollup(
                    "2026-07-02",
                    1_000,
                    profileId = profileId,
                    languageTag = languageTag,
                    provenanceState = ProvenanceState.LEGACY_AGGREGATE,
                ),
                rollup(
                    "2026-07-02",
                    1_000,
                    replay = true,
                    profileId = profileId,
                    languageTag = languageTag,
                ),
            )
            stub(rows)
            val currentFilter = filter.copy(comparisonRange = null)
            val previousFilter = filter.copy(dateRange = previous, comparisonRange = null)
            val historyFilter = filter.copy(dateRange = null, comparisonRange = null)
            val currentInventory = inventory(distinct = 3, new = 2)
            val previousInventory = inventory(distinct = 1, new = 1)
            val quality = AnalyticsDataQuality(
                eventBackedSessionCount = 1,
                sourceUnitCount = 1,
                indexedSourceUnitCount = 1,
                textAvailableSourceUnitCount = 1,
                provenanceState = ProvenanceState.AVAILABLE,
            )
            coEvery { repository.inventoryMetrics(currentFilter) } returns currentInventory
            coEvery { repository.inventoryMetrics(previousFilter) } returns previousInventory
            coEvery {
                repository.dataQuality(filter, Instant.parse("2026-07-02T12:00:00Z").toEpochMilli())
            } returns quality

            val result = serviceAt("2026-07-02T12:00:00Z").overview(filter)

            result.value.comparison.current.let { metrics ->
                metrics.characters.gross.value shouldBe 300
                metrics.characters.uniqueSource.value shouldBe 120
                metrics.distinctCharacters.value shouldBe 3
                metrics.newCharacters.value shouldBe 2
            }
            checkNotNull(result.value.comparison.previous).let { metrics ->
                metrics.characters.gross.value shouldBe 100
                metrics.characters.uniqueSource.value shouldBe 40
                metrics.distinctCharacters.value shouldBe 1
            }
            result.value.comparison.characterChangeRatio shouldBe 2.0
            result.value.streak.currentDays shouldBe 2
            result.value.streak.qualifyingDates shouldBe setOf(
                date("2026-07-01"),
                date("2026-07-02"),
            )
            result.quality shouldBe quality
            coVerify(exactly = 1) { repository.inventoryMetrics(currentFilter) }
            coVerify(exactly = 1) { repository.inventoryMetrics(previousFilter) }
            coVerify(exactly = 1) { repository.availableDateRange(historyFilter) }
            coVerify(exactly = 1) {
                repository.dataQuality(
                    filter,
                    Instant.parse("2026-07-02T12:00:00Z").toEpochMilli(),
                )
            }
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
        progress.todayAchieved shouldBe 0.0
        progress.todayTarget shouldBe 0.0
    }

    @Test
    fun `finish-title goals count captured source units within the title scope`() = runTest {
        val range = LocalDateRange(date("2026-07-24"), date("2026-07-25"))
        stub(
            listOf(
                rollup("2026-07-24", 100, sourceUnits = 3),
                rollup("2026-07-25", 100, sourceUnits = 2),
            ),
        )
        stubGoals(
            goal(
                id = "finish-title",
                metric = "source_units",
                target = 10.0,
                period = "TOTAL",
                type = "FINISH_TITLE_BY_DATE",
                startDate = range.start,
                endDate = range.endInclusive,
                titleId = TITLE,
            ),
        )

        val progress = serviceAt("2026-07-25T12:00:00Z")
            .goals(StatsFilter(titleIds = setOf(TITLE)))
            .value
            .single()

        progress.achieved shouldBe 5.0
        progress.targetToDate shouldBe 10.0
        progress.todayAchieved shouldBe 5.0
        progress.todayTarget shouldBe 10.0
    }

    @Test
    fun `goal forecast caps high outliers and normalizes fractional active days`() = runTest {
        val start = date("2026-07-20")
        val today = date("2026-07-27")
        val deadline = date("2026-08-03")
        stub(
            listOf(
                rollup("2026-07-20", 100),
                rollup("2026-07-21", 100),
                rollup("2026-07-22", 10_000),
                rollup("2026-07-23", 100),
                rollup("2026-07-24", 100),
                rollup("2026-07-25", 50),
                rollup("2026-07-27", 100),
            ),
        )
        stubGoals(
            goal(
                id = "robust-forecast",
                target = 20_000.0,
                period = "TOTAL",
                type = "DATE_BOUND_TOTAL",
                startDate = start,
                endDate = deadline,
                weekdayMultipliers = "SATURDAY=0.5,SUNDAY=0",
            ),
        )

        val progress = serviceAt("2026-07-27T12:00:00Z")
            .goals(StatsFilter())
            .value
            .single()

        progress.rollingThirtyDayPace shouldBe 100.0
        progress.forecastConfidence shouldBe CapabilityState.AVAILABLE
        progress.forecastSampleDays shouldBe 7
        progress.forecastWindowDays shouldBe 30
        progress.remainingActiveDays shouldBe 6
        progress.requiredPacePerActiveDay shouldBe (9_450.0 / 5.5)
        progress.projectedCompletionDate shouldBe date("2026-11-24")
    }

    @Test
    fun `goal forecast confidence and outlier handling use recent qualifying days`() = runTest {
        val start = date("2026-06-01")
        val today = date("2026-07-30")
        stub(
            (1..7).map { day -> rollup("2026-06-${day.toString().padStart(2, '0')}", 100) } +
                listOf(
                    rollup("2026-07-28", 100),
                    rollup("2026-07-29", 100),
                    rollup("2026-07-30", 100),
                ),
        )
        stubGoals(
            goal(
                id = "recent-sample",
                target = 2_000.0,
                period = "TOTAL",
                type = "DATE_BOUND_TOTAL",
                startDate = start,
            ),
        )

        val progress = serviceAt("2026-07-30T12:00:00Z")
            .goals(StatsFilter())
            .value
            .single()

        progress.forecastSampleDays shouldBe 3
        progress.forecastConfidence shouldBe CapabilityState.PARTIAL
        progress.rollingThirtyDayPace shouldBe 10.0
        progress.projectedCompletionDate shouldBe date("2026-11-07")
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
    fun `filtered dashboard only returns goals bound to that exact scope`() = runTest {
        val first = date("2026-07-01")
        stub(
            listOf(
                rollup("2026-07-01", 100, titleId = TITLE),
                rollup("2026-07-01", 200, titleId = TITLE_TWO),
            ),
        )
        stubGoals(
            goal(id = "matching", startDate = first, titleId = TITLE),
            goal(id = "other-title", startDate = first, titleId = TITLE_TWO),
            goal(id = "global", startDate = first, titleId = null),
        )

        val progress = serviceAt("2026-07-01T12:00:00Z")
            .goals(StatsFilter(titleIds = setOf(TITLE)))
            .value

        progress.map { it.goal.id } shouldBe listOf("matching")
        progress.single().achieved shouldBe 100.0
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

    @Test
    fun `Anki missing lists stay unavailable without a usable current inventory`() = runTest {
        val range = LocalDateRange(date("2026-07-01"), date("2026-07-01"))
        val filter = StatsFilter(
            dateRange = range,
            profileIds = setOf("profile"),
            languageTags = setOf(LanguageTag("ja")),
        )
        stub(listOf(rollup("2026-07-01", 100)))
        coEvery { repository.ankiSummary(filter) } returnsMany listOf(
            ankiSummary(snapshot = null),
            ankiSummary(snapshot = ankiSnapshot(CapabilityState.UNAVAILABLE)),
        )
        val service = serviceAt("2026-07-01T12:00:00Z")

        val missingSnapshot = service.anki(filter).value
        val unavailableCapability = service.anki(filter).value

        missingSnapshot.missingHighFrequencyWords shouldBe emptyList()
        missingSnapshot.missingHighFrequencyCharacters shouldBe emptyList()
        unavailableCapability.missingHighFrequencyWords shouldBe emptyList()
        unavailableCapability.missingHighFrequencyCharacters shouldBe emptyList()
        coVerify(exactly = 0) {
            repository.vocabularyPage(any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            repository.characterPage(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `Anki missing lists use the full filter with stale last known good inventory`() = runTest {
        val range = LocalDateRange(date("2026-07-01"), date("2026-07-02"))
        val filter = StatsFilter(
            dateRange = range,
            mediaKinds = setOf(MediaKind.NOVEL),
            profileIds = setOf("profile"),
            languageTags = setOf(LanguageTag("ja")),
            titleIds = setOf(TITLE),
            includeLegacyAggregates = false,
            includeRereadsAndReplays = false,
            provenanceStates = setOf(ProvenanceState.AVAILABLE),
        )
        val missingFilter = filter.copy(maturityTiers = setOf(MaturityTier.UNKNOWN))
        val word = AnalyticsWordRow(
            id = "word-cat",
            languageTag = LanguageTag("ja"),
            headword = "猫",
            reading = "ねこ",
            partOfSpeech = "noun",
            occurrenceCount = 4,
            titleCount = 1,
            firstSeenAtEpochMillis = 1,
            lastSeenAtEpochMillis = 2,
            frequencyRank = 10,
            maturity = MaturityTier.UNKNOWN,
            matchConfidence = null,
        )
        val character = AnalyticsCharacterRow(
            codePoint = UnicodeCodePoint('猫'.code),
            rendered = "猫",
            unicodeName = "CJK UNIFIED IDEOGRAPH-732B",
            unicodeCategory = "OTHER_LETTER",
            unicodeScript = "HAN",
            japaneseReadings = null,
            occurrenceCount = 4,
            sourceUnitCount = 1,
            wordCount = 1,
            titleCount = 1,
            firstSeenAtEpochMillis = 1,
            lastSeenAtEpochMillis = 2,
            frequencyRank = 10,
            jlptLevel = null,
            gradeLevel = null,
            maturity = MaturityTier.UNKNOWN,
            priorityScore = 100_000.0,
        )
        stub(
            listOf(
                rollup("2026-07-01", 100),
                rollup("2026-07-02", 100),
            ),
        )
        coEvery { repository.ankiSummary(filter) } returns
            ankiSummary(snapshot = ankiSnapshot(CapabilityState.AVAILABLE, isStale = true))
        coEvery {
            repository.vocabularyPage(
                missingFilter,
                VocabularyFilter(),
                AnalyticsSort.FREQUENCY_RANK,
                0,
                20,
            )
        } returns AnalyticsPage(listOf(word), null)
        coEvery {
            repository.characterPage(
                missingFilter,
                AnalyticsSort.FREQUENCY_RANK,
                0,
                20,
                null,
                AnalyticsCharacterFilter(),
            )
        } returns AnalyticsPage(listOf(character), null)

        val result = serviceAt("2026-07-02T12:00:00Z").anki(filter).value

        result.missingHighFrequencyWords shouldBe listOf(word)
        result.missingHighFrequencyCharacters shouldBe listOf(character)
        coVerify(exactly = 1) {
            repository.vocabularyPage(
                missingFilter,
                VocabularyFilter(),
                AnalyticsSort.FREQUENCY_RANK,
                0,
                20,
            )
        }
        coVerify(exactly = 1) {
            repository.characterPage(
                missingFilter,
                AnalyticsSort.FREQUENCY_RANK,
                0,
                20,
                null,
                AnalyticsCharacterFilter(),
            )
        }
    }

    @Test
    fun `Anki impact exposes capability freshness and partial ISO weeks without causal claims`() = runTest {
        val range = LocalDateRange(date("2026-07-01"), date("2026-07-02"))
        val filter = StatsFilter(
            dateRange = range,
            profileIds = setOf("profile"),
            languageTags = setOf(LanguageTag("ja")),
        )
        val repositorySummary = ankiSummary(
            snapshot = ankiSnapshot(CapabilityState.AVAILABLE, isStale = true),
        ).copy(
            linkedOperationCount = 2,
            unattributedOperationCount = 1,
            meanReadingToCardLagMillis = 3_600_000,
            weeklyImpact = listOf(
                AnalyticsAnkiWeeklyImpact(
                    weekStart = date("2026-06-29"),
                    weekEndInclusive = date("2026-07-05"),
                    partial = false,
                    activeDurationMillis = 60_000,
                    grossCharacters = 1_000,
                    cardsCreated = 2,
                    cardsUpdated = 0,
                    linkedOperations = 2,
                    unattributedOperations = 1,
                    sameWeekReadingToCardOperations = 1,
                    maturedOperations = 0,
                    meanReadingToCardLagMillis = 3_600_000,
                    meanCardToMaturityLagMillis = null,
                ),
            ),
        )
        stub(emptyList())
        coEvery { repository.ankiSummary(filter) } returns repositorySummary
        coEvery {
            repository.vocabularyPage(any(), any(), any(), any(), any())
        } returns AnalyticsPage(emptyList(), null)
        coEvery {
            repository.characterPage(any(), any(), any(), any(), any(), any())
        } returns AnalyticsPage(emptyList(), null)

        val result = serviceAt("2026-07-02T12:00:00Z").anki(filter).value

        result.weeklyImpact.single().partial shouldBe true
        result.generatedAtEpochMillis shouldBe Instant.parse("2026-07-02T12:00:00Z").toEpochMilli()
        result.minimumComparisonSampleSize shouldBe 20
        result.capabilities.associateBy { it.report }.let { capabilities ->
            capabilities.getValue(AnalyticsAnkiReport.INVENTORY).let {
                it.state shouldBe CapabilityState.STALE
                it.reason shouldBe AnalyticsAnkiCapabilityReason.STALE_INVENTORY
            }
            capabilities.getValue(AnalyticsAnkiReport.SOURCE_ATTRIBUTION).state shouldBe
                CapabilityState.PARTIAL
            capabilities.getValue(AnalyticsAnkiReport.READING_TO_CARD_LAG).state shouldBe
                CapabilityState.AVAILABLE
            capabilities.getValue(AnalyticsAnkiReport.CARD_TO_MATURITY_LAG).reason shouldBe
                AnalyticsAnkiCapabilityReason.INSUFFICIENT_SAMPLE
            capabilities.getValue(AnalyticsAnkiReport.RETENTION).reason shouldBe
                AnalyticsAnkiCapabilityReason.PROVIDER_UNSUPPORTED
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
        coEvery { repository.titleCoverage(any()) } returns emptyMap()
        coEvery { repository.titleUnitProgress(any()) } returns emptyMap()
        coEvery { repository.titleNetProgress(any()) } returns rows
            .groupBy(ImmersionDailyRollup::titleId)
            .mapValues { (_, titleRows) ->
                NetCharacterProgress(
                    titleRows.sumOf { it.metrics.characters.netProgress.value },
                )
            }
        coEvery { repository.availableDateRange(any()) } returns rows
            .takeIf { it.isNotEmpty() }
            ?.let {
                LocalDateRange(
                    it.minOf(ImmersionDailyRollup::date),
                    it.maxOf(ImmersionDailyRollup::date),
                )
            }
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
        weekdayMultipliers: String? = null,
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
        weekdayMultipliers = weekdayMultipliers,
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

    private fun ankiSummary(snapshot: ImmersionAnkiSnapshot?) =
        AnalyticsAnkiSummary(
            snapshot = snapshot,
            wordCoverageEncountered = 0,
            wordCoverageKnown = 0,
            characterCoverageEncountered = 0,
            characterCoverageKnown = 0,
            reviewHistoryAvailable = false,
        )

    private fun ankiSnapshot(
        capabilityState: CapabilityState,
        isStale: Boolean = false,
    ) = ImmersionAnkiSnapshot(
        id = "snapshot",
        profileId = "profile",
        deckScope = "Mining",
        requestedAtEpochMillis = 1,
        completedAtEpochMillis = 2,
        capabilityVersion = 1,
        capabilityState = capabilityState,
        providerVersion = "test",
        supportsNoteModificationTime = true,
        supportsCardModificationTime = true,
        supportsReviewHistory = false,
        status = AnkiSnapshotStatus.COMPLETE,
        errorCode = null,
        itemCount = 1,
        noteCount = 1,
        matureIntervalDays = 21,
        mappingHash = "mapping",
        queryDurationMillis = 1,
        isComplete = true,
        isPartial = false,
        isCurrent = true,
        isStale = isStale,
    )

    private fun titleMetadata(
        id: TitleId = TITLE,
        displayTitle: String = "Title",
        totalUnits: Long? = null,
        totalCharacterEstimate: Long? = null,
        completed: Boolean? = null,
    ) = AnalyticsTitleMetadata(
        titleId = id,
        displayTitle = displayTitle,
        mediaKind = MediaKind.NOVEL,
        sourceKey = "novel:${id.value}",
        profileId = "default",
        languageTag = LanguageTag("ja"),
        libraryId = null,
        trackerId = null,
        mediaId = id.value,
        status = null,
        totalUnits = totalUnits,
        totalCharacterEstimate = totalCharacterEstimate,
        completed = completed,
        deletedAtEpochMillis = null,
    )

    private fun metrics(characters: Long) = ReadingMetrics(
        activeTime = MillisecondDuration(characters * 10),
        characters = CharacterVolume(
            gross = NonNegativeCounter(characters),
            uniqueSource = NonNegativeCounter(characters),
            netProgress = NetCharacterProgress(characters),
        ),
    )

    private fun rollup(
        date: String,
        characters: Long,
        replay: Boolean = false,
        titleId: TitleId = TITLE,
        poisonedInventory: Long = 0,
        sourceUnits: Long = 0,
        uniqueCharacters: Long = characters,
        netCharacters: Long = characters,
        profileId: String = "default",
        languageTag: LanguageTag = LanguageTag("ja"),
        mediaKind: MediaKind = MediaKind.NOVEL,
        provenanceState: ProvenanceState = ProvenanceState.AVAILABLE,
    ) = ImmersionDailyRollup(
        date = date(date),
        profileId = profileId,
        languageTag = languageTag,
        mediaKind = mediaKind,
        titleId = titleId,
        metrics = ReadingMetrics(
            activeTime = MillisecondDuration(characters * 10),
            characters = CharacterVolume(
                gross = NonNegativeCounter(characters),
                uniqueSource = NonNegativeCounter(uniqueCharacters),
                netProgress = NetCharacterProgress(netCharacters),
            ),
            distinctCharacters = NonNegativeCounter(poisonedInventory),
            newCharacters = NonNegativeCounter(poisonedInventory),
            uniqueWords = NonNegativeCounter(poisonedInventory),
            newWords = NonNegativeCounter(poisonedInventory),
            sourceUnits = NonNegativeCounter(sourceUnits),
            sessions = NonNegativeCounter(1),
        ),
        provenanceState = provenanceState,
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
