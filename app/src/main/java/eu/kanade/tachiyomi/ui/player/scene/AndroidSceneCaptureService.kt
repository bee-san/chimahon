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
import java.util.concurrent.atomic.AtomicReference

internal fun interface SceneCaptureService {
    suspend fun prepare(request: SceneCaptureRequest): AnkiScreenshotPreparation
}

internal class AndroidSceneCaptureService private constructor(
    private val sceneDirectory: File,
    private val inputAcquirer: SceneInputAcquirer,
    private val commandExecutor: SceneCommandExecutor,
    private val validate: (File) -> AnimatedAvifInfo?,
    private val av1Encoder: (SceneVideoDimensions) -> Av1EncoderSelection?,
) : SceneCaptureService {
    constructor(context: Context) : this(
        sceneDirectory = File(context.cacheDir, SCENE_CACHE_DIRECTORY),
        inputAcquirer = AndroidSceneInputAcquirer(context),
        commandExecutor = IsolatedSceneCommandExecutor(context),
        validate = AnimatedAvifValidator::validate,
        av1Encoder = ::platformAv1Encoder,
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

        val undeliveredOutput = AtomicReference<SceneNativeCleanup?>()
        return try {
            val result = withContext(Dispatchers.IO) {
                try {
                    val sourceDimensions = inspectSafeVideo(input)
                    if (sourceDimensions == null) {
                        sceneLog { "prepare: input rejected by ffprobe safety check" }
                        return@withContext AnkiScreenshotPreparation.Failed(stillFallback = null)
                    }
                    val encoder = av1Encoder(sourceDimensions)
                        ?: run {
                            sceneLog {
                                "prepare: no usable av1 MediaCodec encoder found for " +
                                    "${sourceDimensions.width}x${sourceDimensions.height}"
                            }
                            return@withContext AnkiScreenshotPreparation.Failed(stillFallback = null)
                        }
                    sceneLog {
                        "prepare: starting, encoder=${encoder.name} source=${sourceDimensions.width}x" +
                            "${sourceDimensions.height} content=${encoder.contentSize.width}x" +
                            "${encoder.contentSize.height} output=${encoder.outputSize.width}x" +
                            "${encoder.outputSize.height} range=${range.startSeconds}..${range.endSeconds} " +
                            "(${range.durationSeconds}s) input=${input.describe()}"
                    }
                    prepareOnIo(
                        input = input,
                        range = range,
                        encoder = encoder,
                        undeliveredOutput = undeliveredOutput,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    sceneLog(throwable = e) { "prepare: threw before scene generation" }
                    AnkiScreenshotPreparation.Failed(stillFallback = null)
                }
            }
            undeliveredOutput.set(null)
            result
        } finally {
            undeliveredOutput.getAndSet(null)?.release()
        }
    }

    private suspend fun prepareOnIo(
        input: SceneVideoInputSpec,
        range: SceneTimeRange,
        encoder: Av1EncoderSelection,
        undeliveredOutput: AtomicReference<SceneNativeCleanup?>,
    ): AnkiScreenshotPreparation {
        sceneDirectory.mkdirs()
        val outputBaseName = UUID.randomUUID().toString()
        val intermediate = File(sceneDirectory, "$outputBaseName.obu")
        val output = File(sceneDirectory, "$outputBaseName.avif")
        val lease = inputAcquirer.acquire(input)
            ?: run {
                sceneLog { "prepare: could not acquire input lease" }
                return AnkiScreenshotPreparation.Failed(stillFallback = null)
            }
        val encodeArguments = try {
            SceneFfmpegArguments.av1MediaCodecPackets(
                input = input,
                acquiredInputValue = lease.ffmpegValue,
                range = range,
                outputFile = intermediate.absolutePath,
                encoderName = encoder.name,
                contentSize = encoder.contentSize,
                outputSize = encoder.outputSize,
                tlsCaFile = lease.tlsCaFile,
            )
        } catch (e: Exception) {
            lease.close()
            sceneLog(throwable = e) { "prepare: could not build AV1 encode arguments" }
            return AnkiScreenshotPreparation.Failed(stillFallback = null)
        }
        val inputCleanup = SceneNativeCleanup(lease::close)
        val intermediateCleanup = SceneNativeCleanup(intermediate::delete)
        var outputCleanup: SceneNativeCleanup? = null
        var transferred = false
        return try {
            val encodeResult = try {
                commandExecutor.executeFfmpeg(encodeArguments) {
                    inputCleanup.nativeFinished()
                    intermediateCleanup.nativeFinished()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                inputCleanup.nativeFinished()
                intermediateCleanup.nativeFinished()
                throw e
            }
            inputCleanup.release()
            when (encodeResult) {
                SceneCommandResult.Failed -> {
                    sceneLog { "prepare: pass 1 (av1_mediacodec encode) failed" }
                    return AnkiScreenshotPreparation.Failed(stillFallback = null)
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
                return AnkiScreenshotPreparation.Failed(stillFallback = null)
            }
            val normalized = MediaCodecAv1StreamNormalizer.normalize(rawPackets)
            if (normalized == null) {
                sceneLog { "prepare: AV1 packet normalization rejected ${rawPackets.size} bytes" }
                return AnkiScreenshotPreparation.Failed(stillFallback = null)
            }
            sceneLog { "prepare: normalized ${rawPackets.size} -> ${normalized.size} bytes" }
            intermediate.writeBytes(normalized)

            val remuxArguments = SceneFfmpegArguments.animatedAvifFromObu(
                inputFile = intermediate.absolutePath,
                outputFile = output.absolutePath,
            )
            val currentOutputCleanup = SceneNativeCleanup(output::delete)
            outputCleanup = currentOutputCleanup
            val finishIntermediateRemuxUse = intermediateCleanup.retainNativeUse()
            val remuxResult = try {
                commandExecutor.executeFfmpeg(remuxArguments) {
                    finishIntermediateRemuxUse()
                    currentOutputCleanup.nativeFinished()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                finishIntermediateRemuxUse()
                currentOutputCleanup.nativeFinished()
                throw e
            }
            when (remuxResult) {
                SceneCommandResult.Failed -> {
                    sceneLog { "prepare: pass 2 (AVIF remux) failed" }
                    return AnkiScreenshotPreparation.Failed(stillFallback = null)
                }
                is SceneCommandResult.Success -> Unit
            }
            val validated = validate(output)
            if (validated == null) {
                sceneLog { "prepare: AVIF structure validation failed, ${output.length()} bytes" }
                return AnkiScreenshotPreparation.Failed(stillFallback = null)
            }
            val info = validated
                .takeIf {
                    it.width == encoder.outputSize.width &&
                        it.height == encoder.outputSize.height &&
                        it.frameCount in 2..SceneFfmpegArguments.MAX_FRAME_COUNT &&
                        it.totalDurationMillis > 0L
                }
                ?: run {
                    sceneLog {
                        "prepare: AVIF outside selection, width=${validated.width} " +
                            "height=${validated.height} expected=${encoder.outputSize.width}x" +
                            "${encoder.outputSize.height} frameCount=${validated.frameCount} " +
                            "(need 2..${SceneFfmpegArguments.MAX_FRAME_COUNT}) " +
                            "durationMs=${validated.totalDurationMillis}"
                    }
                    return AnkiScreenshotPreparation.Failed(stillFallback = null)
                }
            val animation = AnkiMediaNaming.sceneFileSource(output)
            val prepared = AnkiScreenshotPreparation.Animated(
                animation = animation,
                stillFallback = null,
            )
            undeliveredOutput.set(currentOutputCleanup)
            transferred = true
            sceneLog {
                "prepare: success, ${info.frameCount} frames ${info.width}x${info.height} " +
                    "${info.totalDurationMillis}ms ${output.length()} bytes"
            }
            prepared
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sceneLog(throwable = e) { "prepare: threw during scene generation" }
            return AnkiScreenshotPreparation.Failed(stillFallback = null)
        } finally {
            inputCleanup.release()
            intermediateCleanup.release()
            if (!transferred) {
                outputCleanup?.release() ?: output.delete()
            }
        }
    }

    private suspend fun inspectSafeVideo(input: SceneVideoInputSpec): SceneVideoDimensions? {
        val lease = inputAcquirer.acquire(input) ?: run {
            sceneLog { "isSafe: could not acquire input lease for probe" }
            return null
        }
        val arguments = try {
            SceneFfmpegArguments.videoProbe(input, lease.ffmpegValue, lease.tlsCaFile)
        } catch (e: Exception) {
            lease.close()
            sceneLog(throwable = e) { "isSafe: could not build ffprobe arguments" }
            return null
        }
        val cleanup = SceneNativeCleanup(lease::close)
        return try {
            val result = try {
                commandExecutor.executeFfprobe(arguments, cleanup::nativeFinished)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cleanup.nativeFinished()
                throw e
            }
            when (result) {
                SceneCommandResult.Failed -> {
                    sceneLog { "isSafe: ffprobe failed to run" }
                    null
                }
                is SceneCommandResult.Success -> {
                    // An absent pix_fmt and an HDR rejection both return false, so print the output.
                    SceneMediaProbe.inspectVideo(result.output).also { inspected ->
                        if (inspected == null) {
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
        private const val MAX_INTERMEDIATE_BYTES = 12L * 1024L * 1024L

        fun forTests(
            sceneDirectory: File,
            inputAcquirer: SceneInputAcquirer,
            commandExecutor: SceneCommandExecutor,
            validate: (File) -> AnimatedAvifInfo?,
            av1Encoder: (SceneVideoDimensions) -> Av1EncoderSelection? = ::testAv1Encoder,
        ): AndroidSceneCaptureService {
            return AndroidSceneCaptureService(
                sceneDirectory = sceneDirectory,
                inputAcquirer = inputAcquirer,
                commandExecutor = commandExecutor,
                validate = validate,
                av1Encoder = av1Encoder,
            )
        }

        private fun platformAv1Encoder(source: SceneVideoDimensions): Av1EncoderSelection? {
            val mimeTypes = MimeTypeMap.getSingleton()
            val hasMimeMapping = mimeTypes.getMimeTypeFromExtension("avif")
                ?.equals("image/avif", ignoreCase = true) == true &&
                mimeTypes.getExtensionFromMimeType("image/avif")
                    ?.equals("avif", ignoreCase = true) == true
            if (!hasMimeMapping) return null

            return runCatching {
                val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .asSequence()
                    .filter(MediaCodecInfo::isEncoder)
                    .filter { info ->
                        info.supportedTypes.any { it.equals(AV1_MIME_TYPE, ignoreCase = true) }
                    }
                    .mapNotNull { info ->
                        runCatching {
                            val capabilities = info.getCapabilitiesForType(AV1_MIME_TYPE)
                            val encoder = capabilities.encoderCapabilities ?: return@runCatching null
                            val video = capabilities.videoCapabilities ?: return@runCatching null
                            Av1EncoderCandidate(
                                name = info.name,
                                supportsPlanarYuv420 = capabilities.colorFormats.contains(
                                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                                ),
                                supportsConstantQuality = encoder.isBitrateModeSupported(
                                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
                                ),
                                supportsTargetQuality = encoder.qualityRange.contains(MEDIACODEC_QUALITY),
                                widthAlignment = video.widthAlignment,
                                heightAlignment = video.heightAlignment,
                                minimumWidth = video.supportedWidths.lower,
                                minimumHeight = video.supportedHeights.lower,
                                maximumWidth = video.supportedWidths.upper,
                                maximumHeight = video.supportedHeights.upper,
                                supportedWidthsForHeight = { height ->
                                    runCatching {
                                        video.getSupportedWidthsFor(height)
                                    }.getOrNull()?.let { range ->
                                        range.lower..range.upper
                                    }
                                },
                                supportsSizeAndRate = { size, rate ->
                                    video.areSizeAndRateSupported(size.width, size.height, rate)
                                },
                            )
                        }.getOrNull()
                    }
                selectAv1Encoder(
                    source = source,
                    candidates = candidates,
                    frameRate = SceneFfmpegArguments.FRAME_RATE,
                )
            }.getOrNull()
        }

        private fun testAv1Encoder(source: SceneVideoDimensions): Av1EncoderSelection? {
            return selectAv1Encoder(
                source = source,
                candidates = sequenceOf(
                    Av1EncoderCandidate(
                        name = TEST_AV1_ENCODER_NAME,
                        supportsPlanarYuv420 = true,
                        supportsConstantQuality = true,
                        supportsTargetQuality = true,
                        widthAlignment = SCENE_PIXEL_ALIGNMENT,
                        heightAlignment = SCENE_PIXEL_ALIGNMENT,
                        supportsSizeAndRate = { _, _ -> true },
                    ),
                ),
            )
        }

        internal const val TEST_AV1_ENCODER_NAME = "test.av1.encoder"
        private const val AV1_MIME_TYPE = "video/av01"
        private const val MEDIACODEC_QUALITY = 35
    }
}
