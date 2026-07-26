// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.immersion.model.AnalyticsAnkiSummary
import tachiyomi.domain.immersion.model.AnalyticsBucketInventory
import tachiyomi.domain.immersion.model.AnalyticsCharacterRow
import tachiyomi.domain.immersion.model.AnalyticsDataQuality
import tachiyomi.domain.immersion.model.AnalyticsInventoryMetrics
import tachiyomi.domain.immersion.model.AnalyticsPage
import tachiyomi.domain.immersion.model.AnalyticsSessionDetail
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.AnalyticsSourceOccurrence
import tachiyomi.domain.immersion.model.AnalyticsTemporalActivity
import tachiyomi.domain.immersion.model.AnalyticsTitleMetadata
import tachiyomi.domain.immersion.model.AnalyticsTitleSeriesSelection
import tachiyomi.domain.immersion.model.AnalyticsTitleTrendDailyPoint
import tachiyomi.domain.immersion.model.AnalyticsVocabularyFirstSeenDay
import tachiyomi.domain.immersion.model.AnalyticsWordRow
import tachiyomi.domain.immersion.model.ExposureEvent
import tachiyomi.domain.immersion.model.ImmersionAnkiItem
import tachiyomi.domain.immersion.model.ImmersionAnkiSnapshot
import tachiyomi.domain.immersion.model.ImmersionDailyRollup
import tachiyomi.domain.immersion.model.ImmersionDeletionPreview
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionGoalAchievement
import tachiyomi.domain.immersion.model.ImmersionGoalCheckIn
import tachiyomi.domain.immersion.model.ImmersionIntegrityReport
import tachiyomi.domain.immersion.model.ImmersionMaintenanceSummary
import tachiyomi.domain.immersion.model.ImmersionMergeReport
import tachiyomi.domain.immersion.model.ImmersionOverview
import tachiyomi.domain.immersion.model.ImmersionPortableArchive
import tachiyomi.domain.immersion.model.ImmersionReindexRequest
import tachiyomi.domain.immersion.model.ImmersionRollupDirtyRange
import tachiyomi.domain.immersion.model.ImmersionRollupRebuildResult
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.ImmersionSessionStart
import tachiyomi.domain.immersion.model.ImmersionSourceUnit
import tachiyomi.domain.immersion.model.ImmersionStatsDeletionScope
import tachiyomi.domain.immersion.model.ImmersionTitle
import tachiyomi.domain.immersion.model.IndexTerminalReason
import tachiyomi.domain.immersion.model.IndexWorkItem
import tachiyomi.domain.immersion.model.IndexedCharacter
import tachiyomi.domain.immersion.model.IndexedWord
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.LocalDateRange
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
    suspend fun isTitleCaptureExcluded(titleId: TitleId): Boolean = false

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
        request: ImmersionReindexRequest = ImmersionReindexRequest(),
    ): List<IndexWorkItem>

    suspend fun releaseClaims(work: List<IndexWorkItem>)

    suspend fun storeIndexResult(
        sourceUnitId: SourceUnitId,
        claimGeneration: Int,
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
        claimGeneration: Int,
        errorCode: String,
        nextAttemptAtEpochMillis: Long,
    )

    suspend fun requeue(
        request: ImmersionReindexRequest,
        targetVersion: Int,
    ): Long

    suspend fun pendingCount(
        targetVersion: Int,
        request: ImmersionReindexRequest = ImmersionReindexRequest(),
    ): Long
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

    suspend fun inventoryMetrics(filter: StatsFilter): AnalyticsInventoryMetrics

    /**
     * Returns one inventory result per ordered, non-overlapping bucket. The
     * buckets are authoritative for dates; gaps are excluded from cumulative
     * inventory even when [StatsFilter.dateRange] spans them.
     */
    suspend fun bucketInventoryMetrics(
        filter: StatsFilter,
        buckets: List<LocalDateRange>,
    ): List<AnalyticsBucketInventory>

    suspend fun titleInventoryMetrics(filter: StatsFilter): Map<TitleId, AnalyticsInventoryMetrics>

    suspend fun titleMetadata(titleIds: Set<TitleId>): List<AnalyticsTitleMetadata>

    suspend fun temporalActivity(filter: StatsFilter): AnalyticsTemporalActivity

    suspend fun titleTrendDaily(
        filter: StatsFilter,
        selection: AnalyticsTitleSeriesSelection,
        limit: Int,
    ): List<AnalyticsTitleTrendDailyPoint>

    suspend fun vocabularyFirstSeenByDate(
        filter: StatsFilter,
    ): List<AnalyticsVocabularyFirstSeenDay>

    suspend fun dataQuality(
        filter: StatsFilter,
        nowEpochMillis: Long,
    ): AnalyticsDataQuality

    suspend fun vocabularyPage(
        filter: StatsFilter,
        sort: AnalyticsSort,
        offset: Long,
        limit: Int,
        searchQuery: String? = null,
    ): AnalyticsPage<AnalyticsWordRow>

    suspend fun characterPage(
        filter: StatsFilter,
        sort: AnalyticsSort,
        offset: Long,
        limit: Int,
        searchQuery: String? = null,
    ): AnalyticsPage<AnalyticsCharacterRow>

    suspend fun filteredSessionsPage(
        filter: StatsFilter,
        cursor: SessionCursor?,
        limit: Int,
    ): SessionPage

    suspend fun sessionDetail(
        sessionId: SessionId,
        maxTimelineBuckets: Int,
    ): AnalyticsSessionDetail?

    suspend fun sourceSearch(
        filter: StatsFilter,
        query: String,
        offset: Long,
        limit: Int,
    ): AnalyticsPage<AnalyticsSourceOccurrence>

    suspend fun wordOccurrences(
        filter: StatsFilter,
        wordId: String,
        offset: Long,
        limit: Int,
    ): AnalyticsPage<AnalyticsSourceOccurrence>

    suspend fun characterOccurrences(
        filter: StatsFilter,
        codePoint: UnicodeCodePoint,
        offset: Long,
        limit: Int,
    ): AnalyticsPage<AnalyticsSourceOccurrence>

    suspend fun characterContainingWords(
        filter: StatsFilter,
        codePoint: UnicodeCodePoint,
        sort: AnalyticsSort,
        offset: Long,
        limit: Int,
    ): AnalyticsPage<AnalyticsWordRow>

    suspend fun ankiSummary(filter: StatsFilter): AnalyticsAnkiSummary

    suspend fun dirtyRollupRanges(limit: Int): List<ImmersionRollupDirtyRange>

    suspend fun rebuildRollups(
        range: tachiyomi.domain.immersion.model.LocalDateRange,
        rollupVersion: Int,
        nowEpochMillis: Long,
    ): ImmersionRollupRebuildResult
}

