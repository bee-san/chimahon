// SPDX-License-Identifier: MIT

package mihon.feature.stats.capture

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tachiyomi.domain.immersion.model.CapabilityState
import tachiyomi.domain.immersion.model.CharacterVolume
import tachiyomi.domain.immersion.model.ContentHash
import tachiyomi.domain.immersion.model.EventType
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.ImmersionSourceUnit
import tachiyomi.domain.immersion.model.ImmersionTitle
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.MangaSourceLocator
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.NetCharacterProgress
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.RawTextRetention
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SourceUnitId
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.service.ActiveTimePolicyDifference
import tachiyomi.domain.immersion.service.CaptureCommand
import tachiyomi.domain.immersion.service.DefaultUnicodeCountPolicy
import tachiyomi.domain.immersion.service.FinalizeReason
import tachiyomi.domain.immersion.service.ImmersionRecorder
import tachiyomi.domain.immersion.service.ImmersionShadowIdentity
import tachiyomi.domain.immersion.service.ImmersionShadowReconciler
import tachiyomi.domain.immersion.service.ImmersionShadowResult
import tachiyomi.domain.immersion.service.ImmersionShadowTotals
import tachiyomi.domain.immersion.service.InteractionProvenance
import tachiyomi.domain.immersion.service.PauseReason
import tachiyomi.domain.immersion.service.ReadingTimeTolerance
import tachiyomi.domain.immersion.service.RecordResult
import tachiyomi.domain.immersion.service.ResumeReason
import tachiyomi.domain.immersion.service.SessionContext
import tachiyomi.domain.immersion.service.SessionHandle
import tachiyomi.domain.immersion.service.SessionStartResult
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

data class MangaCaptureTitle(
    val mangaId: Long,
    val sourceId: Long,
    val displayTitle: String,
    val profileId: String,
    val languageTag: LanguageTag?,
    val createdAtEpochMillis: Long,
) {
    init {
        require(mangaId >= 0) { "Manga ID cannot be negative" }
        require(displayTitle.isNotBlank()) { "Manga title cannot be blank" }
        require(createdAtEpochMillis >= 0) { "Manga creation time cannot be negative" }
    }
}

data class MangaPageKey(
    val chapterId: Long,
    val pageIndex: Int,
) {
    init {
        require(chapterId >= 0 && pageIndex >= 0) { "Manga page identity cannot be negative" }
    }
}

enum class MangaOcrAvailability {
    AVAILABLE,
    NOT_REQUESTED,
    UNSUPPORTED,
    FAILED,
}

/**
 * Page-space viewport after viewer layout. A page becomes an exposure at 50% visible area.
 * OCR blocks independently become exposures when 50% of their vertical page-space area is visible.
 */
data class MangaPageViewport(
    val key: MangaPageKey,
    val visibleTop: Float = 0f,
    val visibleBottom: Float = 1f,
    val ocrAvailability: MangaOcrAvailability = MangaOcrAvailability.NOT_REQUESTED,
) {
    init {
        require(visibleTop in 0f..1f && visibleBottom in 0f..1f && visibleBottom > visibleTop) {
            "Manga page viewport must be a non-empty normalized range"
        }
    }
}

data class MangaOcrBlockCapture(
    val text: String,
    val blockId: String? = null,
    val xmin: Float,
    val ymin: Float,
    val xmax: Float,
    val ymax: Float,
    val engineId: String,
    val engineVersion: Int,
    val confidence: Double? = null,
) {
    init {
        require(text.isNotBlank()) { "OCR block text cannot be blank" }
        require(xmin in 0f..1f && ymin in 0f..1f && xmax in 0f..1f && ymax in 0f..1f) {
            "OCR block bounds must be normalized"
        }
        require(xmax > xmin && ymax > ymin) { "OCR block bounds must be non-empty" }
        require(engineId.isNotBlank()) { "OCR engine ID cannot be blank" }
        require(engineVersion > 0) { "OCR engine version must be positive" }
        require(confidence == null || confidence in 0.0..1.0) {
            "OCR confidence must be between zero and one"
        }
    }
}

