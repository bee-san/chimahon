package eu.kanade.tachiyomi.ui.stats

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.presentation.more.stats.StatsCharacterGridMode
import eu.kanade.presentation.more.stats.StatsCharacterLayout
import eu.kanade.presentation.more.stats.StatsDetails
import eu.kanade.presentation.more.stats.StatsFilterState
import eu.kanade.presentation.more.stats.StatsLoadable
import eu.kanade.presentation.more.stats.StatsRangePreset
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.more.stats.StatsSection
import eu.kanade.presentation.more.stats.StatsSections
import eu.kanade.presentation.more.stats.StatsSelection
import eu.kanade.presentation.more.stats.StatsTab
import eu.kanade.presentation.more.stats.StatsTrendMetric
import eu.kanade.presentation.more.stats.decodePersistedStatsFilterSelection
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.immersion.model.AnalyticsBucketScale
import tachiyomi.domain.immersion.model.AnalyticsCharacterFilter
import tachiyomi.domain.immersion.model.AnalyticsCharacterPriorityMode
import tachiyomi.domain.immersion.model.AnalyticsCharacterRange
import tachiyomi.domain.immersion.model.AnalyticsCharacterScript
import tachiyomi.domain.immersion.model.AnalyticsPage
import tachiyomi.domain.immersion.model.AnalyticsResult
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.AnalyticsSourceOccurrence
import tachiyomi.domain.immersion.model.AnalyticsTitleCompletedUnit
import tachiyomi.domain.immersion.model.AnalyticsTitleFilter
import tachiyomi.domain.immersion.model.AnalyticsTitleSeriesSelection
import tachiyomi.domain.immersion.model.AnalyticsTitleSort
import tachiyomi.domain.immersion.model.AnalyticsTrends
import tachiyomi.domain.immersion.model.AnkiMaturityAggregation
import tachiyomi.domain.immersion.model.CharacterMetric
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.ImmersionTitleMutationRequest
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.MaturityTier
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.ProvenanceState
import tachiyomi.domain.immersion.model.RawTextRetention
import tachiyomi.domain.immersion.model.SessionPage
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.model.UnicodeCodePoint
import tachiyomi.domain.immersion.repository.ImmersionAnkiRepository
import tachiyomi.domain.immersion.repository.ImmersionMaintenanceRepository
import tachiyomi.domain.immersion.service.ImmersionAnalyticsService
import tachiyomi.domain.immersion.service.ImmersionExportDocument
import tachiyomi.domain.immersion.service.ImmersionExportService
import tachiyomi.domain.immersion.service.ImmersionStatsPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class StatsScreenModel(
    titleId: TitleId? = null,
    private val analyticsService: ImmersionAnalyticsService = Injekt.get(),
    private val preferences: ImmersionStatsPreferences = Injekt.get(),
    private val dictionaryPreferences: DictionaryPreferences = Injekt.get(),
    private val maintenanceRepository: ImmersionMaintenanceRepository = Injekt.get(),
    private val ankiRepository: ImmersionAnkiRepository = Injekt.get(),
    private val exportService: ImmersionExportService = Injekt.get(),
    private val titleMetadataResolver: StatsTitleMetadataResolver = StatsTitleMetadataResolver.create(),
    private val today: () -> LocalDate = LocalDate::now,
) : StateScreenModel<StatsScreenState>(StatsScreenState.Loading) {

    private val fixedTitleId = titleId
    private val refreshGeneration = AtomicLong()
    private val sectionPagingRequests = StatsPagingRequestTracker()
    private val titlePagingRequests = StatsPagingRequestTracker()
    private val wordPagingRequests = StatsPagingRequestTracker()
    private val characterPagingRequests = StatsPagingRequestTracker()
    private val sourcePagingRequests = StatsPagingRequestTracker()
    private val sessionDetailRequests = StatsPagingRequestTracker()
    private val sessionRelinkPreviewRequests = StatsPagingRequestTracker()
    private val titleDetailRequests = StatsPagingRequestTracker()
    private val titleAcquisitionRequests = StatsPagingRequestTracker()
    private val titleSessionPagingRequests = StatsPagingRequestTracker()
    private val titleUnitPagingRequests = StatsPagingRequestTracker()
    private val titleSourcePagingRequests = StatsPagingRequestTracker()
    private var refreshJob: Job? = null
    private var vocabularySearchJob: Job? = null
    private var titleSearchJob: Job? = null
    private var characterSearchJob: Job? = null
    private var sourceSearchJob: Job? = null
    private var titleTrendsJob: Job? = null
    private var restoreSelectionAfterRefresh = true
    private val mutableExportDocuments = MutableSharedFlow<ImmersionExportDocument>()
    val exportDocuments: SharedFlow<ImmersionExportDocument> = mutableExportDocuments.asSharedFlow()

    init {
        val profiles = dictionaryPreferences.profileStore.getProfiles()
        val profileId = preferences.dashboardProfileId().get().takeIf { selected ->
            selected.isNotBlank() && profiles.any { it.id == selected }
        }
        val selectedTitleId = fixedTitleId ?: preferences.dashboardTitleId().get()
            .takeIf(String::isNotBlank)
            ?.let { runCatching { TitleId(it) }.getOrNull() }
        val goalsEnabled = preferences.goalsEnabled().get()
        val ankiEnabled = preferences.ankiSyncEnabled().get()
        val requestedTab = if (fixedTitleId != null) {
            StatsTab.TITLES
        } else {
            preferences.dashboardSelectedTab().get().enumOrDefault(StatsTab.OVERVIEW)
        }
        val initialFilter = StatsFilterState(
            rangePreset = preferences.dashboardRangePreset().get().enumOrDefault(StatsRangePreset.TODAY),
            periodOffset = preferences.dashboardPeriodOffset().get().coerceAtMost(0),
            customStart = preferences.dashboardCustomStart().get().toImmersionLocalDateOrNull(),
            customEnd = preferences.dashboardCustomEnd().get().toImmersionLocalDateOrNull(),
            mediaKind = preferences.dashboardMediaKind().get()
                .takeIf(String::isNotBlank)
                ?.enumOrNull(),
            profileId = profileId,
            characterMetric = preferences.dashboardCharacterMetric().get(),
            includeLegacy = preferences.includeLegacyAggregates().get(),
            includeRereadsAndReplays = preferences.dashboardIncludeRereads().get(),
            maturityTiers = preferences.dashboardMaturityTiers().get()
                .decodePersistedStatsFilterSelection(MaturityTier.UNAVAILABLE),
            provenanceStates = preferences.dashboardProvenanceStates().get()
                .decodePersistedStatsFilterSelection(ProvenanceState.UNAVAILABLE),
            titleId = selectedTitleId?.value,
        )
        val persistedCharacterScripts = preferences.dashboardCharacterScripts().get()
        val characterScripts = when {
            CHARACTER_ALL_SCRIPTS_SENTINEL in persistedCharacterScripts -> emptySet()
            persistedCharacterScripts.isNotEmpty() ->
                persistedCharacterScripts.decodeEnums<AnalyticsCharacterScript>()
            profileId
                ?.let { id -> profiles.find { it.id == id } }
                ?.languageCode
                ?.startsWith("ja", ignoreCase = true) == true ->
                setOf(
                    AnalyticsCharacterScript.HAN,
                    AnalyticsCharacterScript.HIRAGANA,
                    AnalyticsCharacterScript.KATAKANA,
                )
            else -> emptySet()
        }
        mutableState.value = StatsScreenState.Success(
            filter = initialFilter,
            selectedTab = resolveEnabledStatsTab(requestedTab, goalsEnabled, ankiEnabled),
            profiles = profiles,
            sections = StatsSections(),
            goalsEnabled = goalsEnabled,
            ankiEnabled = ankiEnabled,
            trendScale = preferences.dashboardTrendScale().get(),
            trendMetric = preferences.dashboardTrendMetric().get()
                .enumOrDefault(StatsTrendMetric.CHARACTERS),
            titleSort = preferences.dashboardTitleSort().get(),
            titleFilter = AnalyticsTitleFilter(
                state = preferences.dashboardTitleState().get(),
                coverage = preferences.dashboardTitleCoverage().get(),
            ),
            characterSort = preferences.dashboardCharacterSort().get(),
            characterFilter = AnalyticsCharacterFilter(
                scripts = characterScripts,
                range = preferences.dashboardCharacterRange().get(),
                priorityMode = preferences.dashboardCharacterPriorityMode().get(),
                maximumMissingFrequencyRank =
                preferences.dashboardCharacterMaximumMissingFrequencyRank().get()
                    .coerceAtLeast(1),
            ),
            characterGridMode = preferences.dashboardCharacterGridMode().get()
                .enumOrDefault(StatsCharacterGridMode.FREQUENCY),
            characterLayout = preferences.dashboardCharacterLayout().get()
                .enumOrDefault(StatsCharacterLayout.GRID),
            characterCoverageTargetPercent =
            preferences.dashboardCharacterCoverageTargetPercent().get().coerceIn(1, 100),
        )
        refresh()
        observeFeatureFlags()
    }

    fun refresh() {
        refreshJob?.cancel()
        sectionPagingRequests.invalidate()
        titlePagingRequests.invalidate()
        val generation = refreshGeneration.incrementAndGet()
        val current = successState() ?: return
        mutableState.value = current.copy(
            sections = current.sections.refreshing(
                selectedTab = current.selectedTab,
                goalsEnabled = current.goalsEnabled,
                ankiEnabled = current.ankiEnabled,
            ),
        )
        refreshJob = screenModelScope.launch {
            val filter = current.toStatsFilter()
            coroutineScope {
                listOf(
                    async { loadOverview(generation, filter) },
                    async { loadHeatmap(generation, filter) },
                    async { loadTrends(generation, filter, current.trendScale) },
                    async {
                        if (current.selectedTab == StatsTab.ACTIVITY) {
                            loadTemporalActivity(generation, filter)
                        }
                    },
                    async {
                        if (current.selectedTab == StatsTab.ACTIVITY) {
                            loadTitleTrends(
                                generation,
                                filter,
                                current.trendScale,
                                current.titleTrendSelection,
                            )
                        }
                    },
                    async {
                        loadTitles(
                            generation,
                            filter,
                            current.titleFilter,
                            current.titleSort,
                        )
                    },
                    async {
                        loadCharacters(
                            generation,
                            filter,
                            current.characterFilter,
                            current.characterSort,
                        )
                    },
                    async {
                        loadCharacterSummary(generation, filter, current.characterFilter)
                    },
                    async { loadSessions(generation, filter) },
                    async {
                        if (current.goalsEnabled) {
                            loadGoals(generation, filter)
                        }
                    },
                    async {
                        if (current.ankiEnabled) {
                            loadAnki(generation, filter)
                        }
                    },
                ).awaitAll()
            }
            if (refreshGeneration.get() == generation) {
                if (restoreSelectionAfterRefresh) {
                    restoreSelectionAfterRefresh = false
                    restorePersistedSelection()
                } else {
                    val selected = successState()?.selection?.title
                    if (selected != null) {
                        val refreshed = successState()
                            ?.sections
                            ?.titles
                            ?.value
                            ?.value
                            ?.items
                            ?.find { it.titleId == selected.titleId }
                            ?: selected
                        selectTitle(refreshed)
                    }
                }
            }
        }
    }

    fun retrySection(section: StatsSection) {
        val current = successState() ?: return
        if (current.sections.isRefreshing(section)) return
        if (section == StatsSection.GOALS && !current.goalsEnabled) return
        if (section == StatsSection.ANKI && !current.ankiEnabled) return

        val generation = refreshGeneration.get()
        updateSuccess {
            it.copy(sections = it.sections.retrying(section))
        }
        screenModelScope.launch {
            val state = successState() ?: return@launch
            if (refreshGeneration.get() != generation) return@launch
            val filter = state.toStatsFilter()
            when (section) {
                StatsSection.OVERVIEW -> loadOverview(generation, filter)
                StatsSection.HEATMAP -> loadHeatmap(generation, filter)
                StatsSection.TRENDS -> loadTrends(generation, filter, state.trendScale)
                StatsSection.TEMPORAL_ACTIVITY -> loadTemporalActivity(generation, filter)
                StatsSection.TITLE_TRENDS -> loadTitleTrends(
                    generation = generation,
                    filter = filter,
                    scale = state.trendScale,
                    selection = state.titleTrendSelection,
                )
                StatsSection.TITLES -> loadTitles(
                    generation = generation,
                    filter = filter,
                    titleFilter = state.titleFilter,
                    sort = state.titleSort,
                )
                StatsSection.CHARACTERS -> loadCharacters(
                    generation,
                    filter,
                    state.characterFilter,
                    state.characterSort,
                )
                StatsSection.CHARACTER_SUMMARY -> loadCharacterSummary(
                    generation,
                    filter,
                    state.characterFilter,
                )
                StatsSection.SESSIONS -> loadSessions(generation, filter)
                StatsSection.GOALS -> loadGoals(generation, filter)
                StatsSection.ANKI -> loadAnki(generation, filter)
            }
        }
    }

    fun rawTextDisclosureRequired(): Boolean =
        preferences.rawTextDisclosureRequired()

    fun acknowledgeRawTextDisclosure(retention: RawTextRetention) {
        preferences.acknowledgeRawTextDisclosure(retention)
    }

    fun selectTab(tab: StatsTab) {
        val current = successState() ?: return
        if (
            tab != resolveEnabledStatsTab(
                requested = tab,
                goalsEnabled = current.goalsEnabled,
                ankiEnabled = current.ankiEnabled,
            )
        ) {
            return
        }
        preferences.dashboardSelectedTab().set(tab.name)
        clearPersistedSelection()
        titleDetailRequests.invalidate()
        titleAcquisitionRequests.invalidate()
        titleSessionPagingRequests.invalidate()
        titleUnitPagingRequests.invalidate()
        titleSourcePagingRequests.invalidate()
        updateSuccess {
            it.copy(
                selectedTab = tab,
                selection = StatsSelection(),
                details = StatsDetails(),
            )
        }
        loadOptionalSections(tab)
    }

    fun selectRange(preset: StatsRangePreset) {
        preferences.dashboardRangePreset().set(preset.name)
        preferences.dashboardPeriodOffset().set(0)
        updateFilter { it.copy(rangePreset = preset, periodOffset = 0) }
    }

    fun movePeriod(offset: Int) {
        val periodOffset = offset.coerceAtMost(0)
        preferences.dashboardPeriodOffset().set(periodOffset)
        updateFilter { it.copy(periodOffset = periodOffset) }
    }

    fun setCustomRange(start: String, end: String): Boolean {
        val parsedStart = runCatching { ImmersionLocalDate.from(LocalDate.parse(start.trim())) }.getOrNull()
        val parsedEnd = runCatching { ImmersionLocalDate.from(LocalDate.parse(end.trim())) }.getOrNull()
        if (parsedStart == null || parsedEnd == null || parsedStart > parsedEnd) return false
        preferences.dashboardRangePreset().set(StatsRangePreset.CUSTOM.name)
        preferences.dashboardPeriodOffset().set(0)
        preferences.dashboardCustomStart().set(parsedStart.toString())
        preferences.dashboardCustomEnd().set(parsedEnd.toString())
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

    fun selectTitleFilter(titleId: String?) {
        if (fixedTitleId != null) return
        val selectedTitleId = titleId
            ?.let { runCatching { TitleId(it) }.getOrNull() }
            ?.value
        preferences.dashboardTitleId().set(selectedTitleId.orEmpty())
        updateFilter { it.copy(titleId = selectedTitleId) }
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

    fun selectMaturityTiers(tiers: Set<MaturityTier>) {
        preferences.dashboardMaturityTiers().set(tiers.mapTo(linkedSetOf()) { it.name })
        updateFilter { it.copy(maturityTiers = tiers) }
    }

    fun selectProvenanceStates(states: Set<ProvenanceState>) {
        preferences.dashboardProvenanceStates().set(states.mapTo(linkedSetOf()) { it.name })
        updateFilter { it.copy(provenanceStates = states) }
    }

    fun selectTrendScale(scale: AnalyticsBucketScale) {
        preferences.dashboardTrendScale().set(scale)
        updateSuccess { it.copy(trendScale = scale) }
        refresh()
    }

    fun selectTrendMetric(metric: StatsTrendMetric) {
        preferences.dashboardTrendMetric().set(metric.name)
        updateSuccess { it.copy(trendMetric = metric) }
    }

    fun selectTitleTrendSelection(selection: AnalyticsTitleSeriesSelection) {
        updateSuccess {
            it.copy(
                titleTrendSelection = selection,
                sections = it.sections.copy(titleTrends = it.sections.titleTrends.refreshing()),
            )
        }
        val state = successState() ?: return
        titleTrendsJob?.cancel()
        val generation = refreshGeneration.get()
        titleTrendsJob = screenModelScope.launch {
            loadTitleTrends(
                generation = generation,
                filter = state.toStatsFilter(),
                scale = state.trendScale,
                selection = selection,
            )
        }
    }

    fun selectTitleSort(sort: AnalyticsTitleSort) {
        preferences.dashboardTitleSort().set(sort)
        updateSuccess { it.copy(titleSort = sort) }
        refresh()
    }

    fun updateTitleFilter(filter: AnalyticsTitleFilter) {
        val normalized = filter.copy(searchQuery = null)
        preferences.dashboardTitleState().set(normalized.state)
        preferences.dashboardTitleCoverage().set(normalized.coverage)
        titlePagingRequests.invalidate()
        updateSuccess {
            it.copy(
                titleFilter = normalized,
                selection = it.selection.copy(title = null),
                details = StatsDetails(),
            )
        }
        titleDetailRequests.invalidate()
        titleAcquisitionRequests.invalidate()
        titleSessionPagingRequests.invalidate()
        titleUnitPagingRequests.invalidate()
        titleSourcePagingRequests.invalidate()
        preferences.dashboardSelectedTitleId().delete()
        refresh()
    }

    fun selectCharacterSort(sort: AnalyticsSort) {
        preferences.dashboardCharacterSort().set(sort)
        updateSuccess {
            it.copy(
                characterSort = sort,
                selectedCharacterCodePoints = emptySet(),
            )
        }
        refresh()
    }

    fun updateCharacterFilter(filter: AnalyticsCharacterFilter) {
        val normalized = filter.copy(searchQuery = null)
        preferences.dashboardCharacterScripts().set(
            normalized.scripts
                .mapTo(linkedSetOf()) { it.name }
                .ifEmpty { linkedSetOf(CHARACTER_ALL_SCRIPTS_SENTINEL) },
        )
        preferences.dashboardCharacterRange().set(normalized.range)
        preferences.dashboardCharacterPriorityMode().set(normalized.priorityMode)
        preferences.dashboardCharacterMaximumMissingFrequencyRank()
            .set(normalized.maximumMissingFrequencyRank)
        sectionPagingRequests.invalidate()
        characterPagingRequests.invalidate()
        preferences.dashboardSelectedCharacter().delete()
        updateSuccess {
            it.copy(
                characterFilter = normalized,
                selectedCharacterCodePoints = emptySet(),
                selection = it.selection.copy(character = null),
                details = it.details.copy(
                    characterOccurrences = StatsLoadable(),
                    characterAnkiItems = StatsLoadable(),
                ),
            )
        }
        refresh()
    }

    fun selectCharacterGridMode(mode: StatsCharacterGridMode) {
        preferences.dashboardCharacterGridMode().set(mode.name)
        updateSuccess { it.copy(characterGridMode = mode) }
    }

    fun selectCharacterLayout(layout: StatsCharacterLayout) {
        preferences.dashboardCharacterLayout().set(layout.name)
        updateSuccess { it.copy(characterLayout = layout) }
    }

    fun setCharacterCoverageTargetPercent(percent: Int) {
        val normalized = percent.coerceIn(1, 100)
        preferences.dashboardCharacterCoverageTargetPercent().set(normalized)
        updateSuccess { it.copy(characterCoverageTargetPercent = normalized) }
    }

    fun openMissingAnkiCharactersWorkbench() {
        val state = successState() ?: return
        val filter = state.characterFilter.copy(
            range = AnalyticsCharacterRange.MISSING_HIGH_FREQUENCY,
        )
        preferences.dashboardSelectedTab().set(StatsTab.CHARACTERS.name)
        preferences.dashboardMaturityTiers().set(emptySet())
        preferences.dashboardCharacterSort().set(AnalyticsSort.PRIORITY)
        preferences.dashboardCharacterRange().set(filter.range)
        preferences.dashboardCharacterGridMode().set(StatsCharacterGridMode.PRIORITY.name)
        characterPagingRequests.invalidate()
        clearPersistedSelection()
        updateSuccess {
            it.copy(
                filter = it.filter.copy(maturityTiers = emptySet()),
                selectedTab = StatsTab.CHARACTERS,
                characterSort = AnalyticsSort.PRIORITY,
                characterFilter = filter,
                characterGridMode = StatsCharacterGridMode.PRIORITY,
                characterSearch = "",
                selection = StatsSelection(),
                details = StatsDetails(),
                selectedCharacterCodePoints = emptySet(),
            )
        }
        refresh()
    }

    fun setCharacterSelected(codePoint: Int, selected: Boolean) {
        updateSuccess { state ->
            state.copy(
                selectedCharacterCodePoints = state.selectedCharacterCodePoints
                    .toMutableSet()
                    .apply {
                        if (selected) add(codePoint) else remove(codePoint)
                    },
            )
        }
    }

    fun clearCharacterSelection() {
        updateSuccess { it.copy(selectedCharacterCodePoints = emptySet()) }
    }

    fun exportSelectedCharacters() {
        val state = successState() ?: return
        val selected = state.selectedCharacterCodePoints.mapTo(linkedSetOf(), ::UnicodeCodePoint)
        if (selected.isEmpty()) return
        screenModelScope.launch {
            mutableExportDocuments.emit(
                exportService.selectedCharactersCsv(
                    filter = state.toStatsFilter(),
                    characterFilter = state.characterFilter.copy(
                        searchQuery = state.characterSearch.takeIf(String::isNotBlank),
                    ),
                    sort = state.characterSort,
                    selectedCodePoints = selected,
                ),
            )
        }
    }

    fun searchTitles(query: String) {
        titlePagingRequests.invalidate()
        updateSuccess {
            it.copy(
                titleSearch = query,
                sections = it.sections.copy(titles = it.sections.titles.refreshing()),
            )
        }
        titleSearchJob?.cancel()
        titleSearchJob = screenModelScope.launch {
            delay(250)
            refresh()
        }
    }

    fun searchCharacters(query: String) {
        sectionPagingRequests.invalidate()
        updateSuccess {
            it.copy(
                characterSearch = query,
                sections = it.sections.copy(characters = it.sections.characters.refreshing()),
            )
        }
        characterSearchJob?.cancel()
        characterSearchJob = screenModelScope.launch {
            delay(250)
            refresh()
        }
    }

    fun selectTitle(title: tachiyomi.domain.immersion.model.AnalyticsTitleRow?) {
        val detailGeneration = titleDetailRequests.invalidate()
        titleSessionPagingRequests.invalidate()
        titleUnitPagingRequests.invalidate()
        titleSourcePagingRequests.invalidate()
        preferences.dashboardSelectedTitleId().set(title?.titleId?.value.orEmpty())
        updateSuccess {
            it.copy(
                selection = it.selection.copy(title = title),
                details = if (title == null) {
                    StatsDetails()
                } else {
                    it.details.copy(
                        titleCaptureExcluded = StatsLoadable(refreshing = true),
                        titleMutationInProgress = false,
                        titleMutationError = false,
                        titleTrends = StatsLoadable(refreshing = true),
                        titleSessions = StatsLoadable(refreshing = true),
                        titleCompletedUnits = StatsLoadable(refreshing = true),
                        titleSources = StatsLoadable(refreshing = true),
                    )
                },
            )
        }
        if (title == null) return
        screenModelScope.launch {
            val state = successState() ?: return@launch
            val filter = state.toStatsFilter()
            val scopedFilter = filter.copy(
                titleIds = setOf(title.titleId),
                comparisonRange = null,
            )
            val (
                captureExcluded,
                trends,
                sessions,
                completedUnits,
                sources,
            ) = coroutineScope {
                val captureDeferred = async {
                    runCatching {
                        maintenanceRepository.isTitleCaptureExcluded(title.titleId)
                    }
                }
                val trendsDeferred = async {
                    runCatching {
                        analyticsService.trends(scopedFilter, AnalyticsBucketScale.DAY)
                    }
                }
                val sessionsDeferred = async {
                    runCatching {
                        analyticsService.sessions(scopedFilter, null, DETAIL_PAGE_SIZE)
                    }
                }
                val unitsDeferred = async {
                    runCatching {
                        analyticsService.titleCompletedUnits(
                            filter = filter,
                            titleId = title.titleId,
                            offset = 0,
                            limit = DETAIL_PAGE_SIZE,
                        )
                    }
                }
                val sourcesDeferred = async {
                    runCatching {
                        analyticsService.titleSourceOccurrences(
                            filter = filter,
                            titleId = title.titleId,
                            offset = 0,
                            limit = DETAIL_PAGE_SIZE,
                        )
                    }
                }
                TitleDetailQueryResults(
                    captureExcluded = captureDeferred.await(),
                    trends = trendsDeferred.await(),
                    sessions = sessionsDeferred.await(),
                    completedUnits = unitsDeferred.await(),
                    sources = sourcesDeferred.await(),
                )
            }
            if (
                !titleDetailRequests.isCurrent(detailGeneration) ||
                successState()?.selection?.title?.titleId != title.titleId
            ) {
                return@launch
            }
            updateSuccess { latest ->
                latest.copy(
                    details = latest.details.copy(
                        titleCaptureExcluded = captureExcluded.toStatsLoadable(),
                        titleTrends = trends.toStatsLoadable(),
                        titleSessions = sessions.toStatsLoadable(),
                        titleCompletedUnits = completedUnits.toStatsLoadable(),
                        titleSources = sources.toStatsLoadable(),
                    ),
                )
            }
        }
    }

    fun setSelectedTitleCaptureExcluded(excluded: Boolean) {
        val title = successState()?.selection?.title ?: return
        updateSuccess {
            it.copy(
                details = it.details.copy(
                    titleCaptureExcluded = it.details.titleCaptureExcluded.copy(refreshing = true, error = false),
                ),
            )
        }
        screenModelScope.launch {
            val result = runCatching {
                maintenanceRepository.setTitleCaptureExcluded(
                    titleId = title.titleId,
                    excluded = excluded,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                )
            }
            if (successState()?.selection?.title?.titleId != title.titleId) return@launch
            updateSuccess { state ->
                state.copy(
                    details = state.details.copy(
                        titleCaptureExcluded = result.fold(
                            onSuccess = { StatsLoadable(excluded) },
                            onFailure = {
                                StatsLoadable(
                                    value = state.details.titleCaptureExcluded.value,
                                    error = true,
                                )
                            },
                        ),
                    ),
                )
            }
        }
    }

    fun unlinkSelectedTitle() {
        val title = successState()?.selection?.title ?: return
        updateSuccess {
            it.copy(
                details = it.details.copy(
                    titleMutationInProgress = true,
                    titleMutationError = false,
                ),
            )
        }
        screenModelScope.launch {
            val unlinkedAt = System.currentTimeMillis()
            val result = runCatching {
                check(
                    maintenanceRepository.unlinkTitle(
                        titleId = title.titleId,
                        updatedAtEpochMillis = unlinkedAt,
                    ),
                ) {
                    "Selected title no longer exists"
                }
            }
            if (successState()?.selection?.title?.titleId != title.titleId) return@launch
            updateSuccess { state ->
                result.fold(
                    onSuccess = {
                        val unlinked = title.copy(
                            libraryId = null,
                            trackerId = null,
                            mediaId = null,
                            deletedAtEpochMillis = unlinkedAt,
                        )
                        val currentTitles = state.sections.titles.value
                        state.copy(
                            sections = state.sections.copy(
                                titles = currentTitles?.let { analyticsResult ->
                                    StatsLoadable(
                                        analyticsResult.copy(
                                            value = analyticsResult.value.copy(
                                                items = analyticsResult.value.items.map { row ->
                                                    if (row.titleId == title.titleId) unlinked else row
                                                },
                                            ),
                                        ),
                                    )
                                } ?: state.sections.titles,
                            ),
                            titleOptions = state.titleOptions.map { row ->
                                if (row.titleId == title.titleId) unlinked else row
                            },
                            titleMetadata = state.titleMetadata + (
                                title.titleId to StatsTitlePresentationMetadata(
                                    titleId = title.titleId,
                                    localDisplayTitle = null,
                                    author = null,
                                    coverLocation = null,
                                    linkState = StatsTitleLinkState.UNAVAILABLE,
                                )
                                ),
                            selection = state.selection.copy(title = unlinked),
                            details = state.details.copy(
                                titleMutationInProgress = false,
                                titleMutationError = false,
                            ),
                        )
                    },
                    onFailure = {
                        state.copy(
                            details = state.details.copy(
                                titleMutationInProgress = false,
                                titleMutationError = true,
                            ),
                        )
                    },
                )
            }
        }
    }

    fun selectCharacter(character: tachiyomi.domain.immersion.model.AnalyticsCharacterRow?) {
        val requestGeneration = characterPagingRequests.invalidate()
        preferences.dashboardSelectedCharacter().set(character?.codePoint?.value ?: -1)
        updateSuccess {
            it.copy(
                selection = it.selection.copy(character = character),
                details = it.details.copy(
                    characterOccurrences = if (character == null) {
                        StatsLoadable()
                    } else {
                        StatsLoadable(refreshing = true)
                    },
                    characterAnkiItems = if (character == null) {
                        StatsLoadable()
                    } else {
                        StatsLoadable(refreshing = true)
                    },
                ),
            )
        }
        if (character != null) {
            screenModelScope.launch {
                val state = successState() ?: return@launch
                val filter = state.toStatsFilter()
                val profileIds = state.filter.profileId
                    ?.let(::listOf)
                    ?: state.profiles.map { it.id }
                val (occurrences, ankiItems) = coroutineScope {
                    val occurrenceResult = async {
                        runCatching {
                            analyticsService.characterOccurrences(
                                filter,
                                character.codePoint,
                                0,
                                DETAIL_PAGE_SIZE,
                            )
                        }
                    }
                    val ankiItemResult = async {
                        runCatching {
                            loadCharacterAnkiItems(
                                repository = ankiRepository,
                                profileIds = profileIds,
                                codePoint = character.codePoint,
                            )
                        }
                    }
                    occurrenceResult.await() to ankiItemResult.await()
                }
                if (
                    !characterPagingRequests.isCurrent(requestGeneration) ||
                    successState()?.selection?.character?.codePoint != character.codePoint
                ) {
                    return@launch
                }
                updateSuccess {
                    it.copy(
                        details = it.details.copy(
                            characterOccurrences = occurrences.fold(
                                onSuccess = { value -> StatsLoadable(value) },
                                onFailure = { StatsLoadable(error = true) },
                            ),
                            characterAnkiItems = ankiItems.fold(
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
        val requestGeneration = sessionDetailRequests.invalidate()
        sessionRelinkPreviewRequests.invalidate()
        preferences.dashboardSelectedSessionId().set(session?.id?.value.orEmpty())
        updateSuccess {
            it.copy(
                selection = it.selection.copy(session = session),
                details = it.details.copy(
                    session = if (session == null) {
                        StatsLoadable()
                    } else {
                        StatsLoadable(refreshing = true)
                    },
                    sessionDeletionPreview = if (session == null) {
                        StatsLoadable()
                    } else {
                        StatsLoadable(refreshing = true)
                    },
                    sessionRelinkPreview = StatsLoadable(),
                ),
            )
        }
        if (session != null) {
            screenModelScope.launch {
                val state = successState() ?: return@launch
                val (detailResult, deletionPreviewResult) = coroutineScope {
                    val detail = async {
                        runCatching {
                            analyticsService.sessionDetail(
                                state.toStatsFilter(),
                                session.id,
                            )
                        }
                    }
                    val deletionPreview = async {
                        runCatching {
                            maintenanceRepository.previewSessionDeletion(session.id)
                        }
                    }
                    detail.await() to deletionPreview.await()
                }
                if (
                    !sessionDetailRequests.isCurrent(requestGeneration) ||
                    successState()?.selection?.session?.id != session.id
                ) {
                    return@launch
                }
                updateSuccess {
                    it.copy(
                        details = it.details.copy(
                            session = detailResult.fold(
                                onSuccess = { value -> StatsLoadable(value) },
                                onFailure = { StatsLoadable(error = true) },
                            ),
                            sessionDeletionPreview = deletionPreviewResult.fold(
                                onSuccess = { value ->
                                    value?.let { StatsLoadable(value = it) }
                                        ?: StatsLoadable(error = true)
                                },
                                onFailure = { StatsLoadable(error = true) },
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun previewSessionRelink(targetTitleId: TitleId) {
        val session = successState()?.selection?.session ?: return
        if (session.titleId == targetTitleId) return
        val requestGeneration = sessionRelinkPreviewRequests.invalidate()
        val request = ImmersionTitleMutationRequest.RelinkSession(
            sourceTitleId = session.titleId,
            targetTitleId = targetTitleId,
            sessionId = session.id,
        )
        updateSuccess {
            it.copy(
                details = it.details.copy(
                    sessionRelinkPreview = StatsLoadable(refreshing = true),
                ),
            )
        }
        screenModelScope.launch {
            val result = runCatching {
                maintenanceRepository.previewTitleMutation(request)
            }
            if (
                !sessionRelinkPreviewRequests.isCurrent(requestGeneration) ||
                successState()?.selection?.session?.id != session.id
            ) {
                return@launch
            }
            updateSuccess {
                it.copy(
                    details = it.details.copy(
                        sessionRelinkPreview = result.fold(
                            onSuccess = { preview -> StatsLoadable(preview) },
                            onFailure = { StatsLoadable(error = true) },
                        ),
                    ),
                )
            }
        }
    }

    fun clearSessionRelinkPreview() {
        sessionRelinkPreviewRequests.invalidate()
        updateSuccess {
            it.copy(
                details = it.details.copy(sessionRelinkPreview = StatsLoadable()),
            )
        }
    }

    fun applySessionRelink() {
        val state = successState() ?: return
        val session = state.selection.session ?: return
        val preview = state.details.sessionRelinkPreview.value ?: return
        if (!preview.canApply) return
        sessionRelinkPreviewRequests.invalidate()
        updateSuccess {
            it.copy(
                details = it.details.copy(
                    sessionRelinkPreview = it.details.sessionRelinkPreview.refreshing(),
                ),
            )
        }
        screenModelScope.launch {
            val result = runCatching {
                maintenanceRepository.applyTitleMutation(
                    expectedPreview = preview,
                    appliedAtEpochMillis = System.currentTimeMillis(),
                )
                repairAllDirtyRollups()
            }
            if (successState()?.selection?.session?.id != session.id) return@launch
            result.fold(
                onSuccess = {
                    selectSession(null)
                    refresh()
                },
                onFailure = {
                    updateSuccess { latest ->
                        latest.copy(
                            details = latest.details.copy(
                                sessionRelinkPreview = StatsLoadable(error = true),
                            ),
                        )
                    }
                },
            )
        }
    }

    fun deleteSession(session: tachiyomi.domain.immersion.model.ImmersionSession) {
        val expectedPreview = successState()?.details?.sessionDeletionPreview?.value ?: return
        screenModelScope.launch {
            val deleted = try {
                maintenanceRepository.deleteSession(session.id, expectedPreview)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (deleted == null) {
                selectSession(session)
                return@launch
            }
            repairAllDirtyRollups()
            selectSession(null)
            refresh()
        }
    }

    fun loadMoreCharacterOccurrences() {
        val state = successState() ?: return
        val requestGeneration = characterPagingRequests.snapshot()
        val character = state.selection.character ?: return
        val currentLoadable = state.details.characterOccurrences
        if (currentLoadable.refreshing) return
        val currentResult = currentLoadable.value ?: return
        val offset = currentResult.value.nextOffset ?: return
        updateSuccess {
            it.copy(
                details = it.details.copy(
                    characterOccurrences = it.details.characterOccurrences.refreshing(),
                ),
            )
        }
        screenModelScope.launch {
            val result = runCatching {
                analyticsService.characterOccurrences(
                    filter = state.toStatsFilter(),
                    codePoint = character.codePoint,
                    offset = offset,
                    limit = DETAIL_PAGE_SIZE,
                )
            }
            if (
                !characterPagingRequests.isCurrent(requestGeneration) ||
                successState()?.selection?.character?.codePoint != character.codePoint
            ) {
                return@launch
            }
            updateSuccess { latest ->
                val nextLoadable = result.fold(
                    onSuccess = { next ->
                        StatsLoadable(
                            next.copy(
                                value = mergeAnalyticsPages(
                                    current = currentResult.value,
                                    next = next.value,
                                    keyOf = { it.statsOccurrenceKey() },
                                ),
                            ),
                        )
                    },
                    onFailure = {
                        latest.details.characterOccurrences.copy(refreshing = false, error = true)
                    },
                )
                latest.copy(details = latest.details.copy(characterOccurrences = nextLoadable))
            }
        }
    }

    fun loadMoreTitles() {
        val state = successState() ?: return
        val requestGeneration = titlePagingRequests.snapshot()
        val currentLoadable = state.sections.titles
        if (currentLoadable.refreshing) return
        val currentResult = currentLoadable.value ?: return
        val offset = currentResult.value.nextOffset ?: return
        updateSuccess {
            it.copy(
                sections = it.sections.copy(titles = it.sections.titles.refreshing()),
            )
        }
        screenModelScope.launch {
            val result = runCatching {
                val next = analyticsService.titlePage(
                    filter = state.toStatsFilter(),
                    titleFilter = state.titleFilter.copy(
                        searchQuery = state.titleSearch.takeIf(String::isNotBlank),
                    ),
                    sort = state.titleSort,
                    offset = offset,
                    limit = PAGE_SIZE,
                )
                next to titleMetadataResolver.resolve(next.value.items)
            }
            if (!titlePagingRequests.isCurrent(requestGeneration)) return@launch
            updateSuccess { latest ->
                result.fold(
                    onSuccess = { (next, metadata) ->
                        latest.copy(
                            sections = latest.sections.copy(
                                titles = StatsLoadable(
                                    next.copy(
                                        value = mergeAnalyticsPages(
                                            current = currentResult.value,
                                            next = next.value,
                                            keyOf = { it.titleId },
                                        ),
                                    ),
                                ),
                            ),
                            titleMetadata = latest.titleMetadata + metadata,
                        )
                    },
                    onFailure = {
                        latest.copy(
                            sections = latest.sections.copy(
                                titles = latest.sections.titles.copy(
                                    refreshing = false,
                                    error = true,
                                ),
                            ),
                        )
                    },
                )
            }
        }
    }

    fun loadMoreTitleSessions() {
        val state = successState() ?: return
        val title = state.selection.title ?: return
        val requestGeneration = titleSessionPagingRequests.snapshot()
        val currentLoadable = state.details.titleSessions
        if (currentLoadable.refreshing) return
        val currentResult = currentLoadable.value ?: return
        val cursor = currentResult.value.nextCursor ?: return
        updateSuccess {
            it.copy(
                details = it.details.copy(
                    titleSessions = it.details.titleSessions.refreshing(),
                ),
            )
        }
        screenModelScope.launch {
            val result = runCatching {
                analyticsService.sessions(
                    filter = state.toStatsFilter().copy(
                        titleIds = setOf(title.titleId),
                        comparisonRange = null,
                    ),
                    cursor = cursor,
                    limit = DETAIL_PAGE_SIZE,
                )
            }
            if (
                !titleSessionPagingRequests.isCurrent(requestGeneration) ||
                successState()?.selection?.title?.titleId != title.titleId
            ) {
                return@launch
            }
            updateSuccess { latest ->
                val nextLoadable = result.fold(
                    onSuccess = { next ->
                        StatsLoadable(
                            next.copy(
                                value = SessionPage(
                                    items = (currentResult.value.items + next.value.items)
                                        .distinctBy { it.id },
                                    nextCursor = next.value.nextCursor,
                                ),
                            ),
                        )
                    },
                    onFailure = {
                        latest.details.titleSessions.copy(refreshing = false, error = true)
                    },
                )
                latest.copy(details = latest.details.copy(titleSessions = nextLoadable))
            }
        }
    }

    fun loadMoreTitleCompletedUnits() {
        val state = successState() ?: return
        val title = state.selection.title ?: return
        val requestGeneration = titleUnitPagingRequests.snapshot()
        val currentLoadable = state.details.titleCompletedUnits
        if (currentLoadable.refreshing) return
        val currentResult = currentLoadable.value ?: return
        val offset = currentResult.value.nextOffset ?: return
        updateSuccess {
            it.copy(
                details = it.details.copy(
                    titleCompletedUnits = it.details.titleCompletedUnits.refreshing(),
                ),
            )
        }
        screenModelScope.launch {
            val result = runCatching {
                analyticsService.titleCompletedUnits(
                    filter = state.toStatsFilter(),
                    titleId = title.titleId,
                    offset = offset,
                    limit = DETAIL_PAGE_SIZE,
                )
            }
            if (
                !titleUnitPagingRequests.isCurrent(requestGeneration) ||
                successState()?.selection?.title?.titleId != title.titleId
            ) {
                return@launch
            }
            updateSuccess { latest ->
                val nextLoadable = result.fold(
                    onSuccess = { next ->
                        StatsLoadable(
                            next.copy(
                                value = mergeAnalyticsPages(
                                    current = currentResult.value,
                                    next = next.value,
                                    keyOf = { it.titleId to it.completionUnitId },
                                ),
                            ),
                        )
                    },
                    onFailure = {
                        latest.details.titleCompletedUnits.copy(refreshing = false, error = true)
                    },
                )
                latest.copy(details = latest.details.copy(titleCompletedUnits = nextLoadable))
            }
        }
    }

    fun loadMoreTitleSources() {
        val state = successState() ?: return
        val title = state.selection.title ?: return
        val requestGeneration = titleSourcePagingRequests.snapshot()
        val currentLoadable = state.details.titleSources
        if (currentLoadable.refreshing) return
        val currentResult = currentLoadable.value ?: return
        val offset = currentResult.value.nextOffset ?: return
        updateSuccess {
            it.copy(
                details = it.details.copy(
                    titleSources = it.details.titleSources.refreshing(),
                ),
            )
        }
        screenModelScope.launch {
            val result = runCatching {
                analyticsService.titleSourceOccurrences(
                    filter = state.toStatsFilter(),
                    titleId = title.titleId,
                    offset = offset,
                    limit = DETAIL_PAGE_SIZE,
                )
            }
            if (
                !titleSourcePagingRequests.isCurrent(requestGeneration) ||
                successState()?.selection?.title?.titleId != title.titleId
            ) {
                return@launch
            }
            updateSuccess { latest ->
                val nextLoadable = result.fold(
                    onSuccess = { next ->
                        StatsLoadable(
                            next.copy(
                                value = mergeAnalyticsPages(
                                    current = currentResult.value,
                                    next = next.value,
                                    keyOf = { it.statsOccurrenceKey() },
                                ),
                            ),
                        )
                    },
                    onFailure = {
                        latest.details.titleSources.copy(refreshing = false, error = true)
                    },
                )
                latest.copy(details = latest.details.copy(titleSources = nextLoadable))
            }
        }
    }

    fun loadMoreCharacters() {
        val state = successState() ?: return
        val requestGeneration = sectionPagingRequests.snapshot()
        val currentLoadable = state.sections.characters
        if (currentLoadable.refreshing) return
        val currentResult = currentLoadable.value ?: return
        val offset = currentResult.value.nextOffset ?: return
        updateSuccess {
            it.copy(
                sections = it.sections.copy(characters = it.sections.characters.refreshing()),
            )
        }
        screenModelScope.launch {
            val result = runCatching {
                analyticsService.characters(
                    state.toStatsFilter(),
                    state.characterSort,
                    offset,
                    PAGE_SIZE,
                    state.characterSearch.takeIf(String::isNotBlank),
                    state.characterFilter,
                )
            }
            if (!sectionPagingRequests.isCurrent(requestGeneration)) return@launch
            updateSuccess { latest ->
                if (result.isFailure) {
                    latest.copy(
                        sections = latest.sections.copy(
                            characters = latest.sections.characters.copy(refreshing = false, error = true),
                        ),
                    )
                } else {
                    val next = result.getOrThrow()
                    val mergedPage = mergeAnalyticsPages(
                        current = currentResult.value,
                        next = next.value,
                        keyOf = { it.codePoint },
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
        val requestGeneration = sectionPagingRequests.snapshot()
        val currentLoadable = state.sections.sessions
        if (currentLoadable.refreshing) return
        val currentResult = currentLoadable.value ?: return
        val cursor = currentResult.value.nextCursor ?: return
        updateSuccess {
            it.copy(
                sections = it.sections.copy(sessions = it.sections.sessions.refreshing()),
            )
        }
        screenModelScope.launch {
            val result = runCatching {
                analyticsService.sessions(
                    state.toStatsFilter(),
                    cursor,
                    PAGE_SIZE,
                )
            }
            if (!sectionPagingRequests.isCurrent(requestGeneration)) return@launch
            updateSuccess { latest ->
                if (result.isFailure) {
                    latest.copy(
                        sections = latest.sections.copy(
                            sessions = latest.sections.sessions.copy(refreshing = false, error = true),
                        ),
                    )
                } else {
                    val next = result.getOrThrow()
                    val mergedPage = SessionPage(
                        items = (currentResult.value.items + next.value.items).distinctBy { it.id },
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

    fun saveGoal(values: StatsGoalEditorValues, existing: ImmersionGoal?): Boolean {
        val state = successState() ?: return false
        if (!state.goalsEnabled) return false
        val now = System.currentTimeMillis()
        val today = ImmersionLocalDate.from(today())
        val goal = when {
            existing == null -> createStatsGoal(
                id = UUID.randomUUID().toString(),
                values = values,
                scope = StatsGoalScope(
                    mediaKind = state.filter.mediaKind,
                    profileId = state.filter.profileId,
                    languageTag = state.profileLanguageCode()
                        ?.takeIf(String::isNotBlank)
                        ?.let { runCatching { LanguageTag.from(it) }.getOrNull() },
                    titleId = state.filter.titleId
                        ?.let { runCatching { TitleId(it) }.getOrNull() }
                        ?: fixedTitleId,
                ),
                nowEpochMillis = now,
            )
            values.editMode == StatsGoalEditMode.RESTART_HISTORY -> restartStatsGoalHistory(
                existing = existing,
                replacementId = UUID.randomUUID().toString(),
                values = values,
                restartDate = today,
                nowEpochMillis = now,
            )
            else -> editStatsGoalProspectively(
                existing = existing,
                values = values,
                prospectiveStartDate = today,
                nowEpochMillis = now,
            )
        } ?: return false
        screenModelScope.launch {
            if (existing != null && values.editMode == StatsGoalEditMode.RESTART_HISTORY) {
                analyticsService.restartGoal(
                    expectedGoal = existing,
                    replacementGoal = goal,
                    restartedAtEpochMillis = now,
                )
            } else {
                analyticsService.saveGoal(goal)
            }
            refresh()
        }
        return true
    }

    fun archiveGoal(goal: ImmersionGoal) {
        if (successState()?.goalsEnabled != true) return
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

    fun checkInGoal(goalId: String, note: String?) {
        if (successState()?.goalsEnabled != true) return
        screenModelScope.launch {
            analyticsService.checkIn(
                goalId = goalId,
                date = ImmersionLocalDate.from(today()),
                completed = true,
                note = note?.trim()?.takeIf(String::isNotEmpty),
            )
            refresh()
        }
    }

    private fun updateFilter(transform: (StatsFilterState) -> StatsFilterState) {
        clearPersistedSelection()
        sourceSearchJob?.cancel()
        wordPagingRequests.invalidate()
        characterPagingRequests.invalidate()
        sourcePagingRequests.invalidate()
        sessionDetailRequests.invalidate()
        titleDetailRequests.invalidate()
        titleAcquisitionRequests.invalidate()
        titleSessionPagingRequests.invalidate()
        titleUnitPagingRequests.invalidate()
        titleSourcePagingRequests.invalidate()
        updateSuccess {
            it.copy(
                filter = transform(it.filter),
                sourceSearch = "",
                selection = StatsSelection(),
                details = StatsDetails(),
            )
        }
        refresh()
    }

    private fun restorePersistedSelection() {
        val state = successState() ?: return
        when (state.selectedTab) {
            StatsTab.TITLES -> preferences.dashboardSelectedTitleId().get()
                .takeIf(String::isNotBlank)
                ?.let { selectedId ->
                    state.sections.titles.value?.value?.items
                        ?.find { it.titleId.value == selectedId }
                        ?.let(::selectTitle)
                }
            StatsTab.CHARACTERS -> preferences.dashboardSelectedCharacter().get()
                .takeIf { it >= 0 }
                ?.let { selectedCodePoint ->
                    state.sections.characters.value?.value?.items
                        ?.find { it.codePoint.value == selectedCodePoint }
                        ?.let(::selectCharacter)
                }
            StatsTab.SESSIONS -> preferences.dashboardSelectedSessionId().get()
                .takeIf(String::isNotBlank)
                ?.let { selectedId ->
                    state.sections.sessions.value?.value?.items
                        ?.find { it.id.value == selectedId }
                        ?.let(::selectSession)
                }
            StatsTab.OVERVIEW,
            StatsTab.ACTIVITY,
            StatsTab.GOALS,
            StatsTab.ANKI,
            -> Unit
        }
    }

    private fun clearPersistedSelection() {
        preferences.dashboardSelectedTitleId().delete()
        preferences.dashboardSelectedCharacter().delete()
        preferences.dashboardSelectedSessionId().delete()
    }

    private fun loadOptionalSections(tab: StatsTab) {
        val state = successState() ?: return
        val generation = refreshGeneration.get()
        val filter = state.toStatsFilter()
        when (tab) {
            StatsTab.ACTIVITY -> {
                val loadTemporal = state.sections.temporalActivity.value == null &&
                    !state.sections.temporalActivity.refreshing
                val loadTitleTrends = state.sections.titleTrends.value == null &&
                    !state.sections.titleTrends.refreshing
                if (!loadTemporal && !loadTitleTrends) return
                updateSuccess {
                    it.copy(
                        sections = it.sections.copy(
                            temporalActivity = if (loadTemporal) {
                                it.sections.temporalActivity.refreshing()
                            } else {
                                it.sections.temporalActivity
                            },
                            titleTrends = if (loadTitleTrends) {
                                it.sections.titleTrends.refreshing()
                            } else {
                                it.sections.titleTrends
                            },
                        ),
                    )
                }
                screenModelScope.launch {
                    coroutineScope {
                        buildList {
                            if (loadTemporal) {
                                add(async { loadTemporalActivity(generation, filter) })
                            }
                            if (loadTitleTrends) {
                                add(
                                    async {
                                        loadTitleTrends(
                                            generation,
                                            filter,
                                            state.trendScale,
                                            state.titleTrendSelection,
                                        )
                                    },
                                )
                            }
                        }.awaitAll()
                    }
                }
            }
            else -> Unit
        }
    }

    private suspend fun loadOverview(generation: Long, filter: StatsFilter) =
        updateSection(generation, { it.overview }, { sections, result ->
            sections.copy(overview = result)
        }) {
            analyticsService.overview(filter)
        }

    private suspend fun loadHeatmap(generation: Long, filter: StatsFilter) =
        updateSection(generation, { it.heatmap }, { sections, result ->
            sections.copy(heatmap = result)
        }) {
            analyticsService.trends(
                filter.forHeatmap(today()),
                AnalyticsBucketScale.DAY,
            )
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

    private suspend fun loadTemporalActivity(
        generation: Long,
        filter: StatsFilter,
    ) = updateSection(generation, { it.temporalActivity }, { sections, result ->
        sections.copy(temporalActivity = result)
    }) {
        analyticsService.temporalActivity(filter)
    }

    private suspend fun loadTitleTrends(
        generation: Long,
        filter: StatsFilter,
        scale: AnalyticsBucketScale,
        selection: AnalyticsTitleSeriesSelection,
    ) {
        val result = runCatching {
            analyticsService.titleTrends(
                filter = filter,
                scale = scale,
                selection = selection,
                maxTitles = TITLE_TREND_LIMIT,
            )
        }
        if (refreshGeneration.get() != generation) return
        updateSuccess { state ->
            if (state.titleTrendSelection != selection) return@updateSuccess state
            val previous = state.sections.titleTrends
            state.copy(
                sections = state.sections.copy(
                    titleTrends = result.fold(
                        onSuccess = { StatsLoadable(it) },
                        onFailure = { previous.copy(refreshing = false, error = true) },
                    ),
                ),
            )
        }
    }

    private suspend fun loadTitles(
        generation: Long,
        filter: StatsFilter,
        titleFilter: AnalyticsTitleFilter,
        sort: AnalyticsTitleSort,
    ) {
        val searchQuery = successState()?.titleSearch?.takeIf(String::isNotBlank)
        val result = runCatching {
            analyticsService.titlePage(
                filter = filter,
                titleFilter = titleFilter.copy(searchQuery = searchQuery),
                sort = sort,
                offset = 0,
                limit = PAGE_SIZE,
            )
        }
        val optionResult = runCatching {
            analyticsService.titles(
                StatsFilter(),
                AnalyticsSort.ALPHABETICAL,
            )
        }
        val metadataResult = result.getOrNull()?.let { titles ->
            runCatching { titleMetadataResolver.resolve(titles.value.items) }
        }
        if (refreshGeneration.get() != generation) return
        updateSuccess { state ->
            val previous = state.sections.titles
            val next = result.fold(
                onSuccess = { StatsLoadable(it) },
                onFailure = { previous.copy(refreshing = false, error = true) },
            )
            state.copy(
                sections = state.sections.copy(titles = next),
                titleOptions = optionResult.getOrNull()?.value ?: state.titleOptions,
                titleMetadata = metadataResult?.getOrNull() ?: state.titleMetadata,
            )
        }
    }

    private suspend fun loadCharacters(
        generation: Long,
        filter: StatsFilter,
        characterFilter: AnalyticsCharacterFilter,
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
            characterFilter,
        )
    }

    private suspend fun loadCharacterSummary(
        generation: Long,
        filter: StatsFilter,
        characterFilter: AnalyticsCharacterFilter,
    ) = updateSection(generation, { it.characterSummary }, { sections, result ->
        sections.copy(characterSummary = result)
    }) {
        analyticsService.characterSummary(filter, characterFilter)
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
        result.exceptionOrNull()?.let {
            // Same reasoning as toStatsLoadable: the section card is deliberately vague, so the
            // cause has to reach the log or nobody can tell why a section went blank.
            logcat(LogPriority.ERROR, it) { "Statistics section refresh failed" }
        }
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

    private suspend fun repairAllDirtyRollups() {
        while (analyticsService.repairDirtyRollups(ROLLUP_REPAIR_BATCH_SIZE).isNotEmpty()) {
            // Keep draining: one deleted session can dirty more ranges than a single batch returns.
        }
    }

    private fun observeFeatureFlags() {
        screenModelScope.launch {
            combine(
                preferences.goalsEnabled().changes().onStart {
                    emit(preferences.goalsEnabled().get())
                },
                preferences.ankiSyncEnabled().changes().onStart {
                    emit(preferences.ankiSyncEnabled().get())
                },
            ) { goalsEnabled, ankiEnabled -> goalsEnabled to ankiEnabled }
                .collect { (goalsEnabled, ankiEnabled) ->
                    val current = successState() ?: return@collect
                    if (
                        current.goalsEnabled == goalsEnabled &&
                        current.ankiEnabled == ankiEnabled
                    ) {
                        return@collect
                    }
                    val selectedTab = resolveEnabledStatsTab(
                        current.selectedTab,
                        goalsEnabled,
                        ankiEnabled,
                    )
                    if (selectedTab != current.selectedTab) {
                        preferences.dashboardSelectedTab().set(selectedTab.name)
                        clearPersistedSelection()
                    }
                    updateSuccess {
                        it.copy(
                            selectedTab = selectedTab,
                            goalsEnabled = goalsEnabled,
                            ankiEnabled = ankiEnabled,
                            sections = it.sections.copy(
                                goals = if (goalsEnabled) it.sections.goals else StatsLoadable(),
                                anki = if (ankiEnabled) it.sections.anki else StatsLoadable(),
                            ),
                        )
                    }
                    refresh()
                }
        }
    }

    private fun successState(): StatsScreenState.Success? = mutableState.value as? StatsScreenState.Success

    private fun StatsScreenState.Success.profileLanguageCode(): String? =
        profiles.find { it.id == filter.profileId }?.languageCode

    private fun StatsScreenState.Success.toStatsFilter(): StatsFilter {
        val profile = profiles.find { it.id == filter.profileId }
        val aggregation = profile
            ?.ankiStatsMaturityAggregation
            ?.let { runCatching { AnkiMaturityAggregation.valueOf(it) }.getOrNull() }
            ?: AnkiMaturityAggregation.MAX_INTERVAL
        return filter.toStatsFilter(
            now = today(),
            profileLanguageCode = profile?.languageCode,
            ankiMaturityAggregation = aggregation,
        )
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val DETAIL_PAGE_SIZE = 50
        const val TITLE_TREND_LIMIT = 5
        const val ROLLUP_REPAIR_BATCH_SIZE = 366
        const val CHARACTER_ALL_SCRIPTS_SENTINEL = "*"
    }
}

internal fun sessionRelinkTargets(
    session: ImmersionSession,
    titles: List<tachiyomi.domain.immersion.model.AnalyticsTitleRow>,
    query: String,
    limit: Int = 20,
): List<tachiyomi.domain.immersion.model.AnalyticsTitleRow> {
    require(limit > 0)
    val normalizedQuery = query.trim()
    return titles.asSequence()
        .filter { title ->
            title.titleId != session.titleId &&
                title.mediaKind == session.mediaKind &&
                title.profileId == session.profileId &&
                title.languageTag == session.languageTag
        }
        .filter { title ->
            normalizedQuery.isEmpty() ||
                title.displayTitle.contains(normalizedQuery, ignoreCase = true) ||
                title.sourceKey.contains(normalizedQuery, ignoreCase = true)
        }
        .take(limit)
        .toList()
}

private fun StatsSections.refreshing(
    selectedTab: StatsTab,
    goalsEnabled: Boolean,
    ankiEnabled: Boolean,
): StatsSections = copy(
    overview = overview.refreshing(),
    heatmap = heatmap.refreshing(),
    trends = trends.refreshing(),
    temporalActivity = if (selectedTab == StatsTab.ACTIVITY) {
        temporalActivity.refreshing()
    } else {
        StatsLoadable()
    },
    titleTrends = if (selectedTab == StatsTab.ACTIVITY) {
        titleTrends.refreshing()
    } else {
        StatsLoadable()
    },
    titles = titles.refreshing(),
    characters = characters.refreshing(),
    characterSummary = characterSummary.refreshing(),
    sessions = sessions.refreshing(),
    goals = if (goalsEnabled) goals.refreshing() else StatsLoadable(),
    anki = if (ankiEnabled) anki.refreshing() else StatsLoadable(),
)

private inline fun <reified T : Enum<T>> String.enumOrNull(): T? =
    runCatching { enumValueOf<T>(this) }.getOrNull()

private inline fun <reified T : Enum<T>> String.enumOrDefault(default: T): T =
    enumOrNull() ?: default

private inline fun <reified T : Enum<T>> Set<String>.decodeEnums(): Set<T> =
    mapNotNullTo(linkedSetOf()) { value ->
        runCatching { enumValueOf<T>(value) }.getOrNull()
    }

private fun Long.positiveOrNull(): Long? = takeIf { it > 0 }

private data class TitleDetailQueryResults(
    val captureExcluded: Result<Boolean>,
    val trends: Result<AnalyticsResult<AnalyticsTrends>>,
    val sessions: Result<AnalyticsResult<SessionPage>>,
    val completedUnits: Result<AnalyticsResult<AnalyticsPage<AnalyticsTitleCompletedUnit>>>,
    val sources: Result<AnalyticsResult<AnalyticsPage<AnalyticsSourceOccurrence>>>,
)

private fun <T> Result<T>.toStatsLoadable(): StatsLoadable<T> =
    fold(
        onSuccess = { StatsLoadable(value = it) },
        onFailure = {
            // The screen only shows "this section could not be loaded", so without this the
            // cause never leaves the process and the failure is undiagnosable from a bug report.
            logcat(LogPriority.ERROR, it) { "Statistics section query failed" }
            StatsLoadable(error = true)
        },
    )

private fun String.toImmersionLocalDateOrNull(): ImmersionLocalDate? =
    takeIf(String::isNotBlank)
        ?.let { runCatching { ImmersionLocalDate.parse(it) }.getOrNull() }
