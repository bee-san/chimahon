// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.service

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.AnalyticsBucketScale
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.LocalDateRange
import java.time.DayOfWeek
import java.time.Instant

class ImmersionAnalyticsCalendarTest {

    @Test
    fun `cross-midnight active duration is split without losing milliseconds`() {
        val calendar = ImmersionAnalyticsCalendar()
        val end = Instant.parse("2026-03-30T00:30:00Z").toEpochMilli()

        calendar.splitDuration(
            endEpochMillis = end,
            durationMillis = 60 * 60 * 1_000,
            offsetSeconds = 0,
        ) shouldContainExactly linkedMapOf(
            ImmersionLocalDate.parse("2026-03-29") to 30 * 60 * 1_000L,
            ImmersionLocalDate.parse("2026-03-30") to 30 * 60 * 1_000L,
        )
    }

    @Test
    fun `event offsets remain authoritative across daylight saving changes`() {
        val calendar = ImmersionAnalyticsCalendar()
        val instant = Instant.parse("2026-10-25T00:30:00Z").toEpochMilli()

        calendar.localDate(instant, 3_600) shouldBe ImmersionLocalDate.parse("2026-10-25")
        calendar.localDate(instant, 0) shouldBe ImmersionLocalDate.parse("2026-10-25")
        calendar.splitDuration(instant, 60 * 60 * 1_000, 3_600).values.sum() shouldBe
            60 * 60 * 1_000L
    }

    @Test
    fun `week buckets honor configured first day and zero range edges`() {
        val calendar = ImmersionAnalyticsCalendar(DayOfWeek.SUNDAY)

        calendar.buckets(
            LocalDateRange(
                ImmersionLocalDate.parse("2026-07-01"),
                ImmersionLocalDate.parse("2026-07-15"),
            ),
            AnalyticsBucketScale.WEEK,
        ) shouldContainExactly listOf(
            LocalDateRange(
                ImmersionLocalDate.parse("2026-07-01"),
                ImmersionLocalDate.parse("2026-07-04"),
            ),
            LocalDateRange(
                ImmersionLocalDate.parse("2026-07-05"),
                ImmersionLocalDate.parse("2026-07-11"),
            ),
            LocalDateRange(
                ImmersionLocalDate.parse("2026-07-12"),
                ImmersionLocalDate.parse("2026-07-15"),
            ),
        )
    }

    @Test
    fun `previous range has equal length across leap day`() {
        val calendar = ImmersionAnalyticsCalendar()

        calendar.previousEqualRange(
            LocalDateRange(
                ImmersionLocalDate.parse("2024-02-29"),
                ImmersionLocalDate.parse("2024-03-02"),
            ),
        ) shouldBe LocalDateRange(
            ImmersionLocalDate.parse("2024-02-26"),
            ImmersionLocalDate.parse("2024-02-28"),
        )
    }
}
