package tachiyomi.domain.immersion.model

import kotlinx.serialization.Serializable

@Serializable
data class ImmersionTitle(
    val id: TitleId,
    val mediaKind: MediaKind,
    val sourceKey: String,
    val profileId: String = "",
    val languageTag: LanguageTag? = null,
    val displayTitle: String,
    val libraryId: Long? = null,
    val trackerId: String? = null,
    val mediaId: String? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(sourceKey.isNotBlank()) { "Source key cannot be blank" }
        require(displayTitle.isNotBlank()) { "Display title cannot be blank" }
        require(createdAtEpochMillis >= 0) { "Creation timestamp cannot be negative" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "Update timestamp cannot precede creation"
        }
    }
}

@Serializable
data class ImmersionSessionStart(
    val id: SessionId,
    val deviceId: String,
    val titleId: TitleId,
    val mediaKind: MediaKind,
    val languageTag: LanguageTag? = null,
    val profileId: String = "",
    val startedAtEpochMillis: Long,
    val startZoneId: String,
    val startOffsetSeconds: Int,
    val captureVersion: Int,
    val schemaVersion: Int,
    val legacyImport: Boolean = false,
    val syncOrigin: String? = null,
) {
    init {
        require(deviceId.isNotBlank()) { "Device ID cannot be blank" }
        require(startedAtEpochMillis >= 0) { "Start timestamp cannot be negative" }
        require(startZoneId.isNotBlank()) { "Start zone ID cannot be blank" }
        require(startOffsetSeconds in MIN_ZONE_OFFSET_SECONDS..MAX_ZONE_OFFSET_SECONDS) {
            "Start offset is outside the valid UTC offset range"
        }
        require(captureVersion > 0) { "Capture version must be positive" }
        require(schemaVersion > 0) { "Schema version must be positive" }
    }
}

@Serializable
data class ImmersionSourceUnit(
    val id: SourceUnitId,
    val titleId: TitleId,
    val sourceKind: SourceKind,
    val canonicalLocator: String,
    val normalizedTextHash: String,
    val chapterOrSectionId: String? = null,
    val episodeOrMediaId: String? = null,
    val pageOrCueIndex: Long? = null,
    val trackId: String? = null,
    val sourceStart: Long? = null,
    val sourceEnd: Long? = null,
    val parserVersion: Int? = null,
    val ocrEngineId: String? = null,
    val ocrVersion: Int? = null,
    val ocrConfidence: Double? = null,
    val ocrQuality: CapabilityState? = null,
    val tokenizerVersion: Int = 0,
    val rawText: String? = null,
    val firstExposedAtEpochMillis: Long,
    val lastExposedAtEpochMillis: Long,
    val characterCounts: CharacterVolume = CharacterVolume(),
) {
    init {
        require(canonicalLocator.isNotBlank()) { "Canonical locator cannot be blank" }
        require(normalizedTextHash.isNotBlank()) { "Normalized text hash cannot be blank" }
        require(pageOrCueIndex == null || pageOrCueIndex >= 0) { "Page or cue index cannot be negative" }
        require(sourceStart == null || sourceStart >= 0) { "Source start cannot be negative" }
        require(sourceEnd == null || sourceEnd >= 0) { "Source end cannot be negative" }
        require(sourceStart == null || sourceEnd == null || sourceEnd > sourceStart) {
            "Source end must be after source start"
        }
        require(parserVersion == null || parserVersion > 0) { "Parser version must be positive" }
        require(ocrVersion == null || ocrVersion > 0) { "OCR version must be positive" }
        require(ocrConfidence == null || ocrConfidence in 0.0..1.0) {
            "OCR confidence must be between zero and one"
        }
        require(tokenizerVersion >= 0) { "Tokenizer version cannot be negative" }
        require(firstExposedAtEpochMillis >= 0) { "First exposure timestamp cannot be negative" }
        require(lastExposedAtEpochMillis >= firstExposedAtEpochMillis) {
            "Last exposure cannot precede first exposure"
        }
    }
}

@Serializable
sealed interface RecordedImmersionEvent {
    val id: EventId
    val sessionId: SessionId
    val sequence: Long
    val occurredAtEpochMillis: Long
    val timezoneOffsetSeconds: Int
    val type: EventType
    val activeDuration: MillisecondDuration
}

