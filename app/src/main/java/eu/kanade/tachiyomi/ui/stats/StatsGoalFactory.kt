// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.TitleId
import java.time.DayOfWeek
import kotlin.math.roundToLong

internal const val ACTIVE_TIME_GOAL_METRIC = "active_time_ms"
internal const val SOURCE_UNITS_GOAL_METRIC = "source_units"

internal val STATS_GOAL_METRICS = setOf(
    ACTIVE_TIME_GOAL_METRIC,
    "gross_characters",
    "unique_source_characters",
    "net_characters",
    SOURCE_UNITS_GOAL_METRIC,
    "sessions",
    "lookups",
    "cards",
    "new_words",
    "new_characters",
    "manual",
)

enum class StatsGoalKind {
    DAILY,
    DATE_BOUND_TOTAL,
    FINISH_TITLE_BY_DATE,
    MANUAL,
}

/**
 * Historical activity remains assigned to the local date recorded with each event. "Today" and
 * future goal boundaries follow the device's current timezone when the goal is evaluated.
 */
internal enum class StatsGoalTimezonePolicy {
    RECORDED_EVENT_DATE_CURRENT_DEVICE_TODAY,
}

internal val STATS_GOAL_TIMEZONE_POLICY =
    StatsGoalTimezonePolicy.RECORDED_EVENT_DATE_CURRENT_DEVICE_TODAY

internal data class StatsGoalScope(
    val mediaKind: MediaKind?,
    val profileId: String?,
    val languageTag: LanguageTag?,
    val titleId: TitleId?,
)

data class StatsGoalEditorValues(
    val kind: StatsGoalKind,
    val metric: String,
    val inputTarget: Double,
    val startDate: ImmersionLocalDate,
    val endDate: ImmersionLocalDate?,
    val weekdayMultipliers: Map<DayOfWeek, Double>,
)

internal enum class StatsGoalDisplayKind {
    DURATION,
    COUNT,
}

internal data class StatsGoalDisplayValue(
    val kind: StatsGoalDisplayKind,
    val value: Double,
)

internal fun statsGoalDisplayValue(metric: String, value: Double): StatsGoalDisplayValue =
    StatsGoalDisplayValue(
        kind = if (metric == ACTIVE_TIME_GOAL_METRIC) {
            StatsGoalDisplayKind.DURATION
        } else {
            StatsGoalDisplayKind.COUNT
        },
        value = value,
    )

internal fun defaultStatsGoalWeekdayMultipliers(): Map<DayOfWeek, Double> =
    DayOfWeek.entries.associateWith { 1.0 }

internal fun suggestedStatsGoalWeekdayMultipliers(): Map<DayOfWeek, Double> =
    defaultStatsGoalWeekdayMultipliers() + mapOf(
        DayOfWeek.SATURDAY to 0.5,
        DayOfWeek.SUNDAY to 0.0,
    )

internal fun createStatsGoal(
    id: String,
    values: StatsGoalEditorValues,
    scope: StatsGoalScope,
    nowEpochMillis: Long,
): ImmersionGoal? = buildStatsGoal(
    id = id,
    values = values,
    scope = scope,
    state = "ACTIVE",
    createdAtEpochMillis = nowEpochMillis,
    updatedAtEpochMillis = nowEpochMillis,
)

/**
 * Applies an edit from the current local day forward. Keeping the same goal ID preserves immutable
 * achievement and check-in rows; moving the start forward prevents the new target from rewriting
 * the goal's previously evaluated period.
 */
internal fun editStatsGoalProspectively(
    existing: ImmersionGoal,
    values: StatsGoalEditorValues,
    prospectiveStartDate: ImmersionLocalDate,
    nowEpochMillis: Long,
): ImmersionGoal? {
    if (nowEpochMillis < existing.createdAtEpochMillis) return null
    val prospectiveValues = values.copy(
        startDate = maxOf(values.startDate, prospectiveStartDate),
    )
    return buildStatsGoal(
        id = existing.id,
        values = prospectiveValues,
        scope = StatsGoalScope(
            mediaKind = existing.mediaKind,
            profileId = existing.profileId,
            languageTag = existing.languageTag,
            titleId = existing.titleId,
        ),
        state = existing.state,
        createdAtEpochMillis = existing.createdAtEpochMillis,
        updatedAtEpochMillis = nowEpochMillis,
    )
}

internal fun ImmersionGoal.toStatsGoalEditorValues(
    fallbackStartDate: ImmersionLocalDate,
): StatsGoalEditorValues {
    val kind = when (type) {
        "PERPETUAL_DAILY" -> StatsGoalKind.DAILY
        "DATE_BOUND_TOTAL" -> StatsGoalKind.DATE_BOUND_TOTAL
        "FINISH_TITLE_BY_DATE" -> StatsGoalKind.FINISH_TITLE_BY_DATE
        "MANUAL" -> StatsGoalKind.MANUAL
        else -> if (period == "DAILY") StatsGoalKind.DAILY else StatsGoalKind.DATE_BOUND_TOTAL
    }
    return StatsGoalEditorValues(
        kind = kind,
        metric = metric,
        inputTarget = when {
            kind == StatsGoalKind.MANUAL -> 1.0
            metric == ACTIVE_TIME_GOAL_METRIC -> target / MILLIS_PER_MINUTE
            else -> target
        },
        startDate = startDate ?: fallbackStartDate,
        endDate = endDate,
        weekdayMultipliers = decodeStatsGoalWeekdayMultipliers(weekdayMultipliers),
    )
}

