// SPDX-License-Identifier: MIT

package mihon.feature.stats.legacy

import android.app.Application
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.LegacyImportIssueCode
import tachiyomi.domain.immersion.model.LegacyImportSourceKind
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.repository.ImmersionLegacyImportRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class LegacyStatsImporterTest {
    @Test
    fun `novel duplicate days preserve current summed totals and future fields`() = runTest {
        val plan = importer().planNovel(
            novelDocument(
                """
                [
                  {
                    "title": "Test",
                    "dateKey": "2024-01-02",
                    "charactersRead": 1200,
                    "readingTime": 600.25,
                    "maxReadingSpeed": 2400,
                    "futureField": {"ignored": true}
                  },
                  {
                    "title": "Test",
                    "dateKey": "2024-01-02",
                    "charactersRead": 800,
                    "readingTime": 300.25,
                    "completedBook": 1
                  }
                ]
                """.trimIndent(),
            ),
        )

        plan.issues shouldHaveSize 0
        plan.aggregates.single().let { aggregate ->
            aggregate.characters.value shouldBe 2_000
            aggregate.activeDuration.value shouldBe 900_500
            aggregate.originalReadingTimeSeconds shouldBe 900.5
            aggregate.completed shouldBe true
            aggregate.mediaKind shouldBe MediaKind.NOVEL
        }
    }

    @Test
    fun `old minimal novel shape retains defaults`() = runTest {
        val plan = importer().planNovel(
            novelDocument("""[{"title":"Old","dateKey":"2024-01-02"}]"""),
        )

        plan.issues shouldHaveSize 0
        plan.aggregates.single().let { aggregate ->
            aggregate.characters.value shouldBe 0
            aggregate.activeDuration.value shouldBe 0
            aggregate.originalReadingTimeSeconds shouldBe 0.0
        }
    }

    @Test
    fun `empty legacy file records a successful no-op checkpoint`() = runTest {
        val plan = importer().planNovel(novelDocument("[]"))

        plan.aggregates shouldHaveSize 0
        plan.issues shouldHaveSize 0
        plan.batch.failedCount.value shouldBe 0
    }

    @Test
    fun `invalid records are isolated while valid records remain importable`() = runTest {
        val plan = importer().planNovel(
            novelDocument(
                """
                [
                  {"title":"Test","dateKey":"not-a-date","charactersRead":10},
                  {"title":"Test","dateKey":"2024-01-03","charactersRead":-1},
                  {"title":"Test","dateKey":"2024-01-04","charactersRead":20,"readingTime":2.5}
                ]
                """.trimIndent(),
            ),
        )

        plan.aggregates.single().characters.value shouldBe 20
        plan.issues.map { it.code } shouldBe listOf(
            LegacyImportIssueCode.INVALID_DATE,
            LegacyImportIssueCode.INVALID_VALUE,
        )
        plan.batch.failedCount.value shouldBe 2
        plan.batch.errorSummary shouldBe "INVALID_DATE:1,INVALID_VALUE:1"
    }

    @Test
    fun `corrupt document becomes a failed restartable ledger plan`() = runTest {
        val plan = importer().planNovel(novelDocument("""{"not":"an array"}"""))

        plan.aggregates shouldHaveSize 0
        plan.batch.failedCount.value shouldBe 1
        plan.issues.single().code shouldBe LegacyImportIssueCode.CORRUPT_JSON
        plan.batch.identity.contentHash.length shouldBe 64
    }

    @Test
    fun `Anki totals split combined legacy cards without fabricating operations`() {
        val plans = importer().planAnki(
            LegacySourceDocument(
                sourceKey = "anki_stats.json",
                kind = LegacyImportSourceKind.ANKI_JSON,
                bytes = """
                    [
                      {
                        "dateKey":"2024-01-02",
                        "mangaCards":3,
                        "novelCards":2,
                        "profileId":"default",
                        "titleId":"42",
                        "futureField":true
                      }
                    ]
                """.trimIndent().encodeToByteArray(),
            ),
        )

        plans shouldHaveSize 2
        plans.flatMap { it.aggregates }
            .associate { it.mediaKind to it.cardsTotal.value } shouldBe
            mapOf(MediaKind.MANGA to 3L, MediaKind.NOVEL to 2L)
    }

    @Test
    fun `legacy date anchor uses the configured local timezone`() = runTest {
        val zone = ZoneId.of("America/New_York")
        val aggregate = importer(zone).planNovel(
            novelDocument("""[{"title":"DST","dateKey":"2024-03-10","readingTime":1.0}]"""),
        ).aggregates.single()

        aggregate.startZoneId shouldBe "America/New_York"
        aggregate.startOffsetSeconds shouldBe -18_000
        aggregate.startAnchorEpochMillis shouldBe Instant.parse("2024-03-10T05:00:00Z").toEpochMilli()
    }

    @Test
    fun `large legacy file is reduced to bounded daily aggregates`() = runTest {
        val records = (0 until 2_000).joinToString(",") { index ->
            val day = index % 20 + 1
            """{"title":"Large","dateKey":"2024-01-${day.toString().padStart(2, '0')}","charactersRead":1,"readingTime":0.5}"""
        }
        val plan = importer().planNovel(novelDocument("[$records]"))

        plan.issues shouldHaveSize 0
        plan.aggregates shouldHaveSize 20
        plan.aggregates.sumOf { it.characters.value } shouldBe 2_000
        plan.aggregates.sumOf { it.activeDuration.value } shouldBe 1_000_000
    }

    private fun importer(zoneId: ZoneId = ZoneId.of("UTC")) =
        LegacyStatsImporter(
            application = mockk<Application>(relaxed = true),
            repository = mockk<ImmersionLegacyImportRepository>(relaxed = true),
            getManga = mockk<GetManga>(relaxed = true),
            dictionaryPreferences = mockk<DictionaryPreferences>(relaxed = true),
            sourceManager = mockk<SourceManager>(relaxed = true),
            clock = Clock.fixed(Instant.parse("2024-02-01T00:00:00Z"), ZoneId.of("UTC")),
            zoneIdProvider = { zoneId },
        )

    private fun novelDocument(json: String) =
        LegacySourceDocument(
            sourceKey = "novels/test/statistics.json",
            kind = LegacyImportSourceKind.NOVEL_JSON,
            bytes = json.encodeToByteArray(),
            novel = LegacyTitleDescriptor(
                sourceKey = "legacy:novel:test",
                displayTitle = "Test novel",
                profileId = "default",
            ),
        )
}
