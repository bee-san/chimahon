package eu.kanade.tachiyomi.ui.player.scene

import chimahon.anki.AnkiMediaNaming
import chimahon.anki.AnkiMediaSource
import chimahon.anki.AnkiScreenshotMode
import chimahon.anki.AnkiScreenshotPreparation
import chimahon.anki.AnkiUnsupportedVideoReason
import chimahon.util.ImageEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable

internal fun interface SceneCaptureService {
    suspend fun prepare(
        request: SceneCaptureRequest,
        onProgress: (SceneCaptureProgress) -> Unit,
    ): AnkiScreenshotPreparation
}

internal fun interface SceneStillFallbackEncoder {
    suspend fun encode(request: SceneCaptureRequest): AnkiMediaSource.Bytes?
}

internal object AndroidSceneStillFallbackEncoder : SceneStillFallbackEncoder {
    override suspend fun encode(request: SceneCaptureRequest): AnkiMediaSource.Bytes? {
        val bitmap = request.fallbackBitmapOrNull() ?: return null
        return withContext(Dispatchers.Default) {
            try {
                ImageEncoder.encode(bitmap)
                    .bytes
                    .takeIf(ByteArray::isNotEmpty)
                    ?.let { bytes ->
                        AnkiMediaSource.Bytes(
                            data = bytes,
                            preferredBaseName = "chimahon_screenshot_${AnkiMediaNaming.sha256(bytes)}",
                            extension = "webp",
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }
}

internal sealed interface PlayerSceneMiningProgress {
    val isBusy: Boolean
    val canCancel: Boolean

    data object Idle : PlayerSceneMiningProgress {
        override val isBusy = false
        override val canCancel = false
    }

    data object CheckingDuplicate : PlayerSceneMiningProgress {
        override val isBusy = true
        override val canCancel = true
    }

    data object PreparingStill : PlayerSceneMiningProgress {
        override val isBusy = true
        override val canCancel = true
    }

    data class GeneratingScene(
        val phase: SceneCaptureProgress,
    ) : PlayerSceneMiningProgress {
        override val isBusy = true
        override val canCancel = true
    }

    data object PreparingSentenceAudio : PlayerSceneMiningProgress {
        override val isBusy = true
        override val canCancel = true
    }

    data object WaitingForCommit : PlayerSceneMiningProgress {
        override val isBusy = true
        override val canCancel = true
    }

    data object Committing : PlayerSceneMiningProgress {
        override val isBusy = true
        override val canCancel = false
    }
}

/**
 * Owns at most one accepted mining job for a player instance.
 *
 * A popup only owns the root bitmap handle. The coordinator holds a lease for the whole accepted
 * Anki operation, so popup dismissal may close the root without recycling underneath FFmpeg or
 * still encoding. Activity/ViewModel teardown cancels only while the writer is still pre-commit.
 */
internal class PlayerSceneMiningCoordinator(
    private val scope: CoroutineScope,
    private val sceneCaptureService: () -> SceneCaptureService,
    private val stillEncoder: SceneStillFallbackEncoder = AndroidSceneStillFallbackEncoder,
    private val sceneTimeoutMillis: Long = DEFAULT_SCENE_TIMEOUT_MILLIS,
) {
    private val lock = Any()
    private var lifecycleState = LifecycleState.IDLE
    private var activeJob: Job? = null
    private var shuttingDown = false
    private val _progress = MutableStateFlow<PlayerSceneMiningProgress>(PlayerSceneMiningProgress.Idle)
    val progress: StateFlow<PlayerSceneMiningProgress> = _progress.asStateFlow()

    fun launch(
        request: SceneCaptureRequest,
        block: suspend () -> Unit,
    ): Boolean = launchWithLease(request::acquireMiningLease, block)

    internal fun launchWithLease(
        acquireLease: () -> Closeable?,
        block: suspend () -> Unit,
    ): Boolean {
        synchronized(lock) {
            if (shuttingDown || activeJob?.isActive == true) return false
            val lease = acquireLease() ?: return false
            lifecycleState = LifecycleState.PRE_COMMIT
            _progress.value = PlayerSceneMiningProgress.CheckingDuplicate

            val job = scope.launch(start = CoroutineStart.LAZY) {
                block()
            }
            activeJob = job
            job.invokeOnCompletion {
                lease.close()
                val closeScope = synchronized(lock) {
                    if (activeJob === job) {
                        activeJob = null
                        lifecycleState = LifecycleState.IDLE
                        _progress.value = PlayerSceneMiningProgress.Idle
                    }
                    shuttingDown
                }
                if (closeScope) {
                    scope.cancel()
                }
            }
            job.start()
            return true
        }
    }

    suspend fun prepareScreenshot(
        request: SceneCaptureRequest,
        mode: AnkiScreenshotMode,
    ): AnkiScreenshotPreparation {
        return when (mode) {
            AnkiScreenshotMode.NONE -> AnkiScreenshotPreparation.Still(null)
            AnkiScreenshotMode.FULL,
            AnkiScreenshotMode.CROP,
            -> {
                setProgressIfPreCommit(PlayerSceneMiningProgress.PreparingStill)
                AnkiScreenshotPreparation.Still(stillEncoder.encode(request))
                    .also { setProgressIfPreCommit(PlayerSceneMiningProgress.WaitingForCommit) }
            }
            AnkiScreenshotMode.ANIMATED_SCENE -> prepareAnimated(request)
        }
    }

    fun markPreparingSentenceAudio() {
        synchronized(lock) {
            if (lifecycleState == LifecycleState.PRE_COMMIT) {
                _progress.value = PlayerSceneMiningProgress.PreparingSentenceAudio
            }
        }
    }

    fun markWaitingForCommit() {
        synchronized(lock) {
            if (lifecycleState == LifecycleState.PRE_COMMIT) {
                _progress.value = PlayerSceneMiningProgress.WaitingForCommit
            }
        }
    }

    fun markCommitStarted() {
        synchronized(lock) {
            when (lifecycleState) {
                LifecycleState.PRE_COMMIT -> {
                    lifecycleState = LifecycleState.COMMITTING
                    _progress.value = PlayerSceneMiningProgress.Committing
                }
                LifecycleState.CANCELLING -> throw CancellationException("Scene mining cancelled before commit")
                LifecycleState.COMMITTING -> Unit
                LifecycleState.IDLE -> throw CancellationException("Scene mining job is no longer active")
            }
        }
    }

    fun cancelPreCommit() {
        val job = synchronized(lock) {
            if (lifecycleState != LifecycleState.PRE_COMMIT) {
                null
            } else {
                lifecycleState = LifecycleState.CANCELLING
                activeJob
            }
        }
        job?.cancel()
    }

    /**
     * Stops accepting work and cancels only a pre-commit operation.
     *
     * A commit that already crossed [markCommitStarted] is allowed to finish in the dedicated
     * coordinator scope. The scope is cancelled after that job reports its real result.
     */
    fun shutdown() {
        val shutdown = synchronized(lock) {
            shuttingDown = true
            when (lifecycleState) {
                LifecycleState.PRE_COMMIT -> {
                    lifecycleState = LifecycleState.CANCELLING
                    ShutdownAction(activeJob, closeScopeNow = false)
                }
                LifecycleState.CANCELLING -> {
                    ShutdownAction(activeJob, closeScopeNow = false)
                }
                LifecycleState.COMMITTING -> {
                    ShutdownAction(jobToCancel = null, closeScopeNow = false)
                }
                LifecycleState.IDLE -> {
                    ShutdownAction(jobToCancel = null, closeScopeNow = true)
                }
            }
        }
        shutdown.jobToCancel?.cancel()
        if (shutdown.closeScopeNow) {
            scope.cancel()
        }
    }

    private suspend fun prepareAnimated(request: SceneCaptureRequest): AnkiScreenshotPreparation {
        val input = request.videoInput
        if (input is SceneVideoInputResolution.Unsupported) {
            setProgressIfPreCommit(PlayerSceneMiningProgress.PreparingStill)
            return AnkiScreenshotPreparation.UnsupportedVideo(
                reason = input.reason.toAnkiReason(),
                stillFallback = stillEncoder.encode(request),
            ).also { setProgressIfPreCommit(PlayerSceneMiningProgress.WaitingForCommit) }
        }
        if (request.resolvedTiming == null) {
            setProgressIfPreCommit(PlayerSceneMiningProgress.PreparingStill)
            return AnkiScreenshotPreparation.GenerationFailed(
                stillFallback = stillEncoder.encode(request),
            ).also { setProgressIfPreCommit(PlayerSceneMiningProgress.WaitingForCommit) }
        }

        setProgressIfPreCommit(
            PlayerSceneMiningProgress.GeneratingScene(SceneCaptureProgress.Preparing),
        )
        return try {
            withTimeout(sceneTimeoutMillis) {
                sceneCaptureService().prepare(request, ::onSceneCaptureProgress)
            }.withFallbackIfMissing(request)
        } catch (_: TimeoutCancellationException) {
            AnkiScreenshotPreparation.GenerationFailed(stillEncoder.encode(request))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AnkiScreenshotPreparation.GenerationFailed(stillEncoder.encode(request))
        }.also {
            setProgressIfPreCommit(PlayerSceneMiningProgress.WaitingForCommit)
        }
    }

    private fun onSceneCaptureProgress(progress: SceneCaptureProgress) {
        setProgressIfPreCommit(PlayerSceneMiningProgress.GeneratingScene(progress))
    }

    private fun setProgressIfPreCommit(progress: PlayerSceneMiningProgress) {
        synchronized(lock) {
            if (lifecycleState == LifecycleState.PRE_COMMIT) {
                _progress.value = progress
            }
        }
    }

    private suspend fun AnkiScreenshotPreparation.withFallbackIfMissing(
        request: SceneCaptureRequest,
    ): AnkiScreenshotPreparation {
        return when (this) {
            is AnkiScreenshotPreparation.Animated -> {
                if (stillFallback != null) this else copy(stillFallback = stillEncoder.encode(request))
            }
            is AnkiScreenshotPreparation.Still -> {
                if (still != null) this else copy(still = stillEncoder.encode(request))
            }
            is AnkiScreenshotPreparation.ExpectedNonVideo -> {
                if (still != null) this else copy(still = stillEncoder.encode(request))
            }
            is AnkiScreenshotPreparation.UnsupportedVideo -> {
                if (stillFallback != null) this else copy(stillFallback = stillEncoder.encode(request))
            }
            is AnkiScreenshotPreparation.GenerationFailed -> {
                if (stillFallback != null) this else copy(stillFallback = stillEncoder.encode(request))
            }
            AnkiScreenshotPreparation.Cancelled -> this
        }
    }

    private fun SceneUnsupportedReason.toAnkiReason(): AnkiUnsupportedVideoReason {
        return when (this) {
            SceneUnsupportedReason.NON_SEEKABLE -> AnkiUnsupportedVideoReason.NON_SEEKABLE
            SceneUnsupportedReason.HDR -> AnkiUnsupportedVideoReason.HDR_OR_TEN_BIT
            SceneUnsupportedReason.TORRENT -> AnkiUnsupportedVideoReason.TORRENT
            SceneUnsupportedReason.ENCRYPTED -> AnkiUnsupportedVideoReason.ENCRYPTED
            SceneUnsupportedReason.DRM -> AnkiUnsupportedVideoReason.DRM
            SceneUnsupportedReason.UNSAFE_INPUT_OPTION -> AnkiUnsupportedVideoReason.UNSAFE_INPUT
            SceneUnsupportedReason.CONTENT_URI_UNAVAILABLE -> AnkiUnsupportedVideoReason.UNAVAILABLE_CONTENT_URI
            SceneUnsupportedReason.NO_VIDEO,
            SceneUnsupportedReason.TRANSIENT_INPUT,
            SceneUnsupportedReason.UNSUPPORTED_SCHEME,
            -> AnkiUnsupportedVideoReason.UNSUPPORTED_INPUT
        }
    }

    private companion object {
        const val DEFAULT_SCENE_TIMEOUT_MILLIS = 60_000L
    }

    private enum class LifecycleState {
        IDLE,
        PRE_COMMIT,
        COMMITTING,
        CANCELLING,
    }

    private data class ShutdownAction(
        val jobToCancel: Job?,
        val closeScopeNow: Boolean,
    )
}
