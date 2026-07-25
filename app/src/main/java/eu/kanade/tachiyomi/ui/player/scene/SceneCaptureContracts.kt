package eu.kanade.tachiyomi.ui.player.scene

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal data class SceneCapturePipelineRequest(
    val input: SceneVideoInputSpec,
    val animationRange: SceneTimeRange,
)

internal enum class SceneCaptureUnsupportedReason {
    CONTENT_URI_UNAVAILABLE,
    DRM,
    ENCRYPTED,
    HDR_OR_TEN_BIT,
    NON_SEEKABLE,
}

internal enum class SceneCaptureFailureReason {
    PROBE_FAILED,
    PROTECTION_CHECK_FAILED,
    EXTRACTION_FAILED,
    INVALID_FRAME_COUNT,
    FRAME_ENCODING_FAILED,
    MUX_FAILED,
    INVALID_ANIMATED_WEBP,
    FINALIZATION_FAILED,
    IO_FAILURE,
    TIMEOUT,
}

internal data class SceneCaptureMetrics(
    val frameCount: Int,
    val outputBytes: Long,
    val outputDurationMillis: Long,
    val wallTimeMillis: Long,
)

internal sealed interface SceneCaptureProgress {
    data object Preparing : SceneCaptureProgress

    data object Extracting : SceneCaptureProgress

    data class Encoding(
        val frameIndex: Int,
        val frameCount: Int,
    ) : SceneCaptureProgress

    data object Muxing : SceneCaptureProgress

    data object Hashing : SceneCaptureProgress
}

/**
 * A validated scene output owned by the receiver.
 *
 * [close] is idempotent and should be called after ownership has either been
 * transferred to the Anki media request or abandoned.
 */
internal class SceneCapturedFile internal constructor(
    val file: File,
    val digest: String,
    val preferredBaseName: String,
    private val onClose: (File) -> Unit = { it.delete() },
) : AutoCloseable {
    private val owned = AtomicBoolean(true)

    /**
     * Transfers deletion responsibility to the caller exactly once.
     *
     * A subsequent [close] is safe and does not delete the transferred file.
     * The new owner must delete it or pass it to an API with equivalent
     * delete-after-consumption semantics.
     */
    fun takeFile(): File? {
        return file.takeIf { owned.compareAndSet(true, false) }
    }

    override fun close() {
        if (owned.compareAndSet(true, false)) {
            onClose(file)
        }
    }
}

internal sealed interface SceneCaptureResult {
    data class Success(
        val output: SceneCapturedFile,
        val info: AnimatedWebpInfo,
        val metrics: SceneCaptureMetrics,
    ) : SceneCaptureResult

    data class Unsupported(
        val reason: SceneCaptureUnsupportedReason,
    ) : SceneCaptureResult

    data class Failure(
        val reason: SceneCaptureFailureReason,
    ) : SceneCaptureResult

    data object Cancelled : SceneCaptureResult
}

internal sealed interface SceneCommandResult {
    data class Success(
        val output: String = "",
    ) : SceneCommandResult

    data class Failed(
        val exitCode: Int?,
    ) : SceneCommandResult

    data object Cancelled : SceneCommandResult
}

internal interface SceneCommandExecutor {
    suspend fun executeFfmpeg(
        arguments: Array<String>,
        onCancellationDeferred: () -> Unit = {},
        onCancelledSessionFinished: () -> Unit = {},
    ): SceneCommandResult

    suspend fun executeFfprobe(
        arguments: Array<String>,
        onCancellationDeferred: () -> Unit = {},
        onCancelledSessionFinished: () -> Unit = {},
    ): SceneCommandResult
}

internal sealed interface SceneInputAcquisition {
    data class Acquired(
        val lease: SceneInputLease,
    ) : SceneInputAcquisition

    data class Unsupported(
        val reason: SceneCaptureUnsupportedReason,
    ) : SceneInputAcquisition
}

internal interface SceneInputLease : AutoCloseable {
    val ffmpegValue: String
}

internal fun interface SceneInputAcquirer {
    suspend fun acquire(input: SceneVideoInputSpec): SceneInputAcquisition
}

internal sealed interface SceneInputProtectionResult {
    data class Clear(
        val input: SceneVideoInputSpec,
    ) : SceneInputProtectionResult

    data class Protected(
        val reason: SceneCaptureUnsupportedReason,
    ) : SceneInputProtectionResult

    data object Unavailable : SceneInputProtectionResult
}

internal fun interface SceneInputProtectionChecker {
    suspend fun check(
        input: SceneVideoInputSpec,
        workingDirectory: File,
    ): SceneInputProtectionResult
}

internal fun interface SceneWebpFrameEncoder {
    suspend fun encode(pngFile: File, webpFile: File): Boolean
}
