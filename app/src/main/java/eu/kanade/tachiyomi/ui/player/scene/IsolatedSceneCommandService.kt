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
            sceneLog {
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
                        sceneLog {
                            "execute: completed request=$requestId success=" +
                                "${result is SceneCommandResult.Success} pid=${Process.myPid()}"
                        }
                        deliverSceneCommandCallback(result) { payload ->
                            callback.onCompleted(requestId, payload.success, payload.output)
                        }
                    }
                } finally {
                    jobs.remove(requestId)
                }
            }
            val previous = jobs.putIfAbsent(requestId, job)
            if (previous == null) {
                job.start()
            } else {
                job.cancel()
                runCatching { callback.onCompleted(requestId, false, "") }
            }
        }

        override fun cancel(requestId: Long) {
            jobs[requestId]?.cancel()
        }
    }

    override fun onCreate() {
        super.onCreate()
        sceneLog { "onCreate: isolated FFmpeg process pid=${Process.myPid()}" }
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
}
