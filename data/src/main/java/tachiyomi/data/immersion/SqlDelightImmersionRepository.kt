package tachiyomi.data.immersion

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.Immersion_anki_item
import tachiyomi.data.Immersion_anki_snapshot
import tachiyomi.data.Immersion_daily_rollup
import tachiyomi.data.Immersion_goal
import tachiyomi.data.Immersion_import_ledger
import tachiyomi.data.Immersion_session
import tachiyomi.data.Immersion_source_unit
import tachiyomi.data.Immersion_title
import tachiyomi.data.Immersion_title_mutation
import tachiyomi.data.SelectImmersionIndexWork
import tachiyomi.data.SelectLegacyImmersionAggregates
import tachiyomi.domain.immersion.model.AnalyticsActivityTotals
import tachiyomi.domain.immersion.model.AnalyticsAnkiSummary
import tachiyomi.domain.immersion.model.AnalyticsAnkiTitleImpact
import tachiyomi.domain.immersion.model.AnalyticsAnkiWeeklyImpact
import tachiyomi.domain.immersion.model.AnalyticsBucketInventory
import tachiyomi.domain.immersion.model.AnalyticsCharacterFilter
import tachiyomi.domain.immersion.model.AnalyticsCharacterRange
import tachiyomi.domain.immersion.model.AnalyticsCharacterRow
import tachiyomi.domain.immersion.model.AnalyticsCharacterScript
import tachiyomi.domain.immersion.model.AnalyticsCharacterScriptSummary
import tachiyomi.domain.immersion.model.AnalyticsCharacterSummary
import tachiyomi.domain.immersion.model.AnalyticsDataQuality
import tachiyomi.domain.immersion.model.AnalyticsHourActivity
import tachiyomi.domain.immersion.model.AnalyticsInventoryMetrics
import tachiyomi.domain.immersion.model.AnalyticsPage
import tachiyomi.domain.immersion.model.AnalyticsSessionDetail
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.AnalyticsSourceOccurrence
import tachiyomi.domain.immersion.model.AnalyticsTemporalActivity
import tachiyomi.domain.immersion.model.AnalyticsTimelineBucket
import tachiyomi.domain.immersion.model.AnalyticsTitleCompletedUnit
import tachiyomi.domain.immersion.model.AnalyticsTitleCoverage
import tachiyomi.domain.immersion.model.AnalyticsTitleMetadata
import tachiyomi.domain.immersion.model.AnalyticsTitleSeriesSelection
import tachiyomi.domain.immersion.model.AnalyticsTitleTrendDailyPoint
import tachiyomi.domain.immersion.model.AnalyticsTitleUnitCompletionDay
import tachiyomi.domain.immersion.model.AnalyticsTitleUnitProgress
import tachiyomi.domain.immersion.model.AnalyticsWeekdayActivity
import tachiyomi.domain.immersion.model.AnkiInventoryFailure
import tachiyomi.domain.immersion.model.AnkiOperationEvent
import tachiyomi.domain.immersion.model.AnkiOperationStatus
import tachiyomi.domain.immersion.model.AnkiOperationType
import tachiyomi.domain.immersion.model.AnkiSnapshotStatus
import tachiyomi.domain.immersion.model.CharacterCoverage
import tachiyomi.domain.immersion.model.CharacterVolume
import tachiyomi.domain.immersion.model.EventId
import tachiyomi.domain.immersion.model.EventType
import tachiyomi.domain.immersion.model.ExposureEvent
import tachiyomi.domain.immersion.model.ImmersionAnkiItem
import tachiyomi.domain.immersion.model.ImmersionAnkiSnapshot
import tachiyomi.domain.immersion.model.ImmersionArchiveException
import tachiyomi.domain.immersion.model.ImmersionArchiveRejection
import tachiyomi.domain.immersion.model.ImmersionDailyRollup
import tachiyomi.domain.immersion.model.ImmersionDataException
import tachiyomi.domain.immersion.model.ImmersionDeletionPreview
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionGoalAchievement
import tachiyomi.domain.immersion.model.ImmersionGoalCheckIn
import tachiyomi.domain.immersion.model.ImmersionIntegrityReport
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.ImmersionMaintenanceSummary
import tachiyomi.domain.immersion.model.ImmersionMergeDisposition
import tachiyomi.domain.immersion.model.ImmersionMergeEntityCounts
import tachiyomi.domain.immersion.model.ImmersionMergeReport
import tachiyomi.domain.immersion.model.ImmersionMergeVerification
import tachiyomi.domain.immersion.model.ImmersionOverview
import tachiyomi.domain.immersion.model.ImmersionPortableAffinity
import tachiyomi.domain.immersion.model.ImmersionPortableArchive
import tachiyomi.domain.immersion.model.ImmersionPortableCell
import tachiyomi.domain.immersion.model.ImmersionPortableCellKind
import tachiyomi.domain.immersion.model.ImmersionPortableColumn
import tachiyomi.domain.immersion.model.ImmersionPortableRow
import tachiyomi.domain.immersion.model.ImmersionPortableTable
import tachiyomi.domain.immersion.model.ImmersionReindexRequest
import tachiyomi.domain.immersion.model.ImmersionRollupDirtyRange
import tachiyomi.domain.immersion.model.ImmersionRollupRebuildResult
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.ImmersionSessionStart
import tachiyomi.domain.immersion.model.ImmersionSourceUnit
import tachiyomi.domain.immersion.model.ImmersionStatsDeletionScope
import tachiyomi.domain.immersion.model.ImmersionTitle
import tachiyomi.domain.immersion.model.ImmersionTitleMutation
import tachiyomi.domain.immersion.model.ImmersionTitleMutationBlocker
import tachiyomi.domain.immersion.model.ImmersionTitleMutationPreview
import tachiyomi.domain.immersion.model.ImmersionTitleMutationRequest
import tachiyomi.domain.immersion.model.ImmersionTitleMutationType
import tachiyomi.domain.immersion.model.IndexTerminalReason
import tachiyomi.domain.immersion.model.IndexWorkItem
import tachiyomi.domain.immersion.model.IndexedCharacter
import tachiyomi.domain.immersion.model.IndexedWord
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.LegacyAggregateRow
import tachiyomi.domain.immersion.model.LegacyImportBatch
import tachiyomi.domain.immersion.model.LegacyImportIdentity
import tachiyomi.domain.immersion.model.LegacyImportResult
import tachiyomi.domain.immersion.model.LegacyImportResultState
import tachiyomi.domain.immersion.model.LocalDateRange
import tachiyomi.domain.immersion.model.MAX_RECORDED_IMMERSION_EVENT_ACTIVE_DURATION_MILLIS
import tachiyomi.domain.immersion.model.MaturityTier
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.MillisecondDuration
import tachiyomi.domain.immersion.model.NetCharacterProgress
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.PersistenceErrorCode
import tachiyomi.domain.immersion.model.PersistenceResult
import tachiyomi.domain.immersion.model.ProvenanceState
import tachiyomi.domain.immersion.model.ReadingMetrics
import tachiyomi.domain.immersion.model.RecordedImmersionEvent
import tachiyomi.domain.immersion.model.SessionCursor
import tachiyomi.domain.immersion.model.SessionEvent
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SessionPage
import tachiyomi.domain.immersion.model.SessionStatus
import tachiyomi.domain.immersion.model.SourceKind
import tachiyomi.domain.immersion.model.SourceUnitId
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.model.UnicodeCodePoint
import tachiyomi.domain.immersion.repository.ImmersionAnalyticsRepository
import tachiyomi.domain.immersion.repository.ImmersionAnkiRepository
import tachiyomi.domain.immersion.repository.ImmersionGoalRepository
import tachiyomi.domain.immersion.repository.ImmersionIndexRepository
import tachiyomi.domain.immersion.repository.ImmersionLegacyImportRepository
import tachiyomi.domain.immersion.repository.ImmersionMaintenanceRepository
import tachiyomi.domain.immersion.repository.ImmersionRecorderRepository
import tachiyomi.domain.immersion.repository.ImmersionStatsRepository
import tachiyomi.domain.immersion.service.AnkiCoverage
import tachiyomi.domain.immersion.service.ImmersionAnalyticsCalendar
import tachiyomi.domain.immersion.service.ImmersionStatsVersions
import tachiyomi.domain.immersion.service.PendingAnkiOperation
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID

