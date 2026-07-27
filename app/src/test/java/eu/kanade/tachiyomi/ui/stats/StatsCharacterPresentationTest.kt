// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.AnalyticsCharacterScript
import tachiyomi.domain.immersion.model.AnalyticsCharacterScriptSummary
import tachiyomi.domain.immersion.model.AnalyticsCharacterSummary
import tachiyomi.domain.immersion.model.ImmersionAnkiItem
import tachiyomi.domain.immersion.model.UnicodeCodePoint
import tachiyomi.domain.immersion.repository.ImmersionAnkiRepository

class StatsCharacterPresentationTest {

    @Test
    fun `log frequency levels keep zero distinct and bound large counts`() {
        characterFrequencyLevel(0, 1_000_000) shouldBe 0
        characterFrequencyLevel(1, 1_000_000) shouldBe 1
        characterFrequencyLevel(1_000_000, 1_000_000) shouldBe 5
    }

    @Test
    fun `supplementary glyph stays one rendered character and falls back by code point`() {
        val codePoint = UnicodeCodePoint(0x20000)
        val rendered = codePoint.asString()

        rendered.codePointCount(0, rendered.length) shouldBe 1
        characterDisplayText(rendered, codePoint) { true } shouldBe rendered
        characterDisplayText(rendered, codePoint) { false } shouldBe "U+20000"
    }

    @Test
    fun `coverage target produces an explicitly bounded planning suggestion`() {
        val summary = AnalyticsCharacterSummary(
            scripts = listOf(
                AnalyticsCharacterScriptSummary(
                    script = AnalyticsCharacterScript.HAN,
                    distinctCharacters = 1_000,
                    grossOccurrenceExposure = 10_000,
                    representedInAnki = 700,
                    matureInAnki = 600,
                ),
            ),
            firstSeenInRange = 20,
            maximumOccurrenceCount = 500,
        )

        characterCoverageTarget(summary, targetPercent = 90) shouldBe
            CharacterCoverageTarget(
                targetCharacters = 900,
                remainingCharacters = 300,
                dailyPlanningSuggestion = 10,
            )
    }

    @Test
    fun `character Anki detail batches selected profiles and deduplicates cards`() = runTest {
        val repository = mockk<ImmersionAnkiRepository>()
        val codePoint = UnicodeCodePoint('猫'.code)
        val item = mockk<ImmersionAnkiItem> {
            every { snapshotId } returns "snapshot"
            every { cardId } returns 7
        }
        coEvery {
            repository.findCharacterItems(listOf("primary", "secondary"), codePoint)
        } returns listOf(item, item)

        loadCharacterAnkiItems(
            repository = repository,
            profileIds = listOf("primary", "", "primary", "secondary"),
            codePoint = codePoint,
        ) shouldBe listOf(item)

        coVerify(exactly = 1) {
            repository.findCharacterItems(listOf("primary", "secondary"), codePoint)
        }
    }
}
