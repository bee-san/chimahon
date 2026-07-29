package eu.kanade.tachiyomi.ui.stats

import tachiyomi.domain.immersion.model.AnalyticsGoalProgress
import tachiyomi.domain.immersion.model.CapabilityState

internal enum class StatsGoalForecastPresentation {
    NONE,
    AVAILABLE,
    PARTIAL,
    UNAVAILABLE,
    STALE,
}

internal fun statsGoalForecastPresentation(
    progress: AnalyticsGoalProgress,
): StatsGoalForecastPresentation {
    val isDaily = progress.goal.period == "DAILY" || progress.goal.type == "PERPETUAL_DAILY"
    if (
        isDaily ||
        progress.targetToDate <= 0.0 ||
        progress.achieved >= progress.targetToDate
    ) {
        return StatsGoalForecastPresentation.NONE
    }
    return when (progress.forecastConfidence) {
        CapabilityState.AVAILABLE -> if (progress.projectedCompletionDate != null) {
            StatsGoalForecastPresentation.AVAILABLE
        } else {
            StatsGoalForecastPresentation.UNAVAILABLE
        }
        CapabilityState.PARTIAL -> StatsGoalForecastPresentation.PARTIAL
        CapabilityState.UNAVAILABLE -> StatsGoalForecastPresentation.UNAVAILABLE
        CapabilityState.STALE -> StatsGoalForecastPresentation.STALE
    }
}
