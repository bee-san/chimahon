package eu.kanade.tachiyomi.ui.stats

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.AnalyticsDataQuality

class StatsOverviewMetricPresentationTest {

    @Test
    fun `source units without indexing make growth unavailable`() {
        overviewIndexedGrowthMetricValue(
            value = 0,
            quality = quality(sourceUnits = 3, indexedSourceUnits = 0, textSourceUnits = 3),
        ).shouldBeNull()
    }

    @Test
    fun `indexed growth remains available after raw text deletion`() {
        overviewIndexedGrowthMetricValue(
            value = 0,
            quality = quality(sourceUnits = 3, indexedSourceUnits = 3, textSourceUnits = 0),
        ) shouldBe 0L
    }

    @Test
    fun `indexed empty result remains numeric zero`() {
        overviewIndexedGrowthMetricValue(
            value = 0,
            quality = quality(sourceUnits = 3, indexedSourceUnits = 3, textSourceUnits = 3),
        ) shouldBe 0L
    }

    @Test
    fun `empty selected dataset remains numeric zero`() {
        overviewIndexedGrowthMetricValue(
            value = 0,
            quality = AnalyticsDataQuality(),
        ) shouldBe 0L
    }

    private fun quality(
        sourceUnits: Long,
        indexedSourceUnits: Long,
        textSourceUnits: Long,
    ) = AnalyticsDataQuality(
        sourceUnitCount = sourceUnits,
        indexedSourceUnitCount = indexedSourceUnits,
        textAvailableSourceUnitCount = textSourceUnits,
    )
}
