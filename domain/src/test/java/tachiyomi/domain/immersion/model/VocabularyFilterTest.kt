// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VocabularyFilterTest {

    @Test
    fun `default filter preserves the included vocabulary view`() {
        VocabularyFilter() shouldBe VocabularyFilter(
            knownness = VocabularyKnownness.ALL,
            exclusion = VocabularyExclusion.INCLUDED,
        )
    }

    @Test
    fun `occurrence and frequency bounds reject ambiguous values`() {
        shouldThrow<IllegalArgumentException> {
            VocabularyFilter(minimumOccurrences = 0)
        }
        shouldThrow<IllegalArgumentException> {
            VocabularyFilter(maximumOccurrences = -1)
        }
        shouldThrow<IllegalArgumentException> {
            VocabularyFilter(minimumOccurrences = 10, maximumOccurrences = 9)
        }
        shouldThrow<IllegalArgumentException> {
            VocabularyFilter(maximumFrequencyRank = 0)
        }
    }
}
