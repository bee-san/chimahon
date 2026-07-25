package tachiyomi.domain.immersion.service

import tachiyomi.domain.immersion.model.ImmersionReindexRequest
import tachiyomi.domain.immersion.model.IndexTerminalReason
import tachiyomi.domain.immersion.model.IndexWorkItem
import tachiyomi.domain.immersion.model.IndexedCharacter
import tachiyomi.domain.immersion.model.IndexedWord
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.model.UnicodeCodePoint
import tachiyomi.domain.immersion.repository.ImmersionIndexRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.math.min

data class NormalizedText(
    val value: String,
    val language: LanguageTag,
    val normalizationVersion: Int,
)

interface SourceTextNormalizer {
    val version: Int

    fun normalize(
        input: String,
        language: LanguageTag,
    ): NormalizedText
}

class DefaultSourceTextNormalizer(
    override val version: Int = ImmersionStatsVersions.NORMALIZATION,
    private val collapseRepeatedCharacters: Boolean = false,
) : SourceTextNormalizer {
    override fun normalize(
        input: String,
        language: LanguageTag,
    ): NormalizedText {
        var normalized = Normalizer.normalize(
            input.replace("\r\n", "\n").replace('\r', '\n'),
            Normalizer.Form.NFC,
        )
        normalized = normalized.replace(WHITESPACE, " ").trim()
        if (collapseRepeatedCharacters) {
            normalized = normalized.replace(REPEATED_CHARACTER) { match ->
                match.value.take(3)
            }
        }
        return NormalizedText(normalized, language, version)
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val REPEATED_CHARACTER = Regex("(.)\\1{3,}")
    }
}

data class ImmersionToken(
    val headword: String,
    val reading: String? = null,
    val displayHeadword: String = headword,
    val displayReading: String? = reading,
    val partOfSpeech: String? = null,
    val surface: String? = null,
    val startOffset: Long? = null,
    val endOffset: Long? = null,
    val deinflectionRule: String? = null,
    val confidence: Double? = null,
    val frequencyCorpus: String? = null,
    val frequencyRank: Long? = null,
    val jlptLevel: Int? = null,
    val gradeLevel: Int? = null,
) {
    init {
        require(headword.isNotBlank()) { "Token headword cannot be blank" }
        require(startOffset == null || startOffset >= 0) { "Token start cannot be negative" }
        require(endOffset == null || endOffset >= 0) { "Token end cannot be negative" }
        require(startOffset == null || endOffset == null || endOffset > startOffset) {
            "Token end must be after its start"
        }
        require(confidence == null || confidence in 0.0..1.0) {
            "Token confidence must be between zero and one"
        }
        require(frequencyRank == null || frequencyRank > 0) { "Frequency rank must be positive" }
    }
}

data class TokenizationResult(
    val tokens: List<ImmersionToken>,
    val confidence: Double?,
)

interface ImmersionTokenizer {
    val id: String
    val version: Int

    fun supports(language: LanguageTag): Boolean

    suspend fun tokenize(text: NormalizedText): TokenizationResult
}

class BoundaryImmersionTokenizer(
    override val version: Int = ImmersionStatsVersions.TOKENIZER,
) : ImmersionTokenizer {
    override val id: String = "unicode-boundary-low-confidence"

    override fun supports(language: LanguageTag): Boolean {
        val base = language.value.substringBefore('-')
        return base !in setOf("ja", "ko", "zh", "und")
    }

    override suspend fun tokenize(text: NormalizedText): TokenizationResult {
        val locale = Locale.forLanguageTag(text.language.value)
        val tokens = mutableListOf<ImmersionToken>()
        var start = -1
        var offset = 0
        while (offset < text.value.length) {
            val codePoint = text.value.codePointAt(offset)
            val countable = DefaultUnicodeCountPolicy.isCountable(UnicodeCodePoint(codePoint))
            if (countable && start < 0) start = offset
            if (!countable && start >= 0) {
                tokens += boundaryToken(text.value, start, offset, locale)
                start = -1
            }
            offset += Character.charCount(codePoint)
        }
        if (start >= 0) tokens += boundaryToken(text.value, start, text.value.length, locale)
        return TokenizationResult(tokens, BOUNDARY_CONFIDENCE)
    }

    private fun boundaryToken(
        text: String,
        start: Int,
        end: Int,
        locale: Locale,
    ): ImmersionToken {
        val surface = text.substring(start, end)
        return ImmersionToken(
            headword = surface.lowercase(locale),
            displayHeadword = surface,
            surface = surface,
            startOffset = start.toLong(),
            endOffset = end.toLong(),
            confidence = BOUNDARY_CONFIDENCE,
        )
    }

    private companion object {
        const val BOUNDARY_CONFIDENCE = 0.35
    }
}

