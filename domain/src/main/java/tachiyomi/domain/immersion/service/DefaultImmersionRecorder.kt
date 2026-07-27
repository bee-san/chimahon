// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.immersion.model.AnkiOperationEvent
import tachiyomi.domain.immersion.model.EventId
import tachiyomi.domain.immersion.model.EventType
import tachiyomi.domain.immersion.model.ExposureEvent
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.ImmersionSessionStart
import tachiyomi.domain.immersion.model.LookupEvent
import tachiyomi.domain.immersion.model.MillisecondDuration
import tachiyomi.domain.immersion.model.NetCharacterProgress
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.PersistenceErrorCode
import tachiyomi.domain.immersion.model.PersistenceResult
import tachiyomi.domain.immersion.model.RecordedImmersionEvent
import tachiyomi.domain.immersion.model.SessionEvent
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SessionStatus
import tachiyomi.domain.immersion.model.SourceUnitId
import tachiyomi.domain.immersion.repository.ImmersionRecorderRepository
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

data class ImmersionRecorderConfiguration(
    val queueCapacity: Int = 256,
    val batchSize: Int = 32,
    val flushIntervalMillis: Long = 1_000,
    val heartbeatIntervalMillis: Long = 15_000,
    val idleTimeoutMillis: Long = 120_000,
    val abandonedSessionTimeoutMillis: Long = 90_000,
    val startWriteTimeoutMillis: Long = 2_000,
    val retryBackoffMillis: List<Long> = listOf(10, 25, 50),
) {
    init {
        require(queueCapacity > 0) { "Queue capacity must be positive" }
        require(batchSize in 1..queueCapacity) { "Batch size must fit inside the queue" }
        require(flushIntervalMillis > 0) { "Flush interval must be positive" }
        require(heartbeatIntervalMillis > 0) { "Heartbeat interval must be positive" }
        require(idleTimeoutMillis > 0) { "Idle timeout must be positive" }
        require(abandonedSessionTimeoutMillis > 0) { "Abandoned-session timeout must be positive" }
        require(startWriteTimeoutMillis > 0) { "Start-write timeout must be positive" }
        require(retryBackoffMillis.all { it >= 0 }) { "Retry backoff cannot be negative" }
    }
}