data class MangaOcrCoverageSnapshot(
    val viewedPages: Int = 0,
    val ocrCoveredPages: Int = 0,
    val unavailablePages: Int = 0,
) {
    val ratio: Double?
        get() = viewedPages.takeIf { it > 0 }?.let { ocrCoveredPages.toDouble() / it }
}

enum class MangaCaptureOverlay {
    READER_DIALOG,
    STATISTICS,
    LOOKUP,
    MANUAL_PAUSE,
}

data class LegacyMangaSessionSnapshot(
    val activeDurationMillis: Long,
    val characters: Long,
    val equivalentPolicy: Boolean,
) {
    init {
        require(activeDurationMillis >= 0) { "Legacy active duration cannot be negative" }
        require(characters >= 0) { "Legacy character count cannot be negative" }
    }
}

data class MangaReconciliationEntry(
    val scope: MangaReconciliationScope,
    val key: String,
    val legacyComparable: Boolean,
    val result: ImmersionShadowResult?,
)

enum class MangaReconciliationScope {
    SESSION,
    DAY,
}

data class MangaReconciliationReport(
    val entries: List<MangaReconciliationEntry> = emptyList(),
)

object MangaCaptureReconciliationReporter {
    private const val MAX_REPORT_ENTRIES = 100
    private val mutableReport = MutableStateFlow(MangaReconciliationReport())
    private val dayTotals = linkedMapOf<String, ReconciliationAccumulator>()
    val report: StateFlow<MangaReconciliationReport> = mutableReport.asStateFlow()

    @Synchronized
    fun record(
        session: ImmersionSession,
        legacy: LegacyMangaSessionSnapshot,
        idleToleranceMillis: Long,
        zoneId: ZoneId,
    ) {
        val result = reconcile(session, legacy, idleToleranceMillis)
        val sessionEntry = MangaReconciliationEntry(
            scope = MangaReconciliationScope.SESSION,
            key = session.id.value,
            legacyComparable = legacy.equivalentPolicy,
            result = result,
        )
        val day = Instant.ofEpochMilli(session.endedAtEpochMillis ?: session.startedAtEpochMillis)
            .atZone(zoneId)
            .toLocalDate()
            .toString()
        val accumulator = dayTotals.getOrPut(day) { ReconciliationAccumulator() }
        accumulator.recordedActiveMillis += session.activeDuration.value
        accumulator.recordedCharacters += session.grossCharacters.value
        accumulator.sessionIds += session.id
        if (legacy.equivalentPolicy) {
            accumulator.legacyActiveMillis += legacy.activeDurationMillis
            accumulator.legacyCharacters += legacy.characters
            accumulator.comparableSessions += 1
        } else {
            accumulator.hasNonComparableSession = true
        }
        val dayEntry = accumulator.toEntry(day, idleToleranceMillis)
        mutableReport.value = MangaReconciliationReport(
            entries = (listOf(sessionEntry, dayEntry) + mutableReport.value.entries)
                .distinctBy { it.scope to it.key }
                .take(MAX_REPORT_ENTRIES),
        )
    }

    @Synchronized
    fun resetForTest() {
        dayTotals.clear()
        mutableReport.value = MangaReconciliationReport()
    }

    private fun reconcile(
        session: ImmersionSession,
        legacy: LegacyMangaSessionSnapshot,
        idleToleranceMillis: Long,
    ): ImmersionShadowResult? {
        if (!legacy.equivalentPolicy) return null
        return ImmersionShadowReconciler.reconcile(
            recorded = ImmersionShadowTotals(session.activeDuration.value, session.grossCharacters.value),
            legacy = ImmersionShadowTotals(legacy.activeDurationMillis, legacy.characters),
            identity = ImmersionShadowIdentity(listOf(session.id), emptyList()),
            readingTimeTolerance = ReadingTimeTolerance.DocumentedPolicyChange(
                maximumDiscrepancyMillis = idleToleranceMillis,
                policy = ActiveTimePolicyDifference.IDLE_EXCLUDED,
            ),
        )
    }

