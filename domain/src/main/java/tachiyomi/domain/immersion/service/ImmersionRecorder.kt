package tachiyomi.domain.immersion.service

import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.immersion.model.EventType
import tachiyomi.domain.immersion.model.ImmersionSourceUnit
import tachiyomi.domain.immersion.model.ImmersionTitle
import tachiyomi.domain.immersion.model.NetCharacterProgress
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.SessionId

interface ImmersionRecorder {
    val state: StateFlow<ImmersionRecorderSnapshot>

    suspend fun startSession(context: SessionContext): SessionStartResult

    fun record(command: CaptureCommand): RecordResult

    suspend fun pause(reason: PauseReason)

    suspend fun resume(reason: ResumeReason)

    suspend fun finalize(reason: FinalizeReason)

    suspend fun setIncognito(enabled: Boolean)

    suspend fun recoverAbandonedSessions(): Long
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

enum class ImmersionRepairReason {
    SESSION_COUNTER_DIVERGENCE,
    EVENT_WRITE_FAILURE,
}
