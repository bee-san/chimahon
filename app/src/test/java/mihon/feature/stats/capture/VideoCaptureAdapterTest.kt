// SPDX-License-Identifier: MIT

package mihon.feature.stats.capture

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.CapabilityState
import tachiyomi.domain.immersion.model.EventType
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.ImmersionTitle
import tachiyomi.domain.immersion.model.LanguageTag
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
import tachiyomi.domain.immersion.service.ImmersionDiagnosticErrorCode
import tachiyomi.domain.immersion.service.ImmersionRecorder
import tachiyomi.domain.immersion.service.ImmersionRecorderSnapshot
import tachiyomi.domain.immersion.service.ImmersionSessionState
import tachiyomi.domain.immersion.service.InteractionProvenance
import tachiyomi.domain.immersion.service.PauseReason
import tachiyomi.domain.immersion.service.RecordResult
import tachiyomi.domain.immersion.service.ResumeReason
import tachiyomi.domain.immersion.service.SessionContext
import tachiyomi.domain.immersion.service.SessionHandle
import tachiyomi.domain.immersion.service.SessionStartResult
import java.util.UUID

class VideoCaptureAdapterTest {

    @Test
    fun `only active sequential cues count and duplicate observer callbacks do not`() = runTest {
        val recorder = FakeRecorder()
        val adapter = playingAdapter(recorder)

        adapter.onSubtitleTrackChanged("1", null, "ja", null)
        adapter.onPlaybackPosition(1_000, 60_000)
        adapter.onSubtitleCueActive(cue(index = 0, text = "日本"))
        adapter.onSubtitleCueActive(cue(index = 0, text = "日本"))
        adapter.onSubtitleCueCleared(VideoSubtitleRole.PRIMARY)
        adapter.onPlaybackPosition(3_000, 60_000)
        adapter.onSubtitleCueActive(cue(index = 1, start = 3_000, end = 5_000, text = "語学"))
        adapter.finalize().await()

        recorder.exposures(SourceKind.SUBTITLE_CUE) shouldHaveSize 2
        recorder.exposures(SourceKind.SUBTITLE_CUE).sumOf { it.grossCharacters.value } shouldBe 4
    }

    @Test
    fun `pause buffer grace background and foreground control active recording`() = runTest {
        val recorder = FakeRecorder()
        val adapter = playingAdapter(recorder, graceMillis = 1_000)
        runCurrent()
        recorder.pauses.clear()
        recorder.resumes.clear()

        adapter.setPlaying(false)
        adapter.setPlaying(true)
        adapter.setBuffering(true)
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        recorder.pauses shouldBe listOf(PauseReason.USER)
        advanceTimeBy(1)
        runCurrent()
        recorder.pauses shouldBe listOf(PauseReason.USER, PauseReason.BUFFERING)
        adapter.setBuffering(false)
        adapter.setBackgrounded(true)
        adapter.setBackgrounded(false)
        adapter.finalize().await()

        recorder.resumes shouldBe listOf(
            ResumeReason.USER,
            ResumeReason.BUFFERING_ENDED,
            ResumeReason.FOREGROUND,
        )
        recorder.pauses.last() shouldBe PauseReason.BACKGROUND
    }

    @Test
    fun `background remains an explicit boundary while playback is already paused`() = runTest {
        val recorder = FakeRecorder()
        val adapter = playingAdapter(recorder)
        runCurrent()
        recorder.pauses.clear()
        recorder.resumes.clear()

        adapter.setPlaying(false)
        adapter.setBackgrounded(true)
        adapter.setBackgrounded(false)
        adapter.setPlaying(true)
        adapter.finalize().await()

        recorder.pauses shouldBe listOf(PauseReason.USER, PauseReason.BACKGROUND)
        recorder.resumes shouldBe listOf(ResumeReason.FOREGROUND, ResumeReason.USER)
    }

