// SPDX-License-Identifier: MIT

package com.canopus.chimareader.stats.capture

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.EventType
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.ImmersionTitle
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.MillisecondDuration
import tachiyomi.domain.immersion.model.NetCharacterProgress
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.RawTextRetention
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SessionStatus
import tachiyomi.domain.immersion.model.SourceUnitId
import tachiyomi.domain.immersion.service.CaptureCommand
import tachiyomi.domain.immersion.service.CaptureSuppressionReason
import tachiyomi.domain.immersion.service.FinalizeReason
import tachiyomi.domain.immersion.service.ImmersionDiagnosticErrorCode
import tachiyomi.domain.immersion.service.ImmersionRecorder
import tachiyomi.domain.immersion.service.ImmersionRecorderSnapshot
import tachiyomi.domain.immersion.service.ImmersionSessionState
import tachiyomi.domain.immersion.service.ImmersionShadowResult
import tachiyomi.domain.immersion.service.PauseReason
import tachiyomi.domain.immersion.service.RecordResult
import tachiyomi.domain.immersion.service.ResumeReason
import tachiyomi.domain.immersion.service.SessionContext
import tachiyomi.domain.immersion.service.SessionHandle
import tachiyomi.domain.immersion.service.SessionStartResult
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class NovelCaptureAdapterTest {

    @BeforeEach
    fun resetReport() {
        NovelCaptureReconciliationReporter.resetForTest()
    }

    @Test
    fun `forward reading records canonical Unicode gross unique and signed net metrics`() = runTest {
        val recorder = FakeRecorder()
        val adapter = adapter(recorder)

        adapter.start("chapter-1.xhtml", 100)
        adapter.onVisibleRanges(
            "chapter-1.xhtml",
            ranges(NovelVisibleRange(0, 6, "日本A😀 한")),
        )
        adapter.onProgress(120)
        adapter.finalize(legacy(net = 20)).await()

        val exposure = recorder.commands.filterIsInstance<CaptureCommand.Exposure>().single()
        exposure.grossCharacters shouldBe NonNegativeCounter(4)
        exposure.uniqueSourceCharacters shouldBe NonNegativeCounter(4)
        exposure.source.sourceStart shouldBe 0
        exposure.source.sourceEnd shouldBe 6
        exposure.source.rawText shouldBe "日本A😀 한"
        recorder.commands.filterIsInstance<CaptureCommand.Progress>()
            .single()
            .netCharacters shouldBe NetCharacterProgress(20)
    }

    @Test
    fun `reflow callbacks deduplicate while a real leave and reread adds only gross`() = runTest {
        val recorder = FakeRecorder()
        val adapter = adapter(recorder)
        val page = ranges(NovelVisibleRange(0, 4, "日本語A"))

        adapter.start("chapter-1.xhtml", 0)
        adapter.onVisibleRanges("chapter-1.xhtml", page)
        adapter.onVisibleRanges("chapter-1.xhtml", page)
        adapter.onVisibleRanges("chapter-1.xhtml", "[]")
        adapter.onVisibleRanges("chapter-1.xhtml", page)
        adapter.finalize(legacy()).await()

        val exposures = recorder.commands.filterIsInstance<CaptureCommand.Exposure>()
        exposures shouldHaveSize 2
        exposures.map { it.grossCharacters.value } shouldBe listOf(4L, 4L)
        exposures.map { it.uniqueSourceCharacters.value } shouldBe listOf(4L, 0L)
        exposures.map(CaptureCommand.Exposure::replayOrdinal) shouldBe listOf(0, 1)
        exposures.map { it.source.id }.distinct() shouldHaveSize 1
    }

    @Test
    fun `stable ranges survive reopen and global unique source stays deduplicated`() = runTest {
        val firstRecorder = FakeRecorder()
        val first = adapter(firstRecorder, retention = RawTextRetention.NEVER)
        val visible = ranges(NovelVisibleRange(64, 68, "한국語A"))

        first.start("chapter-2.xhtml", 10)
        first.onVisibleRanges("chapter-2.xhtml", visible)
        first.finalize(legacy()).await()
        val firstExposure = firstRecorder.commands.filterIsInstance<CaptureCommand.Exposure>().single()
        firstExposure.source.rawText shouldBe null

        val secondRecorder = FakeRecorder(seenSources = mutableSetOf(firstExposure.source.id))
        val second = adapter(secondRecorder)
        second.start("chapter-2.xhtml", 10)
        second.onVisibleRanges("chapter-2.xhtml", visible)
        second.finalize(legacy()).await()
        val secondExposure = secondRecorder.commands.filterIsInstance<CaptureCommand.Exposure>().single()

        secondExposure.source.id shouldBe firstExposure.source.id
        secondExposure.source.canonicalLocator shouldBe firstExposure.source.canonicalLocator
        secondExposure.uniqueSourceCharacters shouldBe NonNegativeCounter.ZERO
    }

    @Test
    fun `backward reading stays signed while reread is additional gross exposure`() = runTest {
        val recorder = FakeRecorder()
        val adapter = adapter(recorder)
        val visible = ranges(NovelVisibleRange(0, 3, "日本語"))

        adapter.start("chapter-1.xhtml", 100)
        adapter.onVisibleRanges("chapter-1.xhtml", visible)
        adapter.onProgress(140)
        adapter.onVisibleRanges("chapter-1.xhtml", "[]")
        adapter.onProgress(110)
        adapter.onVisibleRanges("chapter-1.xhtml", visible)
        adapter.finalize(legacy(net = 10)).await()

        recorder.commands.filterIsInstance<CaptureCommand.Exposure>()
            .sumOf { it.grossCharacters.value } shouldBe 6
        recorder.commands.filterIsInstance<CaptureCommand.Exposure>()
            .sumOf { it.uniqueSourceCharacters.value } shouldBe 3
        recorder.commands.filterIsInstance<CaptureCommand.Progress>()
            .sumOf { it.netCharacters.value } shouldBe 10
    }

    @Test
    fun `chapter search bookmark style jumps reset net baseline without fabricating distance`() = runTest {
        val recorder = FakeRecorder()
        val adapter = adapter(recorder)

        adapter.start("chapter-1.xhtml", 100)
        adapter.onProgress(120)
        adapter.onChapterChanged("chapter-8.xhtml", 2_000, NovelNavigationCause.SEARCH)
        adapter.onProgress(2_012)
        adapter.onChapterChanged("chapter-3.xhtml", 700, NovelNavigationCause.BOOKMARK)
        adapter.onProgress(705)
        adapter.resetProgressBaseline(900, recordSeek = true)
        adapter.onProgress(905)
        adapter.resetProgressBaseline(1_200, recordSeek = false)
        adapter.onProgress(1_202)
        adapter.finalize(legacy(net = 44)).await()

        recorder.commands.filterIsInstance<CaptureCommand.Progress>()
            .sumOf { it.netCharacters.value } shouldBe 44
        recorder.commands.filterIsInstance<CaptureCommand.Activity>()
            .count { it.eventType == EventType.SEEK } shouldBe 3
    }

    @Test
    fun `overlay and background block capture until the reader is visible again`() = runTest {
        val recorder = FakeRecorder()
        val adapter = adapter(recorder)
        val visible = ranges(NovelVisibleRange(0, 3, "日本語"))

        adapter.start("chapter-1.xhtml", 10)
        adapter.setOverlayVisible(NovelCaptureOverlay.LOOKUP_POPUP, true)
        adapter.onProgress(20)
        adapter.onVisibleRanges("chapter-1.xhtml", visible)
        adapter.setBackgrounded(true)
        adapter.setOverlayVisible(NovelCaptureOverlay.LOOKUP_POPUP, false)
        adapter.setBackgrounded(false)
        adapter.onVisibleRanges("chapter-1.xhtml", visible)
        adapter.onProgress(22)
        adapter.finalize(legacy(net = 2)).await()

        recorder.pauses shouldBe listOf(PauseReason.USER)
        recorder.resumes shouldBe listOf(ResumeReason.FOREGROUND)
        recorder.commands.filterIsInstance<CaptureCommand.Exposure>() shouldHaveSize 1
        recorder.commands.filterIsInstance<CaptureCommand.Progress>()
            .single()
            .netCharacters shouldBe NetCharacterProgress(2)
    }

    @Test
    fun `completion events are idempotent and incognito suppression creates no capture`() = runTest {
        val recorder = FakeRecorder()
        val adapter = adapter(recorder)

        adapter.start("chapter-1.xhtml", 0)
        adapter.onChapterCompleted()
        adapter.onChapterCompleted()
        adapter.onTitleCompleted()
        adapter.onTitleCompleted()
        adapter.finalize(legacy()).await()

        recorder.commands.filterIsInstance<CaptureCommand.Activity>()
            .map(CaptureCommand.Activity::eventType) shouldBe
            listOf(EventType.UNIT_COMPLETED, EventType.TITLE_COMPLETED)

        val suppressedRecorder = FakeRecorder(suppressStart = true)
        val suppressed = adapter(suppressedRecorder)
        suppressed.start("chapter-1.xhtml", 0)
        suppressed.onVisibleRanges("chapter-1.xhtml", ranges(NovelVisibleRange(0, 1, "日")))
        suppressed.onProgress(1)
        suppressed.finalize(legacy()).await()
        suppressedRecorder.commands shouldBe emptyList()
    }

    @Test
    fun `codec counts supplementary code points and rejects overlapping source ranges`() {
        val decoded = NovelVisibleRangeCodec.decode(
            ranges(
                NovelVisibleRange(0, 3, "𠮷A한"),
                NovelVisibleRange(3, 5, "日本"),
            ),
        )
        decoded.first().text.length shouldBe 4
        decoded.first().endExclusive shouldBe 3

        runCatching {
            NovelVisibleRangeCodec.decode(
                """[{"start":0,"endExclusive":2,"text":"日本"},{"start":1,"endExclusive":3,"text":"本語"}]""",
            )
        }.isFailure shouldBe true
        runCatching {
            NovelVisibleRange(0, 1, "\uD800")
        }.isFailure shouldBe true
    }

    @Test
    fun `session and day reconciliation expose exact net divergence and midnight day keys`() {
        val session = completedSession(
            activeMillis = 60_000,
            net = 30,
            endedAt = Instant.parse("2026-07-26T00:30:00Z").toEpochMilli(),
        )
        NovelCaptureReconciliationReporter.record(
            session = session,
            legacy = legacy(activeMillis = 60_000, net = 30),
            idleToleranceMillis = 120_000,
            zoneId = ZoneId.of("Europe/London"),
        )

        val report = NovelCaptureReconciliationReporter.report.value
        report.entries shouldHaveSize 2
        report.entries.single { it.scope == NovelReconciliationScope.SESSION }
            .result.shouldBeInstanceOf<ImmersionShadowResult.Matched>()
        report.entries.single { it.scope == NovelReconciliationScope.DAY }.let {
            it.key shouldBe "2026-07-26"
            it.result.shouldBeInstanceOf<ImmersionShadowResult.Matched>()
        }

        NovelCaptureReconciliationReporter.record(
            session = completedSession(activeMillis = 1_000, net = 4),
            legacy = legacy(activeMillis = 1_000, net = 3),
            idleToleranceMillis = 120_000,
            zoneId = ZoneId.of("UTC"),
        )
        NovelCaptureReconciliationReporter.report.value.entries
            .first { it.scope == NovelReconciliationScope.SESSION }
            .result.shouldBeInstanceOf<ImmersionShadowResult.Diverged>()
    }

    private fun TestScope.adapter(
        recorder: FakeRecorder,
        retention: RawTextRetention = RawTextRetention.UNTIL_DELETED,
    ) = NovelCaptureAdapter(
        book = NovelCaptureBook(
            documentId = "stable-book",
            displayTitle = "Test novel",
            profileId = "reader-profile",
            languageTag = null,
            createdAtEpochMillis = 0,
        ),
        recorder = recorder,
        rawTextRetention = { retention },
        idleTimeoutMillis = 120_000,
        clock = { 1_000 },
        zoneId = { ZoneId.of("UTC") },
        workerScope = this,
    )

    private fun ranges(vararg values: NovelVisibleRange): String =
        Json.encodeToString(values.toList())

    private fun legacy(
        activeMillis: Long = 1_000,
        net: Long = 0,
    ) = LegacyNovelSessionSnapshot(
        activeDurationMillis = activeMillis,
        netCharacters = net,
        equivalentPolicy = true,
    )

    private class FakeRecorder(
        val seenSources: MutableSet<SourceUnitId> = mutableSetOf(),
        private val suppressStart: Boolean = false,
    ) : ImmersionRecorder {
        private val mutableState = MutableStateFlow(ImmersionRecorderSnapshot())
        override val state: StateFlow<ImmersionRecorderSnapshot> = mutableState
        val commands = mutableListOf<CaptureCommand>()
        val pauses = mutableListOf<PauseReason>()
        val resumes = mutableListOf<ResumeReason>()
        private var context: SessionContext? = null
        private var handle: SessionHandle? = null

        override suspend fun startSession(context: SessionContext): SessionStartResult {
            if (suppressStart) {
                return SessionStartResult.Suppressed(CaptureSuppressionReason.INCOGNITO)
            }
            val handle = SessionHandle(SessionId(UUID.randomUUID().toString()))
            this.context = context
            this.handle = handle
            mutableState.value = ImmersionRecorderSnapshot(
                sessionId = handle.sessionId,
                state = ImmersionSessionState.ACTIVE,
            )
            return SessionStartResult.Started(handle)
        }

        override fun record(command: CaptureCommand): RecordResult {
            val active = handle ?: return RecordResult.Rejected(ImmersionSessionState.NOT_STARTED)
            return record(active, command)
        }

        override fun record(
            handle: SessionHandle,
            command: CaptureCommand,
        ): RecordResult {
            if (handle != this.handle) return RecordResult.Rejected(ImmersionSessionState.ACTIVE)
            commands += command
            return RecordResult.Enqueued(1)
        }

        override suspend fun pause(reason: PauseReason) {
            pauses += reason
        }

        override suspend fun pause(
            handle: SessionHandle,
            reason: PauseReason,
        ) {
            if (handle == this.handle) pauses += reason
        }

        override suspend fun resume(reason: ResumeReason) {
            resumes += reason
        }

        override suspend fun resume(
            handle: SessionHandle,
            reason: ResumeReason,
        ) {
            if (handle == this.handle) resumes += reason
        }

        override suspend fun finalize(reason: FinalizeReason) {
            handle?.let { finalize(it, reason) }
        }

        override suspend fun finalize(
            handle: SessionHandle,
            reason: FinalizeReason,
        ): ImmersionSession? {
            if (handle != this.handle) return null
            val session = completedSession(
                id = handle.sessionId,
                title = requireNotNull(context).title,
                activeMillis = 1_000,
                net = commands.sumOf { command ->
                    when (command) {
                        is CaptureCommand.Progress -> command.netCharacters.value
                        is CaptureCommand.Exposure -> command.netCharacters.value
                        is CaptureCommand.Activity -> 0
                        is CaptureCommand.Lookup -> 0
                        is CaptureCommand.AnkiOperation -> 0
                    }
                },
                gross = commands.filterIsInstance<CaptureCommand.Exposure>()
                    .sumOf { it.grossCharacters.value },
                unique = commands.filterIsInstance<CaptureCommand.Exposure>()
                    .sumOf { it.uniqueSourceCharacters.value },
            )
            this.handle = null
            return session
        }

        override suspend fun setIncognito(enabled: Boolean) = Unit

        override suspend fun recoverAbandonedSessions(): Long = 0

        override suspend fun hasSeenSource(sourceUnitId: SourceUnitId): Boolean =
            sourceUnitId in seenSources
    }
}

