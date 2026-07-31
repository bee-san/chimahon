package tachiyomi.domain.immersion.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class UnicodeCountPolicyTest {
    @Test
    fun `counts supplementary code points once rather than as UTF-16 units`() {
        val supplementaryHan = "\uD840\uDC00"

        supplementaryHan.length shouldBe 2
        val analysis = DefaultUnicodeCountPolicy.analyze(supplementaryHan)
        analysis.countableCharacters.value shouldBe 1
        analysis.countsByScript.getValue(CharacterScript.HAN).value shouldBe 1
        analysis.distinctCodePoints.single().value shouldBe 0x20000
    }

    @Test
    fun `excludes whitespace punctuation controls formatting combining marks and symbols`() {
        val text = "A e\u0301。\n\u0000\u200D🙂"

        val analysis = DefaultUnicodeCountPolicy.analyze(text)

        analysis.countableCharacters.value shouldBe 2
        analysis.countsByScript.getValue(CharacterScript.LATIN).value shouldBe 2
    }

    @Test
    fun `classifies kana Han Hangul Latin and common digits`() {
        val analysis = DefaultUnicodeCountPolicy.analyze("日あア한A1")

        analysis.countableCharacters.value shouldBe 6
        analysis.countsByScript.getValue(CharacterScript.HAN).value shouldBe 1
        analysis.countsByScript.getValue(CharacterScript.HIRAGANA).value shouldBe 1
        analysis.countsByScript.getValue(CharacterScript.KATAKANA).value shouldBe 1
        analysis.countsByScript.getValue(CharacterScript.HANGUL).value shouldBe 1
        analysis.countsByScript.getValue(CharacterScript.LATIN).value shouldBe 1
        analysis.countsByScript.getValue(CharacterScript.OTHER).value shouldBe 1
    }

    @Test
    fun `distinct inventory does not duplicate repeated characters`() {
        val analysis = DefaultUnicodeCountPolicy.analyze("日日本本")

        analysis.countableCharacters.value shouldBe 4
        analysis.distinctCodePoints.size shouldBe 2
    }
}