class SqlDelightImmersionRepository(
    private val handler: DatabaseHandler,
    private val onAllStatsReset: () -> Unit = {},
    private val onSessionDeleted: (SessionId) -> Unit = {},
    private val portableMergeCheckpointObserver: suspend (
        archiveId: String,
        tableName: String,
        nextRowOffset: Int,
    ) -> Unit = { _, _, _ -> },
    private val portableMergeRollupChunkObserver: suspend (
        archiveId: String,
        pass: Int,
        range: LocalDateRange,
    ) -> Unit = { _, _, _ -> },
    private val portableMergeLifetimePageSize: Int = PORTABLE_ROLLUP_LIFETIME_PAGE_SIZE,
) : ImmersionRecorderRepository,
    ImmersionIndexRepository,
    ImmersionStatsRepository,
    ImmersionAnalyticsRepository,
    ImmersionMaintenanceRepository,
    ImmersionGoalRepository,
    ImmersionAnkiRepository,
    ImmersionLegacyImportRepository {

    init {
        require(portableMergeLifetimePageSize > 0)
    }

    private val ankiSummaryCache =
        object : LinkedHashMap<AnkiSummaryCacheKey, AnalyticsAnkiSummary>(
            ANKI_SUMMARY_CACHE_SIZE,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<AnkiSummaryCacheKey, AnalyticsAnkiSummary>,
            ): Boolean = size > ANKI_SUMMARY_CACHE_SIZE
        }

    override suspend fun isTitleCaptureExcluded(titleId: TitleId): Boolean =
        handler.await {
            captureExclusionTitleIds(titleId.value).any { candidate ->
                immersionQueries.isImmersionIndexEntityExcluded(
                    entityType = IMMERSION_TITLE_EXCLUSION_TYPE,
                    entityId = candidate,
                    scopeKeys = listOf(IMMERSION_CAPTURE_EXCLUSION_SCOPE),
                ).executeAsOne() > 0
            }
        }

    override suspend fun startSession(
        title: ImmersionTitle,
        session: ImmersionSessionStart,
        event: SessionEvent,
    ): PersistenceResult {
        ensureAtomicSessionStartIdentity(title, session, event)
        event.requireSupportedActiveDuration()
        return handler.await(inTransaction = true) {
            val resolvedTitleId = resolveImmersionTitleId(title.id.value)
            val titleResult = if (resolvedTitleId == title.id.value) {
                upsertTitleInDatabase(title)
            } else {
                checkNotNull(immersionQueries.selectImmersionTitleById(resolvedTitleId).executeAsOneOrNull()) {
                    "Merged title target $resolvedTitleId no longer exists"
                }
                PersistenceResult.AlreadyApplied
            }
            val resolvedSession = session.copy(titleId = TitleId(resolvedTitleId))
            val sessionResult = createSessionInDatabase(
                session = resolvedSession,
                originTitleId = title.id,
            )
            val eventResult = appendSessionEventInDatabase(event)
            if (
                titleResult == PersistenceResult.AlreadyApplied &&
                sessionResult == PersistenceResult.AlreadyApplied &&
                eventResult == PersistenceResult.AlreadyApplied
            ) {
                PersistenceResult.AlreadyApplied
            } else {
                PersistenceResult.Applied
            }
        }
    }

    override suspend fun upsertTitle(title: ImmersionTitle): PersistenceResult =
        handler.await(inTransaction = true) {
            if (resolveImmersionTitleId(title.id.value) == title.id.value) {
                upsertTitleInDatabase(title)
            } else {
                PersistenceResult.AlreadyApplied
            }
        }

    override suspend fun createSession(session: ImmersionSessionStart): PersistenceResult =
        handler.await(inTransaction = true) {
            val originTitleId = session.titleId
            createSessionInDatabase(
                session = session.copy(
                    titleId = TitleId(resolveImmersionTitleId(originTitleId.value)),
                ),
                originTitleId = originTitleId,
            )
        }

    override suspend fun upsertSourceUnit(source: ImmersionSourceUnit): PersistenceResult =
        handler.await(inTransaction = true) {
            val originTitleId = source.titleId
            upsertSourceInDatabase(
                source = source.copy(
                    titleId = TitleId(resolveImmersionTitleId(originTitleId.value)),
                ),
                originTitleId = originTitleId,
            )
        }

    override suspend fun appendExposure(event: ExposureEvent): PersistenceResult {
        event.requireSupportedActiveDuration()
        return handler.await(inTransaction = true) {
            appendExposureInDatabase(event)
        }
    }

    override suspend fun appendExposureBatch(events: List<ExposureEvent>): List<PersistenceResult> {
        if (events.isEmpty()) return emptyList()
        events.forEach(RecordedImmersionEvent::requireSupportedActiveDuration)
        return handler.await(inTransaction = true) {
            events.map { appendExposureInDatabase(it) }
        }
    }

    override suspend fun appendEventBatch(events: List<RecordedImmersionEvent>): List<PersistenceResult> {
        if (events.isEmpty()) return emptyList()
        events.forEach(RecordedImmersionEvent::requireSupportedActiveDuration)
        return handler.await(inTransaction = true) {
            events.map { event ->
                when (event) {
                    is ExposureEvent -> appendExposureInDatabase(event)
                    is SessionEvent -> appendSessionEventInDatabase(event)
                    is AnkiOperationEvent -> appendAnkiOperationInDatabase(event)
                }
            }
        }
    }

    override suspend fun finalizeSession(
        sessionId: SessionId,
        status: SessionStatus,
        endedAtEpochMillis: Long,
        elapsedDuration: MillisecondDuration,
    ): PersistenceResult {
        require(status == SessionStatus.COMPLETED || status == SessionStatus.ABANDONED) {
            "A finalized session must be completed or abandoned"
        }
        return handler.await(inTransaction = true) {
            val existing = immersionQueries.selectImmersionSessionById(sessionId.value).executeAsOneOrNull()
                ?: throw identityConflict("Session ${sessionId.value} does not exist")
            require(endedAtEpochMillis >= existing.started_at) { "Session end cannot precede its start" }
            immersionQueries.finalizeImmersionSession(
                status = status.name,
                endedAt = endedAtEpochMillis,
                elapsedDurationMs = elapsedDuration.value,
                lastHeartbeatAt = endedAtEpochMillis,
                id = sessionId.value,
            )
            if (immersionQueries.selectImmersionChanges().executeAsOne() == 1L) {
                markRollupDirty(
                    existing.started_at,
                    existing.start_offset_seconds.toIntExact("session offset"),
                    existing.title_id,
                    "SESSION_FINALIZED",
                )
                markRollupDirty(
                    endedAtEpochMillis,
                    existing.start_offset_seconds.toIntExact("session offset"),
                    existing.title_id,
                    "SESSION_FINALIZED",
                )
                immersionQueries.incrementImmersionRevision(endedAtEpochMillis)
                PersistenceResult.Applied
            } else if (
                existing.status == status.name &&
                existing.ended_at == endedAtEpochMillis &&
                existing.elapsed_duration_ms == elapsedDuration.value
            ) {
                PersistenceResult.AlreadyApplied
            } else {
                throw ImmersionDataException(
                    PersistenceErrorCode.SESSION_NOT_ACTIVE,
                    "Session ${sessionId.value} is not active",
                )
            }
        }
    }

    override suspend fun recoverAbandonedSessions(heartbeatCutoffEpochMillis: Long): Long {
        require(heartbeatCutoffEpochMillis >= 0) { "Heartbeat cutoff cannot be negative" }
        return handler.await(inTransaction = true) {
            val candidates = immersionQueries
                .selectImmersionRollupSessions(0, heartbeatCutoffEpochMillis)
                .executeAsList()
                .filter {
                    it.status == SessionStatus.ACTIVE.name &&
                        (it.last_heartbeat_at ?: it.started_at) < heartbeatCutoffEpochMillis
                }
            immersionQueries.recoverAbandonedImmersionSessions(heartbeatCutoffEpochMillis)
            val recovered = immersionQueries.selectImmersionChanges().executeAsOne()
            if (recovered > 0) {
                candidates.forEach {
                    markRollupDirty(
                        it.started_at,
                        it.start_offset_seconds.toIntExact("session offset"),
                        it.title_id,
                        "SESSION_RECOVERED",
                    )
                }
                immersionQueries.incrementImmersionRevision(heartbeatCutoffEpochMillis)
            }
            recovered
        }
    }

    override suspend fun sourceUnitExists(sourceUnitId: SourceUnitId): Boolean =
        handler.await {
            immersionQueries.selectImmersionSourceUnitById(sourceUnitId.value).executeAsOneOrNull() != null
        }

    override suspend fun getSession(sessionId: SessionId): ImmersionSession? =
        handler.await {
            immersionQueries.selectImmersionSessionById(sessionId.value).executeAsOneOrNull()?.toDomain()
        }

    override suspend fun claimWork(
        targetVersion: Int,
        limit: Int,
        nowEpochMillis: Long,
        request: ImmersionReindexRequest,
    ): List<IndexWorkItem> {
        require(targetVersion > 0) { "Target version must be positive" }
        require(limit in 1..MAX_PAGE_SIZE) { "Index claim limit must be between 1 and $MAX_PAGE_SIZE" }
        require(nowEpochMillis >= 0) { "Index claim timestamp cannot be negative" }
        return handler.await(inTransaction = true) {
            val rows = immersionQueries
                .selectImmersionIndexWork(
                    targetVersion = targetVersion.toLong(),
                    nowEpochMillis = nowEpochMillis,
                    titleId = request.titleId?.value,
                    exposedFrom = request.exposedFromEpochMillis,
                    exposedUntil = request.exposedUntilEpochMillis,
                    languageTag = request.languageTag?.value,
                    limit = limit.toLong(),
                )
                .executeAsList()
            if (rows.isNotEmpty()) {
                immersionQueries.markImmersionIndexWorkClaimed(
                    leaseExpiresAt = Math.addExact(nowEpochMillis, INDEX_WORK_LEASE_MILLIS),
                    ids = rows.map { it.id },
                )
            }
            rows.map(SelectImmersionIndexWork::toDomain)
        }
    }

    override suspend fun releaseClaims(work: List<IndexWorkItem>) {
        if (work.isEmpty()) return
        handler.await(inTransaction = true) {
            work.forEach { item ->
                immersionQueries.releaseImmersionSourceIndexClaim(
                    id = item.sourceUnitId.value,
                    claimGeneration = item.claimGeneration.toLong(),
                )
            }
        }
    }

    override suspend fun storeIndexResult(
        sourceUnitId: SourceUnitId,
        claimGeneration: Int,
        tokenizerId: String,
        normalizationVersion: Int,
        indexedVersion: Int,
        indexedAtEpochMillis: Long,
        terminalReason: IndexTerminalReason?,
        characters: List<IndexedCharacter>,
    ) {
        require(claimGeneration > 0) { "Index claim generation must be positive" }
        require(tokenizerId.isNotBlank()) { "Tokenizer ID cannot be blank" }
        require(normalizationVersion > 0) { "Normalization version must be positive" }
        require(indexedVersion > 0) { "Indexed version must be positive" }
        require(indexedAtEpochMillis >= 0) { "Indexed timestamp cannot be negative" }
        handler.await(inTransaction = true) {
            val source = immersionQueries.selectImmersionSourceUnitById(sourceUnitId.value).executeAsOneOrNull()
            if (source == null) {
                throw identityConflict("Source unit ${sourceUnitId.value} does not exist")
            }
            if (
                source.indexing_status != "IN_PROGRESS" ||
                source.index_attempt_count != claimGeneration.toLong()
            ) {
                throw identityConflict(
                    "Index claim $claimGeneration no longer owns source unit ${sourceUnitId.value}",
                )
            }
            val affectedCharacterCodePoints = linkedSetOf<Long>().apply {
                addAll(
                    immersionQueries
                        .selectImmersionCharacterCodePointsForSource(sourceUnitId.value)
                        .executeAsList(),
                )
                addAll(characters.map { it.codePoint.value.toLong() })
            }
            immersionQueries.deleteImmersionCharacterOccurrencesForSource(sourceUnitId.value)
            characters.forEach { character ->
                immersionQueries.upsertImmersionCharacter(
                    codePoint = character.codePoint.value.toLong(),
                    rendered = character.codePoint.asString(),
                    unicodeName = character.unicodeName,
                    unicodeCategory = character.unicodeCategory,
                    unicodeScript = character.unicodeScript,
                    firstSeenAt = source.first_exposed_at,
                    lastSeenAt = source.last_exposed_at,
                )
                immersionQueries.insertImmersionCharacterOccurrence(
                    characterCodePoint = character.codePoint.value.toLong(),
                    sourceUnitId = sourceUnitId.value,
                    occurrenceCount = character.occurrenceCount.value,
                    firstOrdinal = character.firstOrdinal,
                )
            }
            affectedCharacterCodePoints.chunked(IMMERSION_INDEX_ID_CHUNK_SIZE).forEach { codePoints ->
                immersionQueries.recomputeImmersionCharacterSeenTimesByCodePoints(codePoints)
                immersionQueries.deleteOrphanImmersionCharactersByCodePoints(codePoints)
            }
            val characterTotals = if (terminalReason == IndexTerminalReason.RAW_TEXT_UNAVAILABLE) {
                null
            } else {
                characters.groupingBy { character ->
                    when (character.unicodeScript) {
                        "HAN", "HIRAGANA", "KATAKANA", "HANGUL", "LATIN" -> character.unicodeScript
                        else -> "OTHER"
                    }
                }.fold(0L) { total, character ->
                    Math.addExact(total, character.occurrenceCount.value)
                }
            }
            immersionQueries.markImmersionSourceIndexed(
                tokenizerId = tokenizerId,
                normalizationVersion = normalizationVersion.toLong(),
                indexedVersion = indexedVersion.toLong(),
                terminalReason = terminalReason?.name,
                countableCharacters = characterTotals?.values?.sum(),
                hanCharacters = characterTotals?.get("HAN"),
                hiraganaCharacters = characterTotals?.get("HIRAGANA"),
                katakanaCharacters = characterTotals?.get("KATAKANA"),
                hangulCharacters = characterTotals?.get("HANGUL"),
                latinCharacters = characterTotals?.get("LATIN"),
                otherCharacters = characterTotals?.get("OTHER"),
                claimGeneration = claimGeneration.toLong(),
                id = sourceUnitId.value,
            )
            checkExactlyOneChange("marking source unit indexed")
            val affectedDates = buildSet {
                addAll(selectImmersionExposureDatesForSources(listOf(sourceUnitId.value)))
                addAll(
                    selectImmersionInventoryExposureDates(
                        ImmersionSourceBoundarySnapshot(
                            characterCodePoints = affectedCharacterCodePoints,
                        ),
                    ),
                )
            }
            affectedDates.forEach { date ->
                immersionQueries.upsertImmersionRollupDirty(
                    localDate = date.epochDay,
                    titleId = source.title_id,
                    reason = "INDEX",
                    updatedAt = indexedAtEpochMillis,
                )
            }
            immersionQueries.incrementImmersionRevision(indexedAtEpochMillis)
        }
    }

    override suspend fun markFailure(
        sourceUnitId: SourceUnitId,
        claimGeneration: Int,
        errorCode: String,
        nextAttemptAtEpochMillis: Long,
    ) {
        require(claimGeneration > 0) { "Index claim generation must be positive" }
        require(errorCode.isNotBlank()) { "Index error code cannot be blank" }
        require(nextAttemptAtEpochMillis >= 0) { "Next index attempt cannot be negative" }
        handler.await(inTransaction = true) {
            immersionQueries.markImmersionSourceIndexFailed(
                errorCode = errorCode,
                nextAttemptAt = nextAttemptAtEpochMillis,
                claimGeneration = claimGeneration.toLong(),
                id = sourceUnitId.value,
            )
        }
    }

    override suspend fun requeue(
        request: ImmersionReindexRequest,
        targetVersion: Int,
    ): Long {
        require(targetVersion > 0) { "Target version must be positive" }
        return handler.await(inTransaction = true) {
            immersionQueries.requeueImmersionSourceUnits(
                titleId = request.titleId?.value,
                exposedFrom = request.exposedFromEpochMillis,
                exposedUntil = request.exposedUntilEpochMillis,
                languageTag = request.languageTag?.value,
            )
            immersionQueries.selectImmersionChanges().executeAsOne()
        }
    }

    override suspend fun pendingCount(
        targetVersion: Int,
        request: ImmersionReindexRequest,
    ): Long {
        require(targetVersion > 0) { "Target version must be positive" }
        return handler.await {
            immersionQueries.countPendingImmersionSourceUnits(
                targetVersion = targetVersion.toLong(),
                titleId = request.titleId?.value,
                exposedFrom = request.exposedFromEpochMillis,
                exposedUntil = request.exposedUntilEpochMillis,
                languageTag = request.languageTag?.value,
            ).executeAsOne()
        }
    }

    suspend fun isIndexEntityExcluded(
        entityType: String,
        entityId: String,
        scopeKeys: Collection<String>,
    ): Boolean {
        require(entityType.isNotBlank()) { "Exclusion entity type cannot be blank" }
        require(entityId.isNotBlank()) { "Exclusion entity ID cannot be blank" }
        require(scopeKeys.isNotEmpty()) { "At least one exclusion scope is required" }
        return handler.await {
            immersionQueries.isImmersionIndexEntityExcluded(
                entityType = entityType,
                entityId = entityId,
                scopeKeys = scopeKeys,
            ).executeAsOne() > 0
        }
    }

    override suspend fun overview(): ImmersionOverview =
        handler.await {
            val row = immersionQueries.selectImmersionOverview().executeAsOne()
            mapCorruption("immersion overview") {
                ImmersionOverview(
                    activeDuration = MillisecondDuration(row.active_duration_ms),
                    grossCharacters = NonNegativeCounter(row.gross_characters),
                    uniqueSourceCharacters = NonNegativeCounter(row.unique_source_characters),
                    netCharacters = NetCharacterProgress(row.net_characters),
                    sourceUnits = NonNegativeCounter(row.source_units),
                    cardsCreated = NonNegativeCounter(row.cards_created),
                    cardsUpdated = NonNegativeCounter(row.cards_updated),
                    sessions = NonNegativeCounter(row.sessions),
                )
            }
        }

    override suspend fun sessionsPage(cursor: SessionCursor?, limit: Int): SessionPage {
        require(limit in 1..MAX_PAGE_SIZE) { "Session page limit must be between 1 and $MAX_PAGE_SIZE" }
        return handler.await {
            val rows = if (cursor == null) {
                immersionQueries.selectImmersionSessionsFirstPage(limit.toLong() + 1).executeAsList()
            } else {
                immersionQueries.selectImmersionSessionsAfter(
                    beforeStartedAt = cursor.startedAtEpochMillis,
                    beforeId = cursor.id.value,
                    limit = limit.toLong() + 1,
                ).executeAsList()
            }
            val hasNextPage = rows.size > limit
            val items = rows.take(limit).map(Immersion_session::toDomain)
            SessionPage(
                items = items,
                nextCursor = if (hasNextPage) {
                    items.last().let { SessionCursor(it.startedAtEpochMillis, it.id) }
                } else {
                    null
                },
            )
        }
    }

    override fun observeRevision(): Flow<Long> =
        handler.subscribeToOne { immersionQueries.selectImmersionRevision() }

    override suspend fun availableDateRange(
        filter: StatsFilter,
    ): tachiyomi.domain.immersion.model.LocalDateRange? =
        handler.await {
            val args = filter.sqlArgs()
            val row = immersionQueries.selectImmersionAnalyticsDateBounds(
                filterMediaKinds = args.filterMediaKinds,
                mediaKinds = args.mediaKinds,
                filterProfileIds = args.filterProfileIds,
                profileIds = args.profileIds,
                filterLanguageTags = args.filterLanguageTags,
                languageTags = args.languageTags,
                filterTitleIds = args.filterTitleIds,
                titleIds = args.titleIds,
                includeLegacy = filter.includeLegacyAggregates.toLong(),
                filterProvenance = args.filterProvenance,
                provenanceStates = args.provenanceStates,
                includeRereads = filter.includeRereadsAndReplays.toLong(),
            ).executeAsOne()
            val start = row.first_date ?: return@await null
            val end = row.last_date ?: return@await null
            tachiyomi.domain.immersion.model.LocalDateRange(
                ImmersionLocalDate(start),
                ImmersionLocalDate(end),
            )
        }

    override suspend fun dailyRollups(
        range: tachiyomi.domain.immersion.model.LocalDateRange,
    ): List<ImmersionDailyRollup> =
        handler.await {
            immersionQueries.selectImmersionDailyRollups(
                range.start.epochDay,
                range.endInclusive.epochDay,
            ).executeAsList().map(Immersion_daily_rollup::toDomain)
        }

    override suspend fun inventoryMetrics(filter: StatsFilter): AnalyticsInventoryMetrics =
        handler.await {
            val args = filter.sqlArgs()
            val range = filter.dateRange
            val accumulator = MutableInventoryMetrics()
            val query = immersionQueries.selectImmersionAnalyticsInventoryFacts(
                startDate = range?.start?.epochDay ?: Long.MIN_VALUE,
                endDate = range?.endInclusive?.epochDay ?: Long.MAX_VALUE,
                filterMediaKinds = args.filterMediaKinds,
                mediaKinds = args.mediaKinds,
                filterProfileIds = args.filterProfileIds,
                profileIds = args.profileIds,
                filterLanguageTags = args.filterLanguageTags,
                languageTags = args.languageTags,
                filterTitleIds = args.filterTitleIds,
                titleIds = args.titleIds,
                includeRereads = filter.includeRereadsAndReplays.toLong(),
                includeLegacy = filter.includeLegacyAggregates.toLong(),
                filterProvenance = args.filterProvenance,
                provenanceStates = args.provenanceStates,
                filterMaturity = args.filterMaturity,
                maturityAggregation = args.maturityAggregation,
                maturityTiers = args.maturityTiers,
            )
            query.execute { cursor ->
                while (cursor.next().value) {
                    val fact = query.mapper(cursor)
                    accumulator.add(
                        entityKind = fact.entity_kind,
                        entityKey = fact.entity_key,
                        globallyNew = fact.globally_new == 1L,
                        representedInAnki = fact.represented_in_anki == 1L,
                    )
                }
                QueryResult.Value(accumulator.toDomain())
            }.value
        }

    override suspend fun bucketInventoryMetrics(
        filter: StatsFilter,
        buckets: List<LocalDateRange>,
    ): List<AnalyticsBucketInventory> {
        if (buckets.isEmpty()) return emptyList()
        buckets.zipWithNext().forEach { (previous, next) ->
            require(previous.endInclusive < next.start) {
                "Inventory buckets must be ordered and non-overlapping"
            }
        }
        return handler.await {
            val args = filter.sqlArgs()
            var bucketIndex = 0
            var bucketAccumulator = MutableInventoryMetrics()
            val cumulativeAccumulator = MutableInventoryMetrics()
            val result = mutableListOf<AnalyticsBucketInventory>()
            val query = immersionQueries.selectImmersionAnalyticsInventoryFacts(
                startDate = buckets.first().start.epochDay,
                endDate = buckets.last().endInclusive.epochDay,
                filterMediaKinds = args.filterMediaKinds,
                mediaKinds = args.mediaKinds,
                filterProfileIds = args.filterProfileIds,
                profileIds = args.profileIds,
                filterLanguageTags = args.filterLanguageTags,
                languageTags = args.languageTags,
                filterTitleIds = args.filterTitleIds,
                titleIds = args.titleIds,
                includeRereads = filter.includeRereadsAndReplays.toLong(),
                includeLegacy = filter.includeLegacyAggregates.toLong(),
                filterProvenance = args.filterProvenance,
                provenanceStates = args.provenanceStates,
                filterMaturity = args.filterMaturity,
                maturityAggregation = args.maturityAggregation,
                maturityTiers = args.maturityTiers,
            )

            fun finishBucket() {
                result += AnalyticsBucketInventory(
                    metrics = bucketAccumulator.toDomain(),
                    cumulative = cumulativeAccumulator.toDomain(),
                )
                bucketAccumulator = MutableInventoryMetrics()
                bucketIndex += 1
            }

            query.execute { cursor ->
                while (cursor.next().value) {
                    val fact = query.mapper(cursor)
                    val date = ImmersionLocalDate(fact.local_date)
                    while (
                        bucketIndex < buckets.size &&
                        date > buckets[bucketIndex].endInclusive
                    ) {
                        finishBucket()
                    }
                    if (bucketIndex >= buckets.size) break
                    if (date < buckets[bucketIndex].start) continue

                    bucketAccumulator.add(
                        entityKind = fact.entity_kind,
                        entityKey = fact.entity_key,
                        globallyNew = fact.globally_new == 1L,
                        representedInAnki = fact.represented_in_anki == 1L,
                    )
                    cumulativeAccumulator.add(
                        entityKind = fact.entity_kind,
                        entityKey = fact.entity_key,
                        globallyNew = fact.globally_new == 1L,
                        representedInAnki = fact.represented_in_anki == 1L,
                    )
                }
                while (bucketIndex < buckets.size) finishBucket()
                QueryResult.Value(result)
            }.value
        }
    }

    override suspend fun titleInventoryMetrics(
        filter: StatsFilter,
    ): Map<TitleId, AnalyticsInventoryMetrics> =
        handler.await {
            val args = filter.sqlArgs()
            val range = filter.dateRange
            val accumulators = linkedMapOf<TitleId, MutableInventoryMetrics>()
            val query = immersionQueries.selectImmersionAnalyticsInventoryFacts(
                startDate = range?.start?.epochDay ?: Long.MIN_VALUE,
                endDate = range?.endInclusive?.epochDay ?: Long.MAX_VALUE,
                filterMediaKinds = args.filterMediaKinds,
                mediaKinds = args.mediaKinds,
                filterProfileIds = args.filterProfileIds,
                profileIds = args.profileIds,
                filterLanguageTags = args.filterLanguageTags,
                languageTags = args.languageTags,
                filterTitleIds = args.filterTitleIds,
                titleIds = args.titleIds,
                includeRereads = filter.includeRereadsAndReplays.toLong(),
                includeLegacy = filter.includeLegacyAggregates.toLong(),
                filterProvenance = args.filterProvenance,
                provenanceStates = args.provenanceStates,
                filterMaturity = args.filterMaturity,
                maturityAggregation = args.maturityAggregation,
                maturityTiers = args.maturityTiers,
            )
            query.execute { cursor ->
                while (cursor.next().value) {
                    val fact = query.mapper(cursor)
                    accumulators.getOrPut(TitleId(fact.title_id), ::MutableInventoryMetrics).add(
                        entityKind = fact.entity_kind,
                        entityKey = fact.entity_key,
                        globallyNew = fact.globally_new == 1L,
                        representedInAnki = fact.represented_in_anki == 1L,
                    )
                }
                QueryResult.Value(
                    accumulators.mapValues { (_, accumulator) -> accumulator.toDomain() },
                )
            }.value
        }

    override suspend fun titleMetadata(
        titleIds: Set<TitleId>,
    ): List<AnalyticsTitleMetadata> {
        if (titleIds.isEmpty()) return emptyList()
        return handler.await {
            immersionQueries.selectImmersionAnalyticsTitleMetadata(titleIds.map(TitleId::value))
                .executeAsList()
                .map { row ->
                    AnalyticsTitleMetadata(
                        titleId = TitleId(row.id),
                        displayTitle = row.display_title,
                        mediaKind = MediaKind.valueOf(row.media_kind),
                        sourceKey = row.source_key,
                        profileId = row.profile_id,
                        languageTag = row.language_tag?.let(::LanguageTag),
                        libraryId = row.library_id,
                        trackerId = row.tracker_id,
                        mediaId = row.media_id,
                        status = row.status,
                        totalUnits = row.total_units,
                        totalCharacterEstimate = row.total_character_estimate,
                        completed = when (row.completed) {
                            1L -> true
                            0L -> false
                            else -> null
                        },
                        deletedAtEpochMillis = row.deleted_at,
                    )
                }
        }
    }

    override suspend fun titleCoverage(
        filter: StatsFilter,
    ): Map<TitleId, AnalyticsTitleCoverage> =
        handler.await {
            val args = filter.sqlArgs()
            val range = filter.dateRange
            immersionQueries.selectImmersionAnalyticsTitleCoverage(
                filterDate = (range != null).toLong(),
                startDate = range?.start?.epochDay ?: 0,
                endDate = range?.endInclusive?.epochDay ?: 0,
                filterMediaKinds = args.filterMediaKinds,
                mediaKinds = args.mediaKinds,
                filterProfileIds = args.filterProfileIds,
                profileIds = args.profileIds,
                filterLanguageTags = args.filterLanguageTags,
                languageTags = args.languageTags,
                filterTitleIds = args.filterTitleIds,
                titleIds = args.titleIds,
                includeLegacy = filter.includeLegacyAggregates.toLong(),
                filterProvenance = args.filterProvenance,
                provenanceStates = args.provenanceStates,
            ).executeAsList().associate { row ->
                TitleId(row.title_id) to AnalyticsTitleCoverage(
                    legacySessionCount = row.legacy_sessions,
                    eventBackedSessionCount = row.event_sessions,
                    sourceUnitCount = row.source_units,
                    indexedSourceUnitCount = row.indexed_source_units,
                    textAvailableSourceUnitCount = row.text_source_units,
                    ocrSourceUnitCount = row.ocr_source_units,
                    ocrTextAvailableSourceUnitCount = row.ocr_text_source_units,
                )
            }
        }

    override suspend fun titleNetProgress(
        filter: StatsFilter,
    ): Map<TitleId, NetCharacterProgress> =
        handler.await {
            val args = filter.sqlArgs()
            immersionQueries.selectImmersionAnalyticsTitleNetProgress(
                filterMediaKinds = args.filterMediaKinds,
                mediaKinds = args.mediaKinds,
                filterProfileIds = args.filterProfileIds,
                profileIds = args.profileIds,
                filterLanguageTags = args.filterLanguageTags,
                languageTags = args.languageTags,
                filterTitleIds = args.filterTitleIds,
                titleIds = args.titleIds,
                includeLegacy = filter.includeLegacyAggregates.toLong(),
                includeRereads = filter.includeRereadsAndReplays.toLong(),
                filterProvenance = args.filterProvenance,
                provenanceStates = args.provenanceStates,
            ).executeAsList().associate { row ->
                TitleId(row.title_id) to NetCharacterProgress(row.net_characters ?: 0)
            }
        }

    override suspend fun titleUnitProgress(
        filter: StatsFilter,
    ): Map<TitleId, AnalyticsTitleUnitProgress> =
        handler.await {
            val args = filter.sqlArgs()
            immersionQueries.selectImmersionAnalyticsTitleUnitCompletions(
                filterMediaKinds = args.filterMediaKinds,
                mediaKinds = args.mediaKinds,
                filterProfileIds = args.filterProfileIds,
                profileIds = args.profileIds,
                filterLanguageTags = args.filterLanguageTags,
                languageTags = args.languageTags,
                filterTitleIds = args.filterTitleIds,
                titleIds = args.titleIds,
                includeLegacy = filter.includeLegacyAggregates.toLong(),
                filterProvenance = args.filterProvenance,
                provenanceStates = args.provenanceStates,
            ).executeAsList()
                .groupBy { TitleId(it.title_id) }
                .mapValues { (_, rows) ->
                    val identified = rows.filter { it.completion_unit_id != null }
                    val byDay = identified
                        .groupingBy {
                            ImmersionLocalDate(
                                checkNotNull(it.first_completion_date) {
                                    "Identified completion has no first-completion date"
                                },
                            )
                        }
                        .eachCount()
                        .entries
                        .sortedBy { it.key }
                        .map { (date, count) ->
                            AnalyticsTitleUnitCompletionDay(date, count.toLong())
                        }
                    AnalyticsTitleUnitProgress(
                        identityAvailable = true,
                        completedUnits = identified.size.toLong(),
                        identifiedCompletionEvents = identified.sumOf { it.completion_events },
                        unidentifiedCompletionEvents = rows
                            .filter { it.completion_unit_id == null }
                            .sumOf { it.completion_events },
                        firstCompletionsByDay = byDay,
                    )
                }
        }

    override suspend fun titleCompletedUnits(
        filter: StatsFilter,
        offset: Long,
        limit: Int,
    ): AnalyticsPage<AnalyticsTitleCompletedUnit> {
        require(offset >= 0)
        require(limit in 1..MAX_PAGE_SIZE)
        return handler.await {
            val args = filter.sqlArgs()
            val range = filter.dateRange
            val rows = immersionQueries.selectImmersionAnalyticsTitleCompletedUnits(
                filterDate = (range != null).toLong(),
                startDate = range?.start?.epochDay ?: 0,
                endDate = range?.endInclusive?.epochDay ?: 0,
                filterMediaKinds = args.filterMediaKinds,
                mediaKinds = args.mediaKinds,
                filterProfileIds = args.filterProfileIds,
                profileIds = args.profileIds,
                filterLanguageTags = args.filterLanguageTags,
                languageTags = args.languageTags,
                filterTitleIds = args.filterTitleIds,
                titleIds = args.titleIds,
                includeLegacy = filter.includeLegacyAggregates.toLong(),
                filterProvenance = args.filterProvenance,
                provenanceStates = args.provenanceStates,
                limit = limit.toLong() + 1,
                offset = offset,
            ).executeAsList()
            val hasNext = rows.size > limit
            AnalyticsPage(
                items = rows.take(limit).map { row ->
                    AnalyticsTitleCompletedUnit(
                        titleId = TitleId(row.title_id),
                        completionUnitId = checkNotNull(row.completion_unit_id),
                        firstCompletedAtEpochMillis = checkNotNull(row.first_completed_at),
                        lastCompletedAtEpochMillis = checkNotNull(row.last_completed_at),
                        firstCompletedDate = ImmersionLocalDate(checkNotNull(row.first_completed_date)),
                        completionEventCount = row.completion_events,
                    )
                },
                nextOffset = if (hasNext) Math.addExact(offset, limit.toLong()) else null,
            )
        }
    }

    override suspend fun temporalActivity(filter: StatsFilter): AnalyticsTemporalActivity =
        handler.await {
            val args = filter.sqlArgs()
            val range = filter.dateRange
            val hours = immersionQueries.selectImmersionAnalyticsHourActivity(
                filterDate = (range != null).toLong(),
                startDate = range?.start?.epochDay ?: 0,
                endDate = range?.endInclusive?.epochDay ?: 0,
                filterMediaKinds = args.filterMediaKinds,
                mediaKinds = args.mediaKinds,
                filterProfileIds = args.filterProfileIds,
                profileIds = args.profileIds,
                filterLanguageTags = args.filterLanguageTags,
                languageTags = args.languageTags,
                filterTitleIds = args.filterTitleIds,
                titleIds = args.titleIds,
                includeRereads = filter.includeRereadsAndReplays.toLong(),
                includeLegacy = filter.includeLegacyAggregates.toLong(),
                filterProvenance = args.filterProvenance,
                provenanceStates = args.provenanceStates,
            ).executeAsList().map { row ->
                AnalyticsHourActivity(
                    hourOfDay = row.bucket_index.toIntExact("hour-of-day bucket"),
                    totals = AnalyticsActivityTotals(
                        activeDurationMillis = row.active_duration_ms ?: 0,
                        grossCharacters = row.gross_characters ?: 0,
                        uniqueSourceCharacters = row.unique_source_characters ?: 0,
                        netCharacters = row.net_characters ?: 0,
                    ),
                )
            }
            val weekdays = immersionQueries.selectImmersionAnalyticsWeekdayActivity(
                filterDate = (range != null).toLong(),
                startDate = range?.start?.epochDay ?: 0,
                endDate = range?.endInclusive?.epochDay ?: 0,
                filterMediaKinds = args.filterMediaKinds,
                mediaKinds = args.mediaKinds,
                filterProfileIds = args.filterProfileIds,
                profileIds = args.profileIds,
                filterLanguageTags = args.filterLanguageTags,
                languageTags = args.languageTags,
                filterTitleIds = args.filterTitleIds,
                titleIds = args.titleIds,
                includeLegacy = filter.includeLegacyAggregates.toLong(),
                filterProvenance = args.filterProvenance,
                provenanceStates = args.provenanceStates,
                includeRereads = filter.includeRereadsAndReplays.toLong(),
            ).executeAsList().map { row ->
                AnalyticsWeekdayActivity(
                    isoDayOfWeek = row.bucket_index.toIntExact("weekday bucket"),
                    totals = AnalyticsActivityTotals(
                        activeDurationMillis = row.active_duration_ms ?: 0,
                        grossCharacters = row.gross_characters ?: 0,
                        uniqueSourceCharacters = row.unique_source_characters ?: 0,
                        netCharacters = row.net_characters ?: 0,
                    ),
                )
            }
            AnalyticsTemporalActivity(hours, weekdays)
        }

    override suspend fun titleTrendDaily(
        filter: StatsFilter,
        selection: AnalyticsTitleSeriesSelection,
        limit: Int,
    ): List<AnalyticsTitleTrendDailyPoint> {
        require(limit in 1..MAX_TITLE_TREND_SERIES)
        return handler.await {
            val args = filter.sqlArgs()
            val range = filter.dateRange
            immersionQueries.selectImmersionAnalyticsTitleTrendDaily(
                filterDate = (range != null).toLong(),
                startDate = range?.start?.epochDay ?: 0,
                endDate = range?.endInclusive?.epochDay ?: 0,
                filterMediaKinds = args.filterMediaKinds,
                mediaKinds = args.mediaKinds,
                filterProfileIds = args.filterProfileIds,
                profileIds = args.profileIds,
                filterLanguageTags = args.filterLanguageTags,
                languageTags = args.languageTags,
                filterTitleIds = args.filterTitleIds,
                titleIds = args.titleIds,
                includeLegacy = filter.includeLegacyAggregates.toLong(),
                filterProvenance = args.filterProvenance,
                provenanceStates = args.provenanceStates,
                includeRereads = filter.includeRereadsAndReplays.toLong(),
                characterMetric = filter.characterMetric.name,
                selection = selection.name,
                limit = limit.toLong(),
            ).executeAsList().map { row ->
                AnalyticsTitleTrendDailyPoint(
                    titleId = TitleId(row.title_id),
                    displayTitle = row.display_title,
                    mediaKind = MediaKind.valueOf(row.media_kind),
                    languageTag = row.language_tag?.let(::LanguageTag),
                    date = ImmersionLocalDate(row.local_date),
                    metrics = ReadingMetrics(
                        activeTime = MillisecondDuration(row.active_duration_ms ?: 0),
                        characters = CharacterVolume(
                            gross = NonNegativeCounter(row.gross_characters ?: 0),
                            uniqueSource = NonNegativeCounter(row.unique_source_characters ?: 0),
                            netProgress = NetCharacterProgress(row.net_characters ?: 0),
                        ),
                        sourceUnits = NonNegativeCounter(row.source_units ?: 0),
                        sessions = NonNegativeCounter(row.sessions ?: 0),
                        cardsCreated = NonNegativeCounter(row.cards_created ?: 0),
                        cardsUpdated = NonNegativeCounter(row.cards_updated ?: 0),
                    ),
                )
            }
        }
    }

    override suspend fun dataQuality(
        filter: StatsFilter,
        nowEpochMillis: Long,
    ): AnalyticsDataQuality {
        require(nowEpochMillis >= 0)
        return handler.await {
            val args = filter.sqlArgs()
            val range = filter.dateRange
            val row = immersionQueries.selectImmersionAnalyticsQuality(
                filterDate = (range != null).toLong(),
                startDate = range?.start?.epochDay ?: 0,
                endDate = range?.endInclusive?.epochDay ?: 0,
                filterMediaKinds = args.filterMediaKinds,
                mediaKinds = args.mediaKinds,
                filterProfileIds = args.filterProfileIds,
                profileIds = args.profileIds,
                filterLanguageTags = args.filterLanguageTags,
                languageTags = args.languageTags,
                filterTitleIds = args.filterTitleIds,
                titleIds = args.titleIds,
                includeLegacy = filter.includeLegacyAggregates.toLong(),
                filterProvenance = args.filterProvenance,
                provenanceStates = args.provenanceStates,
            ).executeAsOne()
            val snapshot = filter.profileIds.singleOrNull()?.let { profileId ->
                immersionQueries.selectCurrentImmersionAnkiSnapshot(profileId)
                    .executeAsOneOrNull()
                    ?.toDomain()
            }
            val provenance = when {
                row.legacy_sessions > 0 && row.event_sessions > 0 -> ProvenanceState.PARTIAL
                row.legacy_sessions > 0 -> ProvenanceState.LEGACY_AGGREGATE
                row.source_units == 0L -> ProvenanceState.UNAVAILABLE
                row.text_source_units == 0L -> ProvenanceState.REMOVED
                row.text_source_units < row.source_units -> ProvenanceState.PARTIAL
                else -> ProvenanceState.AVAILABLE
            }
            AnalyticsDataQuality(
                legacySessionCount = row.legacy_sessions,
                eventBackedSessionCount = row.event_sessions,
                sourceUnitCount = row.source_units,
                indexedSourceUnitCount = row.indexed_source_units,
                textAvailableSourceUnitCount = row.text_source_units,
                ocrSourceUnitCount = row.ocr_source_units,
                ocrTextAvailableSourceUnitCount = row.ocr_text_source_units,
                ankiState = snapshot?.let {
                    if (it.isStale) tachiyomi.domain.immersion.model.CapabilityState.STALE else it.capabilityState
                } ?: tachiyomi.domain.immersion.model.CapabilityState.UNAVAILABLE,
                ankiSnapshotAgeMillis = snapshot?.completedAtEpochMillis?.let {
                    (nowEpochMillis - it).coerceAtLeast(0)
                },
                provenanceState = provenance,
            )
        }
    }

    override suspend fun characterPage(
        filter: StatsFilter,
        sort: AnalyticsSort,
        offset: Long,
        limit: Int,
        searchQuery: String?,
        characterFilter: AnalyticsCharacterFilter,
    ): AnalyticsPage<AnalyticsCharacterRow> {
        require(offset >= 0)
        require(limit in 1..MAX_PAGE_SIZE)
        return handler.await {
            val args = filter.sqlArgs()
            val range = filter.dateRange
            val rows = immersionQueries.selectImmersionAnalyticsCharacters(
                filterDate = (range != null).toLong(),
                startDate = range?.start?.epochDay ?: 0,
                endDate = range?.endInclusive?.epochDay ?: 0,
                filterMediaKinds = args.filterMediaKinds,
                mediaKinds = args.mediaKinds,
                filterProfileIds = args.filterProfileIds,
                profileIds = args.profileIds,
                filterLanguageTags = args.filterLanguageTags,
                languageTags = args.languageTags,
                filterTitleIds = args.filterTitleIds,
                titleIds = args.titleIds,
                includeRereads = filter.includeRereadsAndReplays.toLong(),
                includeLegacy = filter.includeLegacyAggregates.toLong(),
                filterProvenance = args.filterProvenance,
                provenanceStates = args.provenanceStates,
                filterMaturity = args.filterMaturity,
                maturityAggregation = args.maturityAggregation,
                maturityTiers = args.maturityTiers,
                searchQuery = searchQuery?.trim().orEmpty(),
                filterCharacterScripts = characterFilter.scripts.isNotEmpty().toLong(),
                characterScripts = characterFilter.scripts
                    .map(AnalyticsCharacterScript::name)
                    .ifEmpty { listOf("") },
                firstSeenInRange =
                (characterFilter.range == AnalyticsCharacterRange.FIRST_SEEN_IN_RANGE).toLong(),
                missingHighFrequency =
                (characterFilter.range == AnalyticsCharacterRange.MISSING_HIGH_FREQUENCY).toLong(),
                maximumMissingFrequencyRank = characterFilter.maximumMissingFrequencyRank,
                priorityMode = characterFilter.priorityMode.name,
                sort = sort.analyticsSqlSort(),
                limit = limit.toLong() + 1,
                offset = offset,
            ).executeAsList()
            val hasNext = rows.size > limit
            AnalyticsPage(
                items = rows.take(limit).map { row ->
                    AnalyticsCharacterRow(
                        codePoint = UnicodeCodePoint(row.code_point.toIntExact("character code point")),
                        rendered = row.rendered,
                        unicodeName = row.unicode_name,
                        unicodeCategory = row.unicode_category,
                        unicodeScript = row.unicode_script,
                        japaneseReadings = row.japanese_readings,
                        occurrenceCount = row.occurrence_count ?: 0,
                        sourceUnitCount = row.source_unit_count,
                        titleCount = row.title_count,
                        firstSeenAtEpochMillis = row.first_seen_at,
                        lastSeenAtEpochMillis = row.last_seen_at,
                        frequencyRank = row.frequency_rank,
                        jlptLevel = row.jlpt_level?.toIntExact("character JLPT level"),
                        gradeLevel = row.grade_level?.toIntExact("character grade level"),
                        maturity = MaturityTier.valueOf(row.maturity_tier),
                        priorityScore = row.priority_score,
                    )
                },
                nextOffset = if (hasNext) Math.addExact(offset, limit.toLong()) else null,
            )
        }
    }

    override suspend fun characterSummary(
        filter: StatsFilter,
        characterFilter: AnalyticsCharacterFilter,
    ): AnalyticsCharacterSummary = handler.await {
        val args = filter.sqlArgs()
        val range = filter.dateRange
        val rows = immersionQueries.selectImmersionAnalyticsCharacterSummary(
            filterDate = (range != null).toLong(),
            startDate = range?.start?.epochDay ?: 0,
            endDate = range?.endInclusive?.epochDay ?: 0,
            filterMediaKinds = args.filterMediaKinds,
            mediaKinds = args.mediaKinds,
            filterProfileIds = args.filterProfileIds,
            profileIds = args.profileIds,
            filterLanguageTags = args.filterLanguageTags,
            languageTags = args.languageTags,
            filterTitleIds = args.filterTitleIds,
            titleIds = args.titleIds,
            includeRereads = filter.includeRereadsAndReplays.toLong(),
            includeLegacy = filter.includeLegacyAggregates.toLong(),
            filterProvenance = args.filterProvenance,
            provenanceStates = args.provenanceStates,
            filterMaturity = args.filterMaturity,
            maturityAggregation = args.maturityAggregation,
            maturityTiers = args.maturityTiers,
            filterCharacterScripts = characterFilter.scripts.isNotEmpty().toLong(),
            characterScripts = characterFilter.scripts
                .map(AnalyticsCharacterScript::name)
                .ifEmpty { listOf("") },
            firstSeenInRange =
            (characterFilter.range == AnalyticsCharacterRange.FIRST_SEEN_IN_RANGE).toLong(),
            missingHighFrequency =
            (characterFilter.range == AnalyticsCharacterRange.MISSING_HIGH_FREQUENCY).toLong(),
            maximumMissingFrequencyRank = characterFilter.maximumMissingFrequencyRank,
        ).executeAsList()
        AnalyticsCharacterSummary(
            scripts = rows.map { row ->
                AnalyticsCharacterScriptSummary(
                    script = AnalyticsCharacterScript.valueOf(row.unicode_script),
                    distinctCharacters = row.distinct_character_count,
                    grossOccurrenceExposure = row.gross_occurrence_exposure,
                    representedInAnki = row.represented_in_anki ?: 0,
                    matureInAnki = row.mature_in_anki ?: 0,
                )
            },
            firstSeenInRange = rows.sumOf { it.first_seen_in_range ?: 0 },
            maximumOccurrenceCount = rows.maxOfOrNull { it.maximum_occurrence_count } ?: 0,
        )
    }

    override suspend fun filteredSessionsPage(
        filter: StatsFilter,
        cursor: SessionCursor?,
        limit: Int,
    ): SessionPage {
        require(limit in 1..MAX_PAGE_SIZE)
        return handler.await {
            val args = filter.sqlArgs()
            val range = filter.dateRange
            val commonLimit = limit.toLong() + 1
            val rows = if (cursor == null) {
                immersionQueries.selectImmersionAnalyticsSessionsFirstPage(
                    filterDate = (range != null).toLong(),
                    startDate = range?.start?.epochDay ?: 0,
                    endDate = range?.endInclusive?.epochDay ?: 0,
                    filterMediaKinds = args.filterMediaKinds,
                    mediaKinds = args.mediaKinds,
                    filterProfileIds = args.filterProfileIds,
                    profileIds = args.profileIds,
                    filterLanguageTags = args.filterLanguageTags,
                    languageTags = args.languageTags,
                    filterTitleIds = args.filterTitleIds,
                    titleIds = args.titleIds,
                    includeLegacy = filter.includeLegacyAggregates.toLong(),
                    filterProvenance = args.filterProvenance,
                    provenanceStates = args.provenanceStates,
                    limit = commonLimit,
                ).executeAsList()
            } else {
                immersionQueries.selectImmersionAnalyticsSessionsAfter(
                    beforeStartedAt = cursor.startedAtEpochMillis,
                    beforeId = cursor.id.value,
                    filterDate = (range != null).toLong(),
                    startDate = range?.start?.epochDay ?: 0,
                    endDate = range?.endInclusive?.epochDay ?: 0,
                    filterMediaKinds = args.filterMediaKinds,
                    mediaKinds = args.mediaKinds,
                    filterProfileIds = args.filterProfileIds,
                    profileIds = args.profileIds,
                    filterLanguageTags = args.filterLanguageTags,
                    languageTags = args.languageTags,
                    filterTitleIds = args.filterTitleIds,
                    titleIds = args.titleIds,
                    includeLegacy = filter.includeLegacyAggregates.toLong(),
                    filterProvenance = args.filterProvenance,
                    provenanceStates = args.provenanceStates,
                    limit = commonLimit,
                ).executeAsList()
            }
            val hasNext = rows.size > limit
            val items = rows.take(limit).map(Immersion_session::toDomain)
            SessionPage(
                items = items,
                nextCursor = if (hasNext) {
                    items.last().let { SessionCursor(it.startedAtEpochMillis, it.id) }
                } else {
                    null
                },
            )
        }
    }

    override suspend fun sessionDetail(
        sessionId: SessionId,
        maxTimelineBuckets: Int,
    ): AnalyticsSessionDetail? {
        require(maxTimelineBuckets in 1..500)
        return handler.await {
            val session = immersionQueries.selectImmersionSessionById(sessionId.value)
                .executeAsOneOrNull()
                ?.toDomain()
                ?: return@await null
            val title = immersionQueries.selectImmersionAnalyticsTitleMetadata(
                listOf(session.titleId.value),
            ).executeAsOneOrNull() ?: return@await null
            val events = immersionQueries.selectImmersionAnalyticsSessionEvents(sessionId.value)
                .executeAsList()
            val end = session.endedAtEpochMillis
                ?: events.lastOrNull()?.occurred_at
                ?: session.startedAtEpochMillis
            val span = (end - session.startedAtEpochMillis + 1).coerceAtLeast(1)
            val bucketWidth = (span + maxTimelineBuckets - 1) / maxTimelineBuckets
            val bucketCount = ((span + bucketWidth - 1) / bucketWidth)
                .coerceIn(1, maxTimelineBuckets.toLong())
                .toInt()
            val buckets = linkedMapOf<Long, TimelineAccumulator>()
            events.forEach { event ->
                val bucketIndex = ((event.occurred_at - session.startedAtEpochMillis) / bucketWidth)
                    .coerceIn(0, bucketCount.toLong() - 1)
                buckets.getOrPut(bucketIndex) { TimelineAccumulator() }.apply {
                    eventCount++
                    activeDuration = Math.addExact(activeDuration, event.active_duration_delta_ms)
                    grossCharacters = Math.addExact(grossCharacters, event.gross_character_delta)
                    uniqueSourceCharacters = Math.addExact(
                        uniqueSourceCharacters,
                        event.unique_source_character_delta,
                    )
                    netCharacters = Math.addExact(netCharacters, event.net_character_delta)
                    cardsCreated = Math.addExact(cardsCreated, event.cards_created_delta)
                    cardsUpdated = Math.addExact(cardsUpdated, event.cards_updated_delta)
                    eventTypes += EventType.valueOf(event.type)
                }
            }
            val timeline = (0 until bucketCount).map { bucketIndex ->
                val index = bucketIndex.toLong()
                val bucketStart = session.startedAtEpochMillis + index * bucketWidth
                buckets.getOrElse(index, ::TimelineAccumulator)
                    .toDomain(bucketStart, minOf(end, bucketStart + bucketWidth - 1))
            }
            val sources = events.mapNotNull { event ->
                val sourceUnitId = event.source_unit_id ?: return@mapNotNull null
                val sourceKind = event.source_kind ?: return@mapNotNull null
                val locator = event.canonical_locator ?: return@mapNotNull null
                AnalyticsSourceOccurrence(
                    sourceUnitId = SourceUnitId(sourceUnitId),
                    titleId = session.titleId,
                    displayTitle = title.display_title,
                    sessionId = session.id,
                    mediaKind = session.mediaKind,
                    sourceKind = SourceKind.valueOf(sourceKind),
                    canonicalLocator = locator,
                    occurredAtEpochMillis = event.occurred_at,
                    excerpt = event.raw_text?.decodeUtf8Strict()?.take(SOURCE_EXCERPT_LENGTH),
                    rawTextAvailable = event.raw_text_available,
                )
            }.distinctBy { it.sourceUnitId }
            AnalyticsSessionDetail(
                session = session,
                displayTitle = title.display_title,
                timeline = timeline,
                sources = sources,
            )
        }
    }

    override suspend fun sourceOccurrences(
        filter: StatsFilter,
        offset: Long,
        limit: Int,
    ): AnalyticsPage<AnalyticsSourceOccurrence> {
        require(offset >= 0)
        require(limit in 1..MAX_PAGE_SIZE)
        return handler.await {
            val args = filter.sqlArgs()
            val range = filter.dateRange
            val rows = immersionQueries.selectImmersionAnalyticsSourceOccurrences(
                filterDate = (range != null).toLong(),
                startDate = range?.start?.epochDay ?: 0,
                endDate = range?.endInclusive?.epochDay ?: 0,
                filterMediaKinds = args.filterMediaKinds,
                mediaKinds = args.mediaKinds,
                filterProfileIds = args.filterProfileIds,
                profileIds = args.profileIds,
                filterLanguageTags = args.filterLanguageTags,
                languageTags = args.languageTags,
                filterTitleIds = args.filterTitleIds,
                titleIds = args.titleIds,
                includeRereads = filter.includeRereadsAndReplays.toLong(),
                includeLegacy = filter.includeLegacyAggregates.toLong(),
                filterProvenance = args.filterProvenance,
                provenanceStates = args.provenanceStates,
                limit = limit.toLong() + 1,
                offset = offset,
            ).executeAsList()
            val hasNext = rows.size > limit
            AnalyticsPage(
                items = rows.take(limit).map { row ->
                    AnalyticsSourceOccurrence(
                        sourceUnitId = SourceUnitId(row.source_unit_id),
                        titleId = TitleId(row.title_id),
                        displayTitle = row.display_title,
                        sessionId = SessionId(row.session_id),
                        mediaKind = MediaKind.valueOf(row.media_kind),
                        sourceKind = SourceKind.valueOf(row.source_kind),
                        canonicalLocator = row.canonical_locator,
                        occurredAtEpochMillis = checkNotNull(row.occurred_at),
                        excerpt = row.excerpt,
                        rawTextAvailable = row.raw_text_available,
                    )
                },
                nextOffset = if (hasNext) Math.addExact(offset, limit.toLong()) else null,
            )
        }
    }

    override suspend fun characterOccurrences(
        filter: StatsFilter,
        codePoint: UnicodeCodePoint,
        offset: Long,
        limit: Int,
    ): AnalyticsPage<AnalyticsSourceOccurrence> {
        require(offset >= 0)
        require(limit in 1..MAX_PAGE_SIZE)
        return handler.await {
            val args = filter.sqlArgs()
            val range = filter.dateRange
            val rows = immersionQueries.selectImmersionAnalyticsCharacterOccurrences(
                codePoint = codePoint.value.toLong(),
                filterDate = (range != null).toLong(),
                startDate = range?.start?.epochDay ?: 0,
                endDate = range?.endInclusive?.epochDay ?: 0,
                filterMediaKinds = args.filterMediaKinds,
                mediaKinds = args.mediaKinds,
                filterProfileIds = args.filterProfileIds,
                profileIds = args.profileIds,
                filterLanguageTags = args.filterLanguageTags,
                languageTags = args.languageTags,
                filterTitleIds = args.filterTitleIds,
                titleIds = args.titleIds,
                includeRereads = filter.includeRereadsAndReplays.toLong(),
                includeLegacy = filter.includeLegacyAggregates.toLong(),
                limit = limit.toLong() + 1,
                offset = offset,
            ).executeAsList()
            val hasNext = rows.size > limit
            AnalyticsPage(
                items = rows.take(limit).map { row ->
                    AnalyticsSourceOccurrence(
                        sourceUnitId = SourceUnitId(row.source_unit_id),
                        titleId = TitleId(row.title_id),
                        displayTitle = row.display_title,
                        sessionId = SessionId(row.session_id),
                        mediaKind = MediaKind.valueOf(row.media_kind),
                        sourceKind = SourceKind.valueOf(row.source_kind),
                        canonicalLocator = row.canonical_locator,
                        occurredAtEpochMillis = checkNotNull(row.occurred_at),
                        excerpt = row.raw_text?.decodeUtf8Strict()?.take(SOURCE_EXCERPT_LENGTH),
                        rawTextAvailable = row.raw_text_available,
                    )
                },
                nextOffset = if (hasNext) Math.addExact(offset, limit.toLong()) else null,
            )
        }
    }

    override suspend fun ankiSummary(filter: StatsFilter): AnalyticsAnkiSummary =
        handler.await {
            val profileId = filter.profileIds.singleOrNull()
            val languageTag = filter.languageTags.singleOrNull()
            val args = filter.sqlArgs()
            val range = filter.dateRange
            val snapshot = profileId
                ?.let(immersionQueries::selectCurrentImmersionAnkiSnapshot)
                ?.executeAsOneOrNull()
                ?.toDomain()
            val cacheKey = AnkiSummaryCacheKey(
                filter = filter,
                revision = immersionQueries.selectImmersionRevision().executeAsOne(),
                snapshotId = snapshot?.id,
            )
            synchronized(ankiSummaryCache) {
                ankiSummaryCache[cacheKey]
            }?.let { return@await it }
            val activity = immersionQueries.selectImmersionAnalyticsAnkiActivity(
                filterDate = (range != null).toLong(),
                startDate = range?.start?.epochDay ?: 0,
                endDate = range?.endInclusive?.epochDay ?: 0,
                filterMediaKinds = args.filterMediaKinds,
                mediaKinds = args.mediaKinds,
                filterProfileIds = args.filterProfileIds,
                profileIds = args.profileIds,
                filterLanguageTags = args.filterLanguageTags,
                languageTags = args.languageTags,
                filterTitleIds = args.filterTitleIds,
                titleIds = args.titleIds,
            ).executeAsOne()
            val weeklyImpact = immersionQueries.selectImmersionAnalyticsAnkiWeeklyImpact(
                filterDate = (range != null).toLong(),
                startDate = range?.start?.epochDay ?: 0,
                endDate = range?.endInclusive?.epochDay ?: 0,
                filterMediaKinds = args.filterMediaKinds,
                mediaKinds = args.mediaKinds,
                filterProfileIds = args.filterProfileIds,
                profileIds = args.profileIds.filterNotNull(),
                filterLanguageTags = args.filterLanguageTags,
                languageTags = args.languageTags,
                filterTitleIds = args.filterTitleIds,
                titleIds = args.titleIds.filterNotNull(),
                includeLegacy = filter.includeLegacyAggregates.toLong(),
                filterProvenance = args.filterProvenance,
                provenanceStates = args.provenanceStates,
                includeRereads = filter.includeRereadsAndReplays.toLong(),
            ).executeAsList().map { row ->
                AnalyticsAnkiWeeklyImpact(
                    weekStart = ImmersionLocalDate(row.week_start),
                    weekEndInclusive = ImmersionLocalDate(row.week_start + 6),
                    partial = false,
                    activeDurationMillis = row.active_duration_ms,
                    grossCharacters = row.gross_characters,
                    cardsCreated = row.cards_created,
                    cardsUpdated = row.cards_updated,
                    linkedOperations = row.linked_operations,
                    unattributedOperations = row.unattributed_operations,
                    sameWeekReadingToCardOperations = row.same_week_operations,
                    maturedOperations = row.matured_operations,
                    meanReadingToCardLagMillis = row.mean_reading_lag_millis?.toLong(),
                    meanCardToMaturityLagMillis = row.mean_maturity_lag_millis?.toLong(),
                )
            }
            val titleImpact = immersionQueries.selectImmersionAnalyticsAnkiTitleImpact(
                filterDate = (range != null).toLong(),
                startDate = range?.start?.epochDay ?: 0,
                endDate = range?.endInclusive?.epochDay ?: 0,
                filterMediaKinds = args.filterMediaKinds,
                mediaKinds = args.mediaKinds,
                filterProfileIds = args.filterProfileIds,
                profileIds = args.profileIds.filterNotNull(),
                filterLanguageTags = args.filterLanguageTags,
                languageTags = args.languageTags,
                filterTitleIds = args.filterTitleIds,
                titleIds = args.titleIds.filterNotNull(),
                includeLegacy = filter.includeLegacyAggregates.toLong(),
                filterProvenance = args.filterProvenance,
                provenanceStates = args.provenanceStates,
                includeRereads = filter.includeRereadsAndReplays.toLong(),
                limit = MAX_ANKI_TITLE_IMPACT_ROWS.toLong(),
            ).executeAsList().map { row ->
                AnalyticsAnkiTitleImpact(
                    titleId = row.title_id.takeIf(String::isNotBlank)?.let(::TitleId),
                    displayTitle = row.display_title,
                    mediaKind = row.media_kind.takeIf(String::isNotBlank)?.let(MediaKind::valueOf),
                    activeDurationMillis = row.active_duration_ms,
                    grossCharacters = row.gross_characters,
                    cardsCreated = row.cards_created,
                    cardsUpdated = row.cards_updated,
                    operationCount = row.operation_count,
                )
            }
            fun cache(summary: AnalyticsAnkiSummary): AnalyticsAnkiSummary {
                synchronized(ankiSummaryCache) {
                    ankiSummaryCache[cacheKey] = summary
                }
                return summary
            }
            if (profileId == null || languageTag == null) {
                return@await cache(
                    AnalyticsAnkiSummary(
                        snapshot = null,
                        characterCoverageEncountered = 0,
                        characterCoverageKnown = 0,
                        reviewHistoryAvailable = false,
                        linkedOperationCount = activity.linked_operations,
                        unattributedOperationCount = activity.unattributed_operations,
                        meanReadingToCardLagMillis = activity.mean_lag_millis?.toLong(),
                        weeklyImpact = weeklyImpact,
                        titleImpact = titleImpact,
                    ),
                )
            }
            if (snapshot?.hasUsableInventory != true) {
                return@await cache(
                    AnalyticsAnkiSummary(
                        snapshot = snapshot,
                        characterCoverageEncountered = 0,
                        characterCoverageKnown = 0,
                        reviewHistoryAvailable = false,
                        linkedOperationCount = activity.linked_operations,
                        unattributedOperationCount = activity.unattributed_operations,
                        meanReadingToCardLagMillis = activity.mean_lag_millis?.toLong(),
                        weeklyImpact = weeklyImpact,
                        titleImpact = titleImpact,
                    ),
                )
            }
            val character = immersionQueries.selectImmersionAnalyticsAnkiCharacterCoverage(
                languageTag = languageTag.value,
                filterDate = (range != null).toLong(),
                startDate = range?.start?.epochDay ?: 0,
                endDate = range?.endInclusive?.epochDay ?: 0,
                filterMediaKinds = args.filterMediaKinds,
                mediaKinds = args.mediaKinds,
                filterProfileIds = args.filterProfileIds,
                profileIds = args.profileIds,
                filterLanguageTags = args.filterLanguageTags,
                languageTags = args.languageTags,
                filterTitleIds = args.filterTitleIds,
                titleIds = args.titleIds,
                includeRereads = filter.includeRereadsAndReplays.toLong(),
                includeLegacy = filter.includeLegacyAggregates.toLong(),
                filterProvenance = args.filterProvenance,
                provenanceStates = args.provenanceStates,
                filterMaturity = args.filterMaturity,
                profileId = profileId,
                maturityAggregation = args.maturityAggregation,
                maturityTiers = args.maturityTiers,
            ).executeAsOne()
            cache(
                AnalyticsAnkiSummary(
                    snapshot = snapshot,
                    characterCoverageEncountered = character.encountered_count,
                    characterCoverageKnown = character.covered_count,
                    reviewHistoryAvailable = snapshot.supportsReviewHistory,
                    linkedOperationCount = activity.linked_operations,
                    unattributedOperationCount = activity.unattributed_operations,
                    meanReadingToCardLagMillis = activity.mean_lag_millis?.toLong(),
                    weeklyImpact = weeklyImpact,
                    titleImpact = titleImpact,
                ),
            )
        }

    override suspend fun dirtyRollupRanges(limit: Int): List<ImmersionRollupDirtyRange> {
        require(limit in 1..366)
        return handler.await {
            immersionQueries.selectImmersionDirtyRollupRanges(limit.toLong())
                .executeAsList()
                .map {
                    ImmersionRollupDirtyRange(
                        start = ImmersionLocalDate(it.local_date),
                        endInclusive = ImmersionLocalDate(it.local_date),
                        reason = it.reason,
                    )
                }
        }
    }

    override suspend fun rebuildRollups(
        range: tachiyomi.domain.immersion.model.LocalDateRange,
        rollupVersion: Int,
        nowEpochMillis: Long,
    ): ImmersionRollupRebuildResult {
        require(rollupVersion > 0)
        require(nowEpochMillis >= 0)
        return IMMERSION_ROLLUP_MUTATION_MUTEX.withLock {
            handler.await(inTransaction = true) {
                rebuildRollupsInDatabase(range, rollupVersion, nowEpochMillis)
            }
        }
    }

    suspend fun repairAnkiOperation(
        operation: PendingAnkiOperation,
        repairedAtEpochMillis: Long = System.currentTimeMillis(),
        repairZoneId: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        require(operation.status == AnkiOperationStatus.SUCCESS) {
            "Only externally successful Anki operations require repair"
        }
        require(repairedAtEpochMillis >= 0) { "Repair timestamp cannot be negative" }
        val sessionId = operation.token.sessionId ?: return true
        return handler.await(inTransaction = true) {
            val existing = immersionQueries
                .selectImmersionAnkiOperationById(operation.token.operationId.value)
                .executeAsOneOrNull()
            if (existing != null && (
                    existing.type != operation.operationType.name ||
                        existing.status != operation.status.name ||
                        existing.note_id != operation.noteId ||
                        existing.expression_hash != operation.token.expressionHash
                    )
            ) {
                throw identityConflict(
                    "Anki operation ${operation.token.operationId.value} conflicts with an existing identity",
                )
            }
            if (existing?.event_id != null) return@await true

            val session = immersionQueries
                .selectImmersionSessionById(sessionId.value)
                .executeAsOneOrNull()
            if (session == null || session.status == SessionStatus.DELETED.name) {
                if (existing != null) {
                    immersionQueries.deleteImmersionAnkiOperationById(operation.token.operationId.value)
                }
                return@await true
            }
            val sourceUnitId = operation.token.sourceUnitId?.let { requestedSourceId ->
                val source = immersionQueries
                    .selectImmersionSourceUnitById(requestedSourceId.value)
                    .executeAsOneOrNull()
                    ?: return@let null
                if (source.title_id != session.title_id) {
                    throw identityConflict("Anki repair source title does not match its session")
                }
                requestedSourceId
            }
            val sequence = Math.addExact(session.last_sequence, 1)
            val capturedOccurredAt = operation.token.occurredAtEpochMillis.takeIf { it > 0 }
            val occurredAt = capturedOccurredAt ?: repairedAtEpochMillis
            val timezoneOffsetSeconds = operation.token.timezoneOffsetSeconds
                .takeIf {
                    capturedOccurredAt != null &&
                        it in MIN_TIMEZONE_OFFSET_SECONDS..MAX_TIMEZONE_OFFSET_SECONDS
                }
                ?: repairZoneId.rules
                    .getOffset(Instant.ofEpochMilli(occurredAt))
                    .totalSeconds
            val event = AnkiOperationEvent(
                id = repairedAnkiEventId(operation),
                sessionId = sessionId,
                sequence = sequence,
                occurredAtEpochMillis = occurredAt,
                timezoneOffsetSeconds = timezoneOffsetSeconds,
                operationId = operation.token.operationId,
                sourceUnitId = sourceUnitId,
                expressionHash = operation.token.expressionHash,
                normalizedExpression = operation.token.normalizedExpression,
                normalizedReading = operation.token.normalizedReading,
                operationType = operation.operationType,
                status = operation.status,
                noteId = operation.noteId,
                errorCode = operation.errorCode,
            )
            val cardsCreated = (operation.operationType == AnkiOperationType.CREATE).toLong()
            val cardsUpdated = (operation.operationType == AnkiOperationType.UPDATE).toLong()
            immersionQueries.insertImmersionInteractionEvent(
                id = event.id.value,
                sessionId = event.sessionId.value,
                sequence = event.sequence,
                occurredAt = event.occurredAtEpochMillis,
                timezoneOffsetSeconds = event.timezoneOffsetSeconds.toLong(),
                type = event.type.name,
                sourceUnitId = event.sourceUnitId?.value,
                ankiOperationId = event.operationId.value,
                cardsCreatedDelta = cardsCreated,
                cardsUpdatedDelta = cardsUpdated,
                payloadHash = event.payloadHash(),
                localDate = event.localDateEpochDay(),
            )
            if (existing == null) {
                immersionQueries.insertImmersionAnkiOperation(
                    id = event.operationId.value,
                    eventId = event.id.value,
                    sessionId = event.sessionId.value,
                    sourceUnitId = event.sourceUnitId?.value,
                    noteId = event.noteId,
                    cardId = event.cardId,
                    deckId = event.deckId,
                    type = event.operationType.name,
                    status = event.status.name,
                    success = 1L,
                    expressionHash = event.expressionHash,
                    normalizedExpression = event.normalizedExpression,
                    normalizedReading = event.normalizedReading,
                    occurredAt = event.occurredAtEpochMillis,
                    errorCode = event.errorCode,
                )
            } else {
                immersionQueries.linkRepairedImmersionAnkiOperation(
                    eventId = event.id.value,
                    sessionId = event.sessionId.value,
                    sourceUnitId = event.sourceUnitId?.value,
                    occurredAt = event.occurredAtEpochMillis,
                    errorCode = event.errorCode,
                    id = event.operationId.value,
                )
                if (immersionQueries.selectImmersionChanges().executeAsOne() != 1L) {
                    throw identityConflict("Anki operation ${event.operationId.value} could not be linked")
                }
            }
            immersionQueries.advanceImmersionSessionForRepairedInteraction(
                cardsCreatedDelta = cardsCreated,
                cardsUpdatedDelta = cardsUpdated,
                sequence = event.sequence,
                sessionId = event.sessionId.value,
            )
            if (immersionQueries.selectImmersionChanges().executeAsOne() != 1L) {
                throw ImmersionDataException(
                    PersistenceErrorCode.SEQUENCE_CONFLICT,
                    "Anki repair could not claim session sequence ${event.sequence}",
                )
            }
            markEventRollupDirty(event, session.title_id, "ANKI_REPAIR")
            immersionQueries.incrementImmersionRevision(repairedAtEpochMillis)
            true
        }
    }

    suspend fun storeUnlinkedAnkiOperation(
        operation: PendingAnkiOperation,
        occurredAtEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        require(operation.status == AnkiOperationStatus.SUCCESS) {
            "Only externally successful Anki operations require unlinked repair"
        }
        require(occurredAtEpochMillis >= 0) { "Repair timestamp cannot be negative" }
        return handler.await(inTransaction = true) {
            val existing = immersionQueries
                .selectImmersionAnkiOperationById(operation.token.operationId.value)
                .executeAsOneOrNull()
            if (existing != null) {
                if (
                    existing.type != operation.operationType.name ||
                    existing.status != operation.status.name ||
                    existing.note_id != operation.noteId ||
                    existing.expression_hash != operation.token.expressionHash
                ) {
                    throw identityConflict(
                        "Anki operation ${operation.token.operationId.value} conflicts with an existing identity",
                    )
                }
                return@await true
            }
            immersionQueries.insertUnlinkedImmersionAnkiOperation(
                id = operation.token.operationId.value,
                noteId = operation.noteId,
                type = operation.operationType.name,
                status = operation.status.name,
                expressionHash = operation.token.expressionHash,
                normalizedExpression = operation.token.normalizedExpression,
                normalizedReading = operation.token.normalizedReading,
                occurredAt = occurredAtEpochMillis,
            )
            val inserted = immersionQueries.selectImmersionChanges().executeAsOne() == 1L
            if (inserted) immersionQueries.incrementImmersionRevision(occurredAtEpochMillis)
            inserted
        }
    }

    override suspend fun validateInvariants(expectedRollupVersion: Int): ImmersionIntegrityReport {
        require(expectedRollupVersion > 0) { "Rollup version must be positive" }
        val rawHandler = handler.requireRawHandler()
        return handler.await {
            selectImmersionIntegrityReportInDatabase(
                expectedRollupVersion = expectedRollupVersion,
                foreignKeyViolations = rawHandler.awaitRawDriver {
                    it.immersionForeignKeyViolationCount()
                },
            )
        }
    }

    private fun Database.selectImmersionIntegrityReportInDatabase(
        expectedRollupVersion: Int,
        foreignKeyViolations: Long,
    ): ImmersionIntegrityReport {
        val row = immersionQueries
            .selectImmersionIntegrityReport(
                expectedRollupVersion = expectedRollupVersion.toLong(),
                maxEventActiveDurationMillis =
                MAX_RECORDED_IMMERSION_EVENT_ACTIVE_DURATION_MILLIS,
            )
            .executeAsOne()
        return mapCorruption("immersion integrity report") {
            ImmersionIntegrityReport(
                orphanedEvents = NonNegativeCounter(row.orphaned_events),
                orphanedOccurrences = NonNegativeCounter(row.orphaned_occurrences),
                duplicateSessionSequences = NonNegativeCounter(row.duplicate_session_sequences),
                negativeCounters = NonNegativeCounter(row.negative_counters),
                rollupVersionMismatches = NonNegativeCounter(row.rollup_version_mismatches),
                foreignKeyViolations = NonNegativeCounter(foreignKeyViolations),
                rollupStateMismatches = NonNegativeCounter(row.rollup_state_mismatches),
                unappliedEvents = NonNegativeCounter(row.unapplied_events),
                rollupSessionMismatches = NonNegativeCounter(row.rollup_session_mismatches),
                dirtyRollupRanges = NonNegativeCounter(row.dirty_rollup_ranges),
                repairInProgress = NonNegativeCounter(row.repair_in_progress),
                overLimitEventDurations = NonNegativeCounter(row.over_limit_event_durations),
            )
        }
    }

    override suspend fun repairSessionCounters(
        sessionId: SessionId,
        repairedAtEpochMillis: Long,
    ): Boolean {
        require(repairedAtEpochMillis >= 0) { "Repair timestamp cannot be negative" }
        return handler.await(inTransaction = true) {
            val session = immersionQueries.selectImmersionSessionById(sessionId.value).executeAsOneOrNull()
                ?: return@await false
            immersionQueries.repairImmersionSessionCounters(sessionId.value)
            if (immersionQueries.selectImmersionChanges().executeAsOne() != 1L) {
                return@await false
            }
            val offsetSeconds = session.start_offset_seconds.toIntExact("session offset")
            markRollupDirty(
                session.started_at,
                offsetSeconds,
                session.title_id,
                "SESSION_COUNTER_REPAIR",
            )
            session.ended_at?.let { endedAt ->
                markRollupDirty(
                    endedAt,
                    offsetSeconds,
                    session.title_id,
                    "SESSION_COUNTER_REPAIR",
                )
            }
            immersionQueries.incrementImmersionRevision(repairedAtEpochMillis)
            true
        }
    }

    override suspend fun maintenanceSummary(): ImmersionMaintenanceSummary {
        val rawHandler = handler.requireRawHandler()
        return rawHandler.awaitRawDriver { driver ->
            val pageCount = driver.singleLong("PRAGMA page_count")
            val pageSize = driver.singleLong("PRAGMA page_size")
            ImmersionMaintenanceSummary(
                databaseBytes = Math.multiplyExact(pageCount, pageSize),
                sessions = driver.singleLong("SELECT count(*) FROM immersion_session"),
                events = driver.singleLong("SELECT count(*) FROM immersion_event"),
                sourceUnits = driver.singleLong("SELECT count(*) FROM immersion_source_unit"),
                rawTextSourceUnits = driver.singleLong(
                    "SELECT count(*) FROM immersion_source_unit WHERE raw_text IS NOT NULL",
                ),
                rawTextBytes = driver.singleLong(
                    "SELECT coalesce(sum(length(raw_text)), 0) FROM immersion_source_unit",
                ),
                characters = driver.singleLong("SELECT count(*) FROM immersion_character"),
                quarantinedConflicts = driver.singleLong(
                    "SELECT count(*) FROM immersion_merge_conflict WHERE resolution_state = 'QUARANTINED'",
                ),
                lastRawTextCleanupAtEpochMillis = driver.singleNullableLong(
                    "SELECT last_success_at FROM immersion_retention_state WHERE scope_key = 'raw_text'",
                ),
            )
        }
    }

    override suspend fun compactFinalizedHeartbeats(
        limit: Int,
        compactedAtEpochMillis: Long,
    ): Long {
        require(limit in 1..MAX_HEARTBEAT_COMPACTION_SESSIONS) {
            "Heartbeat compaction limit must be between 1 and $MAX_HEARTBEAT_COMPACTION_SESSIONS"
        }
        require(compactedAtEpochMillis >= 0) { "Heartbeat compaction time cannot be negative" }
        return handler.await(inTransaction = true) {
            val sessionIds = immersionQueries
                .selectImmersionFinalizedSessionsForHeartbeatCompaction(
                    compactedMetadataVersion = HEARTBEAT_COMPACTION_METADATA_VERSION,
                    windowMillis = HEARTBEAT_COMPACTION_WINDOW_MILLIS,
                    minimumEvents = MIN_HEARTBEATS_PER_COMPACTION_WINDOW.toLong(),
                    limit = limit.toLong(),
                )
                .executeAsList()
            var removedEvents = 0L
            sessionIds.forEach { compactedSessionId ->
                val session = immersionQueries
                    .selectImmersionSessionById(compactedSessionId)
                    .executeAsOneOrNull()
                    ?: return@forEach
                removedEvents = Math.addExact(
                    removedEvents,
                    compactFinalizedSessionHeartbeats(
                        sessionId = compactedSessionId,
                        titleId = session.title_id,
                        deviceId = session.device_id,
                        compactedAtEpochMillis = compactedAtEpochMillis,
                    ),
                )
            }
            if (removedEvents > 0) {
                immersionQueries.incrementImmersionRevision(compactedAtEpochMillis)
            }
            removedEvents
        }
    }

    override suspend fun rollupBacklogCount(): Long = handler.await {
        immersionQueries.countImmersionDirtyRollupRanges().executeAsOne()
    }

    override suspend fun rollupBacklogEventCount(expectedRollupVersion: Int): Long {
        require(expectedRollupVersion > 0) { "Rollup version must be positive" }
        return handler.await {
            immersionQueries
                .countImmersionUnappliedRollupEvents(expectedRollupVersion.toLong())
                .executeAsOne()
        }
    }

    override suspend fun previewAllStatsDeletion(): ImmersionDeletionPreview {
        val rawHandler = handler.requireRawHandler()
        return rawHandler.awaitRawDriver { it.previewAllImmersionDeletion() }
    }

    override suspend fun resetAllStats(
        deviceId: String,
        deletedAtEpochMillis: Long,
    ): ImmersionDeletionPreview {
        require(deviceId.isNotBlank())
        require(deletedAtEpochMillis >= 0)
        val rawHandler = handler.requireRawHandler()
        val preview = rawHandler.awaitRawDriver(inTransaction = true) { driver ->
            val preview = driver.previewAllImmersionDeletion()
            IMMERSION_TOMBSTONE_IDENTITIES.forEach { (tableName, identity) ->
                driver.execute(
                    identifier = null,
                    sql = """
                        INSERT INTO immersion_tombstone(entity_type, entity_id, deleted_at, device_id)
                        SELECT ?, CAST(${identity.second.quotedIdentifier()} AS TEXT), ?, ?
                        FROM ${tableName.quotedIdentifier()}
                        WHERE true
                        ON CONFLICT(entity_type, entity_id) DO UPDATE SET
                            deleted_at = max(immersion_tombstone.deleted_at, excluded.deleted_at),
                            device_id = CASE
                                WHEN excluded.deleted_at >= immersion_tombstone.deleted_at
                                THEN excluded.device_id
                                ELSE immersion_tombstone.device_id
                            END
                    """.trimIndent(),
                    parameters = 3,
                ) {
                    bindString(0, identity.first)
                    bindLong(1, deletedAtEpochMillis)
                    bindString(2, deviceId)
                }.value
            }
            driver.applyImmersionTombstones(driver.loadImmersionTombstones())
            IMMERSION_RESET_DERIVED_TABLES.forEach { tableName ->
                driver.execute(
                    identifier = null,
                    sql = "DELETE FROM ${tableName.quotedIdentifier()}",
                    parameters = 0,
                ).value
            }
            driver.execute(
                identifier = null,
                sql = "UPDATE immersion_rollup_state SET revision = revision + 1, updated_at = ?",
                parameters = 1,
            ) {
                bindLong(0, deletedAtEpochMillis)
            }.value
            driver.notifyListeners(*IMMERSION_PORTABLE_TABLES.toTypedArray())
            preview
        }
        onAllStatsReset()
        return preview
    }

    override suspend fun previewScopedStatsDeletion(
        scope: ImmersionStatsDeletionScope,
    ): ImmersionDeletionPreview =
        handler.await(inTransaction = true) {
            scopedDeletionPreview(
                sessions = loadScopedSessions(scope),
                databaseRevision = immersionQueries.selectImmersionRevision().executeAsOne(),
            )
        }

    override suspend fun deleteScopedStats(
        scope: ImmersionStatsDeletionScope,
        expectedPreview: ImmersionDeletionPreview,
    ): ImmersionDeletionPreview {
        require(
            expectedPreview.selectionDigest != null &&
                expectedPreview.databaseRevision != null,
        ) {
            "Scoped deletion requires an exact preview identity; preview again before deleting"
        }
        val deletedAt = System.currentTimeMillis()
        val result = handler.await(inTransaction = true) {
            val sessions = loadScopedSessions(scope)
            val preview = scopedDeletionPreview(
                sessions = sessions,
                databaseRevision = immersionQueries.selectImmersionRevision().executeAsOne(),
            )
            require(preview == expectedPreview) {
                "Scoped deletion changed after preview; preview again before deleting"
            }
            sessions.forEach { session ->
                check(deleteSessionInDatabase(session.id, deletedAt)) {
                    "Scoped deletion changed while deleting ${session.id.value}"
                }
            }
            ScopedDeletionResult(preview, sessions.map(ImmersionSession::id))
        }
        result.deletedSessionIds.forEach(onSessionDeleted)
        return result.preview
    }

    override suspend fun previewSessionDeletion(
        sessionId: SessionId,
    ): ImmersionDeletionPreview? =
        handler.await(inTransaction = true) {
            val session = immersionQueries
                .selectImmersionSessionById(sessionId.value)
                .executeAsOneOrNull()
                ?.toDomain()
                ?: return@await null
            scopedDeletionPreview(
                sessions = listOf(session),
                databaseRevision = immersionQueries.selectImmersionRevision().executeAsOne(),
            )
        }

    override suspend fun deleteSession(
        sessionId: SessionId,
        expectedPreview: ImmersionDeletionPreview,
    ): ImmersionDeletionPreview? {
        require(
            expectedPreview.selectionDigest != null &&
                expectedPreview.databaseRevision != null,
        ) {
            "Session deletion requires an exact preview identity; preview again before deleting"
        }
        val deletedAt = System.currentTimeMillis()
        val preview = handler.await(inTransaction = true) {
            val session = immersionQueries
                .selectImmersionSessionById(sessionId.value)
                .executeAsOneOrNull()
                ?.toDomain()
                ?: return@await null
            val currentPreview = scopedDeletionPreview(
                sessions = listOf(session),
                databaseRevision = immersionQueries.selectImmersionRevision().executeAsOne(),
            )
            require(currentPreview == expectedPreview) {
                "Session deletion changed after preview; preview again before deleting"
            }
            check(deleteSessionInDatabase(sessionId, deletedAt)) {
                "Session deletion changed while deleting ${sessionId.value}"
            }
            currentPreview
        }
        if (preview != null) onSessionDeleted(sessionId)
        return preview
    }

    override suspend fun deleteSession(sessionId: SessionId): Boolean {
        val deletedAt = System.currentTimeMillis()
        val deleted = handler.await(inTransaction = true) {
            deleteSessionInDatabase(sessionId, deletedAt)
        }
        if (deleted) onSessionDeleted(sessionId)
        return deleted
    }

    private fun Database.deleteSessionInDatabase(
        sessionId: SessionId,
        deletedAtEpochMillis: Long,
    ): Boolean {
        val session = immersionQueries.selectImmersionSessionById(sessionId.value).executeAsOneOrNull()
            ?: return false
        val sourceUnitIds = immersionQueries
            .selectImmersionSourceIdsForSession(sessionId.value)
            .executeAsList()
        val boundarySnapshot = captureImmersionSourceBoundarySnapshot(sourceUnitIds)
        val affectedDates = linkedSetOf<ImmersionLocalDate>().apply {
            session.legacy_local_date?.let { add(ImmersionLocalDate(it)) }
            add(
                ImmersionAnalyticsCalendar().localDate(
                    session.started_at,
                    session.start_offset_seconds.toIntExact("session offset"),
                ),
            )
            session.ended_at?.let { endedAt ->
                add(
                    ImmersionAnalyticsCalendar().localDate(
                        endedAt,
                        session.start_offset_seconds.toIntExact("session offset"),
                    ),
                )
            }
            immersionQueries
                .selectImmersionRollupInvalidationEventsForSession(sessionId.value)
                .executeAsList()
                .forEach { event ->
                    add(ImmersionLocalDate(event.local_date))
                    addAll(
                        ImmersionAnalyticsCalendar().splitDuration(
                            event.occurred_at,
                            event.active_duration_delta_ms,
                            event.timezone_offset_seconds.toIntExact("event offset"),
                        ).keys,
                    )
                }
            addAll(selectImmersionExposureDatesForSources(sourceUnitIds))
            addAll(selectImmersionInventoryExposureDates(boundarySnapshot))
        }
        immersionQueries.upsertImmersionTombstone(
            entityType = "SESSION",
            entityId = sessionId.value,
            deletedAt = deletedAtEpochMillis,
            deviceId = session.device_id,
        )
        immersionQueries.deleteImmersionSession(sessionId.value)
        checkExactlyOneChange("deleting session ${sessionId.value}")
        sourceUnitIds.forEach { sourceUnitId ->
            immersionQueries.deleteImmersionSourceUnitIfUnreferenced(sourceUnitId)
        }
        canonicalizeImmersionSourceBoundaries(boundarySnapshot)
        affectedDates += selectImmersionExposureDatesForSources(sourceUnitIds)
        affectedDates += selectImmersionInventoryExposureDates(boundarySnapshot)
        affectedDates.forEach { date ->
            immersionQueries.upsertImmersionRollupDirty(
                localDate = date.epochDay,
                titleId = session.title_id,
                reason = "SESSION_DELETE",
                updatedAt = deletedAtEpochMillis,
            )
        }
        immersionQueries.incrementImmersionRevision(deletedAtEpochMillis)
        return true
    }

    private fun Database.selectImmersionExposureDatesForSources(
        sourceUnitIds: Collection<String>,
    ): Set<ImmersionLocalDate> =
        sourceUnitIds
            .distinct()
            .chunked(IMMERSION_INDEX_ID_CHUNK_SIZE)
            .flatMapTo(linkedSetOf()) { ids ->
                immersionQueries
                    .selectImmersionExposureDatesForSources(ids)
                    .executeAsList()
                    .map(::ImmersionLocalDate)
            }

    private fun Database.selectImmersionInventoryExposureDates(
        snapshot: ImmersionSourceBoundarySnapshot,
    ): Set<ImmersionLocalDate> =
        buildSet {
            snapshot.characterCodePoints
                .chunked(IMMERSION_INDEX_ID_CHUNK_SIZE)
                .forEach { codePoints ->
                    addAll(
                        immersionQueries
                            .selectImmersionExposureDatesForCharacters(codePoints)
                            .executeAsList()
                            .map(::ImmersionLocalDate),
                    )
                }
        }

    private fun Database.loadScopedSessions(
        scope: ImmersionStatsDeletionScope,
    ): List<ImmersionSession> =
        scope.asStatsFilter().let { filter ->
            val args = filter.sqlArgs()
            val range = filter.dateRange
            immersionQueries.selectImmersionSessionsForScopedDeletion(
                filterDate = (range != null).toLong(),
                startDate = range?.start?.epochDay ?: 0,
                endDate = range?.endInclusive?.epochDay ?: 0,
                filterMediaKinds = args.filterMediaKinds,
                mediaKinds = args.mediaKinds,
                filterProfileIds = args.filterProfileIds,
                profileIds = args.profileIds,
                filterLanguageTags = args.filterLanguageTags,
                languageTags = args.languageTags,
                filterTitleIds = args.filterTitleIds,
                titleIds = args.titleIds,
                includeLegacy = filter.includeLegacyAggregates.toLong(),
                filterProvenance = args.filterProvenance,
                provenanceStates = args.provenanceStates,
            ).executeAsList().map(Immersion_session::toDomain)
        }

    private fun Database.scopedDeletionPreview(
        sessions: List<ImmersionSession>,
        databaseRevision: Long,
    ): ImmersionDeletionPreview {
        val sourceIds = if (sessions.isEmpty()) {
            emptySet()
        } else {
            sessions
                .map { it.id.value }
                .chunked(SQLITE_BIND_BATCH_SIZE)
                .flatMapTo(mutableSetOf<String>()) { ids ->
                    immersionQueries.selectImmersionSourceIdsForSessions(ids).executeAsList()
                }
        }
        val characterCodePoints = selectImmersionCharacterCodePointsForSources(sourceIds)
        val affectedGoals = immersionQueries
            .selectImmersionGoals()
            .executeAsList()
            .map(Immersion_goal::toDomain)
            .count { goal -> sessions.any(goal::isAffectedByDeletionOf) }
            .toLong()
        return ImmersionDeletionPreview(
            sessions = sessions.size.toLong(),
            activeDurationMillis = sessions.sumOf { it.activeDuration.value },
            grossCharacters = sessions.sumOf { it.grossCharacters.value },
            sourceUnits = sourceIds.size.toLong(),
            characters = characterCodePoints.size.toLong(),
            goals = affectedGoals,
            selectionDigest = sessions.selectionDigest(),
            databaseRevision = databaseRevision,
        )
    }

    override suspend fun beginRollupRebuild(
        rollupVersion: Int,
        repairCursor: String?,
        updatedAtEpochMillis: Long,
    ) {
        require(rollupVersion > 0) { "Rollup version must be positive" }
        require(updatedAtEpochMillis >= 0) { "Repair timestamp cannot be negative" }
        IMMERSION_ROLLUP_MUTATION_MUTEX.withLock {
            handler.await(inTransaction = true) {
                beginRollupRebuildInDatabase(
                    rollupVersion = rollupVersion,
                    repairCursor = repairCursor,
                    updatedAtEpochMillis = updatedAtEpochMillis,
                )
            }
        }
    }

    override suspend fun exportPortableArchive(
        includeRawText: Boolean,
        createdAtEpochMillis: Long,
    ): ImmersionPortableArchive {
        require(createdAtEpochMillis >= 0)
        val rawHandler = handler.requireRawHandler()
        val tables = rawHandler.awaitRawDriver { driver ->
            IMMERSION_PORTABLE_TABLES.map { tableName ->
                driver.exportPortableTable(tableName, includeRawText)
            }
        }
        return ImmersionPortableArchive(
            formatVersion = IMMERSION_PORTABLE_FORMAT_VERSION,
            sourceSchemaVersion = ImmersionStatsVersions.SCHEMA,
            createdAtEpochMillis = createdAtEpochMillis,
            includesRawText = includeRawText,
            tables = tables,
        ).withoutUnlinkedAnkiOperations()
    }

    override suspend fun mergePortableArchive(
        archive: ImmersionPortableArchive,
        mergedAtEpochMillis: Long,
    ): ImmersionMergeReport {
        return IMMERSION_ROLLUP_MUTATION_MUTEX.withLock {
            require(mergedAtEpochMillis >= 0)
            if (archive.formatVersion > IMMERSION_PORTABLE_FORMAT_VERSION) {
                throw ImmersionArchiveException(
                    ImmersionArchiveRejection.UNSUPPORTED_FORMAT_VERSION,
                    "This statistics archive was written in format " +
                        "${archive.formatVersion}; this version of the app reads up to " +
                        "$IMMERSION_PORTABLE_FORMAT_VERSION. Update the app and try again.",
                )
            }
            if (archive.sourceSchemaVersion > ImmersionStatsVersions.SCHEMA) {
                throw ImmersionArchiveException(
                    ImmersionArchiveRejection.UNSUPPORTED_SCHEMA_VERSION,
                    "This statistics archive was written against database schema " +
                        "${archive.sourceSchemaVersion}; this version of the app reads up to " +
                        "${ImmersionStatsVersions.SCHEMA}. Update the app and try again.",
                )
            }
            val unknownTables = archive.tables
                .map { it.name }
                .filterNot { it in IMMERSION_PORTABLE_TABLES }
            if (unknownTables.isNotEmpty()) {
                throw ImmersionArchiveException(
                    ImmersionArchiveRejection.UNKNOWN_TABLE,
                    "This statistics archive contains data this version of the app does not " +
                        "track (${unknownTables.sorted().joinToString()}), so merging it would " +
                        "silently drop that data.",
                )
            }

            val effectiveArchive = archive
                .withoutUnlinkedAnkiOperations()
                .canonicalizePortableOrder()
            val incoming = effectiveArchive.tables.associateBy { it.name }
            val touchedSourceUnitIds = incoming.touchedSourceUnitIds()
            val eligibleRowCount = effectiveArchive.tables.sumOf { it.rows.size.toLong() }
            val archiveDigest = effectiveArchive.portableMergeDigest()
            val rawHandler = handler.requireRawHandler()
            val initialCheckpoint = PortableMergeCheckpoint(
                archiveDigest = archiveDigest,
                protocolVersion = IMMERSION_PORTABLE_MERGE_PROTOCOL_VERSION,
                archiveCreatedAt = effectiveArchive.createdAtEpochMillis,
                archiveFormatVersion = effectiveArchive.formatVersion,
                archiveSchemaVersion = effectiveArchive.sourceSchemaVersion,
                includesRawText = effectiveArchive.includesRawText,
                stage = PortableMergeStage.TOMBSTONES,
                tableOrdinal = 0,
                nextRowOffset = 0,
                eligibleRowCount = eligibleRowCount,
                insertedRows = 0,
                unchangedRows = 0,
                skippedByTombstoneRows = 0,
                quarantinedConflicts = 0,
                rebuiltRollupRows = 0,
                verificationJson = null,
                lastErrorCode = null,
                startedAt = mergedAtEpochMillis,
                updatedAt = mergedAtEpochMillis,
                completedAt = null,
            )
            val initialization = rawHandler.awaitRawDriver(inTransaction = true) { driver ->
                val existing = driver.loadPortableMergeCheckpoint(archiveDigest)
                if (existing != null) {
                    PortableMergeInitialization(existing, wasExisting = true)
                } else {
                    val inserted = driver.insertPortableMergeCheckpoint(initialCheckpoint)
                    PortableMergeInitialization(
                        checkpoint = checkNotNull(
                            driver.loadPortableMergeCheckpoint(archiveDigest),
                        ),
                        wasExisting = !inserted,
                    )
                }
            }
            var checkpoint = initialization.checkpoint
            checkpoint.requireArchiveIdentity(effectiveArchive, eligibleRowCount)
            val completionDisposition = if (initialization.wasExisting) {
                ImmersionMergeDisposition.RESUMED
            } else {
                ImmersionMergeDisposition.COMPLETED
            }
            val mergeTableNames = IMMERSION_PORTABLE_TABLES.filterNot {
                it == IMMERSION_TOMBSTONE_TABLE
            }

            while (true) {
                checkpoint = checkNotNull(
                    rawHandler.awaitRawDriver {
                        it.loadPortableMergeCheckpoint(archiveDigest)
                    },
                )
                when (checkpoint.stage) {
                    PortableMergeStage.TOMBSTONES -> {
                        val table = incoming[IMMERSION_TOMBSTONE_TABLE]
                        val rows = table?.rows.orEmpty()
                        if (checkpoint.nextRowOffset < rows.size) {
                            val nextOffset = minOf(
                                checkpoint.nextRowOffset + IMMERSION_PORTABLE_MERGE_CHUNK_SIZE,
                                rows.size,
                            )
                            checkpoint = rawHandler.awaitRawDriver(inTransaction = true) { driver ->
                                val latest = checkNotNull(
                                    driver.loadPortableMergeCheckpoint(archiveDigest),
                                )
                                check(
                                    latest.stage == PortableMergeStage.TOMBSTONES &&
                                        latest.nextRowOffset == checkpoint.nextRowOffset,
                                ) {
                                    "Portable merge checkpoint moved while applying tombstones"
                                }
                                val result = driver.mergePortableTable(
                                    table = checkNotNull(table).copy(
                                        rows = rows.subList(latest.nextRowOffset, nextOffset),
                                    ),
                                    archiveIncludesRawText = effectiveArchive.includesRawText,
                                    tombstones = emptySet(),
                                    mergedAtEpochMillis = mergedAtEpochMillis,
                                    tombstoneMetadataOnly = true,
                                )
                                latest.withCounts(result).copy(
                                    nextRowOffset = nextOffset,
                                    updatedAt = mergedAtEpochMillis,
                                    lastErrorCode = null,
                                ).also(driver::updatePortableMergeCheckpoint)
                            }
                            portableMergeCheckpointObserver(
                                archiveDigest,
                                IMMERSION_TOMBSTONE_TABLE,
                                nextOffset,
                            )
                        } else {
                            checkpoint = handler.await(inTransaction = true) {
                                val latest = checkNotNull(
                                    rawHandler.awaitRawDriver {
                                        it.loadPortableMergeCheckpoint(archiveDigest)
                                    },
                                )
                                if (
                                    latest.stage != PortableMergeStage.TOMBSTONES ||
                                    latest.nextRowOffset != checkpoint.nextRowOffset
                                ) {
                                    return@await latest
                                }
                                val tombstones = rawHandler.awaitRawDriver {
                                    it.loadImmersionTombstones()
                                }
                                val boundarySnapshot = captureImmersionSourceBoundarySnapshot(
                                    sourceUnitIdsAffectedByTombstones(tombstones),
                                )
                                rawHandler.awaitRawDriver {
                                    it.applyImmersionTombstones(tombstones)
                                }
                                canonicalizeImmersionSourceBoundaries(boundarySnapshot)
                                latest.copy(
                                    stage = PortableMergeStage.TABLES,
                                    tableOrdinal = 0,
                                    nextRowOffset = 0,
                                    updatedAt = mergedAtEpochMillis,
                                    lastErrorCode = null,
                                ).also { updated ->
                                    rawHandler.awaitRawDriver {
                                        it.updatePortableMergeCheckpoint(updated)
                                    }
                                }
                            }
                        }
                    }

                    PortableMergeStage.TABLES -> {
                        if (checkpoint.tableOrdinal >= mergeTableNames.size) {
                            checkpoint = rawHandler.awaitRawDriver(inTransaction = true) { driver ->
                                val latest = checkNotNull(
                                    driver.loadPortableMergeCheckpoint(archiveDigest),
                                )
                                if (
                                    latest.stage != PortableMergeStage.TABLES ||
                                    latest.tableOrdinal != checkpoint.tableOrdinal ||
                                    latest.nextRowOffset != checkpoint.nextRowOffset
                                ) {
                                    return@awaitRawDriver latest
                                }
                                latest.copy(
                                    stage = PortableMergeStage.FINALIZE,
                                    tableOrdinal = mergeTableNames.size,
                                    nextRowOffset = 0,
                                    updatedAt = mergedAtEpochMillis,
                                    lastErrorCode = null,
                                ).also(driver::updatePortableMergeCheckpoint)
                            }
                            continue
                        }
                        val tableName = mergeTableNames[checkpoint.tableOrdinal]
                        val table = incoming[tableName]
                        val rows = table?.rows.orEmpty()
                        if (checkpoint.nextRowOffset >= rows.size) {
                            checkpoint = rawHandler.awaitRawDriver(inTransaction = true) { driver ->
                                val latest = checkNotNull(
                                    driver.loadPortableMergeCheckpoint(archiveDigest),
                                )
                                if (
                                    latest.stage != PortableMergeStage.TABLES ||
                                    latest.tableOrdinal != checkpoint.tableOrdinal ||
                                    latest.nextRowOffset != checkpoint.nextRowOffset
                                ) {
                                    return@awaitRawDriver latest
                                }
                                latest.copy(
                                    tableOrdinal = latest.tableOrdinal + 1,
                                    nextRowOffset = 0,
                                    updatedAt = mergedAtEpochMillis,
                                    lastErrorCode = null,
                                ).also(driver::updatePortableMergeCheckpoint)
                            }
                            continue
                        }
                        val nextOffset = minOf(
                            checkpoint.nextRowOffset + IMMERSION_PORTABLE_MERGE_CHUNK_SIZE,
                            rows.size,
                        )
                        checkpoint = rawHandler.awaitRawDriver(inTransaction = true) { driver ->
                            val latest = checkNotNull(
                                driver.loadPortableMergeCheckpoint(archiveDigest),
                            )
                            check(
                                latest.stage == PortableMergeStage.TABLES &&
                                    latest.tableOrdinal == checkpoint.tableOrdinal &&
                                    latest.nextRowOffset == checkpoint.nextRowOffset,
                            ) {
                                "Portable merge checkpoint moved while applying $tableName"
                            }
                            val tombstones = driver.loadImmersionTombstones()
                            val result = driver.mergePortableTable(
                                table = checkNotNull(table).copy(
                                    rows = rows.subList(latest.nextRowOffset, nextOffset),
                                ),
                                archiveIncludesRawText = effectiveArchive.includesRawText,
                                tombstones = tombstones,
                                mergedAtEpochMillis = mergedAtEpochMillis,
                                tombstoneMetadataOnly = false,
                            )
                            latest.withCounts(result).copy(
                                nextRowOffset = nextOffset,
                                updatedAt = mergedAtEpochMillis,
                                lastErrorCode = null,
                            ).also(driver::updatePortableMergeCheckpoint)
                        }
                        portableMergeCheckpointObserver(
                            archiveDigest,
                            tableName,
                            nextOffset,
                        )
                    }

                    PortableMergeStage.FINALIZE -> {
                        checkpoint = handler.await(inTransaction = true) {
                            val latest = checkNotNull(
                                rawHandler.awaitRawDriver {
                                    it.loadPortableMergeCheckpoint(archiveDigest)
                                },
                            )
                            if (latest.stage != PortableMergeStage.FINALIZE) {
                                return@await latest
                            }
                            immersionQueries.deleteOrphanImmersionSourceUnits()
                            canonicalizeMergedImmersionSourceBoundaries(touchedSourceUnitIds)
                            immersionQueries.recomputeImmersionSourceSeenTimes()
                            immersionQueries.recomputeImmersionCharacterSeenTimes()
                            immersionQueries.deleteOrphanImmersionCharacters()
                            latest.copy(
                                stage = PortableMergeStage.ROLLUP_VALIDATE,
                                updatedAt = mergedAtEpochMillis,
                                lastErrorCode = null,
                            ).also { updated ->
                                rawHandler.awaitRawDriver {
                                    it.updatePortableMergeCheckpoint(updated)
                                }
                            }
                        }
                    }

                    PortableMergeStage.ROLLUP_VALIDATE,
                    PortableMergeStage.VALIDATION_FAILED,
                    -> {
                        val step = advancePortableRollupPass(
                            rawHandler = rawHandler,
                            archiveDigest = archiveDigest,
                            archiveCreatedAt = effectiveArchive.createdAtEpochMillis,
                            mergedAtEpochMillis = mergedAtEpochMillis,
                            pass = PORTABLE_ROLLUP_FIRST_PASS,
                        )
                        checkpoint = step.checkpoint
                        step.observerCheckpoint?.let { observer ->
                            portableMergeCheckpointObserver(
                                archiveDigest,
                                observer.name,
                                observer.offset,
                            )
                        }
                    }

                    PortableMergeStage.ROLLUP_VERIFY -> {
                        val step = advancePortableRollupPass(
                            rawHandler = rawHandler,
                            archiveDigest = archiveDigest,
                            archiveCreatedAt = effectiveArchive.createdAtEpochMillis,
                            mergedAtEpochMillis = mergedAtEpochMillis,
                            pass = PORTABLE_ROLLUP_SECOND_PASS,
                        )
                        checkpoint = step.checkpoint
                        step.observerCheckpoint?.let { observer ->
                            portableMergeCheckpointObserver(
                                archiveDigest,
                                observer.name,
                                observer.offset,
                            )
                        }
                        if (
                            checkpoint.stage != PortableMergeStage.COMPLETE &&
                            checkpoint.stage != PortableMergeStage.VALIDATION_FAILED
                        ) {
                            continue
                        }
                        val verification = checkpoint.decodeVerification()
                        check(verification.isHealthy) {
                            "Portable immersion merge failed verification: " +
                                checkNotNull(checkpoint.lastErrorCode)
                        }
                        rawHandler.awaitRawDriver {
                            it.notifyListeners(*IMMERSION_PORTABLE_TABLES.toTypedArray())
                        }
                        return@withLock checkpoint.toReport(
                            if (step.completedByThisCall) {
                                completionDisposition
                            } else {
                                ImmersionMergeDisposition.ALREADY_COMPLETE
                            },
                        )
                    }

                    PortableMergeStage.COMPLETE -> {
                        checkpoint = handler.await(inTransaction = true) {
                            val latest = checkNotNull(
                                rawHandler.awaitRawDriver {
                                    it.loadPortableMergeCheckpoint(archiveDigest)
                                },
                            )
                            if (latest.stage != PortableMergeStage.COMPLETE) {
                                return@await latest
                            }
                            val verification = runCatching {
                                latest.decodeVerification()
                            }.getOrNull()
                            val currentRevision = immersionQueries
                                .selectImmersionRevision()
                                .executeAsOneOrNull()
                            if (
                                verification?.isHealthy == true &&
                                verification.databaseRevision == currentRevision
                            ) {
                                latest
                            } else {
                                latest.copy(
                                    stage = PortableMergeStage.ROLLUP_VALIDATE,
                                    rebuiltRollupRows = 0,
                                    verificationJson = null,
                                    lastErrorCode = null,
                                    updatedAt = mergedAtEpochMillis,
                                    completedAt = null,
                                ).also { updated ->
                                    rawHandler.awaitRawDriver {
                                        it.updatePortableMergeCheckpoint(updated)
                                    }
                                }
                            }
                        }
                        if (checkpoint.stage == PortableMergeStage.COMPLETE) {
                            rawHandler.awaitRawDriver {
                                it.notifyListeners(*IMMERSION_PORTABLE_TABLES.toTypedArray())
                            }
                            return@withLock checkpoint.toReport(
                                ImmersionMergeDisposition.ALREADY_COMPLETE,
                            )
                        }
                    }
                }
            }
            error("Portable merge state machine terminated unexpectedly")
        }
    }

    private suspend fun advancePortableRollupPass(
        rawHandler: AndroidDatabaseHandler,
        archiveDigest: String,
        archiveCreatedAt: Long,
        mergedAtEpochMillis: Long,
        pass: Int,
    ): PortableRollupStepResult {
        require(pass == PORTABLE_ROLLUP_FIRST_PASS || pass == PORTABLE_ROLLUP_SECOND_PASS)
        val checkpoint = checkNotNull(
            rawHandler.awaitRawDriver {
                it.loadPortableMergeCheckpoint(archiveDigest)
            },
        )
        if (
            pass == PORTABLE_ROLLUP_FIRST_PASS &&
            checkpoint.stage != PortableMergeStage.ROLLUP_VALIDATE &&
            checkpoint.stage != PortableMergeStage.VALIDATION_FAILED
        ) {
            return PortableRollupStepResult(checkpoint)
        }
        if (
            pass == PORTABLE_ROLLUP_SECOND_PASS &&
            checkpoint.stage != PortableMergeStage.ROLLUP_VERIFY
        ) {
            return PortableRollupStepResult(checkpoint)
        }

        val progress = checkpoint.decodePortableRollupProgressOrNull()
        if (progress == null) {
            val firstPass = if (pass == PORTABLE_ROLLUP_SECOND_PASS) {
                runCatching {
                    checkpoint.decodePortableRollupFirstPass()
                }.getOrElse {
                    return restartPortableRollupValidation(
                        rawHandler = rawHandler,
                        checkpoint = checkpoint,
                        mergedAtEpochMillis = mergedAtEpochMillis,
                    )
                }
            } else {
                null
            }
            if (
                firstPass != null &&
                firstPass.fingerprintVersion != PORTABLE_ROLLUP_FINGERPRINT_VERSION
            ) {
                return restartPortableRollupValidation(
                    rawHandler = rawHandler,
                    checkpoint = checkpoint,
                    mergedAtEpochMillis = mergedAtEpochMillis,
                )
            }
            return initializePortableRollupPass(
                rawHandler = rawHandler,
                checkpoint = checkpoint,
                archiveCreatedAt = archiveCreatedAt,
                mergedAtEpochMillis = mergedAtEpochMillis,
                pass = pass,
                firstPass = firstPass,
            )
        }
        check(progress.pass == pass) {
            "Portable rollup pass ${progress.pass} cannot resume as pass $pass"
        }

        return try {
            progress.nextChunk()?.let { chunk ->
                return rebuildPortableRollupChunk(
                    rawHandler = rawHandler,
                    checkpoint = checkpoint,
                    progress = progress,
                    chunk = chunk,
                    mergedAtEpochMillis = mergedAtEpochMillis,
                )
            }
            if (!progress.lifetimeComplete) {
                return fingerprintPortableLifetimeRollupPage(
                    rawHandler = rawHandler,
                    checkpoint = checkpoint,
                    progress = progress,
                    mergedAtEpochMillis = mergedAtEpochMillis,
                )
            }
            completePortableRollupPass(
                rawHandler = rawHandler,
                checkpoint = checkpoint,
                progress = progress,
                mergedAtEpochMillis = mergedAtEpochMillis,
            )
        } catch (_: PortableRollupRevisionChangedException) {
            restartPortableRollupValidation(
                rawHandler = rawHandler,
                checkpoint = checkpoint,
                mergedAtEpochMillis = mergedAtEpochMillis,
            )
        }
    }

    private suspend fun restartPortableRollupValidation(
        rawHandler: AndroidDatabaseHandler,
        checkpoint: PortableMergeCheckpoint,
        mergedAtEpochMillis: Long,
    ): PortableRollupStepResult =
        rawHandler.awaitRawDriver(inTransaction = true) { driver ->
            val latest = checkNotNull(
                driver.loadPortableMergeCheckpoint(checkpoint.archiveDigest),
            )
            if (!latest.matchesRollupCheckpoint(checkpoint)) {
                return@awaitRawDriver PortableRollupStepResult(latest)
            }
            val updated = latest.copy(
                stage = PortableMergeStage.ROLLUP_VALIDATE,
                rebuiltRollupRows = 0,
                verificationJson = null,
                lastErrorCode = null,
                updatedAt = mergedAtEpochMillis,
                completedAt = null,
            )
            check(driver.compareAndSetPortableRollupCheckpoint(latest, updated)) {
                "Portable rollup checkpoint moved while restarting validation"
            }
            PortableRollupStepResult(updated)
        }

    private suspend fun initializePortableRollupPass(
        rawHandler: AndroidDatabaseHandler,
        checkpoint: PortableMergeCheckpoint,
        archiveCreatedAt: Long,
        mergedAtEpochMillis: Long,
        pass: Int,
        firstPass: PortableRollupFirstPassEvidence?,
    ): PortableRollupStepResult =
        handler.await(inTransaction = true) {
            val latest = checkNotNull(
                rawHandler.awaitRawDriver {
                    it.loadPortableMergeCheckpoint(checkpoint.archiveDigest)
                },
            )
            if (!latest.matchesRollupCheckpoint(checkpoint)) {
                return@await PortableRollupStepResult(latest)
            }
            val range = immersionRollupRebuildRange()
            beginRollupRebuildInDatabase(
                rollupVersion = ImmersionStatsVersions.ROLLUP,
                repairCursor = portableRollupRepairCursor(
                    archiveCreatedAt = archiveCreatedAt,
                    archiveDigest = checkpoint.archiveDigest,
                    pass = pass,
                ),
                updatedAtEpochMillis = mergedAtEpochMillis,
                markSessionsDirty = false,
            )
            val progress = PortableRollupPassProgress.initial(
                pass = pass,
                range = range,
                databaseRevision = immersionQueries.selectImmersionRevision().executeAsOne(),
                firstPass = firstPass,
            )
            val updated = latest.copy(
                stage = if (pass == PORTABLE_ROLLUP_FIRST_PASS) {
                    PortableMergeStage.ROLLUP_VALIDATE
                } else {
                    PortableMergeStage.ROLLUP_VERIFY
                },
                rebuiltRollupRows = if (pass == PORTABLE_ROLLUP_FIRST_PASS) {
                    0
                } else {
                    latest.rebuiltRollupRows
                },
                verificationJson = Json.encodeToString(progress),
                lastErrorCode = null,
                updatedAt = mergedAtEpochMillis,
                completedAt = null,
            )
            check(
                rawHandler.awaitRawDriver {
                    it.compareAndSetPortableRollupCheckpoint(latest, updated)
                },
            ) {
                "Portable rollup checkpoint moved while initializing pass $pass"
            }
            PortableRollupStepResult(updated)
        }

    private suspend fun rebuildPortableRollupChunk(
        rawHandler: AndroidDatabaseHandler,
        checkpoint: PortableMergeCheckpoint,
        progress: PortableRollupPassProgress,
        chunk: LocalDateRange,
        mergedAtEpochMillis: Long,
    ): PortableRollupStepResult =
        handler.await(inTransaction = true) {
            val latest = checkNotNull(
                rawHandler.awaitRawDriver {
                    it.loadPortableMergeCheckpoint(checkpoint.archiveDigest)
                },
            )
            if (!latest.matchesRollupCheckpoint(checkpoint)) {
                return@await PortableRollupStepResult(latest)
            }
            progress.requireDatabaseRevision(
                immersionQueries.selectImmersionRevision().executeAsOne(),
            )
            val rebuilt = rebuildRollupsInDatabase(
                range = chunk,
                rollupVersion = ImmersionStatsVersions.ROLLUP,
                nowEpochMillis = mergedAtEpochMillis,
                accumulateLifetimeRollups = true,
                completeRepair = false,
            )
            portableMergeRollupChunkObserver(
                checkpoint.archiveDigest,
                progress.pass,
                chunk,
            )
            val fingerprint = rawHandler.awaitRawDriver {
                val daily = it.immersionDailyRollupFingerprint(
                    range = chunk,
                    seedDigest = progress.digest,
                )
                daily.copy(
                    digest = it.immersionHourlyRollupDigest(
                        range = chunk,
                        seedDigest = daily.digest,
                    ),
                )
            }
            check(rebuilt.rowCount == fingerprint.rowCount) {
                "Portable rollup chunk row count changed while fingerprinting"
            }
            val advanced = progress.advance(chunk, rebuilt.rowCount, fingerprint)
            val updated = latest.copy(
                rebuiltRollupRows = if (progress.pass == PORTABLE_ROLLUP_FIRST_PASS) {
                    advanced.rebuiltRollupRows
                } else {
                    latest.rebuiltRollupRows
                },
                verificationJson = Json.encodeToString(advanced),
                lastErrorCode = null,
                updatedAt = mergedAtEpochMillis,
                completedAt = null,
            )
            check(
                rawHandler.awaitRawDriver {
                    it.compareAndSetPortableRollupCheckpoint(latest, updated)
                },
            ) {
                "Portable rollup checkpoint moved while committing pass " +
                    "${progress.pass} chunk ${progress.completedChunks + 1}"
            }
            PortableRollupStepResult(
                checkpoint = updated,
                observerCheckpoint = PortableRollupObserverCheckpoint(
                    name = if (progress.pass == PORTABLE_ROLLUP_FIRST_PASS) {
                        IMMERSION_ROLLUP_FIRST_PASS_CHUNK_CHECKPOINT
                    } else {
                        IMMERSION_ROLLUP_SECOND_PASS_CHUNK_CHECKPOINT
                    },
                    offset = advanced.completedChunks,
                ),
            )
        }

    private suspend fun fingerprintPortableLifetimeRollupPage(
        rawHandler: AndroidDatabaseHandler,
        checkpoint: PortableMergeCheckpoint,
        progress: PortableRollupPassProgress,
        mergedAtEpochMillis: Long,
    ): PortableRollupStepResult =
        rawHandler.awaitRawDriver(inTransaction = true) { driver ->
            val latest = checkNotNull(
                driver.loadPortableMergeCheckpoint(checkpoint.archiveDigest),
            )
            if (!latest.matchesRollupCheckpoint(checkpoint)) {
                return@awaitRawDriver PortableRollupStepResult(latest)
            }
            progress.requireDatabaseRevision(driver.immersionRevision())
            val page = driver.immersionLifetimeRollupFingerprintPage(
                afterScopeKey = progress.lifetimeCursor,
                limit = portableMergeLifetimePageSize,
                seedDigest = progress.digest,
            )
            val advanced = if (page.fingerprint.rowCount == 0L) {
                progress.completeLifetimeFingerprint()
            } else {
                progress.advanceLifetimeFingerprint(page)
            }
            val updated = latest.copy(
                verificationJson = Json.encodeToString(advanced),
                lastErrorCode = null,
                updatedAt = mergedAtEpochMillis,
                completedAt = null,
            )
            check(driver.compareAndSetPortableRollupCheckpoint(latest, updated)) {
                "Portable rollup checkpoint moved while fingerprinting lifetime pass " +
                    progress.pass
            }
            PortableRollupStepResult(
                checkpoint = updated,
                observerCheckpoint = page.lastScopeKey?.let {
                    PortableRollupObserverCheckpoint(
                        name = if (progress.pass == PORTABLE_ROLLUP_FIRST_PASS) {
                            IMMERSION_ROLLUP_FIRST_PASS_LIFETIME_CHECKPOINT
                        } else {
                            IMMERSION_ROLLUP_SECOND_PASS_LIFETIME_CHECKPOINT
                        },
                        offset = advanced.completedLifetimePages,
                    )
                },
            )
        }

    private suspend fun completePortableRollupPass(
        rawHandler: AndroidDatabaseHandler,
        checkpoint: PortableMergeCheckpoint,
        progress: PortableRollupPassProgress,
        mergedAtEpochMillis: Long,
    ): PortableRollupStepResult =
        handler.await(inTransaction = true) {
            val latest = checkNotNull(
                rawHandler.awaitRawDriver {
                    it.loadPortableMergeCheckpoint(checkpoint.archiveDigest)
                },
            )
            if (!latest.matchesRollupCheckpoint(checkpoint)) {
                return@await PortableRollupStepResult(latest)
            }
            progress.requireDatabaseRevision(
                immersionQueries.selectImmersionRevision().executeAsOne(),
            )
            immersionQueries.updateImmersionRepairState(
                rollupVersion = ImmersionStatsVersions.ROLLUP.toLong(),
                repairCursor = null,
                updatedAt = mergedAtEpochMillis,
            )
            val fingerprint = PortableContentFingerprint(
                rowCount = Math.addExact(progress.dailyRowCount, progress.lifetimeRowCount),
                digest = progress.digest,
            )
            if (progress.pass == PORTABLE_ROLLUP_FIRST_PASS) {
                val firstPass = PortableRollupFirstPassEvidence(
                    rebuiltRollupRows = progress.rebuiltRollupRows,
                    rowCount = fingerprint.rowCount,
                    digest = fingerprint.digest,
                    fingerprintVersion = PORTABLE_ROLLUP_FINGERPRINT_VERSION,
                )
                val updated = latest.copy(
                    stage = PortableMergeStage.ROLLUP_VERIFY,
                    rebuiltRollupRows = firstPass.rebuiltRollupRows,
                    verificationJson = Json.encodeToString(firstPass),
                    lastErrorCode = null,
                    updatedAt = mergedAtEpochMillis,
                    completedAt = null,
                )
                check(
                    rawHandler.awaitRawDriver {
                        it.compareAndSetPortableRollupCheckpoint(latest, updated)
                    },
                ) {
                    "Portable rollup checkpoint moved while completing first pass"
                }
                return@await PortableRollupStepResult(
                    checkpoint = updated,
                    observerCheckpoint = PortableRollupObserverCheckpoint(
                        name = IMMERSION_ROLLUP_FIRST_PASS_CHECKPOINT,
                        offset = 0,
                    ),
                )
            }

            val firstPass = checkNotNull(progress.firstPass) {
                "Portable rollup verification lost first-pass evidence"
            }
            val integrity = selectImmersionIntegrityReportInDatabase(
                expectedRollupVersion = ImmersionStatsVersions.ROLLUP,
                foreignKeyViolations = rawHandler.awaitRawDriver {
                    it.immersionForeignKeyViolationCount()
                },
            )
            val entityCounts = rawHandler.awaitRawDriver {
                it.immersionMergeEntityCounts()
            }
            val databaseRevision = immersionQueries
                .selectImmersionRevision()
                .executeAsOne()
            val verification = ImmersionMergeVerification(
                archiveDigest = checkpoint.archiveDigest,
                eligibleRows = latest.eligibleRowCount,
                accountedRows = latest.accountedRows,
                firstRollupRows = firstPass.rowCount,
                secondRollupRows = fingerprint.rowCount,
                firstRollupDigest = firstPass.digest,
                secondRollupDigest = fingerprint.digest,
                entityCounts = entityCounts,
                integrity = integrity,
                evidenceVersion = ImmersionMergeVerification.CURRENT_EVIDENCE_VERSION,
                databaseRevision = databaseRevision,
            )
            val updated = latest.copy(
                stage = if (verification.isHealthy) {
                    PortableMergeStage.COMPLETE
                } else {
                    PortableMergeStage.VALIDATION_FAILED
                },
                rebuiltRollupRows = firstPass.rebuiltRollupRows,
                verificationJson = Json.encodeToString(verification),
                lastErrorCode = verification.failureCode(),
                updatedAt = mergedAtEpochMillis,
                completedAt = mergedAtEpochMillis.takeIf { verification.isHealthy },
            )
            check(
                rawHandler.awaitRawDriver {
                    it.compareAndSetPortableRollupCheckpoint(latest, updated)
                },
            ) {
                "Portable rollup checkpoint moved while completing verification"
            }
            PortableRollupStepResult(
                checkpoint = updated,
                completedByThisCall = verification.isHealthy,
            )
        }

    override suspend fun deleteRawText(
        titleId: TitleId?,
        beforeEpochMillis: Long?,
        updatedAtEpochMillis: Long,
    ): Long {
        require(beforeEpochMillis == null || beforeEpochMillis >= 0)
        require(updatedAtEpochMillis >= 0)
        val rawHandler = handler.requireRawHandler()
        return rawHandler.awaitRawDriver(inTransaction = true) { driver ->
            val sourceClauses = buildList {
                if (titleId != null) add("title_id = ?")
                if (beforeEpochMillis != null) add("last_exposed_at < ?")
                add("raw_text IS NOT NULL")
            }
            var parameter = 0
            val sourceChanged = driver.execute(
                identifier = null,
                sql = "UPDATE immersion_source_unit SET raw_text = NULL, raw_text_encoding = NULL " +
                    "WHERE ${sourceClauses.joinToString(" AND ")}",
                parameters = sourceClauses.size - 1,
            ) {
                titleId?.let { value -> bindString(parameter++, value.value) }
                beforeEpochMillis?.let { value -> bindLong(parameter++, value) }
            }.value
            driver.execute(
                identifier = null,
                sql = """
                    INSERT INTO immersion_retention_state(
                        scope_key,
                        cleanup_cursor,
                        last_success_at,
                        last_error_code,
                        updated_at
                    ) VALUES ('raw_text', NULL, ?, NULL, ?)
                    ON CONFLICT(scope_key) DO UPDATE SET
                        cleanup_cursor = NULL,
                        last_success_at = excluded.last_success_at,
                        last_error_code = NULL,
                        updated_at = excluded.updated_at
                """.trimIndent(),
                parameters = 2,
            ) {
                bindLong(0, updatedAtEpochMillis)
                bindLong(1, updatedAtEpochMillis)
            }.value
            driver.notifyListeners("immersion_source_unit")
            sourceChanged
        }
    }

    override suspend fun previewRawTextDeletion(
        titleId: TitleId?,
        beforeEpochMillis: Long?,
    ): Long {
        require(beforeEpochMillis == null || beforeEpochMillis >= 0)
        val rawHandler = handler.requireRawHandler()
        return rawHandler.awaitRawDriver { driver ->
            val sourceClauses = buildList {
                if (titleId != null) add("title_id = ?")
                if (beforeEpochMillis != null) add("last_exposed_at < ?")
                add("raw_text IS NOT NULL")
            }
            var parameter = 0
            val sourceCount = driver.executeQuery(
                identifier = null,
                sql = "SELECT count(*) FROM immersion_source_unit " +
                    "WHERE ${sourceClauses.joinToString(" AND ")}",
                mapper = { cursor ->
                    check(cursor.next().value)
                    QueryResult.Value(checkNotNull(cursor.getLong(0)))
                },
                parameters = sourceClauses.size - 1,
            ) {
                titleId?.let { value -> bindString(parameter++, value.value) }
                beforeEpochMillis?.let { value -> bindLong(parameter++, value) }
            }.value
            sourceCount
        }
    }

    override suspend fun setTitleCaptureExcluded(
        titleId: TitleId,
        excluded: Boolean,
        updatedAtEpochMillis: Long,
    ) {
        require(updatedAtEpochMillis >= 0)
        handler.await(inTransaction = true) {
            val titleIds = captureExclusionTitleIds(titleId.value)
            val resolvedTitleId = resolveImmersionTitleId(titleId.value)
            if (excluded) {
                immersionQueries.upsertImmersionExclusion(
                    id = "capture-title:$resolvedTitleId",
                    entityType = IMMERSION_TITLE_EXCLUSION_TYPE,
                    entityId = resolvedTitleId,
                    scopeKey = IMMERSION_CAPTURE_EXCLUSION_SCOPE,
                    reason = "USER_CAPTURE_EXCLUSION",
                    createdAt = updatedAtEpochMillis,
                )
            } else {
                titleIds.forEach { candidate ->
                    immersionQueries.deleteImmersionExclusion(
                        entityType = IMMERSION_TITLE_EXCLUSION_TYPE,
                        entityId = candidate,
                        scopeKey = IMMERSION_CAPTURE_EXCLUSION_SCOPE,
                    )
                }
            }
            immersionQueries.incrementImmersionRevision(updatedAtEpochMillis)
        }
    }

    override suspend fun unlinkTitle(
        titleId: TitleId,
        updatedAtEpochMillis: Long,
    ): Boolean {
        require(updatedAtEpochMillis >= 0)
        return handler.await(inTransaction = true) {
            if (immersionQueries.selectImmersionTitleById(titleId.value).executeAsOneOrNull() == null) {
                return@await false
            }
            immersionQueries.unlinkImmersionTitle(
                unlinkedAt = updatedAtEpochMillis,
                id = titleId.value,
            )
            checkExactlyOneChange("unlinking title ${titleId.value}")
            immersionQueries.incrementImmersionRevision(updatedAtEpochMillis)
            true
        }
    }

    override suspend fun previewTitleMutation(
        request: ImmersionTitleMutationRequest,
    ): ImmersionTitleMutationPreview =
        handler.await {
            titleMutationSnapshot(request).preview
        }

    override suspend fun applyTitleMutation(
        expectedPreview: ImmersionTitleMutationPreview,
        appliedAtEpochMillis: Long,
    ): ImmersionTitleMutation {
        require(appliedAtEpochMillis >= 0)
        require(expectedPreview.canApply) { "Blocked title mutation cannot be applied" }
        return IMMERSION_ROLLUP_MUTATION_MUTEX.withLock {
            handler.await(inTransaction = true) {
                val snapshot = titleMutationSnapshot(expectedPreview.request)
                check(snapshot.preview == expectedPreview) {
                    "Title mutation preview is stale"
                }
                val source = checkNotNull(snapshot.source)
                val operationId = UUID.randomUUID().toString()
                val request = expectedPreview.request
                val type = request.mutationType()
                val targetTitleId = request.targetTitleIdOrNull()

                if (request is ImmersionTitleMutationRequest.Split) {
                    upsertTitleInDatabase(
                        ImmersionTitle(
                            id = request.targetTitleId,
                            mediaKind = MediaKind.valueOf(source.media_kind),
                            sourceKey = "split:${request.targetTitleId.value}",
                            profileId = source.profile_id,
                            languageTag = source.language_tag?.let(::LanguageTag),
                            displayTitle = request.displayTitle.trim(),
                            createdAtEpochMillis = appliedAtEpochMillis,
                            updatedAtEpochMillis = appliedAtEpochMillis,
                        ),
                    )
                }

                immersionQueries.insertImmersionTitleMutation(
                    id = operationId,
                    type = type.name,
                    sourceTitleId = source.id,
                    targetTitleId = targetTitleId?.value,
                    displayTitle = (request as? ImmersionTitleMutationRequest.Rename)
                        ?.displayTitle
                        ?.trim(),
                    previousDisplayTitle = immersionQueries
                        .selectImmersionTitleOverride(source.id)
                        .executeAsOneOrNull(),
                    sourceDeletedAtBefore = source.deleted_at,
                    selectionDigest = expectedPreview.selectionDigest,
                    sessions = snapshot.sessions.size.toLong(),
                    sourceUnits = snapshot.sources.size.toLong(),
                    goals = snapshot.goals.size.toLong(),
                    appliedAt = appliedAtEpochMillis,
                )

                when (request) {
                    is ImmersionTitleMutationRequest.Rename -> {
                        immersionQueries.upsertImmersionTitleOverride(
                            titleId = request.sourceTitleId.value,
                            displayTitle = request.displayTitle.trim(),
                            updatedAt = appliedAtEpochMillis,
                        )
                    }
                    is ImmersionTitleMutationRequest.Merge -> {
                        journalAndMoveTitleMutationRows(
                            operationId = operationId,
                            sessions = snapshot.sessions,
                            sources = snapshot.sources,
                            goals = snapshot.goals,
                            targetTitleId = request.targetTitleId,
                            updatedAtEpochMillis = appliedAtEpochMillis,
                        )
                        immersionQueries.insertImmersionTitleAlias(
                            sourceTitleId = request.sourceTitleId.value,
                            targetTitleId = request.targetTitleId.value,
                            operationId = operationId,
                            createdAt = appliedAtEpochMillis,
                        )
                        immersionQueries.markImmersionTitleMerged(
                            mergedAt = appliedAtEpochMillis,
                            id = request.sourceTitleId.value,
                        )
                        beginRollupRebuildInDatabase(
                            rollupVersion = ImmersionStatsVersions.ROLLUP,
                            repairCursor = "title-mutation:$operationId",
                            updatedAtEpochMillis = appliedAtEpochMillis,
                        )
                    }
                    is ImmersionTitleMutationRequest.RelinkSession -> {
                        journalAndMoveTitleMutationRows(
                            operationId = operationId,
                            sessions = snapshot.sessions,
                            sources = snapshot.sources,
                            goals = emptyList(),
                            targetTitleId = request.targetTitleId,
                            updatedAtEpochMillis = appliedAtEpochMillis,
                        )
                        beginRollupRebuildInDatabase(
                            rollupVersion = ImmersionStatsVersions.ROLLUP,
                            repairCursor = "title-mutation:$operationId",
                            updatedAtEpochMillis = appliedAtEpochMillis,
                        )
                    }
                    is ImmersionTitleMutationRequest.Split -> {
                        journalAndMoveTitleMutationRows(
                            operationId = operationId,
                            sessions = snapshot.sessions,
                            sources = snapshot.sources,
                            goals = emptyList(),
                            targetTitleId = request.targetTitleId,
                            updatedAtEpochMillis = appliedAtEpochMillis,
                        )
                        beginRollupRebuildInDatabase(
                            rollupVersion = ImmersionStatsVersions.ROLLUP,
                            repairCursor = "title-mutation:$operationId",
                            updatedAtEpochMillis = appliedAtEpochMillis,
                        )
                    }
                }
                immersionQueries.incrementImmersionRevision(appliedAtEpochMillis)
                checkNotNull(
                    immersionQueries.selectImmersionTitleMutation(operationId).executeAsOneOrNull(),
                ).toDomain()
            }
        }
    }

    override suspend fun rollbackTitleMutation(
        operationId: String,
        rolledBackAtEpochMillis: Long,
    ): ImmersionTitleMutation {
        require(operationId.isNotBlank())
        require(rolledBackAtEpochMillis >= 0)
        return IMMERSION_ROLLUP_MUTATION_MUTEX.withLock {
            handler.await(inTransaction = true) {
                val operation = checkNotNull(
                    immersionQueries.selectImmersionTitleMutation(operationId).executeAsOneOrNull(),
                ) {
                    "Title mutation $operationId does not exist"
                }
                check(operation.status == TITLE_MUTATION_APPLIED) {
                    "Title mutation $operationId is not active"
                }
                check(rolledBackAtEpochMillis >= operation.applied_at) {
                    "Rollback timestamp precedes the title mutation"
                }
                when (ImmersionTitleMutationType.valueOf(operation.type)) {
                    ImmersionTitleMutationType.RENAME -> {
                        val current = immersionQueries
                            .selectImmersionTitleOverride(operation.source_title_id)
                            .executeAsOneOrNull()
                        check(current == operation.display_title) {
                            "Title has been renamed again since this operation"
                        }
                        operation.previous_display_title?.let { previous ->
                            immersionQueries.upsertImmersionTitleOverride(
                                titleId = operation.source_title_id,
                                displayTitle = previous,
                                updatedAt = rolledBackAtEpochMillis,
                            )
                        } ?: immersionQueries.deleteImmersionTitleOverride(operation.source_title_id)
                    }
                    ImmersionTitleMutationType.MERGE -> {
                        rollbackMergeTitleMutation(operation, rolledBackAtEpochMillis)
                    }
                    ImmersionTitleMutationType.RELINK -> {
                        rollbackRelinkTitleMutation(operation, rolledBackAtEpochMillis)
                    }
                    ImmersionTitleMutationType.SPLIT -> {
                        rollbackSplitTitleMutation(operation, rolledBackAtEpochMillis)
                    }
                }
                immersionQueries.markImmersionTitleMutationRolledBack(
                    rolledBackAt = rolledBackAtEpochMillis,
                    id = operationId,
                )
                checkExactlyOneChange("rolling back title mutation $operationId")
                immersionQueries.incrementImmersionRevision(rolledBackAtEpochMillis)
                checkNotNull(
                    immersionQueries.selectImmersionTitleMutation(operationId).executeAsOneOrNull(),
                ).toDomain()
            }
        }
    }

    override suspend fun titleMutations(titleId: TitleId): List<ImmersionTitleMutation> =
        handler.await {
            immersionQueries.selectImmersionTitleMutations(titleId.value)
                .executeAsList()
                .map(Immersion_title_mutation::toDomain)
        }

    private fun Database.titleMutationSnapshot(
        request: ImmersionTitleMutationRequest,
    ): TitleMutationSnapshot {
        val revision = immersionQueries.selectImmersionRevision().executeAsOne()
        val source = immersionQueries
            .selectImmersionTitleById(request.sourceTitleId.value)
            .executeAsOneOrNull()
        val targetTitleId = request.targetTitleIdOrNull()
        val target = targetTitleId?.let {
            immersionQueries.selectImmersionTitleById(it.value).executeAsOneOrNull()
        }
        val blockers = linkedSetOf<ImmersionTitleMutationBlocker>()
        if (source == null) blockers += ImmersionTitleMutationBlocker.SOURCE_NOT_FOUND
        when (request) {
            is ImmersionTitleMutationRequest.Rename -> Unit
            is ImmersionTitleMutationRequest.Merge,
            is ImmersionTitleMutationRequest.RelinkSession,
            -> {
                if (request.sourceTitleId == targetTitleId) {
                    blockers += ImmersionTitleMutationBlocker.SAME_TITLE
                }
                if (target == null) blockers += ImmersionTitleMutationBlocker.TARGET_NOT_FOUND
                if (source != null && target != null) {
                    if (source.media_kind != target.media_kind) {
                        blockers += ImmersionTitleMutationBlocker.INCOMPATIBLE_MEDIA
                    }
                    if (source.profile_id != target.profile_id) {
                        blockers += ImmersionTitleMutationBlocker.INCOMPATIBLE_PROFILE
                    }
                    if (source.language_tag != target.language_tag) {
                        blockers += ImmersionTitleMutationBlocker.INCOMPATIBLE_LANGUAGE
                    }
                }
            }
            is ImmersionTitleMutationRequest.Split -> {
                if (target != null) blockers += ImmersionTitleMutationBlocker.TARGET_ALREADY_EXISTS
            }
        }

        val aliasTitleIds = listOfNotNull(source?.id, target?.id).distinct()
        val aliases = if (aliasTitleIds.isEmpty()) {
            emptyList()
        } else {
            immersionQueries.selectImmersionTitleAliasesForTitles(aliasTitleIds).executeAsList()
        }
        if (aliases.isNotEmpty()) blockers += ImmersionTitleMutationBlocker.ACTIVE_ALIAS

        val sessions = when {
            source == null -> emptyList()
            request is ImmersionTitleMutationRequest.RelinkSession ->
                immersionQueries.selectImmersionSessionForTitleRelink(
                    sessionId = request.sessionId.value,
                    sourceTitleId = source.id,
                ).executeAsList().map {
                    TitleMutationRow(it.id, it.title_id)
                }
            request is ImmersionTitleMutationRequest.Split ->
                immersionQueries.selectImmersionSessionsForTitleSplit(
                    titleId = source.id,
                    startDate = request.dateRange.start.epochDay,
                    endDate = request.dateRange.endInclusive.epochDay,
                ).executeAsList().map {
                    TitleMutationRow(it.id, it.title_id)
                }
            else -> immersionQueries.selectImmersionSessionsForTitleMutation(source.id)
                .executeAsList()
                .map { TitleMutationRow(it.id, it.title_id) }
        }
        val targetHasActiveSession = if (request is ImmersionTitleMutationRequest.Merge && target != null) {
            immersionQueries.selectImmersionSessionsForTitleMutation(target.id)
                .executeAsList()
                .any { it.status == SessionStatus.ACTIVE.name }
        } else {
            false
        }
        val selectedHasActiveSession = sessions.any { row ->
            immersionQueries.selectImmersionSessionById(row.id)
                .executeAsOne()
                .status == SessionStatus.ACTIVE.name
        }
        if (
            request !is ImmersionTitleMutationRequest.Rename &&
            (selectedHasActiveSession || targetHasActiveSession)
        ) {
            blockers += ImmersionTitleMutationBlocker.ACTIVE_SESSION
        }
        if (request is ImmersionTitleMutationRequest.Split && sessions.isEmpty()) {
            blockers += ImmersionTitleMutationBlocker.EMPTY_SELECTION
        }
        if (request is ImmersionTitleMutationRequest.RelinkSession && sessions.isEmpty()) {
            blockers += ImmersionTitleMutationBlocker.SESSION_NOT_FOUND
        }

        val sessionIds = sessions.map(TitleMutationRow::id)
        val movesSessionSubset = request is ImmersionTitleMutationRequest.RelinkSession ||
            request is ImmersionTitleMutationRequest.Split
        val sources = when {
            source == null -> emptyList()
            movesSessionSubset && sessionIds.isNotEmpty() ->
                immersionQueries.selectImmersionSourcesForTitleSplit(sessionIds, source.id)
                    .executeAsList()
                    .map { TitleMutationRow(it.id, it.title_id) }
            movesSessionSubset -> emptyList()
            else -> immersionQueries.selectImmersionSourcesForTitleMutation(source.id)
                .executeAsList()
                .map { TitleMutationRow(it.id, it.title_id) }
        }
        val sharedSourceIds = if (
            movesSessionSubset &&
            source != null &&
            sessionIds.isNotEmpty()
        ) {
            immersionQueries.selectImmersionSharedSourcesForTitleSplit(sessionIds, source.id)
                .executeAsList()
        } else {
            emptyList()
        }
        if (sharedSourceIds.isNotEmpty()) {
            blockers += ImmersionTitleMutationBlocker.SHARED_SOURCE_UNITS
        }
        val conflictSourceIds = if (
            request is ImmersionTitleMutationRequest.Merge &&
            source != null &&
            target != null
        ) {
            immersionQueries.selectImmersionMergeSourceConflicts(source.id, target.id)
                .executeAsList()
        } else if (
            request is ImmersionTitleMutationRequest.RelinkSession &&
            target != null &&
            sources.isNotEmpty()
        ) {
            immersionQueries.selectImmersionRelinkSourceConflicts(
                sourceUnitIds = sources.map(TitleMutationRow::id),
                targetTitleId = target.id,
            ).executeAsList()
        } else {
            emptyList()
        }
        if (conflictSourceIds.isNotEmpty()) {
            blockers += ImmersionTitleMutationBlocker.SOURCE_IDENTITY_CONFLICT
        }
        val goals = if (
            source == null ||
            request is ImmersionTitleMutationRequest.RelinkSession ||
            request is ImmersionTitleMutationRequest.Split
        ) {
            emptyList()
        } else {
            immersionQueries.selectImmersionGoalsForTitleMutation(source.id)
                .executeAsList()
                .map { TitleMutationRow(it.id, checkNotNull(it.title_id)) }
        }
        val impact = if (sessionIds.isEmpty()) {
            TitleMutationImpact()
        } else {
            immersionQueries.selectImmersionTitleMutationImpact(sessionIds)
                .executeAsOne()
                .let {
                    TitleMutationImpact(
                        events = it.events,
                        ankiOperations = it.anki_operations,
                    )
                }
        }
        val digest = titleMutationSelectionDigest(
            request = request,
            source = source,
            target = target,
            sessions = sessions,
            sources = sources,
            goals = goals,
            aliases = aliases.map { it.source_title_id to it.target_title_id },
            conflictSourceIds = conflictSourceIds + sharedSourceIds,
            databaseRevision = revision,
        )
        return TitleMutationSnapshot(
            preview = ImmersionTitleMutationPreview(
                request = request,
                sessions = sessions.size.toLong(),
                events = impact.events,
                sourceUnits = sources.size.toLong(),
                ankiOperations = impact.ankiOperations,
                goals = goals.size.toLong(),
                conflictingSourceUnits = (conflictSourceIds + sharedSourceIds)
                    .distinct()
                    .size
                    .toLong(),
                selectionDigest = digest,
                databaseRevision = revision,
                blockers = blockers,
            ),
            source = source,
            target = target,
            sessions = sessions,
            sources = sources,
            goals = goals,
        )
    }

    private fun Database.journalAndMoveTitleMutationRows(
        operationId: String,
        sessions: List<TitleMutationRow>,
        sources: List<TitleMutationRow>,
        goals: List<TitleMutationRow>,
        targetTitleId: TitleId,
        updatedAtEpochMillis: Long,
    ) {
        sessions.forEach { row ->
            val originTitleId = immersionQueries
                .selectImmersionSessionOrigin(row.id)
                .executeAsOneOrNull()
                ?: row.previousTitleId
            immersionQueries.insertImmersionSessionOrigin(row.id, originTitleId)
            immersionQueries.insertImmersionTitleMutationSession(
                operationId = operationId,
                sessionId = row.id,
                previousTitleId = row.previousTitleId,
            )
        }
        sessions.map(TitleMutationRow::id).chunked(SQLITE_BIND_BATCH_SIZE).forEach { ids ->
            immersionQueries.updateImmersionSessionTitles(targetTitleId.value, ids)
        }
        sources.forEach { row ->
            val originTitleId = immersionQueries
                .selectImmersionSourceOrigin(row.id)
                .executeAsOneOrNull()
                ?: row.previousTitleId
            immersionQueries.insertImmersionSourceOrigin(row.id, originTitleId)
            immersionQueries.insertImmersionTitleMutationSource(
                operationId = operationId,
                sourceUnitId = row.id,
                previousTitleId = row.previousTitleId,
            )
        }
        sources.map(TitleMutationRow::id).chunked(SQLITE_BIND_BATCH_SIZE).forEach { ids ->
            immersionQueries.updateImmersionSourceTitles(targetTitleId.value, ids)
        }
        goals.forEach { row ->
            immersionQueries.insertImmersionTitleMutationGoal(
                operationId = operationId,
                goalId = row.id,
                previousTitleId = row.previousTitleId,
            )
        }
        goals.map(TitleMutationRow::id).chunked(SQLITE_BIND_BATCH_SIZE).forEach { ids ->
            immersionQueries.updateImmersionGoalTitles(
                titleId = targetTitleId.value,
                updatedAt = updatedAtEpochMillis,
                goalIds = ids,
            )
        }
    }

    private fun Database.rollbackMergeTitleMutation(
        operation: Immersion_title_mutation,
        rolledBackAtEpochMillis: Long,
    ) {
        val targetTitleId = checkNotNull(operation.target_title_id)
        val alias = immersionQueries
            .selectImmersionTitleAliasesForTitles(
                listOf(operation.source_title_id, targetTitleId),
            )
            .executeAsList()
        check(
            alias.size == 1 &&
                alias.single().source_title_id == operation.source_title_id &&
                alias.single().target_title_id == targetTitleId,
        ) {
            "Merged title alias changed after the operation"
        }
        val journalSessions = immersionQueries
            .selectImmersionTitleMutationSessions(operation.id)
            .executeAsList()
            .associate { it.session_id to it.previous_title_id }
        val sessions = (
            journalSessions.keys +
                immersionQueries.selectImmersionSessionsByOriginAndTitle(
                    originTitleId = operation.source_title_id,
                    titleId = targetTitleId,
                ).executeAsList().map { it.id }
            ).distinct()
        check(
            sessions.all { id ->
                immersionQueries.selectImmersionSessionById(id).executeAsOne().title_id == targetTitleId
            },
        ) {
            "Merged sessions were reassigned after the operation"
        }
        sessions.chunked(SQLITE_BIND_BATCH_SIZE).forEach { ids ->
            immersionQueries.updateImmersionSessionTitles(operation.source_title_id, ids)
        }

        val journalSources = immersionQueries
            .selectImmersionTitleMutationSources(operation.id)
            .executeAsList()
            .associate { it.source_unit_id to it.previous_title_id }
        val sources = (
            journalSources.keys +
                immersionQueries.selectImmersionSourcesByOriginAndTitle(
                    originTitleId = operation.source_title_id,
                    titleId = targetTitleId,
                ).executeAsList().map { it.id }
            ).distinct()
        check(
            sources.all { id ->
                immersionQueries.selectImmersionSourceUnitById(id).executeAsOne().title_id == targetTitleId
            },
        ) {
            "Merged source units were reassigned after the operation"
        }
        sources.chunked(SQLITE_BIND_BATCH_SIZE).forEach { ids ->
            immersionQueries.updateImmersionSourceTitles(operation.source_title_id, ids)
        }
        restoreTitleMutationGoals(operation, rolledBackAtEpochMillis)
        immersionQueries.deleteImmersionTitleAlias(
            sourceTitleId = operation.source_title_id,
            targetTitleId = targetTitleId,
            operationId = operation.id,
        )
        checkExactlyOneChange("removing merged title alias ${operation.id}")
        immersionQueries.restoreImmersionTitleDeletedAt(
            deletedAt = operation.source_deleted_at_before,
            updatedAt = rolledBackAtEpochMillis,
            id = operation.source_title_id,
        )
        beginRollupRebuildInDatabase(
            rollupVersion = ImmersionStatsVersions.ROLLUP,
            repairCursor = "title-rollback:${operation.id}",
            updatedAtEpochMillis = rolledBackAtEpochMillis,
        )
    }

    private fun Database.rollbackSplitTitleMutation(
        operation: Immersion_title_mutation,
        rolledBackAtEpochMillis: Long,
    ) {
        val targetTitleId = checkNotNull(operation.target_title_id)
        val sessions = immersionQueries.selectImmersionTitleMutationSessions(operation.id)
            .executeAsList()
        val sources = immersionQueries.selectImmersionTitleMutationSources(operation.id)
            .executeAsList()
        val targetSessions = immersionQueries.selectImmersionSessionsForTitleMutation(targetTitleId)
            .executeAsList()
            .map { it.id }
        val targetSources = immersionQueries.selectImmersionSourcesForTitleMutation(targetTitleId)
            .executeAsList()
            .map { it.id }
        val targetGoals = immersionQueries.selectImmersionGoalsForTitleMutation(targetTitleId)
            .executeAsList()
        val targetAliases = immersionQueries
            .selectImmersionTitleAliasesForTitles(listOf(targetTitleId))
            .executeAsList()
        check(targetSessions.toSet() == sessions.map { it.session_id }.toSet()) {
            "Split title has sessions created after the operation"
        }
        check(targetSources.toSet() == sources.map { it.source_unit_id }.toSet()) {
            "Split title has source units created after the operation"
        }
        check(targetGoals.isEmpty() && targetAliases.isEmpty()) {
            "Split title has dependent state created after the operation"
        }
        sessions.groupBy { it.previous_title_id }.forEach { (titleId, rows) ->
            rows.map { it.session_id }.chunked(SQLITE_BIND_BATCH_SIZE).forEach { ids ->
                immersionQueries.updateImmersionSessionTitles(titleId, ids)
            }
        }
        sources.groupBy { it.previous_title_id }.forEach { (titleId, rows) ->
            rows.map { it.source_unit_id }.chunked(SQLITE_BIND_BATCH_SIZE).forEach { ids ->
                immersionQueries.updateImmersionSourceTitles(titleId, ids)
            }
        }
        immersionQueries.deleteImmersionTitleById(targetTitleId)
        checkExactlyOneChange("deleting rolled-back split title $targetTitleId")
        beginRollupRebuildInDatabase(
            rollupVersion = ImmersionStatsVersions.ROLLUP,
            repairCursor = "title-rollback:${operation.id}",
            updatedAtEpochMillis = rolledBackAtEpochMillis,
        )
    }

    private fun Database.rollbackRelinkTitleMutation(
        operation: Immersion_title_mutation,
        rolledBackAtEpochMillis: Long,
    ) {
        val targetTitleId = checkNotNull(operation.target_title_id)
        val sessions = immersionQueries.selectImmersionTitleMutationSessions(operation.id)
            .executeAsList()
        val sources = immersionQueries.selectImmersionTitleMutationSources(operation.id)
            .executeAsList()
        check(
            sessions.size == 1 && sessions.all { row ->
                immersionQueries.selectImmersionSessionById(row.session_id)
                    .executeAsOne()
                    .title_id == targetTitleId
            },
        ) {
            "Relinked session was reassigned after the operation"
        }
        check(
            sources.all { row ->
                immersionQueries.selectImmersionSourceUnitById(row.source_unit_id)
                    .executeAsOne()
                    .title_id == targetTitleId
            },
        ) {
            "Relinked source units were reassigned after the operation"
        }
        sessions.groupBy { it.previous_title_id }.forEach { (titleId, rows) ->
            rows.map { it.session_id }.chunked(SQLITE_BIND_BATCH_SIZE).forEach { ids ->
                immersionQueries.updateImmersionSessionTitles(titleId, ids)
            }
        }
        sources.groupBy { it.previous_title_id }.forEach { (titleId, rows) ->
            rows.map { it.source_unit_id }.chunked(SQLITE_BIND_BATCH_SIZE).forEach { ids ->
                immersionQueries.updateImmersionSourceTitles(titleId, ids)
            }
        }
        beginRollupRebuildInDatabase(
            rollupVersion = ImmersionStatsVersions.ROLLUP,
            repairCursor = "title-rollback:${operation.id}",
            updatedAtEpochMillis = rolledBackAtEpochMillis,
        )
    }

    private fun Database.restoreTitleMutationGoals(
        operation: Immersion_title_mutation,
        rolledBackAtEpochMillis: Long,
    ) {
        immersionQueries.selectImmersionTitleMutationGoals(operation.id)
            .executeAsList()
            .groupBy { it.previous_title_id }
            .forEach { (titleId, rows) ->
                rows.map { it.goal_id }.chunked(SQLITE_BIND_BATCH_SIZE).forEach { ids ->
                    immersionQueries.updateImmersionGoalTitles(
                        titleId = titleId,
                        updatedAt = rolledBackAtEpochMillis,
                        goalIds = ids,
                    )
                }
            }
    }

    override suspend fun resolveMergeConflictsKeepingLocal(): Long =
        handler.await(inTransaction = true) {
            immersionQueries.resolveImmersionMergeConflictsKeepingLocal()
            immersionQueries.selectImmersionChanges().executeAsOne()
        }

    override suspend fun upsertGoal(goal: ImmersionGoal) {
        handler.await(inTransaction = true) {
            upsertGoalInDatabase(goal)
            immersionQueries.incrementImmersionRevision(goal.updatedAtEpochMillis)
        }
    }

    override suspend fun restartGoal(
        expectedGoal: ImmersionGoal,
        replacementGoal: ImmersionGoal,
        restartedAtEpochMillis: Long,
    ): Boolean {
        require(expectedGoal.id != replacementGoal.id) {
            "Restarted goal must use a new identity"
        }
        require(restartedAtEpochMillis >= expectedGoal.updatedAtEpochMillis)
        require(replacementGoal.createdAtEpochMillis == restartedAtEpochMillis)
        require(replacementGoal.updatedAtEpochMillis == restartedAtEpochMillis)
        require(replacementGoal.state == "ACTIVE")
        return handler.await(inTransaction = true) {
            val current = immersionQueries
                .selectImmersionGoalById(expectedGoal.id)
                .executeAsOneOrNull()
                ?.toDomain()
                ?: return@await false
            if (current != expectedGoal) return@await false
            if (
                immersionQueries
                    .selectImmersionGoalById(replacementGoal.id)
                    .executeAsOneOrNull() != null
            ) {
                return@await false
            }
            upsertGoalInDatabase(
                current.copy(
                    state = "ARCHIVED",
                    updatedAtEpochMillis = restartedAtEpochMillis,
                ),
            )
            upsertGoalInDatabase(replacementGoal)
            immersionQueries.incrementImmersionRevision(restartedAtEpochMillis)
            true
        }
    }

    private fun Database.upsertGoalInDatabase(goal: ImmersionGoal) {
        immersionQueries.upsertImmersionGoal(
            id = goal.id,
            type = goal.type,
            metric = goal.metric,
            target = goal.target,
            period = goal.period,
            startDate = goal.startDate?.epochDay,
            endDate = goal.endDate?.epochDay,
            mediaKind = goal.mediaKind?.name,
            profileId = goal.profileId,
            languageTag = goal.languageTag?.value,
            titleId = goal.titleId?.value,
            weekdayMultipliers = goal.weekdayMultipliers,
            restDayPolicy = goal.restDayPolicy,
            state = goal.state,
            createdAt = goal.createdAtEpochMillis,
            updatedAt = goal.updatedAtEpochMillis,
        )
    }

    override suspend fun getGoals(): List<ImmersionGoal> =
        handler.await {
            immersionQueries.selectImmersionGoals().executeAsList().map(Immersion_goal::toDomain)
        }

    override suspend fun upsertCheckIn(checkIn: ImmersionGoalCheckIn) {
        handler.await(inTransaction = true) {
            immersionQueries.upsertImmersionGoalCheckIn(
                goalId = checkIn.goalId,
                localDate = checkIn.localDate.epochDay,
                status = checkIn.status,
                note = checkIn.note,
                occurredAt = checkIn.occurredAtEpochMillis,
            )
            immersionQueries.incrementImmersionRevision(checkIn.occurredAtEpochMillis)
        }
    }

    override suspend fun getCheckIns(goalId: String): List<ImmersionGoalCheckIn> {
        require(goalId.isNotBlank())
        return handler.await {
            immersionQueries.selectImmersionGoalCheckIns(goalId).executeAsList().map { row ->
                ImmersionGoalCheckIn(
                    goalId = row.goal_id,
                    localDate = ImmersionLocalDate(row.local_date),
                    status = row.status,
                    note = row.note,
                    occurredAtEpochMillis = row.occurred_at,
                )
            }
        }
    }

    override suspend fun recordAchievement(achievement: ImmersionGoalAchievement) {
        handler.await(inTransaction = true) {
            immersionQueries.insertImmersionGoalAchievement(
                id = achievement.id,
                goalId = achievement.goalId,
                milestoneKey = achievement.milestoneKey,
                earnedAt = achievement.earnedAtEpochMillis,
                targetSnapshot = achievement.targetSnapshot,
            )
            immersionQueries.incrementImmersionRevision(achievement.earnedAtEpochMillis)
        }
    }

    override suspend fun getAchievements(goalId: String): List<ImmersionGoalAchievement> {
        require(goalId.isNotBlank())
        return handler.await {
            immersionQueries.selectImmersionGoalAchievements(goalId).executeAsList().map { row ->
                ImmersionGoalAchievement(
                    id = row.id,
                    goalId = row.goal_id,
                    milestoneKey = row.milestone_key,
                    earnedAtEpochMillis = row.earned_at,
                    targetSnapshot = row.target_snapshot,
                )
            }
        }
    }

    override suspend fun activateSnapshot(
        snapshot: ImmersionAnkiSnapshot,
        items: List<ImmersionAnkiItem>,
    ) {
        require(snapshot.isComplete && snapshot.isCurrent)
        handler.await(inTransaction = true) {
            immersionQueries.clearCurrentImmersionAnkiSnapshot(snapshot.profileId)
            upsertAnkiSnapshot(snapshot, isCurrent = false)
            immersionQueries.deleteImmersionAnkiSnapshotItems(snapshot.id)
            items.forEach { item ->
                require(item.snapshotId == snapshot.id)
                immersionQueries.insertImmersionAnkiItem(
                    snapshotId = item.snapshotId,
                    noteId = item.noteId,
                    cardId = item.cardId,
                    noteTypeId = item.noteTypeId,
                    deckId = item.deckId,
                    languageTag = item.languageTag.value,
                    cardType = item.cardType?.toLong(),
                    queue = item.queue?.toLong(),
                    intervalDays = item.intervalDays?.toLong(),
                    due = item.due,
                    repetitions = item.repetitions?.toLong(),
                    lapses = item.lapses?.toLong(),
                    ease = item.ease?.toLong(),
                    noteModifiedAt = item.noteModifiedAtEpochSeconds,
                    maturityTier = item.maturityTier.name,
                    firstMatureAt = item.firstMatureAtEpochMillis,
                )
                item.characters.forEach { character ->
                    immersionQueries.insertImmersionAnkiCharacter(
                        snapshotId = item.snapshotId,
                        cardId = item.cardId,
                        codePoint = character.value.toLong(),
                    )
                }
            }
            immersionQueries.setCurrentImmersionAnkiSnapshot(snapshot.id)
            immersionQueries.incrementImmersionRevision(
                snapshot.completedAtEpochMillis ?: snapshot.requestedAtEpochMillis,
            )
        }
    }

    override suspend fun recordSnapshotAttempt(snapshot: ImmersionAnkiSnapshot) {
        require(!snapshot.isCurrent)
        handler.await(inTransaction = true) {
            upsertAnkiSnapshot(snapshot, isCurrent = false)
            if (snapshot.status != AnkiSnapshotStatus.COMPLETE) {
                immersionQueries.markCurrentImmersionAnkiSnapshotStale(snapshot.profileId)
            }
            immersionQueries.incrementImmersionRevision(
                snapshot.completedAtEpochMillis ?: snapshot.requestedAtEpochMillis,
            )
        }
    }

    override suspend fun getCurrentSnapshot(profileId: String): ImmersionAnkiSnapshot? =
        handler.await {
            immersionQueries
                .selectCurrentImmersionAnkiSnapshot(profileId)
                .executeAsOneOrNull()
                ?.toDomain()
        }

    override suspend fun getLatestSnapshot(profileId: String): ImmersionAnkiSnapshot? =
        handler.await {
            immersionQueries
                .selectLatestImmersionAnkiSnapshot(profileId)
                .executeAsOneOrNull()
                ?.toDomain()
        }

    override fun observeLatestSnapshot(profileId: String): Flow<ImmersionAnkiSnapshot?> =
        handler.subscribeToOneOrNull {
            immersionQueries.selectLatestImmersionAnkiSnapshot(profileId)
        }.map { it?.toDomain() }

    override suspend fun getCurrentItems(profileId: String): List<ImmersionAnkiItem> =
        handler.await {
            val characters = immersionQueries
                .selectCurrentImmersionAnkiCharacters(profileId)
                .executeAsList()
                .groupBy(
                    keySelector = { it.card_id },
                    valueTransform = { UnicodeCodePoint(it.code_point.toIntExact("Anki character")) },
                )
            immersionQueries.selectCurrentImmersionAnkiItems(profileId)
                .executeAsList()
                .map { it.toDomain(characters[it.card_id].orEmpty().toSet()) }
        }

    override suspend fun findCharacterItems(
        profileId: String,
        codePoint: UnicodeCodePoint,
    ): List<ImmersionAnkiItem> = findCharacterItems(listOf(profileId), codePoint)

    override suspend fun findCharacterItems(
        profileIds: Collection<String>,
        codePoint: UnicodeCodePoint,
    ): List<ImmersionAnkiItem> {
        val normalizedProfileIds = profileIds.filter(String::isNotBlank).distinct()
        if (normalizedProfileIds.isEmpty()) return emptyList()
        return handler.await {
            val items = immersionQueries.selectImmersionAnkiCharacterItemsForProfiles(
                profileIds = normalizedProfileIds,
                codePoint = codePoint.value.toLong(),
            ).executeAsList()
            val characters: Map<Pair<String, Long>, Set<UnicodeCodePoint>> = items
                .map { it.card_id }
                .distinct()
                .chunked(ANKI_CHARACTER_QUERY_BATCH_SIZE)
                .flatMap { cardIds ->
                    immersionQueries.selectImmersionAnkiCharactersForCards(
                        snapshotIds = items.map { it.snapshot_id }.distinct(),
                        cardIds = cardIds,
                    ).executeAsList()
                }
                .groupBy(
                    keySelector = { it.snapshot_id to it.card_id },
                    valueTransform = {
                        UnicodeCodePoint(it.code_point.toIntExact("Anki character"))
                    },
                )
                .mapValues { (_, values) -> values.toSet() }
            items.map { item ->
                item.toDomain(characters[item.snapshot_id to item.card_id].orEmpty())
            }
        }
    }

    override suspend fun getCharacterCoverage(
        profileId: String,
        languageTag: LanguageTag,
    ): AnkiCoverage =
        handler.await {
            immersionQueries.selectImmersionAnkiCharacterCoverage(
                profileId = profileId,
                languageTag = languageTag.value,
            ).executeAsOne().let {
                AnkiCoverage(
                    encountered = it.encountered_count,
                    covered = it.covered_count,
                )
            }
        }

    override suspend fun recomputeCurrentMaturity(
        profileId: String,
        matureIntervalDays: Int,
        recomputedAtEpochMillis: Long,
    ) {
        require(matureIntervalDays > 0)
        handler.await(inTransaction = true) {
            immersionQueries.recomputeCurrentImmersionAnkiMaturity(
                matureIntervalDays = matureIntervalDays.toLong(),
                recomputedAt = recomputedAtEpochMillis,
                profileId = profileId,
            )
            immersionQueries.updateCurrentImmersionAnkiMatureInterval(
                matureIntervalDays = matureIntervalDays.toLong(),
                profileId = profileId,
            )
            immersionQueries.incrementImmersionRevision(recomputedAtEpochMillis)
        }
    }

    override suspend fun clearSnapshots(profileId: String): Long =
        handler.await(inTransaction = true) {
            val deleted = immersionQueries
                .countImmersionAnkiSnapshotsByProfile(profileId)
                .executeAsOne()
            immersionQueries.deleteImmersionAnkiSnapshots(profileId)
            immersionQueries.incrementImmersionRevision(System.currentTimeMillis())
            deleted
        }

    private fun Database.immersionRollupRebuildRange(): LocalDateRange? {
        val bounds = immersionQueries
            .selectImmersionRollupRebuildBounds(
                MAX_RECORDED_IMMERSION_EVENT_ACTIVE_DURATION_MILLIS,
            )
            .executeAsOne()
        return bounds.first_date?.let { first ->
            LocalDateRange(
                start = ImmersionLocalDate(first),
                endInclusive = ImmersionLocalDate(checkNotNull(bounds.last_date)),
            )
        }
    }

    private fun Database.beginRollupRebuildInDatabase(
        rollupVersion: Int,
        repairCursor: String?,
        updatedAtEpochMillis: Long,
        markSessionsDirty: Boolean = true,
    ) {
        immersionQueries.ensureImmersionRollupState(
            schemaVersion = ImmersionStatsVersions.SCHEMA.toLong(),
            captureVersion = ImmersionStatsVersions.CAPTURE.toLong(),
            normalizationVersion = ImmersionStatsVersions.NORMALIZATION.toLong(),
            rollupVersion = rollupVersion.toLong(),
            updatedAt = updatedAtEpochMillis,
        )
        immersionQueries.clearImmersionRollups()
        immersionQueries.clearImmersionHourlyRollups()
        immersionQueries.clearImmersionLifetimeRollups()
        immersionQueries.clearImmersionAppliedEvents()
        immersionQueries.clearImmersionRollupDirty()
        if (markSessionsDirty) {
            immersionQueries
                .selectImmersionRollupSessions(0, Long.MAX_VALUE)
                .executeAsList()
                .forEach {
                    markRollupDirty(
                        it.started_at,
                        it.start_offset_seconds.toIntExact("session offset"),
                        it.title_id,
                        "FULL_REBUILD",
                    )
                    it.ended_at?.let { endedAt ->
                        markRollupDirty(
                            endedAt,
                            it.start_offset_seconds.toIntExact("session offset"),
                            it.title_id,
                            "FULL_REBUILD",
                        )
                    }
                }
        }
        immersionQueries.updateImmersionRepairState(
            rollupVersion = rollupVersion.toLong(),
            repairCursor = repairCursor,
            updatedAt = updatedAtEpochMillis,
        )
    }

    private fun Database.rebuildRollupsInDatabase(
        range: tachiyomi.domain.immersion.model.LocalDateRange,
        rollupVersion: Int,
        nowEpochMillis: Long,
        accumulateLifetimeRollups: Boolean = false,
        completeRepair: Boolean = true,
    ): ImmersionRollupRebuildResult {
        val calendar = ImmersionAnalyticsCalendar()
        // Event durations end at occurred_at and span backwards. The supported
        // duration cap keeps this occurred_at query window index-bounded; normal
        // appends enforce the cap and integrity rejects older/imported violations.
        val fromEpochMillis = (
            Math.multiplyExact(range.start.epochDay, MILLIS_PER_DAY) -
                MAX_RECORDED_IMMERSION_EVENT_ACTIVE_DURATION_MILLIS -
                MAX_ZONE_OFFSET_MILLIS
            ).coerceAtLeast(0)
        val untilEpochMillis = Math.addExact(
            Math.addExact(
                Math.multiplyExact(Math.addExact(range.endInclusive.epochDay, 1), MILLIS_PER_DAY),
                MAX_ZONE_OFFSET_MILLIS,
            ),
            MAX_RECORDED_IMMERSION_EVENT_ACTIVE_DURATION_MILLIS,
        )
        val aggregates = linkedMapOf<RollupKey, MutableRollup>()
        val hourlyAggregates = linkedMapOf<HourlyRollupKey, MutableHourlyRollup>()

        fun accumulator(
            date: ImmersionLocalDate,
            titleId: String,
            mediaKind: String,
            profileId: String,
            languageTag: String?,
            legacy: Boolean,
            replay: Boolean,
        ): MutableRollup? {
            if (date < range.start || date > range.endInclusive) return null
            val key = RollupKey(
                date = date,
                titleId = titleId,
                mediaKind = MediaKind.valueOf(mediaKind),
                profileId = profileId,
                languageTag = languageTag?.let(::LanguageTag),
                provenance = if (legacy) {
                    ProvenanceState.LEGACY_AGGREGATE
                } else {
                    ProvenanceState.AVAILABLE
                },
                replay = replay,
            )
            return aggregates.getOrPut(key, ::MutableRollup)
        }

        fun hourlyAccumulator(
            date: ImmersionLocalDate,
            hour: Int,
            titleId: String,
            mediaKind: String,
            profileId: String,
            languageTag: String?,
            legacy: Boolean,
            replay: Boolean,
        ): MutableHourlyRollup? {
            if (date < range.start || date > range.endInclusive) return null
            val key = HourlyRollupKey(
                date = date,
                hour = hour,
                titleId = titleId,
                mediaKind = MediaKind.valueOf(mediaKind),
                profileId = profileId,
                languageTag = languageTag?.let(::LanguageTag),
                provenance = if (legacy) {
                    ProvenanceState.LEGACY_AGGREGATE
                } else {
                    ProvenanceState.AVAILABLE
                },
                replay = replay,
            )
            return hourlyAggregates.getOrPut(key, ::MutableHourlyRollup)
        }

        val sessions = immersionQueries
            .selectImmersionRollupSessions(fromEpochMillis, untilEpochMillis)
            .executeAsList()
        sessions.forEach { session ->
            val date = session.legacy_local_date?.let(::ImmersionLocalDate)
                ?: calendar.localDate(
                    session.started_at,
                    session.start_offset_seconds.toIntExact("session offset"),
                )
            accumulator(
                date,
                session.title_id,
                session.media_kind,
                session.profile_id,
                session.language_tag,
                session.legacy_import == 1L,
                false,
            )?.apply {
                sessionsCount = Math.addExact(sessionsCount, 1)
                if (session.legacy_import == 1L) {
                    activeDuration = Math.addExact(activeDuration, session.active_duration_ms)
                    grossCharacters = Math.addExact(grossCharacters, session.gross_characters)
                    uniqueSourceCharacters = Math.addExact(
                        uniqueSourceCharacters,
                        session.unique_source_characters,
                    )
                    netCharacters = Math.addExact(netCharacters, session.net_characters)
                    sourceUnitFallback = Math.addExact(sourceUnitFallback, session.source_unit_count)
                    cardsCreated = Math.addExact(cardsCreated, session.legacy_cards_total)
                    cardsUpdated = Math.addExact(cardsUpdated, session.cards_updated)
                }
            }
            if (session.legacy_import == 1L) {
                hourlyAccumulator(
                    date = date,
                    hour = localHour(
                        session.started_at,
                        session.start_offset_seconds.toIntExact("session offset"),
                    ),
                    titleId = session.title_id,
                    mediaKind = session.media_kind,
                    profileId = session.profile_id,
                    languageTag = session.language_tag,
                    legacy = true,
                    replay = false,
                )?.apply {
                    activeDuration = Math.addExact(activeDuration, session.active_duration_ms)
                    grossCharacters = Math.addExact(grossCharacters, session.gross_characters)
                    uniqueSourceCharacters = Math.addExact(
                        uniqueSourceCharacters,
                        session.unique_source_characters,
                    )
                    netCharacters = Math.addExact(netCharacters, session.net_characters)
                }
            }
        }

        val events = immersionQueries
            .selectImmersionRollupEvents(
                fromEpochMillis = fromEpochMillis,
                untilEpochMillis = untilEpochMillis,
                maxEventActiveDurationMillis =
                MAX_RECORDED_IMMERSION_EVENT_ACTIVE_DURATION_MILLIS,
            )
            .executeAsList()
        events.forEach { event ->
            val eventDate = calendar.localDate(
                event.occurred_at,
                event.timezone_offset_seconds.toIntExact("event offset"),
            )
            val replay = event.replay_ordinal > 0
            accumulator(
                eventDate,
                event.title_id,
                event.media_kind,
                event.profile_id,
                event.language_tag,
                event.legacy_import == 1L,
                replay,
            )?.apply {
                grossCharacters = Math.addExact(grossCharacters, event.gross_character_delta)
                uniqueSourceCharacters = Math.addExact(
                    uniqueSourceCharacters,
                    event.unique_source_character_delta,
                )
                netCharacters = Math.addExact(netCharacters, event.net_character_delta)
                cardsCreated = Math.addExact(cardsCreated, event.cards_created_delta)
                cardsUpdated = Math.addExact(cardsUpdated, event.cards_updated_delta)
                lastAppliedEventAt = maxOf(lastAppliedEventAt ?: 0, event.occurred_at)
            }
            if (event.legacy_import == 0L) {
                hourlyAccumulator(
                    date = eventDate,
                    hour = localHour(
                        event.occurred_at,
                        event.timezone_offset_seconds.toIntExact("event offset"),
                    ),
                    titleId = event.title_id,
                    mediaKind = event.media_kind,
                    profileId = event.profile_id,
                    languageTag = event.language_tag,
                    legacy = false,
                    replay = replay,
                )?.apply {
                    activeDuration = Math.addExact(
                        activeDuration,
                        event.active_duration_delta_ms,
                    )
                    grossCharacters = Math.addExact(
                        grossCharacters,
                        event.gross_character_delta,
                    )
                    uniqueSourceCharacters = Math.addExact(
                        uniqueSourceCharacters,
                        event.unique_source_character_delta,
                    )
                    netCharacters = Math.addExact(
                        netCharacters,
                        event.net_character_delta,
                    )
                }
            }
            calendar.splitDuration(
                event.occurred_at,
                event.active_duration_delta_ms,
                event.timezone_offset_seconds.toIntExact("event offset"),
            ).forEach { (date, duration) ->
                accumulator(
                    date,
                    event.title_id,
                    event.media_kind,
                    event.profile_id,
                    event.language_tag,
                    event.legacy_import == 1L,
                    event.replay_ordinal > 0,
                )?.apply {
                    activeDuration = Math.addExact(activeDuration, duration)
                    lastAppliedEventAt = maxOf(lastAppliedEventAt ?: 0, event.occurred_at)
                }
            }
        }

        val sourceFacts = immersionQueries
            .selectImmersionRollupSourceFacts(fromEpochMillis, untilEpochMillis)
            .executeAsList()
        sourceFacts.forEach { fact ->
            val date = calendar.localDate(
                fact.occurred_at,
                fact.timezone_offset_seconds.toIntExact("source event offset"),
            )
            accumulator(
                date,
                fact.title_id,
                fact.media_kind,
                fact.profile_id,
                fact.language_tag,
                fact.legacy_import == 1L,
                fact.replay_ordinal > 0,
            )?.apply {
                fact.source_unit_id?.let(sourceUnitIds::add)
            }
        }

        val characterFacts = immersionQueries
            .selectImmersionRollupCharacterFacts(fromEpochMillis, untilEpochMillis)
            .executeAsList()
        characterFacts.forEach { fact ->
            val date = calendar.localDate(
                fact.occurred_at,
                fact.timezone_offset_seconds.toIntExact("character event offset"),
            )
            accumulator(
                date,
                fact.title_id,
                fact.media_kind,
                fact.profile_id,
                fact.language_tag,
                fact.legacy_import == 1L,
                fact.replay_ordinal > 0,
            )?.apply {
                distinctCharacterIds += fact.character_code_point
                val firstSeenDate = calendar.localDate(
                    fact.first_seen_at,
                    fact.timezone_offset_seconds.toIntExact("character event offset"),
                )
                if (firstSeenDate == date) newCharacterIds += fact.character_code_point
            }
        }

        immersionQueries.deleteImmersionDailyRollupsInRange(
            range.start.epochDay,
            range.endInclusive.epochDay,
        )
        immersionQueries.deleteImmersionHourlyRollupsInRange(
            range.start.epochDay,
            range.endInclusive.epochDay,
        )
        immersionQueries.deleteImmersionAppliedEventsInRange(
            range.start.epochDay,
            range.endInclusive.epochDay,
        )
        aggregates.forEach { (key, value) ->
            immersionQueries.insertImmersionDailyRollup(
                scopeKey = key.scopeKey(),
                localDate = key.date.epochDay,
                profileId = key.profileId,
                languageTag = key.languageTag?.value.orEmpty(),
                mediaKind = key.mediaKind.name,
                titleId = key.titleId,
                activeDurationMs = value.activeDuration,
                grossCharacters = value.grossCharacters,
                uniqueSourceCharacters = value.uniqueSourceCharacters,
                netCharacters = value.netCharacters,
                sourceUnits = maxOf(value.sourceUnitIds.size.toLong(), value.sourceUnitFallback),
                distinctCharacters = value.distinctCharacterIds.size.toLong(),
                newCharacters = value.newCharacterIds.size.toLong(),
                sessions = value.sessionsCount,
                cardsCreated = value.cardsCreated,
                cardsUpdated = value.cardsUpdated,
                provenanceState = key.provenance.name,
                replayState = if (key.replay) "REPLAY" else "PRIMARY",
                rollupVersion = rollupVersion.toLong(),
                lastAppliedEventAt = value.lastAppliedEventAt,
            )
        }
        hourlyAggregates.forEach { (key, value) ->
            immersionQueries.insertImmersionHourlyRollup(
                scopeKey = key.scopeKey(),
                localDate = key.date.epochDay,
                localHour = key.hour.toLong(),
                profileId = key.profileId,
                languageTag = key.languageTag?.value.orEmpty(),
                mediaKind = key.mediaKind.name,
                titleId = key.titleId,
                activeDurationMs = value.activeDuration,
                grossCharacters = value.grossCharacters,
                uniqueSourceCharacters = value.uniqueSourceCharacters,
                netCharacters = value.netCharacters,
                provenanceState = key.provenance.name,
                replayState = if (key.replay) "REPLAY" else "PRIMARY",
                rollupVersion = rollupVersion.toLong(),
            )
        }
        events.asSequence()
            .filter {
                calendar.localDate(
                    it.occurred_at,
                    it.timezone_offset_seconds.toIntExact("event offset"),
                ) in range
            }
            .forEach {
                immersionQueries.insertImmersionAppliedEvent(
                    eventId = it.event_id,
                    rollupVersion = rollupVersion.toLong(),
                    appliedAt = nowEpochMillis,
                )
            }
        if (accumulateLifetimeRollups) {
            immersionQueries.accumulateImmersionLifetimeRollups(
                rollupVersion = rollupVersion.toLong(),
                updatedAt = nowEpochMillis,
                startDate = range.start.epochDay,
                endDate = range.endInclusive.epochDay,
            )
        } else {
            immersionQueries.clearImmersionLifetimeRollups()
            immersionQueries.rebuildImmersionLifetimeRollups(
                rollupVersion.toLong(),
                nowEpochMillis,
            )
        }
        immersionQueries.deleteImmersionDirtyRollupRange(
            range.start.epochDay,
            range.endInclusive.epochDay,
        )
        if (completeRepair) {
            immersionQueries.updateImmersionRepairState(
                rollupVersion = rollupVersion.toLong(),
                repairCursor = null,
                updatedAt = nowEpochMillis,
            )
        }
        return ImmersionRollupRebuildResult(
            range = range,
            eventCount = events.count().toLong(),
            sessionCount = sessions.count().toLong(),
            sourceUnitCount = sourceFacts.mapNotNull { it.source_unit_id }.distinct().count().toLong(),
            rowCount = aggregates.size.toLong(),
        )
    }

    private fun Database.upsertAnkiSnapshot(
        snapshot: ImmersionAnkiSnapshot,
        isCurrent: Boolean,
    ) {
        immersionQueries.upsertImmersionAnkiSnapshot(
            id = snapshot.id,
            profileId = snapshot.profileId,
            deckScope = snapshot.deckScope,
            requestedAt = snapshot.requestedAtEpochMillis,
            completedAt = snapshot.completedAtEpochMillis,
            capabilityVersion = snapshot.capabilityVersion.toLong(),
            capabilityState = snapshot.capabilityState.name,
            providerVersion = snapshot.providerVersion,
            supportsNoteModification = snapshot.supportsNoteModificationTime.toLong(),
            supportsCardModification = snapshot.supportsCardModificationTime.toLong(),
            supportsReviewHistory = snapshot.supportsReviewHistory.toLong(),
            status = snapshot.status.name,
            errorCode = snapshot.errorCode?.name,
            itemCount = snapshot.itemCount.toLong(),
            noteCount = snapshot.noteCount.toLong(),
            matureIntervalDays = snapshot.matureIntervalDays.toLong(),
            mappingHash = snapshot.mappingHash,
            queryDurationMillis = snapshot.queryDurationMillis,
            isComplete = snapshot.isComplete.toLong(),
            isPartial = snapshot.isPartial.toLong(),
            isCurrent = isCurrent.toLong(),
            isStale = snapshot.isStale.toLong(),
        )
    }

    override suspend fun importLegacyBatch(batch: LegacyImportBatch): LegacyImportResult =
        handler.await(inTransaction = true) {
            val existing = immersionQueries.selectImmersionImportLedger(
                sourceKey = batch.identity.sourceKey,
                sourceVersion = batch.identity.sourceVersion.toLong(),
                contentHash = batch.identity.contentHash,
            ).executeAsOneOrNull()
            if (existing != null) {
                return@await existing.toDomain(alreadyImported = true)
            }

            batch.aggregates.forEach { aggregate ->
                val existingSession = immersionQueries
                    .selectImmersionSessionById(aggregate.sessionId.value)
                    .executeAsOneOrNull()
                if (existingSession != null && existingSession.legacy_import != 1L) {
                    throw identityConflict(
                        "Legacy session ${aggregate.sessionId.value} conflicts with event-backed data",
                    )
                }
                upsertTitleInDatabase(
                    ImmersionTitle(
                        id = aggregate.titleId,
                        mediaKind = aggregate.mediaKind,
                        sourceKey = aggregate.titleSourceKey,
                        profileId = aggregate.profileId,
                        languageTag = aggregate.languageTag,
                        displayTitle = aggregate.displayTitle,
                        createdAtEpochMillis = batch.importedAtEpochMillis,
                        updatedAtEpochMillis = batch.importedAtEpochMillis,
                    ),
                )
                immersionQueries.insertLegacyImmersionSession(
                    id = aggregate.sessionId.value,
                    titleId = aggregate.titleId.value,
                    mediaKind = aggregate.mediaKind.name,
                    languageTag = aggregate.languageTag?.value,
                    profileId = aggregate.profileId,
                    startedAt = aggregate.startAnchorEpochMillis,
                    startZoneId = aggregate.startZoneId,
                    startOffsetSeconds = aggregate.startOffsetSeconds.toLong(),
                    activeDurationMs = aggregate.activeDuration.value,
                    characters = aggregate.characters.value,
                    captureVersion = 1,
                    schemaVersion = 1,
                    syncOrigin = "legacy:${batch.sourceKind.name}:${batch.identity.sourceKey}",
                    localDate = aggregate.localDate.epochDay,
                    readingTimeSeconds = aggregate.originalReadingTimeSeconds,
                    cardsTotal = aggregate.cardsTotal.value,
                    completed = aggregate.completed?.toLong(),
                    metadataJson = aggregate.metadataJson,
                )
                immersionQueries.insertImmersionSessionOrigin(
                    sessionId = aggregate.sessionId.value,
                    originTitleId = aggregate.titleId.value,
                )
                immersionQueries.upsertImmersionRollupDirty(
                    localDate = aggregate.localDate.epochDay,
                    titleId = aggregate.titleId.value,
                    reason = "LEGACY_IMPORT",
                    updatedAt = batch.importedAtEpochMillis,
                )
            }

            val state = when {
                batch.failedCount.value == 0L -> LegacyImportResultState.IMPORTED
                batch.aggregates.isEmpty() -> LegacyImportResultState.FAILED
                else -> LegacyImportResultState.PARTIAL
            }
            immersionQueries.insertImmersionImportLedger(
                sourceKey = batch.identity.sourceKey,
                sourceVersion = batch.identity.sourceVersion.toLong(),
                contentHash = batch.identity.contentHash,
                importedAt = batch.importedAtEpochMillis,
                result = state.name,
                importedCount = batch.aggregates.size.toLong(),
                skippedCount = batch.skippedCount.value,
                failedCount = batch.failedCount.value,
                errorCode = batch.errorSummary,
            )
            immersionQueries.incrementImmersionRevision(batch.importedAtEpochMillis)
            LegacyImportResult(
                identity = batch.identity,
                state = state,
                importedCount = NonNegativeCounter(batch.aggregates.size.toLong()),
                skippedCount = batch.skippedCount,
                failedCount = batch.failedCount,
                errorSummary = batch.errorSummary,
            )
        }

    override suspend fun getImportResult(identity: LegacyImportIdentity): LegacyImportResult? =
        handler.await {
            immersionQueries.selectImmersionImportLedger(
                sourceKey = identity.sourceKey,
                sourceVersion = identity.sourceVersion.toLong(),
                contentHash = identity.contentHash,
            ).executeAsOneOrNull()?.toDomain(alreadyImported = false)
        }

    override suspend fun getLegacyAggregates(): List<LegacyAggregateRow> =
        handler.await {
            immersionQueries
                .selectLegacyImmersionAggregates()
                .executeAsList()
                .map(SelectLegacyImmersionAggregates::toDomain)
        }

    private fun Database.sourceUnitIdsAffectedByTombstones(
        tombstones: Set<Pair<String, String>>,
    ): Set<String> {
        val sessionIds = tombstones.asSequence()
            .filter { (entityType, _) -> entityType == "SESSION" }
            .map { (_, entityId) -> entityId }
            .toList()
        val eventIds = tombstones.asSequence()
            .filter { (entityType, _) -> entityType == "EVENT" }
            .map { (_, entityId) -> entityId }
            .toList()
        val sourceUnitIds = tombstones.asSequence()
            .filter { (entityType, _) -> entityType == "SOURCE_UNIT" }
            .map { (_, entityId) -> entityId }
            .toList()
        val titleIds = tombstones.asSequence()
            .filter { (entityType, _) -> entityType == "TITLE" }
            .map { (_, entityId) -> entityId }
            .toList()
        return buildSet {
            addAll(sourceUnitIds)
            sessionIds.chunked(IMMERSION_INDEX_ID_CHUNK_SIZE).forEach { ids ->
                addAll(immersionQueries.selectImmersionSourceIdsForSessions(ids).executeAsList())
            }
            eventIds.chunked(IMMERSION_INDEX_ID_CHUNK_SIZE).forEach { ids ->
                addAll(immersionQueries.selectImmersionSourceIdsForEvents(ids).executeAsList())
            }
            titleIds.chunked(IMMERSION_INDEX_ID_CHUNK_SIZE).forEach { ids ->
                addAll(immersionQueries.selectImmersionSourceIdsForTitles(ids).executeAsList())
            }
        }
    }

    private fun Database.captureImmersionSourceBoundarySnapshot(
        sourceUnitIds: Collection<String>,
    ): ImmersionSourceBoundarySnapshot {
        val boundaries = selectImmersionSourceExposureBounds(sourceUnitIds)
        if (boundaries.isEmpty()) return ImmersionSourceBoundarySnapshot()
        val boundedSourceIds = boundaries.keys
        return ImmersionSourceBoundarySnapshot(
            boundaries = boundaries,
            characterCodePoints = selectImmersionCharacterCodePointsForSources(boundedSourceIds),
        )
    }

    private fun Database.canonicalizeImmersionSourceBoundaries(
        snapshot: ImmersionSourceBoundarySnapshot,
    ) {
        if (snapshot.boundaries.isEmpty()) return
        val remainingBoundaries = selectImmersionSourceExposureBounds(snapshot.boundaries.keys)
        remainingBoundaries.forEach { (sourceUnitId, remaining) ->
            val previous = snapshot.boundaries.getValue(sourceUnitId)
            val firstExposedAt = if (remaining.firstEventAt > previous.firstEventAt) {
                remaining.firstEventAt
            } else {
                previous.firstExposedAt
            }
            val lastExposedAt = if (remaining.lastEventAt < previous.lastEventAt) {
                remaining.lastEventAt
            } else {
                previous.lastExposedAt
            }
            if (
                firstExposedAt != remaining.firstExposedAt ||
                lastExposedAt != remaining.lastExposedAt
            ) {
                immersionQueries.updateImmersionSourceSeenTimesById(
                    firstExposedAt = firstExposedAt,
                    lastExposedAt = lastExposedAt,
                    sourceUnitId = sourceUnitId,
                )
            }
        }

        val affectedCharacterCodePoints = buildSet {
            addAll(snapshot.characterCodePoints)
            addAll(selectImmersionCharacterCodePointsForSources(snapshot.boundaries.keys))
        }
        affectedCharacterCodePoints.chunked(IMMERSION_INDEX_ID_CHUNK_SIZE).forEach { codePoints ->
            immersionQueries.recomputeImmersionCharacterSeenTimesByCodePoints(codePoints)
            immersionQueries.deleteOrphanImmersionCharactersByCodePoints(codePoints)
        }
    }

    private fun Database.canonicalizeMergedImmersionSourceBoundaries(
        sourceUnitIds: Collection<String>,
    ) {
        if (sourceUnitIds.isEmpty()) return
        val boundaries = selectImmersionSourceExposureBounds(sourceUnitIds)
        boundaries.forEach { (sourceUnitId, boundary) ->
            immersionQueries.updateImmersionSourceSeenTimesById(
                firstExposedAt = boundary.firstEventAt,
                lastExposedAt = boundary.lastEventAt,
                sourceUnitId = sourceUnitId,
            )
        }
        val existingSourceIds = boundaries.keys
        val affectedCharacterCodePoints = selectImmersionCharacterCodePointsForSources(existingSourceIds)
        affectedCharacterCodePoints.chunked(IMMERSION_INDEX_ID_CHUNK_SIZE).forEach { codePoints ->
            immersionQueries.recomputeImmersionCharacterSeenTimesByCodePoints(codePoints)
        }
    }

    private fun Database.selectImmersionSourceExposureBounds(
        sourceUnitIds: Collection<String>,
    ): Map<String, ImmersionSourceExposureBounds> =
        sourceUnitIds
            .distinct()
            .chunked(IMMERSION_INDEX_ID_CHUNK_SIZE)
            .flatMap { ids ->
                immersionQueries.selectImmersionSourceExposureBoundsByIds(ids).executeAsList()
            }
            .associate { row ->
                row.source_unit_id to ImmersionSourceExposureBounds(
                    firstExposedAt = row.first_exposed_at,
                    lastExposedAt = row.last_exposed_at,
                    firstEventAt = checkNotNull(row.first_event_at),
                    lastEventAt = checkNotNull(row.last_event_at),
                )
            }

    private fun Database.selectImmersionCharacterCodePointsForSources(
        sourceUnitIds: Collection<String>,
    ): Set<Long> =
        sourceUnitIds
            .distinct()
            .chunked(IMMERSION_INDEX_ID_CHUNK_SIZE)
            .flatMapTo(linkedSetOf()) { ids ->
                immersionQueries.selectImmersionCharacterCodePointsForSources(ids).executeAsList()
            }

    private fun Database.upsertTitleInDatabase(title: ImmersionTitle): PersistenceResult {
        val existing = immersionQueries.selectImmersionTitleById(title.id.value).executeAsOneOrNull()
        if (
            existing != null &&
            (
                existing.media_kind != title.mediaKind.name ||
                    existing.source_key != title.sourceKey ||
                    existing.profile_id != title.profileId
                )
        ) {
            throw identityConflict("Title ${title.id.value} conflicts with an existing identity")
        }
        immersionQueries.insertImmersionTitle(
            id = title.id.value,
            mediaKind = title.mediaKind.name,
            sourceKey = title.sourceKey,
            profileId = title.profileId,
            languageTag = title.languageTag?.value,
            displayTitle = title.displayTitle,
            libraryId = title.libraryId,
            trackerId = title.trackerId,
            mediaId = title.mediaId,
            status = title.status,
            totalUnits = title.totalUnits,
            totalCharacterEstimate = title.totalCharacterEstimate,
            createdAt = title.createdAtEpochMillis,
            updatedAt = title.updatedAtEpochMillis,
        )
        val unchanged = existing?.let {
            it.language_tag == title.languageTag?.value &&
                it.display_title == title.displayTitle &&
                it.library_id == title.libraryId &&
                it.tracker_id == title.trackerId &&
                it.media_id == title.mediaId &&
                it.status == title.status &&
                it.total_units == title.totalUnits &&
                it.total_character_estimate == title.totalCharacterEstimate &&
                it.deleted_at == null
        } == true
        if (!unchanged) immersionQueries.incrementImmersionRevision(title.updatedAtEpochMillis)
        return if (unchanged) PersistenceResult.AlreadyApplied else PersistenceResult.Applied
    }

    private fun Database.resolveImmersionTitleId(titleId: String): String =
        immersionQueries.selectImmersionTitleAlias(titleId).executeAsOneOrNull() ?: titleId

    private fun Database.captureExclusionTitleIds(titleId: String): Set<String> {
        val resolvedTitleId = resolveImmersionTitleId(titleId)
        val aliases = immersionQueries
            .selectImmersionTitleAliasesForTitles(listOf(titleId, resolvedTitleId))
            .executeAsList()
        return buildSet {
            add(titleId)
            add(resolvedTitleId)
            aliases.forEach {
                add(it.source_title_id)
                add(it.target_title_id)
            }
        }
    }

    private fun Database.createSessionInDatabase(
        session: ImmersionSessionStart,
        originTitleId: TitleId = session.titleId,
    ): PersistenceResult {
        val existing = immersionQueries.selectImmersionSessionById(session.id.value).executeAsOneOrNull()
        if (existing != null) {
            ensureSessionIdentity(existing, session)
            val existingOrigin = immersionQueries
                .selectImmersionSessionOrigin(session.id.value)
                .executeAsOneOrNull()
            if (existingOrigin != null && existingOrigin != originTitleId.value) {
                throw identityConflict(
                    "Session ${session.id.value} was retried with a different origin title",
                )
            }
            immersionQueries.insertImmersionSessionOrigin(session.id.value, originTitleId.value)
            return PersistenceResult.AlreadyApplied
        }
        immersionQueries.insertImmersionSession(
            id = session.id.value,
            deviceId = session.deviceId,
            titleId = session.titleId.value,
            mediaKind = session.mediaKind.name,
            languageTag = session.languageTag?.value,
            profileId = session.profileId,
            startedAt = session.startedAtEpochMillis,
            startZoneId = session.startZoneId,
            startOffsetSeconds = session.startOffsetSeconds.toLong(),
            captureVersion = session.captureVersion.toLong(),
            schemaVersion = session.schemaVersion.toLong(),
            legacyImport = session.legacyImport.toLong(),
            syncOrigin = session.syncOrigin,
        )
        immersionQueries.insertImmersionSessionOrigin(session.id.value, originTitleId.value)
        markRollupDirty(
            session.startedAtEpochMillis,
            session.startOffsetSeconds,
            session.titleId.value,
            "SESSION",
        )
        immersionQueries.incrementImmersionRevision(session.startedAtEpochMillis)
        return PersistenceResult.Applied
    }

    private fun Database.upsertSourceInDatabase(
        source: ImmersionSourceUnit,
        originTitleId: TitleId = source.titleId,
    ): PersistenceResult {
        val byLocator = immersionQueries.selectImmersionSourceUnitByLocator(
            titleId = source.titleId.value,
            sourceKind = source.sourceKind.name,
            canonicalLocator = source.canonicalLocator,
            normalizedTextHash = source.normalizedTextHash,
        ).executeAsOneOrNull()
        if (byLocator != null && byLocator.id != source.id.value) {
            throw identityConflict("Source locator already belongs to ${byLocator.id}")
        }
        val byId = immersionQueries.selectImmersionSourceUnitById(source.id.value).executeAsOneOrNull()
        if (byId != null) {
            ensureSourceIdentity(byId, source)
            val existingOrigin = immersionQueries
                .selectImmersionSourceOrigin(source.id.value)
                .executeAsOneOrNull()
            if (existingOrigin != null && existingOrigin != originTitleId.value) {
                throw identityConflict(
                    "Source unit ${source.id.value} was retried with a different origin title",
                )
            }
            immersionQueries.insertImmersionSourceOrigin(source.id.value, originTitleId.value)
            immersionQueries.touchImmersionSourceUnit(source.lastExposedAtEpochMillis, source.id.value)
            return PersistenceResult.AlreadyApplied
        }
        immersionQueries.insertImmersionSourceUnit(
            id = source.id.value,
            titleId = source.titleId.value,
            sourceKind = source.sourceKind.name,
            canonicalLocator = source.canonicalLocator,
            chapterOrSectionId = source.chapterOrSectionId,
            episodeOrMediaId = source.episodeOrMediaId,
            pageOrCueIndex = source.pageOrCueIndex,
            trackId = source.trackId,
            sourceStart = source.sourceStart,
            sourceEnd = source.sourceEnd,
            normalizedTextHash = source.normalizedTextHash,
            parserVersion = source.parserVersion?.toLong(),
            ocrEngineId = source.ocrEngineId,
            ocrVersion = source.ocrVersion?.toLong(),
            ocrConfidence = source.ocrConfidence,
            ocrQuality = source.ocrQuality?.name,
            rawText = source.rawText?.encodeToByteArray(),
            rawTextEncoding = source.rawText?.let { UTF8 },
            firstExposedAt = source.firstExposedAtEpochMillis,
            lastExposedAt = source.lastExposedAtEpochMillis,
            countableCharacters = source.characterCounts.gross.value,
            hanCharacters = 0,
            hiraganaCharacters = 0,
            katakanaCharacters = 0,
            hangulCharacters = 0,
            latinCharacters = 0,
            otherCharacters = 0,
        )
        immersionQueries.insertImmersionSourceOrigin(source.id.value, originTitleId.value)
        return PersistenceResult.Applied
    }

    private fun Database.appendExposureInDatabase(event: ExposureEvent): PersistenceResult {
        val payloadHash = event.payloadHash()
        val existing = immersionQueries.selectImmersionEventById(event.id.value).executeAsOneOrNull()
        if (existing != null) {
            if (existing.payload_hash != payloadHash) {
                throw identityConflict("Event ${event.id.value} was retried with a different payload")
            }
            return PersistenceResult.AlreadyApplied
        }
        val sequenceOwner = immersionQueries
            .selectImmersionEventBySequence(event.sessionId.value, event.sequence)
            .executeAsOneOrNull()
        if (sequenceOwner != null) {
            throw ImmersionDataException(
                PersistenceErrorCode.SEQUENCE_CONFLICT,
                "Session sequence ${event.sequence} already belongs to ${sequenceOwner.id}",
            )
        }
        val session = immersionQueries.selectImmersionSessionById(event.sessionId.value).executeAsOneOrNull()
            ?: throw identityConflict("Session ${event.sessionId.value} does not exist")
        val originTitleId = event.source.titleId
        val source = event.source.copy(
            titleId = TitleId(resolveImmersionTitleId(originTitleId.value)),
        )
        if (session.title_id != source.titleId.value) {
            throw identityConflict("Source title does not match session title")
        }
        upsertSourceInDatabase(source, originTitleId)
        val sourceUnitDelta = if (
            immersionQueries.countImmersionSessionSourceExposure(
                event.sessionId.value,
                source.id.value,
            ).executeAsOne() == 0L
        ) {
            1L
        } else {
            0L
        }
        immersionQueries.insertImmersionEvent(
            id = event.id.value,
            sessionId = event.sessionId.value,
            sequence = event.sequence,
            occurredAt = event.occurredAtEpochMillis,
            timezoneOffsetSeconds = event.timezoneOffsetSeconds.toLong(),
            type = event.type.name,
            sourceUnitId = source.id.value,
            activeDurationDeltaMs = event.activeDuration.value,
            grossCharacterDelta = event.grossCharacters.value,
            uniqueSourceCharacterDelta = event.uniqueSourceCharacters.value,
            netCharacterDelta = event.netCharacters.value,
            metadataVersion = 1,
            metadataPayload = null,
            payloadHash = payloadHash,
            localDate = event.localDateEpochDay(),
        )
        immersionQueries.insertImmersionSourceExposure(
            eventId = event.id.value,
            sessionId = event.sessionId.value,
            sourceUnitId = source.id.value,
            replayOrdinal = event.replayOrdinal.toLong(),
            exposurePolicy = event.exposurePolicy,
            grossCharacters = event.grossCharacters.value,
            uniqueSourceCharacters = event.uniqueSourceCharacters.value,
            activeDurationMs = event.activeDuration.value,
        )
        immersionQueries.advanceImmersionSessionForExposure(
            activeDurationDeltaMs = event.activeDuration.value,
            grossCharacterDelta = event.grossCharacters.value,
            uniqueSourceCharacterDelta = event.uniqueSourceCharacters.value,
            netCharacterDelta = event.netCharacters.value,
            sourceUnitDelta = sourceUnitDelta,
            sequence = event.sequence,
            occurredAt = event.occurredAtEpochMillis,
            sessionId = event.sessionId.value,
        )
        if (immersionQueries.selectImmersionChanges().executeAsOne() != 1L) {
            throw ImmersionDataException(
                PersistenceErrorCode.SESSION_NOT_ACTIVE,
                "Session ${event.sessionId.value} is inactive or expected sequence ${session.last_sequence + 1}",
            )
        }
        immersionQueries.touchImmersionSourceUnit(event.occurredAtEpochMillis, source.id.value)
        markEventRollupDirty(event, session.title_id, "EVENT")
        immersionQueries.incrementImmersionRevision(event.occurredAtEpochMillis)
        return PersistenceResult.Applied
    }

    private fun Database.appendSessionEventInDatabase(event: SessionEvent): PersistenceResult {
        val payloadHash = event.payloadHash()
        val existing = immersionQueries.selectImmersionEventById(event.id.value).executeAsOneOrNull()
        if (existing != null) {
            if (existing.payload_hash != payloadHash) {
                throw identityConflict("Event ${event.id.value} was retried with a different payload")
            }
            return PersistenceResult.AlreadyApplied
        }
        val sequenceOwner = immersionQueries
            .selectImmersionEventBySequence(event.sessionId.value, event.sequence)
            .executeAsOneOrNull()
        if (sequenceOwner != null) {
            throw ImmersionDataException(
                PersistenceErrorCode.SEQUENCE_CONFLICT,
                "Session sequence ${event.sequence} already belongs to ${sequenceOwner.id}",
            )
        }
        val session = immersionQueries.selectImmersionSessionById(event.sessionId.value).executeAsOneOrNull()
            ?: throw identityConflict("Session ${event.sessionId.value} does not exist")
        immersionQueries.insertImmersionEvent(
            id = event.id.value,
            sessionId = event.sessionId.value,
            sequence = event.sequence,
            occurredAt = event.occurredAtEpochMillis,
            timezoneOffsetSeconds = event.timezoneOffsetSeconds.toLong(),
            type = event.type.name,
            sourceUnitId = null,
            activeDurationDeltaMs = event.activeDuration.value,
            grossCharacterDelta = 0,
            uniqueSourceCharacterDelta = 0,
            netCharacterDelta = event.netCharacters.value,
            metadataVersion = if (event.completionUnitId == null) 1 else COMPLETION_UNIT_METADATA_VERSION,
            metadataPayload = event.completionUnitId?.encodeToByteArray(),
            payloadHash = payloadHash,
            localDate = event.localDateEpochDay(),
        )
        immersionQueries.advanceImmersionSessionForEvent(
            activeDurationDeltaMs = event.activeDuration.value,
            netCharacterDelta = event.netCharacters.value,
            sequence = event.sequence,
            occurredAt = event.occurredAtEpochMillis,
            sessionId = event.sessionId.value,
        )
        if (immersionQueries.selectImmersionChanges().executeAsOne() != 1L) {
            throw ImmersionDataException(
                PersistenceErrorCode.SESSION_NOT_ACTIVE,
                "Session ${event.sessionId.value} is inactive or expected sequence ${session.last_sequence + 1}",
            )
        }
        markEventRollupDirty(event, session.title_id, "EVENT")
        immersionQueries.incrementImmersionRevision(event.occurredAtEpochMillis)
        return PersistenceResult.Applied
    }

    private fun Database.appendAnkiOperationInDatabase(event: AnkiOperationEvent): PersistenceResult {
        val payloadHash = event.payloadHash()
        existingInteractionResult(event.id.value, event.sessionId.value, event.sequence, payloadHash)?.let {
            return it
        }
        val session = immersionQueries.selectImmersionSessionById(event.sessionId.value).executeAsOneOrNull()
            ?: throw identityConflict("Session ${event.sessionId.value} does not exist")
        val cardsCreated = (
            event.status == AnkiOperationStatus.SUCCESS &&
                event.operationType == AnkiOperationType.CREATE
            ).toLong()
        val cardsUpdated = (
            event.status == AnkiOperationStatus.SUCCESS &&
                event.operationType == AnkiOperationType.UPDATE
            ).toLong()
        immersionQueries.insertImmersionInteractionEvent(
            id = event.id.value,
            sessionId = event.sessionId.value,
            sequence = event.sequence,
            occurredAt = event.occurredAtEpochMillis,
            timezoneOffsetSeconds = event.timezoneOffsetSeconds.toLong(),
            type = event.type.name,
            sourceUnitId = event.sourceUnitId?.value,
            ankiOperationId = event.operationId.value,
            cardsCreatedDelta = cardsCreated,
            cardsUpdatedDelta = cardsUpdated,
            payloadHash = payloadHash,
            localDate = event.localDateEpochDay(),
        )
        immersionQueries.insertImmersionAnkiOperation(
            id = event.operationId.value,
            eventId = event.id.value,
            sessionId = event.sessionId.value,
            sourceUnitId = event.sourceUnitId?.value,
            noteId = event.noteId,
            cardId = event.cardId,
            deckId = event.deckId,
            type = event.operationType.name,
            status = event.status.name,
            success = (event.status == AnkiOperationStatus.SUCCESS).toLong(),
            expressionHash = event.expressionHash,
            normalizedExpression = event.normalizedExpression,
            normalizedReading = event.normalizedReading,
            occurredAt = event.occurredAtEpochMillis,
            errorCode = event.errorCode,
        )
        advanceInteraction(event, session.last_sequence, cardsCreated, cardsUpdated)
        return PersistenceResult.Applied
    }

    private fun Database.existingInteractionResult(
        eventId: String,
        sessionId: String,
        sequence: Long,
        payloadHash: String,
    ): PersistenceResult? {
        val existing = immersionQueries.selectImmersionEventById(eventId).executeAsOneOrNull()
        if (existing != null) {
            if (existing.payload_hash != payloadHash) {
                throw identityConflict("Event $eventId was retried with a different payload")
            }
            return PersistenceResult.AlreadyApplied
        }
        val sequenceOwner = immersionQueries
            .selectImmersionEventBySequence(sessionId, sequence)
            .executeAsOneOrNull()
        if (sequenceOwner != null) {
            throw ImmersionDataException(
                PersistenceErrorCode.SEQUENCE_CONFLICT,
                "Session sequence $sequence already belongs to ${sequenceOwner.id}",
            )
        }
        return null
    }

    private fun Database.advanceInteraction(
        event: RecordedImmersionEvent,
        previousSequence: Long,
        cardsCreatedDelta: Long,
        cardsUpdatedDelta: Long,
    ) {
        immersionQueries.advanceImmersionSessionForInteraction(
            cardsCreatedDelta = cardsCreatedDelta,
            cardsUpdatedDelta = cardsUpdatedDelta,
            sequence = event.sequence,
            occurredAt = event.occurredAtEpochMillis,
            sessionId = event.sessionId.value,
        )
        if (immersionQueries.selectImmersionChanges().executeAsOne() != 1L) {
            throw ImmersionDataException(
                PersistenceErrorCode.SESSION_NOT_ACTIVE,
                "Session ${event.sessionId.value} is inactive or expected sequence ${previousSequence + 1}",
            )
        }
        val session = immersionQueries.selectImmersionSessionById(event.sessionId.value).executeAsOne()
        markEventRollupDirty(event, session.title_id, "INTERACTION")
        immersionQueries.incrementImmersionRevision(event.occurredAtEpochMillis)
    }

    private fun Database.markEventRollupDirty(
        event: RecordedImmersionEvent,
        titleId: String,
        reason: String,
    ) {
        markRollupDirty(
            event.occurredAtEpochMillis,
            event.timezoneOffsetSeconds,
            titleId,
            reason,
        )
        ImmersionAnalyticsCalendar().splitDuration(
            event.occurredAtEpochMillis,
            event.activeDuration.value,
            event.timezoneOffsetSeconds,
        ).keys.forEach { date ->
            immersionQueries.upsertImmersionRollupDirty(
                localDate = date.epochDay,
                titleId = titleId,
                reason = reason,
                updatedAt = event.occurredAtEpochMillis,
            )
        }
    }

    private fun Database.markRollupDirty(
        epochMillis: Long,
        offsetSeconds: Int,
        titleId: String,
        reason: String,
    ) {
        val localDate = ImmersionAnalyticsCalendar().localDate(epochMillis, offsetSeconds)
        immersionQueries.upsertImmersionRollupDirty(
            localDate = localDate.epochDay,
            titleId = titleId,
            reason = reason,
            updatedAt = epochMillis,
        )
    }

    private fun Database.compactFinalizedSessionHeartbeats(
        sessionId: String,
        titleId: String,
        deviceId: String,
        compactedAtEpochMillis: Long,
    ): Long {
        val rows = immersionQueries
            .selectImmersionHeartbeatEventsForCompaction(
                sessionId = sessionId,
                compactedMetadataVersion = HEARTBEAT_COMPACTION_METADATA_VERSION,
            )
            .executeAsList()
        if (rows.size < MIN_HEARTBEATS_PER_COMPACTION_WINDOW) return 0

        val groups = rows.groupBy { row ->
            HeartbeatCompactionWindow(
                localDate = row.local_date,
                window = Math.floorDiv(row.occurred_at, HEARTBEAT_COMPACTION_WINDOW_MILLIS),
                timezoneOffsetSeconds = row.timezone_offset_seconds,
            )
        }
        var removedEvents = 0L
        groups.values
            .filter { it.size >= MIN_HEARTBEATS_PER_COMPACTION_WINDOW }
            .forEach { group ->
                check(
                    group.all { row ->
                        row.gross_character_delta == 0L &&
                            row.unique_source_character_delta == 0L &&
                            row.net_character_delta == 0L &&
                            row.cards_created_delta == 0L &&
                            row.cards_updated_delta == 0L
                    },
                ) {
                    "Heartbeat compaction encountered a non-telemetry delta"
                }
                val first = group.first()
                val last = group.last()
                val compactedEvent = SessionEvent(
                    id = EventId(compactedHeartbeatEventId(sessionId, first.local_date, first.sequence)),
                    sessionId = SessionId(sessionId),
                    sequence = first.sequence,
                    occurredAtEpochMillis = last.occurred_at,
                    timezoneOffsetSeconds = last.timezone_offset_seconds.toIntExact("heartbeat offset"),
                    type = EventType.HEARTBEAT,
                    activeDuration = MillisecondDuration(
                        group.fold(0L) { total, row ->
                            Math.addExact(total, row.active_duration_delta_ms)
                        },
                    ),
                )
                group.forEach { row ->
                    immersionQueries.upsertImmersionTombstone(
                        entityType = "EVENT",
                        entityId = row.id,
                        deletedAt = compactedAtEpochMillis,
                        deviceId = deviceId,
                    )
                }
                immersionQueries.deleteImmersionEventsByIds(group.map { it.id })
                removedEvents = Math.addExact(removedEvents, group.size.toLong() - 1L)
                immersionQueries.insertImmersionEvent(
                    id = compactedEvent.id.value,
                    sessionId = compactedEvent.sessionId.value,
                    sequence = compactedEvent.sequence,
                    occurredAt = compactedEvent.occurredAtEpochMillis,
                    timezoneOffsetSeconds = compactedEvent.timezoneOffsetSeconds.toLong(),
                    type = compactedEvent.type.name,
                    sourceUnitId = null,
                    activeDurationDeltaMs = compactedEvent.activeDuration.value,
                    grossCharacterDelta = 0,
                    uniqueSourceCharacterDelta = 0,
                    netCharacterDelta = 0,
                    metadataVersion = HEARTBEAT_COMPACTION_METADATA_VERSION,
                    metadataPayload = null,
                    payloadHash = compactedEvent.payloadHash(),
                    localDate = first.local_date,
                )
                checkExactlyOneChange("inserting compacted heartbeat ${compactedEvent.id.value}")
                immersionQueries.upsertImmersionRollupDirty(
                    localDate = first.local_date,
                    titleId = titleId,
                    reason = "HEARTBEAT_COMPACTION",
                    updatedAt = compactedAtEpochMillis,
                )
            }
        return removedEvents
    }

    private fun Database.checkExactlyOneChange(operation: String) {
        if (immersionQueries.selectImmersionChanges().executeAsOne() != 1L) {
            throw identityConflict("No row was found while $operation")
        }
    }
}