    @Test
    fun `seek back reentry adds gross replay while observer churn inside hysteresis does not`() = runTest {
        val recorder = FakeRecorder()
        val adapter = playingAdapter(recorder)
        val cue = cue(index = 4, start = 10_000, end = 12_000, text = "日本語")

        adapter.onPlaybackPosition(10_000, 60_000)
        adapter.onSubtitleCueActive(cue)
        adapter.onSubtitleCueCleared(VideoSubtitleRole.PRIMARY)
        adapter.onPlaybackPosition(10_500, 60_000)
        adapter.onSubtitleCueActive(cue)
        adapter.setSeeking(true)
        adapter.setSeeking(false)
        adapter.onPlaybackPosition(10_000, 60_000)
        adapter.onSubtitleCueActive(cue)
        adapter.finalize().await()

        recorder.exposures(SourceKind.SUBTITLE_CUE).map { it.grossCharacters.value } shouldBe listOf(3L, 3L)
        recorder.exposures(SourceKind.SUBTITLE_CUE).map { it.uniqueSourceCharacters.value } shouldBe listOf(3L, 0L)
        recorder.exposures(SourceKind.SUBTITLE_CUE).map(CaptureCommand.Exposure::replayOrdinal) shouldBe listOf(0, 1)
    }

    @Test
    fun `primary learning language contributes while secondary and other language remain provenance only`() = runTest {
        val recorder = FakeRecorder()
        val adapter = playingAdapter(recorder)

        adapter.onSubtitleTrackChanged("ja-track", "en-track", "ja-JP", "en")
        adapter.onPlaybackPosition(1_000, 60_000)
        adapter.onSubtitleCueActive(cue(index = 0, text = "日本", track = "ja-track"))
        adapter.onSubtitleCueActive(
            cue(
                index = 0,
                text = "Japan",
                track = "en-track",
                language = "en",
                role = VideoSubtitleRole.SECONDARY,
            ),
        )
        adapter.onSubtitleTrackChanged("en-track", null, "en", null)
        adapter.onSubtitleCueActive(cue(index = 1, text = "English", track = "en-track", language = "en"))
        adapter.finalize().await()

        val exposures = recorder.exposures(SourceKind.SUBTITLE_CUE)
        exposures shouldHaveSize 3
        exposures.map { it.grossCharacters.value } shouldBe listOf(2L, 0L, 0L)
        adapter.coverage.value.learningSubtitleCues shouldBe 1
        adapter.coverage.value.subtitleCapability shouldBe CapabilityState.PARTIAL
    }

    @Test
    fun `identical text at different cue timestamps keeps distinct source identity`() = runTest {
        val recorder = FakeRecorder()
        val adapter = playingAdapter(recorder)

        adapter.onSubtitleCueActive(cue(index = 1, start = 1_000, end = 2_000, text = "はい"))
        adapter.onSubtitleCueCleared(VideoSubtitleRole.PRIMARY)
        adapter.onPlaybackPosition(9_000, 60_000)
        adapter.onSubtitleCueActive(cue(index = 9, start = 9_000, end = 10_000, text = "はい"))
        adapter.finalize().await()

        recorder.exposures(SourceKind.SUBTITLE_CUE).map { it.source.id }.distinct() shouldHaveSize 2
    }

    @Test
    fun `stable OCR across adjacent frames deduplicates and changed text creates a source`() = runTest {
        val recorder = FakeRecorder()
        val adapter = playingAdapter(recorder)

        adapter.onVideoOcrVisible(frame(12_000, "frame-a", "看板"))
        adapter.onVideoOcrVisible(frame(13_500, "frame-b", "看板"))
        adapter.onVideoOcrHidden()
        adapter.onVideoOcrVisible(frame(14_000, "frame-c", "入口"))
        adapter.finalize().await()

        val exposures = recorder.exposures(SourceKind.VIDEO_OCR_REGION)
        exposures shouldHaveSize 2
        exposures.map { it.source.id }.distinct() shouldHaveSize 2
        adapter.coverage.value shouldBe VideoCoverageSnapshot(
            activeSubtitleCues = 0,
            learningSubtitleCues = 0,
            ocrFrames = 3,
            ocrRegions = 2,
            subtitleCapability = CapabilityState.UNAVAILABLE,
            ocrCapability = CapabilityState.AVAILABLE,
        )
    }

