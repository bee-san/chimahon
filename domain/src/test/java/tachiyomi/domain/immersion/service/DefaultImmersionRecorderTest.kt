package tachiyomi.domain.immersion.service

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.CharacterVolume
import tachiyomi.domain.immersion.model.EventId
import tachiyomi.domain.immersion.model.EventType
import tachiyomi.domain.immersion.model.ExposureEvent
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.ImmersionSessionStart
import tachiyomi.domain.immersion.model.ImmersionSourceUnit
import tachiyomi.domain.immersion.model.ImmersionTitle
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.MillisecondDuration
import tachiyomi.domain.immersion.model.NetCharacterProgress
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.PersistenceErrorCode
import tachiyomi.domain.immersion.model.PersistenceResult
import tachiyomi.domain.immersion.model.RecordedImmersionEvent
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SessionStatus
import tachiyomi.domain.immersion.model.SourceKind
import tachiyomi.domain.immersion.model.SourceUnitId
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.repository.ImmersionRecorderRepository
import java.time.ZoneId
import java.util.UUID
import kotlin.system.measureNanoTime

class DefaultImmersionRecorderTest {

    @Test
    fun `test adapter records a complete durable session without SQL knowledge`() = runTest {
        val fixture = recorderFixture()

        fixture.recorder.startSession(fixture.context).shouldBeInstanceOf<SessionStartResult.Started>()
        fixture.clock.advance(1_000)
        fixture.recorder.record(fixture.exposure()) shouldBe RecordResult.Enqueued(2)
        fixture.recorder.finalize(FinalizeReason.NORMAL)

        fixture.repository.starts.size shouldBe 1
        fixture.repository.events.map { it.sequence } shouldContainExactly listOf(1L, 2L, 3L, 4L)
        fixture.repository.events.map { it.type } shouldContainExactly listOf(
            EventType.SESSION_STARTED,
            EventType.HEARTBEAT,
            EventType.EXPOSURE,
            EventType.SESSION_FINALIZED,
        )
        fixture.rollupBatches.sum() shouldBe 4
        fixture.diagnostics.state.value.rollupLagEventCount shouldBe NonNegativeCounter(4)
        fixture.repository.session().let { session ->
            session.status shouldBe SessionStatus.COMPLETED
            session.activeDuration shouldBe MillisecondDuration(1_000)
            session.grossCharacters shouldBe NonNegativeCounter(20)
            session.uniqueSourceCharacters shouldBe NonNegativeCounter(18)
            session.netCharacters shouldBe NetCharacterProgress(12)
        }
    }

    @Test
    fun `pause background and resume exclude inactive time`() = runTest {
        val fixture = recorderFixture()
        fixture.recorder.startSession(fixture.context)

        fixture.clock.advance(1_000)
        fixture.recorder.pause(PauseReason.BACKGROUND)
        fixture.clock.advance(5_000)
        fixture.recorder.resume(ResumeReason.FOREGROUND)
        fixture.clock.advance(1_000)
        fixture.recorder.record(CaptureCommand.Activity())
        fixture.recorder.finalize(FinalizeReason.NORMAL)

        fixture.repository.session().activeDuration shouldBe MillisecondDuration(2_000)
        fixture.repository.events.map { it.type } shouldContainExactly listOf(
            EventType.SESSION_STARTED,
            EventType.HEARTBEAT,
            EventType.BACKGROUNDED,
            EventType.RESUMED,
            EventType.HEARTBEAT,
            EventType.SESSION_FINALIZED,
        )
    }

    @Test
    fun `idle timeout caps time and activity resumes with an explicit boundary`() = runTest {
        val fixture = recorderFixture(idleTimeoutMillis = 2_000)
        fixture.recorder.startSession(fixture.context)

        fixture.clock.advance(5_000)
        fixture.recorder.record(fixture.exposure())
        fixture.clock.advance(1_000)
        fixture.recorder.finalize(FinalizeReason.NORMAL)

        fixture.repository.session().activeDuration shouldBe MillisecondDuration(3_000)
        fixture.repository.events.map { it.type } shouldContainExactly listOf(
            EventType.SESSION_STARTED,
            EventType.HEARTBEAT,
            EventType.IDLE,
            EventType.RESUMED,
            EventType.EXPOSURE,
            EventType.HEARTBEAT,
            EventType.SESSION_FINALIZED,
        )
    }

