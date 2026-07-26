// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.LanguageTag

class DefaultSourceTextNormalizerTest {

    private val language = LanguageTag("ja")

    @Test
    fun `repeat collapsing keeps at most three BMP code points`() {
        val normalized = DefaultSourceTextNormalizer(collapseRepeatedCharacters = true)
            .normalize("あああああいいいい", language)

        normalized.value shouldBe "あああいいい"
    }

    @Test
    fun `repeat collapsing keeps at most three supplementary code points`() {
        val normalized = DefaultSourceTextNormalizer(collapseRepeatedCharacters = true)
            .normalize("𠮷𠮷𠮷𠮷𠮷", language)

        normalized.value shouldBe "𠮷𠮷𠮷"
    }

    @Test
    fun `repeat collapsing remains disabled by default`() {
        val input = "ああああ𠮷𠮷𠮷𠮷"

        DefaultSourceTextNormalizer().normalize(input, language).value shouldBe input
    }
}