@Serializable
data class SessionEvent(
    override val id: EventId,
    override val sessionId: SessionId,
    override val sequence: Long,
    override val occurredAtEpochMillis: Long,
    override val timezoneOffsetSeconds: Int,
    override val type: EventType,
    override val activeDuration: MillisecondDuration = MillisecondDuration(0),
    val netCharacters: NetCharacterProgress = NetCharacterProgress.ZERO,
) : RecordedImmersionEvent {
    init {
        require(sequence > 0) { "Event sequence must be positive" }
        require(occurredAtEpochMillis >= 0) { "Event timestamp cannot be negative" }
        require(timezoneOffsetSeconds in MIN_ZONE_OFFSET_SECONDS..MAX_ZONE_OFFSET_SECONDS) {
            "Event offset is outside the valid UTC offset range"
        }
        require(type != EventType.EXPOSURE) { "Exposure events must include a source unit" }
        require(type == EventType.PROGRESS || netCharacters == NetCharacterProgress.ZERO) {
            "Only progress events can include a net-character delta"
        }
    }
}

@Serializable
data class ExposureEvent(
    override val id: EventId,
    override val sessionId: SessionId,
    override val sequence: Long,
    override val occurredAtEpochMillis: Long,
    override val timezoneOffsetSeconds: Int,
    override val type: EventType = EventType.EXPOSURE,
    val source: ImmersionSourceUnit,
    override val activeDuration: MillisecondDuration,
    val grossCharacters: NonNegativeCounter,
    val uniqueSourceCharacters: NonNegativeCounter,
    val netCharacters: NetCharacterProgress,
    val replayOrdinal: Int = 0,
    val exposurePolicy: String,
) : RecordedImmersionEvent {
    init {
        require(sequence > 0) { "Event sequence must be positive" }
        require(occurredAtEpochMillis >= 0) { "Event timestamp cannot be negative" }
        require(timezoneOffsetSeconds in MIN_ZONE_OFFSET_SECONDS..MAX_ZONE_OFFSET_SECONDS) {
            "Event offset is outside the valid UTC offset range"
        }
        require(replayOrdinal >= 0) { "Replay ordinal cannot be negative" }
        require(exposurePolicy.isNotBlank()) { "Exposure policy cannot be blank" }
    }
}

@Serializable
data class LookupEvent(
    override val id: EventId,
    override val sessionId: SessionId,
    override val sequence: Long,
    override val occurredAtEpochMillis: Long,
    override val timezoneOffsetSeconds: Int,
    override val type: EventType = EventType.LOOKUP,
    override val activeDuration: MillisecondDuration = MillisecondDuration(0),
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
) : RecordedImmersionEvent {
    init {
        require(sequence > 0) { "Event sequence must be positive" }
        require(occurredAtEpochMillis >= 0) { "Event timestamp cannot be negative" }
        require(timezoneOffsetSeconds in MIN_ZONE_OFFSET_SECONDS..MAX_ZONE_OFFSET_SECONDS) {
            "Event offset is outside the valid UTC offset range"
        }
        require(lookupId.isNotBlank()) { "Lookup ID cannot be blank" }
        require(queryHash.isNotBlank()) { "Lookup query hash cannot be blank" }
        require(type == EventType.LOOKUP) { "Lookup events must use the lookup event type" }
    }
}

@Serializable
data class AnkiOperationEvent(
    override val id: EventId,
    override val sessionId: SessionId,
    override val sequence: Long,
    override val occurredAtEpochMillis: Long,
    override val timezoneOffsetSeconds: Int,
    override val type: EventType = EventType.ANKI_OPERATION,
    override val activeDuration: MillisecondDuration = MillisecondDuration(0),
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
) : RecordedImmersionEvent {
    init {
        require(sequence > 0) { "Event sequence must be positive" }
        require(occurredAtEpochMillis >= 0) { "Event timestamp cannot be negative" }
        require(timezoneOffsetSeconds in MIN_ZONE_OFFSET_SECONDS..MAX_ZONE_OFFSET_SECONDS) {
            "Event offset is outside the valid UTC offset range"
        }
        require(expressionHash.isNotBlank()) { "Anki expression hash cannot be blank" }
        require(noteId == null || noteId >= 0) { "Anki note ID cannot be negative" }
        require(cardId == null || cardId >= 0) { "Anki card ID cannot be negative" }
        require(deckId == null || deckId >= 0) { "Anki deck ID cannot be negative" }
        require(type == EventType.ANKI_OPERATION) {
            "Anki operation events must use the Anki operation event type"
        }
    }
}

