// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.service

import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.UnicodeCodePoint
import java.lang.Character.UnicodeScript

interface CountableCharacterPolicy {
    val version: Int

    fun analyze(text: String): CharacterAnalysis

    fun isCountable(codePoint: UnicodeCodePoint): Boolean
}

enum class CharacterScript {
    HAN,
    HIRAGANA,
    KATAKANA,
    HANGUL,
    LATIN,
    OTHER,
}

data class CharacterAnalysis(
    val countableCharacters: NonNegativeCounter,
    val countsByScript: Map<CharacterScript, NonNegativeCounter>,
    val distinctCodePoints: Set<UnicodeCodePoint>,
)

/**
 * Counts Unicode scalar values rather than UTF-16 code units.
 *
 * The version 1 policy counts Unicode letters and numbers. It excludes whitespace, punctuation,
 * control/format characters, symbols, combining marks, and variation selectors. The retained
 * source hash/version allows a later policy to recompute derived counts.
 */
object DefaultUnicodeCountPolicy : CountableCharacterPolicy {
    override val version: Int = ImmersionStatsVersions.NORMALIZATION

    override fun analyze(text: String): CharacterAnalysis {
        val counts = CharacterScript.entries.associateWith { 0L }.toMutableMap()
        val distinct = mutableSetOf<UnicodeCodePoint>()
        var total = 0L
        var offset = 0

        while (offset < text.length) {
            val value = text.codePointAt(offset)
            val codePoint = UnicodeCodePoint(value)
            if (isCountable(codePoint)) {
                val script = classifyScript(codePoint)
                counts[script] = Math.addExact(counts.getValue(script), 1L)
                total = Math.addExact(total, 1L)
                distinct += codePoint
            }
            offset += Character.charCount(value)
        }

        return CharacterAnalysis(
            countableCharacters = NonNegativeCounter(total),
            countsByScript = counts.mapValues { NonNegativeCounter(it.value) },
            distinctCodePoints = distinct,
        )
    }

    override fun isCountable(codePoint: UnicodeCodePoint): Boolean = when (Character.getType(codePoint.value)) {
        Character.UPPERCASE_LETTER.toInt(),
        Character.LOWERCASE_LETTER.toInt(),
        Character.TITLECASE_LETTER.toInt(),
        Character.MODIFIER_LETTER.toInt(),
        Character.OTHER_LETTER.toInt(),
        Character.DECIMAL_DIGIT_NUMBER.toInt(),
        Character.LETTER_NUMBER.toInt(),
        Character.OTHER_NUMBER.toInt(),
        -> true
        else -> false
    }

    private fun classifyScript(codePoint: UnicodeCodePoint): CharacterScript = when (UnicodeScript.of(codePoint.value)) {
        UnicodeScript.HAN -> CharacterScript.HAN
        UnicodeScript.HIRAGANA -> CharacterScript.HIRAGANA
        UnicodeScript.KATAKANA -> CharacterScript.KATAKANA
        UnicodeScript.HANGUL -> CharacterScript.HANGUL
        UnicodeScript.LATIN -> CharacterScript.LATIN
        else -> CharacterScript.OTHER
    }
}
