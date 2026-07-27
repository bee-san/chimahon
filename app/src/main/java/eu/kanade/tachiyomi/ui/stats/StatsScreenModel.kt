package eu.kanade.tachiyomi.ui.stats

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.presentation.more.stats.StatsDetails
import eu.kanade.presentation.more.stats.StatsFilterState
import eu.kanade.presentation.more.stats.StatsLoadable
import eu.kanade.presentation.more.stats.StatsRangePreset
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.more.stats.StatsSections
import eu.kanade.presentation.more.stats.StatsSelection
import eu.kanade.presentation.more.stats.StatsTab
import eu.kanade.presentation.more.stats.StatsTrendMetric
import eu.kanade.presentation.more.stats.decodePersistedStatsFilterSelection
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
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
import tachiyomi.domain.immersion.model.AnalyticsBucketScale
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.AnalyticsTitleSeriesSelection
import tachiyomi.domain.immersion.model.AnkiMaturityAggregation
import tachiyomi.domain.immersion.model.CharacterMetric
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.MaturityTier
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.ProvenanceState
import tachiyomi.domain.immersion.model.RawTextRetention
import tachiyomi.domain.immersion.model.SessionPage
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.model.VocabularyCategory
import tachiyomi.domain.immersion.model.VocabularyExclusion
import tachiyomi.domain.immersion.model.VocabularyFilter
import tachiyomi.domain.immersion.model.VocabularyKnownness
import tachiyomi.domain.immersion.model.VocabularyScript
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
    private val exportService: ImmersionExportService = Injekt.get(),
    private val today: () -> LocalDate = LocalDate::now,
) : StateScreenModel<StatsScreenState>(StatsScreenState.Loading) {

    private val fixedTitleId = titleId
    private val refreshGeneration = AtomicLong()
    private val sectionPagingRequests = StatsPagingRequestTracker()
    private val wordPagingRequests = StatsPagingRequestTracker()
    private val characterPagingRequests = StatsPagingRequestTracker()
    private val sourcePagingRequests = StatsPagingRequestTracker()
    private val sessionDetailRequests = StatsPagingRequestTracker()
    private var refreshJob: Job? = null
    private var vocabularySearchJob: Job? = null
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
            vocabularySort = preferences.dashboardVocabularySort().get(),
            vocabularyFilter = VocabularyFilter(
                knownness = preferences.dashboardVocabularyKnownness().get()
                    .enumOrDefault(VocabularyKnownness.ALL),
                scripts = preferences.dashboardVocabularyScripts().get()
                    .decodeEnums<VocabularyScript>(),
                categories = preferences.dashboardVocabularyCategories().get()
                    .decodeEnums<VocabularyCategory>(),
                partOfSpeechQuery = preferences.dashboardVocabularyPartOfSpeech().get()
                    .takeIf(String::isNotBlank),
                minimumOccurrences = preferences.dashboardVocabularyMinimumOccurrences().get()
                    .positiveOrNull(),
                maximumOccurrences = preferences.dashboardVocabularyMaximumOccurrences().get()
                    .positiveOrNull(),
                maximumFrequencyRank = preferences.dashboardVocabularyMaximumFrequencyRank().get()
                    .positiveOrNull(),
                exclusion = preferences.dashboardVocabularyExclusion().get()
                    .enumOrDefault(VocabularyExclusion.INCLUDED),
            ),
            characterSort = preferences.dashboardCharacterSort().get(),
        )
        refresh()
        observeFeatureFlags()
    }

    fun refresh() {
        refreshJob?.cancel()
        sectionPagingRequests.invalidate()
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
                    async { loadTitles(generation, filter, current.titleSort) },
                    async { loadVocabulary(generation, filter, current.vocabularySort) },
                    async {
                        if (current.selectedTab == StatsTab.VOCABULARY) {
                            loadVocabularyGrowth(generation, filter, current.trendScale)
                        }
                    },
                    async { loadCharacters(generation, filter, current.characterSort) },
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
            if (restoreSelectionAfterRefresh && refreshGeneration.get() == generation) {
                restoreSelectionAfterRefresh = false
                restorePersistedSelection()
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
        updateSuccess {
            it.copy(
                selectedTab = tab,
                selection = StatsSelection(),
                selectedVocabularyWordIds = emptySet(),
                vocabularyMutationError = false,
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

    fun selectTitleSort(sort: AnalyticsSort) {
        preferences.dashboardTitleSort().set(sort)
        updateSuccess { it.copy(titleSort = sort) }
        refresh()
    }

    fun selectVocabularySort(sort: AnalyticsSort) {
        preferences.dashboardVocabularySort().set(sort)
        updateSuccess {
            it.copy(
                vocabularySort = sort,
                selectedVocabularyWordIds = emptySet(),
            )
        }
        refresh()
    }

    fun updateVocabularyFilter(filter: VocabularyFilter) {
        val normalized = filter.copy(
            searchQuery = null,
            partOfSpeechQuery = filter.partOfSpeechQuery
                ?.trim()
                ?.takeIf(String::isNotEmpty),
        )
        preferences.dashboardVocabularyKnownness().set(normalized.knownness.name)
        preferences.dashboardVocabularyScripts().set(normalized.scripts.mapTo(linkedSetOf()) { it.name })
        preferences.dashboardVocabularyCategories().set(
            normalized.categories.mapTo(linkedSetOf()) { it.name },
        )
        preferences.dashboardVocabularyPartOfSpeech().set(normalized.partOfSpeechQuery.orEmpty())
        preferences.dashboardVocabularyMinimumOccurrences().set(normalized.minimumOccurrences ?: -1)
        preferences.dashboardVocabularyMaximumOccurrences().set(normalized.maximumOccurrences ?: -1)
        preferences.dashboardVocabularyMaximumFrequencyRank().set(normalized.maximumFrequencyRank ?: -1)
        preferences.dashboardVocabularyExclusion().set(normalized.exclusion.name)
        wordPagingRequests.invalidate()
        preferences.dashboardSelectedWordId().delete()
        updateSuccess {
            it.copy(
                vocabularyFilter = normalized,
                selectedVocabularyWordIds = emptySet(),
                vocabularyMutationError = false,
                selection = it.selection.copy(word = null),
                details = it.details.copy(wordOccurrences = StatsLoadable()),
            )
        }
        refresh()
    }

    fun setVocabularyWordSelected(wordId: String, selected: Boolean) {
        updateSuccess { state ->
            val selection = state.selectedVocabularyWordIds.toMutableSet().apply {
                if (selected) add(wordId) else remove(wordId)
            }
            state.copy(
                selectedVocabularyWordIds = selection,
                vocabularyMutationError = false,
            )
        }
    }

    fun clearVocabularyWordSelection() {
        updateSuccess {
            it.copy(
                selectedVocabularyWordIds = emptySet(),
                vocabularyMutationError = false,
            )
        }
    }

    fun setSelectedVocabularyWordsExcluded(excluded: Boolean) {
        val selected = successState()?.selectedVocabularyWordIds.orEmpty()
        if (selected.isEmpty()) return
        updateSuccess {
            it.copy(
                vocabularyMutationInProgress = true,
                vocabularyMutationError = false,
            )
        }
        screenModelScope.launch {
            val result = runCatching {
                maintenanceRepository.setWordExclusions(
                    wordIds = selected,
                    excluded = excluded,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                )
                repairAllDirtyRollups()
            }
            if (result.isSuccess) {
                preferences.dashboardSelectedWordId().delete()
                updateSuccess {
                    it.copy(
                        selectedVocabularyWordIds = emptySet(),
                        vocabularyMutationInProgress = false,
                        vocabularyMutationError = false,
                        selection = it.selection.copy(word = null),
                        details = it.details.copy(wordOccurrences = StatsLoadable()),
                    )
                }
                refresh()
            } else {
                updateSuccess {
                    it.copy(
                        vocabularyMutationInProgress = false,
                        vocabularyMutationError = true,
                    )
                }
            }
        }
    }

    fun exportVocabulary() {
        val state = successState() ?: return
        screenModelScope.launch {
            mutableExportDocuments.emit(
                exportService.vocabularyCsv(
                    filter = state.toStatsFilter(),
                    vocabularyFilter = state.vocabularyFilter.copy(
                        searchQuery = state.vocabularySearch.takeIf(String::isNotBlank),
                    ),
                    sort = state.vocabularySort,
                ),
            )
        }
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
        sectionPagingRequests.invalidate()
        updateSuccess {
            it.copy(
                vocabularySearch = query,
                selectedVocabularyWordIds = emptySet(),
                vocabularyMutationError = false,
                sections = it.sections.copy(vocabulary = it.sections.vocabulary.refreshing()),
            )
        }
        vocabularySearchJob?.cancel()
        vocabularySearchJob = screenModelScope.launch {
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
        preferences.dashboardSelectedTitleId().set(title?.titleId?.value.orEmpty())
        updateSuccess {
            it.copy(
                selection = it.selection.copy(title = title),
                details = it.details.copy(
                    titleCaptureExcluded = StatsLoadable(refreshing = title != null),
                ),
            )
        }
        if (title != null) {
            screenModelScope.launch {
                val result = runCatching {
                    maintenanceRepository.isTitleCaptureExcluded(title.titleId)
                }
                if (successState()?.selection?.title?.titleId != title.titleId) return@launch
                updateSuccess {
                    it.copy(
                        details = it.details.copy(
                            titleCaptureExcluded = result.fold(
                                onSuccess = { excluded -> StatsLoadable(excluded) },
                                onFailure = { StatsLoadable(error = true) },
                            ),
                        ),
                    )
                }
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

    fun selectWord(word: tachiyomi.domain.immersion.model.AnalyticsWordRow?) {
        val requestGeneration = wordPagingRequests.invalidate()
        preferences.dashboardSelectedWordId().set(word?.id.orEmpty())
        updateSuccess {
            it.copy(
                selection = it.selection.copy(word = word),
                details = it.details.copy(
                    wordOccurrences = if (word == null) {
                        StatsLoadable()
                    } else {
                        StatsLoadable(refreshing = true)
                    },
                ),
            )
        }
        if (word != null) {
            screenModelScope.launch {
                val state = successState() ?: return@launch
                val result = runCatching {
                    analyticsService.wordOccurrences(
                        state.toStatsFilter(),
                        word.id,
                        0,
                        DETAIL_PAGE_SIZE,
                    )
                }
                if (
                    !wordPagingRequests.isCurrent(requestGeneration) ||
                    successState()?.selection?.word?.id != word.id
                ) {
                    return@launch
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
                    characterContainingWords = if (character == null) {
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
                val (occurrences, containingWords) = coroutineScope {
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
                    val containingWordResult = async {
                        runCatching {
                            analyticsService.characterContainingWords(
                                filter = filter,
                                codePoint = character.codePoint,
                                sort = AnalyticsSort.MOST_OCCURRENCES,
                                offset = 0,
                                limit = DETAIL_PAGE_SIZE,
                            )
                        }
                    }
                    occurrenceResult.await() to containingWordResult.await()
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
                            characterContainingWords = containingWords.fold(
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
                ),
            )
        }
        if (session != null) {
            screenModelScope.launch {
                val state = successState() ?: return@launch
                val result = runCatching {
                    analyticsService.sessionDetail(
                        state.toStatsFilter(),
                        session.id,
                    )
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
            repairAllDirtyRollups()
            selectSession(null)
            refresh()
        }
    }

    fun searchSources(query: String) {
        sourceSearchJob?.cancel()
        val requestGeneration = sourcePagingRequests.invalidate()
        updateSuccess {
            it.copy(
                sourceSearch = query,
                details = it.details.copy(
                    sourceSearch = if (query.isBlank()) {
                        StatsLoadable()
                    } else {
                        StatsLoadable(refreshing = true)
                    },
                ),
            )
        }
        if (query.isBlank()) {
            return
        }
        sourceSearchJob = screenModelScope.launch {
            delay(250)
            val state = successState() ?: return@launch
            val result = runCatching {
                analyticsService.sourceSearch(
                    state.toStatsFilter(),
                    query,
                    0,
                    DETAIL_PAGE_SIZE,
                )
            }
            if (
                !sourcePagingRequests.isCurrent(requestGeneration) ||
                successState()?.sourceSearch != query
            ) {
                return@launch
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

    fun loadMoreWordOccurrences() {
        val state = successState() ?: return
        val requestGeneration = wordPagingRequests.snapshot()
        val word = state.selection.word ?: return
        val currentLoadable = state.details.wordOccurrences
        if (currentLoadable.refreshing) return
        val currentResult = currentLoadable.value ?: return
        val offset = currentResult.value.nextOffset ?: return
        updateSuccess {
            it.copy(
                details = it.details.copy(
                    wordOccurrences = it.details.wordOccurrences.refreshing(),
                ),
            )
        }
        screenModelScope.launch {
            val result = runCatching {
                analyticsService.wordOccurrences(
                    filter = state.toStatsFilter(),
                    wordId = word.id,
                    offset = offset,
                    limit = DETAIL_PAGE_SIZE,
                )
            }
            if (
                !wordPagingRequests.isCurrent(requestGeneration) ||
                successState()?.selection?.word?.id != word.id
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
                        latest.details.wordOccurrences.copy(refreshing = false, error = true)
                    },
                )
                latest.copy(details = latest.details.copy(wordOccurrences = nextLoadable))
            }
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

    fun loadMoreSourceSearch() {
        val state = successState() ?: return
        val requestGeneration = sourcePagingRequests.snapshot()
        val query = state.sourceSearch.takeIf(String::isNotBlank) ?: return
        val currentLoadable = state.details.sourceSearch
        if (currentLoadable.refreshing) return
        val currentResult = currentLoadable.value ?: return
        val offset = currentResult.value.nextOffset ?: return
        updateSuccess {
            it.copy(
                details = it.details.copy(
                    sourceSearch = it.details.sourceSearch.refreshing(),
                ),
            )
        }
        screenModelScope.launch {
            val result = runCatching {
                analyticsService.sourceSearch(
                    filter = state.toStatsFilter(),
                    query = query,
                    offset = offset,
                    limit = DETAIL_PAGE_SIZE,
                )
            }
            if (
                !sourcePagingRequests.isCurrent(requestGeneration) ||
                successState()?.sourceSearch != query
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
                        latest.details.sourceSearch.copy(refreshing = false, error = true)
                    },
                )
                latest.copy(details = latest.details.copy(sourceSearch = nextLoadable))
            }
        }
    }

    fun loadMoreVocabulary() {
        val state = successState() ?: return
        val requestGeneration = sectionPagingRequests.snapshot()
        val currentLoadable = state.sections.vocabulary
        if (currentLoadable.refreshing) return
        val currentResult = currentLoadable.value ?: return
        val offset = currentResult.value.nextOffset ?: return
        updateSuccess {
            it.copy(
                sections = it.sections.copy(vocabulary = it.sections.vocabulary.refreshing()),
            )
        }
        screenModelScope.launch {
            val result = runCatching {
                analyticsService.vocabulary(
                    state.toStatsFilter(),
                    state.vocabularyFilter.copy(
                        searchQuery = state.vocabularySearch.takeIf(String::isNotBlank),
                    ),
                    state.vocabularySort,
                    offset,
                    PAGE_SIZE,
                )
            }
            if (!sectionPagingRequests.isCurrent(requestGeneration)) return@launch
            updateSuccess { latest ->
                if (result.isFailure) {
                    latest.copy(
                        sections = latest.sections.copy(
                            vocabulary = latest.sections.vocabulary.copy(refreshing = false, error = true),
                        ),
                    )
                } else {
                    val next = result.getOrThrow()
                    val mergedPage = mergeAnalyticsPages(
                        current = currentResult.value,
                        next = next.value,
                        keyOf = { it.id },
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

    fun loadMoreCharacterContainingWords() {
        val state = successState() ?: return
        val requestGeneration = characterPagingRequests.snapshot()
        val character = state.selection.character ?: return
        val currentLoadable = state.details.characterContainingWords
        if (currentLoadable.refreshing) return
        val currentResult = currentLoadable.value ?: return
        val offset = currentResult.value.nextOffset ?: return
        updateSuccess {
            it.copy(
                details = it.details.copy(
                    characterContainingWords = it.details.characterContainingWords.refreshing(),
                ),
            )
        }
        screenModelScope.launch {
            val result = runCatching {
                analyticsService.characterContainingWords(
                    filter = state.toStatsFilter(),
                    codePoint = character.codePoint,
                    sort = AnalyticsSort.MOST_OCCURRENCES,
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
                if (result.isFailure) {
                    latest.copy(
                        details = latest.details.copy(
                            characterContainingWords = latest.details.characterContainingWords.copy(
                                refreshing = false,
                                error = true,
                            ),
                        ),
                    )
                } else {
                    val next = result.getOrThrow()
                    val mergedPage = mergeAnalyticsPages(
                        current = currentResult.value,
                        next = next.value,
                        keyOf = { it.id },
                    )
                    latest.copy(
                        details = latest.details.copy(
                            characterContainingWords = StatsLoadable(next.copy(value = mergedPage)),
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
        val goal = if (existing == null) {
            createStatsGoal(
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
        } else {
            editStatsGoalProspectively(
                existing = existing,
                values = values,
                prospectiveStartDate = today,
                nowEpochMillis = now,
            )
        } ?: return false
        screenModelScope.launch {
            analyticsService.saveGoal(goal)
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

    fun checkInGoal(goalId: String) {
        if (successState()?.goalsEnabled != true) return
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
        clearPersistedSelection()
        sourceSearchJob?.cancel()
        wordPagingRequests.invalidate()
        characterPagingRequests.invalidate()
        sourcePagingRequests.invalidate()
        sessionDetailRequests.invalidate()
        updateSuccess {
            it.copy(
                filter = transform(it.filter),
                sourceSearch = "",
                selection = StatsSelection(),
                selectedVocabularyWordIds = emptySet(),
                vocabularyMutationError = false,
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
                    state.sections.titles.value?.value
                        ?.find { it.titleId.value == selectedId }
                        ?.let(::selectTitle)
                }
            StatsTab.VOCABULARY -> preferences.dashboardSelectedWordId().get()
                .takeIf(String::isNotBlank)
                ?.let { selectedId ->
                    state.sections.vocabulary.value?.value?.items
                        ?.find { it.id == selectedId }
                        ?.let(::selectWord)
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
        preferences.dashboardSelectedWordId().delete()
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
            StatsTab.VOCABULARY -> {
                if (
                    state.sections.vocabularyGrowth.value != null ||
                    state.sections.vocabularyGrowth.refreshing
                ) {
                    return
                }
                updateSuccess {
                    it.copy(
                        sections = it.sections.copy(
                            vocabularyGrowth = it.sections.vocabularyGrowth.refreshing(),
                        ),
                    )
                }
                screenModelScope.launch {
                    loadVocabularyGrowth(generation, filter, state.trendScale)
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

    private suspend fun loadVocabularyGrowth(
        generation: Long,
        filter: StatsFilter,
        scale: AnalyticsBucketScale,
    ) = updateSection(generation, { it.vocabularyGrowth }, { sections, result ->
        sections.copy(vocabularyGrowth = result)
    }) {
        analyticsService.vocabularyFirstSeen(filter, scale)
    }

    private suspend fun loadTitles(
        generation: Long,
        filter: StatsFilter,
        sort: AnalyticsSort,
    ) {
        val result = runCatching { analyticsService.titles(filter, sort) }
        val optionResult = if (
            fixedTitleId == null &&
            filter.titleIds.isNotEmpty() &&
            successState()?.titleOptions.isNullOrEmpty()
        ) {
            runCatching {
                analyticsService.titles(
                    filter.copy(titleIds = emptySet()),
                    AnalyticsSort.ALPHABETICAL,
                )
            }
        } else {
            null
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
                titleOptions = if (result.isSuccess && filter.titleIds.isEmpty()) {
                    result.getOrThrow().value
                } else {
                    optionResult?.getOrNull()?.value ?: state.titleOptions
                },
            )
        }
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
            successState()?.let { state ->
                state.vocabularyFilter.copy(
                    searchQuery = state.vocabularySearch.takeIf(String::isNotBlank),
                )
            } ?: VocabularyFilter(),
            sort,
            0,
            PAGE_SIZE,
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
    }
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
    vocabulary = vocabulary.refreshing(),
    vocabularyGrowth = if (selectedTab == StatsTab.VOCABULARY) {
        vocabularyGrowth.refreshing()
    } else {
        StatsLoadable()
    },
    characters = characters.refreshing(),
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

private fun String.toImmersionLocalDateOrNull(): ImmersionLocalDate? =
    takeIf(String::isNotBlank)
        ?.let { runCatching { ImmersionLocalDate.parse(it) }.getOrNull() }