private data class TitleMutationRow(
    val id: String,
    val previousTitleId: String,
)

private data class TitleMutationImpact(
    val events: Long = 0,
    val ankiOperations: Long = 0,
)

private data class TitleMutationSnapshot(
    val preview: ImmersionTitleMutationPreview,
    val source: Immersion_title?,
    val target: Immersion_title?,
    val sessions: List<TitleMutationRow>,
    val sources: List<TitleMutationRow>,
    val goals: List<TitleMutationRow>,
)

private fun ImmersionTitleMutationRequest.mutationType(): ImmersionTitleMutationType =
    when (this) {
        is ImmersionTitleMutationRequest.Rename -> ImmersionTitleMutationType.RENAME
        is ImmersionTitleMutationRequest.Merge -> ImmersionTitleMutationType.MERGE
        is ImmersionTitleMutationRequest.RelinkSession -> ImmersionTitleMutationType.RELINK
        is ImmersionTitleMutationRequest.Split -> ImmersionTitleMutationType.SPLIT
    }

private fun ImmersionTitleMutationRequest.targetTitleIdOrNull(): TitleId? =
    when (this) {
        is ImmersionTitleMutationRequest.Rename -> null
        is ImmersionTitleMutationRequest.Merge -> targetTitleId
        is ImmersionTitleMutationRequest.RelinkSession -> targetTitleId
        is ImmersionTitleMutationRequest.Split -> targetTitleId
    }

