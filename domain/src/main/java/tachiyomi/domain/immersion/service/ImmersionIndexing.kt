package tachiyomi.domain.immersion.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tachiyomi.domain.immersion.model.ImmersionReindexRequest
import tachiyomi.domain.immersion.model.IndexTerminalReason
import tachiyomi.domain.immersion.model.IndexWorkItem
import tachiyomi.domain.immersion.model.IndexedCharacter
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
            normalized = collapseRepeatedCodePoints(normalized)
        }
        return NormalizedText(normalized, language, version)
    }

    private fun collapseRepeatedCodePoints(input: String): String {
        val collapsed = StringBuilder(input.length)
        var previousCodePoint = -1
        var repeatCount = 0
        var offset = 0
        while (offset < input.length) {
            val codePoint = input.codePointAt(offset)
            repeatCount = if (codePoint == previousCodePoint) repeatCount + 1 else 1
            previousCodePoint = codePoint
            if (repeatCount <= MAX_REPEATED_CODE_POINTS) {
                collapsed.appendCodePoint(codePoint)
            }
            offset += Character.charCount(codePoint)
        }
        return collapsed.toString()
    }

    private companion object {
        const val MAX_REPEATED_CODE_POINTS = 3
        val WHITESPACE = Regex("\\s+")
    }
}

interface ImmersionIndexExclusionPolicy {
    suspend fun excludesCharacter(
        codePoint: UnicodeCodePoint,
        languageTag: LanguageTag,
        titleId: TitleId,
    ): Boolean
}

object NoOpImmersionIndexExclusionPolicy : ImmersionIndexExclusionPolicy {
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

/**
 * Indexes source text into a per-code-point character inventory.
 *
 * Indexing is deliberately character-only: a source unit is normalized, its
 * countable code points are counted, and the result is stored. What counts as a
 * character is defined solely by [DefaultUnicodeCountPolicy.isCountable], so the
 * inventory has no dependency on any dictionary or language profile.
 */
class ImmersionIndexingEngine(
    private val repository: ImmersionIndexRepository,
    private val normalizer: SourceTextNormalizer,
    private val exclusionPolicy: ImmersionIndexExclusionPolicy = NoOpImmersionIndexExclusionPolicy,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun processBatch(
        targetVersion: Int = ImmersionStatsVersions.INDEX,
        limit: Int = DEFAULT_BATCH_SIZE,
        request: ImmersionReindexRequest = ImmersionReindexRequest(),
    ): ImmersionIndexBatchResult {
        val now = clock()
        val work = repository.claimWork(targetVersion, limit, now, request)
        var indexed = 0
        var unavailable = 0
        var failed = 0
        try {
            work.forEach { item ->
                when (process(item, targetVersion, now)) {
                    IndexOutcome.INDEXED -> indexed++
                    IndexOutcome.UNAVAILABLE -> unavailable++
                    IndexOutcome.FAILED -> failed++
                }
            }
        } catch (error: CancellationException) {
            releaseClaimsAfterCancellation(work, error)
            throw error
        }
        return ImmersionIndexBatchResult(work.size, indexed, unavailable, failed)
    }

    private suspend fun releaseClaimsAfterCancellation(
        work: List<IndexWorkItem>,
        cancellation: CancellationException,
    ) {
        try {
            withContext(NonCancellable) {
                repository.releaseClaims(work)
            }
        } catch (releaseError: Exception) {
            cancellation.addSuppressed(releaseError)
        }
    }

    private suspend fun process(
        item: IndexWorkItem,
        targetVersion: Int,
        now: Long,
    ): IndexOutcome {
        var failureCode = INDEX_STORAGE_FAILURE
        return try {
            val rawText = item.rawText
            if (rawText == null) {
                repository.storeIndexResult(
                    sourceUnitId = item.sourceUnitId,
                    claimGeneration = item.claimGeneration,
                    tokenizerId = CHARACTER_ONLY_TOKENIZER_ID,
                    normalizationVersion = normalizer.version,
                    indexedVersion = targetVersion,
                    indexedAtEpochMillis = now,
                    terminalReason = IndexTerminalReason.RAW_TEXT_UNAVAILABLE,
                    characters = emptyList(),
                )
                return IndexOutcome.UNAVAILABLE
            }
            val language = item.languageTag ?: LanguageTag.from("und")
            failureCode = NORMALIZATION_FAILURE
            val normalized = normalizer.normalize(rawText, language)
            failureCode = CHARACTER_INDEX_FAILURE
            val characters = indexCharacters(normalized, item)
            failureCode = INDEX_STORAGE_FAILURE
            repository.storeIndexResult(
                sourceUnitId = item.sourceUnitId,
                claimGeneration = item.claimGeneration,
                tokenizerId = CHARACTER_ONLY_TOKENIZER_ID,
                normalizationVersion = normalizer.version,
                indexedVersion = targetVersion,
                indexedAtEpochMillis = now,
                terminalReason = null,
                characters = characters,
            )
            IndexOutcome.INDEXED
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            repository.markFailure(
                sourceUnitId = item.sourceUnitId,
                claimGeneration = item.claimGeneration,
                errorCode = failureCode,
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
        const val NORMALIZATION_FAILURE = "NORMALIZATION_FAILURE"
        const val CHARACTER_INDEX_FAILURE = "CHARACTER_INDEX_FAILURE"
        const val INDEX_STORAGE_FAILURE = "INDEX_STORAGE_FAILURE"
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
            val batch = engine.processBatch(targetVersion, batchSize, request)
            if (batch.claimed == 0) break
            processed += batch.claimed
            onProgress(
                ImmersionReindexProgress(
                    requested = requested,
                    processed = processed,
                    remaining = repository.pendingCount(targetVersion, request),
                    cancelled = false,
                ),
            )
        }
        return ImmersionReindexProgress(
            requested = requested,
            processed = processed,
            remaining = repository.pendingCount(targetVersion, request),
            cancelled = isCancelled(),
        )
    }
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
