// SPDX-License-Identifier: MIT

package eu.kanade.presentation.more.stats

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.MaturityTier

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
        val options = MaturityTier.entries
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
}