private fun titleMutationSelectionDigest(
    request: ImmersionTitleMutationRequest,
    source: Immersion_title?,
    target: Immersion_title?,
    sessions: List<TitleMutationRow>,
    sources: List<TitleMutationRow>,
    goals: List<TitleMutationRow>,
    aliases: List<Pair<String, String>>,
    conflictSourceIds: List<String>,
    databaseRevision: Long,
): String {
    val output = ByteArrayOutputStream()
    output.writeField(TITLE_MUTATION_DIGEST_DOMAIN)
    output.writeField(request.mutationType().name)
    output.writeField(request.sourceTitleId.value)
    output.writeNullableField(request.targetTitleIdOrNull()?.value)
    when (request) {
        is ImmersionTitleMutationRequest.Rename -> output.writeField(request.displayTitle.trim())
        is ImmersionTitleMutationRequest.Merge -> Unit
        is ImmersionTitleMutationRequest.RelinkSession -> {
            output.writeField(request.sessionId.value)
        }
        is ImmersionTitleMutationRequest.Split -> {
            output.writeField(request.displayTitle.trim())
            output.writeLong(request.dateRange.start.epochDay)
            output.writeLong(request.dateRange.endInclusive.epochDay)
        }
    }
    fun writeTitle(title: Immersion_title?) {
        output.writeNullableField(title?.id)
        output.writeNullableField(title?.media_kind)
        output.writeNullableField(title?.source_key)
        output.writeNullableField(title?.profile_id)
        output.writeNullableField(title?.language_tag)
        output.writeNullableLong(title?.updated_at)
        output.writeNullableLong(title?.deleted_at)
    }
    fun writeRows(rows: List<TitleMutationRow>) {
        output.writeLong(rows.size.toLong())
        rows.sortedBy(TitleMutationRow::id).forEach { row ->
            output.writeField(row.id)
            output.writeField(row.previousTitleId)
        }
    }
    writeTitle(source)
    writeTitle(target)
    writeRows(sessions)
    writeRows(sources)
    writeRows(goals)
    output.writeLong(aliases.size.toLong())
    aliases.sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second })).forEach {
        output.writeField(it.first)
        output.writeField(it.second)
    }
    output.writeLong(conflictSourceIds.size.toLong())
    conflictSourceIds.sorted().forEach(output::writeField)
    output.writeLong(databaseRevision)
    return output.sha256()
}

