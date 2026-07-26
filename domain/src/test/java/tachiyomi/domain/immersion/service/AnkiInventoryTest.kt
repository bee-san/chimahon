// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.service

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.AnkiInventoryFailure
import tachiyomi.domain.immersion.model.AnkiMatchConfidence
import tachiyomi.domain.immersion.model.AnkiMaturityAggregation
import tachiyomi.domain.immersion.model.AnkiSnapshotStatus
import tachiyomi.domain.immersion.model.CapabilityState
import tachiyomi.domain.immersion.model.ImmersionAnkiItem
import tachiyomi.domain.immersion.model.ImmersionAnkiSnapshot
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.MaturityTier
import tachiyomi.domain.immersion.model.UnicodeCodePoint
import tachiyomi.domain.immersion.repository.ImmersionAnkiRepository

class AnkiInventoryTest {

    @Test
    fun `card maturity covers new learning young mature and unknown`() {
        cardMaturity(card(type = 0), 21) shouldBe MaturityTier.NEW
        cardMaturity(card(type = 1), 21) shouldBe MaturityTier.LEARNING
        cardMaturity(card(type = 3), 21) shouldBe MaturityTier.LEARNING
        cardMaturity(card(type = 2, interval = 20), 21) shouldBe MaturityTier.YOUNG
        cardMaturity(card(type = 2, interval = 21), 21) shouldBe MaturityTier.MATURE
        cardMaturity(card(type = 99), 21) shouldBe MaturityTier.UNKNOWN
    }

    @Test
    fun `multi-card notes support highest and strict lowest aggregation`() {
        val tiers = listOf(MaturityTier.NEW, MaturityTier.MATURE)
        aggregateMaturity(tiers, AnkiMaturityAggregation.MAX_INTERVAL) shouldBe MaturityTier.MATURE
        aggregateMaturity(tiers, AnkiMaturityAggregation.MIN_INTERVAL) shouldBe MaturityTier.NEW
    }

    @Test
    fun `successful refresh atomically maps reading-aware words characters and mixed cards`() = runTest {
        val repository = FakeAnkiRepository()
        val provider = FakeProvider(
            inventory = inventory(
                notes = listOf(
                    note(1, "生", "せい"),
                    note(2, "生", "なま"),
                    note(3, "生", "せい"),
                ),
                cards = listOf(
                    card(id = 11, noteId = 1, type = 0),
                    card(id = 12, noteId = 1, type = 2, interval = 30),
                    card(id = 21, noteId = 2, type = 2, interval = 10),
                    card(id = 31, noteId = 3, type = 2, interval = 30),
                ),
            ),
        )
        val synchronizer = AnkiInventorySynchronizer(repository, provider) { 10_000 }

        val result = synchronizer.refresh(configuration())

        result.status shouldBe AnkiSnapshotStatus.COMPLETE
        result.itemCount shouldBe 4
        repository.current?.isCurrent shouldBe true
        repository.items shouldHaveSize 4
        repository.items.first().characters shouldBe setOf(UnicodeCodePoint('生'.code))

        val resolver = AnkiKnownnessResolver(repository)
        resolver.word(true, PROFILE, JA, "生", "セイ").tier shouldBe MaturityTier.MATURE
        resolver.word(
            enabled = true,
            profileId = PROFILE,
            languageTag = JA,
            headword = "生",
            reading = "せい",
            aggregation = AnkiMaturityAggregation.MIN_INTERVAL,
        ).tier shouldBe MaturityTier.NEW
        resolver.word(true, PROFILE, JA, "生", "なま").tier shouldBe MaturityTier.YOUNG
    }

    @Test
    fun `headword-only homographs are confidence labeled ambiguous`() = runTest {
        val repository = FakeAnkiRepository()
        val provider = FakeProvider(
            inventory = inventory(
                notes = listOf(
                    note(1, "生", "せい"),
                    note(2, "生", "なま"),
                    note(3, "生", ""),
                ),
                cards = listOf(card(11, 1, type = 2), card(21, 2, type = 2), card(31, 3, type = 2)),
            ),
        )
        AnkiInventorySynchronizer(repository, provider) { 10_000 }.refresh(configuration())

        repository.items.single { it.noteId == 3L }.let {
            it.matchConfidence shouldBe AnkiMatchConfidence.AMBIGUOUS
            it.ambiguityCount shouldBe 2
        }
    }