    @Test
    fun `subtitle lookup snapshots retain the selected cue when playback advances`() = runTest {
        val recorder = FakeRecorder()
        val adapter = playingAdapter(recorder)
        val first = cue(index = 1, start = 1_000, end = 2_000, text = "最初")
        val second = cue(index = 2, start = 3_000, end = 4_000, text = "次")

        adapter.onPlaybackPosition(1_000, 60_000)
        adapter.onSubtitleCueActive(first)
        runCurrent()
        val firstProvenance = adapter.snapshotSubtitleLookupProvenance(first)

        adapter.onSubtitleCueCleared(VideoSubtitleRole.PRIMARY)
        adapter.onPlaybackPosition(3_000, 60_000)
        adapter.onSubtitleCueActive(second)
        runCurrent()
        val secondProvenance = adapter.snapshotSubtitleLookupProvenance(second)
        val firstProvenanceAfterAdvance = adapter.snapshotSubtitleLookupProvenance(first)
        adapter.finalize().await()

        firstProvenance?.sessionId shouldBe secondProvenance?.sessionId
        firstProvenance?.sourceUnitId shouldBe recorder.exposures(SourceKind.SUBTITLE_CUE)[0].source.id
        secondProvenance?.sourceUnitId shouldBe recorder.exposures(SourceKind.SUBTITLE_CUE)[1].source.id
        firstProvenanceAfterAdvance shouldBe firstProvenance
        (firstProvenance?.sourceUnitId == secondProvenance?.sourceUnitId) shouldBe false
        adapter.snapshotSubtitleLookupProvenance(first) shouldBe null
    }

    @Test
    fun `OCR lookup snapshots map each visible frame region to its stable source`() = runTest {
        val recorder = FakeRecorder()
        val adapter = playingAdapter(recorder)
        val first = frame(12_000, "frame-a", "看板")
        val adjacent = frame(13_500, "frame-b", "看板")
        val changed = frame(14_000, "frame-c", "入口")

        adapter.onVideoOcrVisible(first)
        runCurrent()
        val firstProvenance = adapter.snapshotOcrLookupProvenance(first, first.regions.single())
        adapter.onVideoOcrVisible(adjacent)
        runCurrent()
        val adjacentProvenance = adapter.snapshotOcrLookupProvenance(adjacent, adjacent.regions.single())
        adapter.onVideoOcrHidden()
        adapter.onVideoOcrVisible(changed)
        runCurrent()
        val changedProvenance = adapter.snapshotOcrLookupProvenance(changed, changed.regions.single())
        adapter.finalize().await()

        firstProvenance?.sessionId shouldBe adjacentProvenance?.sessionId
        firstProvenance?.sourceUnitId shouldBe adjacentProvenance?.sourceUnitId
        changedProvenance?.sourceUnitId shouldBe recorder.exposures(SourceKind.VIDEO_OCR_REGION)[1].source.id
        (firstProvenance?.sourceUnitId == changedProvenance?.sourceUnitId) shouldBe false
        adapter.snapshotOcrLookupProvenance(first, first.regions.single()) shouldBe null
    }

    @Test
    fun `OCR lookup snapshot selects the exact region and rejects an unknown region`() = runTest {
        val recorder = FakeRecorder()
        val adapter = playingAdapter(recorder)
        val frame = VideoOcrFrameCapture(
            timestampMillis = 20_000,
            frameIdentity = "frame-two-regions",
            regions = listOf(
                ocrRegion(id = "left", text = "左"),
                ocrRegion(id = "right", text = "右"),
            ),
        )

        adapter.onVideoOcrVisible(frame)
        runCurrent()
        val left = adapter.snapshotOcrLookupProvenance(frame, frame.regions[0])
        val right = adapter.snapshotOcrLookupProvenance(frame, frame.regions[1])
        val unknown = adapter.snapshotOcrLookupProvenance(
            frame,
            frame.regions[0].copy(regionId = "unknown"),
        )
        adapter.finalize().await()

        left?.sourceUnitId shouldBe recorder.exposures(SourceKind.VIDEO_OCR_REGION)[0].source.id
        right?.sourceUnitId shouldBe recorder.exposures(SourceKind.VIDEO_OCR_REGION)[1].source.id
        (left?.sourceUnitId == right?.sourceUnitId) shouldBe false
        unknown shouldBe InteractionProvenance(
            sessionId = requireNotNull(left).sessionId,
            sourceUnitId = null,
        )
    }

