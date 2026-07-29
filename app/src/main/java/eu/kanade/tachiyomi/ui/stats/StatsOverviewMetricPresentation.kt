package eu.kanade.tachiyomi.ui.stats

import tachiyomi.domain.immersion.model.AnalyticsDataQuality

internal fun overviewIndexedGrowthMetricValue(
    value: Long,
    quality: AnalyticsDataQuality,
): Long? {
    val hasSourceDataWithoutUsableIndex =
        quality.sourceUnitCount > 0L && quality.indexedSourceUnitCount == 0L
    return if (hasSourceDataWithoutUsableIndex) null else value
}