    @Test
    fun `knownness prefers exact reading and never accepts another reading or ambiguity`() = runTest {
        val repository = FakeAnkiRepository()
        val provider = FakeProvider(
            inventory = inventory(
                notes = listOf(
                    note(1, "今日", "きょう"),
                    note(2, "今日", ""),
                    note(3, "生", "なま"),
                    note(4, "上", "うえ"),
                    note(5, "上", "じょう"),
                    note(6, "上", ""),
                ),
                cards = listOf(
                    card(11, 1, type = 0),
                    card(21, 2, type = 2, interval = 30),
                    card(31, 3, type = 2, interval = 30),
                    card(41, 4, type = 2, interval = 30),
                    card(51, 5, type = 2, interval = 30),
                    card(61, 6, type = 2, interval = 30),
                ),
            ),
        )
        AnkiInventorySynchronizer(repository, provider) { 10_000 }.refresh(configuration())
        val resolver = AnkiKnownnessResolver(repository)

        resolver.word(true, PROFILE, JA, "今日", "きょう").let {
            it.tier shouldBe MaturityTier.NEW
            it.matchConfidence shouldBe AnkiMatchConfidence.READING_AWARE
        }
        resolver.word(true, PROFILE, JA, "生", "せい").tier shouldBe MaturityTier.UNKNOWN
        resolver.word(true, PROFILE, JA, "上", "かみ").tier shouldBe MaturityTier.UNKNOWN
    }

    @Test
    fun `partial provider failure preserves and stales the last valid snapshot`() = runTest {
        val repository = FakeAnkiRepository().apply {
            current = snapshot("old", isCurrent = true)
        }
        val provider = FakeProvider(
            inventory = inventory(emptyList(), emptyList()).copy(isComplete = false),
        )

        val result = AnkiInventorySynchronizer(repository, provider) { 20_000 }
            .refresh(configuration())

        result.status shouldBe AnkiSnapshotStatus.FAILED
        result.failure shouldBe AnkiInventoryFailure.PARTIAL_RESULT
        result.retainedPreviousSnapshot shouldBe true
        repository.current?.id shouldBe "old"
        repository.current?.isStale shouldBe true
        repository.items shouldBe emptyList()
    }

    @Test
    fun `missing expression mapping fails before provider inventory query`() = runTest {
        val repository = FakeAnkiRepository()
        val provider = FakeProvider(inventory(emptyList(), emptyList()))

        val result = AnkiInventorySynchronizer(repository, provider) { 20_000 }
            .refresh(configuration().copy(expressionField = ""))

        result.failure shouldBe AnkiInventoryFailure.MISCONFIGURED_FIELDS
        provider.loadCalls shouldBe 0
        repository.current shouldBe null
    }

    @Test
    fun `disabled and unavailable integration never masquerade as unknown knowledge`() = runTest {
        val repository = FakeAnkiRepository()
        val provider = FakeProvider(
            inventory = inventory(emptyList(), emptyList()),
            capability = capability(
                state = CapabilityState.UNAVAILABLE,
                failure = AnkiInventoryFailure.DISABLED,
            ),
        )
        val result = AnkiInventorySynchronizer(repository, provider) { 20_000 }
            .refresh(configuration().copy(enabled = false))

        result.status shouldBe AnkiSnapshotStatus.UNAVAILABLE
        AnkiKnownnessResolver(repository).word(false, PROFILE, JA, "猫", "ねこ").tier shouldBe
            MaturityTier.UNAVAILABLE
    }

    @Test
    fun `large snapshot remains a single provider load and one activation`() = runTest {
        val repository = FakeAnkiRepository()
        val cards = (1L..10_000L).map { card(it, it, type = 2, interval = 30) }
        val notes = (1L..10_000L).map { note(it, "語$it", "ご") }
        val provider = FakeProvider(inventory(notes, cards))

        val result = AnkiInventorySynchronizer(repository, provider) { 30_000 }
            .refresh(configuration())

        result.itemCount shouldBe 10_000
        provider.loadCalls shouldBe 1
        repository.activations shouldBe 1
    }

    private class FakeProvider(
        private val inventory: AnkiProviderInventory,
        private val capability: AnkiProviderCapability = capability(),
    ) : AnkiInventoryProvider {
        var loadCalls = 0

        override suspend fun probe(enabled: Boolean): AnkiProviderCapability = capability

        override suspend fun load(configuration: AnkiInventoryConfiguration): AnkiProviderInventory {
            loadCalls++
            return inventory.copy(capability = capability)
        }
    }

    private class FakeAnkiRepository : ImmersionAnkiRepository {
        var current: ImmersionAnkiSnapshot? = null
        var latest: ImmersionAnkiSnapshot? = null
        var items = emptyList<ImmersionAnkiItem>()
        var activations = 0
        private val latestFlow = MutableStateFlow<ImmersionAnkiSnapshot?>(null)

        override suspend fun activateSnapshot(
            snapshot: ImmersionAnkiSnapshot,
            items: List<ImmersionAnkiItem>,
        ) {
            activations++
            current = snapshot
            latest = snapshot
            this.items = items
            latestFlow.value = snapshot
        }

        override suspend fun recordSnapshotAttempt(snapshot: ImmersionAnkiSnapshot) {
            latest = snapshot
            current = current?.copy(isStale = true)
            latestFlow.value = snapshot
        }