@Serializable
data class ImmersionSession(
    val id: SessionId,
    val deviceId: String,
    val titleId: TitleId,
    val mediaKind: MediaKind,
    val languageTag: LanguageTag?,
    val profileId: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
    val startZoneId: String,
    val startOffsetSeconds: Int,
    val status: SessionStatus,
    val activeDuration: MillisecondDuration,
    val elapsedDuration: MillisecondDuration,
    val grossCharacters: NonNegativeCounter,
    val uniqueSourceCharacters: NonNegativeCounter,
    val netCharacters: NetCharacterProgress,
    val sourceUnitCount: NonNegativeCounter,
    val lastSequence: Long,
    val lastHeartbeatAtEpochMillis: Long?,
    val captureVersion: Int,
    val schemaVersion: Int,
    val legacyImport: Boolean,
) {
    init {
        require(startedAtEpochMillis >= 0) { "Start timestamp cannot be negative" }
        require(endedAtEpochMillis == null || endedAtEpochMillis >= startedAtEpochMillis) {
            "End timestamp cannot precede start"
        }
        require(status == SessionStatus.ACTIVE || endedAtEpochMillis != null || legacyImport) {
            "A finalized session must have an end timestamp"
        }
        require(lastSequence >= 0) { "Last sequence cannot be negative" }
        require(lastHeartbeatAtEpochMillis == null || lastHeartbeatAtEpochMillis >= startedAtEpochMillis) {
            "Last heartbeat cannot precede start"
        }
        require(captureVersion > 0) { "Capture version must be positive" }
        require(schemaVersion > 0) { "Schema version must be positive" }
    }
}

@Serializable
data class SessionCursor(
    val startedAtEpochMillis: Long,
    val id: SessionId,
)

@Serializable
data class SessionPage(
    val items: List<ImmersionSession>,
    val nextCursor: SessionCursor?,
)

@Serializable
data class ImmersionOverview(
    val activeDuration: MillisecondDuration,
    val grossCharacters: NonNegativeCounter,
    val uniqueSourceCharacters: NonNegativeCounter,
    val netCharacters: NetCharacterProgress,
    val sourceUnits: NonNegativeCounter,
    val words: NonNegativeCounter,
    val lookups: NonNegativeCounter,
    val cardsCreated: NonNegativeCounter,
    val cardsUpdated: NonNegativeCounter,
    val sessions: NonNegativeCounter,
)

@Serializable
data class ImmersionIntegrityReport(
    val orphanedEvents: NonNegativeCounter,
    val orphanedOccurrences: NonNegativeCounter,
    val duplicateSessionSequences: NonNegativeCounter,
    val negativeCounters: NonNegativeCounter,
    val rollupVersionMismatches: NonNegativeCounter,
) {
    val isHealthy: Boolean
        get() = orphanedEvents == NonNegativeCounter.ZERO &&
            orphanedOccurrences == NonNegativeCounter.ZERO &&
            duplicateSessionSequences == NonNegativeCounter.ZERO &&
            negativeCounters == NonNegativeCounter.ZERO &&
            rollupVersionMismatches == NonNegativeCounter.ZERO
}

@Serializable
data class IndexWorkItem(
    val sourceUnitId: SourceUnitId,
    val titleId: TitleId,
    val sourceKind: SourceKind,
    val languageTag: LanguageTag?,
    val profileId: String,
    val normalizedTextHash: String,
    val rawText: String?,
    val tokenizerVersion: Int,
    val indexedVersion: Int,
    val attemptCount: Int = 0,
) {
    init {
        require(normalizedTextHash.isNotBlank()) { "Normalized text hash cannot be blank" }
        require(tokenizerVersion >= 0) { "Tokenizer version cannot be negative" }
        require(indexedVersion >= 0) { "Indexed version cannot be negative" }
        require(attemptCount >= 0) { "Index attempt count cannot be negative" }
    }
}

@Serializable
enum class IndexTerminalReason {
    UNSUPPORTED_LANGUAGE,
    RAW_TEXT_UNAVAILABLE,
}

