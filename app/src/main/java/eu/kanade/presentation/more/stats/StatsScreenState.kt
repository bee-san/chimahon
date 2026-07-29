package eu.kanade.presentation.more.stats

import androidx.compose.runtime.Immutable
import chimahon.anki.AnkiProfile
import eu.kanade.tachiyomi.ui.stats.StatsTitlePresentationMetadata
import tachiyomi.domain.immersion.model.AnalyticsAnkiSummary
import tachiyomi.domain.immersion.model.AnalyticsBucketScale
import tachiyomi.domain.immersion.model.AnalyticsCharacterFilter
import tachiyomi.domain.immersion.model.AnalyticsCharacterRow
import tachiyomi.domain.immersion.model.AnalyticsCharacterSummary
import tachiyomi.domain.immersion.model.AnalyticsGoalProgress
import tachiyomi.domain.immersion.model.AnalyticsOverview
import tachiyomi.domain.immersion.model.AnalyticsPage
import tachiyomi.domain.immersion.model.AnalyticsResult
import tachiyomi.domain.immersion.model.AnalyticsSessionDetail
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.AnalyticsSourceOccurrence
import tachiyomi.domain.immersion.model.AnalyticsTemporalActivity
import tachiyomi.domain.immersion.model.AnalyticsTitleCompletedUnit
import tachiyomi.domain.immersion.model.AnalyticsTitleFilter
import tachiyomi.domain.immersion.model.AnalyticsTitleRow
import tachiyomi.domain.immersion.model.AnalyticsTitleSeriesSelection
import tachiyomi.domain.immersion.model.AnalyticsTitleSort
import tachiyomi.domain.immersion.model.AnalyticsTitleTrends
import tachiyomi.domain.immersion.model.AnalyticsTrends
import tachiyomi.domain.immersion.model.CharacterMetric
import tachiyomi.domain.immersion.model.ImmersionAnkiItem
import tachiyomi.domain.immersion.model.ImmersionDeletionPreview
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.ImmersionTitleMutationPreview
import tachiyomi.domain.immersion.model.MaturityTier
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.ProvenanceState
import tachiyomi.domain.immersion.model.SessionPage
import tachiyomi.domain.immersion.model.TitleId

enum class StatsTab {
    OVERVIEW,
    ACTIVITY,
    TITLES,
    CHARACTERS,
    SESSIONS,
    GOALS,
    ANKI,
}

enum class StatsRangePreset {
    TODAY,
    SEVEN_DAYS,
    THIRTY_DAYS,
    NINETY_DAYS,
    YEAR,
    ALL,
    CUSTOM,
}

enum class StatsTrendMetric {
    ACTIVE_TIME,
    CHARACTERS,
    SESSIONS,
    CARDS,
    NEW_CHARACTERS,
}

enum class StatsCharacterGridMode {
    FREQUENCY,
    FIRST_SEEN,
    MATURITY,
    METADATA,
    PRIORITY,
}

enum class StatsCharacterLayout {
    GRID,
    LIST,
}

enum class StatsSection {
    OVERVIEW,
    HEATMAP,
    TRENDS,
    TEMPORAL_ACTIVITY,
    TITLE_TRENDS,
    TITLES,
    CHARACTERS,
    CHARACTER_SUMMARY,
    SESSIONS,
    GOALS,
    ANKI,
}

@Immutable
data class StatsFilterState(
    val rangePreset: StatsRangePreset = StatsRangePreset.TODAY,
    val periodOffset: Int = 0,
    val customStart: ImmersionLocalDate? = null,
    val customEnd: ImmersionLocalDate? = null,
    val mediaKind: MediaKind? = null,
    val profileId: String? = null,
    val characterMetric: CharacterMetric = CharacterMetric.GROSS,
    val includeLegacy: Boolean = true,
    val includeRereadsAndReplays: Boolean = true,
    val maturityTiers: Set<MaturityTier> = emptySet(),
    val provenanceStates: Set<ProvenanceState> = emptySet(),
    val titleId: String? = null,
)

@Immutable
data class StatsLoadable<T>(
    val value: T? = null,
    val refreshing: Boolean = false,
    val error: Boolean = false,
) {
    fun refreshing(): StatsLoadable<T> = copy(refreshing = true, error = false)
}