class DefaultImmersionRecorder(
    private val repository: ImmersionRecorderRepository,
    private val deviceIdProvider: ImmersionDeviceIdProvider,
    private val captureEnabled: () -> Boolean,
    private val diagnostics: ImmersionStatsDiagnosticsStore,
    private val clock: ImmersionRecorderClock = SystemImmersionRecorderClock,
    private val repairScheduler: ImmersionRepairScheduler = ImmersionRepairScheduler { _, _ -> },
    private val rollupScheduler: ImmersionRollupScheduler = ImmersionRollupScheduler { _, _ -> },
    private val eventPersistenceObserver: ImmersionEventPersistenceObserver =
        ImmersionEventPersistenceObserver { },
    private val configuration: ImmersionRecorderConfiguration = ImmersionRecorderConfiguration(),
    private val idleTimeoutMillis: () -> Long = { configuration.idleTimeoutMillis },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    externalScope: CoroutineScope? = null,
) : ImmersionRecorder {

    private val lock = Any()
    private val transitionMutex = Mutex()
    private val workerScope = externalScope ?: CoroutineScope(SupervisorJob() + dispatcher)
    private val workerCommands = Channel<WorkerCommand>(
        configuration.queueCapacity + configuration.batchSize + CRITICAL_QUEUE_ALLOWANCE,
    )
    private val queuedEventCount = AtomicInteger(0)
    private val urgentFlushInFlight = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(ImmersionRecorderSnapshot())
    override val state: StateFlow<ImmersionRecorderSnapshot> = mutableState.asStateFlow()

    private var session: ActiveSession? = null
    private var incognitoBarrier = false
    private val workerJob: Job

    init {
        workerJob = workerScope.launch { runWorker() }
        workerScope.launch {
            while (isActive) {
                delay(configuration.heartbeatIntervalMillis)
                recordHeartbeat()
            }
        }
    }

    override suspend fun startSession(context: SessionContext): SessionStartResult =
        transitionMutex.withLock {
            if (session != null) finalizeLocked(FinalizeReason.TITLE_CHANGED)
            resetTerminalState()

            val policy = ImmersionCapturePolicy.evaluate(
                CapturePolicyContext(
                    captureEnabled = captureEnabled(),
                    incognito = incognitoBarrier || context.incognito,
                    titleExcluded = context.titleExcluded ||
                        repository.isTitleCaptureExcluded(context.title.id),
                ),
            )
            if (policy is CapturePolicyDecision.Suppressed) {
                updateState(null, SessionTransition.SUPPRESS)
                return@withLock SessionStartResult.Suppressed(policy.reason)
            }

            updateState(null, SessionTransition.BEGIN_START)
            val startTime = clock.now()
            val sessionId = SessionId(UUID.randomUUID().toString())
            val startEvent = SessionEvent(
                id = EventId(UUID.randomUUID().toString()),
                sessionId = sessionId,
                sequence = 1,
                occurredAtEpochMillis = startTime.epochMillis,
                timezoneOffsetSeconds = startTime.offsetSeconds,
                type = EventType.SESSION_STARTED,
            )
            val start = ImmersionSessionStart(
                id = sessionId,
                deviceId = deviceIdProvider.get(),
                titleId = context.title.id,
                mediaKind = context.title.mediaKind,
                languageTag = context.title.languageTag,
                profileId = context.title.profileId,
                startedAtEpochMillis = startTime.epochMillis,
                startZoneId = startTime.zoneId.id,
                startOffsetSeconds = startTime.offsetSeconds,
                captureVersion = ImmersionStatsVersions.CAPTURE,
                schemaVersion = ImmersionStatsVersions.SCHEMA,
            )
            val startOutcome = withTimeoutOrNull(configuration.startWriteTimeoutMillis) {
                startSessionRows(context, start, startEvent)
            }
            if (startOutcome?.successful != true) {
                val errorCode = startOutcome?.diagnosticCode
                    ?: ImmersionDiagnosticErrorCode.DATABASE_UNAVAILABLE
                diagnostics.recordError(
                    ImmersionDiagnosticStage.WRITE,
                    errorCode,
                )
                updateState(sessionId, SessionTransition.FAIL, errorCode)
                return@withLock SessionStartResult.Failed(errorCode)
            }

            synchronized(lock) {
                session = ActiveSession(
                    context = context,
                    id = sessionId,
                    startedAt = startTime,
                    state = ImmersionSessionState.ACTIVE,
                    lastBoundary = startTime,
                    lastActivityMonotonicNanos = startTime.monotonicNanos,
                    nextSequence = 2,
                )
            }
            updateState(sessionId, SessionTransition.START)
            SessionStartResult.Started(SessionHandle(sessionId))
        }

    override fun record(command: CaptureCommand): RecordResult =
        recordLocked(expectedSessionId = null, command)

    override fun record(handle: SessionHandle, command: CaptureCommand): RecordResult =
        recordLocked(expectedSessionId = handle.sessionId, command)

    private fun recordLocked(
        expectedSessionId: SessionId?,
        command: CaptureCommand,
    ): RecordResult =
        synchronized(lock) {
            if (!captureEnabled()) {
                return@synchronized RecordResult.Suppressed(
                    CaptureSuppressionReason.FEATURE_DISABLED,
                )
            }
            if (incognitoBarrier) {
                return@synchronized RecordResult.Suppressed(
                    CaptureSuppressionReason.INCOGNITO,
                )
            }
            val active = session ?: return@synchronized RecordResult.Rejected(mutableState.value.state)
            if (expectedSessionId != null && active.id != expectedSessionId) {
                return@synchronized RecordResult.Rejected(active.state)
            }
            val pausedInteraction = active.state == ImmersionSessionState.PAUSED &&
                (
                    command is CaptureCommand.Exposure ||
                        command is CaptureCommand.Lookup ||
                        command is CaptureCommand.AnkiOperation
                    )
            if (
                active.state != ImmersionSessionState.ACTIVE &&
                active.state != ImmersionSessionState.IDLE &&
                !pausedInteraction
            ) {
                return@synchronized RecordResult.Rejected(active.state)
            }
            val now = clock.now()
            val drafts = if (pausedInteraction) {
                mutableListOf()
            } else {
                accrueActiveTimeLocked(active, now).toMutableList()
            }
            if (active.state == ImmersionSessionState.IDLE) {
                active.state = ImmersionSessionState.ACTIVE
                active.lastBoundary = now
                drafts += EventDraft.Session(EventType.RESUMED, now, 0)
            }
            if (!pausedInteraction) {
                active.lastActivityMonotonicNanos = now.monotonicNanos
            }
            when (command) {
                is CaptureCommand.Activity -> {
                    if (command.eventType != EventType.PROGRESS) {
                        drafts += EventDraft.Session(command.eventType, now, 0)
                    }
                }
                is CaptureCommand.Progress -> {
                    drafts += EventDraft.Session(
                        eventType = EventType.PROGRESS,
                        time = now,
                        activeDurationMillis = 0,
                        netCharacters = command.netCharacters,
                    )
                }
                is CaptureCommand.Exposure -> {
                    if (command.source.titleId != active.context.title.id) {
                        return@synchronized RecordResult.Rejected(active.state)
                    }
                    drafts += EventDraft.Exposure(
                        time = now,
                        command = command,
                    )
                }
                is CaptureCommand.Lookup -> {
                    drafts += EventDraft.Lookup(time = now, command = command)
                }
                is CaptureCommand.AnkiOperation -> {
                    drafts += EventDraft.AnkiOperation(time = now, command = command)
                }
            }
            enqueueDraftsLocked(active, drafts, bounded = true)
        }

    override suspend fun pause(reason: PauseReason) {
        pauseLocked(expectedSessionId = null, reason)
    }

    override suspend fun pause(handle: SessionHandle, reason: PauseReason) {
        pauseLocked(expectedSessionId = handle.sessionId, reason)
    }

    private suspend fun pauseLocked(
        expectedSessionId: SessionId?,
        reason: PauseReason,
    ) {
        transitionMutex.withLock {
            val shouldFlush = synchronized(lock) {
                val active = session ?: return@synchronized false
                if (expectedSessionId != null && active.id != expectedSessionId) {
                    return@synchronized false
                }
                if (
                    active.state == ImmersionSessionState.FINALIZING ||
                    active.state == ImmersionSessionState.FINALIZED ||
                    active.state == ImmersionSessionState.FAILED
                ) {
                    return@synchronized false
                }
                if (active.state == ImmersionSessionState.BACKGROUND && reason != PauseReason.BACKGROUND) {
                    return@synchronized false
                }
                val now = clock.now()
                val drafts = accrueActiveTimeLocked(active, now).toMutableList()
                val eventType = if (reason == PauseReason.BACKGROUND) {
                    EventType.BACKGROUNDED
                } else {
                    EventType.PAUSED
                }
                val transition = if (reason == PauseReason.BACKGROUND) {
                    SessionTransition.FOREGROUND_LOST
                } else {
                    SessionTransition.PAUSE
                }
                active.state = ImmersionSessionStateMachine.transition(active.state, transition)
                drafts += EventDraft.Session(eventType, now, 0)
                enqueueDraftsLocked(active, drafts, bounded = false)
                mutableState.value = mutableState.value.copy(state = active.state)
                true
            }
            if (shouldFlush) flush()
        }
    }

    override suspend fun resume(reason: ResumeReason) {
        resumeLocked(expectedSessionId = null, reason)
    }

    override suspend fun resume(handle: SessionHandle, reason: ResumeReason) {
        resumeLocked(expectedSessionId = handle.sessionId, reason)
    }

    private suspend fun resumeLocked(
        expectedSessionId: SessionId?,
        reason: ResumeReason,
    ) {
        transitionMutex.withLock {
            if (incognitoBarrier || !captureEnabled()) return@withLock
            synchronized(lock) {
                val active = session ?: return@synchronized
                if (expectedSessionId != null && active.id != expectedSessionId) {
                    return@synchronized
                }
                if (active.state == ImmersionSessionState.BACKGROUND) {
                    active.state = ImmersionSessionStateMachine.transition(
                        active.state,
                        SessionTransition.FOREGROUND_RESTORED,
                    )
                }
                if (
                    active.state != ImmersionSessionState.PAUSED &&
                    active.state != ImmersionSessionState.IDLE
                ) {
                    return@synchronized
                }
                val now = clock.now()
                active.state = ImmersionSessionStateMachine.transition(active.state, SessionTransition.RESUME)
                active.lastBoundary = now
                active.lastActivityMonotonicNanos = now.monotonicNanos
                enqueueDraftsLocked(
                    active,
                    listOf(EventDraft.Session(EventType.RESUMED, now, 0)),
                    bounded = false,
                )
                mutableState.value = mutableState.value.copy(state = active.state)
            }
        }
    }

    override suspend fun finalize(reason: FinalizeReason) {
        transitionMutex.withLock {
            finalizeLocked(reason, expectedSessionId = null)
        }
    }

    override suspend fun finalize(
        handle: SessionHandle,
        reason: FinalizeReason,
    ): ImmersionSession? =
        transitionMutex.withLock {
            finalizeLocked(reason, expectedSessionId = handle.sessionId)
        }

    override suspend fun setIncognito(enabled: Boolean) {
        transitionMutex.withLock {
            if (enabled) {
                synchronized(lock) { incognitoBarrier = true }
                finalizeLocked(FinalizeReason.INCOGNITO_ENABLED, expectedSessionId = null)
            } else {
                synchronized(lock) { incognitoBarrier = false }
                resetTerminalState()
            }
        }
    }

    override suspend fun recoverAbandonedSessions(): Long {
        val cutoff = (clock.now().epochMillis - configuration.abandonedSessionTimeoutMillis).coerceAtLeast(0)
        val recovered = runCatching { repository.recoverAbandonedSessions(cutoff) }
            .getOrElse { error ->
                diagnostics.recordError(ImmersionDiagnosticStage.WRITE, error.toDiagnosticCode())
                return 0
            }
        if (recovered > 0) diagnostics.recordAbandonedRecovery(recovered)
        return recovered
    }

    override suspend fun hasSeenSource(sourceUnitId: SourceUnitId): Boolean =
        runCatching { repository.sourceUnitExists(sourceUnitId) }
            .getOrElse { error ->
                diagnostics.recordError(ImmersionDiagnosticStage.WRITE, error.toDiagnosticCode())
                false
            }

    private suspend fun startSessionRows(
        context: SessionContext,
        start: ImmersionSessionStart,
        startEvent: SessionEvent,
    ): EventWriteOutcome {
        val persisted = persistSingleWithRetry {
            repository.startSession(context.title, start, startEvent)
        }
        if (persisted.successful) {
            diagnostics.addRollupLag(1)
            rollupScheduler.schedule(startEvent.id, 1)
        }
        return persisted
    }

    private suspend fun finalizeLocked(
        reason: FinalizeReason,
        expectedSessionId: SessionId? = null,
    ): ImmersionSession? {
        val finalization = synchronized(lock) {
            val active = session ?: return null
            if (expectedSessionId != null && active.id != expectedSessionId) return null
            if (
                active.state == ImmersionSessionState.FINALIZING ||
                active.state == ImmersionSessionState.FINALIZED
            ) {
                return null
            }
            val now = clock.now()
            val drafts = accrueActiveTimeLocked(active, now).toMutableList()
            active.state = ImmersionSessionStateMachine.transition(
                active.state,
                SessionTransition.BEGIN_FINALIZE,
            )
            drafts += EventDraft.Session(EventType.SESSION_FINALIZED, now, 0)
            enqueueDraftsLocked(active, drafts, bounded = false)
            mutableState.value = mutableState.value.copy(state = active.state)
            PendingFinalization(
                sessionId = active.id,
                now = now,
                elapsedMillis = (
                    (now.monotonicNanos - active.startedAt.monotonicNanos)
                        .coerceAtLeast(0) / NANOS_PER_MILLISECOND
                    ),
                expectedActiveMillis = active.expectedActiveMillis,
                expectedGrossCharacters = active.expectedGrossCharacters,
                expectedUniqueCharacters = active.expectedUniqueCharacters,
                expectedNetCharacters = active.expectedNetCharacters,
            )
        }
        val flushed = flush()
        val status = if (reason == FinalizeReason.PERSISTENCE_FAILURE || !flushed) {
            SessionStatus.ABANDONED
        } else {
            SessionStatus.COMPLETED
        }
        val endedAt = max(finalization.now.epochMillis, sessionStartEpochMillis(finalization.sessionId))
        val finalizationOutcome = persistSingleWithRetry {
            repository.finalizeSession(
                sessionId = finalization.sessionId,
                status = status,
                endedAtEpochMillis = endedAt,
                elapsedDuration = MillisecondDuration(finalization.elapsedMillis),
            )
        }
        val finalized = finalizationOutcome.successful
        if (!finalized) {
            diagnostics.recordError(
                ImmersionDiagnosticStage.WRITE,
                finalizationOutcome.diagnosticCode,
            )
        }
        synchronized(lock) {
            val active = session
            if (active?.id == finalization.sessionId) {
                active.state = if (finalized) {
                    ImmersionSessionStateMachine.transition(
                        active.state,
                        SessionTransition.FINALIZE,
                    )
                } else {
                    ImmersionSessionStateMachine.transition(active.state, SessionTransition.FAIL)
                }
                mutableState.value = mutableState.value.copy(
                    state = active.state,
                    sourceUnitId = null,
                    lastFailure = if (finalized) null else finalizationOutcome.diagnosticCode,
                )
                session = null
            }
        }
        return if (finalized) validateFinalCounters(finalization) else null
    }

    private fun sessionStartEpochMillis(sessionId: SessionId): Long =
        synchronized(lock) {
            session?.takeIf { it.id == sessionId }?.startedAt?.epochMillis ?: 0
        }

    private suspend fun validateFinalCounters(expected: PendingFinalization): ImmersionSession? {
        val persisted = runCatching { repository.getSession(expected.sessionId) }
            .getOrElse { error ->
                diagnostics.recordError(ImmersionDiagnosticStage.WRITE, error.toDiagnosticCode())
                return null
            } ?: return null
        if (
            persisted.activeDuration.value != expected.expectedActiveMillis ||
            persisted.grossCharacters.value != expected.expectedGrossCharacters ||
            persisted.uniqueSourceCharacters.value != expected.expectedUniqueCharacters ||
            persisted.netCharacters.value != expected.expectedNetCharacters
        ) {
            diagnostics.recordRepair(clock.now().epochMillis)
            repairScheduler.schedule(expected.sessionId, ImmersionRepairReason.SESSION_COUNTER_DIVERGENCE)
        }
        return persisted
    }

    private fun recordHeartbeat() {
        synchronized(lock) {
            val active = session ?: return
            if (active.state != ImmersionSessionState.ACTIVE) return
            val now = clock.now()
            val drafts = accrueActiveTimeLocked(active, now)
            if (drafts.isNotEmpty()) {
                enqueueDraftsLocked(active, drafts, bounded = true)
            }
        }
    }

    private fun accrueActiveTimeLocked(
        active: ActiveSession,
        now: RecorderTime,
    ): List<EventDraft> {
        if (active.state != ImmersionSessionState.ACTIVE) return emptyList()
        val elapsedNanos = (now.monotonicNanos - active.lastBoundary.monotonicNanos).coerceAtLeast(0)
        val activeDeadline = active.lastActivityMonotonicNanos +
            idleTimeoutMillis().coerceAtLeast(1L) * NANOS_PER_MILLISECOND
        val countedEndNanos = minOf(now.monotonicNanos, activeDeadline)
        val countedNanos = (countedEndNanos - active.lastBoundary.monotonicNanos)
            .coerceIn(0, elapsedNanos)
        val countedMillis = countedNanos / NANOS_PER_MILLISECOND
        val startWall = active.lastBoundary.epochMillis
        val countedEndWall = if (now.epochMillis >= startWall) {
            minOf(now.epochMillis, startWall + countedMillis)
        } else {
            now.epochMillis
        }
        val drafts = splitActiveDurationAtLocalMidnight(
            startEpochMillis = startWall,
            endEpochMillis = countedEndWall,
            durationMillis = countedMillis,
            zoneId = active.lastBoundary.zoneId,
        ).map { segment ->
            EventDraft.Session(
                eventType = EventType.HEARTBEAT,
                time = RecorderTime(
                    epochMillis = segment.occurredAtEpochMillis,
                    monotonicNanos = countedEndNanos,
                    zoneId = active.lastBoundary.zoneId,
                ),
                activeDurationMillis = segment.durationMillis,
                timezoneOffsetSeconds = segment.timezoneOffsetSeconds,
            )
        }.toMutableList()
        active.lastBoundary = now
        if (countedEndNanos < now.monotonicNanos) {
            active.state = ImmersionSessionStateMachine.transition(
                active.state,
                SessionTransition.IDLE_TIMEOUT,
            )
            drafts += EventDraft.Session(EventType.IDLE, now, 0)
            mutableState.value = mutableState.value.copy(state = active.state)
        }
        return drafts
    }

    private fun enqueueDraftsLocked(
        active: ActiveSession,
        drafts: List<EventDraft>,
        bounded: Boolean,
    ): RecordResult {
        if (drafts.isEmpty()) return RecordResult.Enqueued(0)
        if (bounded && incognitoBarrier) {
            return RecordResult.Suppressed(CaptureSuppressionReason.INCOGNITO)
        }
        if (bounded && !reserveQueueCapacity(drafts.size)) {
            diagnostics.recordError(ImmersionDiagnosticStage.WRITE, ImmersionDiagnosticErrorCode.QUEUE_FULL)
            diagnostics.recordDroppedCommand()
            requestUrgentFlush()
            return RecordResult.QueueFull
        }
        if (!bounded) {
            val depth = queuedEventCount.addAndGet(drafts.size)
            diagnostics.setQueueDepth(depth)
        }
        drafts.forEach { draft ->
            val sequence = active.nextSequence++
            val event = when (draft) {
                is EventDraft.Session -> SessionEvent(
                    id = EventId(UUID.randomUUID().toString()),
                    sessionId = active.id,
                    sequence = sequence,
                    occurredAtEpochMillis = draft.time.epochMillis,
                    timezoneOffsetSeconds = draft.timezoneOffsetSeconds ?: draft.time.offsetSeconds,
                    type = draft.eventType,
                    activeDuration = MillisecondDuration(draft.activeDurationMillis),
                    netCharacters = draft.netCharacters,
                )
                is EventDraft.Exposure -> ExposureEvent(
                    id = EventId(UUID.randomUUID().toString()),
                    sessionId = active.id,
                    sequence = sequence,
                    occurredAtEpochMillis = draft.time.epochMillis,
                    timezoneOffsetSeconds = draft.time.offsetSeconds,
                    source = draft.command.source,
                    activeDuration = MillisecondDuration(0),
                    grossCharacters = draft.command.grossCharacters,
                    uniqueSourceCharacters = draft.command.uniqueSourceCharacters,
                    netCharacters = draft.command.netCharacters,
                    replayOrdinal = draft.command.replayOrdinal,
                    exposurePolicy = draft.command.exposurePolicy,
                )
                is EventDraft.Lookup -> LookupEvent(
                    id = EventId(UUID.randomUUID().toString()),
                    sessionId = active.id,
                    sequence = sequence,
                    occurredAtEpochMillis = draft.time.epochMillis,
                    timezoneOffsetSeconds = draft.time.offsetSeconds,
                    lookupId = draft.command.lookupId,
                    sourceUnitId = draft.command.sourceUnitId,
                    queryHash = draft.command.queryHash,
                    rawQuery = draft.command.rawQuery,
                    normalizedHeadword = draft.command.normalizedHeadword,
                    normalizedReading = draft.command.normalizedReading,
                    partOfSpeech = draft.command.partOfSpeech,
                    dictionaryId = draft.command.dictionaryId,
                    resultId = draft.command.resultId,
                    status = draft.command.status,
                )
                is EventDraft.AnkiOperation -> AnkiOperationEvent(
                    id = EventId(UUID.randomUUID().toString()),
                    sessionId = active.id,
                    sequence = sequence,
                    occurredAtEpochMillis = draft.time.epochMillis,
                    timezoneOffsetSeconds = draft.time.offsetSeconds,
                    operationId = draft.command.operationId,
                    sourceUnitId = draft.command.sourceUnitId,
                    expressionHash = draft.command.expressionHash,
                    normalizedExpression = draft.command.normalizedExpression,
                    normalizedReading = draft.command.normalizedReading,
                    operationType = draft.command.operationType,
                    status = draft.command.status,
                    noteId = draft.command.noteId,
                    cardId = draft.command.cardId,
                    deckId = draft.command.deckId,
                    errorCode = draft.command.errorCode,
                )
            }
            active.expectedActiveMillis += event.activeDuration.value
            when (event) {
                is ExposureEvent -> {
                    active.expectedGrossCharacters += event.grossCharacters.value
                    active.expectedUniqueCharacters += event.uniqueSourceCharacters.value
                    active.expectedNetCharacters += event.netCharacters.value
                }
                is SessionEvent -> active.expectedNetCharacters += event.netCharacters.value
                is LookupEvent,
                is AnkiOperationEvent,
                -> Unit
            }
            if (event is ExposureEvent) {
                mutableState.value = mutableState.value.copy(sourceUnitId = event.source.id)
            }
            check(workerCommands.trySend(WorkerCommand.Event(event)).isSuccess)
        }
        return RecordResult.Enqueued(drafts.size)
    }

    private fun reserveQueueCapacity(count: Int): Boolean {
        while (true) {
            val current = queuedEventCount.get()
            if (current + count > configuration.queueCapacity) return false
            if (queuedEventCount.compareAndSet(current, current + count)) {
                diagnostics.setQueueDepth(current + count)
                return true
            }
        }
    }

    private fun requestUrgentFlush() {
        if (!urgentFlushInFlight.compareAndSet(false, true)) return
        workerScope.launch {
            try {
                flush()
            } finally {
                urgentFlushInFlight.set(false)
            }
        }
    }

    private suspend fun flush(): Boolean {
        if (!workerJob.isActive) return false
        val completion = CompletableDeferred<Boolean>()
        workerCommands.send(WorkerCommand.Flush(completion))
        return completion.await()
    }

    private suspend fun runWorker() {
        val batch = mutableListOf<RecordedImmersionEvent>()
        while (workerScope.isActive) {
            val first = workerCommands.receive()
            when (first) {
                is WorkerCommand.Event -> batch += first.event
                is WorkerCommand.Flush -> {
                    first.completion.complete(persistAndRelease(batch))
                    batch.clear()
                    continue
                }
            }
            var flushCompletion: CompletableDeferred<Boolean>? = null
            while (batch.size < configuration.batchSize) {
                val next = withTimeoutOrNull(configuration.flushIntervalMillis) {
                    workerCommands.receive()
                } ?: break
                when (next) {
                    is WorkerCommand.Event -> batch += next.event
                    is WorkerCommand.Flush -> {
                        flushCompletion = next.completion
                        break
                    }
                }
            }
            val persisted = persistAndRelease(batch)
            batch.clear()
            flushCompletion?.complete(persisted)
        }
    }

    private suspend fun persistAndRelease(events: List<RecordedImmersionEvent>): Boolean {
        if (events.isEmpty()) return true
        val started = clock.now().monotonicNanos
        val outcome = persistEventsWithRetry(events)
        val elapsed = ((clock.now().monotonicNanos - started).coerceAtLeast(0) / NANOS_PER_MILLISECOND)
        diagnostics.recordWriteLatency(elapsed)
        val depth = queuedEventCount.addAndGet(-events.size).coerceAtLeast(0)
        diagnostics.setQueueDepth(depth)
        if (!outcome.successful) {
            diagnostics.recordError(
                ImmersionDiagnosticStage.WRITE,
                outcome.diagnosticCode,
            )
            val failedSession = synchronized(lock) {
                session?.let { active ->
                    if (
                        active.state != ImmersionSessionState.FINALIZED
                    ) {
                        active.state = ImmersionSessionStateMachine.transition(
                            active.state,
                            SessionTransition.FAIL,
                        )
                        mutableState.value = mutableState.value.copy(
                            state = active.state,
                            sourceUnitId = null,
                            lastFailure = outcome.diagnosticCode,
                        )
                    }
                    repairScheduler.schedule(active.id, ImmersionRepairReason.EVENT_WRITE_FAILURE)
                    val failure = PendingFailureFinalization(
                        sessionId = active.id,
                        endedAtEpochMillis = max(clock.now().epochMillis, active.startedAt.epochMillis),
                        elapsedMillis = (
                            (clock.now().monotonicNanos - active.startedAt.monotonicNanos)
                                .coerceAtLeast(0) / NANOS_PER_MILLISECOND
                            ),
                    )
                    session = null
                    failure
                }
            }
            failedSession?.let { failure ->
                runCatching {
                    repository.finalizeSession(
                        sessionId = failure.sessionId,
                        status = SessionStatus.ABANDONED,
                        endedAtEpochMillis = failure.endedAtEpochMillis,
                        elapsedDuration = MillisecondDuration(failure.elapsedMillis),
                    )
                }
            }
        } else {
            eventPersistenceObserver.onPersisted(events)
            diagnostics.addRollupLag(events.size.toLong())
            rollupScheduler.schedule(events.last().id, events.size)
        }
        return outcome.successful
    }

    private suspend fun persistEventsWithRetry(
        events: List<RecordedImmersionEvent>,
    ): EventWriteOutcome {
        var attempt = 0
        while (true) {
            val result = runCatching { repository.appendEventBatch(events) }
            val values = result.getOrNull()
            if (values != null && values.size == events.size && values.all(PersistenceResult::isSuccessful)) {
                return EventWriteOutcome.SUCCESS
            }
            val retryable = values
                ?.filterIsInstance<PersistenceResult.Failed>()
                ?.all { it.code == PersistenceErrorCode.DATABASE_BUSY }
                ?: result.exceptionOrNull().isDatabaseBusy()
            if (!retryable || attempt >= configuration.retryBackoffMillis.size) {
                return EventWriteOutcome(
                    successful = false,
                    diagnosticCode = values
                        ?.filterIsInstance<PersistenceResult.Failed>()
                        ?.firstOrNull()
                        ?.code
                        ?.toDiagnosticCode()
                        ?: result.exceptionOrNull().toDiagnosticCode(),
                )
            }
            diagnostics.recordError(ImmersionDiagnosticStage.WRITE, ImmersionDiagnosticErrorCode.DATABASE_BUSY)
            delay(configuration.retryBackoffMillis[attempt++])
        }
    }

    private suspend fun persistSingleWithRetry(
        block: suspend () -> PersistenceResult,
    ): EventWriteOutcome {
        var attempt = 0
        while (true) {
            val result = runCatching { block() }
            val value = result.getOrNull()
            if (value?.isSuccessful() == true) return EventWriteOutcome.SUCCESS
            val retryable = (value as? PersistenceResult.Failed)?.code == PersistenceErrorCode.DATABASE_BUSY ||
                result.exceptionOrNull().isDatabaseBusy()
            if (!retryable || attempt >= configuration.retryBackoffMillis.size) {
                return EventWriteOutcome(
                    successful = false,
                    diagnosticCode = (value as? PersistenceResult.Failed)
                        ?.code
                        ?.toDiagnosticCode()
                        ?: result.exceptionOrNull().toDiagnosticCode(),
                )
            }
            diagnostics.recordError(ImmersionDiagnosticStage.WRITE, ImmersionDiagnosticErrorCode.DATABASE_BUSY)
            delay(configuration.retryBackoffMillis[attempt++])
        }
    }

    private fun updateState(
        sessionId: SessionId?,
        transition: SessionTransition,
        failure: ImmersionDiagnosticErrorCode? = null,
    ) {
        mutableState.value = mutableState.value.let {
            it.copy(
                sessionId = sessionId ?: it.sessionId,
                state = ImmersionSessionStateMachine.transition(it.state, transition),
                lastFailure = failure,
            )
        }
    }

    private fun resetTerminalState() {
        val current = mutableState.value
        if (
            current.state == ImmersionSessionState.FINALIZED ||
            current.state == ImmersionSessionState.FAILED ||
            current.state == ImmersionSessionState.SUPPRESSED
        ) {
            updateState(null, SessionTransition.RESET)
            mutableState.value = mutableState.value.copy(
                sessionId = null,
                sourceUnitId = null,
                lastFailure = null,
            )
        }
    }

    private data class ActiveSession(
        val context: SessionContext,
        val id: SessionId,
        val startedAt: RecorderTime,
        var state: ImmersionSessionState,
        var lastBoundary: RecorderTime,
        var lastActivityMonotonicNanos: Long,
        var nextSequence: Long,
        var expectedActiveMillis: Long = 0,
        var expectedGrossCharacters: Long = 0,
        var expectedUniqueCharacters: Long = 0,
        var expectedNetCharacters: Long = 0,
    )

    private sealed interface EventDraft {
        data class Session(
            val eventType: EventType,
            val time: RecorderTime,
            val activeDurationMillis: Long,
            val timezoneOffsetSeconds: Int? = null,
            val netCharacters: NetCharacterProgress = NetCharacterProgress.ZERO,
        ) : EventDraft

        data class Exposure(
            val time: RecorderTime,
            val command: CaptureCommand.Exposure,
        ) : EventDraft

        data class Lookup(
            val time: RecorderTime,
            val command: CaptureCommand.Lookup,
        ) : EventDraft

        data class AnkiOperation(
            val time: RecorderTime,
            val command: CaptureCommand.AnkiOperation,
        ) : EventDraft
    }

    private sealed interface WorkerCommand {
        data class Event(val event: RecordedImmersionEvent) : WorkerCommand

        data class Flush(val completion: CompletableDeferred<Boolean>) : WorkerCommand
    }

    private data class PendingFinalization(
        val sessionId: SessionId,
        val now: RecorderTime,
        val elapsedMillis: Long,
        val expectedActiveMillis: Long,
        val expectedGrossCharacters: Long,
        val expectedUniqueCharacters: Long,
        val expectedNetCharacters: Long,
    )

    private data class PendingFailureFinalization(
        val sessionId: SessionId,
        val endedAtEpochMillis: Long,
        val elapsedMillis: Long,
    )

    private data class EventWriteOutcome(
        val successful: Boolean,
        val diagnosticCode: ImmersionDiagnosticErrorCode,
    ) {
        companion object {
            val SUCCESS = EventWriteOutcome(
                successful = true,
                diagnosticCode = ImmersionDiagnosticErrorCode.UNKNOWN,
            )
        }
    }

    private companion object {
        const val CRITICAL_QUEUE_ALLOWANCE = 8
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

private fun PersistenceResult.isSuccessful(): Boolean =
    this is PersistenceResult.Applied || this is PersistenceResult.AlreadyApplied

private fun Throwable?.isDatabaseBusy(): Boolean =
    this?.message.orEmpty().contains("busy", ignoreCase = true)

private fun PersistenceErrorCode.toDiagnosticCode(): ImmersionDiagnosticErrorCode = when (this) {
    PersistenceErrorCode.CORRUPT_VALUE -> ImmersionDiagnosticErrorCode.CORRUPT_VALUE
    PersistenceErrorCode.DATABASE_BUSY -> ImmersionDiagnosticErrorCode.DATABASE_BUSY
    PersistenceErrorCode.DATABASE_UNAVAILABLE -> ImmersionDiagnosticErrorCode.DATABASE_UNAVAILABLE
    PersistenceErrorCode.CONSTRAINT_VIOLATION,
    PersistenceErrorCode.IDENTITY_CONFLICT,
    PersistenceErrorCode.SEQUENCE_CONFLICT,
    PersistenceErrorCode.SESSION_NOT_ACTIVE,
    -> ImmersionDiagnosticErrorCode.CONSTRAINT_VIOLATION
    PersistenceErrorCode.UNKNOWN -> ImmersionDiagnosticErrorCode.UNKNOWN
}

private fun Throwable?.toDiagnosticCode(): ImmersionDiagnosticErrorCode {
    val normalizedMessage = this?.message.orEmpty().lowercase()
    return when {
        this is tachiyomi.domain.immersion.model.ImmersionDataException -> code.toDiagnosticCode()
        "busy" in normalizedMessage -> ImmersionDiagnosticErrorCode.DATABASE_BUSY
        "constraint" in normalizedMessage -> ImmersionDiagnosticErrorCode.CONSTRAINT_VIOLATION
        "database" in normalizedMessage -> ImmersionDiagnosticErrorCode.DATABASE_UNAVAILABLE
        else -> ImmersionDiagnosticErrorCode.UNKNOWN
    }
}
