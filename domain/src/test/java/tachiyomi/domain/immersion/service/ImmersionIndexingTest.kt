// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.service

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.ImmersionReindexRequest
import tachiyomi.domain.immersion.model.IndexTerminalReason
import tachiyomi.domain.immersion.model.IndexWorkItem
import tachiyomi.domain.immersion.model.IndexedCharacter
import tachiyomi.domain.immersion.model.IndexedWord
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.SourceKind
import tachiyomi.domain.immersion.model.SourceUnitId
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.model.UnicodeCodePoint
import tachiyomi.domain.immersion.repository.ImmersionIndexRepository
import java.util.UUID
import kotlin.system.measureTimeMillis

class ImmersionIndexingTest {

    @Test
    fun `normalizer canonicalizes Unicode line endings and whitespace without changing the input`() {
        val input = "  か\u3099\r\n\t読む  "
        val normalized = DefaultSourceTextNormalizer().normalize(input, LanguageTag("ja"))

        input shouldBe "  か\u3099\r\n\t読む  "
        normalized.value shouldBe "が 読む"
        normalized.normalizationVersion shouldBe ImmersionStatsVersions.NORMALIZATION
    }

    @Test
    fun `Unicode inventory counts scalar values across scripts and excludes marks selectors punctuation and symbols`() = runTest {
        val repository = FakeIndexRepository(
            work = mutableListOf(
                workItem(rawText = "漢あア한A1𠮷\u0301\uFE0F。★", language = "ja"),
            ),
        )
        val engine = engine(repository, FixedTokenizer(emptyList()))

        engine.processBatch().indexed shouldBe 1

        val characters = repository.stored.single().characters.associateBy { it.codePoint.asString() }
        characters.keys shouldContainExactly setOf("漢", "あ", "ア", "한", "A", "1", "𠮷")
        characters.getValue("𠮷").occurrenceCount.value shouldBe 1
        characters.values.map { it.firstOrdinal } shouldContainExactly (0L..6L).toList()
    }

    @Test
    fun `Japanese identities are reading aware and normalize katakana readings to hiragana`() = runTest {
        val tokens = listOf(
            ImmersionToken("生", "セイ", partOfSpeech = "noun", surface = "生"),
            ImmersionToken("生", "しょう", partOfSpeech = "noun", surface = "生"),
            ImmersionToken("かな", reading = null, surface = "かな"),
            ImmersionToken("読む", "ヨム", surface = "読んだ", deinflectionRule = "past"),
        )
        val repository = FakeIndexRepository(
            work = mutableListOf(workItem(rawText = "生生かな読んだ", language = "ja")),
        )

        engine(repository, FixedTokenizer(tokens)).processBatch()

        val words = repository.stored.single().words
        words.shouldHaveSize(4)
        words[0].id shouldBe words[0].id
        (words[0].id == words[1].id) shouldBe false
        words[0].normalizedReading shouldBe "せい"
        words[2].normalizedReading shouldBe ""
        words[3].normalizedHeadword shouldBe "読む"
        words[3].normalizedReading shouldBe "よむ"
        words[3].deinflectionRule shouldBe "past"
    }

    @Test
    fun `boundary tokenizer is explicitly low confidence and skips Japanese Korean and unknown language`() = runTest {
        val tokenizer = BoundaryImmersionTokenizer()

        tokenizer.supports(LanguageTag("en")) shouldBe true
        tokenizer.supports(LanguageTag("ja")) shouldBe false
        tokenizer.supports(LanguageTag("ko")) shouldBe false
        tokenizer.supports(LanguageTag("und")) shouldBe false
        tokenizer.tokenize(
            NormalizedText("Hello, WORLD 42", LanguageTag("en"), 1),
        ).let { result ->
            result.confidence shouldBe 0.35
            result.tokens.map { it.headword } shouldContainExactly listOf("hello", "world", "42")
        }
    }

    @Test
    fun `raw text removal and unsupported language become explicit terminal states`() = runTest {
        val missingRaw = FakeIndexRepository(
            work = mutableListOf(workItem(rawText = null, language = "ja")),
        )
        engine(missingRaw, FixedTokenizer(emptyList())).processBatch().unavailable shouldBe 1
        missingRaw.stored.single().terminalReason shouldBe IndexTerminalReason.RAW_TEXT_UNAVAILABLE

        val unsupported = FakeIndexRepository(
            work = mutableListOf(workItem(rawText = "한글", language = "ko")),
        )
        engine(unsupported).processBatch().unavailable shouldBe 1
        unsupported.stored.single().let { result ->
            result.terminalReason shouldBe IndexTerminalReason.UNSUPPORTED_LANGUAGE
            result.characters.map { it.codePoint.asString() } shouldContainExactly listOf("한", "글")
        }
    }

    @Test
    fun `tokenizer failure is typed retryable and exponentially scheduled`() = runTest {
        val repository = FakeIndexRepository(
            work = mutableListOf(workItem(rawText = "読む", language = "ja", attemptCount = 2)),
        )
        val tokenizer = object : ImmersionTokenizer {
            override val id = "broken"
            override val version = 1
            override fun supports(language: LanguageTag) = true
            override suspend fun tokenize(text: NormalizedText): TokenizationResult = error("boom")
        }

        engine(repository, tokenizer, clock = { 10_000 }).processBatch().failed shouldBe 1

        repository.failures.single().let { failure ->
            failure.errorCode shouldBe ImmersionIndexingEngine.TOKENIZER_FAILURE
            failure.nextAttemptAt shouldBe 30_000
        }
    }

