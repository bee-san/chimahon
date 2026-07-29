package eu.kanade.tachiyomi.ui.stats

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StatsReaderIdleTimeoutTest {

    @Test
    fun `only bounded idle timeout choices are accepted`() {
        STATS_READER_IDLE_TIMEOUT_SECONDS shouldContainExactly listOf(30, 60, 120, 300)
        STATS_READER_IDLE_TIMEOUT_SECONDS.forEach { seconds ->
            validatedStatsReaderIdleTimeoutSeconds(seconds) shouldBe seconds
        }
        validatedStatsReaderIdleTimeoutSeconds(0).shouldBeNull()
        validatedStatsReaderIdleTimeoutSeconds(90).shouldBeNull()
        validatedStatsReaderIdleTimeoutSeconds(301).shouldBeNull()
    }

    @Test
    fun `unsupported stored value maps to safe default`() {
        normalizeStatsReaderIdleTimeoutSeconds(90) shouldBe
            DEFAULT_STATS_READER_IDLE_TIMEOUT_SECONDS
    }

    @Test
    fun `choice maps to compact localized display unit`() {
        statsReaderIdleTimeoutDisplay(30) shouldBe
            StatsReaderIdleTimeoutDisplay(30, StatsReaderIdleTimeoutUnit.SECONDS)
        statsReaderIdleTimeoutDisplay(120) shouldBe
            StatsReaderIdleTimeoutDisplay(2, StatsReaderIdleTimeoutUnit.MINUTES)
    }
}
