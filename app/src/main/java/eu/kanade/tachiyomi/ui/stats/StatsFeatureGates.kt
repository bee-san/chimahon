package eu.kanade.tachiyomi.ui.stats

import eu.kanade.presentation.more.stats.StatsTab

internal fun enabledStatsTabs(
    goalsEnabled: Boolean,
    ankiEnabled: Boolean,
): List<StatsTab> = StatsTab.entries.filter { tab ->
    (tab != StatsTab.GOALS || goalsEnabled) &&
        (tab != StatsTab.ANKI || ankiEnabled)
}

internal fun resolveEnabledStatsTab(
    requested: StatsTab,
    goalsEnabled: Boolean,
    ankiEnabled: Boolean,
): StatsTab = requested.takeIf { it in enabledStatsTabs(goalsEnabled, ankiEnabled) }
    ?: StatsTab.OVERVIEW
