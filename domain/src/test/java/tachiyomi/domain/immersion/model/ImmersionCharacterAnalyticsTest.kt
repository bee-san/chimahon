package tachiyomi.domain.immersion.model

import io.kotest.matchers.doubles.shouldBeExactly
import org.junit.jupiter.api.Test

class ImmersionCharacterAnalyticsTest {

    @Test
    fun `priority formula exposes deterministic component and mixed scores`() {
        AnalyticsCharacterPriorityFormula.components(
            frequencyRank = 10,
            jlptLevel = 1,
            gradeLevel = 1,
        ).let { components ->
            components.frequency shouldBeExactly 100_000.0
            components.jlpt shouldBeExactly 1_000_000.0
            components.grade shouldBeExactly 1_000_000.0
            components.score(AnalyticsCharacterPriorityMode.MIXED) shouldBeExactly 460_000.0
        }
        AnalyticsCharacterPriorityFormula.score(
            frequencyRank = 10,
            jlptLevel = 1,
            gradeLevel = 1,
            mode = AnalyticsCharacterPriorityMode.FREQUENCY,
        ) shouldBeExactly 100_000.0
        AnalyticsCharacterPriorityFormula.score(
            frequencyRank = 10,
            jlptLevel = 1,
            gradeLevel = 1,
            mode = AnalyticsCharacterPriorityMode.JLPT,
        ) shouldBeExactly 1_000_000.0
        AnalyticsCharacterPriorityFormula.score(
            frequencyRank = 10,
            jlptLevel = 1,
            gradeLevel = 1,
            mode = AnalyticsCharacterPriorityMode.GRADE,
        ) shouldBeExactly 1_000_000.0
        AnalyticsCharacterPriorityFormula.score(
            frequencyRank = 10,
            jlptLevel = 1,
            gradeLevel = 1,
            mode = AnalyticsCharacterPriorityMode.MIXED,
        ) shouldBeExactly 460_000.0
    }

    @Test
    fun `missing metadata contributes zero priority`() {
        AnalyticsCharacterPriorityMode.entries.forEach { mode ->
            AnalyticsCharacterPriorityFormula.score(null, null, null, mode) shouldBeExactly 0.0
        }
    }
}
