package eu.kanade.presentation.more.stats

import androidx.compose.runtime.Immutable
import chimahon.anki.AnkiProfile
import tachiyomi.domain.immersion.model.AnalyticsAnkiSummary
import tachiyomi.domain.immersion.model.AnalyticsBucketScale
import tachiyomi.domain.immersion.model.AnalyticsCharacterRow
import tachiyomi.domain.immersion.model.AnalyticsGoalProgress
import tachiyomi.domain.immersion.model.AnalyticsOverview
import tachiyomi.domain.immersion.model.AnalyticsPage
import tachiyomi.domain.immersion.model.AnalyticsResult
import tachiyomi.domain.immersion.model.AnalyticsSessionDetail
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.AnalyticsSourceOccurrence
import tachiyomi.domain.immersion.model.AnalyticsTitleRow
import tachiyomi.domain.immersion.model.AnalyticsTrends
import tachiyomi.domain.immersion.model.AnalyticsWordRow
import tachiyomi.domain.immersion.model.CharacterMetric
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.SessionPage

enum class StatsTab {
    OVERVIEW,
    ACTIVITY,
    TITLES,
    VOCABULARY,
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

@Immutable
data class StatsSections(
    val overview: StatsLoadable<AnalyticsResult<AnalyticsOverview>> = StatsLoadable(),
    val trends: StatsLoadable<AnalyticsResult<AnalyticsTrends>> = StatsLoadable(),
    val titles: StatsLoadable<AnalyticsResult<List<AnalyticsTitleRow>>> = StatsLoadable(),
    val vocabulary: StatsLoadable<AnalyticsResult<AnalyticsPage<AnalyticsWordRow>>> = StatsLoadable(),
    val characters: StatsLoadable<AnalyticsResult<AnalyticsPage<AnalyticsCharacterRow>>> = StatsLoadable(),
    val sessions: StatsLoadable<AnalyticsResult<SessionPage>> = StatsLoadable(),
    val goals: StatsLoadable<AnalyticsResult<List<AnalyticsGoalProgress>>> = StatsLoadable(),
    val anki: StatsLoadable<AnalyticsResult<AnalyticsAnkiSummary>> = StatsLoadable(),
)

@Immutable
data class StatsSelection(
    val title: AnalyticsTitleRow? = null,
    val word: AnalyticsWordRow? = null,
    val character: AnalyticsCharacterRow? = null,
    val session: ImmersionSession? = null,
)

@Immutable
data class StatsDetails(
    val wordOccurrences: StatsLoadable<AnalyticsResult<AnalyticsPage<AnalyticsSourceOccurrence>>> =
        StatsLoadable(),
    val characterOccurrences: StatsLoadable<AnalyticsResult<AnalyticsPage<AnalyticsSourceOccurrence>>> =
        StatsLoadable(),
    val session: StatsLoadable<AnalyticsResult<AnalyticsSessionDetail?>> = StatsLoadable(),
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
        val trendScale: AnalyticsBucketScale = AnalyticsBucketScale.DAY,
        val titleSort: AnalyticsSort = AnalyticsSort.MOST_TIME,
        val vocabularySort: AnalyticsSort = AnalyticsSort.MOST_OCCURRENCES,
        val characterSort: AnalyticsSort = AnalyticsSort.MOST_OCCURRENCES,
        val vocabularySearch: String = "",
        val characterSearch: String = "",
        val titleSearch: String = "",
        val sourceSearch: String = "",
        val selection: StatsSelection = StatsSelection(),
        val details: StatsDetails = StatsDetails(),
    ) : StatsScreenState {
        val isRefreshing: Boolean
            get() = sections.overview.refreshing ||
                sections.trends.refreshing ||
                sections.titles.refreshing ||
                sections.vocabulary.refreshing ||
                sections.characters.refreshing ||
                sections.sessions.refreshing ||
                sections.goals.refreshing ||
                sections.anki.refreshing
    }
}
