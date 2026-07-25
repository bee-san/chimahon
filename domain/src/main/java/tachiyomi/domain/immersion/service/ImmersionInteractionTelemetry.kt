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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    ): LookupIntentToken {
        require(intentId.isNotBlank()) { "Lookup intent ID cannot be blank" }
        require(query.isNotBlank()) { "Lookup query cannot be blank" }
        val existing = intents[intentId]
        if (existing != null) return existing.token
        val snapshot = recorder.state.value
        val accepted = snapshot.sessionId != null && snapshot.state.acceptsInteraction()
        val normalized = normalizeInteractionText(query)
        val token = LookupIntentToken(
            id = intentId,
            sessionId = snapshot.sessionId.takeIf { accepted },
            sourceUnitId = snapshot.sourceUnitId.takeIf { accepted },
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
        val sessionId = token.sessionId
            ?: return RecordResult.Suppressed(CaptureSuppressionReason.NO_ACTIVE_SESSION)
        return recorder.record(
            SessionHandle(sessionId),
            CaptureCommand.Lookup(
                lookupId = token.id,
                sourceUnitId = token.sourceUnitId,
                queryHash = token.queryHash,
                rawQuery = token.rawQuery,
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
    ): AnkiOperationToken

    fun complete(
        token: AnkiOperationToken,
        operationType: AnkiOperationType,
        status: AnkiOperationStatus,
        noteId: Long? = null,
        errorCode: String? = null,
    ): RecordResult

    suspend fun retryPending(): Int
}

class DefaultAnkiOperationRecorder(
    private val recorder: ImmersionRecorder,
    private val repairStore: AnkiOperationRepairStore = NoOpAnkiOperationRepairStore,
    private val repairWriter: AnkiOperationRepairWriter = NoOpAnkiOperationRepairWriter,
) : AnkiOperationRecorder {
    override fun begin(
        expression: String,
        reading: String?,
    ): AnkiOperationToken {
        require(expression.isNotBlank()) { "Anki expression cannot be blank" }
        val snapshot = recorder.state.value
        val accepted = snapshot.sessionId != null && snapshot.state.acceptsInteraction()
        val normalizedExpression = normalizeInteractionText(expression)
        return AnkiOperationToken(
            operationId = AnkiOperationId(UUID.randomUUID().toString()),
            sessionId = snapshot.sessionId.takeIf { accepted },
            sourceUnitId = snapshot.sourceUnitId.takeIf { accepted },
            expressionHash = sha256(normalizedExpression),
            normalizedExpression = normalizedExpression,
            normalizedReading = reading?.let(::normalizeInteractionText),
        )
    }

    override fun complete(
        token: AnkiOperationToken,
        operationType: AnkiOperationType,
        status: AnkiOperationStatus,
        noteId: Long?,
        errorCode: String?,
    ): RecordResult {
        val pending = PendingAnkiOperation(token, operationType, status, noteId, errorCode)
        val sessionId = token.sessionId
            ?: return RecordResult.Suppressed(CaptureSuppressionReason.NO_ACTIVE_SESSION)
        if (status == AnkiOperationStatus.SUCCESS) repairStore.put(pending)
        return record(sessionId, pending)
    }

    override suspend fun retryPending(): Int {
        var repaired = 0
        repairStore.all().forEach { pending ->
            if (repairWriter.write(pending)) {
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
}

private fun ImmersionSessionState.acceptsInteraction(): Boolean =
    this == ImmersionSessionState.ACTIVE ||
        this == ImmersionSessionState.IDLE ||
        this == ImmersionSessionState.PAUSED

private fun normalizeInteractionText(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFC).trim()

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