    @Test
    fun `incognito blocks before insertion and finalizes an existing session before future commands`() = runTest {
        val fixture = recorderFixture()
        fixture.recorder.setIncognito(true)

        fixture.recorder.startSession(fixture.context) shouldBe
            SessionStartResult.Suppressed(CaptureSuppressionReason.INCOGNITO)
        fixture.repository.starts.size shouldBe 0
        fixture.repository.events.size shouldBe 0

        fixture.recorder.setIncognito(false)
        fixture.recorder.startSession(fixture.context)
        fixture.clock.advance(500)
        fixture.recorder.setIncognito(true)
        fixture.recorder.record(fixture.exposure()) shouldBe
            RecordResult.Suppressed(CaptureSuppressionReason.INCOGNITO)

        fixture.repository.starts.size shouldBe 1
        fixture.repository.session().status shouldBe SessionStatus.COMPLETED
        fixture.repository.events.last().type shouldBe EventType.SESSION_FINALIZED
    }

    @Test
    fun `title changes flush and finalize each prior identity`() = runTest {
        val fixture = recorderFixture()
        fixture.recorder.startSession(fixture.context)
        fixture.clock.advance(100)
        fixture.recorder.startSession(fixture.context.copy(title = title("Second")))
        fixture.clock.advance(100)
        fixture.recorder.startSession(fixture.context.copy(title = title("Third")))
        fixture.recorder.finalize(FinalizeReason.NORMAL)

        fixture.repository.starts.size shouldBe 3
        fixture.repository.sessions.values.map { it.status } shouldContainExactly listOf(
            SessionStatus.COMPLETED,
            SessionStatus.COMPLETED,
            SessionStatus.COMPLETED,
        )
    }

    @Test
    fun `database busy retries a stable event identity without duplication`() = runTest {
        val fixture = recorderFixture()
        fixture.recorder.startSession(fixture.context)
        fixture.repository.busyFailuresRemaining = 2
        fixture.clock.advance(100)
        fixture.recorder.record(fixture.exposure())
        fixture.recorder.finalize(FinalizeReason.NORMAL)

        fixture.repository.attemptedBatches.filter { batch ->
            batch.any { it.type == EventType.EXPOSURE }
        }.take(3).map { batch -> batch.map { it.id } }
            .distinct()
            .size shouldBe 1
        fixture.repository.events.map { it.id }.distinct().size shouldBe fixture.repository.events.size
        fixture.repository.session().status shouldBe SessionStatus.COMPLETED
    }

    @Test
    fun `queue saturation drops atomically and requests urgent flush without a sequence gap`() = runTest {
        val fixture = recorderFixture(queueCapacity = 2, batchSize = 2)
        fixture.recorder.startSession(fixture.context)
        fixture.repository.writeGate = CompletableDeferred()
        fixture.clock.advance(100)

        fixture.recorder.record(fixture.exposure()) shouldBe RecordResult.Enqueued(2)
        fixture.recorder.record(CaptureCommand.Activity(EventType.LOOKUP)) shouldBe RecordResult.QueueFull
        fixture.diagnostics.state.value.droppedCommandCount shouldBe NonNegativeCounter(1)

        fixture.repository.writeGate?.complete(Unit)
        fixture.recorder.finalize(FinalizeReason.NORMAL)
        fixture.repository.events.map { it.sequence } shouldContainExactly listOf(1L, 2L, 3L, 4L)
    }

    @Test
    fun `process recovery reports abandoned sessions through bounded diagnostics`() = runTest {
        val fixture = recorderFixture()
        fixture.repository.recoveredSessions = 3
        fixture.clock.advance(200_000)

        fixture.recorder.recoverAbandonedSessions() shouldBe 3
        fixture.diagnostics.state.value.abandonedRecoveryCount shouldBe NonNegativeCounter(3)
    }

    @Test
    fun `database unavailability ends capture and preserves an abandoned recovery boundary`() = runTest {
        val fixture = recorderFixture()
        fixture.recorder.startSession(fixture.context)
        fixture.repository.databaseUnavailable = true
        fixture.clock.advance(100)
        fixture.recorder.record(fixture.exposure())

        fixture.recorder.finalize(FinalizeReason.NORMAL)

        fixture.recorder.state.value.state shouldBe ImmersionSessionState.FAILED
        fixture.recorder.record(fixture.exposure()) shouldBe
            RecordResult.Rejected(ImmersionSessionState.FAILED)
        fixture.repository.session().status shouldBe SessionStatus.ABANDONED
        fixture.diagnostics.state.value.lastWriteError shouldBe
            ImmersionDiagnosticErrorCode.DATABASE_UNAVAILABLE
    }

