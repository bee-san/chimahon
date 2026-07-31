package eu.kanade.tachiyomi.ui.stats

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.CapabilityState

class StatsAnkiPresentationTest {

    @Test
    fun `stale last known good inventory is never presented as available`() {
        ankiPresentationCapabilityState(
            capabilityState = CapabilityState.AVAILABLE,
            isStale = true,
        ) shouldBe CapabilityState.STALE
    }

    @Test
    fun `current inventory preserves its capability state`() {
        ankiPresentationCapabilityState(
            capabilityState = CapabilityState.PARTIAL,
            isStale = false,
        ) shouldBe CapabilityState.PARTIAL
    }
}
