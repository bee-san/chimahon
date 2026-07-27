// SPDX-License-Identifier: MIT

package mihon.feature.stats.capture

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CancellationException
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
import tachiyomi.domain.immersion.model.ImmersionTitleIdentityAdapter
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
import tachiyomi.domain.immersion.service.ImmersionAdapterDiagnosticKind
import tachiyomi.domain.immersion.service.ImmersionCaptureAdapter
import tachiyomi.domain.immersion.service.ImmersionRecorder
import tachiyomi.domain.immersion.service.ImmersionShadowIdentity
import tachiyomi.domain.immersion.service.ImmersionShadowReconciler
import tachiyomi.domain.immersion.service.ImmersionShadowResult
import tachiyomi.domain.immersion.service.ImmersionShadowTotals
import tachiyomi.domain.immersion.service.ImmersionStatsDiagnosticsStore
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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class MangaCaptureTitle(
    val mangaId: Long,
    val sourceId: Long,
    val displayTitle: String,
    val profileId: String,
    val languageTag: LanguageTag?,
    val createdAtEpochMillis: Long,
    val status: String? = null,
    val totalUnits: Long? = null,
) {
    init {
        require(mangaId >= 0) { "Manga ID cannot be negative" }
        require(displayTitle.isNotBlank()) { "Manga title cannot be blank" }
        require(status == null || status.isNotBlank()) { "Manga status cannot be blank" }
        require(totalUnits == null || totalUnits >= 0) { "Manga total units cannot be negative" }
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
    fun clear() {
        dayTotals.clear()
        mutableReport.value = MangaReconciliationReport()
    }

    @Synchronized
    fun resetForTest() = clear()

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
    diagnostics: ImmersionStatsDiagnosticsStore? = null,
) {
    private val titleIdentity = ImmersionTitleIdentityAdapter.manga(
        mangaId = captureTitle.mangaId,
        profileId = captureTitle.profileId,
    )
    private val sourceKey = titleIdentity.sourceKey
    private val titleId = titleIdentity.id
    private val title = ImmersionTitle(
        id = titleId,
        mediaKind = titleIdentity.mediaKind,
        sourceKey = sourceKey,
        profileId = titleIdentity.profileId,
        languageTag = captureTitle.languageTag,
        displayTitle = captureTitle.displayTitle,
        libraryId = titleIdentity.libraryId,
        mediaId = titleIdentity.mediaId,
        status = captureTitle.status,
        totalUnits = captureTitle.totalUnits,
        createdAtEpochMillis = captureTitle.createdAtEpochMillis,
        updatedAtEpochMillis = maxOf(captureTitle.createdAtEpochMillis, clock()),
    )
    private val mangaId = captureTitle.mangaId
    private val commands = BoundedCaptureCommandQueue<AdapterCommand> { kind ->
        if (!incognito) {
            diagnostics?.recordAdapterDiagnostic(ImmersionCaptureAdapter.MANGA, kind)
        }
    }
    private val mutableCoverage = MutableStateFlow(MangaOcrCoverageSnapshot())
    private val activeLookupSources = AtomicReference(ActiveLookupSources())
    val coverage: StateFlow<MangaOcrCoverageSnapshot> = mutableCoverage.asStateFlow()
    internal val queueDiagnostics: StateFlow<CaptureCommandQueueDiagnostics> = commands.diagnostics

    init {
        workerScope.launch {
            val state = AdapterState()
            while (true) {
                val command = commands.receive() ?: break
                try {
                    when (command) {
                        is AdapterCommand.VisiblePages -> handleVisiblePages(state, command.pages)
                        is AdapterCommand.OcrResult -> handleOcrResult(state, command)
                        is AdapterCommand.ChapterCompleted -> {
                            if (state.completedChapters.add(command.chapterId)) {
                                recordActivity(
                                    state = state,
                                    eventType = EventType.UNIT_COMPLETED,
                                    completionUnitId = command.chapterId.toString(),
                                )
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
                } catch (error: CancellationException) {
                    if (command is AdapterCommand.Finalize) {
                        command.completion.complete(Unit)
                    }
                    throw error
                } catch (_: Exception) {
                    commands.recordWorkerFailure()
                    if (command is AdapterCommand.Finalize) {
                        command.completion.complete(Unit)
                        commands.close()
                    }
                }
            }
        }.invokeOnCompletion {
            val terminal = commands.close()
            if (terminal is AdapterCommand.Finalize) {
                terminal.completion.complete(Unit)
            }
        }
    }

    fun onVisiblePages(pages: List<MangaPageViewport>) {
        enqueue(AdapterCommand.VisiblePages(pages))
    }

    fun onOcrResult(
        page: MangaPageKey,
        availability: MangaOcrAvailability,
        blocks: List<MangaOcrBlockCapture>,
    ) {
        enqueue(AdapterCommand.OcrResult(page, availability, blocks))
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
        enqueue(AdapterCommand.ChapterCompleted(chapterId))
    }

    fun onTitleCompleted() {
        enqueue(AdapterCommand.TitleCompleted)
    }

    fun setOverlayVisible(
        overlay: MangaCaptureOverlay,
        visible: Boolean,
    ) {
        enqueue(AdapterCommand.Blocked(CaptureBlocker.Overlay(overlay), visible))
    }

    fun setBackgrounded(backgrounded: Boolean) {
        enqueue(AdapterCommand.Blocked(CaptureBlocker.Background, backgrounded))
    }

    fun finalize(
        legacy: LegacyMangaSessionSnapshot,
        reason: FinalizeReason = FinalizeReason.NORMAL,
    ): CompletableDeferred<Unit> {
        val completion = CompletableDeferred<Unit>()
        if (!commands.finish(AdapterCommand.Finalize(reason, legacy, completion))) {
            completion.complete(Unit)
        }
        return completion
    }

    private fun enqueue(command: AdapterCommand) {
        when (commands.offer(command, command.queuePolicy)) {
            // Capture is best-effort at the reader boundary. Saturation remains visible in
            // diagnostics so rollout can be stopped, but it must never crash or freeze reading.
            CaptureCommandOfferResult.SEMANTIC_OVERFLOW,
            CaptureCommandOfferResult.SEMANTIC_DROPPED,
            CaptureCommandOfferResult.ACCEPTED,
            CaptureCommandOfferResult.COALESCED,
            CaptureCommandOfferResult.SNAPSHOT_DROPPED,
            CaptureCommandOfferResult.CLOSED,
            -> Unit
        }
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

    private suspend fun recordActivity(
        state: AdapterState,
        eventType: EventType,
        completionUnitId: String? = null,
    ) {
        val handle = ensureStarted(state) ?: return
        recorder.record(handle, CaptureCommand.Activity(eventType, completionUnitId))
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

        val queuePolicy: CaptureCommandQueuePolicy
            get() = when (this) {
                is VisiblePages -> CaptureCommandQueuePolicy.AdjacentCoalescible(
                    family = "manga-visible-pages",
                    key = pages,
                )
                is OcrResult -> CaptureCommandQueuePolicy.AdjacentCoalescible(
                    family = "manga-ocr-result",
                    key = this,
                )
                is ChapterCompleted -> CaptureCommandQueuePolicy.AdjacentCoalescible(
                    family = "manga-chapter-completed",
                    key = chapterId,
                )
                TitleCompleted -> CaptureCommandQueuePolicy.AdjacentCoalescible(
                    family = "manga-title-completed",
                    key = Unit,
                )
                is Blocked -> CaptureCommandQueuePolicy.AdjacentCoalescible(
                    family = "manga-blocked",
                    key = blocker to blocked,
                )
                is Finalize -> CaptureCommandQueuePolicy.NonCoalescible
            }
    }

    private sealed interface CaptureBlocker {
        data object Background : CaptureBlocker

        data class Overlay(val overlay: MangaCaptureOverlay) : CaptureBlocker
    }

    private companion object {
        const val PAGE_EXPOSURE_POLICY = "manga-page-area-50-v1"
        const val OCR_EXPOSURE_POLICY = "manga-ocr-block-area-50-v1"
        const val SOURCE_NAMESPACE = "immersion-source-manga"
    }
}

/**
 * A fixed-capacity command queue for capture callbacks.
 *
 * Only commands explicitly marked as latest-wins snapshots may be evicted. Semantic commands first
 * reclaim snapshot capacity and then enter a fixed-capacity FIFO overflow spool. Once both budgets
 * are full, the command is dropped with an explicit diagnostic instead of blocking the reader.
 * Finalization uses a reserved terminal slot and drains every previously accepted command.
 */
internal class BoundedCaptureCommandQueue<T : Any>(
    private val capacity: Int = CAPTURE_COMMAND_QUEUE_CAPACITY,
    private val overflowCapacity: Int = CAPTURE_COMMAND_OVERFLOW_CAPACITY,
    private val onDiagnostic: (ImmersionAdapterDiagnosticKind) -> Unit = {},
) {
    private val lock = ReentrantLock()
    private val pending = ArrayDeque<QueueEntry<T>>(capacity)
    private val overflow = ArrayDeque<QueueEntry<T>>(overflowCapacity)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val mutableDiagnostics = MutableStateFlow(
        CaptureCommandQueueDiagnostics(
            capacity = capacity,
            overflowCapacity = overflowCapacity,
        ),
    )
    private var terminal: T? = null
    private var accepting = true
    private var closed = false
    private var semanticGeneration = 0L
    private var highWatermark = 0
    private var coalescedCommands = 0L
    private var evictedSnapshots = 0L
    private var droppedSnapshots = 0L
    private var semanticOverflowCommands = 0L
    private var droppedSemanticCommands = 0L
    private var workerFailures = 0L

    val diagnostics: StateFlow<CaptureCommandQueueDiagnostics> = mutableDiagnostics.asStateFlow()

    init {
        require(capacity > 0) { "Capture command queue capacity must be positive" }
        require(overflowCapacity > 0) { "Capture command overflow capacity must be positive" }
    }

    fun offer(
        command: T,
        policy: CaptureCommandQueuePolicy,
    ): CaptureCommandOfferResult {
        var diagnostic: ImmersionAdapterDiagnosticKind? = null
        val result = lock.withLock {
            if (!accepting || closed) return@withLock CaptureCommandOfferResult.CLOSED
            if (coalesce(command, policy)) {
                coalescedCommands += 1
                publishDiagnostics()
                signalWorker()
                return@withLock CaptureCommandOfferResult.COALESCED
            }

            if (pending.size >= capacity && policy is CaptureCommandQueuePolicy.LatestSnapshot) {
                if (!evictOldestSnapshot()) {
                    droppedSnapshots += 1
                    diagnostic = ImmersionAdapterDiagnosticKind.SNAPSHOT_DROPPED
                    publishDiagnostics()
                    return@withLock CaptureCommandOfferResult.SNAPSHOT_DROPPED
                }
            }

            if (
                pending.size >= capacity &&
                overflow.isEmpty() &&
                policy !is CaptureCommandQueuePolicy.LatestSnapshot
            ) {
                evictOldestSnapshot()
            }

            if (pending.size >= capacity || overflow.isNotEmpty()) {
                if (policy is CaptureCommandQueuePolicy.LatestSnapshot) {
                    droppedSnapshots += 1
                    diagnostic = ImmersionAdapterDiagnosticKind.SNAPSHOT_DROPPED
                    publishDiagnostics()
                    return@withLock CaptureCommandOfferResult.SNAPSHOT_DROPPED
                }
                if (overflow.size >= overflowCapacity) {
                    droppedSemanticCommands += 1
                    diagnostic = ImmersionAdapterDiagnosticKind.SEMANTIC_COMMAND_DROPPED
                    publishDiagnostics()
                    return@withLock CaptureCommandOfferResult.SEMANTIC_DROPPED
                }
                val generation = ++semanticGeneration
                overflow.addLast(QueueEntry(command, policy, generation))
                semanticOverflowCommands += 1
                highWatermark = maxOf(highWatermark, pending.size + overflow.size)
                publishDiagnostics()
                signalWorker()
                return@withLock CaptureCommandOfferResult.SEMANTIC_OVERFLOW
            }

            val generation = if (policy is CaptureCommandQueuePolicy.LatestSnapshot) {
                semanticGeneration
            } else {
                ++semanticGeneration
            }
            pending.addLast(QueueEntry(command, policy, generation))
            highWatermark = maxOf(highWatermark, pending.size + overflow.size)
            publishDiagnostics()
            signalWorker()
            CaptureCommandOfferResult.ACCEPTED
        }
        diagnostic?.let(onDiagnostic)
        return result
    }

    fun finish(command: T): Boolean = lock.withLock {
        if (!accepting || closed) return false
        accepting = false
        terminal = command
        signalWorker()
        true
    }

    suspend fun receive(): T? {
        while (true) {
            lock.withLock {
                if (pending.isNotEmpty()) {
                    val entry = pending.removeFirst()
                    if (overflow.isNotEmpty()) {
                        pending.addLast(overflow.removeFirst())
                    }
                    publishDiagnostics()
                    return entry.command
                }
                if (overflow.isNotEmpty()) {
                    return overflow.removeFirst().command.also {
                        publishDiagnostics()
                    }
                }
                terminal?.let {
                    terminal = null
                    return it
                }
                if (!accepting || closed) return null
            }
            if (wakeups.receiveCatching().isClosed) return null
        }
    }

    fun close(): T? {
        val terminalCommand = lock.withLock {
            closed = true
            accepting = false
            terminal.also { terminal = null }
        }
        wakeups.close()
        return terminalCommand
    }

    fun recordWorkerFailure() {
        lock.withLock {
            workerFailures += 1
            publishDiagnostics()
        }
        onDiagnostic(ImmersionAdapterDiagnosticKind.WORKER_FAILURE)
    }

    private fun coalesce(
        command: T,
        policy: CaptureCommandQueuePolicy,
    ): Boolean {
        val queue = if (overflow.isNotEmpty()) overflow else pending
        val index = when (policy) {
            is CaptureCommandQueuePolicy.AdjacentCoalescible -> {
                queue.lastIndex.takeIf { candidate ->
                    candidate >= 0 &&
                        queue[candidate].policy == policy
                }
            }
            is CaptureCommandQueuePolicy.LatestSnapshot -> {
                queue.indexOfLast { entry ->
                    entry.semanticGeneration == semanticGeneration &&
                        entry.policy == policy
                }.takeIf { it >= 0 }
            }
            CaptureCommandQueuePolicy.NonCoalescible -> null
        } ?: return false

        queue.removeAt(index)
        queue.addLast(QueueEntry(command, policy, semanticGeneration))
        return true
    }

    private fun evictOldestSnapshot(): Boolean {
        val index = pending.indexOfFirst {
            it.semanticGeneration == semanticGeneration &&
                it.policy is CaptureCommandQueuePolicy.LatestSnapshot
        }
        if (index < 0) return false
        pending.removeAt(index)
        evictedSnapshots += 1
        publishDiagnostics()
        return true
    }

    private fun publishDiagnostics() {
        mutableDiagnostics.value = CaptureCommandQueueDiagnostics(
            capacity = capacity,
            overflowCapacity = overflowCapacity,
            depth = pending.size + overflow.size,
            overflowDepth = overflow.size,
            highWatermark = highWatermark,
            coalescedCommands = coalescedCommands,
            evictedSnapshots = evictedSnapshots,
            droppedSnapshots = droppedSnapshots,
            semanticOverflowCommands = semanticOverflowCommands,
            droppedSemanticCommands = droppedSemanticCommands,
            workerFailures = workerFailures,
        )
    }

    private fun signalWorker() {
        wakeups.trySend(Unit)
    }

    private data class QueueEntry<T>(
        val command: T,
        val policy: CaptureCommandQueuePolicy,
        val semanticGeneration: Long,
    )
}

internal sealed interface CaptureCommandQueuePolicy {
    data class LatestSnapshot(
        val family: String,
        val key: Any,
    ) : CaptureCommandQueuePolicy

    data class AdjacentCoalescible(
        val family: String,
        val key: Any,
    ) : CaptureCommandQueuePolicy

    data object NonCoalescible : CaptureCommandQueuePolicy
}

internal enum class CaptureCommandOfferResult {
    ACCEPTED,
    COALESCED,
    SNAPSHOT_DROPPED,
    SEMANTIC_OVERFLOW,
    SEMANTIC_DROPPED,
    CLOSED,
}

internal data class CaptureCommandQueueDiagnostics(
    val capacity: Int,
    val overflowCapacity: Int,
    val depth: Int = 0,
    val overflowDepth: Int = 0,
    val highWatermark: Int = 0,
    val coalescedCommands: Long = 0,
    val evictedSnapshots: Long = 0,
    val droppedSnapshots: Long = 0,
    val semanticOverflowCommands: Long = 0,
    val droppedSemanticCommands: Long = 0,
    val workerFailures: Long = 0,
)

private const val CAPTURE_COMMAND_QUEUE_CAPACITY = 64
private const val CAPTURE_COMMAND_OVERFLOW_CAPACITY = 64

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
