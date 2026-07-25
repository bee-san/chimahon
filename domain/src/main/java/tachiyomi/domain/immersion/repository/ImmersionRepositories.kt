package tachiyomi.domain.immersion.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.immersion.model.ExposureEvent
import tachiyomi.domain.immersion.model.ImmersionAnkiSnapshot
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionIntegrityReport
import tachiyomi.domain.immersion.model.ImmersionOverview
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.ImmersionSessionStart
import tachiyomi.domain.immersion.model.ImmersionSourceUnit
import tachiyomi.domain.immersion.model.ImmersionTitle
import tachiyomi.domain.immersion.model.IndexWorkItem
import tachiyomi.domain.immersion.model.IndexedCharacter
import tachiyomi.domain.immersion.model.IndexedWord
import tachiyomi.domain.immersion.model.MillisecondDuration
import tachiyomi.domain.immersion.model.PersistenceResult
import tachiyomi.domain.immersion.model.RecordedImmersionEvent
import tachiyomi.domain.immersion.model.SessionCursor
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SessionPage
import tachiyomi.domain.immersion.model.SessionStatus
import tachiyomi.domain.immersion.model.SourceUnitId

interface ImmersionRecorderRepository {
    suspend fun upsertTitle(title: ImmersionTitle): PersistenceResult

    suspend fun createSession(session: ImmersionSessionStart): PersistenceResult

    suspend fun upsertSourceUnit(source: ImmersionSourceUnit): PersistenceResult

    suspend fun appendExposure(event: ExposureEvent): PersistenceResult

    suspend fun appendExposureBatch(events: List<ExposureEvent>): List<PersistenceResult>

    suspend fun appendEventBatch(events: List<RecordedImmersionEvent>): List<PersistenceResult>

    suspend fun finalizeSession(
        sessionId: SessionId,
        status: SessionStatus,
        endedAtEpochMillis: Long,
        elapsedDuration: MillisecondDuration,
    ): PersistenceResult

    suspend fun recoverAbandonedSessions(heartbeatCutoffEpochMillis: Long): Long

    suspend fun getSession(sessionId: SessionId): ImmersionSession?
}

interface ImmersionIndexRepository {
    suspend fun claimWork(targetVersion: Int, limit: Int): List<IndexWorkItem>

    suspend fun storeIndexResult(
        sourceUnitId: SourceUnitId,
        tokenizerVersion: Int,
        indexedVersion: Int,
        indexedAtEpochMillis: Long,
        words: List<IndexedWord>,
        characters: List<IndexedCharacter>,
    )

    suspend fun markFailure(sourceUnitId: SourceUnitId, errorCode: String)
}

interface ImmersionStatsRepository {
    suspend fun overview(): ImmersionOverview

    suspend fun sessionsPage(
        cursor: SessionCursor? = null,
        limit: Int,
    ): SessionPage

    fun observeRevision(): Flow<Long>
}

interface ImmersionMaintenanceRepository {
    suspend fun validateInvariants(expectedRollupVersion: Int): ImmersionIntegrityReport

    suspend fun deleteSession(sessionId: SessionId): Boolean

    suspend fun beginRollupRebuild(
        rollupVersion: Int,
        repairCursor: String?,
        updatedAtEpochMillis: Long,
    )
}

interface ImmersionGoalRepository {
    suspend fun upsertGoal(goal: ImmersionGoal)

    suspend fun getGoals(): List<ImmersionGoal>
}

interface ImmersionAnkiRepository {
    suspend fun upsertSnapshot(snapshot: ImmersionAnkiSnapshot)

    suspend fun getLatestSnapshot(profileId: String): ImmersionAnkiSnapshot?
}