    @Test
    fun `suppressed video capture never offers lookup provenance`() = runTest {
        val adapter = playingAdapter(FakeRecorder(suppressStart = true), incognito = true)
        val subtitle = cue()
        val ocr = frame(1_000, "frame", "文字")

        adapter.onSubtitleCueActive(subtitle)
        adapter.onVideoOcrVisible(ocr)
        runCurrent()
        adapter.finalize().await()

        adapter.snapshotSubtitleLookupProvenance(subtitle) shouldBe null
        adapter.snapshotOcrLookupProvenance(ocr, ocr.regions.single()) shouldBe null
    }

    @Test
    fun `immediate subtitle and OCR taps keep session provenance until sources are published`() = runTest {
        val recorder = FakeRecorder()
        val adapter = playingAdapter(recorder)
        runCurrent()
        val subtitle = cue(text = "即時")
        val ocr = frame(1_000, "immediate-frame", "文字")

        adapter.onSubtitleCueActive(subtitle)
        adapter.onVideoOcrVisible(ocr)

        adapter.snapshotSubtitleLookupProvenance(subtitle) shouldBe
            InteractionProvenance(
                sessionId = requireNotNull(recorder.activeSessionId),
                sourceUnitId = null,
            )
        adapter.snapshotOcrLookupProvenance(ocr, ocr.regions.single()) shouldBe
            InteractionProvenance(
                sessionId = requireNotNull(recorder.activeSessionId),
                sourceUnitId = null,
            )
        runCurrent()
        adapter.snapshotSubtitleLookupProvenance(subtitle)?.sourceUnitId shouldBe
            recorder.exposures(SourceKind.SUBTITLE_CUE).single().source.id
        adapter.snapshotOcrLookupProvenance(ocr, ocr.regions.single())?.sourceUnitId shouldBe
            recorder.exposures(SourceKind.VIDEO_OCR_REGION).single().source.id
        adapter.finalize().await()
    }

    @Test
    fun `queued commands after finalization cannot start a second session`() = runTest {
        val recorder = FakeRecorder()
        val adapter = playingAdapter(recorder)
        val firstFinalization = adapter.finalize()
        val repeatedFinalization = adapter.finalize()

        adapter.setPlaying(true)
        adapter.onSubtitleCueActive(cue())
        adapter.onVideoOcrVisible(frame(1_000, "late-frame", "遅延"))

        firstFinalization.await()
        repeatedFinalization.await()
        runCurrent()

        recorder.sessionStarts shouldBe 1
        recorder.sessionFinalizations shouldBe 1
        recorder.exposures(SourceKind.SUBTITLE_CUE) shouldHaveSize 0
        recorder.exposures(SourceKind.VIDEO_OCR_REGION) shouldHaveSize 0
        adapter.snapshotSubtitleLookupProvenance(cue()) shouldBe null
    }

    @Test
    fun `progress completion and media context retain episode timestamps without media bytes`() = runTest {
        val recorder = FakeRecorder()
        val adapter = playingAdapter(recorder)

        adapter.onPlaybackPosition(30_000, 100_000)
        adapter.onSubtitleCueActive(cue(index = 3, start = 30_000, end = 32_000, text = "字幕"))
        adapter.onPlaybackPosition(96_000, 100_000)
        adapter.onTitleCompleted()
        adapter.finalize().await()

        adapter.progress.value shouldBe VideoProgressSnapshot(
            episodeId = 10,
            positionMillis = 96_000,
            durationMillis = 100_000,
            estimatedRemainingMillis = 4_000,
            completed = true,
        )
        recorder.activities().count { it.eventType == EventType.UNIT_COMPLETED } shouldBe 1
        recorder.activities().count { it.eventType == EventType.TITLE_COMPLETED } shouldBe 1
        adapter.mediaContext.value?.let {
            it.episodeId shouldBe 10
            it.positionMillis shouldBe 96_000
            it.sourceUnitId shouldBe null
        }
    }