internal enum class StatsCollectionLoadState(
    val showLoading: Boolean,
    val showError: Boolean,
    val showContent: Boolean,
    val showEmpty: Boolean,
) {
    LOADING(showLoading = true, showError = false, showContent = false, showEmpty = false),
    EMPTY(showLoading = false, showError = false, showContent = false, showEmpty = true),
    CONTENT(showLoading = false, showError = false, showContent = true, showEmpty = false),
    REFRESHING_CONTENT(showLoading = true, showError = false, showContent = true, showEmpty = false),
    ERROR(showLoading = false, showError = true, showContent = false, showEmpty = false),
    STALE_ERROR(showLoading = false, showError = true, showContent = true, showEmpty = false),
}

internal fun StatsLoadable<*>.collectionLoadState(hasContent: Boolean): StatsCollectionLoadState =
    when {
        hasContent && error -> StatsCollectionLoadState.STALE_ERROR
        hasContent && refreshing -> StatsCollectionLoadState.REFRESHING_CONTENT
        hasContent -> StatsCollectionLoadState.CONTENT
        error -> StatsCollectionLoadState.ERROR
        refreshing -> StatsCollectionLoadState.LOADING
        else -> StatsCollectionLoadState.EMPTY
    }

@Immutable
data class StatsSections(
    val overview: StatsLoadable<AnalyticsResult<AnalyticsOverview>> = StatsLoadable(),
    val heatmap: StatsLoadable<AnalyticsResult<AnalyticsTrends>> = StatsLoadable(),
    val trends: StatsLoadable<AnalyticsResult<AnalyticsTrends>> = StatsLoadable(),
    val temporalActivity: StatsLoadable<AnalyticsResult<AnalyticsTemporalActivity>> = StatsLoadable(),
    val titleTrends: StatsLoadable<AnalyticsResult<AnalyticsTitleTrends>> = StatsLoadable(),
    val titles: StatsLoadable<AnalyticsResult<AnalyticsPage<AnalyticsTitleRow>>> = StatsLoadable(),
    val characters: StatsLoadable<AnalyticsResult<AnalyticsPage<AnalyticsCharacterRow>>> = StatsLoadable(),
    val characterSummary: StatsLoadable<AnalyticsResult<AnalyticsCharacterSummary>> = StatsLoadable(),
    val sessions: StatsLoadable<AnalyticsResult<SessionPage>> = StatsLoadable(),
    val goals: StatsLoadable<AnalyticsResult<List<AnalyticsGoalProgress>>> = StatsLoadable(),
    val anki: StatsLoadable<AnalyticsResult<AnalyticsAnkiSummary>> = StatsLoadable(),
) {
    internal fun isRefreshing(section: StatsSection): Boolean = when (section) {
        StatsSection.OVERVIEW -> overview.refreshing
        StatsSection.HEATMAP -> heatmap.refreshing
        StatsSection.TRENDS -> trends.refreshing
        StatsSection.TEMPORAL_ACTIVITY -> temporalActivity.refreshing
        StatsSection.TITLE_TRENDS -> titleTrends.refreshing
        StatsSection.TITLES -> titles.refreshing
        StatsSection.CHARACTERS -> characters.refreshing
        StatsSection.CHARACTER_SUMMARY -> characterSummary.refreshing
        StatsSection.SESSIONS -> sessions.refreshing
        StatsSection.GOALS -> goals.refreshing
        StatsSection.ANKI -> anki.refreshing
    }

    internal fun retrying(section: StatsSection): StatsSections = when (section) {
        StatsSection.OVERVIEW -> copy(overview = overview.refreshing())
        StatsSection.HEATMAP -> copy(heatmap = heatmap.refreshing())
        StatsSection.TRENDS -> copy(trends = trends.refreshing())
        StatsSection.TEMPORAL_ACTIVITY -> copy(temporalActivity = temporalActivity.refreshing())
        StatsSection.TITLE_TRENDS -> copy(titleTrends = titleTrends.refreshing())
        StatsSection.TITLES -> copy(titles = titles.refreshing())
        StatsSection.CHARACTERS -> copy(characters = characters.refreshing())
        StatsSection.CHARACTER_SUMMARY -> copy(characterSummary = characterSummary.refreshing())
        StatsSection.SESSIONS -> copy(sessions = sessions.refreshing())
        StatsSection.GOALS -> copy(goals = goals.refreshing())
        StatsSection.ANKI -> copy(anki = anki.refreshing())
    }
}

