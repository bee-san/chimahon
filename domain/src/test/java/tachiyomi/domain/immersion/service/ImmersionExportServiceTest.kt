// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ImmersionExportServiceTest {

    @Test
    fun `CSV export neutralizes spreadsheet formulas and escapes quotes`() {
        listOf(
            listOf("title", "value"),
            listOf("=HYPERLINK(\"https://example.test\")", "+1"),
            listOf("-2", "@command"),
        ).toCsv() shouldBe
            "\"title\",\"value\"\r\n" +
            "\"'=HYPERLINK(\"\"https://example.test\"\")\",\"'+1\"\r\n" +
            "\"'-2\",\"'@command\"\r\n"
    }
}
