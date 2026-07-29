package tachiyomi.domain.immersion.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tachiyomi.domain.immersion.model.AnkiInventoryFailure
import tachiyomi.domain.immersion.model.AnkiMaturityAggregation
import tachiyomi.domain.immersion.model.AnkiSnapshotStatus
import tachiyomi.domain.immersion.model.CapabilityState
import tachiyomi.domain.immersion.model.ImmersionAnkiItem
import tachiyomi.domain.immersion.model.ImmersionAnkiSnapshot
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.MaturityTier
import tachiyomi.domain.immersion.model.UnicodeCodePoint
import tachiyomi.domain.immersion.repository.ImmersionAnkiRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

data class AnkiInventoryConfiguration(
    val profileId: String,
    val enabled: Boolean,
    val deckName: String,
    val noteTypeName: String,
    val languageTag: LanguageTag,
    val expressionField: String,
    val readingField: String?,
    val characterField: String?,
    val matureIntervalDays: Int = DEFAULT_MATURE_INTERVAL_DAYS,
    val maturityAggregation: AnkiMaturityAggregation = AnkiMaturityAggregation.MAX_INTERVAL,
) {
    init {
        require(profileId.isNotBlank())
        require(deckName.isNotBlank())
        require(noteTypeName.isNotBlank())
        require(matureIntervalDays > 0)
    }

    val mappingHash: String
        get() = sha256(
            listOf(
                deckName,
                noteTypeName,
                languageTag.value,
                expressionField,
                readingField.orEmpty(),
                characterField.orEmpty(),
                matureIntervalDays.toString(),
                maturityAggregation.name,
            ).joinToString("\u0000"),
        )
}

data class AnkiProviderCapability(
    val state: CapabilityState,
    val packageVersion: String?,
    val noteModificationTime: Boolean,
    val cardModificationTime: Boolean,
    val reviewHistory: Boolean,
    val failure: AnkiInventoryFailure? = null,
)

data class AnkiProviderNote(
    val id: Long,
    val noteTypeId: Long,
    val modifiedAtEpochSeconds: Long?,
    val fields: Map<String, String>,
)

data class AnkiProviderCard(
    val id: Long,
    val noteId: Long,
    val deckId: Long,
    val type: Int?,
    val queue: Int?,
    val intervalDays: Int?,
    val due: Long?,
    val repetitions: Int?,
    val lapses: Int?,
    val ease: Int?,
)

data class AnkiProviderQueryMetrics(
    val noteQueryMillis: Long,
    val cardQueryMillis: Long,
)

data class AnkiProviderInventory(
    val capability: AnkiProviderCapability,
    val notes: List<AnkiProviderNote>,
    val cards: List<AnkiProviderCard>,
    val metrics: AnkiProviderQueryMetrics,
    val isComplete: Boolean = true,
)