interface ImmersionIndexExclusionPolicy {
    suspend fun excludesWord(
        identity: String,
        languageTag: LanguageTag,
        titleId: TitleId,
    ): Boolean

    suspend fun excludesCharacter(
        codePoint: UnicodeCodePoint,
        languageTag: LanguageTag,
        titleId: TitleId,
    ): Boolean
}

object NoOpImmersionIndexExclusionPolicy : ImmersionIndexExclusionPolicy {
    override suspend fun excludesWord(
        identity: String,
        languageTag: LanguageTag,
        titleId: TitleId,
    ): Boolean = false

    override suspend fun excludesCharacter(
        codePoint: UnicodeCodePoint,
        languageTag: LanguageTag,
        titleId: TitleId,
    ): Boolean = false
}

data class ImmersionIndexBatchResult(
    val claimed: Int,
    val indexed: Int,
    val unavailable: Int,
    val failed: Int,
)

class ImmersionIndexingEngine(
    private val repository: ImmersionIndexRepository,
    private val normalizer: SourceTextNormalizer,
    private val tokenizers: List<ImmersionTokenizer>,
    private val exclusionPolicy: ImmersionIndexExclusionPolicy = NoOpImmersionIndexExclusionPolicy,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun processBatch(
        targetVersion: Int = ImmersionStatsVersions.INDEX,
        limit: Int = DEFAULT_BATCH_SIZE,
    ): ImmersionIndexBatchResult {
        val now = clock()
        val work = repository.claimWork(targetVersion, limit, now)
        var indexed = 0
        var unavailable = 0
        var failed = 0
        work.forEach { item ->
            when (process(item, targetVersion, now)) {
                IndexOutcome.INDEXED -> indexed++
                IndexOutcome.UNAVAILABLE -> unavailable++
                IndexOutcome.FAILED -> failed++
            }
        }
        return ImmersionIndexBatchResult(work.size, indexed, unavailable, failed)
    }

    private suspend fun process(
        item: IndexWorkItem,
        targetVersion: Int,
        now: Long,
    ): IndexOutcome {
        val rawText = item.rawText
        if (rawText == null) {
            repository.storeIndexResult(
                sourceUnitId = item.sourceUnitId,
                tokenizerId = CHARACTER_ONLY_TOKENIZER_ID,
                tokenizerVersion = ImmersionStatsVersions.TOKENIZER,
                normalizationVersion = normalizer.version,
                indexedVersion = targetVersion,
                indexedAtEpochMillis = now,
                tokenizationConfidence = null,
                terminalReason = IndexTerminalReason.RAW_TEXT_UNAVAILABLE,
                words = emptyList(),
                characters = emptyList(),
            )
            return IndexOutcome.UNAVAILABLE
        }
        val language = item.languageTag ?: LanguageTag.from("und")
        return try {
            val normalized = normalizer.normalize(rawText, language)
            val characters = indexCharacters(normalized, item)
            val tokenizer = tokenizers.firstOrNull { it.supports(language) }
            if (tokenizer == null) {
                repository.storeIndexResult(
                    sourceUnitId = item.sourceUnitId,
                    tokenizerId = CHARACTER_ONLY_TOKENIZER_ID,
                    tokenizerVersion = ImmersionStatsVersions.TOKENIZER,
                    normalizationVersion = normalizer.version,
                    indexedVersion = targetVersion,
                    indexedAtEpochMillis = now,
                    tokenizationConfidence = null,
                    terminalReason = IndexTerminalReason.UNSUPPORTED_LANGUAGE,
                    words = emptyList(),
                    characters = characters,
                )
                IndexOutcome.UNAVAILABLE
            } else {
                val tokenization = tokenizer.tokenize(normalized)
                val words = tokenization.tokens.mapIndexedNotNull { ordinal, token ->
                    token.toIndexedWord(language, ordinal.toLong())
                        .takeUnless {
                            exclusionPolicy.excludesWord(it.id, language, item.titleId)
                        }
                }
                repository.storeIndexResult(
                    sourceUnitId = item.sourceUnitId,
                    tokenizerId = tokenizer.id,
                    tokenizerVersion = tokenizer.version,
                    normalizationVersion = normalizer.version,
                    indexedVersion = targetVersion,
                    indexedAtEpochMillis = now,
                    tokenizationConfidence = tokenization.confidence,
                    terminalReason = null,
                    words = words,
                    characters = characters,
                )
                IndexOutcome.INDEXED
            }
        } catch (_: Exception) {
            repository.markFailure(
                sourceUnitId = item.sourceUnitId,
                errorCode = TOKENIZER_FAILURE,
                nextAttemptAtEpochMillis = now + retryDelayMillis(item.attemptCount),
            )
            IndexOutcome.FAILED
        }
    }

    private suspend fun indexCharacters(
        text: NormalizedText,
        item: IndexWorkItem,
    ): List<IndexedCharacter> {
        data class MutableOccurrence(
            var count: Long,
            val firstOrdinal: Long,
        )

        val occurrences = linkedMapOf<UnicodeCodePoint, MutableOccurrence>()
        var offset = 0
        var ordinal = 0L
        while (offset < text.value.length) {
            val value = text.value.codePointAt(offset)
            val codePoint = UnicodeCodePoint(value)
            if (DefaultUnicodeCountPolicy.isCountable(codePoint)) {
                val occurrence = occurrences[codePoint]
                if (occurrence == null) {
                    occurrences[codePoint] = MutableOccurrence(1, ordinal)
                } else {
                    occurrence.count = Math.addExact(occurrence.count, 1)
                }
                ordinal++
            }
            offset += Character.charCount(value)
        }
        return occurrences.mapNotNull { (codePoint, occurrence) ->
            if (exclusionPolicy.excludesCharacter(codePoint, text.language, item.titleId)) {
                null
            } else {
                IndexedCharacter(
                    codePoint = codePoint,
                    unicodeName = runCatching { Character.getName(codePoint.value) }.getOrNull(),
                    unicodeCategory = unicodeCategory(codePoint.value),
                    unicodeScript = Character.UnicodeScript.of(codePoint.value).name,
                    occurrenceCount = NonNegativeCounter(occurrence.count),
                    firstOrdinal = occurrence.firstOrdinal,
                )
            }
        }
    }

    private fun ImmersionToken.toIndexedWord(
        language: LanguageTag,
        ordinal: Long,
    ): IndexedWord {
        val normalizedHeadword = ImmersionLexemeNormalizer.normalizeHeadword(headword, language)
        val normalizedReading = reading?.let { ImmersionLexemeNormalizer.normalizeReading(it) }.orEmpty()
        val identity = "$normalizedHeadword\u0000$normalizedReading"
        val id = ImmersionLexemeNormalizer.stableWordId(language, identity)
        return IndexedWord(
            id = id,
            languageTag = language,
            normalizedHeadword = normalizedHeadword,
            normalizedReading = normalizedReading,
            displayHeadword = displayHeadword,
            displayReading = displayReading,
            partOfSpeech = partOfSpeech,
            tokenizationConfidence = confidence,
            frequencyCorpus = frequencyCorpus,
            frequencyRank = frequencyRank,
            jlptLevel = jlptLevel,
            gradeLevel = gradeLevel,
            tokenOrdinal = ordinal,
            surfaceText = surface,
            sourceStart = startOffset,
            sourceEnd = endOffset,
            deinflectionRule = deinflectionRule,
        )
    }

    private fun retryDelayMillis(attemptCount: Int): Long {
        val shift = min(attemptCount.coerceAtLeast(0), 10)
        return min(MAX_RETRY_MILLIS, BASE_RETRY_MILLIS * (1L shl shift))
    }

    private enum class IndexOutcome {
        INDEXED,
        UNAVAILABLE,
        FAILED,
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 32
        const val CHARACTER_ONLY_TOKENIZER_ID = "characters-only"
        const val TOKENIZER_FAILURE = "TOKENIZER_FAILURE"
        private const val BASE_RETRY_MILLIS = 5_000L
        private const val MAX_RETRY_MILLIS = 3_600_000L
    }
}

