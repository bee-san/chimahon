package tachiyomi.domain.immersion.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.immersion.model.AnalyticsAnkiSummary
import tachiyomi.domain.immersion.model.AnalyticsCharacterRow
import tachiyomi.domain.immersion.model.AnalyticsDataQuality
import tachiyomi.domain.immersion.model.AnalyticsPage
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.AnalyticsTitleMetadata
import tachiyomi.domain.immersion.model.AnalyticsWordRow
import tachiyomi.domain.immersion.model.ExposureEvent
import tachiyomi.domain.immersion.model.ImmersionAnkiItem
import tachiyomi.domain.immersion.model.ImmersionAnkiSnapshot
import tachiyomi.domain.immersion.model.ImmersionDailyRollup
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionIntegrityReport
import tachiyomi.domain.immersion.model.ImmersionOverview
import tachiyomi.domain.immersion.model.ImmersionReindexRequest
import tachiyomi.domain.immersion.model.ImmersionRollupDirtyRange
import tachiyomi.domain.immersion.model.ImmersionRollupRebuildResult
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.ImmersionSessionStart
import tachiyomi.domain.immersion.model.ImmersionSourceUnit
import tachiyomi.domain.immersion.model.ImmersionTitle
import tachiyomi.domain.immersion.model.IndexTerminalReason
import tachiyomi.domain.immersion.model.IndexWorkItem
import tachiyomi.domain.immersion.model.IndexedCharacter
import tachiyomi.domain.immersion.model.IndexedWord
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.MillisecondDuration
import tachiyomi.domain.immersion.model.PersistenceResult
import tachiyomi.domain.immersion.model.RecordedImmersionEvent
import tachiyomi.domain.immersion.model.SessionCursor
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SessionPage
import tachiyomi.domain.immersion.model.SessionStatus
import tachiyomi.domain.immersion.model.SourceUnitId
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.model.UnicodeCodePoint
import tachiyomi.domain.immersion.service.AnkiCoverage

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

    suspend fun sourceUnitExists(sourceUnitId: SourceUnitId): Boolean

    suspend fun getSession(sessionId: SessionId): ImmersionSession?
}

interface ImmersionIndexRepository {
    suspend fun claimWork(
        targetVersion: Int,
        limit: Int,
        nowEpochMillis: Long,
    ): List<IndexWorkItem>

    suspend fun storeIndexResult(
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
    )

    suspend fun markFailure(
        sourceUnitId: SourceUnitId,
        errorCode: String,
        nextAttemptAtEpochMillis: Long,
    )

    suspend fun requeue(
        request: ImmersionReindexRequest,
        targetVersion: Int,
    ): Long

    suspend fun pendingCount(targetVersion: Int): Long
}

interface ImmersionStatsRepository {
    suspend fun overview(): ImmersionOverview

    suspend fun sessionsPage(
        cursor: SessionCursor? = null,
        limit: Int,
    ): SessionPage

    fun observeRevision(): Flow<Long>
}

interface ImmersionAnalyticsRepository {
    suspend fun availableDateRange(filter: StatsFilter): tachiyomi.domain.immersion.model.LocalDateRange?

    suspend fun dailyRollups(
        range: tachiyomi.domain.immersion.model.LocalDateRange,
    ): List<ImmersionDailyRollup>

    suspend fun titleMetadata(titleIds: Set<TitleId>): List<AnalyticsTitleMetadata>

    suspend fun dataQuality(
        filter: StatsFilter,
        nowEpochMillis: Long,
    ): AnalyticsDataQuality

    suspend fun vocabularyPage(
        filter: StatsFilter,
        sort: AnalyticsSort,
        offset: Long,
        limit: Int,
    ): AnalyticsPage<AnalyticsWordRow>

    suspend fun characterPage(
        filter: StatsFilter,
        sort: AnalyticsSort,
        offset: Long,
        limit: Int,
    ): AnalyticsPage<AnalyticsCharacterRow>

    suspend fun filteredSessionsPage(
        filter: StatsFilter,
        cursor: SessionCursor?,
        limit: Int,
    ): SessionPage

    suspend fun ankiSummary(filter: StatsFilter): AnalyticsAnkiSummary

    suspend fun dirtyRollupRanges(limit: Int): List<ImmersionRollupDirtyRange>

    suspend fun rebuildRollups(
        range: tachiyomi.domain.immersion.model.LocalDateRange,
        rollupVersion: Int,
        nowEpochMillis: Long,
    ): ImmersionRollupRebuildResult
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
    suspend fun activateSnapshot(
        snapshot: ImmersionAnkiSnapshot,
        items: List<ImmersionAnkiItem>,
    )

    suspend fun recordSnapshotAttempt(snapshot: ImmersionAnkiSnapshot)

    suspend fun getCurrentSnapshot(profileId: String): ImmersionAnkiSnapshot?

    suspend fun getLatestSnapshot(profileId: String): ImmersionAnkiSnapshot?

    fun observeLatestSnapshot(profileId: String): Flow<ImmersionAnkiSnapshot?>

    suspend fun getCurrentItems(profileId: String): List<ImmersionAnkiItem>

    suspend fun findWordItems(
        profileId: String,
        languageTag: LanguageTag,
        normalizedWord: String,
        normalizedReading: String,
    ): List<ImmersionAnkiItem>

    suspend fun findCharacterItems(
        profileId: String,
        codePoint: UnicodeCodePoint,
    ): List<ImmersionAnkiItem>

    suspend fun getWordCoverage(
        profileId: String,
        languageTag: LanguageTag,
    ): AnkiCoverage

    suspend fun getCharacterCoverage(
        profileId: String,
        languageTag: LanguageTag,
    ): AnkiCoverage

    suspend fun recomputeCurrentMaturity(
        profileId: String,
        matureIntervalDays: Int,
        recomputedAtEpochMillis: Long,
    )

    suspend fun clearSnapshots(profileId: String)
}