@Serializable
data class ImmersionReindexRequest(
    val languageTag: LanguageTag? = null,
    val titleId: TitleId? = null,
    val exposedFromEpochMillis: Long? = null,
    val exposedUntilEpochMillis: Long? = null,
) {
    init {
        require(exposedFromEpochMillis == null || exposedFromEpochMillis >= 0) {
            "Reindex start cannot be negative"
        }
        require(exposedUntilEpochMillis == null || exposedUntilEpochMillis >= 0) {
            "Reindex end cannot be negative"
        }
        require(
            exposedFromEpochMillis == null ||
                exposedUntilEpochMillis == null ||
                exposedUntilEpochMillis >= exposedFromEpochMillis,
        ) {
            "Reindex end cannot precede its start"
        }
    }
}

@Serializable
data class IndexedWord(
    val id: String,
    val languageTag: LanguageTag,
    val normalizedHeadword: String,
    val normalizedReading: String = "",
    val displayHeadword: String,
    val displayReading: String? = null,
    val partOfSpeech: String? = null,
    val tokenizationConfidence: Double? = null,
    val frequencyCorpus: String? = null,
    val frequencyRank: Long? = null,
    val jlptLevel: Int? = null,
    val gradeLevel: Int? = null,
    val tokenOrdinal: Long,
    val surfaceText: String? = null,
    val sourceStart: Long? = null,
    val sourceEnd: Long? = null,
    val deinflectionRule: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Word ID cannot be blank" }
        require(normalizedHeadword.isNotBlank()) { "Normalized headword cannot be blank" }
        require(displayHeadword.isNotBlank()) { "Display headword cannot be blank" }
        require(tokenOrdinal >= 0) { "Token ordinal cannot be negative" }
        require(frequencyRank == null || frequencyRank > 0) { "Frequency rank must be positive" }
    }
}

@Serializable
data class IndexedCharacter(
    val codePoint: UnicodeCodePoint,
    val unicodeName: String?,
    val unicodeCategory: String,
    val unicodeScript: String,
    val occurrenceCount: NonNegativeCounter,
    val firstOrdinal: Long,
) {
    init {
        require(unicodeCategory.isNotBlank()) { "Unicode category cannot be blank" }
        require(unicodeScript.isNotBlank()) { "Unicode script cannot be blank" }
        require(occurrenceCount.value > 0) { "Occurrence count must be positive" }
        require(firstOrdinal >= 0) { "First ordinal cannot be negative" }
    }
}

@Serializable
data class ImmersionGoal(
    val id: String,
    val type: String,
    val metric: String,
    val target: Double,
    val period: String,
    val startDate: ImmersionLocalDate?,
    val endDate: ImmersionLocalDate?,
    val mediaKind: MediaKind?,
    val profileId: String?,
    val languageTag: LanguageTag?,
    val titleId: TitleId?,
    val weekdayMultipliers: String?,
    val restDayPolicy: String?,
    val state: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(id.isNotBlank()) { "Goal ID cannot be blank" }
        require(type.isNotBlank()) { "Goal type cannot be blank" }
        require(metric.isNotBlank()) { "Goal metric cannot be blank" }
        require(target.isFinite() && target >= 0) { "Goal target must be finite and non-negative" }
        require(period.isNotBlank()) { "Goal period cannot be blank" }
        require(startDate == null || endDate == null || startDate <= endDate) {
            "Goal end date cannot precede its start date"
        }
        require(state.isNotBlank()) { "Goal state cannot be blank" }
        require(createdAtEpochMillis >= 0) { "Goal creation timestamp cannot be negative" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "Goal update timestamp cannot precede creation"
        }
    }
}

@Serializable
data class ImmersionGoalCheckIn(
    val goalId: String,
    val localDate: ImmersionLocalDate,
    val status: String,
    val note: String?,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(goalId.isNotBlank())
        require(status.isNotBlank())
        require(occurredAtEpochMillis >= 0)
    }
}

@Serializable
data class ImmersionGoalAchievement(
    val id: String,
    val goalId: String,
    val milestoneKey: String,
    val earnedAtEpochMillis: Long,
    val targetSnapshot: Double,
) {
    init {
        require(id.isNotBlank())
        require(goalId.isNotBlank())
        require(milestoneKey.isNotBlank())
        require(earnedAtEpochMillis >= 0)
        require(targetSnapshot.isFinite() && targetSnapshot >= 0)
    }
}

