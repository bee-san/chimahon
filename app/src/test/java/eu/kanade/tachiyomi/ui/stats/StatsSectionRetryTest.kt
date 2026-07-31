package eu.kanade.tachiyomi.ui.stats

import eu.kanade.presentation.more.stats.StatsCollectionLoadState
import eu.kanade.presentation.more.stats.StatsLoadable
import eu.kanade.presentation.more.stats.StatsSection
import eu.kanade.presentation.more.stats.StatsSections
import eu.kanade.presentation.more.stats.collectionLoadState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StatsSectionRetryTest {

    @Test
    fun `retry marks only the requested section as refreshing`() {
        val failed = failedSections()

        StatsSection.entries.forEach { target ->
            val retrying = failed.retrying(target)

            StatsSection.entries.forEach { section ->
                retrying.isRefreshing(section) shouldBe (section == target)
                retrying.hasError(section) shouldBe (section != target)
            }
        }
    }

    @Test
    fun `retry preserves stale data while clearing its error`() {
        StatsLoadable(
            value = "stale value",
            error = true,
        ).refreshing() shouldBe StatsLoadable(
            value = "stale value",
            refreshing = true,
            error = false,
        )
    }

    @Test
    fun `collection state distinguishes loading empty content and stale failure`() {
        StatsLoadable<String>(refreshing = true)
            .collectionLoadState(hasContent = false) shouldBe StatsCollectionLoadState.LOADING
        StatsLoadable<String>()
            .collectionLoadState(hasContent = false) shouldBe StatsCollectionLoadState.EMPTY
        StatsLoadable(value = "value")
            .collectionLoadState(hasContent = true) shouldBe StatsCollectionLoadState.CONTENT
        StatsLoadable(value = "value", refreshing = true)
            .collectionLoadState(hasContent = true) shouldBe
            StatsCollectionLoadState.REFRESHING_CONTENT
        StatsLoadable<String>(error = true)
            .collectionLoadState(hasContent = false) shouldBe StatsCollectionLoadState.ERROR
        StatsLoadable(value = "stale", error = true)
            .collectionLoadState(hasContent = true) shouldBe StatsCollectionLoadState.STALE_ERROR
    }

    private fun failedSections() = StatsSections(
        overview = StatsLoadable(error = true),
        heatmap = StatsLoadable(error = true),
        trends = StatsLoadable(error = true),
        temporalActivity = StatsLoadable(error = true),
        titleTrends = StatsLoadable(error = true),
        titles = StatsLoadable(error = true),
        characters = StatsLoadable(error = true),
        characterSummary = StatsLoadable(error = true),
        sessions = StatsLoadable(error = true),
        goals = StatsLoadable(error = true),
        anki = StatsLoadable(error = true),
    )

    private fun StatsSections.hasError(section: StatsSection): Boolean = when (section) {
        StatsSection.OVERVIEW -> overview.error
        StatsSection.HEATMAP -> heatmap.error
        StatsSection.TRENDS -> trends.error
        StatsSection.TEMPORAL_ACTIVITY -> temporalActivity.error
        StatsSection.TITLE_TRENDS -> titleTrends.error
        StatsSection.TITLES -> titles.error
        StatsSection.CHARACTERS -> characters.error
        StatsSection.CHARACTER_SUMMARY -> characterSummary.error
        StatsSection.SESSIONS -> sessions.error
        StatsSection.GOALS -> goals.error
        StatsSection.ANKI -> anki.error
    }
}
