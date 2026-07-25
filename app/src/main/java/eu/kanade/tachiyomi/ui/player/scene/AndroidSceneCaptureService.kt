package eu.kanade.tachiyomi.ui.player.scene

import android.content.Context
import chimahon.anki.AnkiMediaFileOwnership
import chimahon.anki.AnkiMediaSource
import chimahon.anki.AnkiScreenshotPreparation
import chimahon.anki.AnkiUnsupportedVideoReason
import kotlinx.coroutines.CancellationException

internal fun interface SceneCaptureRunner {
    suspend fun capture(request: SceneCapturePipelineRequest): SceneCaptureResult
}

/**
 * Bridges the player-owned immutable request to the Android scene pipeline.
 *
 * A successful pipeline file is detached exactly once and handed to the Anki writer, which then
 * deletes it after the store attempt. Every abandoned pipeline output is closed here.
 */
internal class AndroidSceneCaptureService private constructor(
    private val runnerFactory: ((SceneCaptureProgress) -> Unit) -> SceneCaptureRunner,
    private val recordMetrics: (SceneCaptureMetrics) -> Unit,
) : SceneCaptureService {
    constructor(context: Context) : this(
        runnerFactory = { onProgress ->
            val pipeline = SceneCapturePipeline.create(
                context = context.applicationContext,
                onProgress = onProgress,
            )
            SceneCaptureRunner(pipeline::capture)
        },
        recordMetrics = SceneCaptureMetricsStore(context.applicationContext)::record,
    )

    override suspend fun prepare(
        request: SceneCaptureRequest,
        onProgress: (SceneCaptureProgress) -> Unit,
    ): AnkiScreenshotPreparation {
        val input = (request.videoInput as? SceneVideoInputResolution.Supported)?.input
            ?: return AnkiScreenshotPreparation.GenerationFailed(stillFallback = null)
        val range = request.resolvedTiming?.animationRange
            ?: return AnkiScreenshotPreparation.GenerationFailed(stillFallback = null)

        return try {
            when (
                val result = runnerFactory(onProgress).capture(
                    SceneCapturePipelineRequest(
                        input = input,
                        animationRange = range,
                    ),
                )
            ) {
                is SceneCaptureResult.Success -> {
                    runCatching { recordMetrics(result.metrics) }
                    result.toPreparation()
                }
                is SceneCaptureResult.Unsupported -> {
                    AnkiScreenshotPreparation.UnsupportedVideo(
                        reason = result.reason.toAnkiReason(),
                        stillFallback = null,
                    )
                }
                is SceneCaptureResult.Failure -> {
                    AnkiScreenshotPreparation.GenerationFailed(stillFallback = null)
                }
                SceneCaptureResult.Cancelled -> AnkiScreenshotPreparation.Cancelled
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AnkiScreenshotPreparation.GenerationFailed(stillFallback = null)
        }
    }

    private fun SceneCaptureResult.Success.toPreparation(): AnkiScreenshotPreparation {
        val detached = output.takeFile()
        output.close()
        if (detached == null) {
            return AnkiScreenshotPreparation.GenerationFailed(stillFallback = null)
        }

        return try {
            AnkiScreenshotPreparation.Animated(
                animation = AnkiMediaSource.FileSource(
                    file = detached,
                    preferredBaseName = output.preferredBaseName,
                    extension = "webp",
                    ownership = AnkiMediaFileOwnership.DELETE_AFTER_STORE_ATTEMPT,
                ),
                stillFallback = null,
            )
        } catch (e: Exception) {
            detached.delete()
            throw e
        }
    }

    private fun SceneCaptureUnsupportedReason.toAnkiReason(): AnkiUnsupportedVideoReason {
        return when (this) {
            SceneCaptureUnsupportedReason.CONTENT_URI_UNAVAILABLE -> {
                AnkiUnsupportedVideoReason.UNAVAILABLE_CONTENT_URI
            }
            SceneCaptureUnsupportedReason.DRM -> AnkiUnsupportedVideoReason.DRM
            SceneCaptureUnsupportedReason.ENCRYPTED -> AnkiUnsupportedVideoReason.ENCRYPTED
            SceneCaptureUnsupportedReason.HDR_OR_TEN_BIT -> AnkiUnsupportedVideoReason.HDR_OR_TEN_BIT
            SceneCaptureUnsupportedReason.NON_SEEKABLE -> AnkiUnsupportedVideoReason.NON_SEEKABLE
        }
    }

    internal companion object {
        fun forTests(
            recordMetrics: (SceneCaptureMetrics) -> Unit = {},
            runnerFactory: ((SceneCaptureProgress) -> Unit) -> SceneCaptureRunner,
        ): AndroidSceneCaptureService {
            return AndroidSceneCaptureService(runnerFactory, recordMetrics)
        }
    }
}
