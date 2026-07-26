// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import tachiyomi.domain.immersion.model.AnalyticsPage
import tachiyomi.domain.immersion.model.AnalyticsSourceOccurrence
import java.util.concurrent.atomic.AtomicLong

internal class StatsPagingRequestTracker {
    private val generation = AtomicLong()

    fun invalidate(): Long = generation.incrementAndGet()

    fun snapshot(): Long = generation.get()

    fun isCurrent(snapshot: Long): Boolean = generation.get() == snapshot
}

internal fun <T, K> mergeAnalyticsPages(
    current: AnalyticsPage<T>,
    next: AnalyticsPage<T>,
    keyOf: (T) -> K,
): AnalyticsPage<T> {
    val merged = LinkedHashMap<K, T>(current.items.size + next.items.size)
    current.items.forEach { item -> merged.putIfAbsent(keyOf(item), item) }
    next.items.forEach { item -> merged.putIfAbsent(keyOf(item), item) }
    return AnalyticsPage(
        items = merged.values.toList(),
        nextOffset = next.nextOffset,
    )
}

internal data class StatsOccurrenceKey(
    val sourceUnitId: String,
    val sessionId: String,
    val occurredAtEpochMillis: Long,
    val canonicalLocator: String,
)

internal fun AnalyticsSourceOccurrence.statsOccurrenceKey(): StatsOccurrenceKey =
    StatsOccurrenceKey(
        sourceUnitId = sourceUnitId.value,
        sessionId = sessionId.value,
        occurredAtEpochMillis = occurredAtEpochMillis,
        canonicalLocator = canonicalLocator,
    )
