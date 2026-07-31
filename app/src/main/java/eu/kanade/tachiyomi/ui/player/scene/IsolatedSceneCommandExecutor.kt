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
 * Runs FFmpegKit in a dedicated process (`:scene_processing`).
 *
 * NOTE: an earlier version of this comment claimed the process split was required to avoid a
 * duplicate-SONAME linker conflict between `aniyomi-mpv-lib` and `ffmpeg-kit`. That is not correct.
 * `aniyomi-mpv-lib`'s AAR ships NO `libav*.so`; its `libmpv.so` DT_NEEDEDs `libavcodec.so`,
 * `libavformat.so`, `libavutil.so`, etc., and `ffmpeg-kit` is the sole provider of those SONAMEs.
 * Both consumers therefore share the one FFmpeg build already present in the process -- there is no
 * competing second implementation and no ABI-level collision. Consistently, this app also invokes
 * FFmpegKit in the main process from [eu.kanade.tachiyomi.data.animedownload.AnimeDownloader] and
 * [eu.kanade.tachiyomi.util.storage.FFmpegUtils] without any such conflict.
 *
 * The remaining defensible reasons for a separate process are unproven here: isolating FFmpegKit's
 * process-global state (log/statistics callbacks, session registry) from a live libmpv, and
 * containing native crashes in the media path. Neither is backed by a reproduction, so treat this
 * isolation as optional hardening rather than a correctness requirement. If a future change wants to
 * fold scene mining back into the main process, that is safe from a linker standpoint; the only open
 * question is FFmpegKit global-state sharing, which can be handled in-process.
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
