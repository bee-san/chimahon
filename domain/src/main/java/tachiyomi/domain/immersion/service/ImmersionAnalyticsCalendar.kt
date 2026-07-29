package tachiyomi.domain.immersion.service

import tachiyomi.domain.immersion.model.AnalyticsBucketScale
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.LocalDateRange
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

class ImmersionAnalyticsCalendar(
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
) {
    fun localDate(epochMillis: Long, offsetSeconds: Int): ImmersionLocalDate {
        require(epochMillis >= 0)
        val date = Instant.ofEpochMilli(epochMillis)
            .atOffset(ZoneOffset.ofTotalSeconds(offsetSeconds))
            .toLocalDate()
        return ImmersionLocalDate.from(date)
    }

    fun bucket(date: ImmersionLocalDate, scale: AnalyticsBucketScale): LocalDateRange {
        val localDate = date.toLocalDate()
        return when (scale) {
            AnalyticsBucketScale.DAY -> LocalDateRange(date, date)
            AnalyticsBucketScale.WEEK -> {
                val start = localDate.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
                LocalDateRange(
                    ImmersionLocalDate.from(start),
                    ImmersionLocalDate.from(start.plusDays(6)),
                )
            }
            AnalyticsBucketScale.MONTH -> {
                val start = localDate.withDayOfMonth(1)
                LocalDateRange(
                    ImmersionLocalDate.from(start),
                    ImmersionLocalDate.from(start.with(TemporalAdjusters.lastDayOfMonth())),
                )
            }
        }
    }

    fun buckets(
        range: LocalDateRange,
        scale: AnalyticsBucketScale,
    ): List<LocalDateRange> {
        val result = mutableListOf<LocalDateRange>()
        var cursor = bucket(range.start, scale)
        while (cursor.start <= range.endInclusive) {
            result += LocalDateRange(
                start = maxOf(cursor.start, range.start),
                endInclusive = minOf(cursor.endInclusive, range.endInclusive),
            )
            cursor = when (scale) {
                AnalyticsBucketScale.DAY -> oneDay(cursor.endInclusive.epochDay + 1)
                AnalyticsBucketScale.WEEK -> {
                    val start = ImmersionLocalDate(cursor.start.epochDay + 7)
                    LocalDateRange(start, ImmersionLocalDate(start.epochDay + 6))
                }
                AnalyticsBucketScale.MONTH -> {
                    val start = cursor.start.toLocalDate().plusMonths(1).withDayOfMonth(1)
                    bucket(ImmersionLocalDate.from(start), scale)
                }
            }
        }
        return result
    }

    fun previousEqualRange(range: LocalDateRange): LocalDateRange {
        val dayCount = Math.addExact(
            Math.subtractExact(range.endInclusive.epochDay, range.start.epochDay),
            1,
        )
        val previousEnd = Math.subtractExact(range.start.epochDay, 1)
        return LocalDateRange(
            start = ImmersionLocalDate(Math.subtractExact(previousEnd, dayCount - 1)),
            endInclusive = ImmersionLocalDate(previousEnd),
        )
    }

    /**
     * Splits an active interval ending at [endEpochMillis] at fixed-offset local
     * midnight boundaries. Event offsets are authoritative, including across
     * DST changes where adjacent events carry different offsets.
     */
    fun splitDuration(
        endEpochMillis: Long,
        durationMillis: Long,
        offsetSeconds: Int,
    ): Map<ImmersionLocalDate, Long> {
        require(endEpochMillis >= 0)
        require(durationMillis >= 0)
        if (durationMillis == 0L) return emptyMap()
        val startEpochMillis = (endEpochMillis - durationMillis).coerceAtLeast(0)
        var cursor = startEpochMillis
        val result = linkedMapOf<ImmersionLocalDate, Long>()
        val offset = ZoneOffset.ofTotalSeconds(offsetSeconds)
        while (cursor < endEpochMillis) {
            val local = Instant.ofEpochMilli(cursor).atOffset(offset)
            val nextMidnight = local.toLocalDate().plusDays(1).atStartOfDay().toInstant(offset).toEpochMilli()
            val segmentEnd = minOf(endEpochMillis, nextMidnight)
            val date = ImmersionLocalDate.from(local.toLocalDate())
            result[date] = Math.addExact(result[date] ?: 0, segmentEnd - cursor)
            cursor = segmentEnd
        }
        return result
    }

    private fun oneDay(epochDay: Long): LocalDateRange {
        val date = ImmersionLocalDate(epochDay)
        return LocalDateRange(date, date)
    }
}
