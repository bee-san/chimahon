package tachiyomi.data.immersion

import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.Immersion_anki_snapshot
import tachiyomi.data.Immersion_goal
import tachiyomi.data.Immersion_import_ledger
import tachiyomi.data.Immersion_session
import tachiyomi.data.Immersion_source_unit
import tachiyomi.data.SelectImmersionIndexWork
import tachiyomi.data.SelectLegacyImmersionAggregates
import tachiyomi.domain.immersion.model.ExposureEvent
import tachiyomi.domain.immersion.model.ImmersionAnkiSnapshot
import tachiyomi.domain.immersion.model.ImmersionDataException
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionIntegrityReport
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.ImmersionOverview
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.ImmersionSessionStart
import tachiyomi.domain.immersion.model.ImmersionSourceUnit
import tachiyomi.domain.immersion.model.ImmersionTitle
import tachiyomi.domain.immersion.model.IndexWorkItem
import tachiyomi.domain.immersion.model.IndexedCharacter
import tachiyomi.domain.immersion.model.IndexedWord
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.LegacyAggregateRow
import tachiyomi.domain.immersion.model.LegacyImportBatch
import tachiyomi.domain.immersion.model.LegacyImportIdentity
import tachiyomi.domain.immersion.model.LegacyImportResult
import tachiyomi.domain.immersion.model.LegacyImportResultState
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.MillisecondDuration
import tachiyomi.domain.immersion.model.NetCharacterProgress
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.PersistenceErrorCode
import tachiyomi.domain.immersion.model.PersistenceResult
import tachiyomi.domain.immersion.model.RecordedImmersionEvent
import tachiyomi.domain.immersion.model.SessionCursor
import tachiyomi.domain.immersion.model.SessionEvent
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SessionPage
import tachiyomi.domain.immersion.model.SessionStatus
import tachiyomi.domain.immersion.model.SourceKind
import tachiyomi.domain.immersion.model.SourceUnitId
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.repository.ImmersionAnkiRepository
import tachiyomi.domain.immersion.repository.ImmersionGoalRepository
import tachiyomi.domain.immersion.repository.ImmersionIndexRepository
import tachiyomi.domain.immersion.repository.ImmersionLegacyImportRepository
import tachiyomi.domain.immersion.repository.ImmersionMaintenanceRepository
import tachiyomi.domain.immersion.repository.ImmersionRecorderRepository
import tachiyomi.domain.immersion.repository.ImmersionStatsRepository
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
            immersionQueries.recoverAbandonedImmersionSessions(heartbeatCutoffEpochMillis)
            val recovered = immersionQueries.selectImmersionChanges().executeAsOne()
            if (recovered > 0) immersionQueries.incrementImmersionRevision(heartbeatCutoffEpochMillis)
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

    override suspend fun claimWork(targetVersion: Int, limit: Int): List<IndexWorkItem> {
        require(targetVersion > 0) { "Target version must be positive" }
        require(limit in 1..MAX_PAGE_SIZE) { "Index claim limit must be between 1 and $MAX_PAGE_SIZE" }
        return handler.await(inTransaction = true) {
            val rows = immersionQueries
                .selectImmersionIndexWork(targetVersion.toLong(), limit.toLong())
                .executeAsList()
            if (rows.isNotEmpty()) {
                immersionQueries.markImmersionIndexWorkClaimed(rows.map { it.id })
            }
            rows.map(SelectImmersionIndexWork::toDomain)
        }
    }

    override suspend fun storeIndexResult(
        sourceUnitId: SourceUnitId,
        tokenizerVersion: Int,
        indexedVersion: Int,
        indexedAtEpochMillis: Long,
        words: List<IndexedWord>,
        characters: List<IndexedCharacter>,
    ) {
        require(tokenizerVersion > 0) { "Tokenizer version must be positive" }
        require(indexedVersion > 0) { "Indexed version must be positive" }
        require(indexedAtEpochMillis >= 0) { "Indexed timestamp cannot be negative" }
        handler.await(inTransaction = true) {
            if (immersionQueries.selectImmersionSourceUnitById(sourceUnitId.value).executeAsOneOrNull() == null) {
                throw identityConflict("Source unit ${sourceUnitId.value} does not exist")
            }
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
                    firstSeenAt = indexedAtEpochMillis,
                    lastSeenAt = indexedAtEpochMillis,
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
                    firstSeenAt = indexedAtEpochMillis,
                    lastSeenAt = indexedAtEpochMillis,
                )
                immersionQueries.insertImmersionCharacterOccurrence(
                    characterCodePoint = character.codePoint.value.toLong(),
                    sourceUnitId = sourceUnitId.value,
                    occurrenceCount = character.occurrenceCount.value,
                    firstOrdinal = character.firstOrdinal,
                )
            }
            immersionQueries.markImmersionSourceIndexed(
                tokenizerVersion = tokenizerVersion.toLong(),
                indexedVersion = indexedVersion.toLong(),
                id = sourceUnitId.value,
            )
            checkExactlyOneChange("marking source unit indexed")
            immersionQueries.incrementImmersionRevision(indexedAtEpochMillis)
        }
    }

    override suspend fun markFailure(sourceUnitId: SourceUnitId, errorCode: String) {
        require(errorCode.isNotBlank()) { "Index error code cannot be blank" }
        handler.await(inTransaction = true) {
            immersionQueries.markImmersionSourceIndexFailed(errorCode, sourceUnitId.value)
            checkExactlyOneChange("marking source unit index failure")
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
            immersionQueries.deleteImmersionSession(sessionId.value)
            val deleted = immersionQueries.selectImmersionChanges().executeAsOne() == 1L
            if (deleted) immersionQueries.incrementImmersionRevision(0)
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
            immersionQueries.clearImmersionRollups()
            immersionQueries.clearImmersionLifetimeRollups()
            immersionQueries.clearImmersionAppliedEvents()
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

    override suspend fun upsertSnapshot(snapshot: ImmersionAnkiSnapshot) {
        handler.await(inTransaction = true) {
            immersionQueries.upsertImmersionAnkiSnapshot(
                id = snapshot.id,
                profileId = snapshot.profileId,
                deckScope = snapshot.deckScope,
                requestedAt = snapshot.requestedAtEpochMillis,
                completedAt = snapshot.completedAtEpochMillis,
                capabilityVersion = snapshot.capabilityVersion.toLong(),
                status = snapshot.status,
                errorCode = snapshot.errorCode,
                isComplete = snapshot.isComplete.toLong(),
                isPartial = snapshot.isPartial.toLong(),
                isStale = snapshot.isStale.toLong(),
            )
            immersionQueries.incrementImmersionRevision(
                snapshot.completedAtEpochMillis ?: snapshot.requestedAtEpochMillis,
            )
        }
    }

    override suspend fun getLatestSnapshot(profileId: String): ImmersionAnkiSnapshot? =
        handler.await {
            immersionQueries
                .selectLatestImmersionAnkiSnapshot(profileId)
                .executeAsOneOrNull()
                ?.toDomain()
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
        immersionQueries.incrementImmersionRevision(event.occurredAtEpochMillis)
        return PersistenceResult.Applied
    }

    private fun Database.checkExactlyOneChange(operation: String) {
        if (immersionQueries.selectImmersionChanges().executeAsOne() != 1L) {
            throw identityConflict("No row was found while $operation")
        }
    }
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
            normalizedTextHash = normalized_text_hash,
            rawText = raw_text?.decodeUtf8Strict(),
            tokenizerVersion = tokenizer_version.toIntExact("tokenizer version"),
            indexedVersion = indexed_version.toIntExact("indexed version"),
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
            status = status,
            errorCode = error_code,
            isComplete = is_complete.toBooleanExact("complete flag"),
            isPartial = is_partial.toBooleanExact("partial flag"),
            isStale = is_stale.toBooleanExact("stale flag"),
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

private fun ByteArrayOutputStream.writeField(value: String) {
    val encoded = value.encodeToByteArray()
    writeLong(encoded.size.toLong())
    write(encoded)
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
private const val UTF8 = "UTF-8"