class AnkiInventoryProviderException(
    val failure: AnkiInventoryFailure,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

interface AnkiInventoryProvider {
    suspend fun probe(enabled: Boolean): AnkiProviderCapability

    suspend fun load(configuration: AnkiInventoryConfiguration): AnkiProviderInventory
}

data class AnkiInventorySyncResult(
    val status: AnkiSnapshotStatus,
    val snapshotId: String,
    val itemCount: Int,
    val noteCount: Int,
    val retainedPreviousSnapshot: Boolean,
    val failure: AnkiInventoryFailure? = null,
)

class AnkiInventorySynchronizer(
    private val repository: ImmersionAnkiRepository,
    private val provider: AnkiInventoryProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun refresh(configuration: AnkiInventoryConfiguration): AnkiInventorySyncResult {
        val requestedAt = clock()
        val snapshotId = UUID.randomUUID().toString()
        val previous = repository.getCurrentSnapshot(configuration.profileId)
        val capability = provider.probe(configuration.enabled)
        if (capability.state != CapabilityState.AVAILABLE) {
            val failure = capability.failure ?: AnkiInventoryFailure.PROVIDER_ERROR
            repository.recordSnapshotAttempt(
                snapshot(
                    id = snapshotId,
                    configuration = configuration,
                    requestedAt = requestedAt,
                    capability = capability,
                    status = AnkiSnapshotStatus.UNAVAILABLE,
                    failure = failure,
                ),
            )
            return AnkiInventorySyncResult(
                status = AnkiSnapshotStatus.UNAVAILABLE,
                snapshotId = snapshotId,
                itemCount = 0,
                noteCount = 0,
                retainedPreviousSnapshot = previous != null,
                failure = failure,
            )
        }
        if (configuration.expressionField.isBlank()) {
            return recordFailure(
                snapshotId,
                configuration,
                requestedAt,
                capability,
                previous != null,
                AnkiInventoryFailure.MISCONFIGURED_FIELDS,
            )
        }

        return try {
            val inventory = provider.load(configuration)
            if (!inventory.isComplete) {
                return recordFailure(
                    snapshotId,
                    configuration,
                    requestedAt,
                    inventory.capability,
                    previous != null,
                    AnkiInventoryFailure.PARTIAL_RESULT,
                )
            }
            val items = buildItems(
                snapshotId = snapshotId,
                configuration = configuration,
                inventory = inventory,
                completedAt = clock(),
                previousItems = repository.getCurrentItems(configuration.profileId),
            )
            val completedAt = clock()
            val completedSnapshot = snapshot(
                id = snapshotId,
                configuration = configuration,
                requestedAt = requestedAt,
                capability = inventory.capability,
                status = AnkiSnapshotStatus.COMPLETE,
                completedAt = completedAt,
                itemCount = items.size,
                noteCount = items.asSequence().map { it.noteId }.distinct().count(),
                queryDurationMillis = Math.addExact(
                    inventory.metrics.noteQueryMillis,
                    inventory.metrics.cardQueryMillis,
                ),
            )
            repository.activateSnapshot(completedSnapshot, items)
            AnkiInventorySyncResult(
                status = AnkiSnapshotStatus.COMPLETE,
                snapshotId = snapshotId,
                itemCount = items.size,
                noteCount = completedSnapshot.noteCount,
                retainedPreviousSnapshot = false,
            )
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                repository.recordSnapshotAttempt(
                    snapshot(
                        id = snapshotId,
                        configuration = configuration,
                        requestedAt = requestedAt,
                        capability = capability,
                        status = AnkiSnapshotStatus.CANCELLED,
                        completedAt = clock(),
                    ),
                )
            }
            throw error
        } catch (error: AnkiInventoryProviderException) {
            recordFailure(
                snapshotId,
                configuration,
                requestedAt,
                capability,
                previous != null,
                error.failure,
            )
        } catch (_: Exception) {
            recordFailure(
                snapshotId,
                configuration,
                requestedAt,
                capability,
                previous != null,
                AnkiInventoryFailure.PROVIDER_ERROR,
            )
        }
    }

    private suspend fun recordFailure(
        snapshotId: String,
        configuration: AnkiInventoryConfiguration,
        requestedAt: Long,
        capability: AnkiProviderCapability,
        retainedPrevious: Boolean,
        failure: AnkiInventoryFailure,
    ): AnkiInventorySyncResult {
        repository.recordSnapshotAttempt(
            snapshot(
                id = snapshotId,
                configuration = configuration,
                requestedAt = requestedAt,
                capability = capability,
                status = AnkiSnapshotStatus.FAILED,
                completedAt = clock(),
                failure = failure,
            ),
        )
        return AnkiInventorySyncResult(
            status = AnkiSnapshotStatus.FAILED,
            snapshotId = snapshotId,
            itemCount = 0,
            noteCount = 0,
            retainedPreviousSnapshot = retainedPrevious,
            failure = failure,
        )
    }

    private fun snapshot(
        id: String,
        configuration: AnkiInventoryConfiguration,
        requestedAt: Long,
        capability: AnkiProviderCapability,
        status: AnkiSnapshotStatus,
        completedAt: Long? = null,
        failure: AnkiInventoryFailure? = null,
        itemCount: Int = 0,
        noteCount: Int = 0,
        queryDurationMillis: Long? = null,
    ) = ImmersionAnkiSnapshot(
        id = id,
        profileId = configuration.profileId,
        deckScope = configuration.deckName,
        requestedAtEpochMillis = requestedAt,
        completedAtEpochMillis = completedAt,
        capabilityVersion = ImmersionStatsVersions.ANKI_CAPABILITY,
        capabilityState = capability.state,
        providerVersion = capability.packageVersion,
        supportsNoteModificationTime = capability.noteModificationTime,
        supportsCardModificationTime = capability.cardModificationTime,
        supportsReviewHistory = capability.reviewHistory,
        status = status,
        errorCode = failure,
        itemCount = itemCount,
        noteCount = noteCount,
        matureIntervalDays = configuration.matureIntervalDays,
        mappingHash = configuration.mappingHash,
        queryDurationMillis = queryDurationMillis,
        isComplete = status == AnkiSnapshotStatus.COMPLETE,
        isPartial = failure == AnkiInventoryFailure.PARTIAL_RESULT,
        isCurrent = status == AnkiSnapshotStatus.COMPLETE,
        isStale = false,
    )

    private fun buildItems(
        snapshotId: String,
        configuration: AnkiInventoryConfiguration,
        inventory: AnkiProviderInventory,
        completedAt: Long,
        previousItems: List<ImmersionAnkiItem>,
    ): List<ImmersionAnkiItem> {
        val cardsByNote = inventory.cards.groupBy(AnkiProviderCard::noteId)
        val previousMaturity = previousItems.associate { it.cardId to it.firstMatureAtEpochMillis }
        val candidates = inventory.notes.mapNotNull { note ->
            val expression = note.fields[configuration.expressionField]?.stripAnkiMarkup()?.trim().orEmpty()
            if (expression.isBlank()) return@mapNotNull null
            val characters = configuration.characterField
                ?.let(note.fields::get)
                ?.takeIf(String::isNotBlank)
                ?: expression
            Candidate(
                note = note,
                characters = countableCodePoints(characters),
                cards = cardsByNote[note.id].orEmpty(),
            )
        }
        return candidates.flatMap { candidate ->
            candidate.cards.map { card ->
                val maturity = cardMaturity(card, configuration.matureIntervalDays)
                ImmersionAnkiItem(
                    snapshotId = snapshotId,
                    noteId = candidate.note.id,
                    cardId = card.id,
                    noteTypeId = candidate.note.noteTypeId,
                    deckId = card.deckId,
                    languageTag = configuration.languageTag,
                    characters = candidate.characters,
                    cardType = card.type,
                    queue = card.queue,
                    intervalDays = card.intervalDays,
                    due = card.due,
                    repetitions = card.repetitions,
                    lapses = card.lapses,
                    ease = card.ease,
                    noteModifiedAtEpochSeconds = candidate.note.modifiedAtEpochSeconds,
                    maturityTier = maturity,
                    firstMatureAtEpochMillis = when {
                        maturity != MaturityTier.MATURE -> previousMaturity[card.id]
                        previousMaturity[card.id] != null -> previousMaturity[card.id]
                        else -> completedAt
                    },
                )
            }
        }
    }

    private data class Candidate(
        val note: AnkiProviderNote,
        val characters: Set<UnicodeCodePoint>,
        val cards: List<AnkiProviderCard>,
    )
}