data class ImmersionReindexProgress(
    val requested: Long,
    val processed: Long,
    val remaining: Long,
    val cancelled: Boolean,
)

class ImmersionReindexController(
    private val repository: ImmersionIndexRepository,
    private val engine: ImmersionIndexingEngine,
) {
    suspend fun reindex(
        request: ImmersionReindexRequest,
        targetVersion: Int = ImmersionStatsVersions.INDEX,
        batchSize: Int = ImmersionIndexingEngine.DEFAULT_BATCH_SIZE,
        isCancelled: () -> Boolean = { false },
        onProgress: (ImmersionReindexProgress) -> Unit = {},
    ): ImmersionReindexProgress {
        val requested = repository.requeue(request, targetVersion)
        var processed = 0L
        while (!isCancelled()) {
            val batch = engine.processBatch(targetVersion, batchSize)
            if (batch.claimed == 0) break
            processed += batch.claimed
            onProgress(
                ImmersionReindexProgress(
                    requested = requested,
                    processed = processed,
                    remaining = repository.pendingCount(targetVersion),
                    cancelled = false,
                ),
            )
        }
        return ImmersionReindexProgress(
            requested = requested,
            processed = processed,
            remaining = repository.pendingCount(targetVersion),
            cancelled = isCancelled(),
        )
    }
}

