// SPDX-License-Identifier: MIT

package com.canopus.chimareader.stats.capture

import com.canopus.chimareader.data.BookMetadata
import com.canopus.chimareader.data.BookStorage
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import tachiyomi.domain.immersion.model.CharacterVolume
import tachiyomi.domain.immersion.model.ContentHash
import tachiyomi.domain.immersion.model.EventType
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.ImmersionSourceUnit
import tachiyomi.domain.immersion.model.ImmersionTitle
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.NetCharacterProgress
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.NovelSourceLocator
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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A layout-independent range emitted by the reader JavaScript.
 *
 * Offsets count Unicode code points in the chapter text after ruby annotation nodes are removed.
 * The WebView emits fixed-size ranges only after at least half of their rendered area intersects
 * the viewport. This makes the locator stable across pagination, font, theme, and rotation changes.
 */
@Serializable
data class NovelVisibleRange(
    val start: Long,
    val endExclusive: Long,
    val text: String,
) {
    init {
        require(start >= 0 && endExclusive > start) { "Visible range must be non-empty" }
        require(text.hasOnlyUnicodeScalarValues()) {
            "Visible range text must contain only Unicode scalar values"
        }
        require(text.codePointCount(0, text.length).toLong() == endExclusive - start) {
            "Visible range offsets must count Unicode code points"
        }
    }
}

object NovelVisibleRangeCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun decode(value: String): List<NovelVisibleRange> =
        json.decodeFromString(ListSerializer(NovelVisibleRange.serializer()), value)
            .sortedBy(NovelVisibleRange::start)
            .also { ranges ->
                ranges.zipWithNext().forEach { (first, second) ->
                    require(first.endExclusive <= second.start) {
                        "Visible ranges must not overlap"
                    }
                }
            }
}

/**
 * Immutable interaction view of the source ranges successfully captured for the current novel
 * viewport. Raw range text remains private and transient; callers can only resolve a selection to
 * its canonical session/source provenance.
 */
class NovelLookupProvenanceSnapshot internal constructor(
    val sessionId: SessionId,
    val sectionId: String,
    ranges: List<Pair<SourceUnitId, List<Int>>>,
) {
    private val visibleRanges = ranges.map { (sourceUnitId, text) ->
        NovelLookupSourceRange(
            sourceUnitId = sourceUnitId,
            text = text.toList(),
        )
    }

    fun resolve(
        sectionId: String,
        selectedText: String,
        contextText: String,
        selectionOffset: Int,
    ): InteractionProvenance? {
        if (sectionId != this.sectionId) return null
        val selection = NovelLookupSelection.create(
            selectedText = selectedText,
            contextText = contextText,
            selectionOffset = selectionOffset,
        ) ?: return null
        val candidates = visibleRanges.mapNotNull { range ->
            range.match(selection)
        }
        val bestScore = candidates.maxOfOrNull(NovelLookupMatch::score) ?: return null
        val bestSources = candidates
            .filter { it.score == bestScore }
            .map(NovelLookupMatch::sourceUnitId)
            .distinct()
        val sourceUnitId = bestSources.singleOrNull() ?: return null
        return InteractionProvenance(
            sessionId = sessionId,
            sourceUnitId = sourceUnitId,
        )
    }
}

private data class NovelLookupSourceRange(
    val sourceUnitId: SourceUnitId,
    val text: List<Int>,
) {
    fun match(selection: NovelLookupSelection): NovelLookupMatch? {
        val selectedStart = selection.selectedStart
        val selectedCodePoints = selection.selectedText
        val anchor = selection.contextText[selectedStart]
        var bestScore: NovelLookupMatchScore? = null
        text.forEachIndexed { rangeIndex, codePoint ->
            if (codePoint != anchor) return@forEachIndexed
            val availableSelectionLength = minOf(selectedCodePoints.size, text.size - rangeIndex)
            if (
                availableSelectionLength <= 0 ||
                !text.matchesAt(rangeIndex, selectedCodePoints, availableSelectionLength)
            ) {
                return@forEachIndexed
            }
            var matchedBefore = 0
            while (
                rangeIndex - matchedBefore - 1 >= 0 &&
                selectedStart - matchedBefore - 1 >= 0 &&
                text[rangeIndex - matchedBefore - 1] ==
                selection.contextText[selectedStart - matchedBefore - 1]
            ) {
                matchedBefore += 1
            }
            var matchedAfter = 0
            while (
                rangeIndex + matchedAfter < text.size &&
                selectedStart + matchedAfter < selection.contextText.size &&
                text[rangeIndex + matchedAfter] ==
                selection.contextText[selectedStart + matchedAfter]
            ) {
                matchedAfter += 1
            }
            val score = NovelLookupMatchScore(
                selectedCharacters = availableSelectionLength,
                contextCharacters = matchedBefore + matchedAfter,
            )
            val currentBest = bestScore
            if (currentBest == null || score > currentBest) {
                bestScore = score
            }
        }
        return bestScore?.let { NovelLookupMatch(sourceUnitId, it) }
    }
}

