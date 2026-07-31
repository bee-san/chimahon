package mihon.feature.stats.recorder

import eu.kanade.domain.base.BasePreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.immersion.model.AnkiOperationStatus
import tachiyomi.domain.immersion.model.AnkiOperationType
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.SourceUnitId
import tachiyomi.domain.immersion.service.AnkiOperationRecorder
import tachiyomi.domain.immersion.service.AnkiOperationToken
import tachiyomi.domain.immersion.service.CaptureCommand
import tachiyomi.domain.immersion.service.FinalizeReason
import tachiyomi.domain.immersion.service.ImmersionRecorder
import tachiyomi.domain.immersion.service.ImmersionRecorderSnapshot
import tachiyomi.domain.immersion.service.ImmersionStatsPreferences
import tachiyomi.domain.immersion.service.InteractionProvenance
import tachiyomi.domain.immersion.service.PauseReason
import tachiyomi.domain.immersion.service.RecordResult
import tachiyomi.domain.immersion.service.ResumeReason
import tachiyomi.domain.immersion.service.SessionContext
import tachiyomi.domain.immersion.service.SessionHandle
import tachiyomi.domain.immersion.service.SessionStartResult

class ImmersionRecorderLifecycleCoordinatorTest {

    @Test
    fun `lifting either privacy barrier reattempts pending Anki repair`() = runTest {
        val incognito = MutableStateFlow(true)
        val captureEnabled = MutableStateFlow(false)
        val basePreferences = mockk<BasePreferences>()
        val statsPreferences = mockk<ImmersionStatsPreferences>()
        every { basePreferences.incognitoMode() } returns preference(incognito)
        every { statsPreferences.captureEnabled() } returns preference(captureEnabled)
        val recorder = FakeRecorder()
        val ankiRecorder = FakeAnkiOperationRecorder()
        val coordinator = ImmersionRecorderLifecycleCoordinator(
            recorder = recorder,
            basePreferences = basePreferences,
            statsPreferences = statsPreferences,
            ankiOperationRecorder = ankiRecorder,
        )

        coordinator.initialize(backgroundScope)
        runCurrent()
        ankiRecorder.retryAttempts shouldBe 1

        incognito.value = false
        runCurrent()
        ankiRecorder.retryAttempts shouldBe 2

        captureEnabled.value = true
        runCurrent()
        ankiRecorder.retryAttempts shouldBe 3
    }

    @Test
    fun `successful pending Anki repair schedules rollup repair`() = runTest {
        val incognito = MutableStateFlow(true)
        val captureEnabled = MutableStateFlow(true)
        val basePreferences = mockk<BasePreferences>()
        val statsPreferences = mockk<ImmersionStatsPreferences>()
        every { basePreferences.incognitoMode() } returns preference(incognito)
        every { statsPreferences.captureEnabled() } returns preference(captureEnabled)
        val ankiRecorder = FakeAnkiOperationRecorder()
        var rollupStarts = 0
        val coordinator = ImmersionRecorderLifecycleCoordinator(
            recorder = FakeRecorder(),
            basePreferences = basePreferences,
            statsPreferences = statsPreferences,
            ankiOperationRecorder = ankiRecorder,
            onAnkiRepairsPersisted = { rollupStarts++ },
        )

        coordinator.initialize(backgroundScope)
        runCurrent()
        rollupStarts shouldBe 0

        ankiRecorder.retryResult = 1
        incognito.value = false
        runCurrent()

        rollupStarts shouldBe 1
    }

    private fun preference(state: MutableStateFlow<Boolean>): Preference<Boolean> =
        mockk {
            every { get() } answers { state.value }
            every { changes() } returns state
        }

    private class FakeAnkiOperationRecorder : AnkiOperationRecorder {
        var retryAttempts = 0
        var retryResult = 0

        override fun begin(
            expression: String,
            reading: String?,
            provenance: InteractionProvenance?,
        ): AnkiOperationToken = error("Not used")

        override fun complete(
            token: AnkiOperationToken,
            operationType: AnkiOperationType,
            status: AnkiOperationStatus,
            noteId: Long?,
            errorCode: String?,
        ): RecordResult = error("Not used")

        override fun abandon(token: AnkiOperationToken): Boolean = false

        override suspend fun retryPending(): Int {
            retryAttempts++
            return retryResult
        }
    }

    private class FakeRecorder : ImmersionRecorder {
        override val state: StateFlow<ImmersionRecorderSnapshot> =
            MutableStateFlow(ImmersionRecorderSnapshot())

        override suspend fun startSession(context: SessionContext): SessionStartResult =
            error("Not used")

        override fun record(command: CaptureCommand): RecordResult = error("Not used")

        override fun record(
            handle: SessionHandle,
            command: CaptureCommand,
        ): RecordResult = error("Not used")

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

        override suspend fun finalize(reason: FinalizeReason) = Unit

        override suspend fun finalize(
            handle: SessionHandle,
            reason: FinalizeReason,
        ): ImmersionSession? = null

        override suspend fun setIncognito(enabled: Boolean) = Unit

        override suspend fun recoverAbandonedSessions(): Long = 0

        override suspend fun hasSeenSource(sourceUnitId: SourceUnitId): Boolean = false
    }
}