    private data class ReconciliationAccumulator(
        var recordedActiveMillis: Long = 0,
        var recordedCharacters: Long = 0,
        var legacyActiveMillis: Long = 0,
        var legacyCharacters: Long = 0,
        var comparableSessions: Int = 0,
        var hasNonComparableSession: Boolean = false,
        val sessionIds: MutableList<SessionId> = mutableListOf(),
    ) {
        fun toEntry(day: String, idleToleranceMillis: Long): MangaReconciliationEntry {
            val comparable = !hasNonComparableSession && comparableSessions == sessionIds.size
            val result = if (comparable) {
                ImmersionShadowReconciler.reconcile(
                    recorded = ImmersionShadowTotals(recordedActiveMillis, recordedCharacters),
                    legacy = ImmersionShadowTotals(legacyActiveMillis, legacyCharacters),
                    identity = ImmersionShadowIdentity(sessionIds, emptyList()),
                    readingTimeTolerance = ReadingTimeTolerance.DocumentedPolicyChange(
                        maximumDiscrepancyMillis = idleToleranceMillis * sessionIds.size,
                        policy = ActiveTimePolicyDifference.IDLE_EXCLUDED,
                    ),
                )
            } else {
                null
            }
            return MangaReconciliationEntry(MangaReconciliationScope.DAY, day, comparable, result)
        }
    }
}