private fun Immersion_title_mutation.toDomain(): ImmersionTitleMutation =
    ImmersionTitleMutation(
        id = id,
        type = ImmersionTitleMutationType.valueOf(type),
        sourceTitleId = TitleId(source_title_id),
        targetTitleId = target_title_id?.let(::TitleId),
        displayTitle = display_title,
        sessions = sessions,
        sourceUnits = source_units,
        goals = goals,
        appliedAtEpochMillis = applied_at,
        rolledBackAtEpochMillis = rolled_back_at,
    )

private data class RollupKey(
    val date: ImmersionLocalDate,
    val titleId: String,
    val mediaKind: MediaKind,
    val profileId: String,
    val languageTag: LanguageTag?,
    val provenance: ProvenanceState,
    val replay: Boolean,
) {
    fun scopeKey(): String =
        listOf(
            date.epochDay,
            profileId,
            languageTag?.value.orEmpty(),
            mediaKind.name,
            titleId,
            provenance.name,
            if (replay) "REPLAY" else "PRIMARY",
        ).joinToString("\u001f")
}

private data class HourlyRollupKey(
    val date: ImmersionLocalDate,
    val hour: Int,
    val titleId: String,
    val mediaKind: MediaKind,
    val profileId: String,
    val languageTag: LanguageTag?,
    val provenance: ProvenanceState,
    val replay: Boolean,
) {
    init {
        require(hour in 0..23)
    }

    fun scopeKey(): String =
        listOf(
            date.epochDay,
            hour,
            profileId,
            languageTag?.value.orEmpty(),
            mediaKind.name,
            titleId,
            provenance.name,
            if (replay) "REPLAY" else "PRIMARY",
        ).joinToString("\u001f")
}