@Immutable
data class StatsSelection(
    val title: AnalyticsTitleRow? = null,
    val character: AnalyticsCharacterRow? = null,
    val session: ImmersionSession? = null,
)

@Immutable
data class StatsDetails(
    val titleCaptureExcluded: StatsLoadable<Boolean> = StatsLoadable(),
    val titleMutationInProgress: Boolean = false,
    val titleMutationError: Boolean = false,
    val titleTrends: StatsLoadable<AnalyticsResult<AnalyticsTrends>> = StatsLoadable(),
    val titleSessions: StatsLoadable<AnalyticsResult<SessionPage>> = StatsLoadable(),
    val titleCompletedUnits: StatsLoadable<AnalyticsResult<AnalyticsPage<AnalyticsTitleCompletedUnit>>> =
        StatsLoadable(),
    val titleSources: StatsLoadable<AnalyticsResult<AnalyticsPage<AnalyticsSourceOccurrence>>> =
        StatsLoadable(),
    val characterOccurrences: StatsLoadable<AnalyticsResult<AnalyticsPage<AnalyticsSourceOccurrence>>> =
        StatsLoadable(),
    val characterAnkiItems: StatsLoadable<List<ImmersionAnkiItem>> = StatsLoadable(),
    val session: StatsLoadable<AnalyticsResult<AnalyticsSessionDetail?>> = StatsLoadable(),
    val sessionDeletionPreview: StatsLoadable<ImmersionDeletionPreview> = StatsLoadable(),
    val sessionRelinkPreview: StatsLoadable<ImmersionTitleMutationPreview> = StatsLoadable(),
    val sourceSearch: StatsLoadable<AnalyticsResult<AnalyticsPage<AnalyticsSourceOccurrence>>> =
        StatsLoadable(),
)

sealed interface StatsScreenState {
    @Immutable
    data object Loading : StatsScreenState

    @Immutable
    data class Success(
        val filter: StatsFilterState,
        val selectedTab: StatsTab,
        val profiles: List<AnkiProfile>,
        val sections: StatsSections,
        val goalsEnabled: Boolean = true,
        val ankiEnabled: Boolean = true,
        val trendScale: AnalyticsBucketScale = AnalyticsBucketScale.DAY,
        val trendMetric: StatsTrendMetric = StatsTrendMetric.CHARACTERS,
        val titleTrendSelection: AnalyticsTitleSeriesSelection =
            AnalyticsTitleSeriesSelection.TOP_CHARACTERS,
        val titleSort: AnalyticsTitleSort = AnalyticsTitleSort.MOST_TIME,
        val titleFilter: AnalyticsTitleFilter = AnalyticsTitleFilter(),
        val characterSort: AnalyticsSort = AnalyticsSort.MOST_OCCURRENCES,
        val characterFilter: AnalyticsCharacterFilter = AnalyticsCharacterFilter(),
        val characterGridMode: StatsCharacterGridMode = StatsCharacterGridMode.FREQUENCY,
        val characterLayout: StatsCharacterLayout = StatsCharacterLayout.GRID,
        val characterCoverageTargetPercent: Int = 90,
        val selectedCharacterCodePoints: Set<Int> = emptySet(),
        val characterSearch: String = "",
        val titleSearch: String = "",
        val sourceSearch: String = "",
        val titleOptions: List<AnalyticsTitleRow> = emptyList(),
        val titleMetadata: Map<TitleId, StatsTitlePresentationMetadata> = emptyMap(),
        val selection: StatsSelection = StatsSelection(),
        val details: StatsDetails = StatsDetails(),
    ) : StatsScreenState {
        val isRefreshing: Boolean
            get() = sections.overview.refreshing ||
                sections.heatmap.refreshing ||
                sections.trends.refreshing ||
                sections.temporalActivity.refreshing ||
                sections.titleTrends.refreshing ||
                sections.titles.refreshing ||
                sections.characters.refreshing ||
                sections.characterSummary.refreshing ||
                sections.sessions.refreshing ||
                sections.goals.refreshing ||
                sections.anki.refreshing
    }
}
