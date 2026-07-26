// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.TitleId

class StatsScreenRouteTest {

    @Test
    fun `canonical analytics title id keeps a title scoped route`() {
        val value = "00000000-0000-0000-0000-000000000001"

        canonicalStatsTitleId(value) shouldBe TitleId(value)
    }

    @Test
    fun `legacy media ids cannot masquerade as analytics title routes`() {
        canonicalStatsTitleId("123").shouldBeNull()
        canonicalStatsTitleId("novel-folder").shouldBeNull()
        canonicalStatsTitleId(null).shouldBeNull()
    }
}
