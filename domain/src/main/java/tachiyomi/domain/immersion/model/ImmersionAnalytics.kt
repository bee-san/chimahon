// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.model

import kotlinx.serialization.Serializable

@Serializable
enum class AnalyticsBucketScale {
    DAY,
    WEEK,
    MONTH,
}

@Serializable
enum class AnalyticsTitleSeriesSelection {
    TOP_CHARACTERS,
    MOST_RECENT,
}

@Serializable
enum class AnalyticsSort {
    MOST_RECENT,
    MOST_TIME,
    MOST_CHARACTERS,
    MOST_OCCURRENCES,
    FIRST_SEEN,
    ALPHABETICAL,
    FREQUENCY_RANK,
    PRIORITY,
}

@Serializable
enum class AnalyticsTitleSort {
    MOST_RECENT,
    ALPHABETICAL,
    MOST_TIME,
    MOST_CHARACTERS,
    READING_SPEED,
    NOVELTY,
    MINING_RATE,
    PROGRESS,
}

@Serializable
enum class AnalyticsTitleStateFilter {
    ALL,
    COMPLETED,
    IN_PROGRESS,
    UNKNOWN,
}

@Serializable
enum class AnalyticsTitleCoverageFilter {
    ALL,
    COMPLETE,
    PARTIAL,
    MISSING,
}

@Serializable
data class AnalyticsTitleFilter(
    val searchQuery: String? = null,
    val state: AnalyticsTitleStateFilter = AnalyticsTitleStateFilter.ALL,
    val coverage: AnalyticsTitleCoverageFilter = AnalyticsTitleCoverageFilter.ALL,
    val minimumSpeedCharacters: Long = 1_000,
    val minimumSpeedActiveMillis: Long = 10 * 60 * 1_000,
) {
    init {
        require(minimumSpeedCharacters > 0)
        require(minimumSpeedActiveMillis > 0)
    }
}

@Serializable
enum class VocabularyKnownness {
    ALL,
    UNKNOWN,
    KNOWN,
}

@Serializable
enum class VocabularyScript {
    KANJI,
    KANA,
    LATIN,
    OTHER,
}

@Serializable
enum class VocabularyCategory {
    NAME,
    KANA_ONLY,
    GRAMMAR,
    OTHER,
}

@Serializable
enum class VocabularyExclusion {
    INCLUDED,
    EXCLUDED,
    ALL,
}

@Serializable
data class VocabularyFilter(
    val searchQuery: String? = null,
    val knownness: VocabularyKnownness = VocabularyKnownness.ALL,
    val scripts: Set<VocabularyScript> = emptySet(),
    val categories: Set<VocabularyCategory> = emptySet(),
    val partOfSpeechQuery: String? = null,
    val minimumOccurrences: Long? = null,
    val maximumOccurrences: Long? = null,
    val maximumFrequencyRank: Long? = null,
    val exclusion: VocabularyExclusion = VocabularyExclusion.INCLUDED,
) {
    init {
        require(minimumOccurrences == null || minimumOccurrences > 0)
        require(maximumOccurrences == null || maximumOccurrences > 0)
        require(
            minimumOccurrences == null ||
                maximumOccurrences == null ||
                minimumOccurrences <= maximumOccurrences,
        )
        require(maximumFrequencyRank == null || maximumFrequencyRank > 0)
    }
}

@Serializable
enum class AnalyticsCharacterScript {
    HAN,
    HIRAGANA,
    KATAKANA,
    HANGUL,
    LATIN,
    OTHER,
}

@Serializable
enum class AnalyticsCharacterRange {
    ENCOUNTERED,
    FIRST_SEEN_IN_RANGE,
    UNKNOWN,
    NEW,
    LEARNING,
    YOUNG,
    MATURE,
    MISSING_HIGH_FREQUENCY,
}

@Serializable
enum class AnalyticsCharacterPriorityMode {
    FREQUENCY,
    JLPT,
    GRADE,
    MIXED,
}

