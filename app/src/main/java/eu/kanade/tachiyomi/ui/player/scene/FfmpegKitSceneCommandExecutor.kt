package eu.kanade.tachiyomi.ui.player.scene

import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeSession
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.LogRedirectionStrategy
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Session
import com.arthenica.ffmpegkit.StatisticsCallback
import eu.kanade.tachiyomi.data.animedownload.buildFFmpegFailureMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

internal sealed interface SceneCommandResult {
    data class Success(val output: String = "") : SceneCommandResult

    data object Failed : SceneCommandResult
}

internal interface SceneCommandExecutor {
    suspend fun executeFfmpeg(
        arguments: Array<String>,
        onNativeFinished: () -> Unit = {},
    ): SceneCommandResult

    suspend fun executeFfprobe(
        arguments: Array<String>,
        onNativeFinished: () -> Unit = {},
    ): SceneCommandResult
}

/**
 * Releases an input or output only after both its Kotlin owner and native FFmpeg are done with it.
 */
internal class SceneNativeCleanup(
    private val cleanup: () -> Unit,
) {
    private val lock = Any()
    private val initialNativeFinished = AtomicBoolean(false)
    private var activeNativeUses = 1
    private var released = false
    private var cleaned = false

    fun nativeFinished() {
        finishNativeUse(initialNativeFinished)
    }

    fun retainNativeUse(): () -> Unit {
        synchronized(lock) {
            check(!released) { "Cannot retain a released native resource" }
            activeNativeUses++
        }
        val finished = AtomicBoolean(false)
        return {
            finishNativeUse(finished)
        }
    }

    fun release() {
        val shouldClean = synchronized(lock) {
            released = true
            markCleanIfReady()
        }
        if (shouldClean) runCatching(cleanup)
    }

    private fun finishNativeUse(finished: AtomicBoolean) {
        if (finished.compareAndSet(false, true)) {
            val shouldClean = synchronized(lock) {
                check(activeNativeUses > 0)
                activeNativeUses--
                markCleanIfReady()
            }
            if (shouldClean) runCatching(cleanup)
        }
    }

    private fun markCleanIfReady(): Boolean {
        if (activeNativeUses == 0 && released && !cleaned) {
            cleaned = true
            return true
        }
        return false
    }
}

internal class FfmpegKitSceneCommandExecutor : SceneCommandExecutor {
    override suspend fun executeFfmpeg(
        arguments: Array<String>,
        onNativeFinished: () -> Unit,
    ): SceneCommandResult {
        return executeSession(
            createSession = {
                FFmpegSession.create(
                    arguments,
                    {},
                    SCENE_LOG_CALLBACK,
                    DISCARD_STATISTICS_CALLBACK,
                    LogRedirectionStrategy.NEVER_PRINT_LOGS,
                )
            },
            runSession = FFmpegKitConfig::ffmpegExecute,
            cancelSession = FFmpegSession::cancel,
            resultFor = { session ->
                if (ReturnCode.isSuccess(session.returnCode)) {
                    SceneCommandResult.Success()
                } else {
                    sceneLog { "ffmpeg: ${session.describeFailure()}" }
                    SceneCommandResult.Failed
                }
            },
            onNativeFinished = onNativeFinished,
        )
    }

    override suspend fun executeFfprobe(
        arguments: Array<String>,
        onNativeFinished: () -> Unit,
    ): SceneCommandResult {
        return executeSession(
            createSession = {
                FFprobeSession.create(
                    arguments,
                    {},
                    SCENE_LOG_CALLBACK,
                    LogRedirectionStrategy.NEVER_PRINT_LOGS,
                )
            },
            runSession = FFmpegKitConfig::ffprobeExecute,
            cancelSession = FFprobeSession::cancel,
            resultFor = { session ->
                if (ReturnCode.isSuccess(session.returnCode)) {
                    SceneCommandResult.Success(session.output.orEmpty())
                } else {
                    sceneLog { "ffprobe: ${session.describeFailure()}" }
                    SceneCommandResult.Failed
                }
            },
            onNativeFinished = onNativeFinished,
        )
    }

    /**
     * FFmpegKit's Future can report cancellation after its Runnable has started. Dispatch the
     * synchronous API ourselves so QUEUED -> CANCELLED proves native code never ran, while a
     * RUNNING cancellation calls the native cancel hook. Cleanup is notified only after the
     * synchronous call actually returns.
     */
    private suspend fun <Session : Any> executeSession(
        createSession: () -> Session,
        runSession: (Session) -> Unit,
        cancelSession: (Session) -> Unit,
        resultFor: (Session) -> SceneCommandResult,
        onNativeFinished: () -> Unit,
    ): SceneCommandResult {
        return suspendCancellableCoroutine { continuation ->
            val state = AtomicReference(SessionExecutionState.QUEUED)
            val finishCalled = AtomicBoolean(false)
            fun finishNativeUse() {
                if (finishCalled.compareAndSet(false, true)) {
                    runCatching(onNativeFinished)
                }
            }

            val session = try {
                createSession()
            } catch (_: Exception) {
                state.set(SessionExecutionState.FINISHED)
                finishNativeUse()
                continuation.resume(SceneCommandResult.Failed)
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation {
                if (!state.compareAndSet(SessionExecutionState.QUEUED, SessionExecutionState.CANCELLED) &&
                    state.get() == SessionExecutionState.RUNNING
                ) {
                    runCatching { cancelSession(session) }
                }
            }

            try {
                Dispatchers.IO.dispatch(
                    continuation.context,
                    Runnable {
                        if (!state.compareAndSet(SessionExecutionState.QUEUED, SessionExecutionState.RUNNING)) {
                            if (state.compareAndSet(
                                    SessionExecutionState.CANCELLED,
                                    SessionExecutionState.FINISHED,
                                )
                            ) {
                                finishNativeUse()
                            }
                            return@Runnable
                        }

                        val result = try {
                            runSession(session)
                            runCatching { resultFor(session) }
                                .getOrDefault(SceneCommandResult.Failed)
                        } catch (_: Exception) {
                            SceneCommandResult.Failed
                        } finally {
                            state.set(SessionExecutionState.FINISHED)
                            finishNativeUse()
                        }
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    },
                )
            } catch (_: Exception) {
                if (state.getAndSet(SessionExecutionState.FINISHED) == SessionExecutionState.RUNNING) {
                    runCatching { cancelSession(session) }
                }
                finishNativeUse()
                if (continuation.isActive) {
                    continuation.resume(SceneCommandResult.Failed)
                }
            }
        }
    }

    private enum class SessionExecutionState {
        QUEUED,
        RUNNING,
        CANCELLED,
        FINISHED,
    }

    private companion object {
        val DISCARD_STATISTICS_CALLBACK = StatisticsCallback {}

        /**
         * FFmpegKit hands every line to the session callback before consulting the redirection
         * strategy, so [LogRedirectionStrategy.NEVER_PRINT_LOGS] still yields the full output here
         * while suppressing FFmpegKit's own unredacted logcat writes.
         */
        val SCENE_LOG_CALLBACK = LogCallback { log ->
            if (log.level.value <= Level.AV_LOG_WARNING.value) {
                log.message?.takeIf(String::isNotBlank)?.let { message ->
                    sceneLog { "ffmpeg output: ${redactSceneLogLine(message)}" }
                }
            }
        }

        fun Session.describeFailure(): String {
            val message = buildFFmpegFailureMessage(
                exitCode = returnCode?.toString() ?: "<none>",
                failStackTrace = failStackTrace,
                logs = allLogsAsString,
            )
            return redactSceneLogLine(message)
        }
    }
}
