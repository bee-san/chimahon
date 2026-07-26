// SPDX-License-Identifier: MIT

package eu.kanade.presentation.more.stats

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.MaturityTier
import tachiyomi.domain.immersion.model.ProvenanceState

class StatsFilterSelectionTest {

    @Test
    fun `empty single and multiple selections have bounded render summaries`() {
        emptySet<MaturityTier>().statsFilterSelectionSummary() shouldBe
            StatsFilterSelectionSummary.All
        setOf(MaturityTier.MATURE).statsFilterSelectionSummary() shouldBe
            StatsFilterSelectionSummary.Single(MaturityTier.MATURE)
        setOf(
            MaturityTier.NEW,
            MaturityTier.LEARNING,
            MaturityTier.YOUNG,
        ).statsFilterSelectionSummary() shouldBe StatsFilterSelectionSummary.Multiple(3)
    }

    @Test
    fun `multi select narrows from all and normalizes every option back to all`() {
        val options = statsMaturityFilterOptions
        var selected = toggleStatsFilterSelection(
            selected = emptySet(),
            option = MaturityTier.NEW,
            options = options,
        )
        selected shouldBe setOf(MaturityTier.NEW)

        options.filterNot { it == MaturityTier.NEW }.forEach { option ->
            selected = toggleStatsFilterSelection(selected, option, options)
        }

        selected shouldBe emptySet()
        selected.statsFilterSelectionSummary() shouldBe StatsFilterSelectionSummary.All
    }

    @Test
    fun `unknown options cannot leak into a rendered filter selection`() {
        toggleStatsFilterSelection(
            selected = setOf("AVAILABLE", "REMOVED"),
            option = "UNKNOWN_FUTURE_VALUE",
            options = listOf("AVAILABLE", "PARTIAL", "REMOVED"),
        ) shouldBe setOf("AVAILABLE", "REMOVED")
    }

    @Test
    fun `filter choices contain only values emitted by analytics row filters`() {
        statsMaturityFilterOptions shouldBe listOf(
            MaturityTier.UNKNOWN,
            MaturityTier.NEW,
            MaturityTier.LEARNING,
            MaturityTier.YOUNG,
            MaturityTier.MATURE,
        )
        statsProvenanceFilterOptions shouldBe listOf(
            ProvenanceState.AVAILABLE,
            ProvenanceState.LEGACY_AGGREGATE,
        )
    }

    @Test
    fun `unknown persisted values fail closed instead of widening to all`() {
        setOf("FUTURE_MATURITY")
            .decodePersistedStatsFilterSelection(MaturityTier.UNAVAILABLE) shouldBe
            setOf(MaturityTier.UNAVAILABLE)
        setOf("FUTURE_PROVENANCE")
            .decodePersistedStatsFilterSelection(ProvenanceState.UNAVAILABLE) shouldBe
            setOf(ProvenanceState.UNAVAILABLE)
    }

    @Test
    fun `empty and partially decodable persisted values keep their intended scope`() {
        emptySet<String>()
            .decodePersistedStatsFilterSelection(MaturityTier.UNAVAILABLE) shouldBe emptySet()
        setOf(MaturityTier.MATURE.name, "FUTURE_MATURITY")
            .decodePersistedStatsFilterSelection(MaturityTier.UNAVAILABLE) shouldBe
            setOf(MaturityTier.MATURE)
        setOf(ProvenanceState.PARTIAL.name)
            .decodePersistedStatsFilterSelection(ProvenanceState.UNAVAILABLE) shouldBe
            setOf(ProvenanceState.PARTIAL)
    }
}
