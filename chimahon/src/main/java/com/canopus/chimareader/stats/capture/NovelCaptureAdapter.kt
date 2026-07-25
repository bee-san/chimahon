package com.canopus.chimareader.stats.capture

import com.canopus.chimareader.data.BookMetadata
import com.canopus.chimareader.data.BookStorage
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
    private val commands = Channel<AdapterCommand>(Channel.UNLIMITED)

    init {
        workerScope.launch {
            var state = AdapterState()
            for (command in commands) {
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
            }
        }
    }

    fun start(
        sectionId: String,
        netPosition: Long,
    ) {
        commands.trySend(AdapterCommand.Start(sectionId, netPosition))
    }

    fun onVisibleRanges(
        sectionId: String,
        rangesJson: String,
    ) {
        commands.trySend(AdapterCommand.VisibleRanges(sectionId, rangesJson))
    }

    fun onProgress(netPosition: Long) {
        commands.trySend(AdapterCommand.Progress(netPosition))
    }

    fun resetProgressBaseline(
        netPosition: Long,
        recordSeek: Boolean,
    ) {
        commands.trySend(AdapterCommand.ResetProgress(netPosition, recordSeek))
    }

    fun onChapterChanged(
        sectionId: String,
        netPosition: Long,
        cause: NovelNavigationCause,
    ) {
        commands.trySend(AdapterCommand.ChapterChanged(sectionId, netPosition, cause))
    }

    fun onChapterCompleted() {
        commands.trySend(AdapterCommand.ChapterCompleted)
    }

    fun onTitleCompleted() {
        commands.trySend(AdapterCommand.TitleCompleted)
    }

    fun setOverlayVisible(
        overlay: NovelCaptureOverlay,
        visible: Boolean,
    ) {
        commands.trySend(AdapterCommand.Blocked(CaptureBlocker.Overlay(overlay), visible))
    }

    fun setBackgrounded(backgrounded: Boolean) {
        commands.trySend(AdapterCommand.Blocked(CaptureBlocker.Background, backgrounded))
    }

    fun finalize(
        legacy: LegacyNovelSessionSnapshot,
        reason: FinalizeReason = FinalizeReason.NORMAL,
    ): CompletableDeferred<Unit> {
        val completion = CompletableDeferred<Unit>()
        if (commands.trySend(AdapterCommand.Finalize(reason, legacy, completion)).isFailure) {
            completion.complete(Unit)
        }
        return completion
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
            )
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
            ?: return
        val sources = ranges.associate { range ->
            val source = sourceFor(command.sectionId, range)
            source.id to source
        }
        val reportedIds = sources.keys
        val successfullyVisible = state.visibleSourceIds.intersect(reportedIds).toMutableSet()
        sources
            .filterKeys { it !in state.visibleSourceIds }
            .values
            .sortedBy { it.sourceStart }
            .forEach { source ->
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

    private sealed interface AdapterCommand {
        data class Start(
            val sectionId: String,
            val netPosition: Long,
        ) : AdapterCommand

        data class VisibleRanges(
            val sectionId: String,
            val rangesJson: String,
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
