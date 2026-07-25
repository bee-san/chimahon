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
    val normalizedTextHash: String,
    val rawText: String?,
    val tokenizerVersion: Int,
    val indexedVersion: Int,
) {
    init {
        require(normalizedTextHash.isNotBlank()) { "Normalized text hash cannot be blank" }
        require(tokenizerVersion >= 0) { "Tokenizer version cannot be negative" }
        require(indexedVersion >= 0) { "Indexed version cannot be negative" }
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
data class ImmersionAnkiSnapshot(
    val id: String,
    val profileId: String,
    val deckScope: String?,
    val requestedAtEpochMillis: Long,
    val completedAtEpochMillis: Long?,
    val capabilityVersion: Int,
    val status: String,
    val errorCode: String?,
    val isComplete: Boolean,
    val isPartial: Boolean,
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
        require(status.isNotBlank()) { "Snapshot status cannot be blank" }
        require(!(isComplete && isPartial)) { "Snapshot cannot be both complete and partial" }
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
