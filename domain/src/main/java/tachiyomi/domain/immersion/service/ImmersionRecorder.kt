// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.service

import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.immersion.model.AnkiOperationId
import tachiyomi.domain.immersion.model.AnkiOperationStatus
import tachiyomi.domain.immersion.model.AnkiOperationType
import tachiyomi.domain.immersion.model.EventType
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.ImmersionSourceUnit
import tachiyomi.domain.immersion.model.ImmersionTitle
import tachiyomi.domain.immersion.model.LookupStatus
import tachiyomi.domain.immersion.model.NetCharacterProgress
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.RecordedImmersionEvent
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SourceUnitId

interface ImmersionRecorder {
    val state: StateFlow<ImmersionRecorderSnapshot>

    suspend fun startSession(context: SessionContext): SessionStartResult

    fun record(command: CaptureCommand): RecordResult

    fun record(handle: SessionHandle, command: CaptureCommand): RecordResult

    suspend fun pause(reason: PauseReason)

    suspend fun pause(handle: SessionHandle, reason: PauseReason)

    suspend fun resume(reason: ResumeReason)

    suspend fun resume(handle: SessionHandle, reason: ResumeReason)

    suspend fun finalize(reason: FinalizeReason)

    suspend fun finalize(handle: SessionHandle, reason: FinalizeReason): ImmersionSession?

    suspend fun setIncognito(enabled: Boolean)

    suspend fun recoverAbandonedSessions(): Long

    suspend fun hasSeenSource(sourceUnitId: SourceUnitId): Boolean
}

data class SessionContext(
    val title: ImmersionTitle,
    val incognito: Boolean = false,
    val titleExcluded: Boolean = false,
)

data class SessionHandle(
    val sessionId: SessionId,
)

sealed interface SessionStartResult {
    data class Started(val handle: SessionHandle) : SessionStartResult

    data class Suppressed(val reason: CaptureSuppressionReason) : SessionStartResult

    data class Failed(val error: ImmersionDiagnosticErrorCode) : SessionStartResult
}

sealed interface CaptureCommand {
    data class Activity(
        val eventType: EventType = EventType.PROGRESS,
    ) : CaptureCommand {
        init {
            require(eventType != EventType.EXPOSURE) { "Exposure activity must include a source unit" }
            require(eventType != EventType.SESSION_STARTED && eventType != EventType.SESSION_FINALIZED) {
                "Session boundary events are owned by the recorder"
            }
        }
    }

    data class Progress(
        val netCharacters: NetCharacterProgress,
    ) : CaptureCommand {
        init {
            require(netCharacters != NetCharacterProgress.ZERO) {
                "Zero net progress does not require an event"
            }
        }
    }

    data class Exposure(
        val source: ImmersionSourceUnit,
        val grossCharacters: NonNegativeCounter,
        val uniqueSourceCharacters: NonNegativeCounter,
        val netCharacters: NetCharacterProgress,
        val replayOrdinal: Int = 0,
        val exposurePolicy: String,
    ) : CaptureCommand {
        init {
            require(replayOrdinal >= 0) { "Replay ordinal cannot be negative" }
            require(exposurePolicy.isNotBlank()) { "Exposure policy cannot be blank" }
        }
    }

    data class Lookup(
        val lookupId: String,
        val sourceUnitId: SourceUnitId?,
        val queryHash: String,
        val rawQuery: String?,
        val normalizedHeadword: String?,
        val normalizedReading: String?,
        val partOfSpeech: String?,
        val dictionaryId: String?,
        val resultId: String?,
        val status: LookupStatus,
    ) : CaptureCommand {
        init {
            require(lookupId.isNotBlank()) { "Lookup ID cannot be blank" }
            require(queryHash.isNotBlank()) { "Lookup query hash cannot be blank" }
        }
    }

    data class AnkiOperation(
        val operationId: AnkiOperationId,
        val sourceUnitId: SourceUnitId?,
        val expressionHash: String,
        val normalizedExpression: String?,
        val normalizedReading: String?,
        val operationType: AnkiOperationType,
        val status: AnkiOperationStatus,
        val noteId: Long?,
        val cardId: Long? = null,
        val deckId: Long? = null,
        val errorCode: String? = null,
    ) : CaptureCommand {
        init {
            require(expressionHash.isNotBlank()) { "Anki expression hash cannot be blank" }
        }
    }
}

sealed interface RecordResult {
    data class Enqueued(val eventCount: Int) : RecordResult

    data class Suppressed(val reason: CaptureSuppressionReason) : RecordResult

    data class Rejected(val state: ImmersionSessionState) : RecordResult

    data object QueueFull : RecordResult
}

enum class PauseReason {
    USER,
    BACKGROUND,
    BUFFERING,
}

enum class ResumeReason {
    USER,
    FOREGROUND,
    BUFFERING_ENDED,
    ACTIVITY_AFTER_IDLE,
}

enum class FinalizeReason {
    NORMAL,
    TITLE_CHANGED,
    INCOGNITO_ENABLED,
    CAPTURE_DISABLED,
    PERSISTENCE_FAILURE,
}

data class ImmersionRecorderSnapshot(
    val sessionId: SessionId? = null,
    val sourceUnitId: SourceUnitId? = null,
    val state: ImmersionSessionState = ImmersionSessionState.NOT_STARTED,
    val lastFailure: ImmersionDiagnosticErrorCode? = null,
)

fun interface ImmersionDeviceIdProvider {
    fun get(): String
}

fun interface ImmersionRepairScheduler {
    fun schedule(sessionId: SessionId, reason: ImmersionRepairReason)
}

fun interface ImmersionRollupScheduler {
    fun schedule(lastEventId: tachiyomi.domain.immersion.model.EventId, eventCount: Int)
}

fun interface ImmersionEventPersistenceObserver {
    fun onPersisted(events: List<RecordedImmersionEvent>)
}

enum class ImmersionRepairReason {
    SESSION_COUNTER_DIVERGENCE,
    EVENT_WRITE_FAILURE,
}
