// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import eu.kanade.presentation.more.stats.StatsFilterState
import eu.kanade.presentation.more.stats.StatsRangePreset
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.AnkiMaturityAggregation
import tachiyomi.domain.immersion.model.CharacterMetric
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.MaturityTier
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.ProvenanceState
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.model.TitleId
import java.time.LocalDate

class StatsFilterMappingTest {

    @Test
    fun `selected title profile language and metric options reach domain filter`() {
        val titleId = "00000000-0000-0000-0000-000000000001"
        val filter = StatsFilterState(
            rangePreset = StatsRangePreset.ALL,
            mediaKind = MediaKind.MANGA,
            profileId = "japanese",
            characterMetric = CharacterMetric.UNIQUE_SOURCE,
            includeLegacy = false,
            includeRereadsAndReplays = false,
            maturityTiers = setOf(MaturityTier.LEARNING, MaturityTier.MATURE),
            provenanceStates = setOf(ProvenanceState.AVAILABLE, ProvenanceState.PARTIAL),
            titleId = titleId,
        ).toStatsFilter(
            now = LocalDate.of(2026, 7, 26),
            profileLanguageCode = "ja_JP",
            ankiMaturityAggregation = AnkiMaturityAggregation.MIN_INTERVAL,
        )

        filter.dateRange shouldBe null
        filter.mediaKinds shouldBe setOf(MediaKind.MANGA)
        filter.profileIds shouldBe setOf("japanese")
        filter.languageTags.single().value shouldBe "ja-JP"
        filter.titleIds shouldBe setOf(TitleId(titleId))
        filter.characterMetric shouldBe CharacterMetric.UNIQUE_SOURCE
        filter.includeLegacyAggregates shouldBe false
        filter.includeRereadsAndReplays shouldBe false
        filter.maturityTiers shouldBe setOf(MaturityTier.LEARNING, MaturityTier.MATURE)
        filter.provenanceStates shouldBe setOf(
            ProvenanceState.AVAILABLE,
            ProvenanceState.PARTIAL,
        )
        filter.ankiMaturityAggregation shouldBe AnkiMaturityAggregation.MIN_INTERVAL
    }

    @Test
    fun `empty UI selections preserve all maturity and provenance data`() {
        val filter = StatsFilterState(rangePreset = StatsRangePreset.ALL).toStatsFilter(
            now = LocalDate.of(2026, 7, 26),
            profileLanguageCode = null,
        )

        filter.maturityTiers shouldBe emptySet()
        filter.provenanceStates shouldBe emptySet()
        filter.includeRereadsAndReplays shouldBe true
    }

    @Test
    fun `period offset produces the requested complete range`() {
        val range = StatsFilterState(
            rangePreset = StatsRangePreset.SEVEN_DAYS,
            periodOffset = -1,
        ).dateRange(LocalDate.of(2026, 7, 26))

        range?.start shouldBe ImmersionLocalDate.parse("2026-07-13")
        range?.endInclusive shouldBe ImmersionLocalDate.parse("2026-07-19")
    }

    @Test
    fun `custom range preserves explicit endpoints`() {
        val start = ImmersionLocalDate.parse("2024-02-28")
        val end = ImmersionLocalDate.parse("2024-03-02")

        StatsFilterState(
            rangePreset = StatsRangePreset.CUSTOM,
            customStart = start,
            customEnd = end,
        ).dateRange(LocalDate.of(2026, 7, 26)).let {
            it?.start shouldBe start
            it?.endInclusive shouldBe end
        }
    }

    @Test
    fun `invalid persisted title is ignored instead of breaking dashboard refresh`() {
        val filter = StatsFilterState(
            rangePreset = StatsRangePreset.ALL,
            titleId = "not-a-title-id",
        ).toStatsFilter(
            now = LocalDate.of(2026, 7, 26),
            profileLanguageCode = null,
        )

        filter.titleIds shouldBe emptySet()
    }

    @Test
    fun `heatmap limits all time history to the latest 365 days`() {
        val filter = StatsFilter().forHeatmap(LocalDate.of(2026, 7, 26))

        filter.dateRange?.start shouldBe ImmersionLocalDate.parse("2025-07-27")
        filter.dateRange?.endInclusive shouldBe ImmersionLocalDate.parse("2026-07-26")
    }

    @Test
    fun `heatmap preserves a shorter selected range`() {
        val filter = StatsFilter(
            dateRange = tachiyomi.domain.immersion.model.LocalDateRange(
                ImmersionLocalDate.parse("2026-07-01"),
                ImmersionLocalDate.parse("2026-07-15"),
            ),
        ).forHeatmap(LocalDate.of(2026, 7, 26))

        filter.dateRange?.start shouldBe ImmersionLocalDate.parse("2026-07-01")
        filter.dateRange?.endInclusive shouldBe ImmersionLocalDate.parse("2026-07-15")
    }
}
