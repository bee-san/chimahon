// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.immersion.model.AnkiOperationId
import tachiyomi.domain.immersion.model.AnkiOperationStatus
import tachiyomi.domain.immersion.model.AnkiOperationType
import tachiyomi.domain.immersion.model.LookupStatus
import tachiyomi.domain.immersion.model.RawTextRetention
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SourceUnitId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Immutable session/source provenance captured at the user interaction boundary.
 */
@Serializable
data class InteractionProvenance(
    val sessionId: SessionId,
    val sourceUnitId: SourceUnitId? = null,
)

data class LookupIntentToken(
    val id: String,
    val sessionId: SessionId?,
    val sourceUnitId: SourceUnitId?,
    internal val queryHash: String,
    internal val rawQuery: String?,
    internal val accepted: Boolean,
)

interface LookupTelemetry {
    fun begin(
        intentId: String,
        query: String,
        provenance: InteractionProvenance? = null,
        allowAmbientFallback: Boolean = true,
    ): LookupIntentToken

    fun complete(
        token: LookupIntentToken,
        status: LookupStatus,
        normalizedHeadword: String? = null,
        normalizedReading: String? = null,
        partOfSpeech: String? = null,
        dictionaryId: String? = null,
        resultId: String? = null,
    ): RecordResult
}

class DefaultLookupTelemetry(
    private val recorder: ImmersionRecorder,
    private val rawTextRetention: () -> RawTextRetention,
) : LookupTelemetry {
    private val intents = ConcurrentHashMap<String, PendingLookup>()

    override fun begin(
        intentId: String,
        query: String,
        provenance: InteractionProvenance?,
        allowAmbientFallback: Boolean,
    ): LookupIntentToken {
        require(intentId.isNotBlank()) { "Lookup intent ID cannot be blank" }
        require(query.isNotBlank()) { "Lookup query cannot be blank" }
        val existing = intents[intentId]
        if (existing != null) return existing.token
        val snapshot = recorder.state.value
        val resolvedProvenance = snapshot.resolveInteractionProvenance(
            explicit = provenance,
            allowAmbientFallback = allowAmbientFallback,
        )
        val accepted = resolvedProvenance != null
        val normalized = normalizeInteractionText(query)
        val token = LookupIntentToken(
            id = intentId,
            sessionId = resolvedProvenance?.sessionId,
            sourceUnitId = resolvedProvenance?.sourceUnitId,
            queryHash = sha256(normalized),
            rawQuery = normalized.takeIf {
                accepted && rawTextRetention() != RawTextRetention.NEVER
            },
            accepted = accepted,
        )
        return intents.putIfAbsent(intentId, PendingLookup(token))?.token ?: token
    }

    override fun complete(
        token: LookupIntentToken,
        status: LookupStatus,
        normalizedHeadword: String?,
        normalizedReading: String?,
        partOfSpeech: String?,
        dictionaryId: String?,
        resultId: String?,
    ): RecordResult {
        val pending = intents[token.id] ?: return RecordResult.Enqueued(0)
        if (!pending.completed.compareAndSet(false, true)) return RecordResult.Enqueued(0)
        intents.remove(token.id, pending)
        val canonicalToken = pending.token
        val sessionId = canonicalToken.sessionId
            ?: return RecordResult.Suppressed(CaptureSuppressionReason.NO_ACTIVE_SESSION)
        return recorder.record(
            SessionHandle(sessionId),
            CaptureCommand.Lookup(
                lookupId = canonicalToken.id,
                sourceUnitId = canonicalToken.sourceUnitId,
                queryHash = canonicalToken.queryHash,
                rawQuery = canonicalToken.rawQuery,
                normalizedHeadword = normalizedHeadword?.let(::normalizeInteractionText),
                normalizedReading = normalizedReading?.let(::normalizeInteractionText),
                partOfSpeech = partOfSpeech?.trim()?.takeIf(String::isNotBlank),
                dictionaryId = dictionaryId?.trim()?.takeIf(String::isNotBlank),
                resultId = resultId?.trim()?.takeIf(String::isNotBlank),
                status = status,
            ),
        )
    }

    private data class PendingLookup(
        val token: LookupIntentToken,
        val completed: AtomicBoolean = AtomicBoolean(false),
    )
}