private data class NovelLookupSelection(
    val selectedText: List<Int>,
    val contextText: List<Int>,
    val selectedStart: Int,
) {
    companion object {
        fun create(
            selectedText: String,
            contextText: String,
            selectionOffset: Int,
        ): NovelLookupSelection? {
            val normalizedSelectedText = Normalizer.normalize(selectedText.trim(), Normalizer.Form.NFC)
            if (normalizedSelectedText.isEmpty() || contextText.isEmpty()) return null
            val safeOffset = contextText.safeUtf16Offset(selectionOffset)
            val normalizedContextPrefix = Normalizer.normalize(
                contextText.substring(0, safeOffset),
                Normalizer.Form.NFC,
            )
            val normalizedContext = Normalizer.normalize(contextText, Normalizer.Form.NFC)
            val selectedCodePoints = normalizedSelectedText.toCodePointList()
            val contextCodePoints = normalizedContext.toCodePointList()
            var selectedStart = normalizedContextPrefix.codePointCount(
                0,
                normalizedContextPrefix.length,
            )
            if (!contextCodePoints.matchesAt(selectedStart, selectedCodePoints, selectedCodePoints.size)) {
                val occurrences = contextCodePoints.occurrencesOf(selectedCodePoints)
                selectedStart = occurrences.singleOrNull() ?: return null
            }
            return NovelLookupSelection(
                selectedText = selectedCodePoints,
                contextText = contextCodePoints,
                selectedStart = selectedStart,
            )
        }
    }
}

private data class NovelLookupMatch(
    val sourceUnitId: SourceUnitId,
    val score: NovelLookupMatchScore,
)

private data class NovelLookupMatchScore(
    val selectedCharacters: Int,
    val contextCharacters: Int,
) : Comparable<NovelLookupMatchScore> {
    override fun compareTo(other: NovelLookupMatchScore): Int =
        compareValuesBy(
            this,
            other,
            NovelLookupMatchScore::contextCharacters,
            NovelLookupMatchScore::selectedCharacters,
        )
}

data class NovelCaptureBook(
    val documentId: String,
    val displayTitle: String,
    val profileId: String,
    val languageTag: LanguageTag?,
    val createdAtEpochMillis: Long,
) {
    init {
        require(documentId.isNotBlank()) { "Novel document identity cannot be blank" }
        require(displayTitle.isNotBlank()) { "Novel title cannot be blank" }
        require(createdAtEpochMillis >= 0) { "Novel creation time cannot be negative" }
    }

    companion object {
        fun from(
            metadata: BookMetadata?,
            fallbackTitle: String?,
            fallbackDocumentId: String,
            profileId: String?,
        ): NovelCaptureBook {
            val identity = metadata?.let(BookStorage::bookIdentityKey)
                ?.takeIf(String::isNotBlank)
                ?: fallbackDocumentId
            val title = metadata?.title
                ?.takeIf(String::isNotBlank)
                ?: fallbackTitle?.takeIf(String::isNotBlank)
                ?: "Unknown novel"
            return NovelCaptureBook(
                documentId = identity,
                displayTitle = title,
                profileId = profileId.orEmpty(),
                languageTag = metadata?.lang?.let { value ->
                    runCatching { LanguageTag.from(value) }.getOrNull()
                },
                createdAtEpochMillis = metadata?.dateAdded?.coerceAtLeast(0) ?: 0,
            )
        }
    }
}

enum class NovelNavigationCause {
    RESTORE,
    NEXT_CHAPTER,
    PREVIOUS_CHAPTER,
    TABLE_OF_CONTENTS,
    INTERNAL_LINK,
    BOOKMARK,
    SEARCH,
    SYNC_RESTORE,
}

enum class NovelCaptureOverlay {
    READER_SHEET,
    LOOKUP_POPUP,
    IMAGE_VIEWER,
    MANUAL_PAUSE,
}

data class LegacyNovelSessionSnapshot(
    val activeDurationMillis: Long,
    val netCharacters: Long,
    val equivalentPolicy: Boolean,
) {
    init {
        require(activeDurationMillis >= 0) { "Legacy active duration cannot be negative" }
    }
}

data class NovelReconciliationEntry(
    val scope: NovelReconciliationScope,
    val key: String,
    val legacyComparable: Boolean,
    val result: ImmersionShadowResult?,
)

enum class NovelReconciliationScope {
    SESSION,
    DAY,
}

data class NovelReconciliationReport(
    val entries: List<NovelReconciliationEntry> = emptyList(),
)

/**
 * Hidden developer report used during dual-write rollout. It retains counters and IDs only.
 */
