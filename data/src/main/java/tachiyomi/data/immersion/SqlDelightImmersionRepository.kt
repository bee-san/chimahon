// SPDX-License-Identifier: MIT

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
import tachiyomi.data.SelectImmersionIndexWork
import tachiyomi.data.SelectLegacyImmersionAggregates
import tachiyomi.domain.immersion.model.AnalyticsActivityTotals
import tachiyomi.domain.immersion.model.AnalyticsAnkiSummary
import tachiyomi.domain.immersion.model.AnalyticsBucketInventory
import tachiyomi.domain.immersion.model.AnalyticsCharacterRow
import tachiyomi.domain.immersion.model.AnalyticsDataQuality
import tachiyomi.domain.immersion.model.AnalyticsHourActivity
import tachiyomi.domain.immersion.model.AnalyticsInventoryMetrics
import tachiyomi.domain.immersion.model.AnalyticsPage
import tachiyomi.domain.immersion.model.AnalyticsSessionDetail
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.AnalyticsSourceOccurrence
import tachiyomi.domain.immersion.model.AnalyticsTemporalActivity
import tachiyomi.domain.immersion.model.AnalyticsTimelineBucket
import tachiyomi.domain.immersion.model.AnalyticsTitleMetadata
import tachiyomi.domain.immersion.model.AnalyticsTitleSeriesSelection
import tachiyomi.domain.immersion.model.AnalyticsTitleTrendDailyPoint
import tachiyomi.domain.immersion.model.AnalyticsVocabularyFirstSeenDay
import tachiyomi.domain.immersion.model.AnalyticsWeekdayActivity
import tachiyomi.domain.immersion.model.AnalyticsWordRow
import tachiyomi.domain.immersion.model.AnkiInventoryFailure
import tachiyomi.domain.immersion.model.AnkiMatchConfidence
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
import tachiyomi.domain.immersion.model.LookupEvent
import tachiyomi.domain.immersion.model.LookupStatus
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

    private val portableMergeMutex = Mutex()

    init {
        require(portableMergeLifetimePageSize > 0)
    }

    override suspend fun isTitleCaptureExcluded(titleId: TitleId): Boolean =
        handler.await {
            immersionQueries.isImmersionIndexEntityExcluded(
                entityType = IMMERSION_TITLE_EXCLUSION_TYPE,
                entityId = titleId.value,
                scopeKeys = listOf(IMMERSION_CAPTURE_EXCLUSION_SCOPE),
            ).executeAsOne() > 0
        }

    override suspend fun upsertTitle(title: ImmersionTitle): PersistenceResult =
        handler.await(inTransaction = true) {
            upsertTitleInDatabase(title)
        }

    override suspend fun createSession(session: ImmersionSessionStart): PersistenceResult =
        handler.await(inTransaction = true) {
            val existing = immersionQueries.selectImmersionSessionById(session.id.value).executeAsOneOrNull()
            if (existing != null) {
                ensureSessionIdentity(existing, session)
                return@await PersistenceResult.AlreadyApplied
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
            markRollupDirty(
                session.startedAtEpochMillis,
                session.startOffsetSeconds,
                session.titleId.value,
                "SESSION",
            )
            immersionQueries.incrementImmersionRevision(session.startedAtEpochMillis)
            PersistenceResult.Applied
        }

    override suspend fun upsertSourceUnit(source: ImmersionSourceUnit): PersistenceResult =
        handler.await(inTransaction = true) {
            upsertSourceInDatabase(source)
        }

    override suspend fun appendExposure(event: ExposureEvent): PersistenceResult =
        handler.await(inTransaction = true) {
            appendExposureInDatabase(event)
        }

    override suspend fun appendExposureBatch(events: List<ExposureEvent>): List<PersistenceResult> {
        if (events.isEmpty()) return emptyList()
        return handler.await(inTransaction = true) {
            events.map { appendExposureInDatabase(it) }
        }
    }

    override suspend fun appendEventBatch(events: List<RecordedImmersionEvent>): List<PersistenceResult> {
        if (events.isEmpty()) return emptyList()
        return handler.await(inTransaction = true) {
            events.map { event ->
                when (event) {
                    is ExposureEvent -> appendExposureInDatabase(event)
                    is SessionEvent -> appendSessionEventInDatabase(event)
                    is LookupEvent -> appendLookupInDatabase(event)
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
        tokenizerVersion: Int,
        normalizationVersion: Int,
        indexedVersion: Int,
        indexedAtEpochMillis: Long,
        tokenizationConfidence: Double?,
        terminalReason: IndexTerminalReason?,
        words: List<IndexedWord>,
        characters: List<IndexedCharacter>,
    ) {
        require(claimGeneration > 0) { "Index claim generation must be positive" }
        require(tokenizerId.isNotBlank()) { "Tokenizer ID cannot be blank" }
        require(tokenizerVersion > 0) { "Tokenizer version must be positive" }
        require(normalizationVersion > 0) { "Normalization version must be positive" }
        require(indexedVersion > 0) { "Indexed version must be positive" }
        require(indexedAtEpochMillis >= 0) { "Indexed timestamp cannot be negative" }
        require(tokenizationConfidence == null || tokenizationConfidence in 0.0..1.0) {
            "Tokenization confidence must be between zero and one"
        }
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
            source.raw_text?.decodeUtf8Strict()?.let { rawText ->
                immersionQueries.deleteImmersionSourceSearchDocument(sourceUnitId.value)
                immersionQueries.insertImmersionSourceSearchDocument(
                    sourceUnitId = sourceUnitId.value,
                    normalizedText = rawText,
                    searchTokens = rawText.searchTokenDocument(),
                )
            }
            val affectedWordIds = linkedSetOf<String>().apply {
                addAll(
                    immersionQueries
                        .selectImmersionWordIdsForSource(sourceUnitId.value)
                        .executeAsList(),
                )
                addAll(words.map(IndexedWord::id))
            }
            val affectedCharacterCodePoints = linkedSetOf<Long>().apply {
                addAll(
                    immersionQueries
                        .selectImmersionCharacterCodePointsForSource(sourceUnitId.value)
                        .executeAsList(),
                )
                addAll(characters.map { it.codePoint.value.toLong() })
            }
            immersionQueries.deleteImmersionWordOccurrencesForSource(sourceUnitId.value)
            immersionQueries.deleteImmersionCharacterOccurrencesForSource(sourceUnitId.value)
            words.forEach { word ->
                immersionQueries.upsertImmersionWord(
                    id = word.id,
                    languageTag = word.languageTag.value,
                    normalizedHeadword = word.normalizedHeadword,
                    normalizedReading = word.normalizedReading,
                    displayHeadword = word.displayHeadword,
                    displayReading = word.displayReading,
                    partOfSpeech = word.partOfSpeech,
                    tokenizationConfidence = word.tokenizationConfidence,
                    frequencyCorpus = word.frequencyCorpus,
                    frequencyRank = word.frequencyRank,
                    jlptLevel = word.jlptLevel?.toLong(),
                    gradeLevel = word.gradeLevel?.toLong(),
                    firstSeenAt = source.first_exposed_at,
                    lastSeenAt = source.last_exposed_at,
                )
                immersionQueries.insertImmersionWordOccurrence(
                    wordId = word.id,
                    sourceUnitId = sourceUnitId.value,
                    tokenOrdinal = word.tokenOrdinal,
                    surfaceText = word.surfaceText,
                    sourceStart = word.sourceStart,
                    sourceEnd = word.sourceEnd,
                    deinflectionRule = word.deinflectionRule,
                    tokenizerVersion = tokenizerVersion.toLong(),
                )
            }
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
            affectedWordIds.chunked(IMMERSION_INDEX_ID_CHUNK_SIZE).forEach { wordIds ->
                immersionQueries.recomputeImmersionWordSeenTimesByIds(wordIds)
                immersionQueries.deleteOrphanImmersionWordsByIds(wordIds)
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
                tokenizerVersion = tokenizerVersion.toLong(),
                normalizationVersion = normalizationVersion.toLong(),
                tokenizationConfidence = tokenizationConfidence,
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
                            wordIds = affectedWordIds,
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
                    words = NonNegativeCounter(row.words),
                    lookups = NonNegativeCounter(row.lookups),
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
                        languageTag = row.language_tag?.let(::LanguageTag),
                        totalUnits = row.total_units,
                        totalCharacterEstimate = row.total_character_estimate,
                        completed = when (row.completed) {
                            1L -> true
                            0L -> false
                            else -> null
                        },
                    )
                }
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
                        wordsEncountered = NonNegativeCounter(row.words ?: 0),
                        sourceUnits = NonNegativeCounter(row.source_units ?: 0),
                        sessions = NonNegativeCounter(row.sessions ?: 0),
                        successfulLookups = NonNegativeCounter(row.lookups ?: 0),
                        cardsCreated = NonNegativeCounter(row.cards_created ?: 0),
                        cardsUpdated = NonNegativeCounter(row.cards_updated ?: 0),
                    ),
                )
            }
        }
    }

    override suspend fun vocabularyFirstSeenByDate(
        filter: StatsFilter,
    ): List<AnalyticsVocabularyFirstSeenDay> =
        handler.await {
            val args = filter.sqlArgs()
            val range = filter.dateRange
            immersionQueries.selectImmersionAnalyticsVocabularyFirstSeen(
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
            ).executeAsList().map { row ->
                AnalyticsVocabularyFirstSeenDay(
                    date = ImmersionLocalDate(row.local_date),
                    newWords = row.new_words,
                )
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

    override suspend fun vocabularyPage(
        filter: StatsFilter,
        sort: AnalyticsSort,
        offset: Long,
        limit: Int,
        searchQuery: String?,
    ): AnalyticsPage<AnalyticsWordRow> {
        require(offset >= 0)
        require(limit in 1..MAX_PAGE_SIZE)
        return handler.await {
            val args = filter.sqlArgs()
            val range = filter.dateRange
            val rows = immersionQueries.selectImmersionAnalyticsWords(
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
                sort = sort.analyticsSqlSort(),
                limit = limit.toLong() + 1,
                offset = offset,
            ).executeAsList()
            val hasNext = rows.size > limit
            AnalyticsPage(
                items = rows.take(limit).map { row ->
                    AnalyticsWordRow(
                        id = row.id,
                        languageTag = LanguageTag(row.language_tag),
                        headword = row.display_headword,
                        reading = row.display_reading,
                        partOfSpeech = row.part_of_speech,
                        occurrenceCount = row.occurrence_count,
                        titleCount = row.title_count,
                        firstSeenAtEpochMillis = row.first_seen_at,
                        lastSeenAtEpochMillis = row.last_seen_at,
                        frequencyRank = row.frequency_rank,
                        maturity = MaturityTier.valueOf(row.maturity_tier),
                        matchConfidence = row.match_confidence
                            .takeIf(String::isNotBlank)
                            ?.let(AnkiMatchConfidence::valueOf),
                    )
                },
                nextOffset = if (hasNext) Math.addExact(offset, limit.toLong()) else null,
            )
        }
    }

    override suspend fun characterPage(
        filter: StatsFilter,
        sort: AnalyticsSort,
        offset: Long,
        limit: Int,
        searchQuery: String?,
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
                        unicodeScript = row.unicode_script,
                        occurrenceCount = row.occurrence_count ?: 0,
                        wordCount = row.word_count,
                        titleCount = row.title_count,
                        firstSeenAtEpochMillis = row.first_seen_at,
                        lastSeenAtEpochMillis = row.last_seen_at,
                        frequencyRank = row.frequency_rank,
                        maturity = MaturityTier.valueOf(row.maturity_tier),
                    )
                },
                nextOffset = if (hasNext) Math.addExact(offset, limit.toLong()) else null,
            )
        }
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
                    lookupCount = Math.addExact(lookupCount, event.lookup_delta)
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

    override suspend fun sourceSearch(
        filter: StatsFilter,
        query: String,
        offset: Long,
        limit: Int,
    ): AnalyticsPage<AnalyticsSourceOccurrence> {
        require(offset >= 0)
        require(limit in 1..MAX_PAGE_SIZE)
        val ftsQuery = query.toFtsQuery() ?: return AnalyticsPage(emptyList(), null)
        return handler.await {
            val args = filter.sqlArgs()
            val range = filter.dateRange
            val rows = immersionQueries.selectImmersionAnalyticsSourceSearch(
                ftsQuery = ftsQuery,
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
                        excerpt = row.excerpt,
                        rawTextAvailable = row.raw_text_available == 1L,
                    )
                },
                nextOffset = if (hasNext) Math.addExact(offset, limit.toLong()) else null,
            )
        }
    }

    override suspend fun wordOccurrences(
        filter: StatsFilter,
        wordId: String,
        offset: Long,
        limit: Int,
    ): AnalyticsPage<AnalyticsSourceOccurrence> {
        require(wordId.isNotBlank())
        require(offset >= 0)
        require(limit in 1..MAX_PAGE_SIZE)
        return handler.await {
            val args = filter.sqlArgs()
            val range = filter.dateRange
            val rows = immersionQueries.selectImmersionAnalyticsWordOccurrences(
                wordId = wordId,
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
                        excerpt = row.raw_text?.decodeUtf8Strict()?.take(SOURCE_EXCERPT_LENGTH)
                            ?: row.surface_text,
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

    override suspend fun characterContainingWords(
        filter: StatsFilter,
        codePoint: UnicodeCodePoint,
        sort: AnalyticsSort,
        offset: Long,
        limit: Int,
    ): AnalyticsPage<AnalyticsWordRow> {
        require(offset >= 0)
        require(limit in 1..MAX_PAGE_SIZE)
        return handler.await {
            val args = filter.sqlArgs()
            val range = filter.dateRange
            val rows = immersionQueries.selectImmersionAnalyticsCharacterContainingWords(
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
                filterProvenance = args.filterProvenance,
                provenanceStates = args.provenanceStates,
                filterMaturity = args.filterMaturity,
                maturityAggregation = args.maturityAggregation,
                maturityTiers = args.maturityTiers,
                sort = sort.analyticsSqlSort(),
                limit = limit.toLong() + 1,
                offset = offset,
            ).executeAsList()
            val hasNext = rows.size > limit
            AnalyticsPage(
                items = rows.take(limit).map { row ->
                    AnalyticsWordRow(
                        id = row.id,
                        languageTag = LanguageTag(row.language_tag),
                        headword = row.display_headword,
                        reading = row.display_reading,
                        partOfSpeech = row.part_of_speech,
                        occurrenceCount = row.occurrence_count,
                        titleCount = row.title_count,
                        firstSeenAtEpochMillis = row.first_seen_at,
                        lastSeenAtEpochMillis = row.last_seen_at,
                        frequencyRank = row.frequency_rank,
                        maturity = MaturityTier.valueOf(row.maturity_tier),
                        matchConfidence = row.match_confidence
                            .takeIf(String::isNotBlank)
                            ?.let(AnkiMatchConfidence::valueOf),
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
            if (profileId == null || languageTag == null) {
                return@await AnalyticsAnkiSummary(
                    snapshot = null,
                    wordCoverageEncountered = 0,
                    wordCoverageKnown = 0,
                    characterCoverageEncountered = 0,
                    characterCoverageKnown = 0,
                    reviewHistoryAvailable = false,
                    linkedOperationCount = activity.linked_operations,
                    unattributedOperationCount = activity.unattributed_operations,
                    meanReadingToCardLagMillis = activity.mean_lag_millis?.toLong(),
                )
            }
            val snapshot = immersionQueries.selectCurrentImmersionAnkiSnapshot(profileId)
                .executeAsOneOrNull()
                ?.toDomain()
            if (snapshot?.hasUsableInventory != true) {
                return@await AnalyticsAnkiSummary(
                    snapshot = snapshot,
                    wordCoverageEncountered = 0,
                    wordCoverageKnown = 0,
                    characterCoverageEncountered = 0,
                    characterCoverageKnown = 0,
                    reviewHistoryAvailable = false,
                    linkedOperationCount = activity.linked_operations,
                    unattributedOperationCount = activity.unattributed_operations,
                    meanReadingToCardLagMillis = activity.mean_lag_millis?.toLong(),
                )
            }
            val word = immersionQueries.selectImmersionAnalyticsAnkiWordCoverage(
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
            val maturity = immersionQueries.selectImmersionAnalyticsAnkiMaturityDistribution(
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
                profileId = profileId,
                maturityAggregation = args.maturityAggregation,
                filterMaturity = args.filterMaturity,
                maturityTiers = args.maturityTiers,
            ).executeAsList().associate {
                MaturityTier.valueOf(it.maturity_tier) to it.item_count
            }
            AnalyticsAnkiSummary(
                snapshot = snapshot,
                wordCoverageEncountered = word.encountered_count,
                wordCoverageKnown = word.headword_count,
                characterCoverageEncountered = character.encountered_count,
                characterCoverageKnown = character.covered_count,
                reviewHistoryAvailable = snapshot.supportsReviewHistory,
                maturityDistribution = maturity,
                linkedOperationCount = activity.linked_operations,
                unattributedOperationCount = activity.unattributed_operations,
                meanReadingToCardLagMillis = activity.mean_lag_millis?.toLong(),
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
        return handler.await(inTransaction = true) {
            rebuildRollupsInDatabase(range, rollupVersion, nowEpochMillis)
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
                lookupDelta = 0,
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
            .selectImmersionIntegrityReport(expectedRollupVersion.toLong())
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
                words = driver.singleLong("SELECT count(*) FROM immersion_word"),
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
            snapshot.wordIds
                .chunked(IMMERSION_INDEX_ID_CHUNK_SIZE)
                .forEach { wordIds ->
                    addAll(
                        immersionQueries
                            .selectImmersionExposureDatesForWords(wordIds)
                            .executeAsList()
                            .map(::ImmersionLocalDate),
                    )
                }
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
        val wordIds = selectImmersionWordIdsForSources(sourceIds)
        val characterCodePoints = selectImmersionCharacterCodePointsForSources(sourceIds)
        return ImmersionDeletionPreview(
            sessions = sessions.size.toLong(),
            activeDurationMillis = sessions.sumOf { it.activeDuration.value },
            grossCharacters = sessions.sumOf { it.grossCharacters.value },
            sourceUnits = sourceIds.size.toLong(),
            words = wordIds.size.toLong(),
            characters = characterCodePoints.size.toLong(),
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
        handler.await(inTransaction = true) {
            beginRollupRebuildInDatabase(
                rollupVersion = rollupVersion,
                repairCursor = repairCursor,
                updatedAtEpochMillis = updatedAtEpochMillis,
            )
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
        return portableMergeMutex.withLock {
            require(mergedAtEpochMillis >= 0)
            require(archive.formatVersion <= IMMERSION_PORTABLE_FORMAT_VERSION) {
                "Immersion backup format ${archive.formatVersion} is newer than supported " +
                    "$IMMERSION_PORTABLE_FORMAT_VERSION"
            }
            require(archive.sourceSchemaVersion <= ImmersionStatsVersions.SCHEMA) {
                "Immersion schema ${archive.sourceSchemaVersion} is newer than supported " +
                    "${ImmersionStatsVersions.SCHEMA}"
            }
            require(archive.tables.all { it.name in IMMERSION_PORTABLE_TABLES }) {
                "Immersion backup contains an unknown table"
            }

            val effectiveArchive = archive
                .withoutUnlinkedAnkiOperations()
                .canonicalizeLookupSuccessMetrics()
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
                            immersionQueries.recomputeImmersionWordSeenTimes()
                            immersionQueries.recomputeImmersionCharacterSeenTimes()
                            immersionQueries.deleteOrphanImmersionWords()
                            immersionQueries.deleteOrphanImmersionCharacters()
                            rawHandler.awaitRawDriver {
                                it.repairLookupSuccessMetrics(mergedAtEpochMillis)
                            }
                            latest.copy(
                                stage = PortableMergeStage.SEARCH,
                                updatedAt = mergedAtEpochMillis,
                                lastErrorCode = null,
                            ).also { updated ->
                                rawHandler.awaitRawDriver {
                                    it.updatePortableMergeCheckpoint(updated)
                                }
                            }
                        }
                    }

                    PortableMergeStage.SEARCH -> {
                        checkpoint = rawHandler.awaitRawDriver(inTransaction = true) { driver ->
                            val latest = checkNotNull(
                                driver.loadPortableMergeCheckpoint(archiveDigest),
                            )
                            if (latest.stage != PortableMergeStage.SEARCH) {
                                return@awaitRawDriver latest
                            }
                            driver.rebuildImmersionSourceSearchIndex()
                            latest.copy(
                                stage = PortableMergeStage.ROLLUP_VALIDATE,
                                updatedAt = mergedAtEpochMillis,
                                lastErrorCode = null,
                            ).also(driver::updatePortableMergeCheckpoint)
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
                checkpoint.decodePortableRollupFirstPass()
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
        return completePortableRollupPass(
            rawHandler = rawHandler,
            checkpoint = checkpoint,
            progress = progress,
            mergedAtEpochMillis = mergedAtEpochMillis,
        )
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
                it.immersionDailyRollupFingerprint(
                    range = chunk,
                    seedDigest = progress.digest,
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
            val lookupClauses = buildList {
                if (titleId != null) {
                    add(
                        """
                        EXISTS (
                            SELECT 1
                            FROM immersion_session AS session
                            WHERE session.id = immersion_lookup.session_id
                                AND session.title_id = ?
                        )
                        """.trimIndent(),
                    )
                }
                if (beforeEpochMillis != null) add("occurred_at < ?")
                add("raw_query IS NOT NULL")
            }
            parameter = 0
            val lookupChanged = driver.execute(
                identifier = null,
                sql = "UPDATE immersion_lookup SET raw_query = NULL " +
                    "WHERE ${lookupClauses.joinToString(" AND ")}",
                parameters = lookupClauses.size - 1,
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
            driver.notifyListeners("immersion_source_unit", "immersion_source_fts", "immersion_lookup")
            sourceChanged + lookupChanged
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
            val lookupClauses = buildList {
                if (titleId != null) {
                    add(
                        """
                        EXISTS (
                            SELECT 1
                            FROM immersion_session AS session
                            WHERE session.id = immersion_lookup.session_id
                                AND session.title_id = ?
                        )
                        """.trimIndent(),
                    )
                }
                if (beforeEpochMillis != null) add("occurred_at < ?")
                add("raw_query IS NOT NULL")
            }
            parameter = 0
            val lookupCount = driver.executeQuery(
                identifier = null,
                sql = "SELECT count(*) FROM immersion_lookup " +
                    "WHERE ${lookupClauses.joinToString(" AND ")}",
                mapper = { cursor ->
                    check(cursor.next().value)
                    QueryResult.Value(checkNotNull(cursor.getLong(0)))
                },
                parameters = lookupClauses.size - 1,
            ) {
                titleId?.let { value -> bindString(parameter++, value.value) }
                beforeEpochMillis?.let { value -> bindLong(parameter++, value) }
            }.value
            sourceCount + lookupCount
        }
    }

    override suspend fun setTitleCaptureExcluded(
        titleId: TitleId,
        excluded: Boolean,
        updatedAtEpochMillis: Long,
    ) {
        require(updatedAtEpochMillis >= 0)
        handler.await(inTransaction = true) {
            if (excluded) {
                immersionQueries.upsertImmersionExclusion(
                    id = "capture-title:${titleId.value}",
                    entityType = IMMERSION_TITLE_EXCLUSION_TYPE,
                    entityId = titleId.value,
                    scopeKey = IMMERSION_CAPTURE_EXCLUSION_SCOPE,
                    reason = "USER_CAPTURE_EXCLUSION",
                    createdAt = updatedAtEpochMillis,
                )
            } else {
                immersionQueries.deleteImmersionExclusion(
                    entityType = IMMERSION_TITLE_EXCLUSION_TYPE,
                    entityId = titleId.value,
                    scopeKey = IMMERSION_CAPTURE_EXCLUSION_SCOPE,
                )
            }
            immersionQueries.incrementImmersionRevision(updatedAtEpochMillis)
        }
    }

    override suspend fun resolveMergeConflictsKeepingLocal(): Long =
        handler.await(inTransaction = true) {
            immersionQueries.resolveImmersionMergeConflictsKeepingLocal()
            immersionQueries.selectImmersionChanges().executeAsOne()
        }

    override suspend fun upsertGoal(goal: ImmersionGoal) {
        handler.await(inTransaction = true) {
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
            immersionQueries.incrementImmersionRevision(goal.updatedAtEpochMillis)
        }
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
                    normalizedWord = item.normalizedWord,
                    normalizedReading = item.normalizedReading,
                    cardType = item.cardType?.toLong(),
                    queue = item.queue?.toLong(),
                    intervalDays = item.intervalDays?.toLong(),
                    due = item.due,
                    repetitions = item.repetitions?.toLong(),
                    lapses = item.lapses?.toLong(),
                    ease = item.ease?.toLong(),
                    noteModifiedAt = item.noteModifiedAtEpochSeconds,
                    matchConfidence = item.matchConfidence.name,
                    ambiguityCount = item.ambiguityCount.toLong(),
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

    override suspend fun findWordItems(
        profileId: String,
        languageTag: LanguageTag,
        normalizedWord: String,
        normalizedReading: String,
    ): List<ImmersionAnkiItem> =
        handler.await {
            immersionQueries.selectImmersionAnkiWordItems(
                profileId = profileId,
                languageTag = languageTag.value,
                normalizedWord = normalizedWord,
                normalizedReading = normalizedReading,
            ).executeAsList().map { item ->
                item.toDomain(
                    immersionQueries.selectImmersionAnkiCharactersForCard(
                        snapshotId = item.snapshot_id,
                        cardId = item.card_id,
                    ).executeAsList()
                        .map { UnicodeCodePoint(it.toIntExact("Anki character")) }
                        .toSet(),
                )
            }
        }

    override suspend fun findCharacterItems(
        profileId: String,
        codePoint: UnicodeCodePoint,
    ): List<ImmersionAnkiItem> =
        handler.await {
            immersionQueries.selectImmersionAnkiCharacterItems(
                profileId = profileId,
                codePoint = codePoint.value.toLong(),
            ).executeAsList().map { item ->
                item.toDomain(
                    immersionQueries.selectImmersionAnkiCharactersForCard(
                        snapshotId = item.snapshot_id,
                        cardId = item.card_id,
                    ).executeAsList()
                        .map { UnicodeCodePoint(it.toIntExact("Anki character")) }
                        .toSet(),
                )
            }
        }

    override suspend fun getWordCoverage(
        profileId: String,
        languageTag: LanguageTag,
    ): AnkiCoverage =
        handler.await {
            immersionQueries.selectImmersionAnkiWordCoverage(
                profileId = profileId,
                languageTag = languageTag.value,
            ).executeAsOne().let {
                AnkiCoverage(
                    encountered = it.encountered_count,
                    coveredReadingAware = it.reading_aware_count,
                    coveredHeadwordOrCharacter = it.headword_count,
                )
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
                    coveredReadingAware = it.covered_count,
                    coveredHeadwordOrCharacter = it.covered_count,
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
            .selectImmersionRollupRebuildBounds()
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
            tokenizerVersion = ImmersionStatsVersions.TOKENIZER.toLong(),
            rollupVersion = rollupVersion.toLong(),
            updatedAt = updatedAtEpochMillis,
        )
        immersionQueries.clearImmersionRollups()
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
        val fromEpochMillis = (
            Math.multiplyExact(range.start.epochDay, MILLIS_PER_DAY) -
                MAX_ROLLUP_EVENT_DURATION_MILLIS -
                MAX_ZONE_OFFSET_MILLIS
            ).coerceAtLeast(0)
        val untilEpochMillis = Math.addExact(
            Math.multiplyExact(Math.addExact(range.endInclusive.epochDay, 1), MILLIS_PER_DAY),
            MAX_ZONE_OFFSET_MILLIS,
        )
        val aggregates = linkedMapOf<RollupKey, MutableRollup>()

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
                    words = Math.addExact(words, session.word_count)
                    sourceUnitFallback = Math.addExact(sourceUnitFallback, session.source_unit_count)
                    successfulLookups = Math.addExact(successfulLookups, session.lookup_count)
                    cardsCreated = Math.addExact(cardsCreated, session.legacy_cards_total)
                    cardsUpdated = Math.addExact(cardsUpdated, session.cards_updated)
                }
            }
        }

        val events = immersionQueries
            .selectImmersionRollupEvents(fromEpochMillis, untilEpochMillis)
            .executeAsList()
        events.forEach { event ->
            val eventDate = calendar.localDate(
                event.occurred_at,
                event.timezone_offset_seconds.toIntExact("event offset"),
            )
            accumulator(
                eventDate,
                event.title_id,
                event.media_kind,
                event.profile_id,
                event.language_tag,
                event.legacy_import == 1L,
                event.replay_ordinal > 0,
            )?.apply {
                grossCharacters = Math.addExact(grossCharacters, event.gross_character_delta)
                uniqueSourceCharacters = Math.addExact(
                    uniqueSourceCharacters,
                    event.unique_source_character_delta,
                )
                netCharacters = Math.addExact(netCharacters, event.net_character_delta)
                successfulLookups = Math.addExact(
                    successfulLookups,
                    event.successful_lookup_delta,
                )
                cardsCreated = Math.addExact(cardsCreated, event.cards_created_delta)
                cardsUpdated = Math.addExact(cardsUpdated, event.cards_updated_delta)
                lastAppliedEventAt = maxOf(lastAppliedEventAt ?: 0, event.occurred_at)
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

        val wordFacts = immersionQueries
            .selectImmersionRollupWordFacts(fromEpochMillis, untilEpochMillis)
            .executeAsList()
        wordFacts.forEach { fact ->
            val date = calendar.localDate(
                fact.occurred_at,
                fact.timezone_offset_seconds.toIntExact("word event offset"),
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
                words = Math.addExact(words, fact.occurrence_count)
                uniqueWordIds += fact.word_id
                val firstSeenDate = calendar.localDate(
                    fact.first_seen_at,
                    fact.timezone_offset_seconds.toIntExact("word event offset"),
                )
                if (firstSeenDate == date) newWordIds += fact.word_id
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
                words = value.words,
                uniqueWords = value.uniqueWordIds.size.toLong(),
                newWords = value.newWordIds.size.toLong(),
                distinctCharacters = value.distinctCharacterIds.size.toLong(),
                newCharacters = value.newCharacterIds.size.toLong(),
                sessions = value.sessionsCount,
                lookups = value.successfulLookups,
                cardsCreated = value.cardsCreated,
                cardsUpdated = value.cardsUpdated,
                provenanceState = key.provenance.name,
                replayState = if (key.replay) "REPLAY" else "PRIMARY",
                rollupVersion = rollupVersion.toLong(),
                lastAppliedEventAt = value.lastAppliedEventAt,
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
            wordIds = selectImmersionWordIdsForSources(boundedSourceIds),
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

        val affectedWordIds = buildSet {
            addAll(snapshot.wordIds)
            addAll(selectImmersionWordIdsForSources(snapshot.boundaries.keys))
        }
        affectedWordIds.chunked(IMMERSION_INDEX_ID_CHUNK_SIZE).forEach { wordIds ->
            immersionQueries.recomputeImmersionWordSeenTimesByIds(wordIds)
            immersionQueries.deleteOrphanImmersionWordsByIds(wordIds)
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
        val affectedWordIds = selectImmersionWordIdsForSources(existingSourceIds)
        affectedWordIds.chunked(IMMERSION_INDEX_ID_CHUNK_SIZE).forEach { wordIds ->
            immersionQueries.recomputeImmersionWordSeenTimesByIds(wordIds)
        }
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

    private fun Database.selectImmersionWordIdsForSources(
        sourceUnitIds: Collection<String>,
    ): Set<String> =
        sourceUnitIds
            .distinct()
            .chunked(IMMERSION_INDEX_ID_CHUNK_SIZE)
            .flatMapTo(linkedSetOf()) { ids ->
                immersionQueries.selectImmersionWordIdsForSources(ids).executeAsList()
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
            createdAt = title.createdAtEpochMillis,
            updatedAt = title.updatedAtEpochMillis,
        )
        val unchanged = existing?.let {
            it.language_tag == title.languageTag?.value &&
                it.display_title == title.displayTitle &&
                it.library_id == title.libraryId &&
                it.tracker_id == title.trackerId &&
                it.media_id == title.mediaId
        } == true
        if (!unchanged) immersionQueries.incrementImmersionRevision(title.updatedAtEpochMillis)
        return if (unchanged) PersistenceResult.AlreadyApplied else PersistenceResult.Applied
    }

    private fun Database.upsertSourceInDatabase(source: ImmersionSourceUnit): PersistenceResult {
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
            tokenizerVersion = source.tokenizerVersion.toLong(),
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
        source.rawText?.let { rawText ->
            immersionQueries.deleteImmersionSourceSearchDocument(source.id.value)
            immersionQueries.insertImmersionSourceSearchDocument(
                sourceUnitId = source.id.value,
                normalizedText = rawText,
                searchTokens = rawText.searchTokenDocument(),
            )
        }
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
        if (session.title_id != event.source.titleId.value) {
            throw identityConflict("Source title does not match session title")
        }
        upsertSourceInDatabase(event.source)
        val sourceUnitDelta = if (
            immersionQueries.countImmersionSessionSourceExposure(
                event.sessionId.value,
                event.source.id.value,
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
            sourceUnitId = event.source.id.value,
            activeDurationDeltaMs = event.activeDuration.value,
            grossCharacterDelta = event.grossCharacters.value,
            uniqueSourceCharacterDelta = event.uniqueSourceCharacters.value,
            netCharacterDelta = event.netCharacters.value,
            payloadHash = payloadHash,
            localDate = event.localDateEpochDay(),
        )
        immersionQueries.insertImmersionSourceExposure(
            eventId = event.id.value,
            sessionId = event.sessionId.value,
            sourceUnitId = event.source.id.value,
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
        immersionQueries.touchImmersionSourceUnit(event.occurredAtEpochMillis, event.source.id.value)
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

    private fun Database.appendLookupInDatabase(event: LookupEvent): PersistenceResult {
        val payloadHash = event.payloadHash()
        existingInteractionResult(event.id.value, event.sessionId.value, event.sequence, payloadHash)?.let {
            return it
        }
        val session = immersionQueries.selectImmersionSessionById(event.sessionId.value).executeAsOneOrNull()
            ?: throw identityConflict("Session ${event.sessionId.value} does not exist")
        val successfulLookupDelta = (event.status == LookupStatus.SUCCESS).toLong()
        immersionQueries.insertImmersionInteractionEvent(
            id = event.id.value,
            sessionId = event.sessionId.value,
            sequence = event.sequence,
            occurredAt = event.occurredAtEpochMillis,
            timezoneOffsetSeconds = event.timezoneOffsetSeconds.toLong(),
            type = event.type.name,
            sourceUnitId = event.sourceUnitId?.value,
            ankiOperationId = null,
            lookupDelta = successfulLookupDelta,
            cardsCreatedDelta = 0,
            cardsUpdatedDelta = 0,
            payloadHash = payloadHash,
            localDate = event.localDateEpochDay(),
        )
        immersionQueries.insertImmersionLookup(
            id = event.lookupId,
            eventId = event.id.value,
            sessionId = event.sessionId.value,
            sourceUnitId = event.sourceUnitId?.value,
            rawQuery = event.rawQuery,
            queryHash = event.queryHash,
            normalizedHeadword = event.normalizedHeadword,
            normalizedReading = event.normalizedReading,
            partOfSpeech = event.partOfSpeech,
            dictionaryId = event.dictionaryId,
            resultId = event.resultId,
            status = event.status.name,
            occurredAt = event.occurredAtEpochMillis,
        )
        advanceInteraction(event, session.last_sequence, successfulLookupDelta, 0, 0)
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
            lookupDelta = 0,
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
        advanceInteraction(event, session.last_sequence, 0, cardsCreated, cardsUpdated)
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
        lookupDelta: Long,
        cardsCreatedDelta: Long,
        cardsUpdatedDelta: Long,
    ) {
        immersionQueries.advanceImmersionSessionForInteraction(
            lookupDelta = lookupDelta,
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

    private fun Database.checkExactlyOneChange(operation: String) {
        if (immersionQueries.selectImmersionChanges().executeAsOne() != 1L) {
            throw identityConflict("No row was found while $operation")
        }
    }
}

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

private class MutableRollup {
    var activeDuration: Long = 0
    var grossCharacters: Long = 0
    var uniqueSourceCharacters: Long = 0
    var netCharacters: Long = 0
    var sourceUnitFallback: Long = 0
    var words: Long = 0
    var sessionsCount: Long = 0
    var successfulLookups: Long = 0
    var cardsCreated: Long = 0
    var cardsUpdated: Long = 0
    var lastAppliedEventAt: Long? = null
    val sourceUnitIds = mutableSetOf<String>()
    val uniqueWordIds = mutableSetOf<String>()
    val newWordIds = mutableSetOf<String>()
    val distinctCharacterIds = mutableSetOf<Long>()
    val newCharacterIds = mutableSetOf<Long>()
}

private class MutableInventoryMetrics {
    private val wordIds = mutableSetOf<String>()
    private val newWordIds = mutableSetOf<String>()
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
            "WORD" -> {
                wordIds += entityKey
                if (globallyNew) newWordIds += entityKey
            }
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
            uniqueWords = wordIds.size.toLong(),
            newWords = newWordIds.size.toLong(),
            charactersRepresentedInAnki = representedCharacterIds.size.toLong(),
        )
}

private data class ImmersionSourceBoundarySnapshot(
    val boundaries: Map<String, ImmersionSourceExposureBounds> = emptyMap(),
    val wordIds: Set<String> = emptySet(),
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
    var lookupCount: Long = 0,
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
        lookupCount = lookupCount,
        cardsCreated = cardsCreated,
        cardsUpdated = cardsUpdated,
        eventTypes = eventTypes,
    )
}

private fun String.toFtsQuery(): String? {
    val terms = Normalizer.normalize(this, Normalizer.Form.NFC)
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .take(8)
    if (terms.isEmpty()) return null
    return terms.joinToString(" AND ") { term ->
        "\"${term.replace("\"", "\"\"")}\"*"
    }
}

internal const val INDEX_WORK_LEASE_MILLIS = 10 * 60 * 1_000L

private fun String.searchTokenDocument(): String =
    buildString {
        var offset = 0
        var first = true
        while (offset < this@searchTokenDocument.length) {
            val codePoint = this@searchTokenDocument.codePointAt(offset)
            if (!Character.isWhitespace(codePoint)) {
                if (!first) append(' ')
                appendCodePoint(codePoint)
                first = false
            }
            offset += Character.charCount(codePoint)
        }
    }

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
                wordsEncountered = NonNegativeCounter(words),
                uniqueWords = NonNegativeCounter(unique_words),
                newWords = NonNegativeCounter(new_words),
                sourceUnits = NonNegativeCounter(source_units),
                sessions = NonNegativeCounter(sessions),
                successfulLookups = NonNegativeCounter(lookups),
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
            tokenizerVersion = tokenizer_version.toIntExact("tokenizer version"),
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
            normalizedWord = requireNotNull(normalized_word),
            normalizedReading = normalized_reading.orEmpty(),
            characters = characters,
            cardType = card_type?.toIntExact("card type"),
            queue = queue?.toIntExact("card queue"),
            intervalDays = interval_days?.toIntExact("card interval"),
            due = due,
            repetitions = repetitions?.toIntExact("card repetitions"),
            lapses = lapses?.toIntExact("card lapses"),
            ease = ease?.toIntExact("card ease"),
            noteModifiedAtEpochSeconds = note_modified_at,
            matchConfidence = enumValueOf<AnkiMatchConfidence>(match_confidence),
            ambiguityCount = ambiguity_count.toIntExact("match ambiguity"),
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
    return MessageDigest.getInstance("SHA-256")
        .digest(output.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

private fun LookupEvent.payloadHash(): String {
    val output = ByteArrayOutputStream()
    output.writeField(id.value)
    output.writeField(sessionId.value)
    output.writeLong(sequence)
    output.writeLong(occurredAtEpochMillis)
    output.writeLong(timezoneOffsetSeconds.toLong())
    output.writeField(type.name)
    output.writeField(lookupId)
    output.writeNullableField(sourceUnitId?.value)
    output.writeField(queryHash)
    output.writeNullableField(rawQuery)
    output.writeNullableField(normalizedHeadword)
    output.writeNullableField(normalizedReading)
    output.writeNullableField(partOfSpeech)
    output.writeNullableField(dictionaryId)
    output.writeNullableField(resultId)
    output.writeField(status.name)
    return output.sha256()
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
    SEARCH,
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
            firstPass: PortableRollupFirstPassEvidence?,
        ): PortableRollupPassProgress {
            val output = ByteArrayOutputStream()
            output.writeField(PORTABLE_ROLLUP_FINGERPRINT_DOMAIN)
            output.writeLong(ImmersionStatsVersions.ROLLUP.toLong())
            return PortableRollupPassProgress(
                formatVersion = PORTABLE_ROLLUP_PROGRESS_FORMAT_VERSION,
                pass = pass,
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
                (SELECT count(*) FROM immersion_word),
                (SELECT count(*) FROM immersion_character),
                (SELECT count(*) FROM immersion_lookup),
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
                    words = checkNotNull(cursor.getLong(4)),
                    characters = checkNotNull(cursor.getLong(5)),
                    lookups = checkNotNull(cursor.getLong(6)),
                    ankiOperations = checkNotNull(cursor.getLong(7)),
                    goals = checkNotNull(cursor.getLong(8)),
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

private fun ImmersionPortableArchive.canonicalizeLookupSuccessMetrics(): ImmersionPortableArchive {
    val lookupTable = tables.firstOrNull { it.name == "immersion_lookup" } ?: return this
    val eventIdIndex = lookupTable.columnIndex("event_id")
    val sessionIdIndex = lookupTable.columnIndex("session_id")
    val statusIndex = lookupTable.columnIndex("status")
    val statusByEventId = lookupTable.rows.associate { row ->
        row.cells[eventIdIndex].portableIdentityValue() to
            row.cells[statusIndex].portableIdentityValue()
    }
    val successfulLookupsBySession = lookupTable.rows
        .groupingBy { row -> row.cells[sessionIdIndex].portableIdentityValue() }
        .fold(0L) { count, row ->
            count + (row.cells[statusIndex].portableIdentityValue() == LookupStatus.SUCCESS.name).toLong()
        }
    return copy(
        tables = tables.map { table ->
            when (table.name) {
                "immersion_event" -> {
                    val idIndex = table.columnIndex("id")
                    val typeIndex = table.columnIndex("type")
                    val lookupDeltaIndex = table.columnIndex("lookup_delta")
                    table.copy(
                        rows = table.rows.map { row ->
                            val eventId = row.cells[idIndex].portableIdentityValue()
                            val status = statusByEventId[eventId]
                            if (row.cells[typeIndex].portableIdentityValue() != EventType.LOOKUP.name) {
                                row
                            } else {
                                row.withInteger(
                                    lookupDeltaIndex,
                                    (status == LookupStatus.SUCCESS.name).toLong(),
                                )
                            }
                        },
                    )
                }
                "immersion_session" -> {
                    val idIndex = table.columnIndex("id")
                    val lookupCountIndex = table.columnIndex("lookup_count")
                    val legacyImportIndex = table.columnIndex("legacy_import")
                    table.copy(
                        rows = table.rows.map { row ->
                            if (row.cells[legacyImportIndex].integerValue != 0L) {
                                row
                            } else {
                                row.withInteger(
                                    lookupCountIndex,
                                    successfulLookupsBySession[
                                        row.cells[idIndex].portableIdentityValue(),
                                    ] ?: 0,
                                )
                            }
                        },
                    )
                }
                else -> table
            }
        },
    )
}

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
        long(first, "tokenizer_version"),
        long(second, "tokenizer_version"),
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

private fun SqlDriver.repairLookupSuccessMetrics(updatedAtEpochMillis: Long) {
    execute(
        identifier = null,
        sql = """
            UPDATE immersion_event
            SET lookup_delta = CASE
                WHEN EXISTS (
                    SELECT 1
                    FROM immersion_lookup
                    WHERE
                        immersion_lookup.event_id = immersion_event.id
                        AND immersion_lookup.status = 'SUCCESS'
                ) THEN 1
                ELSE 0
            END
            WHERE type = 'LOOKUP'
        """.trimIndent(),
        parameters = 0,
    ).value
    execute(
        identifier = null,
        sql = """
            UPDATE immersion_session
            SET lookup_count = (
                SELECT count(*)
                FROM immersion_lookup
                WHERE
                    immersion_lookup.session_id = immersion_session.id
                    AND immersion_lookup.status = 'SUCCESS'
            )
            WHERE legacy_import = 0
        """.trimIndent(),
        parameters = 0,
    ).value
    execute(
        identifier = null,
        sql = """
            INSERT INTO immersion_rollup_dirty(local_date, title_id, reason, updated_at)
            SELECT
                immersion_event.local_date,
                immersion_session.title_id,
                'LOOKUP_SUCCESS_REPAIR',
                ?
            FROM immersion_event
            JOIN immersion_lookup ON immersion_lookup.event_id = immersion_event.id
            JOIN immersion_session ON immersion_session.id = immersion_event.session_id
            GROUP BY immersion_event.local_date, immersion_session.title_id
            ON CONFLICT(local_date, title_id, reason) DO UPDATE SET
                updated_at = max(updated_at, excluded.updated_at)
        """.trimIndent(),
        parameters = 1,
    ) {
        bindLong(0, updatedAtEpochMillis)
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

private fun SqlDriver.rebuildImmersionSourceSearchIndex() {
    val sources = executeQuery(
        identifier = null,
        sql = "SELECT id, raw_text FROM immersion_source_unit WHERE raw_text IS NOT NULL",
        mapper = { cursor ->
            val result = mutableListOf<Pair<String, String>>()
            while (cursor.next().value) {
                result += checkNotNull(cursor.getString(0)) to
                    checkNotNull(cursor.getBytes(1)).decodeUtf8Strict()
            }
            QueryResult.Value(result)
        },
        parameters = 0,
    ).value
    execute(
        identifier = null,
        sql = "DELETE FROM immersion_source_fts",
        parameters = 0,
    ).value
    sources.forEach { (sourceUnitId, rawText) ->
        execute(
            identifier = null,
            sql = """
                INSERT INTO immersion_source_fts(source_unit_id, normalized_text, search_tokens)
                VALUES (?, ?, ?)
            """.trimIndent(),
            parameters = 3,
        ) {
            bindString(0, sourceUnitId)
            bindString(1, rawText)
            bindString(2, rawText.searchTokenDocument())
        }.value
    }
}

private fun SqlDriver.previewAllImmersionDeletion(): ImmersionDeletionPreview =
    executeQuery(
        identifier = null,
        sql = """
            SELECT
                (SELECT count(*) FROM immersion_session),
                (SELECT coalesce(sum(active_duration_ms), 0) FROM immersion_session),
                (SELECT coalesce(sum(gross_characters), 0) FROM immersion_session),
                (SELECT count(*) FROM immersion_source_unit),
                (SELECT count(*) FROM immersion_word),
                (SELECT count(*) FROM immersion_character)
        """.trimIndent(),
        mapper = { cursor ->
            check(cursor.next().value)
            QueryResult.Value(
                ImmersionDeletionPreview(
                    sessions = checkNotNull(cursor.getLong(0)),
                    activeDurationMillis = checkNotNull(cursor.getLong(1)),
                    grossCharacters = checkNotNull(cursor.getLong(2)),
                    sourceUnits = checkNotNull(cursor.getLong(3)),
                    words = checkNotNull(cursor.getLong(4)),
                    characters = checkNotNull(cursor.getLong(5)),
                ),
            )
        },
        parameters = 0,
    ).value

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
            "WORD" -> executeDeleteByIdentity("immersion_word", "id", entityId)
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

private const val MAX_PAGE_SIZE = 500
private const val SQLITE_BIND_BATCH_SIZE = 400
private const val MAX_TITLE_TREND_SERIES = 20
private const val SOURCE_EXCERPT_LENGTH = 240
private const val IMMERSION_PORTABLE_FORMAT_VERSION = 1
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
private const val PORTABLE_ROLLUP_PROGRESS_FORMAT_VERSION = 1
private const val PORTABLE_ROLLUP_FINGERPRINT_VERSION = 2
private const val PORTABLE_ROLLUP_CHUNK_DAYS = 31L
private const val PORTABLE_ROLLUP_LIFETIME_PAGE_SIZE = 256
private const val PORTABLE_ROLLUP_FINGERPRINT_DOMAIN =
    "chimahon:immersion:portable-rollup-semantic:v2"
private const val IMMERSION_TITLE_EXCLUSION_TYPE = "TITLE"
private const val IMMERSION_CAPTURE_EXCLUSION_SCOPE = "capture"
private const val MILLIS_PER_DAY = 86_400_000L
private const val MAX_ZONE_OFFSET_MILLIS = 18L * 60L * 60L * 1_000L
private const val MAX_ROLLUP_EVENT_DURATION_MILLIS = 7L * MILLIS_PER_DAY
private const val UTF8 = "UTF-8"
private const val MIN_TIMEZONE_OFFSET_SECONDS = -18 * 60 * 60
private const val MAX_TIMEZONE_OFFSET_SECONDS = 18 * 60 * 60
private const val ANKI_REPAIR_EVENT_NAMESPACE = "chimahon-immersion-anki-repair-event"

private val IMMERSION_PORTABLE_TABLES = listOf(
    "immersion_title",
    "immersion_session",
    "immersion_source_unit",
    "immersion_event",
    "immersion_source_exposure",
    "immersion_word",
    "immersion_character",
    "immersion_word_occurrence",
    "immersion_character_occurrence",
    "immersion_lookup",
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
    "immersion_lookup" to "raw_query",
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
    "immersion_word" to ("WORD" to "id"),
    "immersion_character" to ("CHARACTER" to "code_point"),
    "immersion_goal" to ("GOAL" to "id"),
)

private val IMMERSION_TOMBSTONE_REFERENCES = mapOf(
    "immersion_session" to listOf("TITLE" to "title_id"),
    "immersion_source_unit" to listOf("TITLE" to "title_id"),
    "immersion_event" to listOf(
        "SESSION" to "session_id",
        "SOURCE_UNIT" to "source_unit_id",
        "WORD" to "word_id",
    ),
    "immersion_source_exposure" to listOf(
        "EVENT" to "event_id",
        "SESSION" to "session_id",
        "SOURCE_UNIT" to "source_unit_id",
    ),
    "immersion_word_occurrence" to listOf(
        "WORD" to "word_id",
        "SOURCE_UNIT" to "source_unit_id",
    ),
    "immersion_character_occurrence" to listOf(
        "CHARACTER" to "character_code_point",
        "SOURCE_UNIT" to "source_unit_id",
    ),
    "immersion_lookup" to listOf(
        "EVENT" to "event_id",
        "SESSION" to "session_id",
        "SOURCE_UNIT" to "source_unit_id",
        "WORD" to "word_id",
    ),
    "immersion_anki_operation" to listOf(
        "EVENT" to "event_id",
        "SESSION" to "session_id",
        "SOURCE_UNIT" to "source_unit_id",
        "WORD" to "word_id",
    ),
    "immersion_goal" to listOf("TITLE" to "title_id"),
    "immersion_goal_check_in" to listOf("GOAL" to "goal_id"),
    "immersion_goal_achievement" to listOf("GOAL" to "goal_id"),
)

private val IMMERSION_RESET_DERIVED_TABLES = listOf(
    "immersion_anki_operation",
    "immersion_anki_snapshot",
    "immersion_daily_rollup",
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
