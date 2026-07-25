// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.LegacyAggregateRow
import tachiyomi.domain.immersion.model.LegacyAggregateTotals
import tachiyomi.domain.immersion.model.LocalDateRange
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.MillisecondDuration
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.repository.ImmersionLegacyImportRepository

class GetLegacyAggregateTotalsTest {
    private val repository = mockk<ImmersionLegacyImportRepository>()
    private val interactor = GetLegacyAggregateTotals(repository)

    @Test
    fun `filters legacy aggregates without fabricating unavailable detail`() = runTest {
        coEvery { repository.getLegacyAggregates() } returns listOf(
            row("2024-01-01", MediaKind.NOVEL, 1_000, 100, 2),
            row("2024-01-02", MediaKind.MANGA, 2_000, 200, 3),
            row("2024-01-03", MediaKind.NOVEL, 4_000, 400, 5),
        )

        interactor.await(
            StatsFilter(
                dateRange = LocalDateRange(
                    ImmersionLocalDate.parse("2024-01-01"),
                    ImmersionLocalDate.parse("2024-01-02"),
                ),
                mediaKinds = setOf(MediaKind.NOVEL),
            ),
        ) shouldBe LegacyAggregateTotals(
            activeDuration = MillisecondDuration(1_000),
            characters = NonNegativeCounter(100),
            cardsTotal = NonNegativeCounter(2),
            records = NonNegativeCounter(1),
        )
    }

    @Test
    fun `excluded legacy aggregates return an explicit zero result`() = runTest {
        interactor.await(StatsFilter(includeLegacyAggregates = false)) shouldBe LegacyAggregateTotals()
    }

    private fun row(
        date: String,
        mediaKind: MediaKind,
        duration: Long,
        characters: Long,
        cards: Long,
    ) = LegacyAggregateRow(
        localDate = ImmersionLocalDate.parse(date),
        mediaKind = mediaKind,
        profileId = "default",
        languageTag = null,
        titleId = TITLE_ID,
        activeDuration = MillisecondDuration(duration),
        characters = NonNegativeCounter(characters),
        cardsTotal = NonNegativeCounter(cards),
        recordCount = NonNegativeCounter(1),
    )

    companion object {
        private val TITLE_ID = TitleId("00000000-0000-0000-0000-000000000001")
    }
}
