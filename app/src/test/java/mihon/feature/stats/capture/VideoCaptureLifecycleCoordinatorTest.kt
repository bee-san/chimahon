package mihon.feature.stats.capture

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.RawTextRetention
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SourceKind
import tachiyomi.domain.immersion.model.SourceUnitId
import tachiyomi.domain.immersion.service.CaptureCommand
import tachiyomi.domain.immersion.service.FinalizeReason
import tachiyomi.domain.immersion.service.ImmersionRecorder
import tachiyomi.domain.immersion.service.ImmersionRecorderSnapshot
import tachiyomi.domain.immersion.service.ImmersionSessionState
import tachiyomi.domain.immersion.service.PauseReason
import tachiyomi.domain.immersion.service.RecordResult
import tachiyomi.domain.immersion.service.ResumeReason
import tachiyomi.domain.immersion.service.SessionContext
import tachiyomi.domain.immersion.service.SessionHandle
import tachiyomi.domain.immersion.service.SessionStartResult
import java.util.UUID

class VideoCaptureLifecycleCoordinatorTest {

    @Test
    fun `episode auto advance finalizes old capture before starting isolated next capture`() = runTest {
        val recorder = StrictRecorder()
        val lifecycle = VideoCaptureLifecycleCoordinator()
        val sharedCue = cue(index = 4, text = "次回")

        val previous = requireNotNull(
            lifecycle.switchEpisode(10) { adapter(episodeId = 10, recorder = recorder) },
        )
        previous.onPlayableMedia()
        previous.setPlaying(true)
        previous.onSubtitleCueActive(sharedCue)
        runCurrent()

        val next = requireNotNull(
            lifecycle.switchEpisode(11) {
                recorder.activeSessionId shouldBe null
                adapter(episodeId = 11, recorder = recorder)
            },
        )

        previous.onPlayableMedia()
        previous.setPlaying(true)
        previous.onSubtitleCueActive(cue(index = 99, text = "late"))
        next.onPlayableMedia()
        next.setPlaying(true)
        next.onSubtitleCueActive(sharedCue)
        runCurrent()
        lifecycle.finalizeCurrent(FinalizeReason.NORMAL)?.await()
        runCurrent()

        recorder.timeline.map(TimelineEntry::kind) shouldBe listOf(
            TimelineKind.STARTED,
            TimelineKind.FINALIZED,
            TimelineKind.STARTED,
            TimelineKind.FINALIZED,
        )
        recorder.finalizations.map(Finalization::reason) shouldBe listOf(
            FinalizeReason.TITLE_CHANGED,
            FinalizeReason.NORMAL,
        )

        val sessionIds = recorder.timeline
            .filter { it.kind == TimelineKind.STARTED }
            .map(TimelineEntry::sessionId)
        sessionIds shouldHaveSize 2
        val previousExposures = recorder.exposures(sessionIds[0])
        val nextExposures = recorder.exposures(sessionIds[1])
        previousExposures shouldHaveSize 1
        nextExposures shouldHaveSize 1
        previousExposures.single().source.episodeOrMediaId shouldBe "episode:10"
        nextExposures.single().source.episodeOrMediaId shouldBe "episode:11"
        (previousExposures.single().source.id == nextExposures.single().source.id) shouldBe false
        recorder.rejectedCommands shouldBe 0
    }

    @Test
    fun `stale episode cannot install collectors after a newer switch`() = runTest {
        val recorder = StrictRecorder()
        val lifecycle = VideoCaptureLifecycleCoordinator()
        val previous = requireNotNull(
            lifecycle.switchEpisode(10) { adapter(episodeId = 10, recorder = recorder) },
        )
        val next = requireNotNull(
            lifecycle.switchEpisode(11) { adapter(episodeId = 11, recorder = recorder) },
        )
        var installedEpisode: Long? = null

        lifecycle.withCurrentAdapter(previous) { installedEpisode = 10 } shouldBe false
        lifecycle.withCurrentAdapter(next) { installedEpisode = 11 } shouldBe true

        installedEpisode shouldBe 11
        lifecycle.finalizeCurrent(FinalizeReason.NORMAL)?.await()
    }

