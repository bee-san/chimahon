package tachiyomi.domain.immersion.repository

import tachiyomi.domain.immersion.model.ExposureEvent
import tachiyomi.domain.immersion.model.ImmersionDataException
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.ImmersionSessionStart
import tachiyomi.domain.immersion.model.ImmersionSourceUnit
import tachiyomi.domain.immersion.model.ImmersionTitle
import tachiyomi.domain.immersion.model.MillisecondDuration
import tachiyomi.domain.immersion.model.PersistenceErrorCode
import tachiyomi.domain.immersion.model.PersistenceResult
import tachiyomi.domain.immersion.model.RecordedImmersionEvent
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SessionStatus
import tachiyomi.domain.immersion.model.SourceUnitId
import tachiyomi.domain.immersion.service.ImmersionDiagnosticErrorCode
import tachiyomi.domain.immersion.service.ImmersionDiagnosticStage
import tachiyomi.domain.immersion.service.ImmersionStatsDiagnosticsStore

class FeatureFlaggedImmersionRecorderRepository(
    private val delegate: ImmersionRecorderRepository,
    private val disabledDelegate: ImmersionRecorderRepository = NoOpImmersionRecorderRepository(),
    private val isEnabled: () -> Boolean,
    private val diagnostics: ImmersionStatsDiagnosticsStore,
) : ImmersionRecorderRepository {

    override suspend fun upsertTitle(title: ImmersionTitle): PersistenceResult =
        guarded({ disabledDelegate.upsertTitle(title) }) { delegate.upsertTitle(title) }

    override suspend fun createSession(session: ImmersionSessionStart): PersistenceResult =
        guarded({ disabledDelegate.createSession(session) }) { delegate.createSession(session) }

    override suspend fun upsertSourceUnit(source: ImmersionSourceUnit): PersistenceResult =
        guarded({ disabledDelegate.upsertSourceUnit(source) }) { delegate.upsertSourceUnit(source) }

    override suspend fun appendExposure(event: ExposureEvent): PersistenceResult =
        guarded({ disabledDelegate.appendExposure(event) }) { delegate.appendExposure(event) }

    override suspend fun appendExposureBatch(events: List<ExposureEvent>): List<PersistenceResult> {
        if (!isEnabled()) return disabledDelegate.appendExposureBatch(events)
        return runCatching { delegate.appendExposureBatch(events) }
            .getOrElse { error ->
                diagnostics.recordError(ImmersionDiagnosticStage.WRITE, error.toDiagnosticCode())
                List(events.size) { PersistenceResult.Failed(error.toPersistenceCode()) }
            }
    }

    override suspend fun appendEventBatch(events: List<RecordedImmersionEvent>): List<PersistenceResult> {
        if (!isEnabled()) return disabledDelegate.appendEventBatch(events)
        return runCatching { delegate.appendEventBatch(events) }
            .getOrElse { error ->
                diagnostics.recordError(ImmersionDiagnosticStage.WRITE, error.toDiagnosticCode())
                List(events.size) { PersistenceResult.Failed(error.toPersistenceCode()) }
            }
    }

    override suspend fun finalizeSession(
        sessionId: SessionId,
        status: SessionStatus,
        endedAtEpochMillis: Long,
        elapsedDuration: MillisecondDuration,
    ): PersistenceResult = guarded(
        disabled = {
            disabledDelegate.finalizeSession(sessionId, status, endedAtEpochMillis, elapsedDuration)
        },
    ) {
        delegate.finalizeSession(sessionId, status, endedAtEpochMillis, elapsedDuration)
    }

    override suspend fun recoverAbandonedSessions(heartbeatCutoffEpochMillis: Long): Long {
        if (!isEnabled()) return disabledDelegate.recoverAbandonedSessions(heartbeatCutoffEpochMillis)
        return runCatching { delegate.recoverAbandonedSessions(heartbeatCutoffEpochMillis) }
            .getOrElse { error ->
                diagnostics.recordError(ImmersionDiagnosticStage.WRITE, error.toDiagnosticCode())
                0
            }
    }

    override suspend fun sourceUnitExists(sourceUnitId: SourceUnitId): Boolean {
        if (!isEnabled()) return disabledDelegate.sourceUnitExists(sourceUnitId)
        return runCatching { delegate.sourceUnitExists(sourceUnitId) }
            .getOrElse { error ->
                diagnostics.recordError(ImmersionDiagnosticStage.WRITE, error.toDiagnosticCode())
                false
            }
    }

    override suspend fun getSession(sessionId: SessionId): ImmersionSession? {
        if (!isEnabled()) return disabledDelegate.getSession(sessionId)
        return runCatching { delegate.getSession(sessionId) }
            .getOrElse { error ->
                diagnostics.recordError(ImmersionDiagnosticStage.WRITE, error.toDiagnosticCode())
                null
            }
    }

    private suspend fun guarded(
        disabled: suspend () -> PersistenceResult,
        block: suspend () -> PersistenceResult,
    ): PersistenceResult {
        if (!isEnabled()) return disabled()
        return runCatching { block() }
            .getOrElse { error ->
                diagnostics.recordError(ImmersionDiagnosticStage.WRITE, error.toDiagnosticCode())
                PersistenceResult.Failed(error.toPersistenceCode())
            }
    }
}

