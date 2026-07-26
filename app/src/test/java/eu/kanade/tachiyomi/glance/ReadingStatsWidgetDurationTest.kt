// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.glance

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReadingStatsWidgetDurationTest {

    @Test
    fun `sub-hour duration uses only compact minutes`() {
        readingStatsWidgetDurationParts(30 * 60 * 1_000L) shouldBe
            ReadingStatsWidgetDurationParts(
                hours = null,
                minutes = 30,
            )
    }

    @Test
    fun `long duration keeps compact hours and remainder minutes`() {
        readingStatsWidgetDurationParts(5_490_000) shouldBe
            ReadingStatsWidgetDurationParts(
                hours = 1,
                minutes = 31,
            )
    }
}
