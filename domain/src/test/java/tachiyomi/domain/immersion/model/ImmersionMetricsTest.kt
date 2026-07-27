// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ImmersionMetricsTest {
    @Test
    fun `rates use the documented metric and denominator`() {
        val metrics = ReadingMetrics(
            activeTime = MillisecondDuration(30 * 60 * 1_000),
            characters = CharacterVolume(
                gross = NonNegativeCounter(10_000),
                uniqueSource = NonNegativeCounter(8_000),
                netProgress = NetCharacterProgress(7_000),
            ),
            uniqueWords = NonNegativeCounter(2_000),
            newWords = NonNegativeCounter(500),
            successfulLookups = NonNegativeCounter(20),
            cardsCreated = NonNegativeCounter(10),
            cardsUpdated = NonNegativeCounter(50),
            characterCoverage = CharacterCoverage(
                encounteredTargetScriptCharacters = NonNegativeCounter(250),
                representedInAnki = NonNegativeCounter(200),
            ),
        )

        metrics.readingSpeedPerHour(CharacterMetric.GROSS)!! shouldBeExactly 20_000.0
        metrics.readingSpeedPerHour(CharacterMetric.UNIQUE_SOURCE)!! shouldBeExactly 16_000.0
        metrics.readingSpeedPerHour(CharacterMetric.NET_PROGRESS)!! shouldBeExactly 14_000.0
        metrics.lookupRatePerTenThousandGrossCharacters()!! shouldBeExactly 20.0
        metrics.miningRatePerTenThousandGrossCharacters()!! shouldBeExactly 10.0
        metrics.noveltyRate()!! shouldBeExactly 0.25
        metrics.newWordsPerTenThousandGrossCharacters()!! shouldBeExactly 500.0
        metrics.vocabularyDensityPerTenThousandGrossCharacters()!! shouldBeExactly 2_000.0
        metrics.characterCoverage.ratio()!! shouldBeExactly 0.8
    }

    @Test
    fun `zero or non-positive denominators are unavailable rather than zero`() {
        ReadingMetrics().readingSpeedPerHour(CharacterMetric.GROSS) shouldBe null
        ReadingMetrics().lookupRatePerTenThousandGrossCharacters() shouldBe null
        ReadingMetrics().miningRatePerTenThousandGrossCharacters() shouldBe null
        ReadingMetrics().noveltyRate() shouldBe null
        ReadingMetrics().newWordsPerTenThousandGrossCharacters() shouldBe null
        ReadingMetrics().vocabularyDensityPerTenThousandGrossCharacters() shouldBe null
        ReadingMetrics().characterCoverage.ratio() shouldBe null

        val backwardOnly = ReadingMetrics(
            activeTime = MillisecondDuration(1_000),
            characters = CharacterVolume(netProgress = NetCharacterProgress(-10)),
        )
        backwardOnly.readingSpeedPerHour(CharacterMetric.NET_PROGRESS) shouldBe null
    }

    @Test
    fun `card updates do not contribute to mining rate`() {
        val metrics = ReadingMetrics(
            characters = CharacterVolume(gross = NonNegativeCounter(10_000)),
            cardsCreated = NonNegativeCounter(1),
            cardsUpdated = NonNegativeCounter(99),
        )

        metrics.miningRatePerTenThousandGrossCharacters()!! shouldBeExactly 1.0
    }

    @Test
    fun `character coverage rejects impossible represented counts`() {
        shouldThrow<IllegalArgumentException> {
            CharacterCoverage(
                encounteredTargetScriptCharacters = NonNegativeCounter(1),
                representedInAnki = NonNegativeCounter(2),
            )
        }
    }
}
