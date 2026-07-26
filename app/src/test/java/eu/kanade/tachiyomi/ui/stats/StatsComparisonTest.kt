// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StatsComparisonTest {

    @Test
    fun `missing previous period stays unavailable instead of becoming zero`() {
        activeTimeComparison(
            currentMillis = 60_000,
            previousMillis = null,
            changeRatio = null,
        ) shouldBe StatsActiveTimeComparison(
            direction = StatsComparisonDirection.NO_PREVIOUS,
            previousMillis = null,
            absoluteDeltaMillis = null,
            percentageChange = null,
        )
    }

    @Test
    fun `increase exposes absolute and percentage changes`() {
        val comparison = activeTimeComparison(
            currentMillis = 90_000,
            previousMillis = 60_000,
            changeRatio = 0.5,
        )

        comparison.direction shouldBe StatsComparisonDirection.UP
        comparison.previousMillis shouldBe 60_000
        comparison.absoluteDeltaMillis shouldBe 30_000
        comparison.percentageChange!!.shouldBeExactly(0.5)
    }

    @Test
    fun `decrease normalizes percentage magnitude`() {
        val comparison = activeTimeComparison(
            currentMillis = 30_000,
            previousMillis = 60_000,
            changeRatio = -0.5,
        )

        comparison.direction shouldBe StatsComparisonDirection.DOWN
        comparison.absoluteDeltaMillis shouldBe 30_000
        comparison.percentageChange!!.shouldBeExactly(0.5)
    }

    @Test
    fun `zero previous value keeps percentage unavailable`() {
        val comparison = activeTimeComparison(
            currentMillis = 30_000,
            previousMillis = 0,
            changeRatio = null,
        )

        comparison.direction shouldBe StatsComparisonDirection.UP
        comparison.absoluteDeltaMillis shouldBe 30_000
        comparison.percentageChange shouldBe null
    }
}
