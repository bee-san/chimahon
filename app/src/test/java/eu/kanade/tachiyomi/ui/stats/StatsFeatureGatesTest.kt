// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import eu.kanade.presentation.more.stats.StatsTab
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StatsFeatureGatesTest {

    @Test
    fun `disabled optional features are absent from dashboard tabs`() {
        val tabs = enabledStatsTabs(goalsEnabled = false, ankiEnabled = false)

        tabs.shouldNotContain(StatsTab.GOALS)
        tabs.shouldNotContain(StatsTab.ANKI)
    }

    @Test
    fun `disabled persisted tab resolves to overview`() {
        resolveEnabledStatsTab(
            requested = StatsTab.GOALS,
            goalsEnabled = false,
            ankiEnabled = true,
        ) shouldBe StatsTab.OVERVIEW

        resolveEnabledStatsTab(
            requested = StatsTab.ANKI,
            goalsEnabled = true,
            ankiEnabled = false,
        ) shouldBe StatsTab.OVERVIEW
    }
}