@Serializable
data class AnalyticsCharacterFilter(
    val searchQuery: String? = null,
    val scripts: Set<AnalyticsCharacterScript> = emptySet(),
    val range: AnalyticsCharacterRange = AnalyticsCharacterRange.ENCOUNTERED,
    val priorityMode: AnalyticsCharacterPriorityMode = AnalyticsCharacterPriorityMode.MIXED,
    val maximumMissingFrequencyRank: Long = 10_000,
) {
    init {
        require(maximumMissingFrequencyRank > 0)
    }
}

@Serializable
data class AnalyticsCharacterScriptSummary(
    val script: AnalyticsCharacterScript,
    val distinctCharacters: Long,
    val grossOccurrenceExposure: Long,
    val representedInAnki: Long,
    val matureInAnki: Long,
) {
    init {
        require(distinctCharacters >= 0)
        require(grossOccurrenceExposure >= 0)
        require(representedInAnki in 0..distinctCharacters)
        require(matureInAnki in 0..representedInAnki)
    }
}

@Serializable
data class AnalyticsCharacterSummary(
    val scripts: List<AnalyticsCharacterScriptSummary>,
    val firstSeenInRange: Long,
    val maximumOccurrenceCount: Long,
) {
    init {
        require(firstSeenInRange >= 0)
        require(maximumOccurrenceCount >= 0)
        require(scripts.distinctBy(AnalyticsCharacterScriptSummary::script).size == scripts.size)
    }

    val distinctCharacters: Long
        get() = scripts.sumOf(AnalyticsCharacterScriptSummary::distinctCharacters)

    val grossOccurrenceExposure: Long
        get() = scripts.sumOf(AnalyticsCharacterScriptSummary::grossOccurrenceExposure)

    val representedInAnki: Long
        get() = scripts.sumOf(AnalyticsCharacterScriptSummary::representedInAnki)

    val matureInAnki: Long
        get() = scripts.sumOf(AnalyticsCharacterScriptSummary::matureInAnki)
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
data class AnalyticsInventoryMetrics(
    val distinctCharacters: Long = 0,
    val newCharacters: Long = 0,
    val uniqueWords: Long = 0,
    val newWords: Long = 0,
    val charactersRepresentedInAnki: Long = 0,
) {
    init {
        require(distinctCharacters >= 0)
        require(newCharacters in 0..distinctCharacters)
        require(uniqueWords >= 0)
        require(newWords in 0..uniqueWords)
        require(charactersRepresentedInAnki in 0..distinctCharacters)
    }
}

@Serializable
data class AnalyticsBucketInventory(
    val metrics: AnalyticsInventoryMetrics = AnalyticsInventoryMetrics(),
    val cumulative: AnalyticsInventoryMetrics = AnalyticsInventoryMetrics(),
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
data class AnalyticsActivityTotals(
    val activeDurationMillis: Long = 0,
    val grossCharacters: Long = 0,
    val uniqueSourceCharacters: Long = 0,
    val netCharacters: Long = 0,
) {
    init {
        require(activeDurationMillis >= 0)
        require(grossCharacters >= 0)
        require(uniqueSourceCharacters >= 0)
    }

    fun readingSpeedPerHour(metric: CharacterMetric): Double? {
        if (activeDurationMillis == 0L) return null
        val characters = when (metric) {
            CharacterMetric.GROSS -> grossCharacters
            CharacterMetric.UNIQUE_SOURCE -> uniqueSourceCharacters
            CharacterMetric.NET_PROGRESS -> netCharacters
        }
        if (characters < 0) return null
        return characters.toDouble() * 3_600_000.0 / activeDurationMillis.toDouble()
    }
}

@Serializable
data class AnalyticsHourActivity(
    val hourOfDay: Int,
    val totals: AnalyticsActivityTotals,
) {
    init {
        require(hourOfDay in 0..23)
    }
}

@Serializable
data class AnalyticsWeekdayActivity(
    val isoDayOfWeek: Int,
    val totals: AnalyticsActivityTotals,
) {
    init {
        require(isoDayOfWeek in 1..7)
    }
}

@Serializable
data class AnalyticsTemporalActivity(
    val hours: List<AnalyticsHourActivity>,
    val weekdays: List<AnalyticsWeekdayActivity>,
)

@Serializable
data class AnalyticsTitleTrendDailyPoint(
    val titleId: TitleId,
    val displayTitle: String,
    val mediaKind: MediaKind,
    val languageTag: LanguageTag?,
    val date: ImmersionLocalDate,
    val metrics: ReadingMetrics,
) {
    init {
        require(displayTitle.isNotBlank())
    }
}

@Serializable
data class AnalyticsTitleTrendSeries(
    val titleId: TitleId,
    val displayTitle: String,
    val mediaKind: MediaKind,
    val languageTag: LanguageTag?,
    val points: List<AnalyticsTrendPoint>,
) {
    init {
        require(displayTitle.isNotBlank())
    }
}

@Serializable
data class AnalyticsTitleTrends(
    val scale: AnalyticsBucketScale,
    val selection: AnalyticsTitleSeriesSelection,
    val series: List<AnalyticsTitleTrendSeries>,
)

@Serializable
data class AnalyticsVocabularyFirstSeenDay(
    val date: ImmersionLocalDate,
    val newWords: Long,
) {
    init {
        require(newWords >= 0)
    }
}

@Serializable
data class AnalyticsVocabularyFirstSeenPoint(
    val range: LocalDateRange,
    val newWords: Long,
    val cumulativeNewWords: Long,
) {
    init {
        require(newWords >= 0)
        require(cumulativeNewWords >= newWords)
    }
}

@Serializable
data class AnalyticsVocabularyFirstSeen(
    val scale: AnalyticsBucketScale,
    val points: List<AnalyticsVocabularyFirstSeenPoint>,
)

@Serializable
data class AnalyticsTitleCoverage(
    val legacySessionCount: Long = 0,
    val eventBackedSessionCount: Long = 0,
    val sourceUnitCount: Long = 0,
    val indexedSourceUnitCount: Long = 0,
    val textAvailableSourceUnitCount: Long = 0,
    val ocrSourceUnitCount: Long = 0,
    val ocrTextAvailableSourceUnitCount: Long = 0,
) {
    init {
        require(legacySessionCount >= 0)
        require(eventBackedSessionCount >= 0)
        require(sourceUnitCount >= 0)
        require(indexedSourceUnitCount in 0..sourceUnitCount)
        require(textAvailableSourceUnitCount in 0..sourceUnitCount)
        require(ocrSourceUnitCount in 0..sourceUnitCount)
        require(ocrTextAvailableSourceUnitCount in 0..ocrSourceUnitCount)
    }

    val indexingCompletion: Double?
        get() = ratio(indexedSourceUnitCount, sourceUnitCount)

    val textCoverage: Double?
        get() = ratio(textAvailableSourceUnitCount, sourceUnitCount)

    val ocrTextCoverage: Double?
        get() = ratio(ocrTextAvailableSourceUnitCount, ocrSourceUnitCount)

    val provenanceState: ProvenanceState
        get() = when {
            legacySessionCount > 0 && eventBackedSessionCount > 0 -> ProvenanceState.PARTIAL
            legacySessionCount > 0 -> ProvenanceState.LEGACY_AGGREGATE
            sourceUnitCount == 0L -> ProvenanceState.UNAVAILABLE
            textAvailableSourceUnitCount == 0L -> ProvenanceState.REMOVED
            textAvailableSourceUnitCount < sourceUnitCount -> ProvenanceState.PARTIAL
            else -> ProvenanceState.AVAILABLE
        }

    private fun ratio(numerator: Long, denominator: Long): Double? =
        if (denominator == 0L) null else numerator.toDouble() / denominator.toDouble()
}

@Serializable
enum class AnalyticsEstimateUnit {
    CHARACTERS,
    MEDIA_UNITS,
}

@Serializable
enum class AnalyticsEstimateConfidence {
    LOW,
    MEDIUM,
    HIGH,
}

@Serializable
data class AnalyticsTitleEstimate(
    val remainingAmount: Long,
    val unit: AnalyticsEstimateUnit,
    val estimatedActiveTimeMillis: Long,
    val confidence: AnalyticsEstimateConfidence,
    val qualifyingDayCount: Int,
) {
    init {
        require(remainingAmount >= 0)
        require(estimatedActiveTimeMillis >= 0)
        require(qualifyingDayCount >= 3)
    }
}

@Serializable
data class AnalyticsTitleDayHighlight(
    val date: ImmersionLocalDate,
    val value: Double,
) {
    init {
        require(value >= 0 && value.isFinite())
    }
}

@Serializable
data class AnalyticsTitleDayHighlights(
    val characters: AnalyticsTitleDayHighlight?,
    val activeTime: AnalyticsTitleDayHighlight?,
    val speed: AnalyticsTitleDayHighlight?,
)

@Serializable
data class AnalyticsTitleUnitCompletionDay(
    val date: ImmersionLocalDate,
    val completedUnits: Long,
) {
    init {
        require(completedUnits > 0)
    }
}

@Serializable
data class AnalyticsTitleUnitProgress(
    val identityAvailable: Boolean = false,
    val completedUnits: Long = 0,
    val identifiedCompletionEvents: Long = 0,
    val unidentifiedCompletionEvents: Long = 0,
    val firstCompletionsByDay: List<AnalyticsTitleUnitCompletionDay> = emptyList(),
) {
    init {
        require(completedUnits >= 0)
        require(identifiedCompletionEvents >= completedUnits)
        require(unidentifiedCompletionEvents >= 0)
        require(identityAvailable || completedUnits == 0L)
        require(identityAvailable || identifiedCompletionEvents == 0L)
        require(identityAvailable || unidentifiedCompletionEvents == 0L)
        require(firstCompletionsByDay.sumOf { it.completedUnits } == completedUnits)
        require(firstCompletionsByDay.zipWithNext().all { (first, second) -> first.date < second.date })
    }

    val hasTrustworthyIdentity: Boolean
        get() = identityAvailable && unidentifiedCompletionEvents == 0L
}

@Serializable
enum class AnalyticsTitleAcquisitionBucketSize(val characters: Long) {
    TEN_THOUSAND(10_000),
    TWENTY_FIVE_THOUSAND(25_000),
    FIFTY_THOUSAND(50_000),
    ONE_HUNDRED_THOUSAND(100_000),
}

@Serializable
data class AnalyticsTitleWordAcquisitionBucket(
    val index: Int,
    val startCharacter: Long,
    val endCharacterInclusive: Long,
    val newWords: Long,
    val cumulativeNewWords: Long,
) {
    init {
        require(index >= 0)
        require(startCharacter >= 0)
        require(endCharacterInclusive >= startCharacter)
        require(newWords >= 0)
        require(cumulativeNewWords >= newWords)
    }
}

@Serializable
data class AnalyticsTitleWordAcquisition(
    val titleId: TitleId,
    val bucketSize: AnalyticsTitleAcquisitionBucketSize,
    val totalGrossCharacters: Long,
    val buckets: List<AnalyticsTitleWordAcquisitionBucket>,
) {
    init {
        require(totalGrossCharacters >= 0)
        require(buckets.map { it.index } == buckets.indices.toList())
        require(
            buckets.zipWithNext().all { (first, second) ->
                first.endCharacterInclusive + 1 == second.startCharacter &&
                    first.cumulativeNewWords + second.newWords == second.cumulativeNewWords
            },
        )
        require(
            buckets.isEmpty() == (totalGrossCharacters == 0L) &&
                (
                    buckets.isEmpty() ||
                        buckets.last().endCharacterInclusive == totalGrossCharacters - 1
                    ),
        )
    }
}

@Serializable
data class AnalyticsTitleCompletedUnit(
    val titleId: TitleId,
    val completionUnitId: String,
    val firstCompletedAtEpochMillis: Long,
    val lastCompletedAtEpochMillis: Long,
    val firstCompletedDate: ImmersionLocalDate,
    val completionEventCount: Long,
) {
    init {
        require(completionUnitId.isNotBlank())
        require(firstCompletedAtEpochMillis >= 0)
        require(lastCompletedAtEpochMillis >= firstCompletedAtEpochMillis)
        require(completionEventCount > 0)
    }
}

@Serializable
data class AnalyticsTitleRow(
    val titleId: TitleId,
    val displayTitle: String,
    val mediaKind: MediaKind,
    val sourceKey: String,
    val profileId: String,
    val languageTag: LanguageTag?,
    val libraryId: Long?,
    val trackerId: String?,
    val mediaId: String?,
    val status: String?,
    val totalUnits: Long?,
    val totalCharacterEstimate: Long?,
    val deletedAtEpochMillis: Long?,
    val metrics: ReadingMetrics,
    val coverage: AnalyticsTitleCoverage,
    val firstActiveDate: ImmersionLocalDate,
    val lastActiveDate: ImmersionLocalDate,
    val activeDays: Int,
    val calendarSpanDays: Int,
    val averageCharactersPerActiveDay: Double,
    val averageActiveTimePerActiveDayMillis: Double,
    val dayHighlights: AnalyticsTitleDayHighlights,
    val unitProgress: AnalyticsTitleUnitProgress,
    val estimate: AnalyticsTitleEstimate?,
    val speedRankingEligible: Boolean,
    val progress: Double?,
    val completed: Boolean?,
) {
    init {
        require(activeDays > 0)
        require(calendarSpanDays >= activeDays)
        require(averageCharactersPerActiveDay >= 0 && averageCharactersPerActiveDay.isFinite())
        require(
            averageActiveTimePerActiveDayMillis >= 0 &&
                averageActiveTimePerActiveDayMillis.isFinite(),
        )
    }
}

@Serializable
data class AnalyticsTitleMetadata(
    val titleId: TitleId,
    val displayTitle: String,
    val mediaKind: MediaKind,
    val sourceKey: String,
    val profileId: String,
    val languageTag: LanguageTag?,
    val libraryId: Long?,
    val trackerId: String?,
    val mediaId: String?,
    val status: String?,
    val totalUnits: Long?,
    val totalCharacterEstimate: Long?,
    val completed: Boolean?,
    val deletedAtEpochMillis: Long?,
) {
    init {
        require(displayTitle.isNotBlank())
        require(sourceKey.isNotBlank())
        require(totalUnits == null || totalUnits >= 0)
        require(totalCharacterEstimate == null || totalCharacterEstimate >= 0)
        require(deletedAtEpochMillis == null || deletedAtEpochMillis >= 0)
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
    val jlptLevel: Int? = null,
    val gradeLevel: Int? = null,
    val script: VocabularyScript = VocabularyScript.OTHER,
    val category: VocabularyCategory = VocabularyCategory.OTHER,
    val excluded: Boolean = false,
) {
    init {
        require(id.isNotBlank())
        require(headword.isNotBlank())
        require(occurrenceCount >= 0)
        require(titleCount >= 0)
        require(firstSeenAtEpochMillis >= 0)
        require(lastSeenAtEpochMillis >= firstSeenAtEpochMillis)
        require(frequencyRank == null || frequencyRank > 0)
        require(jlptLevel == null || jlptLevel > 0)
        require(gradeLevel == null || gradeLevel > 0)
    }
}

@Serializable
data class AnalyticsCharacterRow(
    val codePoint: UnicodeCodePoint,
    val rendered: String,
    val unicodeName: String?,
    val unicodeCategory: String,
    val unicodeScript: String,
    val japaneseReadings: String?,
    val occurrenceCount: Long,
    val sourceUnitCount: Long,
    val wordCount: Long,
    val titleCount: Long,
    val firstSeenAtEpochMillis: Long,
    val lastSeenAtEpochMillis: Long,
    val frequencyRank: Long?,
    val jlptLevel: Int?,
    val gradeLevel: Int?,
    val maturity: MaturityTier,
    val priorityScore: Double,
) {
    init {
        require(rendered.isNotBlank())
        require(unicodeCategory.isNotBlank())
        require(unicodeScript.isNotBlank())
        require(occurrenceCount >= 0)
        require(sourceUnitCount >= 0)
        require(wordCount >= 0)
        require(titleCount >= 0)
        require(firstSeenAtEpochMillis >= 0)
        require(lastSeenAtEpochMillis >= firstSeenAtEpochMillis)
        require(frequencyRank == null || frequencyRank > 0)
        require(jlptLevel == null || jlptLevel > 0)
        require(gradeLevel == null || gradeLevel > 0)
        require(priorityScore >= 0 && priorityScore.isFinite())
    }
}

@Serializable
data class AnalyticsSourceOccurrence(
    val sourceUnitId: SourceUnitId,
    val titleId: TitleId,
    val displayTitle: String,
    val sessionId: SessionId,
    val mediaKind: MediaKind,
    val sourceKind: SourceKind,
    val canonicalLocator: String,
    val occurredAtEpochMillis: Long,
    val excerpt: String?,
    val rawTextAvailable: Boolean,
) {
    init {
        require(displayTitle.isNotBlank())
        require(canonicalLocator.isNotBlank())
        require(occurredAtEpochMillis >= 0)
    }
}

@Serializable
data class AnalyticsTimelineBucket(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val eventCount: Long,
    val activeDurationMillis: Long,
    val grossCharacters: Long,
    val uniqueSourceCharacters: Long,
    val netCharacters: Long,
    val lookupCount: Long,
    val cardsCreated: Long,
    val cardsUpdated: Long,
    val eventTypes: Set<EventType>,
) {
    init {
        require(startEpochMillis >= 0)
        require(endEpochMillis >= startEpochMillis)
        require(eventCount >= 0)
        require(activeDurationMillis >= 0)
        require(grossCharacters >= 0)
        require(uniqueSourceCharacters >= 0)
        require(lookupCount >= 0)
        require(cardsCreated >= 0)
        require(cardsUpdated >= 0)
    }
}

@Serializable
data class AnalyticsSessionDetail(
    val session: ImmersionSession,
    val displayTitle: String,
    val timeline: List<AnalyticsTimelineBucket>,
    val sources: List<AnalyticsSourceOccurrence>,
)

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
    val targetToDate: Double = target,
    val requiredPacePerActiveDay: Double? = null,
    val rollingSevenDayPace: Double? = null,
    val rollingThirtyDayPace: Double? = null,
    val currentStreakDays: Int = 0,
    val longestStreakDays: Int = 0,
    val isRestDay: Boolean = false,
    val forecastConfidence: CapabilityState = CapabilityState.UNAVAILABLE,
)

@Serializable
enum class AnalyticsAnkiReport {
    INVENTORY,
    CARD_ACTIVITY,
    SOURCE_ATTRIBUTION,
    READING_TO_CARD_LAG,
    CARD_TO_MATURITY_LAG,
    WEEKLY_FLOW,
    REVIEW_HISTORY,
    RETENTION,
    REVIEW_TIME,
}

@Serializable
enum class AnalyticsAnkiCapabilityReason {
    AVAILABLE,
    NO_CURRENT_INVENTORY,
    STALE_INVENTORY,
    PARTIAL_INVENTORY,
    NO_LINKED_SAMPLE,
    INSUFFICIENT_SAMPLE,
    PROVIDER_UNSUPPORTED,
    DATA_NOT_COLLECTED,
}

@Serializable
data class AnalyticsAnkiReportCapability(
    val report: AnalyticsAnkiReport,
    val state: CapabilityState,
    val reason: AnalyticsAnkiCapabilityReason,
)

@Serializable
data class AnalyticsAnkiWeeklyImpact(
    val weekStart: ImmersionLocalDate,
    val weekEndInclusive: ImmersionLocalDate,
    val partial: Boolean,
    val activeDurationMillis: Long,
    val grossCharacters: Long,
    val cardsCreated: Long,
    val cardsUpdated: Long,
    val linkedOperations: Long,
    val unattributedOperations: Long,
    val sameWeekReadingToCardOperations: Long,
    val maturedOperations: Long,
    val meanReadingToCardLagMillis: Long?,
    val meanCardToMaturityLagMillis: Long?,
) {
    init {
        require(weekEndInclusive.epochDay >= weekStart.epochDay)
        require(activeDurationMillis >= 0)
        require(grossCharacters >= 0)
        require(cardsCreated >= 0)
        require(cardsUpdated >= 0)
        require(linkedOperations >= 0)
        require(unattributedOperations >= 0)
        require(sameWeekReadingToCardOperations in 0..linkedOperations)
        require(maturedOperations in 0..linkedOperations)
        require(meanReadingToCardLagMillis == null || meanReadingToCardLagMillis >= 0)
        require(meanCardToMaturityLagMillis == null || meanCardToMaturityLagMillis >= 0)
    }
}

@Serializable
data class AnalyticsAnkiTitleImpact(
    val titleId: TitleId?,
    val displayTitle: String?,
    val mediaKind: MediaKind?,
    val activeDurationMillis: Long,
    val grossCharacters: Long,
    val cardsCreated: Long,
    val cardsUpdated: Long,
    val operationCount: Long,
) {
    init {
        require(displayTitle == null || displayTitle.isNotBlank())
        require((titleId == null) == (mediaKind == null))
        require(activeDurationMillis >= 0)
        require(grossCharacters >= 0)
        require(cardsCreated >= 0)
        require(cardsUpdated >= 0)
        require(operationCount >= 0)
    }

    fun cardsPerTenThousandGrossCharacters(): Double? =
        grossCharacters.takeIf { it > 0 }?.let {
            cardsCreated * 10_000.0 / it
        }
}

@Serializable
data class AnalyticsAnkiSummary(
    val snapshot: ImmersionAnkiSnapshot?,
    val wordCoverageEncountered: Long,
    val wordCoverageKnown: Long,
    val characterCoverageEncountered: Long,
    val characterCoverageKnown: Long,
    val reviewHistoryAvailable: Boolean,
    val maturityDistribution: Map<MaturityTier, Long> = emptyMap(),
    val cardsCreated: Long = 0,
    val cardsUpdated: Long = 0,
    val linkedOperationCount: Long = 0,
    val unattributedOperationCount: Long = 0,
    val meanReadingToCardLagMillis: Long? = null,
    val missingHighFrequencyWords: List<AnalyticsWordRow> = emptyList(),
    val missingHighFrequencyCharacters: List<AnalyticsCharacterRow> = emptyList(),
    val capabilities: List<AnalyticsAnkiReportCapability> = emptyList(),
    val weeklyImpact: List<AnalyticsAnkiWeeklyImpact> = emptyList(),
    val titleImpact: List<AnalyticsAnkiTitleImpact> = emptyList(),
    val generatedAtEpochMillis: Long? = null,
    val minimumComparisonSampleSize: Int = 20,
) {
    init {
        require(wordCoverageEncountered >= 0)
        require(wordCoverageKnown in 0..wordCoverageEncountered)
        require(characterCoverageEncountered >= 0)
        require(characterCoverageKnown in 0..characterCoverageEncountered)
        require(maturityDistribution.values.all { it >= 0 })
        require(cardsCreated >= 0)
        require(cardsUpdated >= 0)
        require(linkedOperationCount >= 0)
        require(unattributedOperationCount >= 0)
        require(meanReadingToCardLagMillis == null || meanReadingToCardLagMillis >= 0)
        require(capabilities.distinctBy(AnalyticsAnkiReportCapability::report).size == capabilities.size)
        require(
            weeklyImpact.zipWithNext().all { (first, second) ->
                first.weekStart.epochDay < second.weekStart.epochDay
            },
        )
        require(titleImpact.distinctBy { it.titleId to it.mediaKind }.size == titleImpact.size)
        require(generatedAtEpochMillis == null || generatedAtEpochMillis >= 0)
        require(minimumComparisonSampleSize > 0)
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
