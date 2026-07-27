// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.AnalyticsTitleCoverage
import tachiyomi.domain.immersion.model.AnalyticsTitleDayHighlights
import tachiyomi.domain.immersion.model.AnalyticsTitleRow
import tachiyomi.domain.immersion.model.AnalyticsTitleUnitProgress
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.ReadingMetrics
import tachiyomi.domain.immersion.model.TitleId

class StatsTitleMetadataResolverTest {
    @Test
    fun `missing local title retains its analytics row with unavailable linkage`() = runTest {
        val title = title(
            id = "00000000-0000-4000-8000-000000000001",
            mediaKind = MediaKind.MANGA,
            sourceKey = "manga:42",
            libraryId = 42,
        )

        val result = resolver().resolve(listOf(title))

        result shouldHaveSize 1
        result.getValue(title.titleId).linkState shouldBe StatsTitleLinkState.UNAVAILABLE
        result.getValue(title.titleId).localDisplayTitle shouldBe null
    }

    @Test
    fun `local metadata and cover resolve without changing stable stats identity`() = runTest {
        val title = title(
            id = "00000000-0000-4000-8000-000000000002",
            mediaKind = MediaKind.MANGA,
            sourceKey = "manga:42",
            libraryId = 42,
        )
        val resolver = resolver(
            manga = mapOf(
                42L to StatsTitleLocalRecord(
                    displayTitle = "Current local title",
                    author = "Author",
                    coverLocation = "content://local-cover",
                    favorite = true,
                ),
            ),
        )

        val result = resolver.resolve(listOf(title)).getValue(title.titleId)

        result.titleId shouldBe title.titleId
        result.localDisplayTitle shouldBe "Current local title"
        result.author shouldBe "Author"
        result.coverLocation shouldBe "content://local-cover"
        result.favorite shouldBe true
        result.linkState shouldBe StatsTitleLinkState.AVAILABLE
    }

    @Test
    fun `legacy-only titles do not imply a broken library link`() = runTest {
        val title = title(
            id = "00000000-0000-4000-8000-000000000003",
            mediaKind = MediaKind.NOVEL,
            sourceKey = "legacy:novel:book",
        )

        resolver().resolve(listOf(title)).getValue(title.titleId).linkState shouldBe
            StatsTitleLinkState.LEGACY_ONLY
    }

    @Test
    fun `explicitly unlinked title does not resolve through its retained source key`() = runTest {
        val title = title(
            id = "00000000-0000-4000-8000-000000000004",
            mediaKind = MediaKind.MANGA,
            sourceKey = "manga:42",
            libraryId = null,
            deletedAtEpochMillis = 2_000,
        )
        val resolver = resolver(
            manga = mapOf(
                42L to StatsTitleLocalRecord(
                    displayTitle = "Still present locally",
                    author = null,
                    coverLocation = null,
                ),
            ),
        )

        resolver.resolve(listOf(title)).getValue(title.titleId).linkState shouldBe
            StatsTitleLinkState.UNAVAILABLE
    }

    @Test
    fun `local metadata requests are batched once per media database`() = runTest {
        val mangaRequests = mutableListOf<Set<Long>>()
        val novelRequests = mutableListOf<Set<String>>()
        val videoRequests = mutableListOf<Set<Long>>()
        val resolver = StatsTitleMetadataResolver(
            mangaLookup = {
                mangaRequests += it
                emptyMap()
            },
            novelLookup = {
                novelRequests += it
                emptyMap()
            },
            videoLookup = {
                videoRequests += it
                emptyMap()
            },
        )

        resolver.resolve(
            listOf(
                title(
                    id = "00000000-0000-4000-8000-000000000011",
                    mediaKind = MediaKind.MANGA,
                    sourceKey = "manga:41",
                    libraryId = 41,
                ),
                title(
                    id = "00000000-0000-4000-8000-000000000012",
                    mediaKind = MediaKind.MANGA,
                    sourceKey = "manga:42",
                    libraryId = 42,
                ),
                title(
                    id = "00000000-0000-4000-8000-000000000013",
                    mediaKind = MediaKind.NOVEL,
                    sourceKey = "novel:book",
                ),
                title(
                    id = "00000000-0000-4000-8000-000000000014",
                    mediaKind = MediaKind.VIDEO,
                    sourceKey = "video:43",
                    libraryId = 43,
                ),
            ),
        )

        mangaRequests shouldBe listOf(setOf(41L, 42L))
        novelRequests shouldBe listOf(setOf("book"))
        videoRequests shouldBe listOf(setOf(43L))
    }

    private fun resolver(
        manga: Map<Long, StatsTitleLocalRecord> = emptyMap(),
        novels: Map<String, StatsTitleLocalRecord> = emptyMap(),
        videos: Map<Long, StatsTitleLocalRecord> = emptyMap(),
    ) = StatsTitleMetadataResolver(
        mangaLookup = { ids -> manga.filterKeys(ids::contains) },
        novelLookup = { ids -> novels.filterKeys(ids::contains) },
        videoLookup = { ids -> videos.filterKeys(ids::contains) },
    )

    private fun title(
        id: String,
        mediaKind: MediaKind,
        sourceKey: String,
        libraryId: Long? = null,
        deletedAtEpochMillis: Long? = null,
    ) = AnalyticsTitleRow(
        titleId = TitleId(id),
        displayTitle = "Persisted stats title",
        mediaKind = mediaKind,
        sourceKey = sourceKey,
        profileId = "jp",
        languageTag = null,
        libraryId = libraryId,
        trackerId = null,
        mediaId = libraryId?.toString(),
        status = null,
        totalUnits = null,
        totalCharacterEstimate = null,
        deletedAtEpochMillis = deletedAtEpochMillis,
        metrics = ReadingMetrics(),
        coverage = AnalyticsTitleCoverage(),
        firstActiveDate = ImmersionLocalDate(1),
        lastActiveDate = ImmersionLocalDate(1),
        activeDays = 1,
        calendarSpanDays = 1,
        averageCharactersPerActiveDay = 0.0,
        averageActiveTimePerActiveDayMillis = 0.0,
        dayHighlights = AnalyticsTitleDayHighlights(null, null, null),
        unitProgress = AnalyticsTitleUnitProgress(),
        estimate = null,
        speedRankingEligible = false,
        progress = null,
        completed = null,
    )
}