private fun completedSession(
    id: SessionId = SessionId(UUID.randomUUID().toString()),
    title: ImmersionTitle? = null,
    activeMillis: Long,
    net: Long,
    gross: Long = 0,
    unique: Long = 0,
    endedAt: Long = 1_000,
): ImmersionSession {
    val resolvedTitle = title ?: ImmersionTitle(
        id = tachiyomi.domain.immersion.model.TitleId(UUID.randomUUID().toString()),
        mediaKind = MediaKind.NOVEL,
        sourceKey = "test",
        displayTitle = "Test",
        createdAtEpochMillis = 0,
        updatedAtEpochMillis = 0,
    )
    return ImmersionSession(
        id = id,
        deviceId = "device",
        titleId = resolvedTitle.id,
        mediaKind = MediaKind.NOVEL,
        languageTag = resolvedTitle.languageTag,
        profileId = resolvedTitle.profileId,
        startedAtEpochMillis = 0,
        endedAtEpochMillis = endedAt,
        startZoneId = "UTC",
        startOffsetSeconds = 0,
        status = SessionStatus.COMPLETED,
        activeDuration = MillisecondDuration(activeMillis),
        elapsedDuration = MillisecondDuration(activeMillis),
        grossCharacters = NonNegativeCounter(gross),
        uniqueSourceCharacters = NonNegativeCounter(unique),
        netCharacters = NetCharacterProgress(net),
        sourceUnitCount = NonNegativeCounter.ZERO,
        lastSequence = 1,
        lastHeartbeatAtEpochMillis = endedAt,
        captureVersion = 1,
        schemaVersion = 1,
        legacyImport = false,
    )
}
