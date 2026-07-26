// SPDX-License-Identifier: MIT

package mihon.feature.stats.capture

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
import tachiyomi.domain.immersion.model.SourceKind
import tachiyomi.domain.immersion.model.SourceUnitId
import tachiyomi.domain.immersion.service.CaptureCommand
import tachiyomi.domain.immersion.service.CaptureSuppressionReason
import tachiyomi.domain.immersion.service.FinalizeReason
import tachiyomi.domain.immersion.service.ImmersionCaptureAdapter
import tachiyomi.domain.immersion.service.ImmersionRecorder
import tachiyomi.domain.immersion.service.ImmersionRecorderSnapshot
import tachiyomi.domain.immersion.service.ImmersionSessionState
import tachiyomi.domain.immersion.service.ImmersionShadowResult
import tachiyomi.domain.immersion.service.ImmersionStatsDiagnosticsStore
import tachiyomi.domain.immersion.service.InteractionProvenance
import tachiyomi.domain.immersion.service.PauseReason
import tachiyomi.domain.immersion.service.RecordResult
import tachiyomi.domain.immersion.service.ResumeReason
import tachiyomi.domain.immersion.service.SessionContext
import tachiyomi.domain.immersion.service.SessionHandle
import tachiyomi.domain.immersion.service.SessionStartResult
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class MangaCaptureAdapterTest {

    @BeforeEach
    fun resetReport() {
        MangaCaptureReconciliationReporter.resetForTest()
    }

    @Test
    fun `pages remain measurable without OCR and chapter identity prevents index collisions`() = runTest {
        val recorder = FakeRecorder()
        val adapter = adapter(recorder)
        val chapterOne = page(chapter = 10, index = 0, availability = MangaOcrAvailability.UNSUPPORTED)
        val chapterTwo = page(chapter = 11, index = 0, availability = MangaOcrAvailability.FAILED)

        adapter.onVisiblePages(listOf(chapterOne))
        adapter.onOcrResult(chapterOne.key, MangaOcrAvailability.UNSUPPORTED, emptyList())
        adapter.onVisiblePages(emptyList())
        adapter.onVisiblePages(listOf(chapterTwo))
        adapter.onOcrResult(chapterTwo.key, MangaOcrAvailability.FAILED, emptyList())
        adapter.finalize(legacy()).await()

        val pages = recorder.exposures(SourceKind.MANGA_PAGE)
        pages shouldHaveSize 2
        pages.map { it.source.id }.distinct() shouldHaveSize 2
        pages.map { it.grossCharacters.value } shouldBe listOf(0L, 0L)
        adapter.coverage.value shouldBe MangaOcrCoverageSnapshot(
            viewedPages = 2,
            ocrCoveredPages = 0,
            unavailablePages = 2,
        )
    }

    @Test
    fun `cached or newly completed OCR counts only while its page is visible`() = runTest {
        val recorder = FakeRecorder()
        val adapter = adapter(recorder)
        val visible = page()

        adapter.onOcrResult(visible.key, MangaOcrAvailability.AVAILABLE, listOf(block("日本語")))
        recorder.exposures(SourceKind.MANGA_OCR_BLOCK) shouldHaveSize 0
        adapter.onVisiblePages(listOf(visible))
        adapter.onOcrResult(visible.key, MangaOcrAvailability.AVAILABLE, listOf(block("日本語")))
        adapter.finalize(legacy(characters = 3)).await()

        val exposure = recorder.exposures(SourceKind.MANGA_OCR_BLOCK).single()
        exposure.grossCharacters shouldBe NonNegativeCounter(3)
        exposure.uniqueSourceCharacters shouldBe NonNegativeCounter(3)
        exposure.source.ocrEngineId shouldBe "lens"
        exposure.source.ocrVersion shouldBe 2
        adapter.coverage.value.ratio shouldBe 1.0
    }

    @Test
    fun `rotation does not duplicate but a real page revisit adds gross only`() = runTest {
        val recorder = FakeRecorder()
        val adapter = adapter(recorder)
        val visible = page()
        val blocks = listOf(block("日本語"))

        adapter.onVisiblePages(listOf(visible))
        adapter.onOcrResult(visible.key, MangaOcrAvailability.AVAILABLE, blocks)
        adapter.onVisiblePages(listOf(visible))
        adapter.onOcrResult(visible.key, MangaOcrAvailability.AVAILABLE, blocks)
        adapter.onVisiblePages(emptyList())
        adapter.onVisiblePages(listOf(visible))
        adapter.finalize(legacy(characters = 3, equivalent = false)).await()

        val exposures = recorder.exposures(SourceKind.MANGA_OCR_BLOCK)
        exposures shouldHaveSize 2
        exposures.map { it.grossCharacters.value } shouldBe listOf(3L, 3L)
        exposures.map { it.uniqueSourceCharacters.value } shouldBe listOf(3L, 0L)
        exposures.map(CaptureCommand.Exposure::replayOrdinal) shouldBe listOf(0, 1)
    }

    @Test
    fun `two-page spread exposes both simultaneously visible pages and their OCR`() = runTest {
        val recorder = FakeRecorder()
        val adapter = adapter(recorder)
        val left = page(index = 4)
        val right = page(index = 5)

        adapter.onOcrResult(left.key, MangaOcrAvailability.AVAILABLE, listOf(block("左")))
        adapter.onOcrResult(right.key, MangaOcrAvailability.AVAILABLE, listOf(block("右")))
        adapter.onVisiblePages(listOf(left, right))
        adapter.finalize(legacy(characters = 2, equivalent = false)).await()

        val pageExposures = recorder.exposures(SourceKind.MANGA_PAGE)
        pageExposures shouldHaveSize 2
        pageExposures.map { it.source.id }.distinct() shouldHaveSize 2

        val ocrExposures = recorder.exposures(SourceKind.MANGA_OCR_BLOCK)
        ocrExposures shouldHaveSize 2
        ocrExposures.map { it.source.id }.distinct() shouldHaveSize 2
        ocrExposures.map { it.grossCharacters.value } shouldBe listOf(1L, 1L)
        ocrExposures.map { it.uniqueSourceCharacters.value } shouldBe listOf(1L, 1L)
        adapter.coverage.value shouldBe MangaOcrCoverageSnapshot(
            viewedPages = 2,
            ocrCoveredPages = 2,
            unavailablePages = 0,
        )
    }

    @Test
    fun `rapid flips resume from idle and ignore pages reported while backgrounded`() = runTest {
        val recorder = FakeRecorder()
        val adapter = adapter(recorder)

        repeat(4) { index ->
            adapter.onVisiblePages(listOf(page(index = index)))
            runCurrent()
        }
        recorder.enterIdle()
        adapter.onVisiblePages(listOf(page(index = 4)))
        runCurrent()

        adapter.setBackgrounded(true)
        runCurrent()
        repeat(4) { index ->
            adapter.onVisiblePages(listOf(page(index = index + 5)))
            runCurrent()
        }
        adapter.setBackgrounded(false)
        runCurrent()
        adapter.onVisiblePages(listOf(page(index = 9)))
        adapter.finalize(legacy(equivalent = false)).await()

        val pageExposures = recorder.exposures(SourceKind.MANGA_PAGE)
        pageExposures shouldHaveSize 6
        pageExposures.map { it.source.id }.distinct() shouldHaveSize 6
        pageExposures.all { it.grossCharacters == NonNegativeCounter.ZERO } shouldBe true
        recorder.idleResumptions shouldBe 1
        recorder.pauses shouldBe listOf(PauseReason.BACKGROUND)
        recorder.resumes shouldBe listOf(ResumeReason.FOREGROUND)
    }

    @Test
    fun `adapter recreation retains page identity without repeating unique OCR`() = runTest {
        val persistedSourceIds = mutableSetOf<SourceUnitId>()
        val visible = page(chapter = 10, index = 3)
        val text = block("再読")

        val firstRecorder = FakeRecorder(persistedSourceIds = persistedSourceIds)
        val firstAdapter = adapter(firstRecorder)
        firstAdapter.onOcrResult(visible.key, MangaOcrAvailability.AVAILABLE, listOf(text))
        firstAdapter.onVisiblePages(listOf(visible))
        firstAdapter.finalize(legacy(characters = 2)).await()

        val secondRecorder = FakeRecorder(persistedSourceIds = persistedSourceIds)
        val recreatedAdapter = adapter(secondRecorder)
        recreatedAdapter.onOcrResult(visible.key, MangaOcrAvailability.AVAILABLE, listOf(text))
        recreatedAdapter.onVisiblePages(listOf(visible))
        recreatedAdapter.finalize(legacy(characters = 2, equivalent = false)).await()

        firstRecorder.exposures(SourceKind.MANGA_PAGE).single().source.id shouldBe
            secondRecorder.exposures(SourceKind.MANGA_PAGE).single().source.id
        val firstOcr = firstRecorder.exposures(SourceKind.MANGA_OCR_BLOCK).single()
        val recreatedOcr = secondRecorder.exposures(SourceKind.MANGA_OCR_BLOCK).single()
        recreatedOcr.source.id shouldBe firstOcr.source.id
        firstOcr.grossCharacters shouldBe NonNegativeCounter(2)
        firstOcr.uniqueSourceCharacters shouldBe NonNegativeCounter(2)
        recreatedOcr.grossCharacters shouldBe NonNegativeCounter(2)
        recreatedOcr.uniqueSourceCharacters shouldBe NonNegativeCounter.ZERO
    }

    @Test
    fun `webtoon block exposure follows repeated partial visibility threshold`() = runTest {
        val recorder = FakeRecorder()
        val adapter = adapter(recorder)
        val key = MangaPageKey(10, 4)
        val text = block("日本", ymin = 0f, ymax = 0.3f)

        adapter.onVisiblePages(listOf(MangaPageViewport(key, visibleTop = 0f, visibleBottom = 0.2f)))
        adapter.onOcrResult(key, MangaOcrAvailability.AVAILABLE, listOf(text))
        adapter.onVisiblePages(listOf(MangaPageViewport(key, visibleTop = 0.4f, visibleBottom = 0.8f)))
        adapter.onVisiblePages(listOf(MangaPageViewport(key, visibleTop = 0f, visibleBottom = 0.2f)))
        adapter.finalize(legacy(characters = 2, equivalent = false)).await()

        recorder.exposures(SourceKind.MANGA_OCR_BLOCK) shouldHaveSize 2
    }

    @Test
    fun `engine revision and hash changes create new OCR identities`() = runTest {
        val recorder = FakeRecorder()
        val adapter = adapter(recorder)
        val visible = page()

        adapter.onVisiblePages(listOf(visible))
        adapter.onOcrResult(visible.key, MangaOcrAvailability.AVAILABLE, listOf(block("日本", version = 1)))
        adapter.onOcrResult(visible.key, MangaOcrAvailability.AVAILABLE, listOf(block("日本語", version = 2)))
        adapter.finalize(legacy(characters = 5, equivalent = false)).await()

        val exposures = recorder.exposures(SourceKind.MANGA_OCR_BLOCK)
        exposures shouldHaveSize 2
        exposures.map { it.source.id }.distinct() shouldHaveSize 2
        exposures.map { it.source.normalizedTextHash }.distinct() shouldHaveSize 2
    }

    @Test
    fun `lookup provenance freezes the active session and exact duplicate-text block source`() = runTest {
        val recorder = FakeRecorder()
        val adapter = adapter(recorder)
        val visible = page()
        val first = block("日本", ymin = 0f, ymax = 0.5f, blockId = "first")
        val second = block("日本", ymin = 0.5f, ymax = 1f, blockId = "second")

        adapter.onVisiblePages(listOf(visible))
        adapter.onOcrResult(visible.key, MangaOcrAvailability.AVAILABLE, listOf(first, second))
        runCurrent()

        val transformedSelection = second.copy(xmin = 0.1f, ymin = 0.1f, xmax = 0.9f, ymax = 0.9f)
        val provenance = requireNotNull(
            adapter.lookupProvenance(
                page = visible.key,
                block = transformedSelection,
                blockIndex = 0,
            ),
        )
        val exposures = recorder.exposures(SourceKind.MANGA_OCR_BLOCK)

        provenance.sessionId shouldBe recorder.startedSessionId
        provenance.sourceUnitId shouldBe exposures[1].source.id
        provenance.sourceUnitId shouldBe adapter.lookupProvenance(
            page = visible.key,
            block = transformedSelection,
            blockIndex = 0,
        )?.sourceUnitId

        val next = page(chapter = 10, index = 1)
        val nextBlock = block("日本", blockId = "second")
        adapter.onVisiblePages(listOf(next))
        adapter.onOcrResult(next.key, MangaOcrAvailability.AVAILABLE, listOf(nextBlock))
        runCurrent()

        val nextProvenance = requireNotNull(adapter.lookupProvenance(next.key, nextBlock, 0))
        nextProvenance.sessionId shouldBe provenance.sessionId
        nextProvenance.sourceUnitId shouldBe recorder.exposures(SourceKind.MANGA_OCR_BLOCK).last().source.id
        provenance.sourceUnitId shouldBe exposures[1].source.id

        adapter.finalize(legacy(characters = 6)).await()
        adapter.lookupProvenance(visible.key, transformedSelection, 0) shouldBe null
    }

    @Test
    fun `lookup provenance keeps the session when an exact OCR source is unavailable`() = runTest {
        val offscreenRecorder = FakeRecorder()
        val offscreenAdapter = adapter(offscreenRecorder)
        val partialPage = MangaPageViewport(
            key = MangaPageKey(10, 0),
            visibleTop = 0f,
            visibleBottom = 0.2f,
        )
        val offscreenBlock = block("日本", ymin = 0.6f, ymax = 1f)

        offscreenAdapter.onVisiblePages(listOf(partialPage))
        offscreenAdapter.onOcrResult(
            partialPage.key,
            MangaOcrAvailability.AVAILABLE,
            listOf(offscreenBlock),
        )
        runCurrent()

        offscreenAdapter.lookupProvenance(partialPage.key, offscreenBlock, 0) shouldBe
            InteractionProvenance(
                sessionId = requireNotNull(offscreenRecorder.startedSessionId),
                sourceUnitId = null,
            )
        offscreenAdapter.finalize(legacy(equivalent = false)).await()

        val rejectingRecorder = FakeRecorder(rejectOcrExposures = true)
        val rejectingAdapter = adapter(rejectingRecorder)
        val visible = page()
        val rejectedBlock = block("語学")

        rejectingAdapter.onVisiblePages(listOf(visible))
        rejectingAdapter.onOcrResult(
            visible.key,
            MangaOcrAvailability.AVAILABLE,
            listOf(rejectedBlock),
        )
        runCurrent()

        rejectingAdapter.lookupProvenance(visible.key, rejectedBlock, 0) shouldBe
            InteractionProvenance(
                sessionId = requireNotNull(rejectingRecorder.startedSessionId),
                sourceUnitId = null,
            )
        rejectingAdapter.finalize(legacy(equivalent = false)).await()
    }

    @Test
    fun `immediate OCR tap keeps session provenance until the exact source is published`() = runTest {
        val recorder = FakeRecorder()
        val adapter = adapter(recorder)
        val visible = page()
        val selectedBlock = block("即時")

        adapter.onVisiblePages(listOf(visible))
        runCurrent()
        adapter.onOcrResult(
            visible.key,
            MangaOcrAvailability.AVAILABLE,
            listOf(selectedBlock),
        )

        adapter.lookupProvenance(visible.key, selectedBlock, 0) shouldBe
            InteractionProvenance(
                sessionId = requireNotNull(recorder.startedSessionId),
                sourceUnitId = null,
            )
        runCurrent()
        adapter.lookupProvenance(visible.key, selectedBlock, 0)?.sourceUnitId shouldBe
            recorder.exposures(SourceKind.MANGA_OCR_BLOCK).single().source.id
        adapter.finalize(legacy(characters = 2)).await()
    }

    @Test
    fun `background overlays completion and incognito preserve capture semantics`() = runTest {
        val recorder = FakeRecorder()
        val adapter = adapter(recorder)

        adapter.onVisiblePages(listOf(page()))
        adapter.setOverlayVisible(MangaCaptureOverlay.LOOKUP, true)
        adapter.setBackgrounded(true)
        adapter.setOverlayVisible(MangaCaptureOverlay.LOOKUP, false)
        adapter.setBackgrounded(false)
        adapter.onChapterCompleted(10)
        adapter.onChapterCompleted(10)
        adapter.onTitleCompleted()
        adapter.onTitleCompleted()
        adapter.finalize(legacy()).await()

        recorder.pauses shouldBe listOf(PauseReason.USER)
        recorder.resumes shouldBe listOf(ResumeReason.FOREGROUND)
        recorder.commands.filterIsInstance<CaptureCommand.Activity>()
            .map(CaptureCommand.Activity::eventType)
            .filter { it == EventType.UNIT_COMPLETED || it == EventType.TITLE_COMPLETED } shouldBe
            listOf(EventType.UNIT_COMPLETED, EventType.TITLE_COMPLETED)

        val suppressedRecorder = FakeRecorder(suppressStart = true)
        val suppressed = adapter(suppressedRecorder, incognito = true)
        suppressed.onVisiblePages(listOf(page()))
        suppressed.onOcrResult(page().key, MangaOcrAvailability.AVAILABLE, listOf(block("日")))
        runCurrent()
        suppressed.lookupProvenance(page().key, block("日"), 0) shouldBe null
        suppressed.finalize(legacy()).await()
        suppressedRecorder.commands shouldBe emptyList()
    }

    @Test
    fun `viewport bursts coalesce to the latest geometry without displacing completion`() = runTest {
        val recorder = FakeRecorder()
        val adapter = adapter(recorder)
        val key = MangaPageKey(chapterId = 10, pageIndex = 0)
        val ocrBlock = block("日本", ymin = 0.75f, ymax = 1f)

        adapter.onOcrResult(key, MangaOcrAvailability.AVAILABLE, listOf(ocrBlock))
        repeat(2_000) { index ->
            adapter.onVisiblePages(
                listOf(
                    MangaPageViewport(
                        key = key,
                        visibleTop = 0f,
                        visibleBottom = if (index == 1_999) 1f else 0.5f,
                    ),
                ),
            )
        }
        adapter.onChapterCompleted(key.chapterId)
        adapter.onTitleCompleted()
        adapter.finalize(legacy(characters = 2)).await()

        recorder.exposures(SourceKind.MANGA_PAGE) shouldHaveSize 1
        recorder.exposures(SourceKind.MANGA_OCR_BLOCK) shouldHaveSize 1
        recorder.commands.filterIsInstance<CaptureCommand.Activity>()
            .map(CaptureCommand.Activity::eventType)
            .filter { it == EventType.UNIT_COMPLETED || it == EventType.TITLE_COMPLETED } shouldBe
            listOf(EventType.UNIT_COMPLETED, EventType.TITLE_COMPLETED)
        adapter.queueDiagnostics.value.let {
            it.depth shouldBe 0
            it.coalescedCommands shouldBe 1_998
            it.droppedSnapshots shouldBe 0
            it.semanticOverflowCommands shouldBe 0
            it.droppedSemanticCommands shouldBe 0
        }
    }

    @Test
    fun `bounded queue diagnoses semantic drops and drains accepted FIFO before terminal`() = runTest {
        val queue = BoundedCaptureCommandQueue<Int>(capacity = 2, overflowCapacity = 1)
        val duplicate = CaptureCommandQueuePolicy.AdjacentCoalescible(
            family = "duplicate",
            key = Unit,
        )

        queue.offer(10, duplicate) shouldBe CaptureCommandOfferResult.ACCEPTED
        queue.offer(11, duplicate) shouldBe CaptureCommandOfferResult.COALESCED
        queue.offer(20, CaptureCommandQueuePolicy.NonCoalescible) shouldBe
            CaptureCommandOfferResult.ACCEPTED
        queue.offer(30, CaptureCommandQueuePolicy.NonCoalescible) shouldBe
            CaptureCommandOfferResult.SEMANTIC_OVERFLOW
        queue.offer(40, CaptureCommandQueuePolicy.NonCoalescible) shouldBe
            CaptureCommandOfferResult.SEMANTIC_DROPPED
        queue.diagnostics.value.let {
            it.depth shouldBe 3
            it.overflowDepth shouldBe 1
            it.highWatermark shouldBe 3
            it.coalescedCommands shouldBe 1
            it.semanticOverflowCommands shouldBe 1
            it.droppedSemanticCommands shouldBe 1
        }
        queue.finish(99) shouldBe true

        queue.receive() shouldBe 11
        queue.receive() shouldBe 20
        queue.receive() shouldBe 30
        queue.receive() shouldBe 99
        queue.receive() shouldBe null
    }

    @Test
    fun `semantic commands evict only safe snapshots and snapshot drops stay observable`() = runTest {
        val queue = BoundedCaptureCommandQueue<Int>(capacity = 2, overflowCapacity = 1)
        val snapshot = CaptureCommandQueuePolicy.LatestSnapshot(
            family = "position",
            key = Unit,
        )

        queue.offer(1, CaptureCommandQueuePolicy.NonCoalescible) shouldBe
            CaptureCommandOfferResult.ACCEPTED
        queue.offer(2, snapshot) shouldBe CaptureCommandOfferResult.ACCEPTED
        queue.offer(3, CaptureCommandQueuePolicy.NonCoalescible) shouldBe
            CaptureCommandOfferResult.ACCEPTED
        queue.offer(4, snapshot) shouldBe CaptureCommandOfferResult.SNAPSHOT_DROPPED

        queue.diagnostics.value.let {
            it.depth shouldBe 2
            it.evictedSnapshots shouldBe 1
            it.droppedSnapshots shouldBe 1
            it.semanticOverflowCommands shouldBe 0
            it.droppedSemanticCommands shouldBe 0
        }
        queue.finish(99) shouldBe true
        queue.receive() shouldBe 1
        queue.receive() shouldBe 3
        queue.receive() shouldBe 99
    }

    @Test
    fun `semantic boundaries seal earlier snapshots against later eviction`() = runTest {
        val queue = BoundedCaptureCommandQueue<Int>(capacity = 3, overflowCapacity = 1)
        val snapshot = CaptureCommandQueuePolicy.LatestSnapshot(
            family = "position",
            key = Unit,
        )

        queue.offer(1, snapshot) shouldBe CaptureCommandOfferResult.ACCEPTED
        queue.offer(2, CaptureCommandQueuePolicy.NonCoalescible) shouldBe
            CaptureCommandOfferResult.ACCEPTED
        queue.offer(3, CaptureCommandQueuePolicy.NonCoalescible) shouldBe
            CaptureCommandOfferResult.ACCEPTED
        queue.offer(4, CaptureCommandQueuePolicy.NonCoalescible) shouldBe
            CaptureCommandOfferResult.SEMANTIC_OVERFLOW
        queue.offer(5, CaptureCommandQueuePolicy.NonCoalescible) shouldBe
            CaptureCommandOfferResult.SEMANTIC_DROPPED

        queue.diagnostics.value.let {
            it.depth shouldBe 4
            it.overflowDepth shouldBe 1
            it.highWatermark shouldBe 4
            it.evictedSnapshots shouldBe 0
            it.semanticOverflowCommands shouldBe 1
            it.droppedSemanticCommands shouldBe 1
        }
        queue.finish(99) shouldBe true
        queue.receive() shouldBe 1
        queue.receive() shouldBe 2
        queue.receive() shouldBe 3
        queue.receive() shouldBe 4
        queue.receive() shouldBe 99
    }

    @Test
    fun `adapter isolates reader from bounded saturation and diagnoses semantic loss`() = runTest {
        val recorder = FakeRecorder()
        val diagnostics = ImmersionStatsDiagnosticsStore()
        val adapter = adapter(recorder, diagnostics = diagnostics)

        val failure = runCatching {
            repeat(129) { chapterId ->
                adapter.onChapterCompleted(chapterId.toLong())
            }
        }.exceptionOrNull()

        failure shouldBe null
        adapter.queueDiagnostics.value.let {
            it.depth shouldBe 128
            it.overflowDepth shouldBe 64
            it.highWatermark shouldBe 128
            it.semanticOverflowCommands shouldBe 64
            it.droppedSemanticCommands shouldBe 1
            it.droppedSnapshots shouldBe 0
        }
        diagnostics.state.value.adapterDiagnostics
            .getValue(ImmersionCaptureAdapter.MANGA)
            .droppedSemanticCommandCount shouldBe NonNegativeCounter(1)
        adapter.finalize(legacy()).await()
        recorder.commands.filterIsInstance<CaptureCommand.Activity>()
            .count { it.eventType == EventType.UNIT_COMPLETED } shouldBe 128
    }

    @Test
    fun `incognito queue saturation is not persisted as a diagnostic`() = runTest {
        val diagnostics = ImmersionStatsDiagnosticsStore()
        val adapter = adapter(
            recorder = FakeRecorder(),
            incognito = true,
            diagnostics = diagnostics,
        )

        repeat(129) { chapterId ->
            adapter.onChapterCompleted(chapterId.toLong())
        }

        adapter.queueDiagnostics.value.droppedSemanticCommands shouldBe 1
        diagnostics.state.value.adapterDiagnostics
            .getValue(ImmersionCaptureAdapter.MANGA)
            .droppedSemanticCommandCount shouldBe NonNegativeCounter.ZERO
        adapter.finalize(legacy()).await()
    }

    @Test
    fun `recorder exception is diagnosed and later commands plus finalization still drain`() = runTest {
        val recorder = FakeRecorder(recordFailuresRemaining = 1)
        val adapter = adapter(recorder)

        adapter.onChapterCompleted(1)
        adapter.onChapterCompleted(2)
        adapter.finalize(legacy()).await()

        adapter.queueDiagnostics.value.workerFailures shouldBe 1
        recorder.commands.filterIsInstance<CaptureCommand.Activity>()
            .map(CaptureCommand.Activity::eventType) shouldBe listOf(EventType.UNIT_COMPLETED)
    }

    @Test
    fun `recorder finalization exception completes the terminal boundary`() = runTest {
        val recorder = FakeRecorder(finalizeFailuresRemaining = 1)
        val adapter = adapter(recorder)

        adapter.onChapterCompleted(1)
        adapter.finalize(legacy()).await()
        adapter.finalize(legacy()).await()

        adapter.queueDiagnostics.value.workerFailures shouldBe 1
    }

    @Test
    fun `session and day reconciliation report exact and policy differences`() = runTest {
        val recorder = FakeRecorder(activeMillis = 60_000)
        val adapter = adapter(recorder)
        val visible = page()
        adapter.onVisiblePages(listOf(visible))
        adapter.onOcrResult(visible.key, MangaOcrAvailability.AVAILABLE, listOf(block("日本")))
        adapter.finalize(legacy(activeMillis = 60_000, characters = 2)).await()

        val report = MangaCaptureReconciliationReporter.report.value
        report.entries shouldHaveSize 2
        report.entries.single { it.scope == MangaReconciliationScope.SESSION }
            .result.shouldBeInstanceOf<ImmersionShadowResult.Matched>()
        report.entries.single { it.scope == MangaReconciliationScope.DAY }.key shouldBe "1970-01-01"
    }

    private fun TestScope.adapter(
        recorder: FakeRecorder,
        incognito: Boolean = false,
        diagnostics: ImmersionStatsDiagnosticsStore? = null,
    ) = MangaCaptureAdapter(
        captureTitle = MangaCaptureTitle(
            mangaId = 1,
            sourceId = 2,
            displayTitle = "Test manga",
            profileId = "ja",
            languageTag = null,
            createdAtEpochMillis = 0,
        ),
        recorder = recorder,
        rawTextRetention = { RawTextRetention.UNTIL_DELETED },
        idleTimeoutMillis = 120_000,
        incognito = incognito,
        clock = { 1_000 },
        zoneId = { ZoneId.of("UTC") },
        workerScope = this,
        diagnostics = diagnostics,
    )

    private fun page(
        chapter: Long = 10,
        index: Int = 0,
        availability: MangaOcrAvailability = MangaOcrAvailability.NOT_REQUESTED,
    ) = MangaPageViewport(MangaPageKey(chapter, index), ocrAvailability = availability)

    private fun block(
        text: String,
        ymin: Float = 0f,
        ymax: Float = 1f,
        version: Int = 2,
        blockId: String = "block-0",
    ) = MangaOcrBlockCapture(
        text = text,
        blockId = blockId,
        xmin = 0f,
        ymin = ymin,
        xmax = 1f,
        ymax = ymax,
        engineId = "lens",
        engineVersion = version,
    )

    private fun legacy(
        activeMillis: Long = 1_000,
        characters: Long = 0,
        equivalent: Boolean = true,
    ) = LegacyMangaSessionSnapshot(activeMillis, characters, equivalent)

    private class FakeRecorder(
        private val suppressStart: Boolean = false,
        private val activeMillis: Long = 1_000,
        private val rejectOcrExposures: Boolean = false,
        private val persistedSourceIds: MutableSet<SourceUnitId> = mutableSetOf(),
        private var recordFailuresRemaining: Int = 0,
        private var finalizeFailuresRemaining: Int = 0,
    ) : ImmersionRecorder {
        private val mutableState = MutableStateFlow(ImmersionRecorderSnapshot())
        override val state: StateFlow<ImmersionRecorderSnapshot> = mutableState
        val commands = mutableListOf<CaptureCommand>()
        val pauses = mutableListOf<PauseReason>()
        val resumes = mutableListOf<ResumeReason>()
        var startedSessionId: SessionId? = null
            private set
        var idleResumptions: Int = 0
            private set
        private var context: SessionContext? = null
        private var handle: SessionHandle? = null

        fun exposures(kind: SourceKind) = commands.filterIsInstance<CaptureCommand.Exposure>()
            .filter { it.source.sourceKind == kind }

        fun enterIdle() {
            mutableState.value = mutableState.value.copy(state = ImmersionSessionState.IDLE)
        }

        override suspend fun startSession(context: SessionContext): SessionStartResult {
            if (suppressStart || context.incognito) {
                return SessionStartResult.Suppressed(CaptureSuppressionReason.INCOGNITO)
            }
            val handle = SessionHandle(SessionId(UUID.randomUUID().toString()))
            this.context = context
            this.handle = handle
            startedSessionId = handle.sessionId
            mutableState.value = ImmersionRecorderSnapshot(
                sessionId = handle.sessionId,
                state = ImmersionSessionState.ACTIVE,
            )
            return SessionStartResult.Started(handle)
        }

        override fun record(command: CaptureCommand): RecordResult =
            handle?.let { record(it, command) }
                ?: RecordResult.Rejected(ImmersionSessionState.NOT_STARTED)

        override fun record(handle: SessionHandle, command: CaptureCommand): RecordResult {
            if (handle != this.handle) return RecordResult.Rejected(ImmersionSessionState.ACTIVE)
            if (recordFailuresRemaining > 0) {
                recordFailuresRemaining -= 1
                error("test recorder failure")
            }
            if (
                rejectOcrExposures &&
                command is CaptureCommand.Exposure &&
                command.source.sourceKind == SourceKind.MANGA_OCR_BLOCK
            ) {
                return RecordResult.Rejected(ImmersionSessionState.ACTIVE)
            }
            if (mutableState.value.state == ImmersionSessionState.IDLE) {
                idleResumptions += 1
                mutableState.value = mutableState.value.copy(state = ImmersionSessionState.ACTIVE)
            }
            commands += command
            if (command is CaptureCommand.Exposure) {
                persistedSourceIds += command.source.id
            }
            return RecordResult.Enqueued(1)
        }

        override suspend fun pause(reason: PauseReason) {
            pauses += reason
        }

        override suspend fun pause(handle: SessionHandle, reason: PauseReason) {
            if (handle == this.handle) pauses += reason
        }

        override suspend fun resume(reason: ResumeReason) {
            resumes += reason
        }

        override suspend fun resume(handle: SessionHandle, reason: ResumeReason) {
            if (handle == this.handle) resumes += reason
        }

        override suspend fun finalize(reason: FinalizeReason) {
            handle?.let { finalize(it, reason) }
        }

        override suspend fun finalize(handle: SessionHandle, reason: FinalizeReason): ImmersionSession? {
            if (handle != this.handle) return null
            if (finalizeFailuresRemaining > 0) {
                finalizeFailuresRemaining -= 1
                error("test recorder finalization failure")
            }
            val title = requireNotNull(context).title
            val exposures = commands.filterIsInstance<CaptureCommand.Exposure>()
            this.handle = null
            return completedSession(
                id = handle.sessionId,
                title = title,
                activeMillis = activeMillis,
                gross = exposures.sumOf { it.grossCharacters.value },
                unique = exposures.sumOf { it.uniqueSourceCharacters.value },
            )
        }

        override suspend fun setIncognito(enabled: Boolean) = Unit

        override suspend fun recoverAbandonedSessions(): Long = 0

        override suspend fun hasSeenSource(sourceUnitId: SourceUnitId): Boolean =
            sourceUnitId in persistedSourceIds
    }
}

private fun completedSession(
    id: SessionId,
    title: ImmersionTitle,
    activeMillis: Long,
    gross: Long,
    unique: Long,
): ImmersionSession = ImmersionSession(
    id = id,
    deviceId = "device",
    titleId = title.id,
    mediaKind = MediaKind.MANGA,
    languageTag = title.languageTag,
    profileId = title.profileId,
    startedAtEpochMillis = 0,
    endedAtEpochMillis = Instant.ofEpochMilli(1_000).toEpochMilli(),
    startZoneId = "UTC",
    startOffsetSeconds = 0,
    status = SessionStatus.COMPLETED,
    activeDuration = MillisecondDuration(activeMillis),
    elapsedDuration = MillisecondDuration(activeMillis),
    grossCharacters = NonNegativeCounter(gross),
    uniqueSourceCharacters = NonNegativeCounter(unique),
    netCharacters = NetCharacterProgress.ZERO,
    sourceUnitCount = NonNegativeCounter(1),
    lastSequence = 1,
    lastHeartbeatAtEpochMillis = 1_000,
    captureVersion = 1,
    schemaVersion = 1,
    legacyImport = false,
)
