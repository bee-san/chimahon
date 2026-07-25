package tachiyomi.domain.immersion.model

import kotlinx.serialization.Serializable

@Serializable
enum class AnalyticsBucketScale {
    DAY,
    WEEK,
    MONTH,
}

@Serializable
enum class AnalyticsSort {
    MOST_RECENT,
    MOST_TIME,
    MOST_CHARACTERS,
    MOST_OCCURRENCES,
    FIRST_SEEN,
    ALPHABETICAL,
}

@Serializable
enum class AnalyticsQueryFamily {
    OVERVIEW,
    TRENDS,
    TITLES,
    VOCABULARY,
    CHARACTERS,
    SESSIONS,
    GOALS,
    ANKI,
}

@Serializable
data class AnalyticsDataQuality(
    val legacySessionCount: Long = 0,
    val eventBackedSessionCount: Long = 0,
    val sourceUnitCount: Long = 0,
    val indexedSourceUnitCount: Long = 0,
    val textAvailableSourceUnitCount: Long = 0,
    val ocrSourceUnitCount: Long = 0,
    val ocrTextAvailableSourceUnitCount: Long = 0,
    val ankiState: CapabilityState = CapabilityState.UNAVAILABLE,
    val ankiSnapshotAgeMillis: Long? = null,
    val provenanceState: ProvenanceState = ProvenanceState.UNAVAILABLE,
) {
    init {
        require(legacySessionCount >= 0)
        require(eventBackedSessionCount >= 0)
        require(sourceUnitCount >= 0)
        require(indexedSourceUnitCount in 0..sourceUnitCount)
        require(textAvailableSourceUnitCount in 0..sourceUnitCount)
        require(ocrSourceUnitCount in 0..sourceUnitCount)
        require(ocrTextAvailableSourceUnitCount in 0..ocrSourceUnitCount)
        require(ankiSnapshotAgeMillis == null || ankiSnapshotAgeMillis >= 0)
    }

    val legacyShare: Double?
        get() = ratio(legacySessionCount, legacySessionCount + eventBackedSessionCount)

    val indexingCompletion: Double?
        get() = ratio(indexedSourceUnitCount, sourceUnitCount)

    val textCoverage: Double?
        get() = ratio(textAvailableSourceUnitCount, sourceUnitCount)

    val ocrTextCoverage: Double?
        get() = ratio(ocrTextAvailableSourceUnitCount, ocrSourceUnitCount)

    private fun ratio(numerator: Long, denominator: Long): Double? =
        if (denominator == 0L) null else numerator.toDouble() / denominator.toDouble()
}

@Serializable
data class AnalyticsQueryDiagnostics(
    val family: AnalyticsQueryFamily,
    val filterHash: String,
    val rowCount: Int,
    val durationMillis: Long,
) {
    init {
        require(filterHash.isNotBlank())
        require(rowCount >= 0)
        require(durationMillis >= 0)
    }
}

@Serializable
data class AnalyticsResult<T>(
    val value: T,
    val quality: AnalyticsDataQuality,
    val diagnostics: AnalyticsQueryDiagnostics,
)

@Serializable
data class AnalyticsPeriod(
    val range: LocalDateRange,
    val isPartialCurrentDay: Boolean = false,
)

@Serializable
data class AnalyticsComparison(
    val current: ReadingMetrics,
    val previous: ReadingMetrics?,
    val activeTimeChangeRatio: Double?,
    val characterChangeRatio: Double?,
)

@Serializable
data class AnalyticsStreak(
    val currentDays: Int,
    val longestDays: Int,
    val qualifyingDates: Set<ImmersionLocalDate>,
) {
    init {
        require(currentDays >= 0)
        require(longestDays >= currentDays)
    }
}

@Serializable
data class AnalyticsOverview(
    val period: AnalyticsPeriod,
    val comparison: AnalyticsComparison,
    val streak: AnalyticsStreak,
)

@Serializable
data class AnalyticsTrendPoint(
    val range: LocalDateRange,
    val metrics: ReadingMetrics,
    val cumulativeMetrics: ReadingMetrics,
)

@Serializable
data class AnalyticsTrends(
    val scale: AnalyticsBucketScale,
    val points: List<AnalyticsTrendPoint>,
)

@Serializable
data class AnalyticsTitleRow(
    val titleId: TitleId,
    val displayTitle: String,
    val mediaKind: MediaKind,
    val languageTag: LanguageTag?,
    val metrics: ReadingMetrics,
    val firstActiveDate: ImmersionLocalDate,
    val lastActiveDate: ImmersionLocalDate,
    val progress: Double?,
    val completed: Boolean?,
)

