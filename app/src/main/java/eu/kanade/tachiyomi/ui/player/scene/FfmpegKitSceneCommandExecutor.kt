package eu.kanade.tachiyomi.ui.player.scene

import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeSession
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.LogRedirectionStrategy
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

internal class FfmpegKitSceneCommandExecutor : SceneCommandExecutor {
    override suspend fun executeFfmpeg(
        arguments: Array<String>,
        onCancellationDeferred: () -> Unit,
        onCancelledSessionFinished: () -> Unit,
    ): SceneCommandResult {
        return executeSession(
            createSession = { complete ->
                FFmpegSession.create(
                    arguments,
                    { completed -> complete(completed) },
                    DISCARD_LOG_CALLBACK,
                    DISCARD_STATISTICS_CALLBACK,
                    LogRedirectionStrategy.NEVER_PRINT_LOGS,
                )
            },
            startSession = { FFmpegKitConfig.asyncFFmpegExecute(it) },
            cancelSession = { it.cancel() },
            resultFor = { completed ->
                when {
                    ReturnCode.isSuccess(completed.returnCode) -> SceneCommandResult.Success()
                    ReturnCode.isCancel(completed.returnCode) -> SceneCommandResult.Cancelled
                    else -> SceneCommandResult.Failed(completed.returnCode?.value)
                }
            },
            onCancellationDeferred = onCancellationDeferred,
            onCancelledSessionFinished = onCancelledSessionFinished,
        )
    }

    override suspend fun executeFfprobe(
        arguments: Array<String>,
        onCancellationDeferred: () -> Unit,
        onCancelledSessionFinished: () -> Unit,
    ): SceneCommandResult {
        return executeSession(
            createSession = { complete ->
                FFprobeSession.create(
                    arguments,
                    { completed -> complete(completed) },
                    DISCARD_LOG_CALLBACK,
                    LogRedirectionStrategy.NEVER_PRINT_LOGS,
                )
            },
            startSession = { FFmpegKitConfig.asyncFFprobeExecute(it) },
            cancelSession = { it.cancel() },
            resultFor = { completed ->
                when {
                    ReturnCode.isSuccess(completed.returnCode) -> {
                        SceneCommandResult.Success(completed.output.orEmpty())
                    }
                    ReturnCode.isCancel(completed.returnCode) -> SceneCommandResult.Cancelled
                    else -> SceneCommandResult.Failed(completed.returnCode?.value)
                }
            },
            onCancellationDeferred = onCancellationDeferred,
            onCancelledSessionFinished = onCancelledSessionFinished,
        )
    }

    private suspend fun <Session : Any> executeSession(
        createSession: ((Session) -> Unit) -> Session,
        startSession: (Session) -> Unit,
        cancelSession: (Session) -> Unit,
        resultFor: (Session) -> SceneCommandResult,
        onCancellationDeferred: () -> Unit,
        onCancelledSessionFinished: () -> Unit,
    ): SceneCommandResult {
        val nativeFinished = CompletableDeferred<Unit>()
        val sessionReference = AtomicReference<Session?>()
        val cancellationDeferred = AtomicBoolean(false)
        val deferredCleanupFinished = AtomicBoolean(false)
        return try {
            suspendCancellableCoroutine { continuation ->
                val cancellationRequested = AtomicBoolean(false)
                val terminal = AtomicBoolean(false)
                val startGate = Any()
                var sessionStarted = false

                fun complete(result: SceneCommandResult) {
                    if (terminal.compareAndSet(false, true)) {
                        continuation.resume(result)
                    }
                }

                fun finishDeferredCancellation() {
                    if (
                        cancellationDeferred.get() &&
                        deferredCleanupFinished.compareAndSet(false, true)
                    ) {
                        runCatching(onCancelledSessionFinished)
                    }
                }

                fun requestCancellation() {
                    cancellationRequested.set(true)
                    terminal.set(true)
                    synchronized(startGate) {
                        val session = sessionReference.get()
                        if (!sessionStarted || session == null || nativeFinished.isCompleted) {
                            nativeFinished.complete(Unit)
                        } else {
                            if (cancellationDeferred.compareAndSet(false, true)) {
                                runCatching(onCancellationDeferred)
                            }
                            runCatching { cancelSession(session) }
                        }
                    }
                }

                continuation.invokeOnCancellation { requestCancellation() }
                if (!continuation.isActive) {
                    requestCancellation()
                    return@suspendCancellableCoroutine
                }

                val session = try {
                    createSession { completed ->
                        val finishDeferred = synchronized(startGate) {
                            nativeFinished.complete(Unit)
                            cancellationDeferred.get()
                        }
                        if (finishDeferred) {
                            finishDeferredCancellation()
                        }
                        complete(
                            runCatching { resultFor(completed) }
                                .getOrElse { SceneCommandResult.Failed(exitCode = null) },
                        )
                    }
                } catch (_: Exception) {
                    nativeFinished.complete(Unit)
                    complete(SceneCommandResult.Failed(exitCode = null))
                    return@suspendCancellableCoroutine
                }
                sessionReference.set(session)

                synchronized(startGate) {
                    if (!continuation.isActive || cancellationRequested.get()) {
                        runCatching { cancelSession(session) }
                        nativeFinished.complete(Unit)
                        return@synchronized
                    }
                    try {
                        sessionStarted = true
                        startSession(session)
                    } catch (_: Exception) {
                        sessionStarted = false
                        nativeFinished.complete(Unit)
                        runCatching { cancelSession(session) }
                        complete(SceneCommandResult.Failed(exitCode = null))
                    }
                }
            }
        } catch (e: CancellationException) {
            sessionReference.get()?.let { session ->
                runCatching { cancelSession(session) }
            }
            withContext(NonCancellable) {
                withTimeoutOrNull(NATIVE_CANCELLATION_GRACE_MILLIS) {
                    nativeFinished.await()
                }
            }
            throw e
        }
    }

    private companion object {
        val DISCARD_LOG_CALLBACK = LogCallback {}
        val DISCARD_STATISTICS_CALLBACK = StatisticsCallback {}
        const val NATIVE_CANCELLATION_GRACE_MILLIS = 1_000L
    }
}
