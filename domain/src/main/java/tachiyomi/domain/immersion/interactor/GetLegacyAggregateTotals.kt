// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.interactor

import tachiyomi.domain.immersion.model.LegacyAggregateTotals
import tachiyomi.domain.immersion.model.MillisecondDuration
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.repository.ImmersionLegacyImportRepository

class GetLegacyAggregateTotals(
    private val repository: ImmersionLegacyImportRepository,
) {
    suspend fun await(filter: StatsFilter = StatsFilter()): LegacyAggregateTotals {
        if (!filter.includeLegacyAggregates) return LegacyAggregateTotals()
        val rows = repository.getLegacyAggregates().filter { row ->
            val dateMatches = filter.dateRange?.let { range ->
                row.localDate >= range.start && row.localDate <= range.endInclusive
            } ?: true
            dateMatches &&
                (filter.mediaKinds.isEmpty() || row.mediaKind in filter.mediaKinds) &&
                (filter.profileIds.isEmpty() || row.profileId in filter.profileIds) &&
                (filter.languageTags.isEmpty() || row.languageTag in filter.languageTags) &&
                (filter.titleIds.isEmpty() || row.titleId in filter.titleIds)
        }
        return LegacyAggregateTotals(
            activeDuration = MillisecondDuration(rows.sumOfExact { it.activeDuration.value }),
            characters = NonNegativeCounter(rows.sumOfExact { it.characters.value }),
            cardsTotal = NonNegativeCounter(rows.sumOfExact { it.cardsTotal.value }),
            records = NonNegativeCounter(rows.sumOfExact { it.recordCount.value }),
        )
    }

    private inline fun <T> Iterable<T>.sumOfExact(selector: (T) -> Long): Long =
        fold(0L) { total, value -> Math.addExact(total, selector(value)) }
}
