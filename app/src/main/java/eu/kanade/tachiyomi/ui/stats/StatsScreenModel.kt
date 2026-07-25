package eu.kanade.tachiyomi.ui.stats

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.presentation.more.stats.StatsFilterState
import eu.kanade.presentation.more.stats.StatsLoadable
import eu.kanade.presentation.more.stats.StatsRangePreset
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.more.stats.StatsSections
import eu.kanade.presentation.more.stats.StatsSelection
import eu.kanade.presentation.more.stats.StatsTab
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.immersion.model.AnalyticsBucketScale
import tachiyomi.domain.immersion.model.AnalyticsPage
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.CharacterMetric
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.LocalDateRange
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.SessionPage
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.repository.ImmersionMaintenanceRepository
import tachiyomi.domain.immersion.service.ImmersionAnalyticsService
import tachiyomi.domain.immersion.service.ImmersionStatsPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class StatsScreenModel(
    titleId: String? = null,
    private val analyticsService: ImmersionAnalyticsService = Injekt.get(),
    private val preferences: ImmersionStatsPreferences = Injekt.get(),
    private val dictionaryPreferences: DictionaryPreferences = Injekt.get(),
    private val maintenanceRepository: ImmersionMaintenanceRepository = Injekt.get(),
    private val today: () -> LocalDate = LocalDate::now,
) : StateScreenModel<StatsScreenState>(StatsScreenState.Loading) {

    private val fixedTitleId = titleId?.let { raw ->
        runCatching { TitleId(raw) }.getOrNull()
    }
    private val refreshGeneration = AtomicLong()
    private var refreshJob: Job? = null
    private var vocabularySearchJob: Job? = null
    private var characterSearchJob: Job? = null
    private var sourceSearchJob: Job? = null

    init {
        val profiles = dictionaryPreferences.profileStore.getProfiles()
        val profileId = preferences.dashboardProfileId().get().takeIf { selected ->
            selected.isNotBlank() && profiles.any { it.id == selected }
        }
        val initialFilter = StatsFilterState(
            rangePreset = preferences.dashboardRangePreset().get().enumOrDefault(StatsRangePreset.TODAY),
            mediaKind = preferences.dashboardMediaKind().get()
                .takeIf(String::isNotBlank)
                ?.enumOrNull(),
            profileId = profileId,
            characterMetric = preferences.dashboardCharacterMetric().get(),
            includeLegacy = preferences.includeLegacyAggregates().get(),
            includeRereadsAndReplays = preferences.dashboardIncludeRereads().get(),
            titleId = fixedTitleId?.value,
        )
        mutableState.value = StatsScreenState.Success(
            filter = initialFilter,
            selectedTab = if (fixedTitleId != null) {
                StatsTab.TITLES
            } else {
                preferences.dashboardSelectedTab().get().enumOrDefault(StatsTab.OVERVIEW)
            },
            profiles = profiles,
            sections = StatsSections(),
            trendScale = preferences.dashboardTrendScale().get(),
            titleSort = preferences.dashboardTitleSort().get(),
            vocabularySort = preferences.dashboardVocabularySort().get(),
            characterSort = preferences.dashboardCharacterSort().get(),
        )
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        val generation = refreshGeneration.incrementAndGet()
        val current = successState() ?: return
        mutableState.value = current.copy(
            sections = current.sections.refreshing(),
        )
        refreshJob = screenModelScope.launch {
            val filter = current.filter.toDomainFilter(today())
            coroutineScope {
                listOf(
                    async { loadOverview(generation, filter) },
                    async { loadTrends(generation, filter, current.trendScale) },
                    async { loadTitles(generation, filter, current.titleSort) },
                    async { loadVocabulary(generation, filter, current.vocabularySort) },
                    async { loadCharacters(generation, filter, current.characterSort) },
                    async { loadSessions(generation, filter) },
                    async { loadGoals(generation, filter) },
                    async { loadAnki(generation, filter) },
                ).awaitAll()
            }
        }
    }

    fun selectTab(tab: StatsTab) {
        preferences.dashboardSelectedTab().set(tab.name)
        updateSuccess { it.copy(selectedTab = tab, selection = StatsSelection()) }
    }

    fun selectRange(preset: StatsRangePreset) {
        preferences.dashboardRangePreset().set(preset.name)
        updateFilter { it.copy(rangePreset = preset, periodOffset = 0) }
    }

    fun movePeriod(offset: Int) {
        updateFilter { it.copy(periodOffset = offset.coerceAtMost(0)) }
    }

    fun setCustomRange(start: String, end: String): Boolean {
        val parsedStart = runCatching { ImmersionLocalDate.from(LocalDate.parse(start.trim())) }.getOrNull()
        val parsedEnd = runCatching { ImmersionLocalDate.from(LocalDate.parse(end.trim())) }.getOrNull()
        if (parsedStart == null || parsedEnd == null || parsedStart > parsedEnd) return false
        updateFilter {
            it.copy(
                rangePreset = StatsRangePreset.CUSTOM,
                periodOffset = 0,
                customStart = parsedStart,
                customEnd = parsedEnd,
            )
        }
        return true
    }

    fun selectMedia(mediaKind: MediaKind?) {
        preferences.dashboardMediaKind().set(mediaKind?.name.orEmpty())
        updateFilter { it.copy(mediaKind = mediaKind) }
    }

    fun selectProfile(profileId: String?) {
        preferences.dashboardProfileId().set(profileId.orEmpty())
        updateFilter { it.copy(profileId = profileId) }
    }

    fun selectCharacterMetric(metric: CharacterMetric) {
        preferences.dashboardCharacterMetric().set(metric)
        updateFilter { it.copy(characterMetric = metric) }
    }

    fun setIncludeLegacy(include: Boolean) {
        preferences.includeLegacyAggregates().set(include)
        updateFilter { it.copy(includeLegacy = include) }
    }

    fun setIncludeRereads(include: Boolean) {
        preferences.dashboardIncludeRereads().set(include)
        updateFilter { it.copy(includeRereadsAndReplays = include) }
    }

    fun selectTrendScale(scale: AnalyticsBucketScale) {
        preferences.dashboardTrendScale().set(scale)
        updateSuccess { it.copy(trendScale = scale) }
        refresh()
    }

    fun selectTitleSort(sort: AnalyticsSort) {
        preferences.dashboardTitleSort().set(sort)
        updateSuccess { it.copy(titleSort = sort) }
        refresh()
    }

    fun selectVocabularySort(sort: AnalyticsSort) {
        preferences.dashboardVocabularySort().set(sort)
        updateSuccess { it.copy(vocabularySort = sort) }
        refresh()
    }

    fun selectCharacterSort(sort: AnalyticsSort) {
        preferences.dashboardCharacterSort().set(sort)
        updateSuccess { it.copy(characterSort = sort) }
        refresh()
    }

    fun searchTitles(query: String) {
        updateSuccess { it.copy(titleSearch = query) }
    }

    fun searchVocabulary(query: String) {
        updateSuccess { it.copy(vocabularySearch = query) }
        vocabularySearchJob?.cancel()
        vocabularySearchJob = screenModelScope.launch {
            delay(250)
            refresh()
        }
    }

    fun searchCharacters(query: String) {
        updateSuccess { it.copy(characterSearch = query) }
        characterSearchJob?.cancel()
        characterSearchJob = screenModelScope.launch {
            delay(250)
            refresh()
        }
    }

    fun selectTitle(title: tachiyomi.domain.immersion.model.AnalyticsTitleRow?) {
        updateSuccess { it.copy(selection = it.selection.copy(title = title)) }
    }

    fun selectWord(word: tachiyomi.domain.immersion.model.AnalyticsWordRow?) {
        updateSuccess {
            it.copy(
                selection = it.selection.copy(word = word),
                details = it.details.copy(
                    wordOccurrences = if (word == null) {
                        StatsLoadable()
                    } else {
                        it.details.wordOccurrences.refreshing()
                    },
                ),
            )
        }
        if (word != null) {
            screenModelScope.launch {
                val state = successState() ?: return@launch
                val result = runCatching {
                    analyticsService.wordOccurrences(
                        state.filter.toDomainFilter(today()),
                        word.id,
                        0,
                        DETAIL_PAGE_SIZE,
                    )
                }
                updateSuccess {
                    it.copy(
                        details = it.details.copy(
                            wordOccurrences = result.fold(
                                onSuccess = { value -> StatsLoadable(value) },
                                onFailure = { StatsLoadable(error = true) },
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun selectCharacter(character: tachiyomi.domain.immersion.model.AnalyticsCharacterRow?) {
        updateSuccess {
            it.copy(
                selection = it.selection.copy(character = character),
                details = it.details.copy(
                    characterOccurrences = if (character == null) {
                        StatsLoadable()
                    } else {
                        it.details.characterOccurrences.refreshing()
                    },
                ),
            )
        }
        if (character != null) {
            screenModelScope.launch {
                val state = successState() ?: return@launch
                val result = runCatching {
                    analyticsService.characterOccurrences(
                        state.filter.toDomainFilter(today()),
                        character.codePoint,
                        0,
                        DETAIL_PAGE_SIZE,
                    )
                }
                updateSuccess {
                    it.copy(
                        details = it.details.copy(
                            characterOccurrences = result.fold(
                                onSuccess = { value -> StatsLoadable(value) },
                                onFailure = { StatsLoadable(error = true) },
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun selectSession(session: tachiyomi.domain.immersion.model.ImmersionSession?) {
        updateSuccess {
            it.copy(
                selection = it.selection.copy(session = session),
                details = it.details.copy(
                    session = if (session == null) {
                        StatsLoadable()
                    } else {
                        it.details.session.refreshing()
                    },
                ),
            )
        }
        if (session != null) {
            screenModelScope.launch {
                val state = successState() ?: return@launch
                val result = runCatching {
                    analyticsService.sessionDetail(
                        state.filter.toDomainFilter(today()),
                        session.id,
                    )
                }
                updateSuccess {
                    it.copy(
                        details = it.details.copy(
                            session = result.fold(
                                onSuccess = { value -> StatsLoadable(value) },
                                onFailure = { StatsLoadable(error = true) },
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun deleteSession(session: tachiyomi.domain.immersion.model.ImmersionSession) {
        screenModelScope.launch {
            if (!maintenanceRepository.deleteSession(session.id)) return@launch
            analyticsService.repairDirtyRollups(366)
            selectSession(null)
            refresh()
        }
    }

    fun searchSources(query: String) {
        updateSuccess { it.copy(sourceSearch = query) }
        sourceSearchJob?.cancel()
        if (query.isBlank()) {
            updateSuccess { it.copy(details = it.details.copy(sourceSearch = StatsLoadable())) }
            return
        }
        sourceSearchJob = screenModelScope.launch {
            delay(250)
            val state = successState() ?: return@launch
            updateSuccess {
                it.copy(details = it.details.copy(sourceSearch = it.details.sourceSearch.refreshing()))
            }
            val result = runCatching {
                analyticsService.sourceSearch(
                    state.filter.toDomainFilter(today()),
                    query,
                    0,
                    DETAIL_PAGE_SIZE,
                )
            }
            updateSuccess {
                it.copy(
                    details = it.details.copy(
                        sourceSearch = result.fold(
                            onSuccess = { value -> StatsLoadable(value) },
                            onFailure = { StatsLoadable(error = true) },
                        ),
                    ),
                )
            }
        }
    }

    fun loadMoreVocabulary() {
        val state = successState() ?: return
        val currentResult = state.sections.vocabulary.value ?: return
        val offset = currentResult.value.nextOffset ?: return
        screenModelScope.launch {
            val result = runCatching {
                analyticsService.vocabulary(
                    state.filter.toDomainFilter(today()),
                    state.vocabularySort,
                    offset,
                    PAGE_SIZE,
                    state.vocabularySearch.takeIf(String::isNotBlank),
                )
            }
            updateSuccess { latest ->
                if (result.isFailure) {
                    latest.copy(sections = latest.sections.copy(vocabulary = latest.sections.vocabulary.copy(error = true)))
                } else {
                    val next = result.getOrThrow()
                    val mergedPage = AnalyticsPage(
                        items = currentResult.value.items + next.value.items,
                        nextOffset = next.value.nextOffset,
                    )
                    latest.copy(
                        sections = latest.sections.copy(
                            vocabulary = StatsLoadable(next.copy(value = mergedPage)),
                        ),
                    )
                }
            }
        }
    }

    fun loadMoreCharacters() {
        val state = successState() ?: return
        val currentResult = state.sections.characters.value ?: return
        val offset = currentResult.value.nextOffset ?: return
        screenModelScope.launch {
            val result = runCatching {
                analyticsService.characters(
                    state.filter.toDomainFilter(today()),
                    state.characterSort,
                    offset,
                    PAGE_SIZE,
                    state.characterSearch.takeIf(String::isNotBlank),
                )
            }
            updateSuccess { latest ->
                if (result.isFailure) {
                    latest.copy(sections = latest.sections.copy(characters = latest.sections.characters.copy(error = true)))
                } else {
                    val next = result.getOrThrow()
                    val mergedPage = AnalyticsPage(
                        items = currentResult.value.items + next.value.items,
                        nextOffset = next.value.nextOffset,
                    )
                    latest.copy(
                        sections = latest.sections.copy(
                            characters = StatsLoadable(next.copy(value = mergedPage)),
                        ),
                    )
                }
            }
        }
    }

    fun loadMoreSessions() {
        val state = successState() ?: return
        val currentResult = state.sections.sessions.value ?: return
        val cursor = currentResult.value.nextCursor ?: return
        screenModelScope.launch {
            val result = runCatching {
                analyticsService.sessions(state.filter.toDomainFilter(today()), cursor, PAGE_SIZE)
            }
            updateSuccess { latest ->
                if (result.isFailure) {
                    latest.copy(sections = latest.sections.copy(sessions = latest.sections.sessions.copy(error = true)))
                } else {
                    val next = result.getOrThrow()
                    val mergedPage = SessionPage(
                        items = currentResult.value.items + next.value.items,
                        nextCursor = next.value.nextCursor,
                    )
                    latest.copy(
                        sections = latest.sections.copy(
                            sessions = StatsLoadable(next.copy(value = mergedPage)),
                        ),
                    )
                }
            }
        }
    }

    fun createGoal(metric: String, target: Double, daily: Boolean): Boolean {
        if (!target.isFinite() || target <= 0 || metric !in GOAL_METRICS) return false
        val now = System.currentTimeMillis()
        val goal = ImmersionGoal(
            id = UUID.randomUUID().toString(),
            type = if (daily) "PERPETUAL_DAILY" else "DATE_BOUND_TOTAL",
            metric = metric,
            target = target,
            period = if (daily) "DAILY" else "TOTAL",
            startDate = ImmersionLocalDate.from(today()),
            endDate = null,
            mediaKind = successState()?.filter?.mediaKind,
            profileId = successState()?.filter?.profileId,
            languageTag = null,
            titleId = fixedTitleId,
            weekdayMultipliers = null,
            restDayPolicy = "SKIP",
            state = "ACTIVE",
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        screenModelScope.launch {
            analyticsService.saveGoal(goal)
            refresh()
        }
        return true
    }

    fun archiveGoal(goal: ImmersionGoal) {
        screenModelScope.launch {
            analyticsService.saveGoal(
                goal.copy(
                    state = "ARCHIVED",
                    updatedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
            refresh()
        }
    }

    fun checkInGoal(goalId: String) {
        screenModelScope.launch {
            analyticsService.checkIn(
                goalId = goalId,
                date = ImmersionLocalDate.from(today()),
                completed = true,
                note = null,
            )
            refresh()
        }
    }

    private fun updateFilter(transform: (StatsFilterState) -> StatsFilterState) {
        updateSuccess { it.copy(filter = transform(it.filter), selection = StatsSelection()) }
        refresh()
    }

    private suspend fun loadOverview(generation: Long, filter: StatsFilter) =
        updateSection(generation, { it.overview }, { sections, result ->
            sections.copy(overview = result)
        }) {
            analyticsService.overview(filter)
        }

    private suspend fun loadTrends(
        generation: Long,
        filter: StatsFilter,
        scale: AnalyticsBucketScale,
    ) = updateSection(generation, { it.trends }, { sections, result ->
        sections.copy(trends = result)
    }) {
        analyticsService.trends(filter, scale)
    }

    private suspend fun loadTitles(
        generation: Long,
        filter: StatsFilter,
        sort: AnalyticsSort,
    ) = updateSection(generation, { it.titles }, { sections, result ->
        sections.copy(titles = result)
    }) {
        analyticsService.titles(filter, sort)
    }

    private suspend fun loadVocabulary(
        generation: Long,
        filter: StatsFilter,
        sort: AnalyticsSort,
    ) = updateSection(generation, { it.vocabulary }, { sections, result ->
        sections.copy(vocabulary = result)
    }) {
        analyticsService.vocabulary(
            filter,
            sort,
            0,
            PAGE_SIZE,
            successState()?.vocabularySearch?.takeIf(String::isNotBlank),
        )
    }

    private suspend fun loadCharacters(
        generation: Long,
        filter: StatsFilter,
        sort: AnalyticsSort,
    ) = updateSection(generation, { it.characters }, { sections, result ->
        sections.copy(characters = result)
    }) {
        analyticsService.characters(
            filter,
            sort,
            0,
            PAGE_SIZE,
            successState()?.characterSearch?.takeIf(String::isNotBlank),
        )
    }

    private suspend fun loadSessions(
        generation: Long,
        filter: StatsFilter,
    ) = updateSection(generation, { it.sessions }, { sections, result ->
        sections.copy(sessions = result)
    }) {
        analyticsService.sessions(filter, null, PAGE_SIZE)
    }

    private suspend fun loadGoals(
        generation: Long,
        filter: StatsFilter,
    ) = updateSection(generation, { it.goals }, { sections, result ->
        sections.copy(goals = result)
    }) {
        analyticsService.goals(filter)
    }

    private suspend fun loadAnki(
        generation: Long,
        filter: StatsFilter,
    ) = updateSection(generation, { it.anki }, { sections, result ->
        sections.copy(anki = result)
    }) {
        analyticsService.anki(filter)
    }

    private suspend fun <T> updateSection(
        generation: Long,
        current: (StatsSections) -> StatsLoadable<T>,
        replace: (StatsSections, StatsLoadable<T>) -> StatsSections,
        query: suspend () -> T,
    ) {
        val result = runCatching { query() }
        if (refreshGeneration.get() != generation) return
        updateSuccess { state ->
            val previous = current(state.sections)
            state.copy(
                sections = replace(
                    state.sections,
                    if (result.isSuccess) {
                        StatsLoadable(value = result.getOrThrow())
                    } else {
                        previous.copy(refreshing = false, error = true)
                    },
                ),
            )
        }
    }

    private fun updateSuccess(transform: (StatsScreenState.Success) -> StatsScreenState.Success) {
        mutableState.update { state ->
            (state as? StatsScreenState.Success)?.let(transform) ?: state
        }
    }

    private fun successState(): StatsScreenState.Success? = mutableState.value as? StatsScreenState.Success

    private fun StatsFilterState.toDomainFilter(now: LocalDate): StatsFilter {
        val selectedProfile = profileId
        val profile = selectedProfile?.let { id ->
            dictionaryPreferences.profileStore.getProfiles().find { it.id == id }
        }
        return StatsFilter(
            dateRange = dateRange(now),
            mediaKinds = mediaKind?.let(::setOf).orEmpty(),
            profileIds = selectedProfile?.let(::setOf).orEmpty(),
            languageTags = profile?.languageCode
                ?.takeIf(String::isNotBlank)
                ?.let { runCatching { LanguageTag.from(it) }.getOrNull() }
                ?.let(::setOf)
                .orEmpty(),
            titleIds = fixedTitleId?.let(::setOf).orEmpty(),
            includeLegacyAggregates = includeLegacy,
            characterMetric = characterMetric,
            includeRereadsAndReplays = includeRereadsAndReplays,
        )
    }

    private fun StatsFilterState.dateRange(now: LocalDate): LocalDateRange? {
        if (rangePreset == StatsRangePreset.ALL) return null
        if (rangePreset == StatsRangePreset.CUSTOM) {
            val start = customStart ?: return LocalDateRange(
                ImmersionLocalDate.from(now),
                ImmersionLocalDate.from(now),
            )
            val end = customEnd ?: start
            return LocalDateRange(start, end)
        }
        val days = when (rangePreset) {
            StatsRangePreset.TODAY -> 1
            StatsRangePreset.SEVEN_DAYS -> 7
            StatsRangePreset.THIRTY_DAYS -> 30
            StatsRangePreset.NINETY_DAYS -> 90
            StatsRangePreset.YEAR -> 365
            StatsRangePreset.ALL, StatsRangePreset.CUSTOM -> error("Handled above")
        }
        val end = now.plusDays(periodOffset.toLong() * days)
        val start = end.minusDays((days - 1).toLong())
        return LocalDateRange(
            ImmersionLocalDate.from(start),
            ImmersionLocalDate.from(end),
        )
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val DETAIL_PAGE_SIZE = 50
        val GOAL_METRICS = setOf(
            "active_time_ms",
            "gross_characters",
            "unique_source_characters",
            "net_characters",
            "sessions",
            "lookups",
            "cards",
            "new_words",
            "new_characters",
            "manual",
        )
    }
}

private fun StatsSections.refreshing(): StatsSections = copy(
    overview = overview.refreshing(),
    trends = trends.refreshing(),
    titles = titles.refreshing(),
    vocabulary = vocabulary.refreshing(),
    characters = characters.refreshing(),
    sessions = sessions.refreshing(),
    goals = goals.refreshing(),
    anki = anki.refreshing(),
)

private inline fun <reified T : Enum<T>> String.enumOrNull(): T? =
    runCatching { enumValueOf<T>(this) }.getOrNull()

private inline fun <reified T : Enum<T>> String.enumOrDefault(default: T): T =
    enumOrNull() ?: default
