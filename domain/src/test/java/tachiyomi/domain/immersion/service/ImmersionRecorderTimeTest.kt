// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.service

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ImmersionRecorderTimeTest {
    @Test
    fun `active duration is split exactly at local midnight`() {
        val zone = ZoneId.of("Europe/London")
        val start = LocalDateTime.of(2026, 7, 25, 23, 59, 59).atZone(zone).toInstant().toEpochMilli()
        val end = LocalDateTime.of(2026, 7, 26, 0, 0, 1).atZone(zone).toInstant().toEpochMilli()

        splitActiveDurationAtLocalMidnight(start, end, 2_000, zone)
            .map { it.durationMillis } shouldContainExactly listOf(1_000L, 1_000L)
    }

    @Test
    fun `DST boundary preserves the whole monotonic duration`() {
        val zone = ZoneId.of("Europe/London")
        val start = LocalDateTime.of(2026, 3, 28, 23, 59, 59).atZone(zone).toInstant().toEpochMilli()
        val end = LocalDateTime.of(2026, 3, 29, 2, 0).atZone(zone).toInstant().toEpochMilli()

        splitActiveDurationAtLocalMidnight(start, end, 3_600_001, zone)
            .sumOf { it.durationMillis } shouldBe 3_600_001
    }

    @Test
    fun `backward wall clock remains one segment with monotonic duration`() {
        splitActiveDurationAtLocalMidnight(
            startEpochMillis = 10_000,
            endEpochMillis = 9_000,
            durationMillis = 2_000,
            zoneId = ZoneId.of("Asia/Tokyo"),
        ) shouldContainExactly listOf(
            ActiveDurationSegment(
                occurredAtEpochMillis = 9_000,
                timezoneOffsetSeconds = 9 * 60 * 60,
                durationMillis = 2_000,
            ),
        )
    }
}