object ImmersionLexemeNormalizer {
    fun normalizeHeadword(
        value: String,
        language: LanguageTag,
    ): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC).trim()
        return if (language.value.substringBefore('-') in setOf("ja", "ko", "zh")) {
            normalized
        } else {
            normalized.lowercase(Locale.forLanguageTag(language.value))
        }
    }

    fun normalizeReading(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .trim()
            .map { character ->
                if (character in '\u30A1'..'\u30F6') {
                    (character.code - KATAKANA_TO_HIRAGANA_OFFSET).toChar()
                } else {
                    character
                }
            }
            .joinToString("")

    fun stableWordId(
        language: LanguageTag,
        identity: String,
    ): String =
        MessageDigest.getInstance("SHA-256")
            .digest("${language.value}\u0000$identity".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

private fun unicodeCategory(codePoint: Int): String = when (Character.getType(codePoint)) {
    Character.UPPERCASE_LETTER.toInt() -> "UPPERCASE_LETTER"
    Character.LOWERCASE_LETTER.toInt() -> "LOWERCASE_LETTER"
    Character.TITLECASE_LETTER.toInt() -> "TITLECASE_LETTER"
    Character.MODIFIER_LETTER.toInt() -> "MODIFIER_LETTER"
    Character.OTHER_LETTER.toInt() -> "OTHER_LETTER"
    Character.DECIMAL_DIGIT_NUMBER.toInt() -> "DECIMAL_DIGIT_NUMBER"
    Character.LETTER_NUMBER.toInt() -> "LETTER_NUMBER"
    Character.OTHER_NUMBER.toInt() -> "OTHER_NUMBER"
    else -> "OTHER"
}

private const val KATAKANA_TO_HIRAGANA_OFFSET = 0x60
