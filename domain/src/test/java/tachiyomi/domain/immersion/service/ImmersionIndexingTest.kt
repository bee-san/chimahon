package tachiyomi.domain.immersion.service

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.ImmersionReindexRequest
import tachiyomi.domain.immersion.model.IndexTerminalReason
import tachiyomi.domain.immersion.model.IndexWorkItem
import tachiyomi.domain.immersion.model.IndexedCharacter
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
        val input = "  が\r\n\t読む  "
        val normalized = DefaultSourceTextNormalizer().normalize(input, LanguageTag("ja"))

        input shouldBe "  が\r\n\t読む  "
        normalized.value shouldBe "が 読む"
        normalized.normalizationVersion shouldBe ImmersionStatsVersions.NORMALIZATION
    }

    @Test
    fun `Unicode inventory counts scalar values across scripts and excludes marks selectors punctuation and symbols`() = runTest {
        val repository = FakeIndexRepository(
            work = mutableListOf(
                workItem(rawText = "漢あア한A1𠮷́️。★", language = "ja"),
            ),
        )
        val engine = engine(repository)

        engine.processBatch().indexed shouldBe 1

        val characters = repository.stored.single().characters.associateBy { it.codePoint.asString() }
        characters.keys shouldContainExactly setOf("漢", "あ", "ア", "한", "A", "1", "𠮷")
        characters.getValue("𠮷").occurrenceCount.value shouldBe 1
        characters.values.map { it.firstOrdinal } shouldContainExactly (0L..6L).toList()
    }

    @Test
    fun `repeated code points accumulate an occurrence count rather than duplicate entries`() = runTest {
        val repository = FakeIndexRepository(
            work = mutableListOf(workItem(rawText = "生生生", language = "ja")),
        )

        engine(repository).processBatch().indexed shouldBe 1

        repository.stored.single().characters.single().let { character ->
            character.codePoint.asString() shouldBe "生"
            character.occurrenceCount.value shouldBe 3
            character.firstOrdinal shouldBe 0
        }
    }

    /**
     * Indexing is character-only, so language support never gates it: a language
     * with no word segmentation still yields a full character inventory.
     */
    @Test
    fun `every language indexes characters regardless of segmentation support`() = runTest {
        listOf("ja", "ko", "en", "und").forEach { language ->
            val repository = FakeIndexRepository(
                work = mutableListOf(workItem(rawText = "한글", language = language)),
            )

            engine(repository).processBatch().indexed shouldBe 1

            repository.stored.single().let { result ->
                result.terminalReason shouldBe null
                result.tokenizerId shouldBe ImmersionIndexingEngine.CHARACTER_ONLY_TOKENIZER_ID
                result.characters.map { it.codePoint.asString() } shouldContainExactly listOf("한", "글")
            }
        }
    }

    @Test
    fun `raw text removal becomes an explicit terminal state rather than an empty inventory`() = runTest {
        val missingRaw = FakeIndexRepository(
            work = mutableListOf(workItem(rawText = null, language = "ja")),
        )

        engine(missingRaw).processBatch().unavailable shouldBe 1

        missingRaw.stored.single().let { result ->
            result.terminalReason shouldBe IndexTerminalReason.RAW_TEXT_UNAVAILABLE
            result.characters shouldBe emptyList()
        }
    }

    @Test
    fun `normalization failure is typed retryable and exponentially scheduled`() = runTest {
        val repository = FakeIndexRepository(
            work = mutableListOf(workItem(rawText = "読む", language = "ja", attemptCount = 2)),
        )
        val normalizer = object : SourceTextNormalizer {
            override val version = 1

            override fun normalize(
                input: String,
                language: LanguageTag,
            ): NormalizedText = error("normalizer unavailable")
        }
        val engine = ImmersionIndexingEngine(
            repository = repository,
            normalizer = normalizer,
            clock = { 10_000 },
        )

        engine.processBatch().failed shouldBe 1

        repository.failures.single().let { failure ->
            failure.claimGeneration shouldBe 3
            failure.errorCode shouldBe ImmersionIndexingEngine.NORMALIZATION_FAILURE
            failure.nextAttemptAt shouldBe 30_000
        }
    }

    @Test
    fun `coroutine cancellation releases the claimed batch and is rethrown`() = runTest {
        val claimedWork = List(2) { workItem(rawText = "読む", language = "ja") }
        val repository = FakeIndexRepository(work = claimedWork.toMutableList())
        val normalizer = object : SourceTextNormalizer {
            override val version = 1

            override fun normalize(
                input: String,
                language: LanguageTag,
            ): NormalizedText = throw CancellationException("test cancellation")
        }
        var cancellation: CancellationException? = null

        try {
            ImmersionIndexingEngine(repository = repository, normalizer = normalizer)
                .processBatch(limit = claimedWork.size)
        } catch (error: CancellationException) {
            cancellation = error
        }

        cancellation?.message shouldBe "test cancellation"
        repository.failures shouldBe emptyList()
        repository.released.map(IndexWorkItem::sourceUnitId) shouldContainExactly
            claimedWork.map(IndexWorkItem::sourceUnitId)
    }

    @Test
    fun `exclusions apply after normalization and before persisted denominators`() = runTest {
        val excludedCharacter = UnicodeCodePoint('外'.code)
        val policy = object : ImmersionIndexExclusionPolicy {
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
            exclusionPolicy = policy,
            clock = { 1_000 },
        )

        engine.processBatch()

        repository.stored.single().characters.map { it.codePoint.asString() } shouldContainExactly listOf("内")
    }

    @Test
    fun `large source indexing stays linear and bounded`() = runTest {
        val text = "読む abc 𠮷。".repeat(10_000)
        val repository = FakeIndexRepository(
            work = mutableListOf(workItem(rawText = text, language = "en")),
        )

        val elapsed = measureTimeMillis {
            engine(repository).processBatch()
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
        val engine = engine(repository)
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
        repository.claimRequests.all { it == repository.lastReindexRequest } shouldBe true
        repository.pendingRequests.all { it == repository.lastReindexRequest } shouldBe true
        progress.single().processed shouldBe 1
        result.cancelled shouldBe true
        result.remaining shouldBe 2
    }

    private fun engine(
        repository: FakeIndexRepository,
        clock: () -> Long = { 1_000 },
    ) = ImmersionIndexingEngine(
        repository = repository,
        normalizer = DefaultSourceTextNormalizer(),
        clock = clock,
    )

    private data class StoredResult(
        val sourceUnitId: SourceUnitId,
        val claimGeneration: Int,
        val tokenizerId: String,
        val terminalReason: IndexTerminalReason?,
        val characters: List<IndexedCharacter>,
    )

    private data class Failure(
        val sourceUnitId: SourceUnitId,
        val claimGeneration: Int,
        val errorCode: String,
        val nextAttemptAt: Long,
    )

    private class FakeIndexRepository(
        val work: MutableList<IndexWorkItem> = mutableListOf(),
        private val requeued: Long = 0,
    ) : ImmersionIndexRepository {
        val stored = mutableListOf<StoredResult>()
        val failures = mutableListOf<Failure>()
        val released = mutableListOf<IndexWorkItem>()
        val claimRequests = mutableListOf<ImmersionReindexRequest>()
        val pendingRequests = mutableListOf<ImmersionReindexRequest>()
        var lastReindexRequest: ImmersionReindexRequest? = null

        override suspend fun claimWork(
            targetVersion: Int,
            limit: Int,
            nowEpochMillis: Long,
            request: ImmersionReindexRequest,
        ): List<IndexWorkItem> {
            claimRequests += request
            return work.take(limit).also { claimed -> work.removeAll(claimed.toSet()) }
        }

        override suspend fun releaseClaims(work: List<IndexWorkItem>) {
            released += work
        }

        override suspend fun storeIndexResult(
            sourceUnitId: SourceUnitId,
            claimGeneration: Int,
            tokenizerId: String,
            normalizationVersion: Int,
            indexedVersion: Int,
            indexedAtEpochMillis: Long,
            terminalReason: IndexTerminalReason?,
            characters: List<IndexedCharacter>,
        ) {
            stored += StoredResult(
                sourceUnitId,
                claimGeneration,
                tokenizerId,
                terminalReason,
                characters,
            )
        }

        override suspend fun markFailure(
            sourceUnitId: SourceUnitId,
            claimGeneration: Int,
            errorCode: String,
            nextAttemptAtEpochMillis: Long,
        ) {
            failures += Failure(sourceUnitId, claimGeneration, errorCode, nextAttemptAtEpochMillis)
        }

        override suspend fun requeue(
            request: ImmersionReindexRequest,
            targetVersion: Int,
        ): Long {
            lastReindexRequest = request
            return requeued
        }

        override suspend fun pendingCount(
            targetVersion: Int,
            request: ImmersionReindexRequest,
        ): Long {
            pendingRequests += request
            return work.size.toLong()
        }
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
            indexedVersion = 0,
            attemptCount = attemptCount,
            claimGeneration = attemptCount + 1,
        )
    }
}
