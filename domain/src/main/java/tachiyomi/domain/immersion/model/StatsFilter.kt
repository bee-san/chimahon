package tachiyomi.domain.immersion.model

import kotlinx.serialization.Serializable

@Serializable
data class StatsFilter(
    val dateRange: LocalDateRange? = null,
    val comparisonRange: LocalDateRange? = null,
    val mediaKinds: Set<MediaKind> = emptySet(),
    val profileIds: Set<String> = emptySet(),
    val languageTags: Set<LanguageTag> = emptySet(),
    val titleIds: Set<TitleId> = emptySet(),
    val includeLegacyAggregates: Boolean = true,
    val characterMetric: CharacterMetric = CharacterMetric.GROSS,
    val includeRereadsAndReplays: Boolean = true,
    val maturityTiers: Set<MaturityTier> = emptySet(),
    val provenanceStates: Set<ProvenanceState> = emptySet(),
) {
    init {
        require(profileIds.none { it.isBlank() }) { "Profile IDs cannot be blank" }
    }
}