private class MutableRollup {
    var activeDuration: Long = 0
    var grossCharacters: Long = 0
    var uniqueSourceCharacters: Long = 0
    var netCharacters: Long = 0
    var sourceUnitFallback: Long = 0
    var sessionsCount: Long = 0
    var cardsCreated: Long = 0
    var cardsUpdated: Long = 0
    var lastAppliedEventAt: Long? = null
    val sourceUnitIds = mutableSetOf<String>()
    val distinctCharacterIds = mutableSetOf<Long>()
    val newCharacterIds = mutableSetOf<Long>()
}

private class MutableHourlyRollup {
    var activeDuration: Long = 0
    var grossCharacters: Long = 0
    var uniqueSourceCharacters: Long = 0
    var netCharacters: Long = 0
}

private fun localHour(epochMillis: Long, offsetSeconds: Int): Int =
    Instant.ofEpochMilli(epochMillis)
        .atOffset(ZoneOffset.ofTotalSeconds(offsetSeconds))
        .hour

private class MutableInventoryMetrics {
    private val characterIds = mutableSetOf<String>()
    private val newCharacterIds = mutableSetOf<String>()
    private val representedCharacterIds = mutableSetOf<String>()

    fun add(
        entityKind: String,
        entityKey: String,
        globallyNew: Boolean,
        representedInAnki: Boolean,
    ) {
        when (entityKind) {
            "CHARACTER" -> {
                characterIds += entityKey
                if (globallyNew) newCharacterIds += entityKey
                if (representedInAnki) representedCharacterIds += entityKey
            }
            else -> error("Unknown analytics inventory entity kind: $entityKind")
        }
    }

    fun toDomain(): AnalyticsInventoryMetrics =
        AnalyticsInventoryMetrics(
            distinctCharacters = characterIds.size.toLong(),
            newCharacters = newCharacterIds.size.toLong(),
            charactersRepresentedInAnki = representedCharacterIds.size.toLong(),
        )
}

private data class ImmersionSourceBoundarySnapshot(
    val boundaries: Map<String, ImmersionSourceExposureBounds> = emptyMap(),
    val characterCodePoints: Set<Long> = emptySet(),
)

private data class ScopedDeletionResult(
    val preview: ImmersionDeletionPreview,
    val deletedSessionIds: List<SessionId>,
)

private data class ImmersionSourceExposureBounds(
    val firstExposedAt: Long,
    val lastExposedAt: Long,
    val firstEventAt: Long,
    val lastEventAt: Long,
)

private data class HeartbeatCompactionWindow(
    val localDate: Long,
    val window: Long,
    val timezoneOffsetSeconds: Long,
)

private data class AnalyticsSqlArgs(
    val filterMediaKinds: Long,
    val mediaKinds: Collection<String>,
    val filterProfileIds: Long,
    val profileIds: Collection<String>,
    val filterLanguageTags: Long,
    val languageTags: Collection<String>,
    val filterTitleIds: Long,
    val titleIds: Collection<String>,
    val filterProvenance: Long,
    val provenanceStates: Collection<String>,
    val filterMaturity: Long,
    val maturityTiers: Collection<String>,
    val maturityAggregation: String,
)

private data class TimelineAccumulator(
    var eventCount: Long = 0,
    var activeDuration: Long = 0,
    var grossCharacters: Long = 0,
    var uniqueSourceCharacters: Long = 0,
    var netCharacters: Long = 0,
    var cardsCreated: Long = 0,
    var cardsUpdated: Long = 0,
    val eventTypes: MutableSet<EventType> = linkedSetOf(),
) {
    fun toDomain(startEpochMillis: Long, endEpochMillis: Long) = AnalyticsTimelineBucket(
        startEpochMillis = startEpochMillis,
        endEpochMillis = endEpochMillis,
        eventCount = eventCount,
        activeDurationMillis = activeDuration,
        grossCharacters = grossCharacters,
        uniqueSourceCharacters = uniqueSourceCharacters,
        netCharacters = netCharacters,
        cardsCreated = cardsCreated,
        cardsUpdated = cardsUpdated,
        eventTypes = eventTypes,
    )
}

/** How long a claimed index work item stays leased before it can be reclaimed. */
internal const val INDEX_WORK_LEASE_MILLIS = 10 * 60 * 1_000L

private fun StatsFilter.sqlArgs(): AnalyticsSqlArgs =
    AnalyticsSqlArgs(
        filterMediaKinds = mediaKinds.isNotEmpty().toLong(),
        mediaKinds = mediaKinds.map { it.name }.ifEmpty { listOf("") },
        filterProfileIds = profileIds.isNotEmpty().toLong(),
        profileIds = profileIds.ifEmpty { setOf("") },
        filterLanguageTags = languageTags.isNotEmpty().toLong(),
        languageTags = languageTags.map { it.value }.ifEmpty { listOf("") },
        filterTitleIds = titleIds.isNotEmpty().toLong(),
        titleIds = titleIds.map { it.value }.ifEmpty { listOf("") },
        filterProvenance = provenanceStates.isNotEmpty().toLong(),
        provenanceStates = provenanceStates.map { it.name }.ifEmpty { listOf("") },
        filterMaturity = maturityTiers.isNotEmpty().toLong(),
        maturityTiers = maturityTiers.map { it.name }.ifEmpty { listOf("") },
        maturityAggregation = ankiMaturityAggregation.name,
    )

private fun AnalyticsSort.analyticsSqlSort(): String =
    when (this) {
        AnalyticsSort.MOST_TIME, AnalyticsSort.MOST_CHARACTERS -> AnalyticsSort.MOST_OCCURRENCES.name
        else -> name
    }

