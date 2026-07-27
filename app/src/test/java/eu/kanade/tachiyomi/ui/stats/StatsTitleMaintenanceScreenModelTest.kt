// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.TitleId

class StatsTitleMaintenanceScreenModelTest {

    private val sourceTitleId = TitleId("00000000-0000-0000-0000-000000000001")

    @Test
    fun `split request trims input and preserves inclusive date range`() {
        val request = createTitleSplitRequest(
            sourceTitleId = sourceTitleId,
            displayTitle = "  Second season  ",
            startDate = " 2026-01-02 ",
            endDate = "2026-03-04",
        )

        request?.sourceTitleId shouldBe sourceTitleId
        request?.displayTitle shouldBe "Second season"
        request?.dateRange?.start shouldBe ImmersionLocalDate.parse("2026-01-02")
        request?.dateRange?.endInclusive shouldBe ImmersionLocalDate.parse("2026-03-04")
    }

    @Test
    fun `split request rejects blank title malformed dates and reversed range`() {
        createTitleSplitRequest(sourceTitleId, " ", "2026-01-01", "2026-01-02").shouldBeNull()
        createTitleSplitRequest(sourceTitleId, "Title", "not-a-date", "2026-01-02").shouldBeNull()
        createTitleSplitRequest(sourceTitleId, "Title", "2026-01-03", "2026-01-02").shouldBeNull()
    }
}