@Serializable
data class ImmersionAnkiSnapshot(
    val id: String,
    val profileId: String,
    val deckScope: String?,
    val requestedAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
    val capabilityVersion: Int,
    val capabilityState: CapabilityState,
    val providerVersion: String?,
    val supportsNoteModificationTime: Boolean,
    val supportsCardModificationTime: Boolean,
    val supportsReviewHistory: Boolean,
    val status: AnkiSnapshotStatus,
    val errorCode: AnkiInventoryFailure?,
    val itemCount: Int,
    val noteCount: Int,
    val matureIntervalDays: Int,
    val mappingHash: String,
    val queryDurationMillis: Long?,
    val isComplete: Boolean,
    val isPartial: Boolean,
    val isCurrent: Boolean,
    val isStale: Boolean,
) {
    init {
        require(id.isNotBlank()) { "Snapshot ID cannot be blank" }
        require(profileId.isNotBlank()) { "Snapshot profile cannot be blank" }
        require(requestedAtEpochMillis >= 0) { "Snapshot request timestamp cannot be negative" }
        require(completedAtEpochMillis == null || completedAtEpochMillis >= requestedAtEpochMillis) {
            "Snapshot completion cannot precede its request"
        }
        require(capabilityVersion > 0) { "Capability version must be positive" }
        require(itemCount >= 0) { "Snapshot item count cannot be negative" }
        require(noteCount >= 0) { "Snapshot note count cannot be negative" }
        require(matureIntervalDays > 0) { "Mature interval must be positive" }
        require(mappingHash.isNotBlank()) { "Snapshot mapping hash cannot be blank" }
        require(queryDurationMillis == null || queryDurationMillis >= 0) {
            "Snapshot query duration cannot be negative"
        }
        require(!(isComplete && isPartial)) { "Snapshot cannot be both complete and partial" }
        require(!isCurrent || isComplete) { "Only a complete snapshot can be current" }
    }
}

@Serializable
data class ImmersionAnkiItem(
    val snapshotId: String,
    val noteId: Long,
    val cardId: Long,
    val noteTypeId: Long,
    val deckId: Long,
    val languageTag: LanguageTag,
    val normalizedWord: String,
    val normalizedReading: String,
    val characters: Set<UnicodeCodePoint>,
    val cardType: Int?,
    val queue: Int?,
    val intervalDays: Int?,
    val due: Long?,
    val repetitions: Int?,
    val lapses: Int?,
    val ease: Int?,
    val noteModifiedAtEpochSeconds: Long?,
    val matchConfidence: AnkiMatchConfidence,
    val ambiguityCount: Int,
    val maturityTier: MaturityTier,
    val firstMatureAtEpochMillis: Long?,
) {
    init {
        require(snapshotId.isNotBlank()) { "Anki item snapshot ID cannot be blank" }
        require(noteId >= 0 && cardId >= 0) { "Anki IDs cannot be negative" }
        require(noteTypeId >= 0 && deckId >= 0) { "Anki scope IDs cannot be negative" }
        require(normalizedWord.isNotBlank()) { "Normalized Anki word cannot be blank" }
        require(intervalDays == null || intervalDays >= 0) { "Anki interval cannot be negative" }
        require(repetitions == null || repetitions >= 0) { "Anki repetitions cannot be negative" }
        require(lapses == null || lapses >= 0) { "Anki lapses cannot be negative" }
        require(ambiguityCount >= 0) { "Anki ambiguity count cannot be negative" }
        require(noteModifiedAtEpochSeconds == null || noteModifiedAtEpochSeconds >= 0)
        require(firstMatureAtEpochMillis == null || firstMatureAtEpochMillis >= 0)
    }
}

enum class PersistenceErrorCode {
    CORRUPT_VALUE,
    IDENTITY_CONFLICT,
    SEQUENCE_CONFLICT,
    SESSION_NOT_ACTIVE,
    DATABASE_BUSY,
    DATABASE_UNAVAILABLE,
    CONSTRAINT_VIOLATION,
    UNKNOWN,
}

class ImmersionDataException(
    val code: PersistenceErrorCode,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

sealed interface PersistenceResult {
    data object Applied : PersistenceResult

    data object AlreadyApplied : PersistenceResult

    data object Disabled : PersistenceResult

    data class Failed(val code: PersistenceErrorCode) : PersistenceResult
}

internal const val MIN_ZONE_OFFSET_SECONDS = -18 * 60 * 60
internal const val MAX_ZONE_OFFSET_SECONDS = 18 * 60 * 60
