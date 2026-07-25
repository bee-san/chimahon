package tachiyomi.data.immersion

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
import tachiyomi.domain.immersion.model.AnalyticsAnkiSummary
import tachiyomi.domain.immersion.model.AnalyticsCharacterRow
import tachiyomi.domain.immersion.model.AnalyticsDataQuality
import tachiyomi.domain.immersion.model.AnalyticsPage
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.AnalyticsTitleMetadata
import tachiyomi.domain.immersion.model.AnalyticsWordRow
import tachiyomi.domain.immersion.model.AnkiInventoryFailure
import tachiyomi.domain.immersion.model.AnkiMatchConfidence
import tachiyomi.domain.immersion.model.AnkiOperationEvent
import tachiyomi.domain.immersion.model.AnkiOperationStatus
import tachiyomi.domain.immersion.model.AnkiOperationType
import tachiyomi.domain.immersion.model.AnkiSnapshotStatus
import tachiyomi.domain.immersion.model.CharacterCoverage
import tachiyomi.domain.immersion.model.CharacterVolume
import tachiyomi.domain.immersion.model.ExposureEvent
import tachiyomi.domain.immersion.model.ImmersionAnkiItem
import tachiyomi.domain.immersion.model.ImmersionAnkiSnapshot
import tachiyomi.domain.immersion.model.ImmersionDailyRollup
import tachiyomi.domain.immersion.model.ImmersionDataException
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionIntegrityReport
import tachiyomi.domain.immersion.model.ImmersionLocalDate
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
import tachiyomi.domain.immersion.model.LegacyAggregateRow
import tachiyomi.domain.immersion.model.LegacyImportBatch
import tachiyomi.domain.immersion.model.LegacyImportIdentity
import tachiyomi.domain.immersion.model.LegacyImportResult
import tachiyomi.domain.immersion.model.LegacyImportResultState
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
import tachiyomi.domain.immersion.service.PendingAnkiOperation
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class SqlDelightImmersionRepository(
    private val handler: DatabaseHandler,
) : ImmersionRecorderRepository,
    ImmersionIndexRepository,
    ImmersionStatsRepository,
    ImmersionAnalyticsRepository,
    ImmersionMaintenanceRepository,
    ImmersionGoalRepository,
    ImmersionAnkiRepository,
    ImmersionLegacyImportRepository {

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
    ): List<IndexWorkItem> {
        require(targetVersion > 0) { "Target version must be positive" }
        require(limit in 1..MAX_PAGE_SIZE) { "Index claim limit must be between 1 and $MAX_PAGE_SIZE" }
        require(nowEpochMillis >= 0) { "Index claim timestamp cannot be negative" }
        return handler.await(inTransaction = true) {
            val rows = immersionQueries
                .selectImmersionIndexWork(
                    targetVersion = targetVersion.toLong(),
                    nowEpochMillis = nowEpochMillis,
                    limit = limit.toLong(),
                )
                .executeAsList()
            if (rows.isNotEmpty()) {
                immersionQueries.markImmersionIndexWorkClaimed(rows.map { it.id })
            }
            rows.map(SelectImmersionIndexWork::toDomain)
        }
    }

    override suspend fun storeIndexResult(
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
    ) {
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
            immersionQueries.recomputeImmersionWordSeenTimes()
            immersionQueries.recomputeImmersionCharacterSeenTimes()
            immersionQueries.deleteOrphanImmersionWords()
            immersionQueries.deleteOrphanImmersionCharacters()
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
                id = sourceUnitId.value,
            )
            checkExactlyOneChange("marking source unit indexed")
            markRollupDirty(
                source.first_exposed_at,
                0,
                source.title_id,
                "INDEX",
            )
            immersionQueries.incrementImmersionRevision(indexedAtEpochMillis)
        }
    }

    override suspend fun markFailure(
        sourceUnitId: SourceUnitId,
        errorCode: String,
        nextAttemptAtEpochMillis: Long,
    ) {
        require(errorCode.isNotBlank()) { "Index error code cannot be blank" }
        require(nextAttemptAtEpochMillis >= 0) { "Next index attempt cannot be negative" }
        handler.await(inTransaction = true) {
            immersionQueries.markImmersionSourceIndexFailed(
                errorCode = errorCode,
                nextAttemptAt = nextAttemptAtEpochMillis,
                id = sourceUnitId.value,
            )
            checkExactlyOneChange("marking source unit index failure")
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

    override suspend fun pendingCount(targetVersion: Int): Long {
        require(targetVersion > 0) { "Target version must be positive" }
        return handler.await {
            immersionQueries.countPendingImmersionSourceUnits(targetVersion.toLong()).executeAsOne()
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

    override suspend fun characterPage(
        filter: StatsFilter,
        sort: AnalyticsSort,
        offset: Long,
        limit: Int,
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
                maturityTiers = args.maturityTiers,
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

    override suspend fun ankiSummary(filter: StatsFilter): AnalyticsAnkiSummary =
        handler.await {
            val profileId = filter.profileIds.singleOrNull()
            val languageTag = filter.languageTags.singleOrNull()
            if (profileId == null || languageTag == null) {
                return@await AnalyticsAnkiSummary(
                    snapshot = null,
                    wordCoverageEncountered = 0,
                    wordCoverageKnown = 0,
                    characterCoverageEncountered = 0,
                    characterCoverageKnown = 0,
                    reviewHistoryAvailable = false,
                )
            }
            val snapshot = immersionQueries.selectCurrentImmersionAnkiSnapshot(profileId)
                .executeAsOneOrNull()
                ?.toDomain()
            val word = immersionQueries.selectImmersionAnkiWordCoverage(
                profileId,
                languageTag.value,
            ).executeAsOne()
            val character = immersionQueries.selectImmersionAnkiCharacterCoverage(
                profileId,
                languageTag.value,
            ).executeAsOne()
            AnalyticsAnkiSummary(
                snapshot = snapshot,
                wordCoverageEncountered = word.encountered_count,
                wordCoverageKnown = word.headword_count,
                characterCoverageEncountered = character.encountered_count,
                characterCoverageKnown = character.covered_count,
                reviewHistoryAvailable = snapshot?.supportsReviewHistory == true,
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
        return handler.await {
            val row = immersionQueries
                .selectImmersionIntegrityReport(expectedRollupVersion.toLong())
                .executeAsOne()
            mapCorruption("immersion integrity report") {
                ImmersionIntegrityReport(
                    orphanedEvents = NonNegativeCounter(row.orphaned_events),
                    orphanedOccurrences = NonNegativeCounter(row.orphaned_occurrences),
                    duplicateSessionSequences = NonNegativeCounter(row.duplicate_session_sequences),
                    negativeCounters = NonNegativeCounter(row.negative_counters),
                    rollupVersionMismatches = NonNegativeCounter(row.rollup_version_mismatches),
                )
            }
        }
    }

    override suspend fun deleteSession(sessionId: SessionId): Boolean =
        handler.await(inTransaction = true) {
            val session = immersionQueries.selectImmersionSessionById(sessionId.value).executeAsOneOrNull()
            immersionQueries.deleteImmersionSession(sessionId.value)
            val deleted = immersionQueries.selectImmersionChanges().executeAsOne() == 1L
            if (deleted) {
                requireNotNull(session)
                markRollupDirty(
                    session.started_at,
                    session.start_offset_seconds.toIntExact("session offset"),
                    session.title_id,
                    "SESSION_DELETE",
                )
                session.ended_at?.let {
                    markRollupDirty(
                        it,
                        session.start_offset_seconds.toIntExact("session offset"),
                        session.title_id,
                        "SESSION_DELETE",
                    )
                }
                immersionQueries.incrementImmersionRevision(session.ended_at ?: session.started_at)
            }
            deleted
        }

    override suspend fun beginRollupRebuild(
        rollupVersion: Int,
        repairCursor: String?,
        updatedAtEpochMillis: Long,
    ) {
        require(rollupVersion > 0) { "Rollup version must be positive" }
        require(updatedAtEpochMillis >= 0) { "Repair timestamp cannot be negative" }
        handler.await(inTransaction = true) {
            val sessions = immersionQueries
                .selectImmersionRollupSessions(0, Long.MAX_VALUE)
                .executeAsList()
            immersionQueries.clearImmersionRollups()
            immersionQueries.clearImmersionLifetimeRollups()
            immersionQueries.clearImmersionAppliedEvents()
            sessions.forEach {
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
            immersionQueries.updateImmersionRepairState(
                rollupVersion = rollupVersion.toLong(),
                repairCursor = repairCursor,
                updatedAt = updatedAtEpochMillis,
            )
        }
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

    override suspend fun clearSnapshots(profileId: String) {
        handler.await(inTransaction = true) {
            immersionQueries.deleteImmersionAnkiSnapshots(profileId)
            immersionQueries.incrementImmersionRevision(System.currentTimeMillis())
        }
    }

    private fun Database.rebuildRollupsInDatabase(
        range: tachiyomi.domain.immersion.model.LocalDateRange,
        rollupVersion: Int,
        nowEpochMillis: Long,
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
        immersionQueries.clearImmersionLifetimeRollups()
        immersionQueries.rebuildImmersionLifetimeRollups(
            rollupVersion.toLong(),
            nowEpochMillis,
        )
        immersionQueries.deleteImmersionDirtyRollupRange(
            range.start.epochDay,
            range.endInclusive.epochDay,
        )
        immersionQueries.updateImmersionRepairState(
            rollupVersion = rollupVersion.toLong(),
            repairCursor = null,
            updatedAt = nowEpochMillis,
        )
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
        val lookupDelta = if (event.status == LookupStatus.CANCELLED) 0L else 1L
        immersionQueries.insertImmersionInteractionEvent(
            id = event.id.value,
            sessionId = event.sessionId.value,
            sequence = event.sequence,
            occurredAt = event.occurredAtEpochMillis,
            timezoneOffsetSeconds = event.timezoneOffsetSeconds.toLong(),
            type = event.type.name,
            sourceUnitId = event.sourceUnitId?.value,
            ankiOperationId = null,
            lookupDelta = lookupDelta,
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
        advanceInteraction(event, session.last_sequence, lookupDelta, 0, 0)
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
)

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

private const val MAX_PAGE_SIZE = 500
private const val MILLIS_PER_DAY = 86_400_000L
private const val MAX_ZONE_OFFSET_MILLIS = 18L * 60L * 60L * 1_000L
private const val MAX_ROLLUP_EVENT_DURATION_MILLIS = 7L * MILLIS_PER_DAY
private const val UTF8 = "UTF-8"
