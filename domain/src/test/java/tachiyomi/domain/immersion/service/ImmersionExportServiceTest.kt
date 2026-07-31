package tachiyomi.domain.immersion.service

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.AnalyticsCharacterRow
import tachiyomi.domain.immersion.model.AnalyticsDataQuality
import tachiyomi.domain.immersion.model.AnalyticsPage
import tachiyomi.domain.immersion.model.AnalyticsQueryDiagnostics
import tachiyomi.domain.immersion.model.AnalyticsQueryFamily
import tachiyomi.domain.immersion.model.AnalyticsResult
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.MaturityTier
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.model.UnicodeCodePoint
import tachiyomi.domain.immersion.repository.ImmersionMaintenanceRepository

class ImmersionExportServiceTest {

    @Test
    fun `CSV export neutralizes spreadsheet formulas and escapes quotes`() {
        listOf(
            listOf("title", "value"),
            listOf("=HYPERLINK(\"https://example.test\")", "+1"),
            listOf("-2", "@command"),
        ).toCsv() shouldBe
            "\"title\",\"value\"\r\n" +
            "\"'=HYPERLINK(\"\"https://example.test\"\")\",\"'+1\"\r\n" +
            "\"'-2\",\"'@command\"\r\n"
    }

    @Test
    fun `character export pages through the analytics service with versioned metadata`() = runTest {
        val analytics = mockk<ImmersionAnalyticsService>()
        val maintenance = mockk<ImmersionMaintenanceRepository>()
        val statsFilter = StatsFilter()
        val character = AnalyticsCharacterRow(
            codePoint = UnicodeCodePoint('猫'.code),
            rendered = "猫",
            unicodeName = "CJK UNIFIED IDEOGRAPH-732B",
            unicodeCategory = "OTHER_LETTER",
            unicodeScript = "HAN",
            japaneseReadings = "ねこ",
            occurrenceCount = 3,
            sourceUnitCount = 2,
            titleCount = 1,
            firstSeenAtEpochMillis = 1,
            lastSeenAtEpochMillis = 2,
            frequencyRank = 10,
            jlptLevel = 5,
            gradeLevel = 1,
            maturity = MaturityTier.MATURE,
            priorityScore = 12.5,
        )
        coEvery {
            analytics.characters(
                filter = statsFilter,
                sort = AnalyticsSort.MOST_OCCURRENCES,
                offset = 0,
                limit = 500,
            )
        } returns AnalyticsResult(
            value = AnalyticsPage(listOf(character), null),
            quality = AnalyticsDataQuality(),
            diagnostics = AnalyticsQueryDiagnostics(
                family = AnalyticsQueryFamily.CHARACTERS,
                filterHash = "filter",
                rowCount = 1,
                durationMillis = 1,
            ),
        )

        val document = ImmersionExportService(analytics, maintenance).charactersCsv(statsFilter)
        val csv = document.bytes.decodeToString()

        document.fileName shouldBe "chimahon-stats-characters.csv"
        csv shouldContain "\"schema_version\""
        csv shouldContain "\"jlpt_level\""
        csv shouldContain "\"HAN\""
        csv shouldContain "\"猫\""
        coVerify(exactly = 1) {
            analytics.characters(
                filter = statsFilter,
                sort = AnalyticsSort.MOST_OCCURRENCES,
                offset = 0,
                limit = 500,
            )
        }
    }
}
