package eu.kanade.tachiyomi.ui.player.scene

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class SceneCapturePipeline(
    private val cacheRoot: File,
    private val commandExecutor: SceneCommandExecutor,
    private val inputAcquirer: SceneInputAcquirer,
    private val inputProtectionChecker: SceneInputProtectionChecker = SceneInputProtectionChecker { input, _ ->
        SceneInputProtectionResult.Clear(input)
    },
    private val frameEncoder: SceneWebpFrameEncoder,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val jobIdProvider: () -> String = { UUID.randomUUID().toString() },
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onProgress: (SceneCaptureProgress) -> Unit = {},
    private val onMetrics: (SceneCaptureMetrics) -> Unit = {},
    private val jobDirectoryCleaner: (File) -> Boolean = File::deleteRecursively,
    private val afterFinalization: suspend (SceneCapturedFile) -> Unit = {},
) {
    init {
        require(timeoutMillis > 0L)
    }

    suspend fun capture(request: SceneCapturePipelineRequest): SceneCaptureResult {
        val startedAtNanos = System.nanoTime()
        val finalizedOutput = FinalizedOutputGuard()
        return try {
            val result = withTimeout(timeoutMillis) {
                withContext(workerDispatcher) {
                    captureWithinTimeout(request, startedAtNanos, finalizedOutput)
                }
            }
            if (result is SceneCaptureResult.Success) {
                if (!finalizedOutput.release(result.output)) {
                    result.output.close()
                    error("Finalized scene output ownership was lost")
                }
            }
            result
        } catch (_: TimeoutCancellationException) {
            SceneCaptureResult.Failure(SceneCaptureFailureReason.TIMEOUT)
        } catch (_: CancellationException) {
            SceneCaptureResult.Cancelled
        } catch (_: Exception) {
            SceneCaptureResult.Failure(SceneCaptureFailureReason.IO_FAILURE)
        } finally {
            finalizedOutput.close()
        }
    }

    private suspend fun captureWithinTimeout(
        request: SceneCapturePipelineRequest,
        startedAtNanos: Long,
        finalizedOutput: FinalizedOutputGuard,
    ): SceneCaptureResult {
        val range = request.animationRange
        if (
            !range.startSeconds.isFinite() ||
            !range.endSeconds.isFinite() ||
            range.startSeconds < 0.0 ||
            range.durationSeconds !in SCENE_MIN_DURATION_SECONDS..SCENE_MAX_ANIMATION_DURATION_SECONDS
        ) {
            return SceneCaptureResult.Failure(SceneCaptureFailureReason.EXTRACTION_FAILED)
        }

        val jobId = jobIdProvider()
        val safeJobId = jobId.replace(UNSAFE_FILE_CHARACTERS, "_").take(64)
        val jobsRoot = File(cacheRoot, SceneStaleJobRoot.CAPTURE.directoryName)
        if (!jobsRoot.mkdirs() && !jobsRoot.isDirectory) {
            return SceneCaptureResult.Failure(SceneCaptureFailureReason.IO_FAILURE)
        }
        SceneStaleJobCleaner.clean(
            cacheRoot = cacheRoot,
            jobRoot = SceneStaleJobRoot.CAPTURE,
            excludedChildName = safeJobId,
        )
        currentCoroutineContext().ensureActive()
        val jobDirectory = File(jobsRoot, safeJobId)
        if (!jobDirectory.mkdir()) {
            return SceneCaptureResult.Failure(SceneCaptureFailureReason.IO_FAILURE)
        }
        val cleanupDeferred = AtomicBoolean(false)
        val cancelledSessionCleanupFinished = AtomicBoolean(false)
        val onCancellationDeferred = {
            cleanupDeferred.set(true)
        }
        val onCancelledSessionFinished = {
            if (cancelledSessionCleanupFinished.compareAndSet(false, true)) {
                runCatching { jobDirectoryCleaner(jobDirectory) }
            }
        }

        val outcome = try {
            runCaptureJob(
                request = request,
                jobDirectory = jobDirectory,
                jobId = jobId,
                finalizedOutput = finalizedOutput,
                onCancellationDeferred = onCancellationDeferred,
                onCancelledSessionFinished = onCancelledSessionFinished,
            )
        } catch (throwable: Throwable) {
            if (!cleanupDeferred.get()) {
                runCatching { jobDirectoryCleaner(jobDirectory) }
            }
            throw throwable
        }
        val cleaned = runCatching {
            jobDirectoryCleaner(jobDirectory)
        }.getOrDefault(false)
        if (!cleaned) {
            (outcome as? SceneCaptureResult.Success)?.output?.close()
            return SceneCaptureResult.Failure(SceneCaptureFailureReason.IO_FAILURE)
        }
        val success = outcome as? SceneCaptureResult.Success ?: return outcome
        val metrics = success.metrics.copy(
            wallTimeMillis = ((System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / NANOS_PER_MILLISECOND),
        )
        reportMetrics(metrics)
        return success.copy(metrics = metrics)
    }

    private suspend fun runCaptureJob(
        request: SceneCapturePipelineRequest,
        jobDirectory: File,
        jobId: String,
        finalizedOutput: FinalizedOutputGuard,
        onCancellationDeferred: () -> Unit,
        onCancelledSessionFinished: () -> Unit,
    ): SceneCaptureResult {
        reportProgress(SceneCaptureProgress.Preparing)
        val protectedRequest = when (
            val protection = inputProtectionChecker.check(
                request.input,
                File(jobDirectory, "checked_input"),
            )
        ) {
            is SceneInputProtectionResult.Clear -> request.copy(input = protection.input)
            is SceneInputProtectionResult.Protected -> {
                return SceneCaptureResult.Unsupported(protection.reason)
            }
            SceneInputProtectionResult.Unavailable -> {
                return SceneCaptureResult.Failure(SceneCaptureFailureReason.PROTECTION_CHECK_FAILED)
            }
        }
        when (
            val probe = probe(
                input = protectedRequest.input,
                onCancellationDeferred = onCancellationDeferred,
                onCancelledSessionFinished = onCancelledSessionFinished,
            )
        ) {
            is SceneMediaProbeResult.Supported -> {
                if (probe.durationSeconds < SCENE_MIN_DURATION_SECONDS) {
                    return SceneCaptureResult.Failure(SceneCaptureFailureReason.INVALID_FRAME_COUNT)
                }
            }
            is SceneMediaProbeResult.Unsupported -> {
                return SceneCaptureResult.Unsupported(probe.reason)
            }
            SceneMediaProbeResult.Invalid -> {
                return SceneCaptureResult.Failure(SceneCaptureFailureReason.PROBE_FAILED)
            }
        }

        val pngDirectory = File(jobDirectory, "png")
        if (!pngDirectory.mkdir()) {
            return SceneCaptureResult.Failure(SceneCaptureFailureReason.IO_FAILURE)
        }
        reportProgress(SceneCaptureProgress.Extracting)
        val extractionResult = withFreshInput(
            input = protectedRequest.input,
            onCancellationDeferred = onCancellationDeferred,
            onCancelledSessionFinished = onCancelledSessionFinished,
        ) { acquiredInput, deferCancellation, finishCancelledSession ->
            commandExecutor.executeFfmpeg(
                arguments = SceneFfmpegArguments.frameExtraction(
                    input = protectedRequest.input,
                    acquiredInputValue = acquiredInput,
                    range = protectedRequest.animationRange,
                    outputPattern = File(pngDirectory, PNG_FRAME_PATTERN).absolutePath,
                ),
                onCancellationDeferred = deferCancellation,
                onCancelledSessionFinished = finishCancelledSession,
            )
        }
        when (extractionResult) {
            is InputCommandResult.Unsupported -> {
                return SceneCaptureResult.Unsupported(extractionResult.reason)
            }
            is InputCommandResult.Executed -> {
                when (extractionResult.result) {
                    is SceneCommandResult.Success -> Unit
                    is SceneCommandResult.Failed -> {
                        return SceneCaptureResult.Failure(SceneCaptureFailureReason.EXTRACTION_FAILED)
                    }
                    SceneCommandResult.Cancelled -> return SceneCaptureResult.Cancelled
                }
            }
        }

        val pngFrames = pngDirectory
            .listFiles()
            .orEmpty()
            .filter { it.isFile && PNG_FRAME_NAME.matches(it.name) }
            .sortedBy(File::getName)
        if (pngFrames.size !in SCENE_MIN_FRAME_COUNT..SCENE_MAX_FRAME_COUNT) {
            return SceneCaptureResult.Failure(SceneCaptureFailureReason.INVALID_FRAME_COUNT)
        }

        val webpDirectory = File(jobDirectory, "webp")
        if (!webpDirectory.mkdir()) {
            return SceneCaptureResult.Failure(SceneCaptureFailureReason.IO_FAILURE)
        }
        for ((index, pngFrame) in pngFrames.withIndex()) {
            currentCoroutineContext().ensureActive()
            reportProgress(
                SceneCaptureProgress.Encoding(
                    frameIndex = index + 1,
                    frameCount = pngFrames.size,
                ),
            )
            val webpFrame = File(webpDirectory, "frame_${(index + 1).toString().padStart(3, '0')}.webp")
            val encoded = try {
                frameEncoder.encode(pngFrame, webpFrame)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            } finally {
                pngFrame.delete()
            }
            if (!encoded || !webpFrame.isFile || webpFrame.length() <= 0L) {
                return SceneCaptureResult.Failure(SceneCaptureFailureReason.FRAME_ENCODING_FAILED)
            }
        }

        currentCoroutineContext().ensureActive()
        reportProgress(SceneCaptureProgress.Muxing)
        val muxedOutput = File(jobDirectory, "scene-candidate.webp")
        when (
            commandExecutor.executeFfmpeg(
                arguments = SceneFfmpegArguments.animatedWebpMux(
                    inputPattern = File(webpDirectory, WEBP_FRAME_PATTERN).absolutePath,
                    outputFile = muxedOutput.absolutePath,
                ),
                onCancellationDeferred = onCancellationDeferred,
                onCancelledSessionFinished = onCancelledSessionFinished,
            )
        ) {
            is SceneCommandResult.Success -> Unit
            is SceneCommandResult.Failed -> {
                return SceneCaptureResult.Failure(SceneCaptureFailureReason.MUX_FAILED)
            }
            SceneCommandResult.Cancelled -> return SceneCaptureResult.Cancelled
        }

        currentCoroutineContext().ensureActive()
        val validation = AnimatedWebpValidator.validate(muxedOutput)
        val valid = validation as? AnimatedWebpValidation.Valid
            ?: return SceneCaptureResult.Failure(SceneCaptureFailureReason.INVALID_ANIMATED_WEBP)

        currentCoroutineContext().ensureActive()
        reportProgress(SceneCaptureProgress.Hashing)
        val digest = try {
            SceneCaptureFiles.sha256(muxedOutput)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return SceneCaptureResult.Failure(SceneCaptureFailureReason.FINALIZATION_FAILED)
        }
        currentCoroutineContext().ensureActive()
        val finalized = try {
            SceneCaptureFiles.atomicallyFinalize(
                source = muxedOutput,
                outputDirectory = File(cacheRoot, OUTPUT_DIRECTORY_NAME),
                jobId = jobId,
                digest = digest,
            )
        } catch (_: Exception) {
            return SceneCaptureResult.Failure(SceneCaptureFailureReason.FINALIZATION_FAILED)
        }
        finalizedOutput.own(finalized)
        afterFinalization(finalized)
        currentCoroutineContext().ensureActive()

        val metrics = SceneCaptureMetrics(
            frameCount = valid.info.frameCount,
            outputBytes = finalized.file.length(),
            outputDurationMillis = valid.info.totalDurationMillis,
            wallTimeMillis = 0L,
        )
        return SceneCaptureResult.Success(
            output = finalized,
            info = valid.info,
            metrics = metrics,
        )
    }

    private fun reportProgress(progress: SceneCaptureProgress) {
        runCatching { onProgress(progress) }
    }

    private fun reportMetrics(metrics: SceneCaptureMetrics) {
        runCatching { onMetrics(metrics) }
    }

    private suspend fun probe(
        input: SceneVideoInputSpec,
        onCancellationDeferred: () -> Unit,
        onCancelledSessionFinished: () -> Unit,
    ): SceneMediaProbeResult {
        return when (
            val result = withFreshInput(
                input = input,
                onCancellationDeferred = onCancellationDeferred,
                onCancelledSessionFinished = onCancelledSessionFinished,
            ) { acquiredInput, deferCancellation, finishCancelledSession ->
                commandExecutor.executeFfprobe(
                    arguments = SceneMediaProbe.arguments(input, acquiredInput),
                    onCancellationDeferred = deferCancellation,
                    onCancelledSessionFinished = finishCancelledSession,
                )
            }
        ) {
            is InputCommandResult.Unsupported -> SceneMediaProbeResult.Unsupported(result.reason)
            is InputCommandResult.Executed -> {
                when (val command = result.result) {
                    is SceneCommandResult.Success -> SceneMediaProbe.parse(command.output)
                    is SceneCommandResult.Failed -> SceneMediaProbeResult.Invalid
                    SceneCommandResult.Cancelled -> throw SceneCommandCancelledException()
                }
            }
        }
    }

    private suspend fun withFreshInput(
        input: SceneVideoInputSpec,
        onCancellationDeferred: () -> Unit,
        onCancelledSessionFinished: () -> Unit,
        block: suspend (String, () -> Unit, () -> Unit) -> SceneCommandResult,
    ): InputCommandResult {
        return when (val acquisition = inputAcquirer.acquire(input)) {
            is SceneInputAcquisition.Unsupported -> InputCommandResult.Unsupported(acquisition.reason)
            is SceneInputAcquisition.Acquired -> {
                val leaseCloseDeferred = AtomicBoolean(false)
                val leaseClosed = AtomicBoolean(false)
                val closeLease = {
                    if (leaseClosed.compareAndSet(false, true)) {
                        runCatching(acquisition.lease::close)
                    }
                }
                val deferCancellation = {
                    leaseCloseDeferred.set(true)
                    onCancellationDeferred()
                }
                val finishCancelledSession = {
                    closeLease()
                    onCancelledSessionFinished()
                }
                try {
                    InputCommandResult.Executed(
                        block(
                            acquisition.lease.ffmpegValue,
                            deferCancellation,
                            finishCancelledSession,
                        ),
                    )
                } finally {
                    if (!leaseCloseDeferred.get()) {
                        closeLease()
                    }
                }
            }
        }
    }

    private sealed interface InputCommandResult {
        data class Executed(val result: SceneCommandResult) : InputCommandResult
        data class Unsupported(val reason: SceneCaptureUnsupportedReason) : InputCommandResult
    }

    private class SceneCommandCancelledException : CancellationException()

    private class FinalizedOutputGuard : AutoCloseable {
        private val output = AtomicReference<SceneCapturedFile?>(null)

        fun own(finalized: SceneCapturedFile) {
            check(output.compareAndSet(null, finalized)) {
                "Only one finalized output may be owned by a capture"
            }
        }

        fun release(finalized: SceneCapturedFile): Boolean {
            return output.compareAndSet(finalized, null)
        }

        override fun close() {
            output.getAndSet(null)?.close()
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val OUTPUT_DIRECTORY_NAME = "scene_capture_outputs"
        private const val PNG_FRAME_PATTERN = "frame_%03d.png"
        private const val WEBP_FRAME_PATTERN = "frame_%03d.webp"
        private val PNG_FRAME_NAME = Regex("""frame_\d{3}\.png""")
        private val UNSAFE_FILE_CHARACTERS = Regex("[^A-Za-z0-9_-]")

        fun create(
            context: Context,
            onProgress: (SceneCaptureProgress) -> Unit = {},
            onMetrics: (SceneCaptureMetrics) -> Unit = {},
        ): SceneCapturePipeline {
            return SceneCapturePipeline(
                cacheRoot = context.cacheDir,
                commandExecutor = FfmpegKitSceneCommandExecutor(),
                inputAcquirer = AndroidSceneInputAcquirer(context),
                inputProtectionChecker = AndroidSceneInputProtectionChecker(context),
                frameEncoder = AndroidSceneWebpFrameEncoder(),
                onProgress = onProgress,
                onMetrics = onMetrics,
            )
        }
    }
}
