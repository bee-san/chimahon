package eu.kanade.tachiyomi.ui.player.scene

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.arthenica.ffmpegkit.FFmpegKitConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
import java.util.concurrent.ConcurrentHashMap

class IsolatedSceneCommandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val executor by lazy(LazyThreadSafetyMode.NONE) {
        FfmpegKitSceneCommandExecutor()
    }

    private val binder = object : ISceneCommandService.Stub() {
        override fun execute(
            requestId: Long,
            commandType: Int,
            arguments: Array<out String>?,
            callback: ISceneCommandCallback?,
        ) {
            if (arguments == null || callback == null) return
            val copiedArguments = Array(arguments.size) { arguments[it] }
            logcat(LogPriority.DEBUG) {
                "execute: accepted request=$requestId type=$commandType pid=${Process.myPid()}"
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = try {
                        executeAndAwaitNative(commandType, copiedArguments)
                    } catch (_: CancellationException) {
                        SceneCommandResult.Failed
                    } catch (_: Exception) {
                        SceneCommandResult.Failed
                    }
                    withContext(NonCancellable) {
                        logcat(LogPriority.DEBUG) {
                            "execute: completed request=$requestId success=" +
                                "${result is SceneCommandResult.Success} pid=${Process.myPid()}"
                        }
                        deliverResult(requestId, result, callback)
                    }
                } finally {
                    jobs.remove(requestId)
                }
            }
            // Request IDs come from a single AtomicLong in the one main-process executor, so they
            // are unique for this service process's lifetime and cannot collide here.
            jobs[requestId] = job
            job.start()
        }

        override fun cancel(requestId: Long) {
            jobs[requestId]?.cancel()
        }
    }

    /**
     * Delivers the result over the (oneway) callback, capping the payload well under Binder's ~1 MB
     * transaction limit. An oversized successful output is reported as a failure with a distinct log
     * line so it is not silently mistaken for a genuine ffmpeg/ffprobe failure.
     */
    private fun deliverResult(
        requestId: Long,
        result: SceneCommandResult,
        callback: ISceneCommandCallback,
    ) {
        val output = (result as? SceneCommandResult.Success)?.output
        val (success, payload) = when {
            output == null -> false to ""
            output.length <= MAX_SCENE_CALLBACK_OUTPUT_CHARS -> true to output
            else -> {
                logcat(LogPriority.WARN) {
                    "execute: dropping oversized output request=$requestId chars=${output.length}"
                }
                false to ""
            }
        }
        runCatching { callback.onCompleted(requestId, success, payload) }
    }

    override fun onCreate() {
        super.onCreate()
        logcat(LogPriority.DEBUG) { "onCreate: isolated FFmpeg process pid=${Process.myPid()}" }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun executeAndAwaitNative(
        commandType: Int,
        arguments: Array<String>,
    ): SceneCommandResult {
        val nativeFinished = CompletableDeferred<Unit>()
        val resolvedArguments = runCatching { resolveSafArguments(arguments) }.getOrNull() ?: run {
            nativeFinished.complete(Unit)
            return SceneCommandResult.Failed
        }
        var result: SceneCommandResult = SceneCommandResult.Failed
        try {
            result = when (commandType) {
                IsolatedSceneCommandExecutor.COMMAND_FFMPEG -> {
                    executor.executeFfmpeg(resolvedArguments) {
                        nativeFinished.complete(Unit)
                    }
                }
                IsolatedSceneCommandExecutor.COMMAND_FFPROBE -> {
                    executor.executeFfprobe(resolvedArguments) {
                        nativeFinished.complete(Unit)
                    }
                }
                else -> {
                    nativeFinished.complete(Unit)
                    SceneCommandResult.Failed
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            result = SceneCommandResult.Failed
        } finally {
            withContext(NonCancellable) {
                nativeFinished.await()
            }
        }
        return result
    }

    private fun resolveSafArguments(arguments: Array<String>): Array<String>? {
        return Array(arguments.size) { index ->
            val value = arguments[index]
            if (!SceneSafInput.isReadToken(value)) {
                value
            } else {
                val uri = SceneSafInput.decodeForRead(value) ?: return null
                FFmpegKitConfig.getSafParameterForRead(applicationContext, uri)
                    ?.takeIf(String::isNotBlank)
                    ?: return null
            }
        }
    }

    private companion object {
        const val MAX_SCENE_CALLBACK_OUTPUT_CHARS = 128 * 1024
    }
}