interface ImmersionMaintenanceRepository {
    suspend fun isTitleCaptureExcluded(titleId: TitleId): Boolean

    suspend fun validateInvariants(expectedRollupVersion: Int): ImmersionIntegrityReport

    suspend fun repairSessionCounters(
        sessionId: SessionId,
        repairedAtEpochMillis: Long,
    ): Boolean

    suspend fun maintenanceSummary(): ImmersionMaintenanceSummary

    suspend fun previewAllStatsDeletion(): ImmersionDeletionPreview

    suspend fun resetAllStats(
        deviceId: String,
        deletedAtEpochMillis: Long,
    ): ImmersionDeletionPreview

    suspend fun previewScopedStatsDeletion(scope: ImmersionStatsDeletionScope): ImmersionDeletionPreview

    suspend fun deleteScopedStats(
        scope: ImmersionStatsDeletionScope,
        expectedPreview: ImmersionDeletionPreview,
    ): ImmersionDeletionPreview

    suspend fun deleteSession(sessionId: SessionId): Boolean

    suspend fun beginRollupRebuild(
        rollupVersion: Int,
        repairCursor: String?,
        updatedAtEpochMillis: Long,
    )

    suspend fun exportPortableArchive(
        includeRawText: Boolean,
        createdAtEpochMillis: Long,
    ): ImmersionPortableArchive

    suspend fun mergePortableArchive(
        archive: ImmersionPortableArchive,
        mergedAtEpochMillis: Long,
    ): ImmersionMergeReport

    suspend fun deleteRawText(
        titleId: TitleId? = null,
        beforeEpochMillis: Long? = null,
        updatedAtEpochMillis: Long,
    ): Long

    suspend fun previewRawTextDeletion(
        titleId: TitleId? = null,
        beforeEpochMillis: Long? = null,
    ): Long

    suspend fun setTitleCaptureExcluded(
        titleId: TitleId,
        excluded: Boolean,
        updatedAtEpochMillis: Long,
    )

    suspend fun resolveMergeConflictsKeepingLocal(): Long
}

interface ImmersionGoalRepository {
    suspend fun upsertGoal(goal: ImmersionGoal)

    suspend fun getGoals(): List<ImmersionGoal>

    suspend fun upsertCheckIn(checkIn: ImmersionGoalCheckIn)

    suspend fun getCheckIns(goalId: String): List<ImmersionGoalCheckIn>

    suspend fun recordAchievement(achievement: ImmersionGoalAchievement)

    suspend fun getAchievements(goalId: String): List<ImmersionGoalAchievement>
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

    suspend fun clearSnapshots(profileId: String): Long
}