object NovelCaptureReconciliationReporter {
    private const val MAX_REPORT_ENTRIES = 100
    private val mutableReport = MutableStateFlow(NovelReconciliationReport())
    private val dayTotals = linkedMapOf<String, ReconciliationAccumulator>()
    val report: StateFlow<NovelReconciliationReport> = mutableReport.asStateFlow()

    @Synchronized
    fun record(
        session: ImmersionSession,
        legacy: LegacyNovelSessionSnapshot,
        idleToleranceMillis: Long,
        zoneId: ZoneId,
    ) {
        val result = reconcile(session, legacy, idleToleranceMillis)
        val sessionEntry = NovelReconciliationEntry(
            scope = NovelReconciliationScope.SESSION,
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
        accumulator.recordedNetCharacters += session.netCharacters.value
        accumulator.sessionIds += session.id
        if (legacy.equivalentPolicy) {
            accumulator.legacyActiveMillis += legacy.activeDurationMillis
            accumulator.legacyNetCharacters += legacy.netCharacters
            accumulator.comparableSessions += 1
        } else {
            accumulator.hasNonComparableSession = true
        }
        val dayEntry = accumulator.toEntry(day, idleToleranceMillis)
        mutableReport.value = NovelReconciliationReport(
            entries = (listOf(sessionEntry, dayEntry) + mutableReport.value.entries)
                .distinctBy { it.scope to it.key }
                .take(MAX_REPORT_ENTRIES),
        )
    }

    @Synchronized
    fun resetForTest() {
        dayTotals.clear()
        mutableReport.value = NovelReconciliationReport()
    }

    private fun reconcile(
        session: ImmersionSession,
        legacy: LegacyNovelSessionSnapshot,
        idleToleranceMillis: Long,
    ): ImmersionShadowResult? {
        if (!legacy.equivalentPolicy) return null
        return ImmersionShadowReconciler.reconcile(
            recorded = ImmersionShadowTotals(
                activeDurationMillis = session.activeDuration.value,
                netCharacters = session.netCharacters.value,
            ),
            legacy = ImmersionShadowTotals(
                activeDurationMillis = legacy.activeDurationMillis,
                netCharacters = legacy.netCharacters,
            ),
            identity = ImmersionShadowIdentity(
                sessionIds = listOf(session.id),
                eventIds = emptyList(),
            ),
            readingTimeTolerance = ReadingTimeTolerance.DocumentedPolicyChange(
                maximumDiscrepancyMillis = idleToleranceMillis,
                policy = ActiveTimePolicyDifference.IDLE_EXCLUDED,
            ),
        )
    }

    private data class ReconciliationAccumulator(
        var recordedActiveMillis: Long = 0,
        var recordedNetCharacters: Long = 0,
        var legacyActiveMillis: Long = 0,
        var legacyNetCharacters: Long = 0,
        var comparableSessions: Int = 0,
        var hasNonComparableSession: Boolean = false,
        val sessionIds: MutableList<SessionId> = mutableListOf(),
    ) {
        fun toEntry(day: String, idleToleranceMillis: Long): NovelReconciliationEntry {
            val comparable = !hasNonComparableSession && comparableSessions == sessionIds.size
            val result = if (comparable) {
                ImmersionShadowReconciler.reconcile(
                    recorded = ImmersionShadowTotals(
                        activeDurationMillis = recordedActiveMillis,
                        netCharacters = recordedNetCharacters,
                    ),
                    legacy = ImmersionShadowTotals(
                        activeDurationMillis = legacyActiveMillis,
                        netCharacters = legacyNetCharacters,
                    ),
                    identity = ImmersionShadowIdentity(
                        sessionIds = sessionIds,
                        eventIds = emptyList(),
                    ),
                    readingTimeTolerance = ReadingTimeTolerance.DocumentedPolicyChange(
                        maximumDiscrepancyMillis = idleToleranceMillis * sessionIds.size,
                        policy = ActiveTimePolicyDifference.IDLE_EXCLUDED,
                    ),
                )
            } else {
                null
            }
            return NovelReconciliationEntry(
                scope = NovelReconciliationScope.DAY,
                key = day,
                legacyComparable = comparable,
                result = result,
            )
        }
    }
}

class NovelCaptureAdapter(
    book: NovelCaptureBook,
    private val recorder: ImmersionRecorder,
    private val rawTextRetention: () -> RawTextRetention,
    private val idleTimeoutMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
    workerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val documentId = book.documentId
    private val sourceKey = "$SOURCE_KEY_PREFIX:$documentId"
    private val titleId = TitleId(stableUuid(TITLE_NAMESPACE, "$sourceKey|${book.profileId}"))
    private val title = ImmersionTitle(
        id = titleId,
        mediaKind = MediaKind.NOVEL,
        sourceKey = sourceKey,
        profileId = book.profileId,
        languageTag = book.languageTag,
        displayTitle = book.displayTitle,
        mediaId = book.documentId,
        createdAtEpochMillis = book.createdAtEpochMillis,
        updatedAtEpochMillis = maxOf(book.createdAtEpochMillis, clock()),
    )
    private val commands = BoundedNovelCommandQueue<AdapterCommand>()
    private val lookupSnapshotState = AtomicReference(LookupSnapshotState())
    internal val queueDiagnostics: StateFlow<NovelCommandQueueDiagnostics> = commands.diagnostics

    init {
        workerScope.launch {
            var state = AdapterState()
            while (true) {
                val command = commands.receive() ?: break
                try {
                    when (command) {
                        is AdapterCommand.Start -> state = start(state, command)
                        is AdapterCommand.VisibleRanges -> handleVisibleRanges(state, command)
                        is AdapterCommand.Progress -> handleProgress(state, command)
                        is AdapterCommand.ResetProgress -> {
                            state.lastNetPosition = command.netPosition
                            if (command.recordSeek) {
                                state.visibleSourceIds.clear()
                                recordActivity(state, EventType.SEEK)
                            }
                        }
                        is AdapterCommand.ChapterChanged -> handleChapterChanged(state, command)
                        is AdapterCommand.ChapterCompleted -> {
                            if (state.completedSections.add(state.sectionId)) {
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

    fun start(
        sectionId: String,
        netPosition: Long,
    ) {
        val snapshotEpoch = invalidateLookupSnapshot()
        enqueue(AdapterCommand.Start(sectionId, netPosition, snapshotEpoch))
    }

    fun onVisibleRanges(
        sectionId: String,
        rangesJson: String,
    ) {
        val snapshotEpoch = invalidateLookupSnapshot()
        enqueue(
            AdapterCommand.VisibleRanges(
                sectionId = sectionId,
                rangesJson = rangesJson,
                snapshotEpoch = snapshotEpoch,
            ),
        )
    }

    fun onProgress(netPosition: Long) {
        invalidateLookupSnapshot()
        enqueue(AdapterCommand.Progress(netPosition))
    }

    fun resetProgressBaseline(
        netPosition: Long,
        recordSeek: Boolean,
    ) {
        invalidateLookupSnapshot()
        enqueue(AdapterCommand.ResetProgress(netPosition, recordSeek))
    }

    fun onChapterChanged(
        sectionId: String,
        netPosition: Long,
        cause: NovelNavigationCause,
    ) {
        invalidateLookupSnapshot()
        enqueue(AdapterCommand.ChapterChanged(sectionId, netPosition, cause))
    }

    fun onChapterCompleted() {
        enqueue(AdapterCommand.ChapterCompleted)
    }

    fun onTitleCompleted() {
        enqueue(AdapterCommand.TitleCompleted)
    }

    fun setOverlayVisible(
        overlay: NovelCaptureOverlay,
        visible: Boolean,
    ) {
        if (visible) invalidateLookupSnapshot()
        enqueue(AdapterCommand.Blocked(CaptureBlocker.Overlay(overlay), visible))
    }

    fun setBackgrounded(backgrounded: Boolean) {
        if (backgrounded) invalidateLookupSnapshot()
        enqueue(AdapterCommand.Blocked(CaptureBlocker.Background, backgrounded))
    }

    fun lookupProvenanceSnapshot(): NovelLookupProvenanceSnapshot? =
        lookupSnapshotState.get().snapshot

    fun resolveLookupProvenance(
        sectionId: String,
        selectedText: String,
        contextText: String,
        selectionOffset: Int,
    ): InteractionProvenance? {
        val lookupState = lookupSnapshotState.get()
        val sessionId = lookupState.sessionId ?: return null
        val sourceUnitId = lookupState.snapshot?.resolve(
            sectionId = sectionId,
            selectedText = selectedText,
            contextText = contextText,
            selectionOffset = selectionOffset,
        )?.sourceUnitId
        return InteractionProvenance(
            sessionId = sessionId,
            sourceUnitId = sourceUnitId,
        )
    }

    fun finalize(
        legacy: LegacyNovelSessionSnapshot,
        reason: FinalizeReason = FinalizeReason.NORMAL,
    ): CompletableDeferred<Unit> {
        clearLookupSnapshot()
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
            NovelCommandOfferResult.SEMANTIC_OVERFLOW,
            NovelCommandOfferResult.SEMANTIC_DROPPED,
            NovelCommandOfferResult.ACCEPTED,
            NovelCommandOfferResult.COALESCED,
            NovelCommandOfferResult.SNAPSHOT_DROPPED,
            NovelCommandOfferResult.CLOSED,
            -> Unit
        }
    }

    private suspend fun start(
        state: AdapterState,
        command: AdapterCommand.Start,
    ): AdapterState {
        val result = recorder.startSession(SessionContext(title = title))
        return if (result is SessionStartResult.Started) {
            state.copy(
                handle = result.handle,
                sectionId = command.sectionId,
                lastNetPosition = command.netPosition,
            ).also { startedState ->
                publishLookupSnapshot(
                    state = startedState,
                    ranges = emptyList(),
                    expectedEpoch = command.snapshotEpoch,
                )
            }
        } else {
            state
        }
    }

    private suspend fun handleVisibleRanges(
        state: AdapterState,
        command: AdapterCommand.VisibleRanges,
    ) {
        val handle = state.handle ?: return
        if (command.sectionId != state.sectionId || state.blockers.isNotEmpty()) return
        val ranges = runCatching { NovelVisibleRangeCodec.decode(command.rangesJson) }
            .getOrNull()
            ?: run {
                publishLookupSnapshot(
                    state = state,
                    ranges = emptyList(),
                    expectedEpoch = command.snapshotEpoch,
                )
                return
            }
        val sources = ranges.associate { range ->
            val source = sourceFor(command.sectionId, range)
            source.id to CapturedNovelVisibleRange(
                source = source,
                normalizedText = Normalizer.normalize(range.text, Normalizer.Form.NFC),
            )
        }
        val reportedIds = sources.keys
        val successfullyVisible = state.visibleSourceIds.intersect(reportedIds).toMutableSet()
        sources
            .filterKeys { it !in state.visibleSourceIds }
            .values
            .sortedBy { it.source.sourceStart }
            .forEach { capturedRange ->
                val source = capturedRange.source
                val count = source.characterCounts.gross
                val globallySeen = source.id in state.seenSourceIds ||
                    recorder.hasSeenSource(source.id)
                val replayOrdinal = state.replayOrdinals.getOrDefault(source.id, 0)
                val result = recorder.record(
                    handle,
                    CaptureCommand.Exposure(
                        source = source,
                        grossCharacters = count,
                        uniqueSourceCharacters = if (globallySeen) {
                            NonNegativeCounter.ZERO
                        } else {
                            count
                        },
                        netCharacters = NetCharacterProgress.ZERO,
                        replayOrdinal = replayOrdinal,
                        exposurePolicy = EXPOSURE_POLICY,
                    ),
                )
                if (result is RecordResult.Enqueued) {
                    successfullyVisible += source.id
                    state.seenSourceIds += source.id
                    state.replayOrdinals[source.id] = replayOrdinal + 1
                }
            }
        state.visibleSourceIds.clear()
        state.visibleSourceIds += successfullyVisible
        publishLookupSnapshot(
            state = state,
            ranges = sources
                .filterKeys { it in successfullyVisible }
                .values
                .sortedBy { it.source.sourceStart },
            expectedEpoch = command.snapshotEpoch,
        )
    }

    private fun handleProgress(
        state: AdapterState,
        command: AdapterCommand.Progress,
    ) {
        val handle = state.handle ?: return
        val previous = state.lastNetPosition
        state.lastNetPosition = command.netPosition
        if (state.blockers.isNotEmpty()) return
        if (previous == null || previous == command.netPosition) return
        recorder.record(
            handle,
            CaptureCommand.Progress(NetCharacterProgress(command.netPosition - previous)),
        )
    }

    private fun handleChapterChanged(
        state: AdapterState,
        command: AdapterCommand.ChapterChanged,
    ) {
        state.sectionId = command.sectionId
        state.lastNetPosition = command.netPosition
        state.visibleSourceIds.clear()
        if (command.cause.isSeek) {
            recordActivity(state, EventType.SEEK)
        }
    }

    private fun recordActivity(
        state: AdapterState,
        eventType: EventType,
    ) {
        val handle = state.handle ?: return
        recorder.record(handle, CaptureCommand.Activity(eventType))
    }

    private suspend fun handleBlocked(
        state: AdapterState,
        command: AdapterCommand.Blocked,
    ) {
        val handle = state.handle ?: return
        val wasBlocked = state.blockers.isNotEmpty()
        if (command.blocked) {
            state.blockers += command.blocker
        } else {
            state.blockers -= command.blocker
        }
        val isBlocked = state.blockers.isNotEmpty()
        when {
            !wasBlocked && isBlocked -> recorder.pause(
                handle,
                if (command.blocker == CaptureBlocker.Background) {
                    PauseReason.BACKGROUND
                } else {
                    PauseReason.USER
                },
            )
            wasBlocked && !isBlocked -> recorder.resume(
                handle,
                if (command.blocker == CaptureBlocker.Background) {
                    ResumeReason.FOREGROUND
                } else {
                    ResumeReason.USER
                },
            )
        }
    }

    private suspend fun finalize(
        state: AdapterState,
        command: AdapterCommand.Finalize,
    ) {
        val handle = state.handle ?: return
        val session = recorder.finalize(handle, command.reason) ?: return
        NovelCaptureReconciliationReporter.record(
            session = session,
            legacy = command.legacy,
            idleToleranceMillis = idleTimeoutMillis,
            zoneId = zoneId(),
        )
    }

    private fun invalidateLookupSnapshot(): Long {
        return lookupSnapshotState.updateAndGet { state ->
            LookupSnapshotState(
                epoch = state.epoch + 1,
                sessionId = state.sessionId,
                snapshot = null,
            )
        }.epoch
    }

    private fun clearLookupSnapshot() {
        lookupSnapshotState.updateAndGet { state ->
            LookupSnapshotState(epoch = state.epoch + 1)
        }
    }

    private fun publishLookupSnapshot(
        state: AdapterState,
        ranges: List<CapturedNovelVisibleRange>,
        expectedEpoch: Long,
    ) {
        val handle = state.handle ?: return
        val snapshot = NovelLookupProvenanceSnapshot(
            sessionId = handle.sessionId,
            sectionId = state.sectionId,
            ranges = ranges.map { range ->
                range.source.id to range.normalizedText.toCodePointList()
            },
        )
        lookupSnapshotState.updateAndGet { current ->
            if (current.epoch == expectedEpoch) {
                current.copy(
                    sessionId = handle.sessionId,
                    snapshot = snapshot,
                )
            } else {
                current
            }
        }
    }

    private fun sourceFor(
        sectionId: String,
        range: NovelVisibleRange,
    ): ImmersionSourceUnit {
        val normalizedText = Normalizer.normalize(range.text, Normalizer.Form.NFC)
        val textHash = ContentHash(sha256(normalizedText))
        val locator = NovelSourceLocator(
            sourceKey = sourceKey,
            documentId = documentId,
            sectionId = sectionId,
            rangeStart = range.start,
            rangeEndExclusive = range.endExclusive,
            normalizedTextHash = textHash,
            parserRevision = PARSER_REVISION,
        )
        val now = clock()
        val count = DefaultUnicodeCountPolicy.analyze(normalizedText).countableCharacters
        return ImmersionSourceUnit(
            id = SourceUnitId(stableUuid(SOURCE_NAMESPACE, "${titleId.value}|${locator.canonicalKey()}")),
            titleId = titleId,
            sourceKind = locator.sourceKind,
            canonicalLocator = locator.canonicalKey(),
            normalizedTextHash = textHash.value,
            chapterOrSectionId = sectionId,
            sourceStart = range.start,
            sourceEnd = range.endExclusive,
            parserVersion = PARSER_REVISION,
            tokenizerVersion = DefaultUnicodeCountPolicy.version,
            rawText = normalizedText.takeUnless { rawTextRetention() == RawTextRetention.NEVER },
            firstExposedAtEpochMillis = now,
            lastExposedAtEpochMillis = now,
            characterCounts = CharacterVolume(
                gross = count,
                uniqueSource = count,
            ),
        )
    }

    private data class AdapterState(
        val handle: SessionHandle? = null,
        var sectionId: String = "",
        var lastNetPosition: Long? = null,
        val visibleSourceIds: MutableSet<SourceUnitId> = mutableSetOf(),
        val seenSourceIds: MutableSet<SourceUnitId> = mutableSetOf(),
        val replayOrdinals: MutableMap<SourceUnitId, Int> = mutableMapOf(),
        val blockers: MutableSet<CaptureBlocker> = mutableSetOf(),
        val completedSections: MutableSet<String> = mutableSetOf(),
        var titleCompleted: Boolean = false,
    )

    private data class CapturedNovelVisibleRange(
        val source: ImmersionSourceUnit,
        val normalizedText: String,
    )

    private data class LookupSnapshotState(
        val epoch: Long = 0,
        val sessionId: SessionId? = null,
        val snapshot: NovelLookupProvenanceSnapshot? = null,
    )

    private sealed interface AdapterCommand {
        data class Start(
            val sectionId: String,
            val netPosition: Long,
            val snapshotEpoch: Long,
        ) : AdapterCommand

        data class VisibleRanges(
            val sectionId: String,
            val rangesJson: String,
            val snapshotEpoch: Long,
        ) : AdapterCommand

        data class Progress(val netPosition: Long) : AdapterCommand

        data class ResetProgress(
            val netPosition: Long,
            val recordSeek: Boolean,
        ) : AdapterCommand

        data class ChapterChanged(
            val sectionId: String,
            val netPosition: Long,
            val cause: NovelNavigationCause,
        ) : AdapterCommand

        data object ChapterCompleted : AdapterCommand

        data object TitleCompleted : AdapterCommand

        data class Blocked(
            val blocker: CaptureBlocker,
            val blocked: Boolean,
        ) : AdapterCommand

        data class Finalize(
            val reason: FinalizeReason,
            val legacy: LegacyNovelSessionSnapshot,
            val completion: CompletableDeferred<Unit>,
        ) : AdapterCommand

        val queuePolicy: NovelCommandQueuePolicy
            get() = when (this) {
                is Progress -> NovelCommandQueuePolicy.LatestSnapshot(
                    family = "novel-progress",
                    key = Unit,
                )
                is Start -> adjacentPolicy("novel-start", Unit)
                is VisibleRanges -> adjacentPolicy(
                    family = "novel-visible-ranges",
                    key = sectionId to rangesJson,
                )
                is ResetProgress -> adjacentPolicy("novel-reset-progress", recordSeek)
                is ChapterChanged -> adjacentPolicy("novel-chapter-changed", this)
                ChapterCompleted -> adjacentPolicy("novel-chapter-completed", Unit)
                TitleCompleted -> adjacentPolicy("novel-title-completed", Unit)
                is Blocked -> adjacentPolicy("novel-blocked", blocker to blocked)
                is Finalize -> NovelCommandQueuePolicy.NonCoalescible
            }

        private fun adjacentPolicy(
            family: String,
            key: Any,
        ) = NovelCommandQueuePolicy.AdjacentCoalescible(family, key)
    }

    private sealed interface CaptureBlocker {
        data object Background : CaptureBlocker

        data class Overlay(val overlay: NovelCaptureOverlay) : CaptureBlocker
    }

    private companion object {
        const val SOURCE_KEY_PREFIX = "novel"
        const val PARSER_REVISION = 1
        const val EXPOSURE_POLICY = "novel-viewport-area-50-fixed-64-v1"
        const val TITLE_NAMESPACE = "immersion-title-novel"
        const val SOURCE_NAMESPACE = "immersion-source-novel"
    }
}

/**
 * Non-blocking capture ingress with bounded primary storage and a bounded FIFO overflow spool.
 *
 * Only latest-state progress snapshots may be evicted to make room. Once both command budgets are
 * full, further commands are dropped with an explicit diagnostic. Finalization has a reserved
 * terminal slot and runs after every accepted semantic command.
 */
private class BoundedNovelCommandQueue<T : Any>(
    private val capacity: Int = NOVEL_CAPTURE_COMMAND_QUEUE_CAPACITY,
    private val overflowCapacity: Int = NOVEL_CAPTURE_COMMAND_OVERFLOW_CAPACITY,
) {
    private val lock = ReentrantLock()
    private val pending = ArrayDeque<QueueEntry<T>>(capacity)
    private val overflow = ArrayDeque<QueueEntry<T>>(overflowCapacity)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val mutableDiagnostics = MutableStateFlow(
        NovelCommandQueueDiagnostics(
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

    val diagnostics: StateFlow<NovelCommandQueueDiagnostics> = mutableDiagnostics.asStateFlow()

    init {
        require(capacity > 0) { "Novel capture command queue capacity must be positive" }
        require(overflowCapacity > 0) { "Novel capture command overflow capacity must be positive" }
    }

    fun offer(
        command: T,
        policy: NovelCommandQueuePolicy,
    ): NovelCommandOfferResult {
        lock.withLock {
            if (!accepting || closed) return NovelCommandOfferResult.CLOSED
            if (coalesce(command, policy)) {
                coalescedCommands += 1
                publishDiagnostics()
                signalWorker()
                return NovelCommandOfferResult.COALESCED
            }

            if (pending.size >= capacity && policy is NovelCommandQueuePolicy.LatestSnapshot) {
                if (!evictOldestSnapshot()) {
                    droppedSnapshots += 1
                    publishDiagnostics()
                    return NovelCommandOfferResult.SNAPSHOT_DROPPED
                }
            }

            if (
                pending.size >= capacity &&
                overflow.isEmpty() &&
                policy !is NovelCommandQueuePolicy.LatestSnapshot
            ) {
                evictOldestSnapshot()
            }

            if (pending.size >= capacity || overflow.isNotEmpty()) {
                if (policy is NovelCommandQueuePolicy.LatestSnapshot) {
                    droppedSnapshots += 1
                    publishDiagnostics()
                    return NovelCommandOfferResult.SNAPSHOT_DROPPED
                }
                if (overflow.size >= overflowCapacity) {
                    droppedSemanticCommands += 1
                    publishDiagnostics()
                    return NovelCommandOfferResult.SEMANTIC_DROPPED
                }
                val generation = ++semanticGeneration
                overflow.addLast(QueueEntry(command, policy, generation))
                semanticOverflowCommands += 1
                highWatermark = maxOf(highWatermark, pending.size + overflow.size)
                publishDiagnostics()
                signalWorker()
                return NovelCommandOfferResult.SEMANTIC_OVERFLOW
            }

            val generation = if (policy is NovelCommandQueuePolicy.LatestSnapshot) {
                semanticGeneration
            } else {
                ++semanticGeneration
            }
            pending.addLast(QueueEntry(command, policy, generation))
            highWatermark = maxOf(highWatermark, pending.size + overflow.size)
            publishDiagnostics()
            signalWorker()
            return NovelCommandOfferResult.ACCEPTED
        }
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
    }

    private fun coalesce(
        command: T,
        policy: NovelCommandQueuePolicy,
    ): Boolean {
        val queue = if (overflow.isNotEmpty()) overflow else pending
        val index = when (policy) {
            is NovelCommandQueuePolicy.AdjacentCoalescible -> {
                queue.lastIndex.takeIf { candidate ->
                    candidate >= 0 &&
                        queue[candidate].policy == policy
                }
            }
            is NovelCommandQueuePolicy.LatestSnapshot -> {
                queue.indexOfLast { entry ->
                    entry.semanticGeneration == semanticGeneration &&
                        entry.policy == policy
                }.takeIf { it >= 0 }
            }
            NovelCommandQueuePolicy.NonCoalescible -> null
        } ?: return false

        queue.removeAt(index)
        queue.addLast(QueueEntry(command, policy, semanticGeneration))
        return true
    }

    private fun evictOldestSnapshot(): Boolean {
        val index = pending.indexOfFirst {
            it.semanticGeneration == semanticGeneration &&
                it.policy is NovelCommandQueuePolicy.LatestSnapshot
        }
        if (index < 0) return false
        pending.removeAt(index)
        evictedSnapshots += 1
        publishDiagnostics()
        return true
    }

    private fun publishDiagnostics() {
        mutableDiagnostics.value = NovelCommandQueueDiagnostics(
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
        val policy: NovelCommandQueuePolicy,
        val semanticGeneration: Long,
    )
}

private sealed interface NovelCommandQueuePolicy {
    data class LatestSnapshot(
        val family: String,
        val key: Any,
    ) : NovelCommandQueuePolicy

    data class AdjacentCoalescible(
        val family: String,
        val key: Any,
    ) : NovelCommandQueuePolicy

    data object NonCoalescible : NovelCommandQueuePolicy
}

private enum class NovelCommandOfferResult {
    ACCEPTED,
    COALESCED,
    SNAPSHOT_DROPPED,
    SEMANTIC_OVERFLOW,
    SEMANTIC_DROPPED,
    CLOSED,
}

internal data class NovelCommandQueueDiagnostics(
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

private const val NOVEL_CAPTURE_COMMAND_QUEUE_CAPACITY = 64
private const val NOVEL_CAPTURE_COMMAND_OVERFLOW_CAPACITY = 64

private val NovelNavigationCause.isSeek: Boolean
    get() = when (this) {
        NovelNavigationCause.RESTORE,
        NovelNavigationCause.TABLE_OF_CONTENTS,
        NovelNavigationCause.INTERNAL_LINK,
        NovelNavigationCause.BOOKMARK,
        NovelNavigationCause.SEARCH,
        NovelNavigationCause.SYNC_RESTORE,
        -> true
        NovelNavigationCause.NEXT_CHAPTER,
        NovelNavigationCause.PREVIOUS_CHAPTER,
        -> false
    }

private fun stableUuid(namespace: String, value: String): String =
    UUID.nameUUIDFromBytes(
        "$namespace\u0000$value".toByteArray(StandardCharsets.UTF_8),
    ).toString()

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun String.hasOnlyUnicodeScalarValues(): Boolean {
    var offset = 0
    while (offset < length) {
        val codePoint = codePointAt(offset)
        if (codePoint in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) return false
        offset += Character.charCount(codePoint)
    }
    return true
}

private fun String.safeUtf16Offset(requestedOffset: Int): Int {
    val offset = requestedOffset.coerceIn(0, length)
    return if (
        offset in 1 until length &&
        this[offset].isLowSurrogate() &&
        this[offset - 1].isHighSurrogate()
    ) {
        offset - 1
    } else {
        offset
    }
}

private fun String.toCodePointList(): List<Int> =
    codePoints().toArray().toList()

private fun List<Int>.matchesAt(
    startIndex: Int,
    expected: List<Int>,
    length: Int,
): Boolean {
    if (
        startIndex < 0 ||
        length < 0 ||
        startIndex + length > size ||
        length > expected.size
    ) {
        return false
    }
    return (0 until length).all { offset ->
        this[startIndex + offset] == expected[offset]
    }
}

private fun List<Int>.occurrencesOf(value: List<Int>): List<Int> {
    if (value.isEmpty() || value.size > size) return emptyList()
    return (0..size - value.size).filter { startIndex ->
        matchesAt(startIndex, value, value.size)
    }
}