    @Test
    fun `track and subtitle mode changes are explicit events`() = runTest {
        val recorder = FakeRecorder()
        val adapter = playingAdapter(recorder)

        adapter.onSubtitleTrackChanged("1", "2", "ja", "en")
        adapter.onSubtitleTrackChanged("1", "2", "ja", "en")
        adapter.setSubtitleModeVisible(false)
        adapter.setSubtitleModeVisible(false)
        adapter.setSubtitleModeVisible(true)
        adapter.finalize().await()

        recorder.activities().map(CaptureCommand.Activity::eventType).filter {
            it == EventType.SUBTITLE_TRACK_CHANGED || it == EventType.SUBTITLE_MODE_CHANGED
        } shouldBe listOf(
            EventType.SUBTITLE_TRACK_CHANGED,
            EventType.SUBTITLE_MODE_CHANGED,
            EventType.SUBTITLE_MODE_CHANGED,
        )
    }

    @Test
    fun `external playback is partial and incognito or process suppression records no exposure`() = runTest {
        val externalRecorder = FakeRecorder()
        val external = adapter(externalRecorder)
        external.markExternalPlaybackUnsupported()
        external.setPlaying(true)
        external.onSubtitleCueActive(cue())
        external.finalize().await()
        external.coverage.value.externalPlayback shouldBe true
        externalRecorder.exposures(SourceKind.SUBTITLE_CUE) shouldHaveSize 0

        val suppressedRecorder = FakeRecorder(suppressStart = true)
        val suppressed = playingAdapter(suppressedRecorder, incognito = true)
        suppressed.onSubtitleCueActive(cue())
        suppressed.onVideoOcrVisible(frame(1_000, "frame", "文字"))
        suppressed.finalize().await()
        suppressedRecorder.commands shouldBe emptyList()
    }

    private fun TestScope.playingAdapter(
        recorder: FakeRecorder,
        graceMillis: Long = 1_000,
        incognito: Boolean = false,
    ): VideoCaptureAdapter = adapter(recorder, graceMillis, incognito).also {
        it.onPlayableMedia()
        it.setPlaying(true)
    }

    private fun TestScope.adapter(
        recorder: FakeRecorder,
        graceMillis: Long = 1_000,
        incognito: Boolean = false,
    ) = VideoCaptureAdapter(
        captureTitle = VideoCaptureTitle(
            animeId = 1,
            sourceId = 2,
            displayTitle = "Test anime",
            profileId = "ja",
            languageTag = LanguageTag.from("ja"),
            createdAtEpochMillis = 0,
        ),
        episode = VideoEpisodeCapture(
            episodeId = 10,
            mediaId = "episode-10",
            displayName = "Episode 10",
            durationMillis = 100_000,
        ),
        recorder = recorder,
        rawTextRetention = { RawTextRetention.UNTIL_DELETED },
        bufferingGraceMillis = graceMillis,
        incognito = incognito,
        clock = { 1_000 },
        workerScope = this,
    )

    private fun cue(
        index: Int = 0,
        start: Long = 1_000,
        end: Long = 3_000,
        text: String = "日本",
        track: String = "1",
        language: String = "ja",
        role: VideoSubtitleRole = VideoSubtitleRole.PRIMARY,
    ) = VideoSubtitleCueCapture(
        trackId = track,
        trackLanguage = language,
        role = role,
        cueIndex = index,
        startMillis = start,
        endMillis = end,
        text = text,
    )