private fun RecordedImmersionEvent.localDateEpochDay(): Long =
    ImmersionAnalyticsCalendar()
        .localDate(occurredAtEpochMillis, timezoneOffsetSeconds)
        .epochDay

private fun Immersion_daily_rollup.toDomain(): ImmersionDailyRollup =
    mapCorruption("daily rollup $scope_key") {
        ImmersionDailyRollup(
            date = ImmersionLocalDate(local_date),
            profileId = profile_id,
            languageTag = language_tag.takeIf(String::isNotBlank)?.let(::LanguageTag),
            mediaKind = MediaKind.valueOf(media_kind),
            titleId = TitleId(title_id),
            metrics = ReadingMetrics(
                activeTime = MillisecondDuration(active_duration_ms),
                characters = CharacterVolume(
                    gross = NonNegativeCounter(gross_characters),
                    uniqueSource = NonNegativeCounter(unique_source_characters),
                    netProgress = NetCharacterProgress(net_characters),
                ),
                distinctCharacters = NonNegativeCounter(distinct_characters),
                newCharacters = NonNegativeCounter(new_characters),
                sourceUnits = NonNegativeCounter(source_units),
                sessions = NonNegativeCounter(sessions),
                cardsCreated = NonNegativeCounter(cards_created),
                cardsUpdated = NonNegativeCounter(cards_updated),
                characterCoverage = CharacterCoverage(),
            ),
            provenanceState = ProvenanceState.valueOf(provenance_state),
            replay = replay_state == "REPLAY",
            rollupVersion = rollup_version.toIntExact("rollup version"),
        )
    }

private fun ensureSessionIdentity(existing: Immersion_session, expected: ImmersionSessionStart) {
    if (
        existing.device_id != expected.deviceId ||
        existing.title_id != expected.titleId.value ||
        existing.media_kind != expected.mediaKind.name ||
        existing.language_tag != expected.languageTag?.value ||
        existing.profile_id != expected.profileId ||
        existing.started_at != expected.startedAtEpochMillis ||
        existing.start_zone_id != expected.startZoneId ||
        existing.start_offset_seconds != expected.startOffsetSeconds.toLong() ||
        existing.capture_version != expected.captureVersion.toLong() ||
        existing.schema_version != expected.schemaVersion.toLong()
    ) {
        throw identityConflict("Session ${expected.id.value} was retried with a different identity")
    }
}

private fun ensureAtomicSessionStartIdentity(
    title: ImmersionTitle,
    session: ImmersionSessionStart,
    event: SessionEvent,
) {
    if (
        session.titleId != title.id ||
        session.mediaKind != title.mediaKind ||
        session.languageTag != title.languageTag ||
        session.profileId != title.profileId
    ) {
        throw identityConflict("Session ${session.id.value} does not match title ${title.id.value}")
    }
    if (
        event.sessionId != session.id ||
        event.type != EventType.SESSION_STARTED ||
        event.sequence != 1L ||
        event.occurredAtEpochMillis != session.startedAtEpochMillis ||
        event.timezoneOffsetSeconds != session.startOffsetSeconds ||
        event.activeDuration != MillisecondDuration(0) ||
        event.netCharacters != NetCharacterProgress.ZERO ||
        event.completionUnitId != null
    ) {
        throw identityConflict("Event ${event.id.value} is not the start of session ${session.id.value}")
    }
}

private fun ensureSourceIdentity(existing: Immersion_source_unit, expected: ImmersionSourceUnit) {
    if (
        existing.title_id != expected.titleId.value ||
        existing.source_kind != expected.sourceKind.name ||
        existing.canonical_locator != expected.canonicalLocator ||
        existing.normalized_text_hash != expected.normalizedTextHash
    ) {
        throw identityConflict("Source unit ${expected.id.value} was retried with a different identity")
    }
}

private fun Immersion_session.toDomain(): ImmersionSession =
    mapCorruption("session $id") {
        ImmersionSession(
            id = SessionId(id),
            deviceId = device_id,
            titleId = TitleId(title_id),
            mediaKind = MediaKind.valueOf(media_kind),
            languageTag = language_tag?.let(::LanguageTag),
            profileId = profile_id,
            startedAtEpochMillis = started_at,
            endedAtEpochMillis = ended_at,
            startZoneId = start_zone_id,
            startOffsetSeconds = start_offset_seconds.toIntExact("start offset"),
            status = SessionStatus.valueOf(status),
            activeDuration = MillisecondDuration(active_duration_ms),
            elapsedDuration = MillisecondDuration(elapsed_duration_ms),
            grossCharacters = NonNegativeCounter(gross_characters),
            uniqueSourceCharacters = NonNegativeCounter(unique_source_characters),
            netCharacters = NetCharacterProgress(net_characters),
            sourceUnitCount = NonNegativeCounter(source_unit_count),
            lastSequence = last_sequence,
            lastHeartbeatAtEpochMillis = last_heartbeat_at,
            captureVersion = capture_version.toIntExact("capture version"),
            schemaVersion = schema_version.toIntExact("schema version"),
            legacyImport = legacy_import.toBooleanExact("legacy import"),
        )
    }

private fun SelectImmersionIndexWork.toDomain(): IndexWorkItem =
    mapCorruption("index work $id") {
        IndexWorkItem(
            sourceUnitId = SourceUnitId(id),
            titleId = TitleId(title_id),
            sourceKind = SourceKind.valueOf(source_kind),
            languageTag = language_tag?.let(::LanguageTag),
            profileId = profile_id,
            normalizedTextHash = normalized_text_hash,
            rawText = raw_text?.decodeUtf8Strict(),
            indexedVersion = indexed_version.toIntExact("indexed version"),
            attemptCount = index_attempt_count.toIntExact("index attempt count"),
            claimGeneration = claim_generation.toIntExact("index claim generation"),
        )
    }

private fun Immersion_goal.toDomain(): ImmersionGoal =
    mapCorruption("goal $id") {
        ImmersionGoal(
            id = id,
            type = type,
            metric = metric,
            target = target,
            period = period,
            startDate = start_date?.let(::ImmersionLocalDate),
            endDate = end_date?.let(::ImmersionLocalDate),
            mediaKind = media_kind?.let(MediaKind::valueOf),
            profileId = profile_id,
            languageTag = language_tag?.let(::LanguageTag),
            titleId = title_id?.let(::TitleId),
            weekdayMultipliers = weekday_multipliers,
            restDayPolicy = rest_day_policy,
            state = state,
            createdAtEpochMillis = created_at,
            updatedAtEpochMillis = updated_at,
        )
    }

private fun Immersion_anki_snapshot.toDomain(): ImmersionAnkiSnapshot =
    mapCorruption("Anki snapshot $id") {
        ImmersionAnkiSnapshot(
            id = id,
            profileId = profile_id,
            deckScope = deck_scope,
            requestedAtEpochMillis = requested_at,
            completedAtEpochMillis = completed_at,
            capabilityVersion = capability_version.toIntExact("capability version"),
            capabilityState = enumValueOf(capability_state),
            providerVersion = provider_version,
            supportsNoteModificationTime = supports_note_modification.toBooleanExact(
                "note modification capability",
            ),
            supportsCardModificationTime = supports_card_modification.toBooleanExact(
                "card modification capability",
            ),
            supportsReviewHistory = supports_review_history.toBooleanExact(
                "review history capability",
            ),
            status = enumValueOf<AnkiSnapshotStatus>(status),
            errorCode = error_code?.let { enumValueOf<AnkiInventoryFailure>(it) },
            itemCount = item_count.toIntExact("Anki item count"),
            noteCount = note_count.toIntExact("Anki note count"),
            matureIntervalDays = mature_interval_days.toIntExact("mature interval"),
            mappingHash = mapping_hash,
            queryDurationMillis = query_duration_ms,
            isComplete = is_complete.toBooleanExact("complete flag"),
            isPartial = is_partial.toBooleanExact("partial flag"),
            isCurrent = is_current.toBooleanExact("current flag"),
            isStale = is_stale.toBooleanExact("stale flag"),
        )
    }

private fun Immersion_anki_item.toDomain(
    characters: Set<UnicodeCodePoint>,
): ImmersionAnkiItem =
    mapCorruption("Anki item $card_id") {
        ImmersionAnkiItem(
            snapshotId = snapshot_id,
            noteId = note_id,
            cardId = card_id,
            noteTypeId = note_type_id,
            deckId = deck_id,
            languageTag = LanguageTag(language_tag),
            characters = characters,
            cardType = card_type?.toIntExact("card type"),
            queue = queue?.toIntExact("card queue"),
            intervalDays = interval_days?.toIntExact("card interval"),
            due = due,
            repetitions = repetitions?.toIntExact("card repetitions"),
            lapses = lapses?.toIntExact("card lapses"),
            ease = ease?.toIntExact("card ease"),
            noteModifiedAtEpochSeconds = note_modified_at,
            maturityTier = enumValueOf<MaturityTier>(maturity_tier),
            firstMatureAtEpochMillis = first_mature_at,
        )
    }

private fun Immersion_import_ledger.toDomain(alreadyImported: Boolean): LegacyImportResult =
    mapCorruption("legacy import ledger") {
        LegacyImportResult(
            identity = LegacyImportIdentity(
                sourceKey = source_key,
                sourceVersion = source_version.toIntExact("legacy import source version"),
                contentHash = content_hash,
            ),
            state = if (alreadyImported) {
                LegacyImportResultState.ALREADY_IMPORTED
            } else {
                LegacyImportResultState.valueOf(result)
            },
            importedCount = NonNegativeCounter(imported_count),
            skippedCount = NonNegativeCounter(skipped_count),
            failedCount = NonNegativeCounter(failed_count),
            errorSummary = error_code,
        )
    }

private fun SelectLegacyImmersionAggregates.toDomain(): LegacyAggregateRow =
    mapCorruption("legacy aggregate") {
        LegacyAggregateRow(
            localDate = ImmersionLocalDate(
                requireNotNull(legacy_local_date) { "Legacy aggregate date is missing" },
            ),
            mediaKind = MediaKind.valueOf(media_kind),
            profileId = profile_id,
            languageTag = language_tag?.let(::LanguageTag),
            titleId = TitleId(title_id),
            activeDuration = MillisecondDuration(
                requireNotNull(active_duration_ms) { "Legacy aggregate duration is missing" },
            ),
            characters = NonNegativeCounter(
                requireNotNull(characters) { "Legacy aggregate characters are missing" },
            ),
            cardsTotal = NonNegativeCounter(
                requireNotNull(cards_total) { "Legacy aggregate cards are missing" },
            ),
            recordCount = NonNegativeCounter(record_count),
        )
    }