@Serializable
data class AnkiOperationToken(
    val operationId: AnkiOperationId,
    val sessionId: SessionId?,
    val sourceUnitId: SourceUnitId?,
    val expressionHash: String,
    val normalizedExpression: String,
    val normalizedReading: String?,
    val occurredAtEpochMillis: Long = 0,
    val timezoneOffsetSeconds: Int = 0,
)

@Serializable
data class PendingAnkiOperation(
    val token: AnkiOperationToken,
    val operationType: AnkiOperationType,
    val status: AnkiOperationStatus,
    val noteId: Long?,
    val errorCode: String?,
)

interface AnkiOperationRepairStore {
    fun put(operation: PendingAnkiOperation)

    fun remove(operationId: AnkiOperationId)

    fun removeForSession(sessionId: SessionId): Int

    fun clear()

    fun all(): List<PendingAnkiOperation>
}

fun interface AnkiOperationRepairWriter {
    suspend fun write(operation: PendingAnkiOperation): Boolean
}

object NoOpAnkiOperationRepairWriter : AnkiOperationRepairWriter {
    override suspend fun write(operation: PendingAnkiOperation): Boolean = false
}

object NoOpAnkiOperationRepairStore : AnkiOperationRepairStore {
    override fun put(operation: PendingAnkiOperation) = Unit

    override fun remove(operationId: AnkiOperationId) = Unit

    override fun removeForSession(sessionId: SessionId): Int = 0

    override fun clear() = Unit

    override fun all(): List<PendingAnkiOperation> = emptyList()
}