    private fun frame(
        timestamp: Long,
        identity: String,
        text: String,
    ) = VideoOcrFrameCapture(
        timestampMillis = timestamp,
        frameIdentity = identity,
        regions = listOf(
            ocrRegion(id = "sign", text = text),
        ),
    )

    private fun ocrRegion(
        id: String,
        text: String,
    ) = VideoOcrRegionCapture(
        regionId = id,
        text = text,
        engineId = "lens",
        engineVersion = 1,
        languageTag = "ja",
    )

    private class FakeRecorder(
        private val suppressStart: Boolean = false,
    ) : ImmersionRecorder {
        private val mutableState = MutableStateFlow(ImmersionRecorderSnapshot())
        override val state: StateFlow<ImmersionRecorderSnapshot> = mutableState
        val commands = mutableListOf<CaptureCommand>()
        val pauses = mutableListOf<PauseReason>()
        val resumes = mutableListOf<ResumeReason>()
        var sessionStarts = 0
            private set
        var sessionFinalizations = 0
            private set
        private var context: SessionContext? = null
        private var handle: SessionHandle? = null
        val activeSessionId: SessionId?
            get() = handle?.sessionId

        fun exposures(kind: SourceKind) = commands.filterIsInstance<CaptureCommand.Exposure>()
            .filter { it.source.sourceKind == kind }

        fun activities() = commands.filterIsInstance<CaptureCommand.Activity>()

        override suspend fun startSession(context: SessionContext): SessionStartResult {
            if (suppressStart || context.incognito) {
                return SessionStartResult.Suppressed(CaptureSuppressionReason.INCOGNITO)
            }
            sessionStarts += 1
            this.context = context
            return SessionHandle(SessionId(UUID.randomUUID().toString())).also {
                handle = it
            }.let(SessionStartResult::Started)
        }

        override fun record(command: CaptureCommand): RecordResult =
            handle?.let { record(it, command) } ?: RecordResult.Rejected(ImmersionSessionState.NOT_STARTED)

        override fun record(
            handle: SessionHandle,
            command: CaptureCommand,
        ): RecordResult {
            commands += command
            return RecordResult.Enqueued(commands.size)
        }

        override suspend fun pause(reason: PauseReason) {
            pauses += reason
        }

        override suspend fun pause(
            handle: SessionHandle,
            reason: PauseReason,
        ) {
            pauses += reason
        }

        override suspend fun resume(reason: ResumeReason) {
            resumes += reason
        }

        override suspend fun resume(
            handle: SessionHandle,
            reason: ResumeReason,
        ) {
            resumes += reason
        }

        override suspend fun finalize(reason: FinalizeReason) {
            handle?.let { finalize(it, reason) }
        }

        override suspend fun finalize(
            handle: SessionHandle,
            reason: FinalizeReason,
        ): ImmersionSession {
            sessionFinalizations += 1
            val title = requireNotNull(context).title
            val exposures = commands.filterIsInstance<CaptureCommand.Exposure>()
            return completedSession(
                handle = handle,
                title = title,
                gross = exposures.sumOf { it.grossCharacters.value },
                unique = exposures.sumOf { it.uniqueSourceCharacters.value },
            )
        }

        override suspend fun setIncognito(enabled: Boolean) = Unit

        override suspend fun recoverAbandonedSessions(): Long = 0

        override suspend fun hasSeenSource(sourceUnitId: SourceUnitId): Boolean = false
    }
}

private fun completedSession(
    handle: SessionHandle,
    title: ImmersionTitle,
    gross: Long,
    unique: Long,
) = ImmersionSession(
    id = handle.sessionId,
    deviceId = "test",
    titleId = title.id,
    mediaKind = MediaKind.VIDEO,
    languageTag = title.languageTag,
    profileId = title.profileId,
    startedAtEpochMillis = 0,
    endedAtEpochMillis = 1_000,
    startZoneId = "UTC",
    startOffsetSeconds = 0,
    status = SessionStatus.COMPLETED,
    activeDuration = MillisecondDuration(1_000),
    elapsedDuration = MillisecondDuration(1_000),
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
