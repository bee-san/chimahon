package tachiyomi.domain.immersion.service

import java.time.Instant
import java.time.ZoneId

data class RecorderTime(
    val epochMillis: Long,
    val monotonicNanos: Long,
    val zoneId: ZoneId,
) {
    init {
        require(epochMillis >= 0) { "Wall-clock time cannot be negative" }
    }

    val offsetSeconds: Int
        get() = zoneId.rules.getOffset(Instant.ofEpochMilli(epochMillis)).totalSeconds
}

fun interface ImmersionRecorderClock {
    fun now(): RecorderTime
}

object SystemImmersionRecorderClock : ImmersionRecorderClock {
    override fun now() = RecorderTime(
        epochMillis = System.currentTimeMillis(),
        monotonicNanos = System.nanoTime(),
        zoneId = ZoneId.systemDefault(),
    )
}

data class ActiveDurationSegment(
    val occurredAtEpochMillis: Long,
    val timezoneOffsetSeconds: Int,
    val durationMillis: Long,
)

internal fun splitActiveDurationAtLocalMidnight(
    startEpochMillis: Long,
    endEpochMillis: Long,
    durationMillis: Long,
    zoneId: ZoneId,
): List<ActiveDurationSegment> {
    require(startEpochMillis >= 0) { "Start time cannot be negative" }
    require(endEpochMillis >= 0) { "End time cannot be negative" }
    require(durationMillis >= 0) { "Duration cannot be negative" }
    if (durationMillis == 0L) return emptyList()
    if (endEpochMillis <= startEpochMillis) {
        return listOf(
            ActiveDurationSegment(
                occurredAtEpochMillis = endEpochMillis,
                timezoneOffsetSeconds = zoneId.rules
                    .getOffset(Instant.ofEpochMilli(endEpochMillis))
                    .totalSeconds,
                durationMillis = durationMillis,
            ),
        )
    }

    val wallDuration = endEpochMillis - startEpochMillis
    val boundaries = buildList {
        var cursor = Instant.ofEpochMilli(startEpochMillis).atZone(zoneId)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
        while (cursor in (startEpochMillis + 1) until endEpochMillis) {
            add(cursor)
            cursor = Instant.ofEpochMilli(cursor).atZone(zoneId)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        }
        add(endEpochMillis)
    }

    var segmentStart = startEpochMillis
    var assignedDuration = 0L
    return boundaries.mapIndexed { index, segmentEnd ->
        val segmentDuration = if (index == boundaries.lastIndex) {
            durationMillis - assignedDuration
        } else {
            val proportional = ((segmentEnd - segmentStart).toDouble() / wallDuration * durationMillis)
                .toLong()
                .coerceAtMost(durationMillis - assignedDuration)
            assignedDuration += proportional
            proportional
        }
        segmentStart = segmentEnd
        ActiveDurationSegment(
            occurredAtEpochMillis = segmentEnd,
            timezoneOffsetSeconds = zoneId.rules.getOffset(Instant.ofEpochMilli(segmentEnd)).totalSeconds,
            durationMillis = segmentDuration,
        )
    }.filter { it.durationMillis > 0 }
}
