package mihon.feature.stats.indexing

import tachiyomi.data.immersion.SqlDelightImmersionRepository
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.model.UnicodeCodePoint
import tachiyomi.domain.immersion.service.ImmersionIndexExclusionPolicy

class SqlImmersionIndexExclusionPolicy(
    private val repository: SqlDelightImmersionRepository,
) : ImmersionIndexExclusionPolicy {
    override suspend fun excludesWord(
        identity: String,
        languageTag: LanguageTag,
        titleId: TitleId,
    ): Boolean =
        repository.isIndexEntityExcluded(
            entityType = "WORD",
            entityId = identity,
            scopeKeys = scopes(languageTag, titleId),
        )

    override suspend fun excludesCharacter(
        codePoint: UnicodeCodePoint,
        languageTag: LanguageTag,
        titleId: TitleId,
    ): Boolean =
        repository.isIndexEntityExcluded(
            entityType = "CHARACTER",
            entityId = codePoint.value.toString(),
            scopeKeys = scopes(languageTag, titleId),
        )

    private fun scopes(
        languageTag: LanguageTag,
        titleId: TitleId,
    ): List<String> = listOf("", "language:${languageTag.value}", "title:${titleId.value}")
}
