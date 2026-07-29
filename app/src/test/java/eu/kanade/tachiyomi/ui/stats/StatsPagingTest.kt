package eu.kanade.tachiyomi.ui.stats

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.AnalyticsPage

class StatsPagingTest {

    @Test
    fun `request tracker rejects results captured before invalidation`() {
        val tracker = StatsPagingRequestTracker()
        val firstRequest = tracker.snapshot()

        tracker.isCurrent(firstRequest) shouldBe true

        val nextRequest = tracker.invalidate()

        tracker.isCurrent(firstRequest) shouldBe false
        tracker.isCurrent(nextRequest) shouldBe true
    }

    @Test
    fun `page merge appends in order and removes boundary duplicates`() {
        val merged = mergeAnalyticsPages(
            current = AnalyticsPage(
                items = listOf(Item("a", 1), Item("b", 2)),
                nextOffset = 2,
            ),
            next = AnalyticsPage(
                items = listOf(Item("b", 99), Item("c", 3)),
                nextOffset = 4,
            ),
            keyOf = Item::id,
        )

        merged.items shouldBe listOf(Item("a", 1), Item("b", 2), Item("c", 3))
        merged.nextOffset shouldBe 4
    }

    private data class Item(
        val id: String,
        val value: Int,
    )
}
