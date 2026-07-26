// SPDX-License-Identifier: MIT

package eu.kanade.presentation.more.stats

import tachiyomi.domain.immersion.model.MaturityTier
import tachiyomi.domain.immersion.model.ProvenanceState

/**
 * The analytics SQL resolves unmatched inventory rows to [MaturityTier.UNKNOWN]. Snapshot
 * availability and staleness are result-quality states, not per-row maturity values.
 */
internal val statsMaturityFilterOptions = listOf(
    MaturityTier.UNKNOWN,
    MaturityTier.NEW,
    MaturityTier.LEARNING,
    MaturityTier.YOUNG,
    MaturityTier.MATURE,
)

/**
 * Analytics rows currently derive provenance from the legacy-import bit. Partial, removed, and
 * unavailable provenance describe aggregate quality and cannot identify an individual row.
 */
internal val statsProvenanceFilterOptions = listOf(
    ProvenanceState.AVAILABLE,
    ProvenanceState.LEGACY_AGGREGATE,
)

internal sealed interface StatsFilterSelectionSummary<out T> {
    data object All : StatsFilterSelectionSummary<Nothing>

    data class Single<T>(
        val value: T,
    ) : StatsFilterSelectionSummary<T>

    data class Multiple(
        val count: Int,
    ) : StatsFilterSelectionSummary<Nothing>
}

internal fun <T> Set<T>.statsFilterSelectionSummary(): StatsFilterSelectionSummary<T> =
    when (size) {
        0 -> StatsFilterSelectionSummary.All
        1 -> StatsFilterSelectionSummary.Single(first())
        else -> StatsFilterSelectionSummary.Multiple(size)
    }

internal fun <T> toggleStatsFilterSelection(
    selected: Set<T>,
    option: T,
    options: List<T>,
): Set<T> {
    val available = options.toSet()
    if (option !in available) return selected.intersect(available)
    val current = selected.intersect(available)
    val updated = if (current.isEmpty()) {
        setOf(option)
    } else if (option in current) {
        current - option
    } else {
        current + option
    }
    return updated.takeUnless { it.size == available.size }.orEmpty()
}

/**
 * Keeps an empty stored set as the explicit "All" selection. A non-empty future/corrupt set that
 * cannot be decoded becomes a safe no-match state instead of silently broadening to "All".
 */
internal inline fun <reified T : Enum<T>> Set<String>.decodePersistedStatsFilterSelection(
    noMatchFallback: T,
): Set<T> {
    if (isEmpty()) return emptySet()
    return mapNotNullTo(linkedSetOf()) { value ->
        runCatching { enumValueOf<T>(value) }.getOrNull()
    }.ifEmpty { setOf(noMatchFallback) }
}
