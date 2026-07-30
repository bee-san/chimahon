package eu.kanade.tachiyomi.ui.stats

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StatsDurationTest {

    @Test
    fun `nonzero sub-second duration is not rounded to zero`() {
        statsDurationParts(500) shouldBe StatsDurationParts(
            hours = 0,
            minutes = 0,
            seconds = 0,
            lessThanSecond = true,
        )
    }

    @Test
    fun `sub-minute duration retains seconds`() {
        statsDurationParts(30_000) shouldBe StatsDurationParts(
            hours = 0,
            minutes = 0,
            seconds = 30,
            lessThanSecond = false,
        )
    }

    @Test
    fun `long duration separates hours minutes and seconds`() {
        statsDurationParts(5_490_000) shouldBe StatsDurationParts(
            hours = 1,
            minutes = 31,
            seconds = 30,
            lessThanSecond = false,
        )
    }

    @Test
    fun `hours carry the remaining minutes rather than absorbing them`() {
        // The compact hero tile renders hours and minutes together, so a duration whose minute
        // remainder is dropped would silently read as a round hour count.
        statsDurationParts(143_280_000) shouldBe StatsDurationParts(
            hours = 39,
            minutes = 48,
            seconds = 0,
            lessThanSecond = false,
        )
    }

    @Test
    fun `whole hours report zero minutes`() {
        statsDurationParts(7_200_000) shouldBe StatsDurationParts(
            hours = 2,
            minutes = 0,
            seconds = 0,
            lessThanSecond = false,
        )
    }
}
