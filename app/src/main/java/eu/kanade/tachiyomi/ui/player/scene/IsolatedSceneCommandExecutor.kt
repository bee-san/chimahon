package eu.kanade.tachiyomi.ui.player.scene

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Runs FFmpegKit in a dedicated process so its FFmpeg globals cannot collide with libmpv.
 */
internal class IsolatedSceneCommandExecutor(
    context: Context,
) : SceneCommandExecutor {
    private val applicationContext = context.applicationContext

    override suspend fun executeFfmpeg(
        arguments: Array<String>,
        onNativeFinished: () -> Unit,
    ): SceneCommandResult {
        return execute(COMMAND_FFMPEG, arguments, onNativeFinished)
    }

    override suspend fun executeFfprobe(
        arguments: Array<String>,
        onNativeFinished: () -> Unit,
    ): SceneCommandResult {
        return execute(COMMAND_FFPROBE, arguments, onNativeFinished)
    }

    private suspend fun execute(
        commandType: Int,
        arguments: Array<String>,
        onNativeFinished: () -> Unit,
    ): SceneCommandResult {
        return suspendCancellableCoroutine { continuation ->
            val call = CommandCall(
                context = applicationContext,
                requestId = NEXT_REQUEST_ID.getAndIncrement(),
                commandType = commandType,
                arguments = arguments.copyOf(),
                onNativeFinished = onNativeFinished,
                deliver = { result ->
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                },
            )
            continuation.invokeOnCancellation { call.cancel() }
            call.bind()
        }
    }

    private class CommandCall(
        private val context: Context,
        private val requestId: Long,
        private val commandType: Int,
        private val arguments: Array<String>,
        private val onNativeFinished: () -> Unit,
        private val deliver: (SceneCommandResult) -> Unit,
    ) {
        private val lock = Any()
        private var remote: ISceneCommandService? = null
        private var bound = false
        private var submitted = false
        private var finished = false

        private val callback = object : ISceneCommandCallback.Stub() {
            override fun onCompleted(
                completedRequestId: Long,
                success: Boolean,
                output: String?,
            ) {
                if (completedRequestId != requestId) return
                complete(
                    if (success) {
                        SceneCommandResult.Success(output.orEmpty())
                    } else {
                        SceneCommandResult.Failed
                    },
                )
            }
        }

        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val commandService = ISceneCommandService.Stub.asInterface(service)
                    ?: run {
                        complete(SceneCommandResult.Failed)
                        return
                    }
                val submittedSuccessfully = synchronized(lock) {
                    if (finished) {
                        false
                    } else {
                        remote = commandService
                        try {
                            commandService.execute(requestId, commandType, arguments, callback)
                            submitted = true
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }
                }
                if (!submittedSuccessfully) {
                    complete(SceneCommandResult.Failed)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                complete(SceneCommandResult.Failed)
            }

            override fun onBindingDied(name: ComponentName?) {
                complete(SceneCommandResult.Failed)
            }

            override fun onNullBinding(name: ComponentName?) {
                complete(SceneCommandResult.Failed)
            }
        }

        fun bind() {
            val didBind = runCatching {
                context.bindService(
                    Intent(context, IsolatedSceneCommandService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
                )
            }.getOrDefault(false)
            val shouldUnbind = synchronized(lock) {
                bound = didBind
                didBind && finished
            }
            if (shouldUnbind) {
                synchronized(lock) {
                    bound = false
                }
                runCatching { context.unbindService(connection) }
            } else if (!didBind) {
                complete(SceneCommandResult.Failed)
            }
        }

        fun cancel() {
            val action = synchronized(lock) {
                when {
                    finished -> CancelAction.None
                    submitted -> CancelAction.Remote(remote)
                    else -> {
                        finished = true
                        CancelAction.Local
                    }
                }
            }
            when (action) {
                CancelAction.None -> Unit
                CancelAction.Local -> finish(SceneCommandResult.Failed)
                is CancelAction.Remote -> {
                    try {
                        action.service?.cancel(requestId)
                    } catch (_: Exception) {
                        complete(SceneCommandResult.Failed)
                    }
                }
            }
        }

        private fun complete(result: SceneCommandResult) {
            val shouldFinish = synchronized(lock) {
                if (finished) {
                    false
                } else {
                    finished = true
                    true
                }
            }
            if (shouldFinish) finish(result)
        }

        private fun finish(result: SceneCommandResult) {
            runCatching(onNativeFinished)
            deliver(result)
            val shouldUnbind = synchronized(lock) {
                remote = null
                bound.also { bound = false }
            }
            if (shouldUnbind) {
                runCatching { context.unbindService(connection) }
            }
        }

        private sealed interface CancelAction {
            data object None : CancelAction
            data object Local : CancelAction
            data class Remote(val service: ISceneCommandService?) : CancelAction
        }
    }

    internal companion object {
        const val COMMAND_FFMPEG = 1
        const val COMMAND_FFPROBE = 2

        private val NEXT_REQUEST_ID = AtomicLong(1L)
    }
}
