package eu.kanade.tachiyomi.ui.stats

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.LocalDateRange
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.TitleId
import java.time.LocalDate

class StatsDeletionScopeInputTest {
    @Test
    fun `valid form maps every supported deletion dimension`() {
        val titleId = "00000000-0000-0000-0000-000000000001"
        val scope = StatsDeletionScopeInput(
            startDate = "2026-07-01",
            endDate = "2026-07-31",
            titleId = titleId,
            mediaKind = MediaKind.NOVEL,
            profileId = "default",
            languageTag = "ja",
        ).parseDeletionScope()

        scope?.dateRange shouldBe LocalDateRange(
            ImmersionLocalDate.from(LocalDate.parse("2026-07-01")),
            ImmersionLocalDate.from(LocalDate.parse("2026-07-31")),
        )
        scope?.titleIds shouldBe setOf(TitleId(titleId))
        scope?.mediaKinds shouldBe setOf(MediaKind.NOVEL)
        scope?.profileIds shouldBe setOf("default")
        scope?.languageTags shouldBe setOf(LanguageTag("ja"))
    }

    @Test
    fun `empty malformed or half-open forms fail closed`() {
        StatsDeletionScopeInput().parseDeletionScope().shouldBeNull()
        StatsDeletionScopeInput(startDate = "2026-07-01").parseDeletionScope().shouldBeNull()
        StatsDeletionScopeInput(titleId = "not-a-uuid").parseDeletionScope().shouldBeNull()
        StatsDeletionScopeInput(languageTag = "_").parseDeletionScope().shouldBeNull()
    }
}