    @Test
    fun `counter divergence schedules typed repair after finalization`() = runTest {
        val fixture = recorderFixture()
        fixture.recorder.startSession(fixture.context)
        fixture.clock.advance(100)
        fixture.recorder.record(fixture.exposure())
        fixture.repository.skewCountersOnRead = true

        fixture.recorder.finalize(FinalizeReason.NORMAL)

        fixture.repairs shouldContainExactly listOf(ImmersionRepairReason.SESSION_COUNTER_DIVERGENCE)
        fixture.diagnostics.state.value.lastRepairAtEpochMillis shouldBe fixture.clock.now().epochMillis
    }

    @Test
    fun `concurrent reader and overlay commands retain one sequence order`() = runTest {
        val fixture = recorderFixture(queueCapacity = 512, batchSize = 64)
        fixture.recorder.startSession(fixture.context)

        (0 until 80).map { ordinal ->
            async(Dispatchers.Default) {
                fixture.recorder.record(
                    CaptureCommand.Activity(
                        if (ordinal % 2 == 0) EventType.PROGRESS else EventType.LOOKUP,
                    ),
                )
            }
        }.awaitAll()
        fixture.recorder.finalize(FinalizeReason.NORMAL)

        fixture.repository.events.map { it.sequence } shouldContainExactly
            (1L..fixture.repository.events.size.toLong()).toList()
    }

    @Test
    fun `wall clock rollback preserves wall time while duration and order remain monotonic`() = runTest {
        val fixture = recorderFixture()
        fixture.recorder.startSession(fixture.context)
        fixture.clock.advance(monotonicMillis = 1_000, wallMillis = -500)
        fixture.recorder.record(fixture.exposure())
        fixture.recorder.finalize(FinalizeReason.NORMAL)

        fixture.repository.events.map { it.sequence } shouldContainExactly listOf(1L, 2L, 3L, 4L)
        fixture.repository.session().activeDuration shouldBe MillisecondDuration(1_000)
        fixture.repository.events[1].occurredAtEpochMillis shouldBe fixture.clock.initialEpochMillis - 500
    }

    @Test
    fun `timezone changes preserve the prior interval offset and apply the new offset to exposure`() = runTest {
        val fixture = recorderFixture()
        fixture.recorder.startSession(fixture.context)
        fixture.clock.advance(1_000)
        fixture.clock.changeZone(ZoneId.of("Asia/Tokyo"))

        fixture.recorder.record(fixture.exposure())
        fixture.recorder.finalize(FinalizeReason.NORMAL)

        val heartbeat = fixture.repository.events.first { it.type == EventType.HEARTBEAT }
        val exposure = fixture.repository.events.first { it.type == EventType.EXPOSURE }
        heartbeat.timezoneOffsetSeconds shouldBe 0
        exposure.timezoneOffsetSeconds shouldBe 9 * 60 * 60
    }

    @Test
    fun `record enqueue p95 stays below the two millisecond budget`() = runTest {
        val fixture = recorderFixture(queueCapacity = 4_096, batchSize = 128)
        fixture.recorder.startSession(fixture.context)

        repeat(100) {
            fixture.recorder.record(CaptureCommand.Activity(EventType.LOOKUP))
        }
        val samples = List(1_000) {
            measureNanoTime {
                fixture.recorder.record(CaptureCommand.Activity(EventType.LOOKUP))
            }
        }.sorted()
        val p95Nanos = samples[(samples.size * 0.95).toInt()]
        p95Nanos shouldBeLessThan 2_000_000
        fixture.recorder.finalize(FinalizeReason.NORMAL)
    }

