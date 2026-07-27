// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.glance

import eu.kanade.presentation.more.stats.StatsFilterState
import eu.kanade.presentation.more.stats.StatsRangePreset
import eu.kanade.tachiyomi.ui.stats.toStatsFilter
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.CharacterMetric
import tachiyomi.domain.immersion.model.CharacterVolume
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.MillisecondDuration
import tachiyomi.domain.immersion.model.NetCharacterProgress
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.ReadingMetrics
import java.time.LocalDate

class ReadingStatsWidgetParityTest {

    @Test
    fun `widget query matches the default Overview query for today`() {
        val now = LocalDate.of(2026, 7, 27)
        val characterMetric = CharacterMetric.UNIQUE_SOURCE
        val includeLegacy = false
        val includeRereads = false

        val overviewFilter = StatsFilterState(
            rangePreset = StatsRangePreset.TODAY,
            characterMetric = characterMetric,
            includeLegacy = includeLegacy,
            includeRereadsAndReplays = includeRereads,
        ).toStatsFilter(
            now = now,
            profileLanguageCode = null,
        )

        readingStatsWidgetFilter(
            today = ImmersionLocalDate.from(now),
            characterMetric = characterMetric,
            includeLegacyAggregates = includeLegacy,
            includeRereadsAndReplays = includeRereads,
        ) shouldBe overviewFilter
    }

    @Test
    fun `widget projects the same character basis active time speed and cards as Overview`() {
        val metrics = ReadingMetrics(
            activeTime = MillisecondDuration(3_600_000),
            characters = CharacterVolume(
                gross = NonNegativeCounter(1_200),
                uniqueSource = NonNegativeCounter(900),
                netProgress = NetCharacterProgress(600),
            ),
            cardsCreated = NonNegativeCounter(4),
        )

        mapOf(
            CharacterMetric.GROSS to 1_200L,
            CharacterMetric.UNIQUE_SOURCE to 900L,
            CharacterMetric.NET_PROGRESS to 600L,
        ).forEach { (metric, expectedCharacters) ->
            readingStatsWidgetData(metrics, metric) shouldBe ReadingStatsWidgetData(
                characters = expectedCharacters,
                activeTimeMillis = 3_600_000,
                charactersPerHour = expectedCharacters.toInt(),
                cardsCreated = 4,
            )
        }
    }
}
