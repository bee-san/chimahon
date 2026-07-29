package eu.kanade.tachiyomi.ui.player.scene

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.webkit.MimeTypeMap
import chimahon.anki.AnkiMediaNaming
import chimahon.anki.AnkiScreenshotPreparation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal fun interface SceneCaptureService {
    suspend fun prepare(request: SceneCaptureRequest): AnkiScreenshotPreparation
}

internal class AndroidSceneCaptureService private constructor(
    private val sceneDirectory: File,
    private val inputAcquirer: SceneInputAcquirer,
    private val commandExecutor: SceneCommandExecutor,
    private val validate: (File) -> AnimatedAvifInfo?,
    private val av1EncoderName: () -> String?,
    private val diagnostics: SceneCaptureDiagnostics,
) : SceneCaptureService {
    constructor(context: Context) : this(
        sceneDirectory = File(context.cacheDir, SCENE_CACHE_DIRECTORY),
        inputAcquirer = AndroidSceneInputAcquirer(context),
        commandExecutor = FfmpegKitSceneCommandExecutor(),
        validate = AnimatedAvifValidator::validate,
        av1EncoderName = { platformAv1EncoderName(AndroidSceneCaptureDiagnostics) },
        diagnostics = AndroidSceneCaptureDiagnostics,
    )

    override suspend fun prepare(request: SceneCaptureRequest): AnkiScreenshotPreparation {
        val input = request.videoInput ?: run {
            diagnostics.reject("gate=videoInput result=reject reason=input-unresolved")
            return AnkiScreenshotPreparation.Failed(stillFallback = null)
        }
        diagnostics.accept("gate=videoInput result=pass kind=${input.kind} headers=${input.headers.size}")
        val range = request.resolvedTiming?.animationRange ?: run {
            diagnostics.reject("gate=timing result=reject reason=animation-range-unresolved")
            return AnkiScreenshotPreparation.Failed(stillFallback = null)
        }
        val encoderName = av1EncoderName()
        if (encoderName.isNullOrBlank()) {
            diagnostics.reject("gate=encoder result=reject reason=no-compatible-av1-encoder")
            return AnkiScreenshotPreparation.Failed(stillFallback = null)
        }
        diagnostics.accept("gate=encoder result=pass encoder=$encoderName")

        return withContext(Dispatchers.IO) {
            val probeStart = System.currentTimeMillis()
            if (!isSafe(input)) {
                diagnostics.reject(
                    "gate=probe result=reject reason=unsafe-or-unsupported-media " +
                        "elapsedMs=${System.currentTimeMillis() - probeStart}",
                )
                return@withContext AnkiScreenshotPreparation.Failed(stillFallback = null)
            }
            diagnostics.accept("gate=probe result=pass elapsedMs=${System.currentTimeMillis() - probeStart}")
            val lease = inputAcquirer.acquire(input) ?: run {
                diagnostics.reject("gate=acquire result=reject reason=input-lease-unavailable")
                return@withContext AnkiScreenshotPreparation.Failed(stillFallback = null)
            }
            sceneDirectory.mkdirs()
            val output = File(sceneDirectory, "${UUID.randomUUID()}.avif")
            val inputCleanup = SceneNativeCleanup(lease::close)
            val outputCleanup = SceneNativeCleanup(output::delete)
            var transferred = false
            try {
                val encodeStart = System.currentTimeMillis()
                val result = commandExecutor.executeFfmpeg(
                    SceneFfmpegArguments.animatedAvif(
                        input = input,
                        acquiredInputValue = lease.ffmpegValue,
                        range = range,
                        outputFile = output.absolutePath,
                        encoderName = encoderName,
                        tlsCaFile = lease.tlsCaFile,
                    ),
                ) {
                    inputCleanup.nativeFinished()
                    outputCleanup.nativeFinished()
                }
                val encodeElapsed = System.currentTimeMillis() - encodeStart
                when (result) {
                    SceneCommandResult.Failed -> {
                        diagnostics.reject(
                            "gate=encode result=reject reason=ffmpeg-failed " +
                                "encoder=$encoderName elapsedMs=$encodeElapsed",
                        )
                        return@withContext AnkiScreenshotPreparation.Failed(stillFallback = null)
                    }
                    is SceneCommandResult.Success -> Unit
                }
                diagnostics.accept(
                    "gate=encode result=pass encoder=$encoderName " +
                        "elapsedMs=$encodeElapsed bytes=${output.length()}",
                )
                val info = validate(output)
                if (info == null) {
                    diagnostics.reject("gate=validate result=reject reason=not-a-valid-animated-avif")
                    return@withContext AnkiScreenshotPreparation.Failed(stillFallback = null)
                }
                val withinBounds = info.width in 1..MAX_OUTPUT_DIMENSION &&
                    info.height in 1..MAX_OUTPUT_DIMENSION &&
                    info.frameCount in 2..SceneFfmpegArguments.MAX_FRAME_COUNT &&
                    info.totalDurationMillis > 0L
                if (!withinBounds) {
                    diagnostics.reject(
                        "gate=validate result=reject reason=out-of-bounds " +
                            "size=${info.width}x${info.height} frames=${info.frameCount} " +
                            "durationMs=${info.totalDurationMillis}",
                    )
                    return@withContext AnkiScreenshotPreparation.Failed(stillFallback = null)
                }
                diagnostics.accept(
                    "gate=validate result=pass size=${info.width}x${info.height} " +
                        "frames=${info.frameCount} durationMs=${info.totalDurationMillis}",
                )
                val animation = AnkiMediaNaming.sceneFileSource(output)
                transferred = true
                AnkiScreenshotPreparation.Animated(
                    animation = animation,
                    stillFallback = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnostics.reject("gate=pipeline result=reject reason=exception", e)
                AnkiScreenshotPreparation.Failed(stillFallback = null)
            } finally {
                inputCleanup.release()
                if (!transferred) outputCleanup.release()
            }
        }
    }

    private suspend fun isSafe(input: SceneVideoInputSpec): Boolean {
        val lease = inputAcquirer.acquire(input) ?: run {
            diagnostics.reject("gate=probeAcquire result=reject reason=input-lease-unavailable")
            return false
        }
        val cleanup = SceneNativeCleanup(lease::close)
        return try {
            val result = commandExecutor.executeFfprobe(
                SceneFfmpegArguments.videoProbe(input, lease.ffmpegValue, lease.tlsCaFile),
                cleanup::nativeFinished,
            )
            if (result !is SceneCommandResult.Success) {
                diagnostics.reject("gate=ffprobe result=reject reason=ffprobe-failed")
                return false
            }
            val rejection = SceneMediaProbe.rejectionFor(result.output)
            if (rejection != null) {
                diagnostics.reject("gate=probe result=reject reason=${rejection.reason} detail=$rejection")
                return false
            }
            true
        } finally {
            cleanup.release()
        }
    }

    internal companion object {
        private const val SCENE_CACHE_DIRECTORY = "chimahon_scene_capture"
        private const val MAX_OUTPUT_DIMENSION = 640

        fun forTests(
            sceneDirectory: File,
            inputAcquirer: SceneInputAcquirer,
            commandExecutor: SceneCommandExecutor,
            validate: (File) -> AnimatedAvifInfo?,
            av1EncoderName: () -> String? = { TEST_AV1_ENCODER_NAME },
            diagnostics: SceneCaptureDiagnostics = SceneCaptureDiagnostics.None,
        ): AndroidSceneCaptureService {
            return AndroidSceneCaptureService(
                sceneDirectory = sceneDirectory,
                inputAcquirer = inputAcquirer,
                commandExecutor = commandExecutor,
                validate = validate,
                av1EncoderName = av1EncoderName,
                diagnostics = diagnostics,
            )
        }

        private fun platformAv1EncoderName(diagnostics: SceneCaptureDiagnostics): String? {
            val mimeTypes = MimeTypeMap.getSingleton()
            val extensionToMime = mimeTypes.getMimeTypeFromExtension("avif")
            val mimeToExtension = mimeTypes.getExtensionFromMimeType("image/avif")
            val hasMimeMapping = extensionToMime?.equals("image/avif", ignoreCase = true) == true &&
                mimeToExtension?.equals("avif", ignoreCase = true) == true
            if (!hasMimeMapping) {
                diagnostics.reject(
                    "gate=mimeMapping result=reject " +
                        "extensionToMime=$extensionToMime mimeToExtension=$mimeToExtension",
                )
                return null
            }
            diagnostics.accept("gate=mimeMapping result=pass")

            return runCatching {
                val av1Encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .asSequence()
                    .filter(MediaCodecInfo::isEncoder)
                    .filter { info ->
                        info.supportedTypes.any { it.equals(AV1_MIME_TYPE, ignoreCase = true) }
                    }
                    .toList()
                if (av1Encoders.isEmpty()) {
                    diagnostics.reject("gate=encoderInventory result=reject reason=no-av1-encoder-registered")
                    return@runCatching null
                }
                av1Encoders.firstOrNull { info ->
                    runCatching {
                        val capabilities = info.getCapabilitiesForType(AV1_MIME_TYPE)
                        val encoder = capabilities.encoderCapabilities ?: return@runCatching false
                        val video = capabilities.videoCapabilities ?: return@runCatching false
                        val supportsYuv420Planar = capabilities.colorFormats.contains(
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                        )
                        val supportsCq = encoder.isBitrateModeSupported(
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
                        )
                        val qualityInRange = encoder.qualityRange.contains(MEDIACODEC_QUALITY)
                        val sizeAndRate = video.areSizeAndRateSupported(
                            MAX_OUTPUT_DIMENSION,
                            MAX_OUTPUT_DIMENSION,
                            SceneFfmpegArguments.FRAME_RATE,
                        )
                        val accepted = supportsYuv420Planar && supportsCq && qualityInRange && sizeAndRate
                        diagnostics.accept(
                            "gate=encoderCandidate name=${info.name} " +
                                "hardware=${info.isHardwareAccelerated} software=${info.isSoftwareOnly} " +
                                "yuv420Planar=$supportsYuv420Planar cq=$supportsCq " +
                                "quality35=$qualityInRange sizeAndRate=$sizeAndRate " +
                                "colorFormats=${capabilities.colorFormats.joinToString(",")} " +
                                "qualityRange=${encoder.qualityRange} accepted=$accepted",
                        )
                        accepted
                    }.getOrElse { error ->
                        diagnostics.reject("gate=encoderCandidate name=${info.name} result=error", error)
                        false
                    }
                }?.name
            }.getOrElse { error ->
                diagnostics.reject("gate=encoderInventory result=error", error)
                null
            }
        }

        internal const val TEST_AV1_ENCODER_NAME = "test.av1.encoder"
        private const val AV1_MIME_TYPE = "video/av01"
        private const val MEDIACODEC_QUALITY = 35
    }
}