class MangaCaptureAdapter(
    captureTitle: MangaCaptureTitle,
    private val recorder: ImmersionRecorder,
    private val rawTextRetention: () -> RawTextRetention,
    private val idleTimeoutMillis: Long,
    private val incognito: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
    workerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val sourceKey = "manga:${captureTitle.mangaId}"
    private val titleId = TitleId(stableUuid(TITLE_NAMESPACE, "$sourceKey|${captureTitle.profileId}"))
    private val title = ImmersionTitle(
        id = titleId,
        mediaKind = MediaKind.MANGA,
        sourceKey = sourceKey,
        profileId = captureTitle.profileId,
        languageTag = captureTitle.languageTag,
        displayTitle = captureTitle.displayTitle,
        libraryId = captureTitle.mangaId,
        mediaId = captureTitle.mangaId.toString(),
        createdAtEpochMillis = captureTitle.createdAtEpochMillis,
        updatedAtEpochMillis = maxOf(captureTitle.createdAtEpochMillis, clock()),
    )
    private val mangaId = captureTitle.mangaId
    private val commands = Channel<AdapterCommand>(Channel.UNLIMITED)
    private val mutableCoverage = MutableStateFlow(MangaOcrCoverageSnapshot())
    private val activeLookupSources = AtomicReference(ActiveLookupSources())
    val coverage: StateFlow<MangaOcrCoverageSnapshot> = mutableCoverage.asStateFlow()

    init {
        workerScope.launch {
            val state = AdapterState()
            for (command in commands) {
                when (command) {
                    is AdapterCommand.VisiblePages -> handleVisiblePages(state, command.pages)
                    is AdapterCommand.OcrResult -> handleOcrResult(state, command)
                    is AdapterCommand.ChapterCompleted -> {
                        if (state.completedChapters.add(command.chapterId)) {
                            recordActivity(state, EventType.UNIT_COMPLETED)
                        }
                    }
                    AdapterCommand.TitleCompleted -> {
                        if (!state.titleCompleted) {
                            state.titleCompleted = true
                            recordActivity(state, EventType.TITLE_COMPLETED)
                        }
                    }
                    is AdapterCommand.Blocked -> handleBlocked(state, command)
                    is AdapterCommand.Finalize -> {
                        finalize(state, command)
                        command.completion.complete(Unit)
                        commands.close()
                    }
                }
            }
        }
    }

    fun onVisiblePages(pages: List<MangaPageViewport>) {
        commands.trySend(AdapterCommand.VisiblePages(pages))
    }

    fun onOcrResult(
        page: MangaPageKey,
        availability: MangaOcrAvailability,
        blocks: List<MangaOcrBlockCapture>,
    ) {
        commands.trySend(AdapterCommand.OcrResult(page, availability, blocks))
    }

    /**
     * Snapshots the active session and the exact source identity used for a tapped OCR block.
     *
     * Reader coordinate transforms may change a block's bounds, but its stable [MangaOcrBlockCapture.blockId]
     * survives those transforms. The list index is used only for legacy blocks without an ID.
     */
    fun lookupProvenance(
        page: MangaPageKey,
        block: MangaOcrBlockCapture,
        blockIndex: Int,
    ): InteractionProvenance? {
        val snapshot = activeLookupSources.get()
        val sessionId = snapshot.sessionId ?: return null
        if (block.blockId == null && blockIndex < 0) {
            return InteractionProvenance(sessionId = sessionId)
        }
        val normalizedText = Normalizer.normalize(block.text, Normalizer.Form.NFC)
        val textHash = ContentHash(sha256(normalizedText))
        val locator = ocrSourceLocator(page, blockIndex, block, textHash)
        return InteractionProvenance(
            sessionId = sessionId,
            sourceUnitId = snapshot.sourceIdsByLocator[locator.canonicalKey()],
        )
    }

    fun onChapterCompleted(chapterId: Long) {
        commands.trySend(AdapterCommand.ChapterCompleted(chapterId))
    }

    fun onTitleCompleted() {
        commands.trySend(AdapterCommand.TitleCompleted)
    }

    fun setOverlayVisible(
        overlay: MangaCaptureOverlay,
        visible: Boolean,
    ) {
        commands.trySend(AdapterCommand.Blocked(CaptureBlocker.Overlay(overlay), visible))
    }

    fun setBackgrounded(backgrounded: Boolean) {
        commands.trySend(AdapterCommand.Blocked(CaptureBlocker.Background, backgrounded))
    }

    fun finalize(
        legacy: LegacyMangaSessionSnapshot,
        reason: FinalizeReason = FinalizeReason.NORMAL,
    ): CompletableDeferred<Unit> {
        val completion = CompletableDeferred<Unit>()
        if (commands.trySend(AdapterCommand.Finalize(reason, legacy, completion)).isFailure) {
            completion.complete(Unit)
        }
        return completion
    }

    private suspend fun ensureStarted(state: AdapterState): SessionHandle? {
        state.handle?.let { return it }
        return when (val result = recorder.startSession(SessionContext(title = title, incognito = incognito))) {
            is SessionStartResult.Started -> result.handle.also { handle ->
                state.handle = handle
                activeLookupSources.set(ActiveLookupSources(sessionId = handle.sessionId))
                if (state.blockers.isNotEmpty()) {
                    recorder.pause(
                        handle,
                        if (CaptureBlocker.Background in state.blockers) PauseReason.BACKGROUND else PauseReason.USER,
                    )
                }
            }
            else -> null
        }
    }

    private suspend fun handleVisiblePages(
        state: AdapterState,
        reportedPages: List<MangaPageViewport>,
    ) {
        val handle = ensureStarted(state) ?: return
        val pages = reportedPages.distinctBy { it.key }
        if (state.blockers.isNotEmpty()) return

        val reportedKeys = pages.mapTo(linkedSetOf(), MangaPageViewport::key)
        val successfullyVisible = state.visiblePages.keys.intersect(reportedKeys).toMutableSet()
        pages.filter { it.key !in state.visiblePages }.forEach { page ->
            val source = pageSource(page)
            val replayOrdinal = state.replayOrdinals.getOrDefault(source.id, 0)
            val result = recorder.record(
                handle,
                CaptureCommand.Exposure(
                    source = source,
                    grossCharacters = NonNegativeCounter.ZERO,
                    uniqueSourceCharacters = NonNegativeCounter.ZERO,
                    netCharacters = NetCharacterProgress.ZERO,
                    replayOrdinal = replayOrdinal,
                    exposurePolicy = PAGE_EXPOSURE_POLICY,
                ),
            )
            if (result is RecordResult.Enqueued) {
                successfullyVisible += page.key
                state.replayOrdinals[source.id] = replayOrdinal + 1
                state.viewedPages += page.key
            }
        }

        val primary = pages.firstOrNull()?.key
        if (primary != null && primary != state.lastPrimaryPage) {
            val previous = state.lastPrimaryPage
            val eventType = if (
                previous != null &&
                (
                    (previous.chapterId == primary.chapterId && primary.pageIndex != previous.pageIndex + 1) ||
                        (previous.chapterId != primary.chapterId && primary.chapterId in state.visitedChapters)
                    )
            ) {
                EventType.SEEK
            } else {
                EventType.PROGRESS
            }
            recorder.record(handle, CaptureCommand.Activity(eventType))
            state.lastPrimaryPage = primary
            state.visitedChapters += primary.chapterId
        }

        state.visiblePages.clear()
        pages.filter { it.key in successfullyVisible }.associateByTo(state.visiblePages, MangaPageViewport::key)
        recomputeVisibleOcr(state)
        publishCoverage(state)
    }

    private suspend fun handleOcrResult(
        state: AdapterState,
        command: AdapterCommand.OcrResult,
    ) {
        state.ocrByPage[command.page] = OcrPageResult(command.availability, command.blocks)
        if (command.availability == MangaOcrAvailability.AVAILABLE && command.blocks.isNotEmpty()) {
            state.coveredPages += command.page
        } else {
            state.coveredPages -= command.page
        }
        if (command.availability == MangaOcrAvailability.UNSUPPORTED ||
            command.availability == MangaOcrAvailability.FAILED
        ) {
            state.unavailablePages += command.page
        } else {
            state.unavailablePages -= command.page
        }
        recomputeVisibleOcr(state)
        publishCoverage(state)
    }

    private suspend fun recomputeVisibleOcr(state: AdapterState) {
        val handle = state.handle ?: return
        if (state.blockers.isNotEmpty()) return
        val visibleSources = linkedSetOf<SourceUnitId>()
        state.visiblePages.forEach { (pageKey, viewport) ->
            val result = state.ocrByPage[pageKey] ?: return@forEach
            if (result.availability != MangaOcrAvailability.AVAILABLE) return@forEach
            result.blocks.forEachIndexed { index, block ->
                if (!block.isMeaningfullyVisible(viewport)) return@forEachIndexed
                val source = ocrSource(pageKey, index, block)
                visibleSources += source.id
                if (source.id in state.visibleOcrSources) return@forEachIndexed
                val globallySeen = source.id in state.seenSources || recorder.hasSeenSource(source.id)
                val replayOrdinal = state.replayOrdinals.getOrDefault(source.id, 0)
                val count = source.characterCounts.gross
                val recordResult = recorder.record(
                    handle,
                    CaptureCommand.Exposure(
                        source = source,
                        grossCharacters = count,
                        uniqueSourceCharacters = if (globallySeen) NonNegativeCounter.ZERO else count,
                        netCharacters = NetCharacterProgress.ZERO,
                        replayOrdinal = replayOrdinal,
                        exposurePolicy = OCR_EXPOSURE_POLICY,
                    ),
                )
                if (recordResult is RecordResult.Enqueued) {
                    state.seenSources += source.id
                    state.replayOrdinals[source.id] = replayOrdinal + 1
                    publishLookupSource(handle, source)
                } else {
                    visibleSources -= source.id
                }
            }
        }
        state.visibleOcrSources.clear()
        state.visibleOcrSources += visibleSources
    }

    private fun publishCoverage(state: AdapterState) {
        mutableCoverage.value = MangaOcrCoverageSnapshot(
            viewedPages = state.viewedPages.size,
            ocrCoveredPages = state.coveredPages.intersect(state.viewedPages).size,
            unavailablePages = state.unavailablePages.intersect(state.viewedPages).size,
        )
    }

    private fun recordActivity(
        state: AdapterState,
        eventType: EventType,
    ) {
        state.handle?.let { recorder.record(it, CaptureCommand.Activity(eventType)) }
    }

    private suspend fun handleBlocked(
        state: AdapterState,
        command: AdapterCommand.Blocked,
    ) {
        val wasBlocked = state.blockers.isNotEmpty()
        if (command.blocked) {
            state.blockers += command.blocker
        } else {
            state.blockers -= command.blocker
        }
        val isBlocked = state.blockers.isNotEmpty()
        val handle = state.handle ?: return
        when {
            !wasBlocked && isBlocked -> recorder.pause(
                handle,
                if (command.blocker == CaptureBlocker.Background) PauseReason.BACKGROUND else PauseReason.USER,
            )
            wasBlocked && !isBlocked -> recorder.resume(
                handle,
                if (command.blocker == CaptureBlocker.Background) ResumeReason.FOREGROUND else ResumeReason.USER,
            )
        }
    }

    private suspend fun finalize(
        state: AdapterState,
        command: AdapterCommand.Finalize,
    ) {
        val handle = state.handle ?: return
        activeLookupSources.set(ActiveLookupSources())
        val session = recorder.finalize(handle, command.reason) ?: return
        MangaCaptureReconciliationReporter.record(
            session = session,
            legacy = command.legacy,
            idleToleranceMillis = idleTimeoutMillis,
            zoneId = zoneId(),
        )
    }

    private fun pageSource(page: MangaPageViewport): ImmersionSourceUnit {
        val locator = MangaSourceLocator(mangaId, page.key.chapterId, page.key.pageIndex)
        val hash = sha256("page\u0000${locator.canonicalKey()}")
        val now = clock()
        return ImmersionSourceUnit(
            id = SourceUnitId(stableUuid(SOURCE_NAMESPACE, "${titleId.value}|${locator.canonicalKey()}")),
            titleId = titleId,
            sourceKind = locator.sourceKind,
            canonicalLocator = locator.canonicalKey(),
            normalizedTextHash = hash,
            chapterOrSectionId = page.key.chapterId.toString(),
            pageOrCueIndex = page.key.pageIndex.toLong(),
            ocrQuality = page.ocrAvailability.toCapability(),
            firstExposedAtEpochMillis = now,
            lastExposedAtEpochMillis = now,
        )
    }

    private fun ocrSource(
        page: MangaPageKey,
        index: Int,
        block: MangaOcrBlockCapture,
    ): ImmersionSourceUnit {
        val normalizedText = Normalizer.normalize(block.text, Normalizer.Form.NFC)
        val textHash = ContentHash(sha256(normalizedText))
        val locator = ocrSourceLocator(page, index, block, textHash)
        val now = clock()
        val count = DefaultUnicodeCountPolicy.analyze(normalizedText).countableCharacters
        return ImmersionSourceUnit(
            id = ocrSourceId(locator),
            titleId = titleId,
            sourceKind = locator.sourceKind,
            canonicalLocator = locator.canonicalKey(),
            normalizedTextHash = textHash.value,
            chapterOrSectionId = page.chapterId.toString(),
            pageOrCueIndex = page.pageIndex.toLong(),
            ocrEngineId = block.engineId,
            ocrVersion = block.engineVersion,
            ocrConfidence = block.confidence,
            ocrQuality = if (block.confidence == null) CapabilityState.PARTIAL else CapabilityState.AVAILABLE,
            tokenizerVersion = DefaultUnicodeCountPolicy.version,
            rawText = normalizedText.takeUnless { rawTextRetention() == RawTextRetention.NEVER },
            firstExposedAtEpochMillis = now,
            lastExposedAtEpochMillis = now,
            characterCounts = CharacterVolume(gross = count, uniqueSource = count),
        )
    }

    private fun ocrSourceId(locator: MangaSourceLocator) =
        SourceUnitId(stableUuid(SOURCE_NAMESPACE, "${titleId.value}|${locator.canonicalKey()}"))

    private fun ocrSourceLocator(
        page: MangaPageKey,
        index: Int,
        block: MangaOcrBlockCapture,
        textHash: ContentHash,
    ) = MangaSourceLocator(
        mangaId = mangaId,
        chapterId = page.chapterId,
        pageIndex = page.pageIndex,
        ocrEngineId = block.engineId,
        ocrRevision = block.engineVersion,
        ocrBlockId = block.blockId ?: index.toString(),
        normalizedTextHash = textHash,
    )

    private fun publishLookupSource(
        handle: SessionHandle,
        source: ImmersionSourceUnit,
    ) {
        val snapshot = activeLookupSources.get()
        if (snapshot.sessionId != handle.sessionId) return
        activeLookupSources.set(
            snapshot.copy(
                sourceIdsByLocator = snapshot.sourceIdsByLocator.put(source.canonicalLocator, source.id),
            ),
        )
    }

    private data class ActiveLookupSources(
        val sessionId: SessionId? = null,
        val sourceIdsByLocator: PersistentMap<String, SourceUnitId> = persistentMapOf(),
    )

    private data class OcrPageResult(
        val availability: MangaOcrAvailability,
        val blocks: List<MangaOcrBlockCapture>,
    )

    private data class AdapterState(
        var handle: SessionHandle? = null,
        val visiblePages: MutableMap<MangaPageKey, MangaPageViewport> = linkedMapOf(),
        val visibleOcrSources: MutableSet<SourceUnitId> = mutableSetOf(),
        val seenSources: MutableSet<SourceUnitId> = mutableSetOf(),
        val replayOrdinals: MutableMap<SourceUnitId, Int> = mutableMapOf(),
        val ocrByPage: MutableMap<MangaPageKey, OcrPageResult> = mutableMapOf(),
        val viewedPages: MutableSet<MangaPageKey> = mutableSetOf(),
        val coveredPages: MutableSet<MangaPageKey> = mutableSetOf(),
        val unavailablePages: MutableSet<MangaPageKey> = mutableSetOf(),
        val visitedChapters: MutableSet<Long> = mutableSetOf(),
        val completedChapters: MutableSet<Long> = mutableSetOf(),
        val blockers: MutableSet<CaptureBlocker> = mutableSetOf(),
        var lastPrimaryPage: MangaPageKey? = null,
        var titleCompleted: Boolean = false,
    )

    private sealed interface AdapterCommand {
        data class VisiblePages(val pages: List<MangaPageViewport>) : AdapterCommand

        data class OcrResult(
            val page: MangaPageKey,
            val availability: MangaOcrAvailability,
            val blocks: List<MangaOcrBlockCapture>,
        ) : AdapterCommand

        data class ChapterCompleted(val chapterId: Long) : AdapterCommand

        data object TitleCompleted : AdapterCommand

        data class Blocked(
            val blocker: CaptureBlocker,
            val blocked: Boolean,
        ) : AdapterCommand

        data class Finalize(
            val reason: FinalizeReason,
            val legacy: LegacyMangaSessionSnapshot,
            val completion: CompletableDeferred<Unit>,
        ) : AdapterCommand
    }

    private sealed interface CaptureBlocker {
        data object Background : CaptureBlocker

        data class Overlay(val overlay: MangaCaptureOverlay) : CaptureBlocker
    }

    private companion object {
        const val PAGE_EXPOSURE_POLICY = "manga-page-area-50-v1"
        const val OCR_EXPOSURE_POLICY = "manga-ocr-block-area-50-v1"
        const val TITLE_NAMESPACE = "immersion-title-manga"
        const val SOURCE_NAMESPACE = "immersion-source-manga"
    }
}

private fun MangaOcrBlockCapture.isMeaningfullyVisible(viewport: MangaPageViewport): Boolean {
    val intersection = minOf(ymax, viewport.visibleBottom) - maxOf(ymin, viewport.visibleTop)
    return intersection > 0f && intersection / (ymax - ymin) >= 0.5f
}

private fun MangaOcrAvailability.toCapability(): CapabilityState = when (this) {
    MangaOcrAvailability.AVAILABLE -> CapabilityState.AVAILABLE
    MangaOcrAvailability.NOT_REQUESTED -> CapabilityState.PARTIAL
    MangaOcrAvailability.UNSUPPORTED,
    MangaOcrAvailability.FAILED,
    -> CapabilityState.UNAVAILABLE
}

private fun stableUuid(namespace: String, value: String): String =
    UUID.nameUUIDFromBytes(
        "$namespace\u0000$value".toByteArray(StandardCharsets.UTF_8),
    ).toString()

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