@Serializable
data class AnalyticsTitleMetadata(
    val titleId: TitleId,
    val displayTitle: String,
    val mediaKind: MediaKind,
    val languageTag: LanguageTag?,
    val totalUnits: Long?,
    val totalCharacterEstimate: Long?,
    val completed: Boolean?,
) {
    init {
        require(displayTitle.isNotBlank())
        require(totalUnits == null || totalUnits >= 0)
        require(totalCharacterEstimate == null || totalCharacterEstimate >= 0)
    }
}

@Serializable
data class AnalyticsWordRow(
    val id: String,
    val languageTag: LanguageTag,
    val headword: String,
    val reading: String?,
    val partOfSpeech: String?,
    val occurrenceCount: Long,
    val titleCount: Long,
    val firstSeenAtEpochMillis: Long,
    val lastSeenAtEpochMillis: Long,
    val frequencyRank: Long?,
    val maturity: MaturityTier,
    val matchConfidence: AnkiMatchConfidence?,
) {
    init {
        require(id.isNotBlank())
        require(headword.isNotBlank())
        require(occurrenceCount >= 0)
        require(titleCount >= 0)
        require(firstSeenAtEpochMillis >= 0)
        require(lastSeenAtEpochMillis >= firstSeenAtEpochMillis)
        require(frequencyRank == null || frequencyRank > 0)
    }
}

@Serializable
data class AnalyticsCharacterRow(
    val codePoint: UnicodeCodePoint,
    val rendered: String,
    val unicodeName: String?,
    val unicodeScript: String,
    val occurrenceCount: Long,
    val wordCount: Long,
    val titleCount: Long,
    val firstSeenAtEpochMillis: Long,
    val lastSeenAtEpochMillis: Long,
    val frequencyRank: Long?,
    val maturity: MaturityTier,
) {
    init {
        require(rendered.isNotBlank())
        require(unicodeScript.isNotBlank())
        require(occurrenceCount >= 0)
        require(wordCount >= 0)
        require(titleCount >= 0)
        require(firstSeenAtEpochMillis >= 0)
        require(lastSeenAtEpochMillis >= firstSeenAtEpochMillis)
        require(frequencyRank == null || frequencyRank > 0)
    }
}

@Serializable
data class AnalyticsPage<T>(
    val items: List<T>,
    val nextOffset: Long?,
) {
    init {
        require(nextOffset == null || nextOffset >= 0)
    }
}

@Serializable
data class AnalyticsGoalProgress(
    val goal: ImmersionGoal,
    val achieved: Double,
    val target: Double,
    val pacePerDay: Double?,
    val projectedCompletionDate: ImmersionLocalDate?,
    val achievedAtEpochMillis: Long?,
)

@Serializable
data class AnalyticsAnkiSummary(
    val snapshot: ImmersionAnkiSnapshot?,
    val wordCoverageEncountered: Long,
    val wordCoverageKnown: Long,
    val characterCoverageEncountered: Long,
    val characterCoverageKnown: Long,
    val reviewHistoryAvailable: Boolean,
) {
    init {
        require(wordCoverageEncountered >= 0)
        require(wordCoverageKnown in 0..wordCoverageEncountered)
        require(characterCoverageEncountered >= 0)
        require(characterCoverageKnown in 0..characterCoverageEncountered)
    }
}

@Serializable
data class ImmersionDailyRollup(
    val date: ImmersionLocalDate,
    val profileId: String,
    val languageTag: LanguageTag?,
    val mediaKind: MediaKind,
    val titleId: TitleId,
    val metrics: ReadingMetrics,
    val provenanceState: ProvenanceState,
    val replay: Boolean,
    val rollupVersion: Int,
)

@Serializable
data class ImmersionRollupDirtyRange(
    val start: ImmersionLocalDate,
    val endInclusive: ImmersionLocalDate,
    val reason: String,
) {
    init {
        require(start <= endInclusive)
        require(reason.isNotBlank())
    }
}

@Serializable
data class ImmersionRollupRebuildResult(
    val range: LocalDateRange,
    val eventCount: Long,
    val sessionCount: Long,
    val sourceUnitCount: Long,
    val rowCount: Long,
) {
    init {
        require(eventCount >= 0)
        require(sessionCount >= 0)
        require(sourceUnitCount >= 0)
        require(rowCount >= 0)
    }
}