    private fun TestScope.adapter(
        episodeId: Long,
        recorder: StrictRecorder,
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
            episodeId = episodeId,
            mediaId = "episode:$episodeId",
            displayName = "Episode $episodeId",
            durationMillis = 60_000,
        ),
        recorder = recorder,
        rawTextRetention = { RawTextRetention.UNTIL_DELETED },
        bufferingGraceMillis = 1_000,
        incognito = false,
        clock = { 1_000 },
        workerScope = this,
    )

    private fun cue(
        index: Int,
        text: String,
    ) = VideoSubtitleCueCapture(
        trackId = "primary",
        trackLanguage = "ja",
        role = VideoSubtitleRole.PRIMARY,
        cueIndex = index,
        startMillis = 1_000,
        endMillis = 3_000,
        text = text,
    )

    private class StrictRecorder : ImmersionRecorder {
        private val mutableState = MutableStateFlow(ImmersionRecorderSnapshot())
        override val state: StateFlow<ImmersionRecorderSnapshot> = mutableState
        val timeline = mutableListOf<TimelineEntry>()
        val finalizations = mutableListOf<Finalization>()
        private val recorded = mutableListOf<RecordedCommand>()
        private var activeHandle: SessionHandle? = null
        var rejectedCommands = 0
            private set
        val activeSessionId: SessionId?
            get() = activeHandle?.sessionId

        fun exposures(sessionId: SessionId): List<CaptureCommand.Exposure> =
            recorded
                .filter { it.sessionId == sessionId }
                .map(RecordedCommand::command)
                .filterIsInstance<CaptureCommand.Exposure>()
                .filter { it.source.sourceKind == SourceKind.SUBTITLE_CUE }

        override suspend fun startSession(context: SessionContext): SessionStartResult {
            check(activeHandle == null) { "The previous episode must finalize before the next starts" }
            val handle = SessionHandle(SessionId(UUID.randomUUID().toString()))
            activeHandle = handle
            timeline += TimelineEntry(TimelineKind.STARTED, handle.sessionId)
            mutableState.value = ImmersionRecorderSnapshot(
                sessionId = handle.sessionId,
                state = ImmersionSessionState.ACTIVE,
            )
            return SessionStartResult.Started(handle)
        }

        override fun record(command: CaptureCommand): RecordResult =
            activeHandle?.let { record(it, command) }
                ?: RecordResult.Rejected(ImmersionSessionState.NOT_STARTED)

        override fun record(
            handle: SessionHandle,
            command: CaptureCommand,
        ): RecordResult {
            if (handle != activeHandle) {
                rejectedCommands += 1
                return RecordResult.Rejected(mutableState.value.state)
            }
            recorded += RecordedCommand(handle.sessionId, command)
            return RecordResult.Enqueued(recorded.size)
        }

        override suspend fun pause(reason: PauseReason) = Unit

        override suspend fun pause(
            handle: SessionHandle,
            reason: PauseReason,
        ) = Unit

        override suspend fun resume(reason: ResumeReason) = Unit

        override suspend fun resume(
            handle: SessionHandle,
            reason: ResumeReason,
        ) = Unit

        override suspend fun finalize(reason: FinalizeReason) {
            activeHandle?.let { finalize(it, reason) }
        }

        override suspend fun finalize(
            handle: SessionHandle,
            reason: FinalizeReason,
        ): ImmersionSession? {
            if (handle != activeHandle) return null
            finalizations += Finalization(handle.sessionId, reason)
            timeline += TimelineEntry(TimelineKind.FINALIZED, handle.sessionId)
            activeHandle = null
            mutableState.value = ImmersionRecorderSnapshot(
                sessionId = handle.sessionId,
                state = ImmersionSessionState.FINALIZED,
            )
            return null
        }

        override suspend fun setIncognito(enabled: Boolean) = Unit

        override suspend fun recoverAbandonedSessions(): Long = 0

        override suspend fun hasSeenSource(sourceUnitId: SourceUnitId): Boolean = false
    }

    private data class RecordedCommand(
        val sessionId: SessionId,
        val command: CaptureCommand,
    )

    private data class Finalization(
        val sessionId: SessionId,
        val reason: FinalizeReason,
    )

    private data class TimelineEntry(
        val kind: TimelineKind,
        val sessionId: SessionId,
    )

    private enum class TimelineKind {
        STARTED,
        FINALIZED,
    }
}
