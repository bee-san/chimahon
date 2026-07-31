package eu.kanade.tachiyomi.ui.stats

import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.ImmersionStatsDeletionScope
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.LocalDateRange
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.TitleId
import java.time.LocalDate

data class StatsDeletionScopeInput(
    val startDate: String = "",
    val endDate: String = "",
    val titleId: String = "",
    val mediaKind: MediaKind? = null,
    val profileId: String = "",
    val languageTag: String = "",
)

internal fun StatsDeletionScopeInput.parseDeletionScope(): ImmersionStatsDeletionScope? =
    runCatching {
        val normalizedStart = startDate.trim()
        val normalizedEnd = endDate.trim()
        require(normalizedStart.isBlank() == normalizedEnd.isBlank()) {
            "Both deletion dates are required"
        }
        val range = normalizedStart.takeIf(String::isNotBlank)?.let {
            LocalDateRange(
                start = ImmersionLocalDate.from(LocalDate.parse(it)),
                endInclusive = ImmersionLocalDate.from(LocalDate.parse(normalizedEnd)),
            )
        }
        ImmersionStatsDeletionScope(
            dateRange = range,
            titleIds = titleId.trim().takeIf(String::isNotBlank)?.let(::TitleId)?.let(::setOf).orEmpty(),
            mediaKinds = mediaKind?.let(::setOf).orEmpty(),
            profileIds = profileId.trim().takeIf(String::isNotBlank)?.let(::setOf).orEmpty(),
            languageTags = languageTag
                .trim()
                .takeIf(String::isNotBlank)
                ?.let { LanguageTag.from(it) }
                ?.let(::setOf)
                .orEmpty(),
        )
    }.getOrNull()
