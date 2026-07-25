package mihon.feature.stats.indexing

import android.app.Application
import chimahon.DictionaryRepository
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryPaths
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.UnicodeCodePoint
import tachiyomi.domain.immersion.service.DefaultUnicodeCountPolicy
import tachiyomi.domain.immersion.service.ImmersionStatsVersions
import tachiyomi.domain.immersion.service.ImmersionToken
import tachiyomi.domain.immersion.service.ImmersionTokenizer
import tachiyomi.domain.immersion.service.NormalizedText
import tachiyomi.domain.immersion.service.TokenizationResult

/**
 * Japanese indexing adapter over the same HoshiDicts-backed repository used by dictionary UI.
 *
 * It intentionally invokes the repository directly, below [tachiyomi.domain.immersion.service.LookupTelemetry],
 * so background tokenization can never appear as a user lookup.
 */
class DictionaryBackedJapaneseTokenizer(
    private val application: Application,
    private val dictionaryRepository: DictionaryRepository,
    private val dictionaryPreferences: DictionaryPreferences,
) : ImmersionTokenizer {
    override val id: String = "hoshidicts-japanese"
    override val version: Int = ImmersionStatsVersions.TOKENIZER

    override fun supports(language: LanguageTag): Boolean =
        language.value.substringBefore('-') == "ja" && japaneseProfile() != null

    override suspend fun tokenize(text: NormalizedText): TokenizationResult {
        val profile = requireNotNull(japaneseProfile()) { "No Japanese dictionary profile is available" }
        val paths = getDictionaryPaths(application, profile)
        require(paths.termPaths.isNotEmpty()) { "No Japanese term dictionaries are available" }
        dictionaryRepository.warmUp(paths, profile.id)

        val tokens = mutableListOf<ImmersionToken>()
        var offset = 0
        while (offset < text.value.length) {
            val codePoint = text.value.codePointAt(offset)
            if (!DefaultUnicodeCountPolicy.isCountable(UnicodeCodePoint(codePoint))) {
                offset += Character.charCount(codePoint)
                continue
            }
            val windowEnd = codePointBoundaryAtMost(text.value, offset, MAX_LOOKUP_CODE_POINTS)
            val window = text.value.substring(offset, windowEnd)
            val result = dictionaryRepository.lookup(window, paths, text.language.value)
                .results
                .firstOrNull { candidate ->
                    candidate.matched.isNotBlank() && window.startsWith(candidate.matched)
                }
            if (result == null) {
                val end = offset + Character.charCount(codePoint)
                val surface = text.value.substring(offset, end)
                tokens += ImmersionToken(
                    headword = surface,
                    displayHeadword = surface,
                    surface = surface,
                    startOffset = offset.toLong(),
                    endOffset = end.toLong(),
                    confidence = UNKNOWN_CHARACTER_CONFIDENCE,
                )
                offset = end
            } else {
                val matchedEnd = offset + result.matched.length
                val frequency = result.term.frequencies
                    .flatMap { entry ->
                        entry.frequencies.mapNotNull { value ->
                            value.value.takeIf { it > 0 }?.let { rank -> entry.dictName to rank.toLong() }
                        }
                    }
                    .minByOrNull { it.second }
                tokens += ImmersionToken(
                    headword = result.term.expression,
                    reading = result.term.reading.takeIf(String::isNotBlank),
                    displayHeadword = result.term.expression,
                    displayReading = result.term.reading.takeIf(String::isNotBlank),
                    partOfSpeech = result.term.rules.takeIf(String::isNotBlank),
                    surface = result.matched,
                    startOffset = offset.toLong(),
                    endOffset = matchedEnd.toLong(),
                    deinflectionRule = result.deinflected.takeIf { it != result.matched },
                    confidence = DICTIONARY_CONFIDENCE,
                    frequencyCorpus = frequency?.first,
                    frequencyRank = frequency?.second,
                )
                offset = matchedEnd
            }
        }
        val confidence = if (tokens.any { it.confidence == UNKNOWN_CHARACTER_CONFIDENCE }) {
            MIXED_CONFIDENCE
        } else {
            DICTIONARY_CONFIDENCE
        }
        return TokenizationResult(tokens, confidence)
    }

    private fun japaneseProfile() =
        dictionaryPreferences.profileStore.getProfiles()
            .firstOrNull { it.languageCode.substringBefore('-') == "ja" }
            ?: dictionaryPreferences.profileStore.getActiveProfile()
                .takeIf { it.languageCode.substringBefore('-') == "ja" }

    private fun codePointBoundaryAtMost(
        value: String,
        start: Int,
        maximumCodePoints: Int,
    ): Int {
        var offset = start
        var count = 0
        while (offset < value.length && count < maximumCodePoints) {
            offset += Character.charCount(value.codePointAt(offset))
            count++
        }
        return offset
    }

    private companion object {
        const val MAX_LOOKUP_CODE_POINTS = 64
        const val DICTIONARY_CONFIDENCE = 0.95
        const val UNKNOWN_CHARACTER_CONFIDENCE = 0.2
        const val MIXED_CONFIDENCE = 0.7
    }
}
