package tachiyomi.domain.immersion.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ImmersionStatsDeletionScopeTest {
    @Test
    fun `empty scope cannot widen into full deletion`() {
        shouldThrow<IllegalArgumentException> {
            ImmersionStatsDeletionScope()
        }
    }

    @Test
    fun `all supported privacy filters map to the stats query contract`() {
        val titleId = TitleId("00000000-0000-0000-0000-000000000001")
        val language = LanguageTag("ja")
        val range = LocalDateRange(
            ImmersionLocalDate.from(LocalDate.parse("2026-07-01")),
            ImmersionLocalDate.from(LocalDate.parse("2026-07-31")),
        )
        val scope = ImmersionStatsDeletionScope(
            dateRange = range,
            titleIds = setOf(titleId),
            mediaKinds = setOf(MediaKind.NOVEL),
            profileIds = setOf("default"),
            languageTags = setOf(language),
        )

        scope.asStatsFilter() shouldBe StatsFilter(
            dateRange = range,
            titleIds = setOf(titleId),
            mediaKinds = setOf(MediaKind.NOVEL),
            profileIds = setOf("default"),
            languageTags = setOf(language),
        )
    }
}