        override suspend fun getCurrentSnapshot(profileId: String): ImmersionAnkiSnapshot? = current

        override suspend fun getLatestSnapshot(profileId: String): ImmersionAnkiSnapshot? = latest

        override fun observeLatestSnapshot(profileId: String): Flow<ImmersionAnkiSnapshot?> = latestFlow

        override suspend fun getCurrentItems(profileId: String): List<ImmersionAnkiItem> = items

        override suspend fun findWordItems(
            profileId: String,
            languageTag: LanguageTag,
            normalizedWord: String,
            normalizedReading: String,
        ): List<ImmersionAnkiItem> {
            val candidates = items.filter {
                it.languageTag == languageTag && it.normalizedWord == normalizedWord
            }
            val readingAware = candidates.filter {
                it.matchConfidence == AnkiMatchConfidence.READING_AWARE &&
                    it.normalizedReading == normalizedReading
            }
            return readingAware.ifEmpty {
                candidates.filter {
                    it.matchConfidence == AnkiMatchConfidence.HEADWORD_ONLY &&
                        it.normalizedReading.isBlank()
                }
            }
        }

        override suspend fun findCharacterItems(
            profileId: String,
            codePoint: UnicodeCodePoint,
        ): List<ImmersionAnkiItem> = items.filter { codePoint in it.characters }

        override suspend fun getWordCoverage(
            profileId: String,
            languageTag: LanguageTag,
        ) = AnkiCoverage(0, 0, 0)

        override suspend fun getCharacterCoverage(
            profileId: String,
            languageTag: LanguageTag,
        ) = AnkiCoverage(0, 0, 0)

        override suspend fun recomputeCurrentMaturity(
            profileId: String,
            matureIntervalDays: Int,
            recomputedAtEpochMillis: Long,
        ) {
            items = items.map {
                it.copy(
                    maturityTier = cardMaturity(
                        card(
                            id = it.cardId,
                            noteId = it.noteId,
                            type = it.cardType,
                            interval = it.intervalDays,
                        ),
                        matureIntervalDays,
                    ),
                )
            }
        }

        override suspend fun clearSnapshots(profileId: String): Long {
            val deleted = if (latest == null) 0 else 1L
            current = null
            latest = null
            items = emptyList()
            latestFlow.value = null
            return deleted
        }
    }

    companion object {
        private const val PROFILE = "profile"
        private val JA = LanguageTag("ja")

        private fun configuration() = AnkiInventoryConfiguration(
            profileId = PROFILE,
            enabled = true,
            deckName = "Mining",
            noteTypeName = "Lapis",
            languageTag = JA,
            expressionField = "Expression",
            readingField = "Reading",
            characterField = null,
        )

        private fun capability(
            state: CapabilityState = CapabilityState.AVAILABLE,
            failure: AnkiInventoryFailure? = null,
        ) = AnkiProviderCapability(
            state = state,
            packageVersion = "2.24",
            noteModificationTime = true,
            cardModificationTime = false,
            reviewHistory = false,
            failure = failure,
        )

        private fun inventory(
            notes: List<AnkiProviderNote>,
            cards: List<AnkiProviderCard>,
        ) = AnkiProviderInventory(
            capability = capability(),
            notes = notes,
            cards = cards,
            metrics = AnkiProviderQueryMetrics(1, 2),
        )

        private fun note(
            id: Long,
            expression: String,
            reading: String,
        ) = AnkiProviderNote(
            id = id,
            noteTypeId = 1,
            modifiedAtEpochSeconds = 1_000,
            fields = mapOf("Expression" to expression, "Reading" to reading),
        )

        private fun card(
            id: Long = 1,
            noteId: Long = 1,
            type: Int? = 2,
            interval: Int? = 0,
        ) = AnkiProviderCard(
            id = id,
            noteId = noteId,
            deckId = 1,
            type = type,
            queue = type,
            intervalDays = interval,
            due = 0,
            repetitions = 0,
            lapses = 0,
            ease = 2_500,
        )

        private fun snapshot(
            id: String,
            isCurrent: Boolean,
        ) = ImmersionAnkiSnapshot(
            id = id,
            profileId = PROFILE,
            deckScope = "Mining",
            requestedAtEpochMillis = 1_000,
            completedAtEpochMillis = 2_000,
            capabilityVersion = 1,
            capabilityState = CapabilityState.AVAILABLE,
            providerVersion = "2.24",
            supportsNoteModificationTime = true,
            supportsCardModificationTime = false,
            supportsReviewHistory = false,
            status = AnkiSnapshotStatus.COMPLETE,
            errorCode = null,
            itemCount = 0,
            noteCount = 0,
            matureIntervalDays = 21,
            mappingHash = "hash",
            queryDurationMillis = 1,
            isComplete = true,
            isPartial = false,
            isCurrent = isCurrent,
            isStale = false,
        )
    }
}
