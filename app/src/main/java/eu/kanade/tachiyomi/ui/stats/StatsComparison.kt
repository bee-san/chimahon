package eu.kanade.tachiyomi.ui.stats

internal enum class StatsComparisonDirection {
    NO_PREVIOUS,
    UP,
    DOWN,
    SAME,
}

internal data class StatsActiveTimeComparison(
    val direction: StatsComparisonDirection,
    val previousMillis: Long?,
    val absoluteDeltaMillis: Long?,
    val percentageChange: Double?,
)

internal fun activeTimeComparison(
    currentMillis: Long,
    previousMillis: Long?,
    changeRatio: Double?,
): StatsActiveTimeComparison {
    require(currentMillis >= 0)
    require(previousMillis == null || previousMillis >= 0)
    if (previousMillis == null) {
        return StatsActiveTimeComparison(
            direction = StatsComparisonDirection.NO_PREVIOUS,
            previousMillis = null,
            absoluteDeltaMillis = null,
            percentageChange = null,
        )
    }
    val direction = when {
        currentMillis > previousMillis -> StatsComparisonDirection.UP
        currentMillis < previousMillis -> StatsComparisonDirection.DOWN
        else -> StatsComparisonDirection.SAME
    }
    val absoluteDelta = if (currentMillis >= previousMillis) {
        currentMillis - previousMillis
    } else {
        previousMillis - currentMillis
    }
    return StatsActiveTimeComparison(
        direction = direction,
        previousMillis = previousMillis,
        absoluteDeltaMillis = absoluteDelta,
        percentageChange = changeRatio?.let { kotlin.math.abs(it) },
    )
}
