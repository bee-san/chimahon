// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.model

/**
 * A deliberately non-empty scope for destructive immersion-history deletion.
 *
 * Full deletion is a separate API with a separate confirmation path, so a
 * missing form value can never silently widen a scoped deletion to all stats.
 */
data class ImmersionStatsDeletionScope(
    val dateRange: LocalDateRange? = null,
    val titleIds: Set<TitleId> = emptySet(),
    val mediaKinds: Set<MediaKind> = emptySet(),
    val profileIds: Set<String> = emptySet(),
    val languageTags: Set<LanguageTag> = emptySet(),
) {
    init {
        require(
            dateRange != null ||
                titleIds.isNotEmpty() ||
                mediaKinds.isNotEmpty() ||
                profileIds.isNotEmpty() ||
                languageTags.isNotEmpty(),
        ) {
            "Scoped stats deletion requires at least one filter"
        }
        require(profileIds.none(String::isBlank)) { "Profile IDs cannot be blank" }
    }

    fun asStatsFilter() =
        StatsFilter(
            dateRange = dateRange,
            titleIds = titleIds,
            mediaKinds = mediaKinds,
            profileIds = profileIds,
            languageTags = languageTags,
        )
}