    private fun TestScope.recorderFixture(
        idleTimeoutMillis: Long = 120_000,
        queueCapacity: Int = 256,
        batchSize: Int = 32,
    ): RecorderFixture {
        val repository = FakeImmersionRecorderRepository()
        val diagnostics = ImmersionStatsDiagnosticsStore()
        val clock = MutableRecorderClock()
        val repairs = mutableListOf<ImmersionRepairReason>()
        val rollupBatches = mutableListOf<Int>()
        val recorder = DefaultImmersionRecorder(
            repository = repository,
            deviceIdProvider = ImmersionDeviceIdProvider { DEVICE_ID },
            captureEnabled = { true },
            diagnostics = diagnostics,
            clock = clock,
            repairScheduler = ImmersionRepairScheduler { _, reason -> repairs += reason },
            rollupScheduler = ImmersionRollupScheduler { _, count -> rollupBatches += count },
            configuration = ImmersionRecorderConfiguration(
                queueCapacity = queueCapacity,
                batchSize = batchSize,
                flushIntervalMillis = 60_000,
                heartbeatIntervalMillis = 60_000,
                idleTimeoutMillis = idleTimeoutMillis,
                retryBackoffMillis = listOf(1, 2, 3),
            ),
            externalScope = backgroundScope,
        )
        return RecorderFixture(
            recorder = recorder,
            repository = repository,
            diagnostics = diagnostics,
            clock = clock,
            context = SessionContext(title("Fixture")),
            repairs = repairs,
            rollupBatches = rollupBatches,
        )
    }

    private data class RecorderFixture(
        val recorder: DefaultImmersionRecorder,
        val repository: FakeImmersionRecorderRepository,
        val diagnostics: ImmersionStatsDiagnosticsStore,
        val clock: MutableRecorderClock,
        val context: SessionContext,
        val repairs: List<ImmersionRepairReason>,
        val rollupBatches: List<Int>,
    ) {
        fun exposure() = CaptureCommand.Exposure(
            source = ImmersionSourceUnit(
                id = SourceUnitId(UUID.randomUUID().toString()),
                titleId = context.title.id,
                sourceKind = SourceKind.NOVEL_RANGE,
                canonicalLocator = "fixture:1",
                normalizedTextHash = "a".repeat(64),
                firstExposedAtEpochMillis = clock.now().epochMillis,
                lastExposedAtEpochMillis = clock.now().epochMillis,
                characterCounts = CharacterVolume(gross = NonNegativeCounter(20)),
            ),
            grossCharacters = NonNegativeCounter(20),
            uniqueSourceCharacters = NonNegativeCounter(18),
            netCharacters = NetCharacterProgress(12),
            exposurePolicy = "test",
        )
    }

    private class MutableRecorderClock(
        val initialEpochMillis: Long = 1_800_000_000_000,
    ) : ImmersionRecorderClock {
        private var epochMillis = initialEpochMillis
        private var monotonicNanos = 1_000_000_000L
        private var zoneId = ZoneId.of("Europe/London")

        override fun now() = RecorderTime(epochMillis, monotonicNanos, zoneId)

        fun advance(
            monotonicMillis: Long,
            wallMillis: Long = monotonicMillis,
        ) {
            monotonicNanos += monotonicMillis * 1_000_000
            epochMillis += wallMillis
        }

        fun changeZone(zoneId: ZoneId) {
            this.zoneId = zoneId
        }
    }

    private class FakeImmersionRecorderRepository : ImmersionRecorderRepository {
        val starts = mutableListOf<ImmersionSessionStart>()
        val events = mutableListOf<RecordedImmersionEvent>()
        val sessions = linkedMapOf<SessionId, ImmersionSession>()
        val attemptedBatches = mutableListOf<List<RecordedImmersionEvent>>()
        var busyFailuresRemaining = 0
        var databaseUnavailable = false
        var skewCountersOnRead = false
        var recoveredSessions = 0L
        var writeGate: CompletableDeferred<Unit>? = null

        override suspend fun upsertTitle(title: ImmersionTitle) = PersistenceResult.Applied

        override suspend fun createSession(session: ImmersionSessionStart): PersistenceResult {
            starts += session
            sessions[session.id] = session.toSession()
            return PersistenceResult.Applied
        }

        override suspend fun upsertSourceUnit(source: ImmersionSourceUnit) = PersistenceResult.Applied

        override suspend fun appendExposure(event: ExposureEvent): PersistenceResult =
            appendEventBatch(listOf(event)).single()

        override suspend fun appendExposureBatch(events: List<ExposureEvent>): List<PersistenceResult> =
            appendEventBatch(events)