    @Test
    fun `exclusions apply after normalization and before persisted denominators`() = runTest {
        val excludedCharacter = UnicodeCodePoint('外'.code)
        val policy = object : ImmersionIndexExclusionPolicy {
            override suspend fun excludesWord(
                identity: String,
                languageTag: LanguageTag,
                titleId: TitleId,
            ) = true

            override suspend fun excludesCharacter(
                codePoint: UnicodeCodePoint,
                languageTag: LanguageTag,
                titleId: TitleId,
            ) = codePoint == excludedCharacter
        }
        val repository = FakeIndexRepository(
            work = mutableListOf(workItem(rawText = "外内", language = "ja")),
        )
        val engine = ImmersionIndexingEngine(
            repository = repository,
            normalizer = DefaultSourceTextNormalizer(),
            tokenizers = listOf(FixedTokenizer(listOf(ImmersionToken("外内")))),
            exclusionPolicy = policy,
            clock = { 1_000 },
        )

        engine.processBatch()

        repository.stored.single().words shouldBe emptyList()
        repository.stored.single().characters.map { it.codePoint.asString() } shouldContainExactly listOf("内")
    }

    @Test
    fun `large source indexing stays linear and bounded`() = runTest {
        val text = "読む abc 𠮷。".repeat(10_000)
        val repository = FakeIndexRepository(
            work = mutableListOf(workItem(rawText = text, language = "en")),
        )

        val elapsed = measureTimeMillis {
            engine(repository, BoundaryImmersionTokenizer()).processBatch()
        }

        elapsed shouldBeLessThan 5_000
        repository.stored.single().characters.isNotEmpty() shouldBe true
    }

    @Test
    fun `reindex is filterable observable and cancellable`() = runTest {
        val repository = FakeIndexRepository(
            work = MutableList(3) { workItem(rawText = "word", language = "en") },
            requeued = 3,
        )
        val engine = engine(repository, BoundaryImmersionTokenizer())
        val controller = ImmersionReindexController(repository, engine)
        var cancel = false
        val progress = mutableListOf<ImmersionReindexProgress>()

        val result = controller.reindex(
            request = ImmersionReindexRequest(languageTag = LanguageTag("en")),
            batchSize = 1,
            isCancelled = { cancel },
            onProgress = {
                progress += it
                cancel = true
            },
        )

        repository.lastReindexRequest shouldBe ImmersionReindexRequest(languageTag = LanguageTag("en"))
        progress.single().processed shouldBe 1
        result.cancelled shouldBe true
        result.remaining shouldBe 2
    }

    private fun engine(
        repository: FakeIndexRepository,
        vararg tokenizers: ImmersionTokenizer,
        clock: () -> Long = { 1_000 },
    ) = ImmersionIndexingEngine(
        repository = repository,
        normalizer = DefaultSourceTextNormalizer(),
        tokenizers = tokenizers.toList(),
        clock = clock,
    )

    private class FixedTokenizer(
        private val tokens: List<ImmersionToken>,
    ) : ImmersionTokenizer {
        override val id = "fixed"
        override val version = 1
        override fun supports(language: LanguageTag) = language.value == "ja"
        override suspend fun tokenize(text: NormalizedText) = TokenizationResult(tokens, 0.9)
    }

    private data class StoredResult(
        val sourceUnitId: SourceUnitId,
        val tokenizerId: String,
        val terminalReason: IndexTerminalReason?,
        val words: List<IndexedWord>,
        val characters: List<IndexedCharacter>,
    )

    private data class Failure(
        val sourceUnitId: SourceUnitId,
        val errorCode: String,
        val nextAttemptAt: Long,
    )

    private class FakeIndexRepository(
        val work: MutableList<IndexWorkItem> = mutableListOf(),
        private val requeued: Long = 0,
    ) : ImmersionIndexRepository {
        val stored = mutableListOf<StoredResult>()
        val failures = mutableListOf<Failure>()
        var lastReindexRequest: ImmersionReindexRequest? = null

        override suspend fun claimWork(
            targetVersion: Int,
            limit: Int,
            nowEpochMillis: Long,
        ): List<IndexWorkItem> =
            work.take(limit).also { claimed -> work.removeAll(claimed.toSet()) }

        override suspend fun storeIndexResult(
            sourceUnitId: SourceUnitId,
            tokenizerId: String,
            tokenizerVersion: Int,
            normalizationVersion: Int,
            indexedVersion: Int,
            indexedAtEpochMillis: Long,
            tokenizationConfidence: Double?,
            terminalReason: IndexTerminalReason?,
            words: List<IndexedWord>,
            characters: List<IndexedCharacter>,
        ) {
            stored += StoredResult(sourceUnitId, tokenizerId, terminalReason, words, characters)
        }

        override suspend fun markFailure(
            sourceUnitId: SourceUnitId,
            errorCode: String,
            nextAttemptAtEpochMillis: Long,
        ) {
            failures += Failure(sourceUnitId, errorCode, nextAttemptAtEpochMillis)
        }

        override suspend fun requeue(
            request: ImmersionReindexRequest,
            targetVersion: Int,
        ): Long {
            lastReindexRequest = request
            return requeued
        }

        override suspend fun pendingCount(targetVersion: Int): Long = work.size.toLong()
    }

    private companion object {
        fun workItem(
            rawText: String?,
            language: String?,
            attemptCount: Int = 0,
        ) = IndexWorkItem(
            sourceUnitId = SourceUnitId(UUID.randomUUID().toString()),
            titleId = TitleId(UUID.randomUUID().toString()),
            sourceKind = SourceKind.NOVEL_RANGE,
            languageTag = language?.let(LanguageTag::from),
            profileId = "profile",
            normalizedTextHash = "a".repeat(64),
            rawText = rawText,
            tokenizerVersion = 0,
            indexedVersion = 0,
            attemptCount = attemptCount,
        )
    }
}
