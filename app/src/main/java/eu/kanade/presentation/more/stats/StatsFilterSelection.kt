// SPDX-License-Identifier: MIT

package eu.kanade.presentation.more.stats

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
