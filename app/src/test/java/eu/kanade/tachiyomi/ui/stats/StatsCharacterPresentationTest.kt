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
    fun `log frequency levels saturate across a narrow band and separate across a wide one`() {
        // The grid shades by level, so the level has to earn its five bands. Log scaling means a
        // band of counts within roughly 2x of the maximum all reads as the top level — the shading
        // only carries information once counts span orders of magnitude, which is what a real
        // reading history looks like and what the legend's log(count + 1) wording describes.
        val narrow = listOf(1_818L, 2_006L, 2_343L).map { characterFrequencyLevel(it, 2_343) }
        narrow shouldBe listOf(5, 5, 5)

        val wide = listOf(2L, 30L, 400L, 5_000L, 60_000L)
            .map { characterFrequencyLevel(it, 60_000) }
        wide shouldBe listOf(1, 2, 3, 4, 5)
    }

    @Test
    fun `four shaded levels keep a blank day distinct from the quietest active one`() {
        // The activity heatmap paints four shaded steps plus an unshaded one, so it asks for four
        // levels where the character grid asks for five. A day with a single character has to land
        // on the first shaded step, not on the blank step a day with nothing gets.
        characterFrequencyLevel(0, 60_000, levels = 4) shouldBe 0
        characterFrequencyLevel(1, 60_000, levels = 4) shouldBe 1
        characterFrequencyLevel(60_000, 60_000, levels = 4) shouldBe 4

        val spread = listOf(3L, 60L, 1_500L, 60_000L)
            .map { characterFrequencyLevel(it, 60_000, levels = 4) }
        spread shouldBe listOf(1, 2, 3, 4)
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