        override suspend fun appendEventBatch(
            events: List<RecordedImmersionEvent>,
        ): List<PersistenceResult> {
            attemptedBatches += events.toList()
            writeGate?.await()
            if (busyFailuresRemaining-- > 0) {
                return List(events.size) { PersistenceResult.Failed(PersistenceErrorCode.DATABASE_BUSY) }
            }
            if (databaseUnavailable) {
                return List(events.size) {
                    PersistenceResult.Failed(PersistenceErrorCode.DATABASE_UNAVAILABLE)
                }
            }
            return synchronized(this) {
                events.map { event ->
                    if (this.events.any { it.id == event.id }) {
                        PersistenceResult.AlreadyApplied
                    } else {
                        this.events += event
                        apply(event)
                        PersistenceResult.Applied
                    }
                }
            }
        }

        override suspend fun finalizeSession(
            sessionId: SessionId,
            status: SessionStatus,
            endedAtEpochMillis: Long,
            elapsedDuration: MillisecondDuration,
        ): PersistenceResult {
            sessions[sessionId] = sessions.getValue(sessionId).copy(
                status = status,
                endedAtEpochMillis = endedAtEpochMillis,
                elapsedDuration = elapsedDuration,
                lastHeartbeatAtEpochMillis = endedAtEpochMillis,
            )
            return PersistenceResult.Applied
        }

        override suspend fun recoverAbandonedSessions(heartbeatCutoffEpochMillis: Long) = recoveredSessions

        override suspend fun getSession(sessionId: SessionId): ImmersionSession? {
            val value = sessions[sessionId] ?: return null
            return if (skewCountersOnRead) {
                value.copy(activeDuration = value.activeDuration + MillisecondDuration(1))
            } else {
                value
            }
        }

        fun session() = sessions.values.single()

        private fun apply(event: RecordedImmersionEvent) {
            val current = sessions.getValue(event.sessionId)
            val exposure = event as? ExposureEvent
            sessions[event.sessionId] = current.copy(
                activeDuration = current.activeDuration + event.activeDuration,
                grossCharacters = current.grossCharacters +
                    (exposure?.grossCharacters ?: NonNegativeCounter.ZERO),
                uniqueSourceCharacters = current.uniqueSourceCharacters +
                    (exposure?.uniqueSourceCharacters ?: NonNegativeCounter.ZERO),
                netCharacters = current.netCharacters +
                    (exposure?.netCharacters ?: NetCharacterProgress.ZERO),
                sourceUnitCount = NonNegativeCounter(
                    events.filterIsInstance<ExposureEvent>()
                        .filter { it.sessionId == event.sessionId }
                        .map { it.source.id }
                        .distinct()
                        .size
                        .toLong(),
                ),
                lastSequence = event.sequence,
                lastHeartbeatAtEpochMillis = maxOf(
                    current.lastHeartbeatAtEpochMillis ?: 0,
                    event.occurredAtEpochMillis,
                ),
            )
        }
    }

    private companion object {
        const val DEVICE_ID = "test-device"

        fun title(name: String) = ImmersionTitle(
            id = TitleId(UUID.nameUUIDFromBytes(name.encodeToByteArray()).toString()),
            mediaKind = MediaKind.NOVEL,
            sourceKey = "test",
            languageTag = LanguageTag("ja"),
            displayTitle = name,
            createdAtEpochMillis = 1_800_000_000_000,
            updatedAtEpochMillis = 1_800_000_000_000,
        )

        fun ImmersionSessionStart.toSession() = ImmersionSession(
            id = id,
            deviceId = deviceId,
            titleId = titleId,
            mediaKind = mediaKind,
            languageTag = languageTag,
            profileId = profileId,
            startedAtEpochMillis = startedAtEpochMillis,
            endedAtEpochMillis = null,
            startZoneId = startZoneId,
            startOffsetSeconds = startOffsetSeconds,
            status = SessionStatus.ACTIVE,
            activeDuration = MillisecondDuration(0),
            elapsedDuration = MillisecondDuration(0),
            grossCharacters = NonNegativeCounter.ZERO,
            uniqueSourceCharacters = NonNegativeCounter.ZERO,
            netCharacters = NetCharacterProgress.ZERO,
            sourceUnitCount = NonNegativeCounter.ZERO,
            lastSequence = 0,
            lastHeartbeatAtEpochMillis = startedAtEpochMillis,
            captureVersion = captureVersion,
            schemaVersion = schemaVersion,
            legacyImport = false,
        )
    }
}
