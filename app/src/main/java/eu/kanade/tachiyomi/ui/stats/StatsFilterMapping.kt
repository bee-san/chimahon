// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import eu.kanade.presentation.more.stats.StatsFilterState
import eu.kanade.presentation.more.stats.StatsRangePreset
import tachiyomi.domain.immersion.model.AnkiMaturityAggregation
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.LocalDateRange
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.model.TitleId
import java.time.LocalDate

internal fun StatsFilterState.toStatsFilter(
    now: LocalDate,
    profileLanguageCode: String?,
    ankiMaturityAggregation: AnkiMaturityAggregation = AnkiMaturityAggregation.MAX_INTERVAL,
): StatsFilter =
    StatsFilter(
        dateRange = dateRange(now),
        mediaKinds = mediaKind?.let(::setOf).orEmpty(),
        profileIds = profileId?.let(::setOf).orEmpty(),
        languageTags = profileLanguageCode
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { LanguageTag.from(it) }.getOrNull() }
            ?.let(::setOf)
            .orEmpty(),
        titleIds = titleId
            ?.let { runCatching { TitleId(it) }.getOrNull() }
            ?.let(::setOf)
            .orEmpty(),
        includeLegacyAggregates = includeLegacy,
        characterMetric = characterMetric,
        includeRereadsAndReplays = includeRereadsAndReplays,
        ankiMaturityAggregation = ankiMaturityAggregation,
    )

internal fun StatsFilterState.dateRange(now: LocalDate): LocalDateRange? {
    if (rangePreset == StatsRangePreset.ALL) return null
    if (rangePreset == StatsRangePreset.CUSTOM) {
        val start = customStart ?: return LocalDateRange(
            ImmersionLocalDate.from(now),
            ImmersionLocalDate.from(now),
        )
        return LocalDateRange(start, customEnd ?: start)
    }
    val days = when (rangePreset) {
        StatsRangePreset.TODAY -> 1
        StatsRangePreset.SEVEN_DAYS -> 7
        StatsRangePreset.THIRTY_DAYS -> 30
        StatsRangePreset.NINETY_DAYS -> 90
        StatsRangePreset.YEAR -> 365
        StatsRangePreset.ALL, StatsRangePreset.CUSTOM -> error("Handled above")
    }
    val end = now.plusDays(periodOffset.toLong() * days)
    val start = end.minusDays((days - 1).toLong())
    return LocalDateRange(
        ImmersionLocalDate.from(start),
        ImmersionLocalDate.from(end),
    )
}

internal fun StatsFilter.forHeatmap(now: LocalDate): StatsFilter {
    val today = ImmersionLocalDate.from(now)
    val requested = dateRange
    val end = minOf(requested?.endInclusive ?: today, today)
    val earliest = ImmersionLocalDate.from(end.toLocalDate().minusDays(364))
    val start = maxOf(requested?.start ?: earliest, earliest).coerceAtMost(end)
    return copy(dateRange = LocalDateRange(start, end))
}
