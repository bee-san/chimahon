// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.glance

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReadingStatsWidgetLoadStateTest {

    @Test
    fun `successful zero totals remain available`() {
        val zeroStats = ReadingStatsWidgetData()

        readingStatsWidgetLoadState(Result.success(zeroStats)) shouldBe
            ReadingStatsWidgetLoadState.Available(zeroStats)
    }

    @Test
    fun `query failure becomes unavailable`() {
        readingStatsWidgetLoadState(
            Result.failure(IllegalStateException("database unavailable")),
        ) shouldBe ReadingStatsWidgetLoadState.Unavailable
    }
}
