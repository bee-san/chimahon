// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.service

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.AnalyticsDataQuality
import tachiyomi.domain.immersion.model.AnalyticsPage
import tachiyomi.domain.immersion.model.AnalyticsQueryDiagnostics
import tachiyomi.domain.immersion.model.AnalyticsQueryFamily
import tachiyomi.domain.immersion.model.AnalyticsResult
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.AnalyticsWordRow
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.MaturityTier
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.model.VocabularyCategory
import tachiyomi.domain.immersion.model.VocabularyExclusion
import tachiyomi.domain.immersion.model.VocabularyFilter
import tachiyomi.domain.immersion.model.VocabularyScript
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
    fun `vocabulary export preserves the filtered sort and versioned metadata`() = runTest {
        val analytics = mockk<ImmersionAnalyticsService>()
        val maintenance = mockk<ImmersionMaintenanceRepository>()
        val statsFilter = StatsFilter()
        val vocabularyFilter = VocabularyFilter(
            scripts = setOf(VocabularyScript.KANJI),
            exclusion = VocabularyExclusion.ALL,
        )
        val word = AnalyticsWordRow(
            id = "word-cat",
            languageTag = LanguageTag("ja"),
            headword = "猫",
            reading = "ねこ",
            partOfSpeech = "noun",
            occurrenceCount = 3,
            titleCount = 1,
            firstSeenAtEpochMillis = 1,
            lastSeenAtEpochMillis = 2,
            frequencyRank = 10,
            maturity = MaturityTier.MATURE,
            matchConfidence = null,
            jlptLevel = 5,
            gradeLevel = 1,
            script = VocabularyScript.KANJI,
            category = VocabularyCategory.OTHER,
            excluded = true,
        )
        coEvery {
            analytics.vocabulary(
                statsFilter,
                vocabularyFilter,
                AnalyticsSort.FREQUENCY_RANK,
                0,
                500,
            )
        } returns AnalyticsResult(
            value = AnalyticsPage(listOf(word), null),
            quality = AnalyticsDataQuality(),
            diagnostics = AnalyticsQueryDiagnostics(
                family = AnalyticsQueryFamily.VOCABULARY,
                filterHash = "filter",
                rowCount = 1,
                durationMillis = 1,
            ),
        )

        val document = ImmersionExportService(analytics, maintenance).vocabularyCsv(
            statsFilter,
            vocabularyFilter,
            AnalyticsSort.FREQUENCY_RANK,
        )
        val csv = document.bytes.decodeToString()

        document.fileName shouldBe "chimahon-stats-vocabulary.csv"
        csv shouldContain "\"schema_version\""
        csv shouldContain "\"jlpt_level\""
        csv shouldContain "\"KANJI\""
        csv shouldContain "\"true\""
        coVerify(exactly = 1) {
            analytics.vocabulary(
                statsFilter,
                vocabularyFilter,
                AnalyticsSort.FREQUENCY_RANK,
                0,
                500,
            )
        }
    }
}