data class AnkiKnownness(
    val tier: MaturityTier,
    val snapshotCompletedAtEpochMillis: Long?,
)

data class AnkiCoverage(
    val encountered: Long,
    val covered: Long,
) {
    init {
        require(encountered >= 0)
        require(covered >= 0)
        require(covered <= encountered)
    }
}

class AnkiKnownnessResolver(
    private val repository: ImmersionAnkiRepository,
) {
    suspend fun character(
        enabled: Boolean,
        profileId: String,
        codePoint: UnicodeCodePoint,
        aggregation: AnkiMaturityAggregation = AnkiMaturityAggregation.MAX_INTERVAL,
    ): AnkiKnownness {
        if (!enabled) return AnkiKnownness(MaturityTier.UNAVAILABLE, null)
        val snapshot = repository.getCurrentSnapshot(profileId)
            ?: return AnkiKnownness(MaturityTier.UNKNOWN, null)
        val items = repository.findCharacterItems(profileId, codePoint)
        val tier = if (items.isEmpty()) {
            if (snapshot.isStale) MaturityTier.STALE else MaturityTier.UNKNOWN
        } else {
            aggregateMaturity(items.map(ImmersionAnkiItem::maturityTier), aggregation)
        }
        return AnkiKnownness(tier, snapshot.completedAtEpochMillis)
    }
}

fun cardMaturity(
    card: AnkiProviderCard,
    matureIntervalDays: Int,
): MaturityTier = when {
    card.type == 0 || card.queue == 0 -> MaturityTier.NEW
    card.type == 1 || card.type == 3 || card.queue == 1 || card.queue == 3 -> MaturityTier.LEARNING
    card.type == 2 && (card.intervalDays ?: 0) >= matureIntervalDays -> MaturityTier.MATURE
    card.type == 2 -> MaturityTier.YOUNG
    else -> MaturityTier.UNKNOWN
}

fun aggregateMaturity(
    tiers: List<MaturityTier>,
    aggregation: AnkiMaturityAggregation,
): MaturityTier {
    if (tiers.isEmpty()) return MaturityTier.UNKNOWN
    val rank = mapOf(
        MaturityTier.UNAVAILABLE to -2,
        MaturityTier.STALE to -1,
        MaturityTier.UNKNOWN to 0,
        MaturityTier.NEW to 1,
        MaturityTier.LEARNING to 2,
        MaturityTier.YOUNG to 3,
        MaturityTier.MATURE to 4,
    )
    return when (aggregation) {
        AnkiMaturityAggregation.MAX_INTERVAL -> tiers.maxBy { rank.getValue(it) }
        AnkiMaturityAggregation.MIN_INTERVAL -> tiers.minBy { rank.getValue(it) }
    }
}

private fun countableCodePoints(value: String): Set<UnicodeCodePoint> {
    val result = linkedSetOf<UnicodeCodePoint>()
    var offset = 0
    while (offset < value.length) {
        val raw = value.codePointAt(offset)
        val codePoint = UnicodeCodePoint(raw)
        if (DefaultUnicodeCountPolicy.isCountable(codePoint)) {
            result += codePoint
        }
        offset += Character.charCount(raw)
    }
    return result
}

private fun String.stripAnkiMarkup(): String =
    replace(Regex("""<[^>]+>"""), "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

const val DEFAULT_MATURE_INTERVAL_DAYS = 21