class PreferenceAnkiOperationRepairStore(
    preferenceStore: PreferenceStore,
) : AnkiOperationRepairStore {
    private val preference = preferenceStore.getString(PREFERENCE_KEY, "")
    private val lock = Any()

    override fun put(operation: PendingAnkiOperation) {
        synchronized(lock) {
            val pending = read().associateByTo(linkedMapOf()) { it.token.operationId }
            pending[operation.token.operationId] = operation
            write(pending.values.toList())
        }
    }

    override fun remove(operationId: AnkiOperationId) {
        synchronized(lock) {
            write(read().filterNot { it.token.operationId == operationId })
        }
    }

    override fun removeForSession(sessionId: SessionId): Int =
        synchronized(lock) {
            val pending = read()
            val retained = pending.filterNot { it.token.sessionId == sessionId }
            write(retained)
            pending.size - retained.size
        }

    override fun clear() {
        synchronized(lock) {
            write(emptyList())
        }
    }

    override fun all(): List<PendingAnkiOperation> = synchronized(lock) { read() }

    private fun read(): List<PendingAnkiOperation> =
        preference.get()
            .takeIf(String::isNotBlank)
            ?.let { runCatching { json.decodeFromString<List<PendingAnkiOperation>>(it) }.getOrNull() }
            .orEmpty()

    private fun write(operations: List<PendingAnkiOperation>) {
        preference.set(if (operations.isEmpty()) "" else json.encodeToString(operations))
    }

    private companion object {
        const val PREFERENCE_KEY = "immersion_stats_pending_anki_operations"
        val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

interface AnkiOperationRecorder {
    fun begin(
        expression: String,
        reading: String? = null,
        provenance: InteractionProvenance? = null,
    ): AnkiOperationToken

    fun complete(
        token: AnkiOperationToken,
        operationType: AnkiOperationType,
        status: AnkiOperationStatus,
        noteId: Long? = null,
        errorCode: String? = null,
    ): RecordResult

    fun abandon(token: AnkiOperationToken): Boolean

    suspend fun retryPending(): Int
}

class DefaultAnkiOperationRecorder(
    private val recorder: ImmersionRecorder,
    private val repairStore: AnkiOperationRepairStore = NoOpAnkiOperationRepairStore,
    private val repairWriter: AnkiOperationRepairWriter = NoOpAnkiOperationRepairWriter,
    private val repairAllowed: () -> Boolean = { true },
    private val clock: () -> Instant = Instant::now,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) : AnkiOperationRecorder {
    private val operations = ConcurrentHashMap<AnkiOperationId, PendingAnkiCompletion>()

    override fun begin(
        expression: String,
        reading: String?,
        provenance: InteractionProvenance?,
    ): AnkiOperationToken {
        require(expression.isNotBlank()) { "Anki expression cannot be blank" }
        val snapshot = recorder.state.value
        val resolvedProvenance = snapshot.resolveInteractionProvenance(
            explicit = provenance,
            allowAmbientFallback = true,
        )
        val now = clock()
        val normalizedExpression = normalizeInteractionText(expression)
        val token = AnkiOperationToken(
            operationId = AnkiOperationId(UUID.randomUUID().toString()),
            sessionId = resolvedProvenance?.sessionId,
            sourceUnitId = resolvedProvenance?.sourceUnitId,
            expressionHash = sha256(normalizedExpression),
            normalizedExpression = normalizedExpression,
            normalizedReading = reading?.let(::normalizeInteractionText),
            occurredAtEpochMillis = now.toEpochMilli(),
            timezoneOffsetSeconds = zoneId().rules.getOffset(now).totalSeconds,
        )
        operations[token.operationId] = PendingAnkiCompletion(token)
        return token
    }

    override fun complete(
        token: AnkiOperationToken,
        operationType: AnkiOperationType,
        status: AnkiOperationStatus,
        noteId: Long?,
        errorCode: String?,
    ): RecordResult {
        val completion = operations[token.operationId] ?: return RecordResult.Enqueued(0)
        if (!completion.completed.compareAndSet(false, true)) return RecordResult.Enqueued(0)
        operations.remove(token.operationId, completion)
        val pending = PendingAnkiOperation(completion.token, operationType, status, noteId, errorCode)
        val sessionId = pending.token.sessionId
            ?: return RecordResult.Suppressed(CaptureSuppressionReason.NO_ACTIVE_SESSION)
        val repairQueued = status == AnkiOperationStatus.SUCCESS && repairAllowed()
        if (repairQueued) repairStore.put(pending)
        val result = record(sessionId, pending)
        if (
            repairQueued &&
            (
                result is RecordResult.Suppressed ||
                    result is RecordResult.Rejected ||
                    !repairAllowed()
                )
        ) {
            repairStore.remove(pending.token.operationId)
        }
        return result
    }

    override fun abandon(token: AnkiOperationToken): Boolean {
        val pending = operations[token.operationId] ?: return false
        if (!pending.completed.compareAndSet(false, true)) return false
        return operations.remove(token.operationId, pending)
    }

    override suspend fun retryPending(): Int {
        if (!repairAllowed()) return 0
        var repaired = 0
        for (pending in repairStore.all()) {
            if (!repairAllowed()) break
            if (runCatching { repairWriter.write(pending) }.getOrDefault(false)) {
                repairStore.remove(pending.token.operationId)
                repaired++
            }
        }
        return repaired
    }

    private fun record(
        sessionId: SessionId,
        pending: PendingAnkiOperation,
    ): RecordResult =
        recorder.record(
            SessionHandle(sessionId),
            CaptureCommand.AnkiOperation(
                operationId = pending.token.operationId,
                sourceUnitId = pending.token.sourceUnitId,
                expressionHash = pending.token.expressionHash,
                normalizedExpression = pending.token.normalizedExpression,
                normalizedReading = pending.token.normalizedReading,
                operationType = pending.operationType,
                status = pending.status,
                noteId = pending.noteId,
                errorCode = pending.errorCode,
            ),
        )

    private data class PendingAnkiCompletion(
        val token: AnkiOperationToken,
        val completed: AtomicBoolean = AtomicBoolean(false),
    )
}

private fun ImmersionSessionState.acceptsInteraction(): Boolean =
    this == ImmersionSessionState.ACTIVE ||
        this == ImmersionSessionState.IDLE ||
        this == ImmersionSessionState.PAUSED

private fun ImmersionRecorderSnapshot.resolveInteractionProvenance(
    explicit: InteractionProvenance?,
    allowAmbientFallback: Boolean,
): InteractionProvenance? {
    if (!state.acceptsInteraction()) return null
    val currentSessionId = sessionId ?: return null
    return if (explicit == null) {
        InteractionProvenance(currentSessionId, sourceUnitId).takeIf { allowAmbientFallback }
    } else {
        explicit.takeIf { it.sessionId == currentSessionId }
    }
}

private fun normalizeInteractionText(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFC).trim()

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
