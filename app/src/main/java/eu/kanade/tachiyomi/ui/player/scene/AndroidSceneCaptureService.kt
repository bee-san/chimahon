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
) : SceneCaptureService {
    constructor(context: Context) : this(
        sceneDirectory = File(context.cacheDir, SCENE_CACHE_DIRECTORY),
        inputAcquirer = AndroidSceneInputAcquirer(context),
        commandExecutor = FfmpegKitSceneCommandExecutor(),
        validate = AnimatedAvifValidator::validate,
        av1EncoderName = ::platformAv1EncoderName,
    )

    override suspend fun prepare(request: SceneCaptureRequest): AnkiScreenshotPreparation {
        val input = request.videoInput ?: run {
            sceneLog { "prepare: videoInput was null" }
            return AnkiScreenshotPreparation.Failed(stillFallback = null)
        }
        val range = request.resolvedTiming?.animationRange
            ?: run {
                sceneLog { "prepare: resolvedTiming.animationRange was null" }
                return AnkiScreenshotPreparation.Failed(stillFallback = null)
            }
        val encoderName = av1EncoderName()
        if (encoderName.isNullOrBlank()) {
            sceneLog { "prepare: no usable av1 MediaCodec encoder found" }
            return AnkiScreenshotPreparation.Failed(stillFallback = null)
        }
        sceneLog {
            "prepare: starting, encoder=$encoderName range=${range.startSeconds}..${range.endSeconds} " +
                "(${range.durationSeconds}s) input=${input.describe()}"
        }

        return withContext(Dispatchers.IO) {
            if (!isSafe(input)) {
                sceneLog { "prepare: input rejected by ffprobe safety check" }
                return@withContext AnkiScreenshotPreparation.Failed(stillFallback = null)
            }
            val lease = inputAcquirer.acquire(input)
                ?: run {
                    sceneLog { "prepare: could not acquire input lease" }
                    return@withContext AnkiScreenshotPreparation.Failed(stillFallback = null)
                }
            sceneDirectory.mkdirs()
            val outputBaseName = UUID.randomUUID().toString()
            val intermediate = File(sceneDirectory, "$outputBaseName.obu")
            val output = File(sceneDirectory, "$outputBaseName.avif")
            val inputCleanup = SceneNativeCleanup(lease::close)
            val intermediateCleanup = SceneNativeCleanup(intermediate::delete)
            var outputCleanup: SceneNativeCleanup? = null
            var transferred = false
            try {
                val encodeResult = commandExecutor.executeFfmpeg(
                    SceneFfmpegArguments.av1MediaCodecPackets(
                        input = input,
                        acquiredInputValue = lease.ffmpegValue,
                        range = range,
                        outputFile = intermediate.absolutePath,
                        encoderName = encoderName,
                        tlsCaFile = lease.tlsCaFile,
                    ),
                ) {
                    inputCleanup.nativeFinished()
                    intermediateCleanup.nativeFinished()
                }
                inputCleanup.release()
                when (encodeResult) {
                    SceneCommandResult.Failed -> {
                        sceneLog { "prepare: pass 1 (av1_mediacodec encode) failed" }
                        return@withContext AnkiScreenshotPreparation.Failed(stillFallback = null)
                    }
                    is SceneCommandResult.Success -> Unit
                }
                val rawPackets = intermediate
                    .takeIf { it.isFile && it.length() in 1..MAX_INTERMEDIATE_BYTES }
                    ?.readBytes()
                if (rawPackets == null) {
                    sceneLog {
                        "prepare: intermediate unusable, isFile=${intermediate.isFile} " +
                            "length=${intermediate.length()} max=$MAX_INTERMEDIATE_BYTES"
                    }
                    return@withContext AnkiScreenshotPreparation.Failed(stillFallback = null)
                }
                val normalized = MediaCodecAv1StreamNormalizer.normalize(rawPackets)
                if (normalized == null) {
                    sceneLog { "prepare: AV1 packet normalization rejected ${rawPackets.size} bytes" }
                    return@withContext AnkiScreenshotPreparation.Failed(stillFallback = null)
                }
                sceneLog { "prepare: normalized ${rawPackets.size} -> ${normalized.size} bytes" }
                intermediate.writeBytes(normalized)

                val currentOutputCleanup = SceneNativeCleanup(output::delete)
                outputCleanup = currentOutputCleanup
                val finishIntermediateRemuxUse = intermediateCleanup.retainNativeUse()
                val remuxResult = commandExecutor.executeFfmpeg(
                    SceneFfmpegArguments.animatedAvifFromObu(
                        inputFile = intermediate.absolutePath,
                        outputFile = output.absolutePath,
                    ),
                ) {
                    finishIntermediateRemuxUse()
                    currentOutputCleanup.nativeFinished()
                }
                when (remuxResult) {
                    SceneCommandResult.Failed -> {
                        sceneLog { "prepare: pass 2 (AVIF remux) failed" }
                        return@withContext AnkiScreenshotPreparation.Failed(stillFallback = null)
                    }
                    is SceneCommandResult.Success -> Unit
                }
                val validated = validate(output)
                if (validated == null) {
                    sceneLog { "prepare: AVIF structure validation failed, ${output.length()} bytes" }
                    return@withContext AnkiScreenshotPreparation.Failed(stillFallback = null)
                }
                val info = validated
                    .takeIf {
                        it.width in 1..MAX_OUTPUT_DIMENSION &&
                            it.height in 1..MAX_OUTPUT_DIMENSION &&
                            it.frameCount in 2..SceneFfmpegArguments.MAX_FRAME_COUNT &&
                            it.totalDurationMillis > 0L
                    }
                    ?: run {
                        sceneLog {
                            "prepare: AVIF outside bounds, width=${validated.width} height=${validated.height} " +
                                "(max $MAX_OUTPUT_DIMENSION) frameCount=${validated.frameCount} " +
                                "(need 2..${SceneFfmpegArguments.MAX_FRAME_COUNT}) " +
                                "durationMs=${validated.totalDurationMillis}"
                        }
                        return@withContext AnkiScreenshotPreparation.Failed(stillFallback = null)
                    }
                val animation = AnkiMediaNaming.sceneFileSource(output)
                transferred = true
                sceneLog {
                    "prepare: success, ${info.frameCount} frames ${info.width}x${info.height} " +
                        "${info.totalDurationMillis}ms ${output.length()} bytes"
                }
                AnkiScreenshotPreparation.Animated(
                    animation = animation,
                    stillFallback = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sceneLog(throwable = e) { "prepare: threw during scene generation" }
                AnkiScreenshotPreparation.Failed(stillFallback = null)
            } finally {
                inputCleanup.release()
                intermediateCleanup.release()
                if (!transferred) {
                    outputCleanup?.release() ?: output.delete()
                }
            }
        }
    }

    private suspend fun isSafe(input: SceneVideoInputSpec): Boolean {
        val lease = inputAcquirer.acquire(input) ?: run {
            sceneLog { "isSafe: could not acquire input lease for probe" }
            return false
        }
        val cleanup = SceneNativeCleanup(lease::close)
        return try {
            val result = commandExecutor.executeFfprobe(
                SceneFfmpegArguments.videoProbe(input, lease.ffmpegValue, lease.tlsCaFile),
                cleanup::nativeFinished,
            )
            when (result) {
                SceneCommandResult.Failed -> {
                    sceneLog { "isSafe: ffprobe failed to run" }
                    false
                }
                is SceneCommandResult.Success -> {
                    // An absent pix_fmt and an HDR rejection both return false, so print the output.
                    SceneMediaProbe.inspect(result.output).also { accepted ->
                        if (!accepted) {
                            val output = redactSceneLogLine(result.output)
                            sceneLog { "isSafe: probe rejected input, ffprobe output=<<<$output>>>" }
                        }
                    }
                }
            }
        } finally {
            cleanup.release()
        }
    }

    internal companion object {
        private const val SCENE_CACHE_DIRECTORY = "chimahon_scene_capture"
        private const val MAX_OUTPUT_DIMENSION = 640
        private const val MAX_INTERMEDIATE_BYTES = 12L * 1024L * 1024L

        fun forTests(
            sceneDirectory: File,
            inputAcquirer: SceneInputAcquirer,
            commandExecutor: SceneCommandExecutor,
            validate: (File) -> AnimatedAvifInfo?,
            av1EncoderName: () -> String? = { TEST_AV1_ENCODER_NAME },
        ): AndroidSceneCaptureService {
            return AndroidSceneCaptureService(
                sceneDirectory = sceneDirectory,
                inputAcquirer = inputAcquirer,
                commandExecutor = commandExecutor,
                validate = validate,
                av1EncoderName = av1EncoderName,
            )
        }

        private fun platformAv1EncoderName(): String? {
            val mimeTypes = MimeTypeMap.getSingleton()
            val hasMimeMapping = mimeTypes.getMimeTypeFromExtension("avif")
                ?.equals("image/avif", ignoreCase = true) == true &&
                mimeTypes.getExtensionFromMimeType("image/avif")
                    ?.equals("avif", ignoreCase = true) == true
            if (!hasMimeMapping) return null

            return runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .asSequence()
                    .filter(MediaCodecInfo::isEncoder)
                    .filter { info ->
                        info.supportedTypes.any { it.equals(AV1_MIME_TYPE, ignoreCase = true) }
                    }
                    .firstOrNull { info ->
                        runCatching {
                            val capabilities = info.getCapabilitiesForType(AV1_MIME_TYPE)
                            val encoder = capabilities.encoderCapabilities ?: return@runCatching false
                            val video = capabilities.videoCapabilities ?: return@runCatching false
                            val supportsYuv420Planar = capabilities.colorFormats.contains(
                                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                            )
                            supportsYuv420Planar &&
                                encoder.isBitrateModeSupported(
                                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
                                ) &&
                                encoder.qualityRange.contains(MEDIACODEC_QUALITY) &&
                                video.areSizeAndRateSupported(
                                    MAX_OUTPUT_DIMENSION,
                                    MAX_OUTPUT_DIMENSION,
                                    SceneFfmpegArguments.FRAME_RATE,
                                )
                        }.getOrDefault(false)
                    }
                    ?.name
            }.getOrNull()
        }

        internal const val TEST_AV1_ENCODER_NAME = "test.av1.encoder"
        private const val AV1_MIME_TYPE = "video/av01"
        private const val MEDIACODEC_QUALITY = 35
    }
}