internal fun encodeStatsGoalWeekdayMultipliers(
    multipliers: Map<DayOfWeek, Double>,
): String? {
    if (!multipliers.isValidStatsGoalSchedule()) return null
    return DayOfWeek.entries.joinToString(separator = ";") { day ->
        "${day.name}=${multipliers.getValue(day)}"
    }
}

internal fun decodeStatsGoalWeekdayMultipliers(value: String?): Map<DayOfWeek, Double> {
    if (value.isNullOrBlank()) return defaultStatsGoalWeekdayMultipliers()
    val parsed = Regex(
        "(MONDAY|TUESDAY|WEDNESDAY|THURSDAY|FRIDAY|SATURDAY|SUNDAY)\\s*[:=]\\s*" +
            "([0-9]+(?:\\.[0-9]+)?)",
    ).findAll(value).associate { match ->
        DayOfWeek.valueOf(match.groupValues[1]) to match.groupValues[2].toDouble()
    }
    val result = DayOfWeek.entries.associateWith { parsed[it] ?: 1.0 }
    return if (result.isValidStatsGoalSchedule()) {
        result
    } else {
        defaultStatsGoalWeekdayMultipliers()
    }
}

internal fun StatsGoalDisplayValue.durationMillis(): Long {
    require(kind == StatsGoalDisplayKind.DURATION)
    return value.coerceAtLeast(0.0).roundToLong()
}

private fun buildStatsGoal(
    id: String,
    values: StatsGoalEditorValues,
    scope: StatsGoalScope,
    state: String,
    createdAtEpochMillis: Long,
    updatedAtEpochMillis: Long,
): ImmersionGoal? {
    if (id.isBlank() || state.isBlank() || createdAtEpochMillis < 0) return null
    if (updatedAtEpochMillis < createdAtEpochMillis) return null
    if (!values.weekdayMultipliers.isValidStatsGoalSchedule()) return null

    val metric = when (values.kind) {
        StatsGoalKind.FINISH_TITLE_BY_DATE -> SOURCE_UNITS_GOAL_METRIC
        StatsGoalKind.MANUAL -> "manual"
        else -> values.metric
    }
    if (metric !in STATS_GOAL_METRICS) return null
    if (values.kind == StatsGoalKind.FINISH_TITLE_BY_DATE && scope.titleId == null) return null

    val endDate = when (values.kind) {
        StatsGoalKind.DATE_BOUND_TOTAL,
        StatsGoalKind.FINISH_TITLE_BY_DATE,
        -> values.endDate ?: return null
        StatsGoalKind.DAILY,
        StatsGoalKind.MANUAL,
        -> values.endDate
    }
    if (endDate != null && values.startDate > endDate) return null

    val inputTarget = if (values.kind == StatsGoalKind.MANUAL) 1.0 else values.inputTarget
    if (!inputTarget.isFinite() || inputTarget <= 0) return null
    val storedTarget = if (metric == ACTIVE_TIME_GOAL_METRIC) {
        inputTarget * MILLIS_PER_MINUTE
    } else {
        inputTarget
    }
    if (!storedTarget.isFinite() || storedTarget <= 0) return null

    return ImmersionGoal(
        id = id,
        type = when (values.kind) {
            StatsGoalKind.DAILY -> "PERPETUAL_DAILY"
            StatsGoalKind.DATE_BOUND_TOTAL -> "DATE_BOUND_TOTAL"
            StatsGoalKind.FINISH_TITLE_BY_DATE -> "FINISH_TITLE_BY_DATE"
            StatsGoalKind.MANUAL -> "MANUAL"
        },
        metric = metric,
        target = storedTarget,
        period = when (values.kind) {
            StatsGoalKind.DAILY,
            StatsGoalKind.MANUAL,
            -> "DAILY"
            StatsGoalKind.DATE_BOUND_TOTAL,
            StatsGoalKind.FINISH_TITLE_BY_DATE,
            -> "TOTAL"
        },
        startDate = values.startDate,
        endDate = endDate,
        mediaKind = scope.mediaKind,
        profileId = scope.profileId,
        languageTag = scope.languageTag,
        titleId = scope.titleId,
        weekdayMultipliers = encodeStatsGoalWeekdayMultipliers(values.weekdayMultipliers),
        restDayPolicy = if (values.weekdayMultipliers.values.any { it == 0.0 }) "SKIP" else "NONE",
        state = state,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

private fun Map<DayOfWeek, Double>.isValidStatsGoalSchedule(): Boolean =
    keys == DayOfWeek.entries.toSet() &&
        values.all { it.isFinite() && it >= 0.0 } &&
        values.any { it > 0.0 }

private const val MILLIS_PER_MINUTE = 60_000.0