private fun ExposureEvent.payloadHash(): String {
    val output = ByteArrayOutputStream()
    output.writeField(id.value)
    output.writeField(sessionId.value)
    output.writeLong(sequence)
    output.writeLong(occurredAtEpochMillis)
    output.writeLong(timezoneOffsetSeconds.toLong())
    output.writeField(type.name)
    output.writeField(source.id.value)
    output.writeField(source.titleId.value)
    output.writeField(source.sourceKind.name)
    output.writeField(source.canonicalLocator)
    output.writeField(source.normalizedTextHash)
    output.writeLong(activeDuration.value)
    output.writeLong(grossCharacters.value)
    output.writeLong(uniqueSourceCharacters.value)
    output.writeLong(netCharacters.value)
    output.writeLong(replayOrdinal.toLong())
    output.writeField(exposurePolicy)
    return MessageDigest.getInstance("SHA-256")
        .digest(output.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

private fun RecordedImmersionEvent.requireSupportedActiveDuration() {
    require(activeDuration.value <= MAX_RECORDED_IMMERSION_EVENT_ACTIVE_DURATION_MILLIS) {
        "Event active duration exceeds the supported maximum"
    }
}

private fun SessionEvent.payloadHash(): String {
    val output = ByteArrayOutputStream()
    output.writeField(id.value)
    output.writeField(sessionId.value)
    output.writeLong(sequence)
    output.writeLong(occurredAtEpochMillis)
    output.writeLong(timezoneOffsetSeconds.toLong())
    output.writeField(type.name)
    output.writeLong(activeDuration.value)
    output.writeLong(netCharacters.value)
    output.writeNullableField(completionUnitId)
    return MessageDigest.getInstance("SHA-256")
        .digest(output.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

private fun AnkiOperationEvent.payloadHash(): String {
    val output = ByteArrayOutputStream()
    output.writeField(id.value)
    output.writeField(sessionId.value)
    output.writeLong(sequence)
    output.writeLong(occurredAtEpochMillis)
    output.writeLong(timezoneOffsetSeconds.toLong())
    output.writeField(type.name)
    output.writeField(operationId.value)
    output.writeNullableField(sourceUnitId?.value)
    output.writeField(expressionHash)
    output.writeNullableField(normalizedExpression)
    output.writeNullableField(normalizedReading)
    output.writeField(operationType.name)
    output.writeField(status.name)
    output.writeNullableLong(noteId)
    output.writeNullableLong(cardId)
    output.writeNullableLong(deckId)
    output.writeNullableField(errorCode)
    return output.sha256()
}

private fun ByteArrayOutputStream.writeField(value: String) {
    val encoded = value.encodeToByteArray()
    writeLong(encoded.size.toLong())
    write(encoded)
}

private fun ByteArrayOutputStream.writeNullableField(value: String?) {
    if (value == null) {
        writeLong(-1)
    } else {
        writeField(value)
    }
}

private fun ByteArrayOutputStream.writeNullableLong(value: Long?) {
    if (value == null) {
        writeLong(0)
    } else {
        writeLong(1)
        writeLong(value)
    }
}

private fun ByteArrayOutputStream.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }

private fun List<ImmersionSession>.selectionDigest(): String {
    val output = ByteArrayOutputStream()
    output.writeLong(size.toLong())
    asSequence()
        .map { it.id.value }
        .sorted()
        .forEach(output::writeField)
    return output.sha256()
}

private fun ByteArrayOutputStream.writeLong(value: Long) {
    write(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
}

private fun ByteArray.decodeUtf8Strict(): String =
    StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()

private fun DatabaseHandler.requireRawHandler(): AndroidDatabaseHandler =
    this as? AndroidDatabaseHandler
        ?: error("Portable immersion backup requires the SQLDelight database handler")

private fun SqlDriver.exportPortableTable(
    tableName: String,
    includeRawText: Boolean,
): ImmersionPortableTable {
    require(tableName in IMMERSION_PORTABLE_TABLES)
    val columns = portableColumns(tableName)
    val projection = columns.joinToString(", ") { it.name.quotedIdentifier() }
    val rows = executeQuery(
        identifier = null,
        sql = "SELECT $projection FROM ${tableName.quotedIdentifier()}",
        mapper = { cursor ->
            val result = mutableListOf<ImmersionPortableRow>()
            while (cursor.next().value) {
                result += cursor.readPortableRow(
                    tableName = tableName,
                    columns = columns,
                    includePrivateText = includeRawText,
                )
            }
            QueryResult.Value(result)
        },
        parameters = 0,
    ).value
    return ImmersionPortableTable(tableName, columns, rows)
}

private fun SqlDriver.portableColumns(tableName: String): List<ImmersionPortableColumn> =
    executeQuery(
        identifier = null,
        sql = "PRAGMA table_info(${tableName.quotedIdentifier()})",
        mapper = { cursor ->
            val result = mutableListOf<ImmersionPortableColumn>()
            while (cursor.next().value) {
                result += ImmersionPortableColumn(
                    name = checkNotNull(cursor.getString(1)),
                    affinity = checkNotNull(cursor.getString(2)).portableAffinity(),
                    primaryKeyPosition = checkNotNull(cursor.getLong(5)).toIntExact("primary key position"),
                )
            }
            QueryResult.Value(result)
        },
        parameters = 0,
    ).value.also {
        require(it.isNotEmpty()) { "Missing portable table $tableName" }
        require(it.any { column -> column.primaryKeyPosition > 0 }) {
            "Portable table $tableName has no primary key"
        }
    }

private fun app.cash.sqldelight.db.SqlCursor.readPortableRow(
    tableName: String,
    columns: List<ImmersionPortableColumn>,
    includePrivateText: Boolean,
): ImmersionPortableRow =
    ImmersionPortableRow(
        columns.mapIndexed { index, column ->
            if (!includePrivateText && tableName to column.name in IMMERSION_PRIVATE_TEXT_COLUMNS) {
                return@mapIndexed ImmersionPortableCell(ImmersionPortableCellKind.NULL)
            }
            when (column.affinity) {
                ImmersionPortableAffinity.TEXT -> getString(index)?.let {
                    ImmersionPortableCell(ImmersionPortableCellKind.TEXT, textValue = it)
                }
                ImmersionPortableAffinity.INTEGER -> getLong(index)?.let {
                    ImmersionPortableCell(ImmersionPortableCellKind.INTEGER, integerValue = it)
                }
                ImmersionPortableAffinity.REAL -> getDouble(index)?.let {
                    ImmersionPortableCell(ImmersionPortableCellKind.REAL, realValue = it)
                }
                ImmersionPortableAffinity.BLOB -> getBytes(index)?.let {
                    ImmersionPortableCell(ImmersionPortableCellKind.BLOB, blobValue = it)
                }
            } ?: ImmersionPortableCell(ImmersionPortableCellKind.NULL)
        },
    )

private data class PortableMergeCounts(
    val inserted: Long = 0,
    val unchanged: Long = 0,
    val skippedByTombstone: Long = 0,
    val conflicts: Long = 0,
) {
    operator fun plus(other: PortableMergeCounts) = PortableMergeCounts(
        inserted = inserted + other.inserted,
        unchanged = unchanged + other.unchanged,
        skippedByTombstone = skippedByTombstone + other.skippedByTombstone,
        conflicts = conflicts + other.conflicts,
    )
}

private enum class PortableMergeStage {
    TOMBSTONES,
    TABLES,
    FINALIZE,
    ROLLUP_VALIDATE,
    ROLLUP_VERIFY,
    VALIDATION_FAILED,
    COMPLETE,
}

private data class PortableMergeCheckpoint(
    val archiveDigest: String,
    val protocolVersion: Int,
    val archiveCreatedAt: Long,
    val archiveFormatVersion: Int,
    val archiveSchemaVersion: Int,
    val includesRawText: Boolean,
    val stage: PortableMergeStage,
    val tableOrdinal: Int,
    val nextRowOffset: Int,
    val eligibleRowCount: Long,
    val insertedRows: Long,
    val unchangedRows: Long,
    val skippedByTombstoneRows: Long,
    val quarantinedConflicts: Long,
    val rebuiltRollupRows: Long,
    val verificationJson: String?,
    val lastErrorCode: String?,
    val startedAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
) {
    val accountedRows: Long
        get() = listOf(
            insertedRows,
            unchangedRows,
            skippedByTombstoneRows,
            quarantinedConflicts,
        ).fold(0L, Math::addExact)

    fun withCounts(counts: PortableMergeCounts): PortableMergeCheckpoint =
        copy(
            insertedRows = Math.addExact(insertedRows, counts.inserted),
            unchangedRows = Math.addExact(unchangedRows, counts.unchanged),
            skippedByTombstoneRows = Math.addExact(
                skippedByTombstoneRows,
                counts.skippedByTombstone,
            ),
            quarantinedConflicts = Math.addExact(
                quarantinedConflicts,
                counts.conflicts,
            ),
        )

    fun requireArchiveIdentity(
        archive: ImmersionPortableArchive,
        expectedEligibleRows: Long,
    ) {
        require(protocolVersion == IMMERSION_PORTABLE_MERGE_PROTOCOL_VERSION) {
            "Portable merge checkpoint protocol is unsupported"
        }
        require(archiveCreatedAt == archive.createdAtEpochMillis)
        require(archiveFormatVersion == archive.formatVersion)
        require(archiveSchemaVersion == archive.sourceSchemaVersion)
        require(includesRawText == archive.includesRawText)
        require(eligibleRowCount == expectedEligibleRows)
    }

    fun toReport(disposition: ImmersionMergeDisposition): ImmersionMergeReport {
        val verification = decodeVerification()
        check(verification.isHealthy) {
            "Completed portable merge checkpoint contains unhealthy verification evidence"
        }
        return ImmersionMergeReport(
            insertedRows = insertedRows,
            unchangedRows = unchangedRows,
            skippedByTombstoneRows = skippedByTombstoneRows,
            quarantinedConflicts = quarantinedConflicts,
            rebuiltRollupRows = rebuiltRollupRows,
            disposition = disposition,
            verification = verification,
        )
    }

    fun decodeVerification(): ImmersionMergeVerification =
        Json.decodeFromString<ImmersionMergeVerification>(
            checkNotNull(verificationJson) {
                "Completed portable merge checkpoint has no verification evidence"
            },
        )

    fun decodePortableRollupProgressOrNull(): PortableRollupPassProgress? =
        verificationJson?.let { encoded ->
            runCatching {
                Json.decodeFromString<PortableRollupPassProgress>(encoded)
            }.getOrNull()
        }

    fun decodePortableRollupFirstPass(): PortableRollupFirstPassEvidence =
        Json.decodeFromString<PortableRollupFirstPassEvidence>(
            checkNotNull(verificationJson) {
                "Portable merge first-pass evidence is missing"
            },
        )

    fun matchesRollupCheckpoint(other: PortableMergeCheckpoint): Boolean =
        archiveDigest == other.archiveDigest &&
            stage == other.stage &&
            verificationJson == other.verificationJson
}

private data class PortableMergeInitialization(
    val checkpoint: PortableMergeCheckpoint,
    val wasExisting: Boolean,
)

private data class PortableRollupStepResult(
    val checkpoint: PortableMergeCheckpoint,
    val observerCheckpoint: PortableRollupObserverCheckpoint? = null,
    val completedByThisCall: Boolean = false,
)

private data class PortableRollupObserverCheckpoint(
    val name: String,
    val offset: Int,
)

@Serializable
private data class PortableRollupFirstPassEvidence(
    val rebuiltRollupRows: Long,
    val rowCount: Long,
    val digest: String,
    val fingerprintVersion: Int = 1,
) {
    init {
        require(rebuiltRollupRows >= 0)
        require(rowCount >= 0)
        require(digest.isNotBlank())
        require(fingerprintVersion > 0)
    }
}

@Serializable
private data class PortableRollupPassProgress(
    val formatVersion: Int,
    val pass: Int,
    val databaseRevision: Long,
    val startEpochDay: Long?,
    val endEpochDay: Long?,
    val nextEpochDay: Long?,
    val completedChunks: Int,
    val rebuiltRollupRows: Long,
    val dailyRowCount: Long,
    val digest: String,
    val lifetimeCursor: String? = null,
    val completedLifetimePages: Int = 0,
    val lifetimeRowCount: Long = 0,
    val lifetimeComplete: Boolean = false,
    val firstPass: PortableRollupFirstPassEvidence? = null,
) {
    init {
        require(formatVersion == PORTABLE_ROLLUP_PROGRESS_FORMAT_VERSION)
        require(pass == PORTABLE_ROLLUP_FIRST_PASS || pass == PORTABLE_ROLLUP_SECOND_PASS)
        require(databaseRevision >= 0)
        require((startEpochDay == null) == (endEpochDay == null))
        require((startEpochDay == null) == (nextEpochDay == null))
        if (startEpochDay != null) {
            require(startEpochDay <= checkNotNull(endEpochDay))
            require(checkNotNull(nextEpochDay) >= startEpochDay)
        }
        require(completedChunks >= 0)
        require(rebuiltRollupRows >= 0)
        require(dailyRowCount >= 0)
        require(digest.isNotBlank())
        require(completedLifetimePages >= 0)
        require(lifetimeRowCount >= 0)
        if (completedLifetimePages == 0) require(lifetimeCursor == null)
        if (lifetimeComplete) require(nextChunk() == null)
        require((pass == PORTABLE_ROLLUP_FIRST_PASS) == (firstPass == null))
    }

    fun nextChunk(): LocalDateRange? {
        val next = nextEpochDay ?: return null
        val end = checkNotNull(endEpochDay)
        if (next > end) return null
        val chunkEnd = minOf(
            end,
            Math.addExact(next, PORTABLE_ROLLUP_CHUNK_DAYS - 1L),
        )
        return LocalDateRange(
            start = ImmersionLocalDate(next),
            endInclusive = ImmersionLocalDate(chunkEnd),
        )
    }

    fun requireDatabaseRevision(actualRevision: Long) {
        if (actualRevision != databaseRevision) {
            throw PortableRollupRevisionChangedException()
        }
    }

    fun advance(
        chunk: LocalDateRange,
        rebuiltRows: Long,
        fingerprint: PortableContentFingerprint,
    ): PortableRollupPassProgress {
        check(chunk == nextChunk()) { "Portable rollup chunk does not match its cursor" }
        require(rebuiltRows >= 0)
        return copy(
            nextEpochDay = Math.addExact(chunk.endInclusive.epochDay, 1),
            completedChunks = Math.addExact(completedChunks, 1),
            rebuiltRollupRows = Math.addExact(rebuiltRollupRows, rebuiltRows),
            dailyRowCount = Math.addExact(dailyRowCount, fingerprint.rowCount),
            digest = fingerprint.digest,
        )
    }

    fun advanceLifetimeFingerprint(
        page: PortableContentFingerprintPage,
    ): PortableRollupPassProgress {
        check(nextChunk() == null) {
            "Portable lifetime fingerprint cannot start before daily chunks complete"
        }
        check(!lifetimeComplete) { "Portable lifetime fingerprint is already complete" }
        check(page.fingerprint.rowCount > 0)
        val lastScopeKey = checkNotNull(page.lastScopeKey)
        check(lastScopeKey != lifetimeCursor) {
            "Portable lifetime fingerprint cursor did not advance"
        }
        return copy(
            lifetimeCursor = lastScopeKey,
            completedLifetimePages = Math.addExact(completedLifetimePages, 1),
            lifetimeRowCount = Math.addExact(
                lifetimeRowCount,
                page.fingerprint.rowCount,
            ),
            digest = page.fingerprint.digest,
        )
    }

    fun completeLifetimeFingerprint(): PortableRollupPassProgress {
        check(nextChunk() == null) {
            "Portable lifetime fingerprint cannot complete before daily chunks"
        }
        check(!lifetimeComplete) { "Portable lifetime fingerprint is already complete" }
        val output = ByteArrayOutputStream()
        output.writeField(digest)
        output.writeField("lifetime-complete")
        output.writeLong(lifetimeRowCount)
        return copy(
            lifetimeComplete = true,
            digest = output.sha256(),
        )
    }

    companion object {
        fun initial(
            pass: Int,
            range: LocalDateRange?,
            databaseRevision: Long,
            firstPass: PortableRollupFirstPassEvidence?,
        ): PortableRollupPassProgress {
            val output = ByteArrayOutputStream()
            output.writeField(PORTABLE_ROLLUP_FINGERPRINT_DOMAIN)
            output.writeLong(ImmersionStatsVersions.ROLLUP.toLong())
            return PortableRollupPassProgress(
                formatVersion = PORTABLE_ROLLUP_PROGRESS_FORMAT_VERSION,
                pass = pass,
                databaseRevision = databaseRevision,
                startEpochDay = range?.start?.epochDay,
                endEpochDay = range?.endInclusive?.epochDay,
                nextEpochDay = range?.start?.epochDay,
                completedChunks = 0,
                rebuiltRollupRows = 0,
                dailyRowCount = 0,
                digest = output.sha256(),
                firstPass = firstPass,
            )
        }
    }
}

private class PortableRollupRevisionChangedException : IllegalStateException()

private data class PortableContentFingerprint(
    val rowCount: Long,
    val digest: String,
)

private data class PortableContentFingerprintPage(
    val fingerprint: PortableContentFingerprint,
    val lastScopeKey: String?,
)

private fun ImmersionMergeVerification.failureCode(): String? =
    buildList {
        if (eligibleRows != accountedRows) add("ROW_ACCOUNTING_MISMATCH")
        if (firstRollupRows != secondRollupRows || firstRollupDigest != secondRollupDigest) {
            add("ROLLUP_MISMATCH")
        }
        if (!integrity.isFullyHealthy) add("INTEGRITY_FAILURE")
    }.takeIf { it.isNotEmpty() }?.joinToString("+")

private fun ImmersionPortableArchive.canonicalizePortableOrder(): ImmersionPortableArchive {
    val tablesByName = tables.associateBy { it.name }
    return copy(
        tables = IMMERSION_PORTABLE_TABLES.mapNotNull { tableName ->
            tablesByName[tableName]?.let { table ->
                table.copy(
                    rows = table.rows.sortedWith(
                        compareBy<ImmersionPortableRow>(
                            { table.primaryKeySortKey(it) },
                            ImmersionPortableRow::portableHash,
                        ),
                    ),
                )
            }
        },
    )
}

private fun ImmersionPortableArchive.portableMergeDigest(): String {
    val output = ByteArrayOutputStream()
    output.writeLong(IMMERSION_PORTABLE_MERGE_PROTOCOL_VERSION.toLong())
    output.writeLong(formatVersion.toLong())
    output.writeLong(sourceSchemaVersion.toLong())
    output.writeLong(createdAtEpochMillis)
    output.writeLong(includesRawText.toLong())
    output.writeLong(tables.size.toLong())
    tables.forEach { table ->
        output.writeField(table.name)
        output.writeLong(table.columns.size.toLong())
        table.columns.forEach { column ->
            output.writeField(column.name)
            output.writeField(column.affinity.name)
            output.writeLong(column.primaryKeyPosition.toLong())
        }
        output.writeLong(table.rows.size.toLong())
        table.rows.forEach { row ->
            output.writeField(row.portableHash())
        }
    }
    return output.sha256()
}

private fun ImmersionPortableTable.primaryKeySortKey(row: ImmersionPortableRow): String {
    val output = ByteArrayOutputStream()
    columns
        .withIndex()
        .filter { it.value.primaryKeyPosition > 0 }
        .sortedBy { it.value.primaryKeyPosition }
        .forEach { indexedColumn ->
            val cell = row.cells[indexedColumn.index]
            output.writeField(cell.kind.name)
            output.writeField(cell.portableIdentityValue())
        }
    return output.toByteArray().joinToString("") { "%02x".format(it) }
}

private fun SqlDriver.loadPortableMergeCheckpoint(
    archiveDigest: String,
): PortableMergeCheckpoint? =
    executeQuery(
        identifier = null,
        sql = """
            SELECT
                archive_digest,
                protocol_version,
                archive_created_at,
                archive_format_version,
                archive_schema_version,
                includes_raw_text,
                stage,
                table_ordinal,
                next_row_offset,
                eligible_row_count,
                inserted_rows,
                unchanged_rows,
                skipped_by_tombstone_rows,
                quarantined_conflicts,
                rebuilt_rollup_rows,
                verification_json,
                last_error_code,
                started_at,
                updated_at,
                completed_at
            FROM immersion_portable_merge_checkpoint
            WHERE archive_digest = ?
        """.trimIndent(),
        mapper = { cursor ->
            QueryResult.Value(
                if (cursor.next().value) {
                    PortableMergeCheckpoint(
                        archiveDigest = checkNotNull(cursor.getString(0)),
                        protocolVersion = checkNotNull(cursor.getLong(1))
                            .toIntExact("merge protocol version"),
                        archiveCreatedAt = checkNotNull(cursor.getLong(2)),
                        archiveFormatVersion = checkNotNull(cursor.getLong(3))
                            .toIntExact("archive format version"),
                        archiveSchemaVersion = checkNotNull(cursor.getLong(4))
                            .toIntExact("archive schema version"),
                        includesRawText = checkNotNull(cursor.getLong(5))
                            .toBooleanExact("archive raw-text flag"),
                        stage = PortableMergeStage.valueOf(checkNotNull(cursor.getString(6))),
                        tableOrdinal = checkNotNull(cursor.getLong(7))
                            .toIntExact("merge table ordinal"),
                        nextRowOffset = checkNotNull(cursor.getLong(8))
                            .toIntExact("merge row offset"),
                        eligibleRowCount = checkNotNull(cursor.getLong(9)),
                        insertedRows = checkNotNull(cursor.getLong(10)),
                        unchangedRows = checkNotNull(cursor.getLong(11)),
                        skippedByTombstoneRows = checkNotNull(cursor.getLong(12)),
                        quarantinedConflicts = checkNotNull(cursor.getLong(13)),
                        rebuiltRollupRows = checkNotNull(cursor.getLong(14)),
                        verificationJson = cursor.getString(15),
                        lastErrorCode = cursor.getString(16),
                        startedAt = checkNotNull(cursor.getLong(17)),
                        updatedAt = checkNotNull(cursor.getLong(18)),
                        completedAt = cursor.getLong(19),
                    )
                } else {
                    null
                },
            )
        },
        parameters = 1,
    ) {
        bindString(0, archiveDigest)
    }.value

private fun SqlDriver.insertPortableMergeCheckpoint(
    checkpoint: PortableMergeCheckpoint,
): Boolean =
    execute(
        identifier = null,
        sql = """
            INSERT OR IGNORE INTO immersion_portable_merge_checkpoint(
                archive_digest,
                protocol_version,
                archive_created_at,
                archive_format_version,
                archive_schema_version,
                includes_raw_text,
                stage,
                table_ordinal,
                next_row_offset,
                eligible_row_count,
                inserted_rows,
                unchanged_rows,
                skipped_by_tombstone_rows,
                quarantined_conflicts,
                rebuilt_rollup_rows,
                verification_json,
                last_error_code,
                started_at,
                updated_at,
                completed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        parameters = 20,
    ) {
        bindPortableMergeCheckpoint(checkpoint)
    }.value == 1L

private fun SqlDriver.updatePortableMergeCheckpoint(checkpoint: PortableMergeCheckpoint) {
    val changed = execute(
        identifier = null,
        sql = """
            UPDATE immersion_portable_merge_checkpoint
            SET
                protocol_version = ?,
                archive_created_at = ?,
                archive_format_version = ?,
                archive_schema_version = ?,
                includes_raw_text = ?,
                stage = ?,
                table_ordinal = ?,
                next_row_offset = ?,
                eligible_row_count = ?,
                inserted_rows = ?,
                unchanged_rows = ?,
                skipped_by_tombstone_rows = ?,
                quarantined_conflicts = ?,
                rebuilt_rollup_rows = ?,
                verification_json = ?,
                last_error_code = ?,
                started_at = ?,
                updated_at = ?,
                completed_at = ?
            WHERE archive_digest = ?
        """.trimIndent(),
        parameters = 20,
    ) {
        bindLong(0, checkpoint.protocolVersion.toLong())
        bindLong(1, checkpoint.archiveCreatedAt)
        bindLong(2, checkpoint.archiveFormatVersion.toLong())
        bindLong(3, checkpoint.archiveSchemaVersion.toLong())
        bindLong(4, checkpoint.includesRawText.toLong())
        bindString(5, checkpoint.stage.name)
        bindLong(6, checkpoint.tableOrdinal.toLong())
        bindLong(7, checkpoint.nextRowOffset.toLong())
        bindLong(8, checkpoint.eligibleRowCount)
        bindLong(9, checkpoint.insertedRows)
        bindLong(10, checkpoint.unchangedRows)
        bindLong(11, checkpoint.skippedByTombstoneRows)
        bindLong(12, checkpoint.quarantinedConflicts)
        bindLong(13, checkpoint.rebuiltRollupRows)
        bindString(14, checkpoint.verificationJson)
        bindString(15, checkpoint.lastErrorCode)
        bindLong(16, checkpoint.startedAt)
        bindLong(17, checkpoint.updatedAt)
        bindLong(18, checkpoint.completedAt)
        bindString(19, checkpoint.archiveDigest)
    }.value
    check(changed == 1L) { "Portable merge checkpoint disappeared" }
}

private fun SqlDriver.compareAndSetPortableRollupCheckpoint(
    expected: PortableMergeCheckpoint,
    updated: PortableMergeCheckpoint,
): Boolean {
    require(expected.archiveDigest == updated.archiveDigest)
    require(expected.eligibleRowCount == updated.eligibleRowCount)
    require(expected.accountedRows == updated.accountedRows)
    return execute(
        identifier = null,
        sql = """
            UPDATE immersion_portable_merge_checkpoint
            SET
                stage = ?,
                rebuilt_rollup_rows = ?,
                verification_json = ?,
                last_error_code = ?,
                updated_at = ?,
                completed_at = ?
            WHERE archive_digest = ?
                AND stage = ?
                AND verification_json IS ?
        """.trimIndent(),
        parameters = 9,
    ) {
        bindString(0, updated.stage.name)
        bindLong(1, updated.rebuiltRollupRows)
        bindString(2, updated.verificationJson)
        bindString(3, updated.lastErrorCode)
        bindLong(4, updated.updatedAt)
        bindLong(5, updated.completedAt)
        bindString(6, expected.archiveDigest)
        bindString(7, expected.stage.name)
        bindString(8, expected.verificationJson)
    }.value == 1L
}

private fun app.cash.sqldelight.db.SqlPreparedStatement.bindPortableMergeCheckpoint(
    checkpoint: PortableMergeCheckpoint,
) {
    bindString(0, checkpoint.archiveDigest)
    bindLong(1, checkpoint.protocolVersion.toLong())
    bindLong(2, checkpoint.archiveCreatedAt)
    bindLong(3, checkpoint.archiveFormatVersion.toLong())
    bindLong(4, checkpoint.archiveSchemaVersion.toLong())
    bindLong(5, checkpoint.includesRawText.toLong())
    bindString(6, checkpoint.stage.name)
    bindLong(7, checkpoint.tableOrdinal.toLong())
    bindLong(8, checkpoint.nextRowOffset.toLong())
    bindLong(9, checkpoint.eligibleRowCount)
    bindLong(10, checkpoint.insertedRows)
    bindLong(11, checkpoint.unchangedRows)
    bindLong(12, checkpoint.skippedByTombstoneRows)
    bindLong(13, checkpoint.quarantinedConflicts)
    bindLong(14, checkpoint.rebuiltRollupRows)
    bindString(15, checkpoint.verificationJson)
    bindString(16, checkpoint.lastErrorCode)
    bindLong(17, checkpoint.startedAt)
    bindLong(18, checkpoint.updatedAt)
    bindLong(19, checkpoint.completedAt)
}

private fun SqlDriver.immersionDailyRollupFingerprint(
    range: LocalDateRange,
    seedDigest: String,
): PortableContentFingerprint {
    require(seedDigest.isNotBlank())
    val tableName = "immersion_daily_rollup"
    val columns = portableColumns(tableName).filterNot {
        it.name in IMMERSION_ROLLUP_FINGERPRINT_EXCLUDED_COLUMNS
    }
    val projection = columns.joinToString(", ") { it.name.quotedIdentifier() }
    val primaryKeyOrder = columns
        .filter { it.primaryKeyPosition > 0 }
        .sortedBy { it.primaryKeyPosition }
        .joinToString(", ") { it.name.quotedIdentifier() }
    return executeQuery(
        identifier = null,
        sql = """
            SELECT $projection
            FROM ${tableName.quotedIdentifier()}
            WHERE local_date BETWEEN ? AND ?
            ORDER BY local_date, $primaryKeyOrder
        """.trimIndent(),
        mapper = { cursor ->
            var rowCount = 0L
            var digest = seedDigest
            while (cursor.next().value) {
                val row = cursor.readPortableRow(
                    tableName = tableName,
                    columns = columns,
                    includePrivateText = true,
                )
                digest = portableRollupSemanticDigest(
                    previousDigest = digest,
                    rowKind = "daily",
                    rowDigest = row.portableHash(),
                )
                rowCount = Math.addExact(rowCount, 1)
            }
            QueryResult.Value(
                PortableContentFingerprint(
                    rowCount = rowCount,
                    digest = digest,
                ),
            )
        },
        parameters = 2,
    ) {
        bindLong(0, range.start.epochDay)
        bindLong(1, range.endInclusive.epochDay)
    }.value
}

private fun SqlDriver.immersionHourlyRollupDigest(
    range: LocalDateRange,
    seedDigest: String,
): String {
    require(seedDigest.isNotBlank())
    val tableName = "immersion_hourly_rollup"
    val columns = portableColumns(tableName)
    val projection = columns.joinToString(", ") { it.name.quotedIdentifier() }
    val primaryKeyOrder = columns
        .filter { it.primaryKeyPosition > 0 }
        .sortedBy { it.primaryKeyPosition }
        .joinToString(", ") { it.name.quotedIdentifier() }
    return executeQuery(
        identifier = null,
        sql = """
            SELECT $projection
            FROM ${tableName.quotedIdentifier()}
            WHERE local_date BETWEEN ? AND ?
            ORDER BY local_date, local_hour, $primaryKeyOrder
        """.trimIndent(),
        mapper = { cursor ->
            var digest = seedDigest
            while (cursor.next().value) {
                val row = cursor.readPortableRow(
                    tableName = tableName,
                    columns = columns,
                    includePrivateText = true,
                )
                digest = portableRollupSemanticDigest(
                    previousDigest = digest,
                    rowKind = "hourly",
                    rowDigest = row.portableHash(),
                )
            }
            QueryResult.Value(digest)
        },
        parameters = 2,
    ) {
        bindLong(0, range.start.epochDay)
        bindLong(1, range.endInclusive.epochDay)
    }.value
}

private fun portableRollupSemanticDigest(
    previousDigest: String,
    rowKind: String,
    rowDigest: String,
): String {
    val output = ByteArrayOutputStream()
    output.writeField(previousDigest)
    output.writeField(rowKind)
    output.writeField(rowDigest)
    return output.sha256()
}

private fun SqlDriver.immersionLifetimeRollupFingerprintPage(
    afterScopeKey: String?,
    limit: Int,
    seedDigest: String,
): PortableContentFingerprintPage {
    require(limit > 0)
    require(seedDigest.isNotBlank())
    val tableName = "immersion_lifetime_rollup"
    val columns = portableColumns(tableName).filterNot {
        it.name in IMMERSION_ROLLUP_FINGERPRINT_EXCLUDED_COLUMNS
    }
    val projection = columns.joinToString(", ") { it.name.quotedIdentifier() }
    val scopeKeyIndex = columns.indexOfFirst { it.name == "scope_key" }
    check(scopeKeyIndex >= 0)
    val mapper = { cursor: app.cash.sqldelight.db.SqlCursor ->
        var rowCount = 0L
        var lastScopeKey: String? = null
        var digest = seedDigest
        while (cursor.next().value) {
            lastScopeKey = checkNotNull(cursor.getString(scopeKeyIndex))
            val row = cursor.readPortableRow(
                tableName = tableName,
                columns = columns,
                includePrivateText = true,
            )
            digest = portableRollupSemanticDigest(
                previousDigest = digest,
                rowKind = "lifetime",
                rowDigest = row.portableHash(),
            )
            rowCount = Math.addExact(rowCount, 1)
        }
        QueryResult.Value(
            PortableContentFingerprintPage(
                fingerprint = PortableContentFingerprint(
                    rowCount = rowCount,
                    digest = digest,
                ),
                lastScopeKey = lastScopeKey,
            ),
        )
    }
    return if (afterScopeKey == null) {
        executeQuery(
            identifier = null,
            sql = """
                SELECT $projection
                FROM ${tableName.quotedIdentifier()}
                ORDER BY scope_key
                LIMIT ?
            """.trimIndent(),
            mapper = mapper,
            parameters = 1,
        ) {
            bindLong(0, limit.toLong())
        }.value
    } else {
        executeQuery(
            identifier = null,
            sql = """
                SELECT $projection
                FROM ${tableName.quotedIdentifier()}
                WHERE scope_key > ?
                ORDER BY scope_key
                LIMIT ?
            """.trimIndent(),
            mapper = mapper,
            parameters = 2,
        ) {
            bindString(0, afterScopeKey)
            bindLong(1, limit.toLong())
        }.value
    }
}

private fun SqlDriver.immersionMergeEntityCounts(): ImmersionMergeEntityCounts =
    executeQuery(
        identifier = null,
        sql = """
            SELECT
                (SELECT count(*) FROM immersion_title),
                (SELECT count(*) FROM immersion_session),
                (SELECT count(*) FROM immersion_event),
                (SELECT count(*) FROM immersion_source_unit),
                (SELECT count(*) FROM immersion_character),
                (SELECT count(*) FROM immersion_anki_operation),
                (SELECT count(*) FROM immersion_goal)
        """.trimIndent(),
        mapper = { cursor ->
            check(cursor.next().value)
            QueryResult.Value(
                ImmersionMergeEntityCounts(
                    titles = checkNotNull(cursor.getLong(0)),
                    sessions = checkNotNull(cursor.getLong(1)),
                    events = checkNotNull(cursor.getLong(2)),
                    sourceUnits = checkNotNull(cursor.getLong(3)),
                    characters = checkNotNull(cursor.getLong(4)),
                    ankiOperations = checkNotNull(cursor.getLong(5)),
                    goals = checkNotNull(cursor.getLong(6)),
                ),
            )
        },
        parameters = 0,
    ).value

private fun SqlDriver.immersionForeignKeyViolationCount(): Long =
    executeQuery(
        identifier = null,
        sql = "PRAGMA foreign_key_check",
        mapper = { cursor ->
            var count = 0L
            while (cursor.next().value) {
                if (cursor.getString(0)?.startsWith("immersion_") == true) {
                    count = Math.addExact(count, 1)
                }
            }
            QueryResult.Value(count)
        },
        parameters = 0,
    ).value

private fun SqlDriver.immersionRevision(): Long =
    singleLong(
        """
        SELECT revision
        FROM immersion_rollup_state
        WHERE scope_key = 'global'
        """.trimIndent(),
    )

private fun ImmersionPortableArchive.withoutUnlinkedAnkiOperations(): ImmersionPortableArchive =
    copy(
        tables = tables.map { table ->
            if (table.name != "immersion_anki_operation") {
                table
            } else {
                val eventIdIndex = table.columnIndex("event_id")
                table.copy(
                    rows = table.rows.filter { row ->
                        row.cells[eventIdIndex].kind != ImmersionPortableCellKind.NULL
                    },
                )
            }
        },
    )

private fun ImmersionPortableTable.columnIndex(name: String): Int =
    columns.indexOfFirst { it.name == name }.also { index ->
        require(index >= 0) { "Portable table ${this.name} is missing column $name" }
    }

private fun ImmersionPortableRow.withInteger(
    index: Int,
    value: Long,
): ImmersionPortableRow =
    copy(
        cells = cells.toMutableList().also { cells ->
            cells[index] = ImmersionPortableCell(
                kind = ImmersionPortableCellKind.INTEGER,
                integerValue = value,
            )
        },
    )

private fun Map<String, ImmersionPortableTable>.touchedSourceUnitIds(): Set<String> =
    buildSet {
        this@touchedSourceUnitIds["immersion_source_unit"]?.let { table ->
            val idIndex = table.columnIndex("id")
            table.rows.mapNotNullTo(this) { row ->
                row.cells[idIndex].portableIdentityValueOrNull()
            }
        }
        this@touchedSourceUnitIds["immersion_source_exposure"]?.let { table ->
            val sourceUnitIdIndex = table.columnIndex("source_unit_id")
            table.rows.mapNotNullTo(this) { row ->
                row.cells[sourceUnitIdIndex].portableIdentityValueOrNull()
            }
        }
    }

private fun SqlDriver.mergePortableTable(
    table: ImmersionPortableTable,
    archiveIncludesRawText: Boolean,
    tombstones: Set<Pair<String, String>>,
    mergedAtEpochMillis: Long,
    tombstoneMetadataOnly: Boolean,
): PortableMergeCounts {
    require(table.name in IMMERSION_PORTABLE_TABLES)
    val expectedColumns = portableColumns(table.name)
    require(table.columns == expectedColumns) {
        "Immersion backup table ${table.name} does not match the current schema"
    }
    val columnNames = table.columns.joinToString(", ") { it.name.quotedIdentifier() }
    val placeholders = table.columns.joinToString(", ") { "?" }
    val insertSql =
        "INSERT OR IGNORE INTO ${table.name.quotedIdentifier()} ($columnNames) VALUES ($placeholders)"
    return table.rows.fold(PortableMergeCounts()) { totals, row ->
        val entityIdentity = table.entityIdentity(row)
        if (
            (entityIdentity != null && entityIdentity in tombstones) ||
            table.referencesTombstone(row, tombstones)
        ) {
            return@fold totals + PortableMergeCounts(skippedByTombstone = 1)
        }
        val changed = execute(
            identifier = null,
            sql = insertSql,
            parameters = row.cells.size,
        ) {
            row.cells.forEachIndexed { index, cell -> bindPortableCell(index, cell) }
        }.value
        if (changed == 1L) {
            return@fold totals + PortableMergeCounts(inserted = 1)
        }

        val existing = selectPortableRowByPrimaryKey(table, row)
        if (existing != null && (
                tombstoneMetadataOnly ||
                    portableRowsEqual(
                        table = table,
                        first = existing,
                        second = row,
                        ignorePrivateText = !archiveIncludesRawText,
                    )
                )
        ) {
            return@fold totals + PortableMergeCounts(unchanged = 1)
        }
        if (
            existing != null &&
            table.name == "immersion_source_unit" &&
            reconcilePortableSourceUnit(table, existing, row)
        ) {
            return@fold totals + PortableMergeCounts(unchanged = 1)
        }
        if (
            existing != null &&
            portableRowsEqual(
                table = table,
                first = existing,
                second = row,
                ignorePrivateText = true,
            ) &&
            portablePrivateCellsCompatible(table, existing, row)
        ) {
            enrichPortablePrivateText(table, row)
            return@fold totals + PortableMergeCounts(unchanged = 1)
        }

        quarantinePortableConflict(
            table = table,
            incoming = row,
            existing = existing,
            detectedAtEpochMillis = mergedAtEpochMillis,
        )
        totals + PortableMergeCounts(conflicts = 1)
    }
}

private fun SqlDriver.reconcilePortableSourceUnit(
    table: ImmersionPortableTable,
    existing: ImmersionPortableRow,
    incoming: ImmersionPortableRow,
): Boolean {
    check(table.name == "immersion_source_unit")
    if (
        IMMERSION_SOURCE_IDENTITY_COLUMNS.any { columnName ->
            val index = table.columnIndex(columnName)
            !existing.cells[index].portableEquals(incoming.cells[index])
        }
    ) {
        return false
    }
    if (!portablePrivateCellsCompatible(table, existing, incoming)) return false

    val preferred = if (comparePortableSourcePreference(table, existing, incoming) >= 0) {
        existing
    } else {
        incoming
    }
    val cells = preferred.cells.toMutableList()
    val firstExposedAtIndex = table.columnIndex("first_exposed_at")
    val lastExposedAtIndex = table.columnIndex("last_exposed_at")
    cells[firstExposedAtIndex] = ImmersionPortableCell(
        kind = ImmersionPortableCellKind.INTEGER,
        integerValue = minOf(
            checkNotNull(existing.cells[firstExposedAtIndex].integerValue),
            checkNotNull(incoming.cells[firstExposedAtIndex].integerValue),
        ),
    )
    cells[lastExposedAtIndex] = ImmersionPortableCell(
        kind = ImmersionPortableCellKind.INTEGER,
        integerValue = maxOf(
            checkNotNull(existing.cells[lastExposedAtIndex].integerValue),
            checkNotNull(incoming.cells[lastExposedAtIndex].integerValue),
        ),
    )
    table.columns.indices.forEach { index ->
        if (
            table.name to table.columns[index].name in IMMERSION_PRIVATE_TEXT_COLUMNS &&
            existing.cells[index].kind != ImmersionPortableCellKind.NULL
        ) {
            cells[index] = existing.cells[index]
        } else if (
            table.name to table.columns[index].name in IMMERSION_PRIVATE_TEXT_COLUMNS &&
            incoming.cells[index].kind != ImmersionPortableCellKind.NULL
        ) {
            cells[index] = incoming.cells[index]
        }
    }
    updatePortableRow(table, ImmersionPortableRow(cells))
    return true
}

private fun comparePortableSourcePreference(
    table: ImmersionPortableTable,
    first: ImmersionPortableRow,
    second: ImmersionPortableRow,
): Int {
    fun long(row: ImmersionPortableRow, columnName: String): Long =
        checkNotNull(row.cells[table.columnIndex(columnName)].integerValue)

    fun text(row: ImmersionPortableRow, columnName: String): String =
        checkNotNull(row.cells[table.columnIndex(columnName)].textValue)

    compareValues(
        long(first, "indexed_version"),
        long(second, "indexed_version"),
    ).takeIf { it != 0 }?.let { return it }
    compareValues(
        sourceIndexStatusRank(text(first, "indexing_status")),
        sourceIndexStatusRank(text(second, "indexing_status")),
    ).takeIf { it != 0 }?.let { return it }
    compareValues(
        long(first, "normalization_version"),
        long(second, "normalization_version"),
    ).takeIf { it != 0 }?.let { return it }
    compareValues(
        long(first, "index_attempt_count"),
        long(second, "index_attempt_count"),
    ).takeIf { it != 0 }?.let { return it }
    return first.portableHash().compareTo(second.portableHash())
}

private fun sourceIndexStatusRank(status: String): Int =
    when (status) {
        "INDEXED" -> 5
        "UNAVAILABLE" -> 4
        "PENDING" -> 3
        "FAILED" -> 2
        "IN_PROGRESS" -> 1
        else -> 0
    }

private fun SqlDriver.updatePortableRow(
    table: ImmersionPortableTable,
    row: ImmersionPortableRow,
) {
    val primaryKeyColumns = table.columns
        .withIndex()
        .filter { it.value.primaryKeyPosition > 0 }
        .sortedBy { it.value.primaryKeyPosition }
    val mutableColumns = table.columns
        .withIndex()
        .filter { it.value.primaryKeyPosition == 0 }
    val assignments = mutableColumns.joinToString(", ") { "${it.value.name.quotedIdentifier()} = ?" }
    val predicates = primaryKeyColumns.joinToString(" AND ") {
        "${it.value.name.quotedIdentifier()} = ?"
    }
    execute(
        identifier = null,
        sql = "UPDATE ${table.name.quotedIdentifier()} SET $assignments WHERE $predicates",
        parameters = mutableColumns.size + primaryKeyColumns.size,
    ) {
        mutableColumns.forEachIndexed { parameter, column ->
            bindPortableCell(parameter, row.cells[column.index])
        }
        primaryKeyColumns.forEachIndexed { offset, column ->
            bindPortableCell(mutableColumns.size + offset, row.cells[column.index])
        }
    }.value
}

private fun SqlDriver.selectPortableRowByPrimaryKey(
    table: ImmersionPortableTable,
    row: ImmersionPortableRow,
): ImmersionPortableRow? {
    val primaryKeyColumns = table.columns
        .withIndex()
        .filter { it.value.primaryKeyPosition > 0 }
        .sortedBy { it.value.primaryKeyPosition }
    val predicates = primaryKeyColumns.joinToString(" AND ") {
        "${it.value.name.quotedIdentifier()} = ?"
    }
    val projection = table.columns.joinToString(", ") { it.name.quotedIdentifier() }
    return executeQuery(
        identifier = null,
        sql = "SELECT $projection FROM ${table.name.quotedIdentifier()} WHERE $predicates",
        mapper = { cursor ->
            QueryResult.Value(
                if (cursor.next().value) {
                    cursor.readPortableRow(
                        tableName = table.name,
                        columns = table.columns,
                        includePrivateText = true,
                    )
                } else {
                    null
                },
            )
        },
        parameters = primaryKeyColumns.size,
    ) {
        primaryKeyColumns.forEachIndexed { parameter, indexedColumn ->
            bindPortableCell(parameter, row.cells[indexedColumn.index])
        }
    }.value
}

private fun app.cash.sqldelight.db.SqlPreparedStatement.bindPortableCell(
    index: Int,
    cell: ImmersionPortableCell,
) {
    when (cell.kind) {
        ImmersionPortableCellKind.NULL -> bindString(index, null)
        ImmersionPortableCellKind.TEXT -> bindString(index, checkNotNull(cell.textValue))
        ImmersionPortableCellKind.INTEGER -> bindLong(index, checkNotNull(cell.integerValue))
        ImmersionPortableCellKind.REAL -> bindDouble(index, checkNotNull(cell.realValue))
        ImmersionPortableCellKind.BLOB -> bindBytes(index, checkNotNull(cell.blobValue))
    }
}

private fun ImmersionPortableTable.entityIdentity(
    row: ImmersionPortableRow,
): Pair<String, String>? {
    val mapping = IMMERSION_TOMBSTONE_IDENTITIES[name] ?: return null
    val index = columns.indexOfFirst { it.name == mapping.second }
    if (index < 0) return null
    return mapping.first to row.cells[index].portableIdentityValue()
}

private fun ImmersionPortableTable.referencesTombstone(
    row: ImmersionPortableRow,
    tombstones: Set<Pair<String, String>>,
): Boolean =
    IMMERSION_TOMBSTONE_REFERENCES[name]
        .orEmpty()
        .any { (entityType, columnName) ->
            val index = columns.indexOfFirst { it.name == columnName }
            index >= 0 &&
                row.cells[index].portableIdentityValueOrNull()?.let {
                    (entityType to it) in tombstones
                } == true
        }

private fun ImmersionPortableCell.portableIdentityValue(): String =
    checkNotNull(portableIdentityValueOrNull()) {
        "A portable primary identity cannot be null"
    }

private fun ImmersionPortableCell.portableIdentityValueOrNull(): String? =
    when (kind) {
        ImmersionPortableCellKind.TEXT -> checkNotNull(textValue)
        ImmersionPortableCellKind.INTEGER -> checkNotNull(integerValue).toString()
        ImmersionPortableCellKind.REAL -> checkNotNull(realValue).toString()
        ImmersionPortableCellKind.BLOB -> checkNotNull(blobValue).joinToString("") { "%02x".format(it) }
        ImmersionPortableCellKind.NULL -> null
    }

private fun portableRowsEqual(
    table: ImmersionPortableTable,
    first: ImmersionPortableRow,
    second: ImmersionPortableRow,
    ignorePrivateText: Boolean,
): Boolean =
    first.cells.indices.all { index ->
        val column = table.columns[index]
        (
            ignorePrivateText &&
                table.name to column.name in IMMERSION_PRIVATE_TEXT_COLUMNS
            ) ||
            first.cells[index].portableEquals(second.cells[index])
    }

private fun ImmersionPortableCell.portableEquals(other: ImmersionPortableCell): Boolean =
    kind == other.kind &&
        textValue == other.textValue &&
        integerValue == other.integerValue &&
        realValue == other.realValue &&
        when {
            blobValue == null && other.blobValue == null -> true
            blobValue == null || other.blobValue == null -> false
            else -> blobValue.contentEquals(other.blobValue)
        }

private fun portablePrivateCellsCompatible(
    table: ImmersionPortableTable,
    existing: ImmersionPortableRow,
    incoming: ImmersionPortableRow,
): Boolean =
    table.columns.indices.all { index ->
        if (table.name to table.columns[index].name !in IMMERSION_PRIVATE_TEXT_COLUMNS) {
            return@all true
        }
        val existingCell = existing.cells[index]
        val incomingCell = incoming.cells[index]
        existingCell.kind == ImmersionPortableCellKind.NULL ||
            incomingCell.kind == ImmersionPortableCellKind.NULL ||
            existingCell.portableEquals(incomingCell)
    }

private fun SqlDriver.enrichPortablePrivateText(
    table: ImmersionPortableTable,
    row: ImmersionPortableRow,
) {
    val privateColumns = table.columns
        .withIndex()
        .filter { table.name to it.value.name in IMMERSION_PRIVATE_TEXT_COLUMNS }
    if (privateColumns.isEmpty()) return
    val primaryKeyColumns = table.columns
        .withIndex()
        .filter { it.value.primaryKeyPosition > 0 }
        .sortedBy { it.value.primaryKeyPosition }
    val assignments = privateColumns.joinToString(", ") {
        val column = it.value.name.quotedIdentifier()
        "$column = CASE WHEN $column IS NULL THEN ? ELSE $column END"
    }
    val predicates = primaryKeyColumns.joinToString(" AND ") {
        "${it.value.name.quotedIdentifier()} = ?"
    }
    execute(
        identifier = null,
        sql = "UPDATE ${table.name.quotedIdentifier()} SET $assignments WHERE $predicates",
        parameters = privateColumns.size + primaryKeyColumns.size,
    ) {
        privateColumns.forEachIndexed { parameter, column ->
            bindPortableCell(parameter, row.cells[column.index])
        }
        primaryKeyColumns.forEachIndexed { offset, column ->
            bindPortableCell(privateColumns.size + offset, row.cells[column.index])
        }
    }.value
}

private fun SqlDriver.quarantinePortableConflict(
    table: ImmersionPortableTable,
    incoming: ImmersionPortableRow,
    existing: ImmersionPortableRow?,
    detectedAtEpochMillis: Long,
) {
    val identity = table.columns
        .withIndex()
        .filter { it.value.primaryKeyPosition > 0 }
        .sortedBy { it.value.primaryKeyPosition }
        .joinToString("|") { "${it.value.name}=${incoming.cells[it.index].portableIdentityValue()}" }
    val incomingHash = incoming.portableHash()
    val existingHash = existing?.portableHash()
    val conflictId = "${
        table.name
    }\u0000$identity\u0000$incomingHash".encodeToByteArray().sha256Hex()
    execute(
        identifier = null,
        sql = """
            INSERT OR IGNORE INTO immersion_merge_conflict(
                id,
                table_name,
                identity_key,
                existing_payload_hash,
                incoming_payload_hash,
                detected_at,
                resolution_state
            ) VALUES (?, ?, ?, ?, ?, ?, 'QUARANTINED')
        """.trimIndent(),
        parameters = 6,
    ) {
        bindString(0, conflictId)
        bindString(1, table.name)
        bindString(2, identity)
        bindString(3, existingHash)
        bindString(4, incomingHash)
        bindLong(5, detectedAtEpochMillis)
    }.value
}

private fun ImmersionPortableRow.portableHash(): String {
    val output = ByteArrayOutputStream()
    cells.forEach { cell ->
        output.writeField(cell.kind.name)
        when (cell.kind) {
            ImmersionPortableCellKind.NULL -> Unit
            ImmersionPortableCellKind.TEXT -> output.writeField(checkNotNull(cell.textValue))
            ImmersionPortableCellKind.INTEGER -> output.writeLong(checkNotNull(cell.integerValue))
            ImmersionPortableCellKind.REAL -> output.writeLong(
                checkNotNull(cell.realValue).toBits(),
            )
            ImmersionPortableCellKind.BLOB -> {
                val bytes = checkNotNull(cell.blobValue)
                output.writeLong(bytes.size.toLong())
                output.write(bytes)
            }
        }
    }
    return output.sha256()
}

private fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

private fun SqlDriver.loadImmersionTombstones(): Set<Pair<String, String>> =
    executeQuery(
        identifier = null,
        sql = "SELECT entity_type, entity_id FROM immersion_tombstone",
        mapper = { cursor ->
            val result = mutableSetOf<Pair<String, String>>()
            while (cursor.next().value) {
                result += checkNotNull(cursor.getString(0)).uppercase(Locale.ROOT) to
                    checkNotNull(cursor.getString(1))
            }
            QueryResult.Value(result)
        },
        parameters = 0,
    ).value

private fun SqlDriver.previewAllImmersionDeletion(): ImmersionDeletionPreview =
    executeQuery(
        identifier = null,
        sql = """
            SELECT
                (SELECT count(*) FROM immersion_session),
                (SELECT coalesce(sum(active_duration_ms), 0) FROM immersion_session),
                (SELECT coalesce(sum(gross_characters), 0) FROM immersion_session),
                (SELECT count(*) FROM immersion_source_unit),
                (SELECT count(*) FROM immersion_character),
                (SELECT count(*) FROM immersion_goal)
        """.trimIndent(),
        mapper = { cursor ->
            check(cursor.next().value)
            QueryResult.Value(
                ImmersionDeletionPreview(
                    sessions = checkNotNull(cursor.getLong(0)),
                    activeDurationMillis = checkNotNull(cursor.getLong(1)),
                    grossCharacters = checkNotNull(cursor.getLong(2)),
                    sourceUnits = checkNotNull(cursor.getLong(3)),
                    characters = checkNotNull(cursor.getLong(4)),
                    goals = checkNotNull(cursor.getLong(5)),
                ),
            )
        },
        parameters = 0,
    ).value

private fun ImmersionGoal.isAffectedByDeletionOf(session: ImmersionSession): Boolean {
    if (type == "MANUAL" || metric == "manual") return false
    val goalStartDate = startDate
    val goalEndDate = endDate
    val calendar = ImmersionAnalyticsCalendar()
    val sessionStartDate = calendar.localDate(
        session.startedAtEpochMillis,
        session.startOffsetSeconds,
    )
    val sessionEndDate = calendar.localDate(
        session.endedAtEpochMillis ?: session.startedAtEpochMillis,
        session.startOffsetSeconds,
    )
    return (goalStartDate == null || sessionEndDate >= goalStartDate) &&
        (goalEndDate == null || sessionStartDate <= goalEndDate) &&
        (mediaKind == null || mediaKind == session.mediaKind) &&
        (profileId == null || profileId == session.profileId) &&
        (languageTag == null || languageTag == session.languageTag) &&
        (titleId == null || titleId == session.titleId)
}

private fun SqlDriver.singleLong(sql: String): Long =
    checkNotNull(singleNullableLong(sql)) { "Query returned no value: $sql" }

private fun SqlDriver.singleNullableLong(sql: String): Long? =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            QueryResult.Value(
                if (cursor.next().value) cursor.getLong(0) else null,
            )
        },
        parameters = 0,
    ).value

private fun SqlDriver.applyImmersionTombstones(tombstones: Set<Pair<String, String>>) {
    tombstones.forEach { (entityType, entityId) ->
        when (entityType) {
            "SESSION" -> executeDeleteByIdentity("immersion_session", "id", entityId)
            "TITLE" -> {
                executeDeleteByIdentity("immersion_session", "title_id", entityId)
                executeDeleteByIdentity("immersion_source_unit", "title_id", entityId)
                executeDeleteByIdentity("immersion_title", "id", entityId)
            }
            "SOURCE_UNIT" -> {
                executeDeleteByIdentity("immersion_source_exposure", "source_unit_id", entityId)
                executeDeleteByIdentity("immersion_source_unit", "id", entityId)
            }
            "EVENT" -> executeDeleteByIdentity("immersion_event", "id", entityId)
            "CHARACTER" -> executeDeleteByIdentity(
                "immersion_character",
                "code_point",
                entityId.toLongOrNull() ?: return@forEach,
            )
            "GOAL" -> executeDeleteByIdentity("immersion_goal", "id", entityId)
        }
    }
}

private fun SqlDriver.executeDeleteByIdentity(
    tableName: String,
    columnName: String,
    value: Any,
) {
    execute(
        identifier = null,
        sql = "DELETE FROM ${tableName.quotedIdentifier()} WHERE ${columnName.quotedIdentifier()} = ?",
        parameters = 1,
    ) {
        when (value) {
            is String -> bindString(0, value)
            is Long -> bindLong(0, value)
            else -> error("Unsupported tombstone identity")
        }
    }.value
}

private fun String.portableAffinity(): ImmersionPortableAffinity {
    val type = uppercase(Locale.ROOT)
    return when {
        "INT" in type -> ImmersionPortableAffinity.INTEGER
        "CHAR" in type || "CLOB" in type || "TEXT" in type -> ImmersionPortableAffinity.TEXT
        "BLOB" in type || type.isBlank() -> ImmersionPortableAffinity.BLOB
        "REAL" in type || "FLOA" in type || "DOUB" in type -> ImmersionPortableAffinity.REAL
        else -> ImmersionPortableAffinity.INTEGER
    }
}

private fun String.quotedIdentifier(): String = "\"${replace("\"", "\"\"")}\""

private inline fun <T> mapCorruption(subject: String, block: () -> T): T =
    try {
        block()
    } catch (error: ImmersionDataException) {
        throw error
    } catch (error: RuntimeException) {
        throw ImmersionDataException(
            PersistenceErrorCode.CORRUPT_VALUE,
            "Invalid value stored for $subject",
            error,
        )
    }

private fun Long.toIntExact(field: String): Int =
    try {
        Math.toIntExact(this)
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("$field is outside the supported range", error)
    }

private fun Long.toBooleanExact(field: String): Boolean =
    when (this) {
        0L -> false
        1L -> true
        else -> throw IllegalArgumentException("$field must be zero or one")
    }

private fun Boolean.toLong(): Long = if (this) 1 else 0

private fun identityConflict(message: String) =
    ImmersionDataException(PersistenceErrorCode.IDENTITY_CONFLICT, message)

private fun portableRollupRepairCursor(
    archiveCreatedAt: Long,
    archiveDigest: String,
    pass: Int,
): String = "portable-merge:$archiveCreatedAt:$archiveDigest:rollup-pass-$pass"

private fun repairedAnkiEventId(operation: PendingAnkiOperation): EventId =
    EventId(
        UUID.nameUUIDFromBytes(
            "$ANKI_REPAIR_EVENT_NAMESPACE\u0000${operation.token.operationId.value}"
                .toByteArray(StandardCharsets.UTF_8),
        ).toString(),
    )

private fun compactedHeartbeatEventId(
    sessionId: String,
    localDate: Long,
    sequence: Long,
): String =
    UUID.nameUUIDFromBytes(
        "$HEARTBEAT_COMPACTION_EVENT_NAMESPACE\u0000$sessionId\u0000$localDate\u0000$sequence"
            .toByteArray(StandardCharsets.UTF_8),
    ).toString()

private data class AnkiSummaryCacheKey(
    val filter: StatsFilter,
    val revision: Long,
    val snapshotId: String?,
)

private const val MAX_PAGE_SIZE = 500
private const val MAX_HEARTBEAT_COMPACTION_SESSIONS = 500
private const val MIN_HEARTBEATS_PER_COMPACTION_WINDOW = 3
private const val HEARTBEAT_COMPACTION_WINDOW_MILLIS = 5 * 60 * 1_000L
private const val HEARTBEAT_COMPACTION_METADATA_VERSION = 2L
private const val HEARTBEAT_COMPACTION_EVENT_NAMESPACE = "chimahon:immersion:heartbeat-compaction:v1"
private const val SQLITE_BIND_BATCH_SIZE = 400
private const val MAX_TITLE_TREND_SERIES = 20
private const val MAX_ANKI_TITLE_IMPACT_ROWS = 20
private const val ANKI_SUMMARY_CACHE_SIZE = 8
private const val SOURCE_EXCERPT_LENGTH = 240
/**
 * Bumped from 1: the archive no longer carries the word, word-occurrence, or
 * lookup tables, and ImmersionMergeEntityCounts lost its words and lookups
 * fields. An archive written by a build that tracked vocabulary is refused with
 * ImmersionArchiveRejection.UNKNOWN_TABLE rather than silently losing that data.
 */
private const val IMMERSION_PORTABLE_FORMAT_VERSION = 2
private const val IMMERSION_PORTABLE_MERGE_PROTOCOL_VERSION = 1
private const val IMMERSION_PORTABLE_MERGE_CHUNK_SIZE = 500
private const val IMMERSION_INDEX_ID_CHUNK_SIZE = 500
private const val IMMERSION_TOMBSTONE_TABLE = "immersion_tombstone"
private const val IMMERSION_ROLLUP_FIRST_PASS_CHECKPOINT = "immersion_rollup_first_pass"
private const val IMMERSION_ROLLUP_FIRST_PASS_CHUNK_CHECKPOINT =
    "immersion_rollup_first_pass_chunk"
private const val IMMERSION_ROLLUP_SECOND_PASS_CHUNK_CHECKPOINT =
    "immersion_rollup_second_pass_chunk"
private const val IMMERSION_ROLLUP_FIRST_PASS_LIFETIME_CHECKPOINT =
    "immersion_rollup_first_pass_lifetime"
private const val IMMERSION_ROLLUP_SECOND_PASS_LIFETIME_CHECKPOINT =
    "immersion_rollup_second_pass_lifetime"
private const val PORTABLE_ROLLUP_FIRST_PASS = 1
private const val PORTABLE_ROLLUP_SECOND_PASS = 2
private const val PORTABLE_ROLLUP_PROGRESS_FORMAT_VERSION = 3
private const val PORTABLE_ROLLUP_FINGERPRINT_VERSION = 3
private const val PORTABLE_ROLLUP_CHUNK_DAYS = 31L
private const val PORTABLE_ROLLUP_LIFETIME_PAGE_SIZE = 256
private const val PORTABLE_ROLLUP_FINGERPRINT_DOMAIN =
    "chimahon:immersion:portable-rollup-semantic:v3"
private const val IMMERSION_TITLE_EXCLUSION_TYPE = "TITLE"
private const val IMMERSION_CAPTURE_EXCLUSION_SCOPE = "capture"
private const val IMMERSION_GLOBAL_EXCLUSION_SCOPE = ""
private const val MILLIS_PER_DAY = 86_400_000L
private const val MAX_ZONE_OFFSET_MILLIS = 18L * 60L * 60L * 1_000L
private const val UTF8 = "UTF-8"
private const val MIN_TIMEZONE_OFFSET_SECONDS = -18 * 60 * 60
private const val MAX_TIMEZONE_OFFSET_SECONDS = 18 * 60 * 60
private const val COMPLETION_UNIT_METADATA_VERSION = 2L
private const val ANKI_REPAIR_EVENT_NAMESPACE = "chimahon-immersion-anki-repair-event"
private const val ANKI_CHARACTER_QUERY_BATCH_SIZE = 500
private const val TITLE_MUTATION_APPLIED = "APPLIED"
private const val TITLE_MUTATION_DIGEST_DOMAIN = "chimahon:immersion:title-mutation:v1"

private val IMMERSION_ROLLUP_MUTATION_MUTEX = Mutex()

private val IMMERSION_PORTABLE_TABLES = listOf(
    "immersion_title",
    "immersion_title_override",
    "immersion_title_alias",
    "immersion_session",
    "immersion_session_origin",
    "immersion_source_unit",
    "immersion_source_origin",
    "immersion_event",
    "immersion_source_exposure",
    "immersion_character",
    "immersion_character_occurrence",
    "immersion_anki_operation",
    "immersion_anki_snapshot",
    "immersion_anki_item",
    "immersion_anki_character",
    "immersion_goal",
    "immersion_goal_check_in",
    "immersion_goal_achievement",
    "immersion_import_ledger",
    "immersion_sync_peer",
    "immersion_tombstone",
    "immersion_exclusion",
    "immersion_retention_state",
)

private val IMMERSION_PRIVATE_TEXT_COLUMNS = setOf(
    "immersion_source_unit" to "raw_text",
    "immersion_source_unit" to "raw_text_encoding",
    "immersion_goal_check_in" to "note",
)

private val IMMERSION_ROLLUP_FINGERPRINT_EXCLUDED_COLUMNS = setOf("updated_at")

private val IMMERSION_SOURCE_IDENTITY_COLUMNS = setOf(
    "id",
    "title_id",
    "source_kind",
    "canonical_locator",
    "normalized_text_hash",
)

private val IMMERSION_TOMBSTONE_IDENTITIES = mapOf(
    "immersion_title" to ("TITLE" to "id"),
    "immersion_session" to ("SESSION" to "id"),
    "immersion_source_unit" to ("SOURCE_UNIT" to "id"),
    "immersion_event" to ("EVENT" to "id"),
    "immersion_character" to ("CHARACTER" to "code_point"),
    "immersion_goal" to ("GOAL" to "id"),
)

private val IMMERSION_TOMBSTONE_REFERENCES = mapOf(
    "immersion_title_override" to listOf("TITLE" to "title_id"),
    "immersion_title_alias" to listOf(
        "TITLE" to "source_title_id",
        "TITLE" to "target_title_id",
    ),
    "immersion_session" to listOf("TITLE" to "title_id"),
    "immersion_session_origin" to listOf(
        "SESSION" to "session_id",
        "TITLE" to "origin_title_id",
    ),
    "immersion_source_unit" to listOf("TITLE" to "title_id"),
    "immersion_source_origin" to listOf(
        "SOURCE_UNIT" to "source_unit_id",
        "TITLE" to "origin_title_id",
    ),
    "immersion_event" to listOf(
        "SESSION" to "session_id",
        "SOURCE_UNIT" to "source_unit_id",
    ),
    "immersion_source_exposure" to listOf(
        "EVENT" to "event_id",
        "SESSION" to "session_id",
        "SOURCE_UNIT" to "source_unit_id",
    ),
    "immersion_character_occurrence" to listOf(
        "CHARACTER" to "character_code_point",
        "SOURCE_UNIT" to "source_unit_id",
    ),
    "immersion_anki_operation" to listOf(
        "EVENT" to "event_id",
        "SESSION" to "session_id",
        "SOURCE_UNIT" to "source_unit_id",
    ),
    "immersion_goal" to listOf("TITLE" to "title_id"),
    "immersion_goal_check_in" to listOf("GOAL" to "goal_id"),
    "immersion_goal_achievement" to listOf("GOAL" to "goal_id"),
)

private val IMMERSION_RESET_DERIVED_TABLES = listOf(
    "immersion_title_mutation_session",
    "immersion_title_mutation_source",
    "immersion_title_mutation_goal",
    "immersion_title_mutation",
    "immersion_anki_operation",
    "immersion_anki_snapshot",
    "immersion_daily_rollup",
    "immersion_hourly_rollup",
    "immersion_lifetime_rollup",
    "immersion_applied_event",
    "immersion_rollup_dirty",
    "immersion_goal_check_in",
    "immersion_goal_achievement",
    "immersion_import_ledger",
    "immersion_sync_peer",
    "immersion_exclusion",
    "immersion_retention_state",
    "immersion_merge_conflict",
    "immersion_portable_merge_checkpoint",
)