class NoOpImmersionRecorderRepository : ImmersionRecorderRepository {
    override suspend fun upsertTitle(title: ImmersionTitle) = PersistenceResult.Disabled

    override suspend fun createSession(session: ImmersionSessionStart) = PersistenceResult.Disabled

    override suspend fun upsertSourceUnit(source: ImmersionSourceUnit) = PersistenceResult.Disabled

    override suspend fun appendExposure(event: ExposureEvent) = PersistenceResult.Disabled

    override suspend fun appendExposureBatch(events: List<ExposureEvent>) =
        List(events.size) { PersistenceResult.Disabled }

    override suspend fun appendEventBatch(events: List<RecordedImmersionEvent>) =
        List(events.size) { PersistenceResult.Disabled }

    override suspend fun finalizeSession(
        sessionId: SessionId,
        status: SessionStatus,
        endedAtEpochMillis: Long,
        elapsedDuration: MillisecondDuration,
    ) = PersistenceResult.Disabled

    override suspend fun recoverAbandonedSessions(heartbeatCutoffEpochMillis: Long) = 0L

    override suspend fun sourceUnitExists(sourceUnitId: SourceUnitId) = false

    override suspend fun getSession(sessionId: SessionId): ImmersionSession? = null
}

private fun Throwable.toDiagnosticCode(): ImmersionDiagnosticErrorCode {
    val normalizedMessage = message.orEmpty().lowercase()
    return when {
        this is ImmersionDataException && code == PersistenceErrorCode.CORRUPT_VALUE ->
            ImmersionDiagnosticErrorCode.CORRUPT_VALUE
        "constraint" in normalizedMessage -> ImmersionDiagnosticErrorCode.CONSTRAINT_VIOLATION
        "busy" in normalizedMessage -> ImmersionDiagnosticErrorCode.DATABASE_BUSY
        "database" in normalizedMessage -> ImmersionDiagnosticErrorCode.DATABASE_UNAVAILABLE
        else -> ImmersionDiagnosticErrorCode.UNKNOWN
    }
}

private fun Throwable.toPersistenceCode(): PersistenceErrorCode =
    (this as? ImmersionDataException)?.code ?: when {
        message.orEmpty().contains("busy", ignoreCase = true) -> PersistenceErrorCode.DATABASE_BUSY
        message.orEmpty().contains("database", ignoreCase = true) -> PersistenceErrorCode.DATABASE_UNAVAILABLE
        else -> PersistenceErrorCode.UNKNOWN
    }
