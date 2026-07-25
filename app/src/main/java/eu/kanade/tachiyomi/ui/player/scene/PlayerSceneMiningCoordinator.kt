package eu.kanade.tachiyomi.ui.player.scene

import chimahon.anki.AnkiMediaSource
import chimahon.anki.AnkiScreenshotMode
import chimahon.anki.AnkiScreenshotPreparation
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
import java.security.MessageDigest

internal fun interface SceneStillFallbackEncoder {
    suspend fun encode(request: SceneCaptureRequest): AnkiMediaSource.Bytes?
}

internal object AndroidSceneStillFallbackEncoder : SceneStillFallbackEncoder {
    override suspend fun encode(request: SceneCaptureRequest): AnkiMediaSource.Bytes? {
        val bitmap = request.fallbackBitmapOrNull() ?: return null
        return withContext(Dispatchers.Default) {
            try {
                val bytes = ImageEncoder.encode(bitmap).bytes.takeIf(ByteArray::isNotEmpty) ?: return@withContext null
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                AnkiMediaSource.Bytes(
                    data = bytes,
                    preferredBaseName = "chimahon_screenshot_$digest",
                    extension = "webp",
                )
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

    data object Preparing : PlayerSceneMiningProgress {
        override val isBusy = true
        override val canCancel = true
    }

    data object Committing : PlayerSceneMiningProgress {
        override val isBusy = true
        override val canCancel = false
    }
}

/**
 * Owns at most one accepted mining job. Cancellation is allowed only until the Anki commit starts.
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
            _progress.value = PlayerSceneMiningProgress.Preparing
            val job = scope.launch(start = CoroutineStart.LAZY) { block() }
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
                if (closeScope) scope.cancel()
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
            -> AnkiScreenshotPreparation.Still(stillEncoder.encode(request))
            AnkiScreenshotMode.ANIMATED_SCENE -> prepareAnimated(request)
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

    fun shutdown() {
        val action = synchronized(lock) {
            shuttingDown = true
            when (lifecycleState) {
                LifecycleState.PRE_COMMIT -> {
                    lifecycleState = LifecycleState.CANCELLING
                    ShutdownAction(activeJob, closeScopeNow = false)
                }
                LifecycleState.CANCELLING -> ShutdownAction(activeJob, closeScopeNow = false)
                LifecycleState.COMMITTING -> ShutdownAction(jobToCancel = null, closeScopeNow = false)
                LifecycleState.IDLE -> ShutdownAction(jobToCancel = null, closeScopeNow = true)
            }
        }
        action.jobToCancel?.cancel()
        if (action.closeScopeNow) scope.cancel()
    }

    private suspend fun prepareAnimated(request: SceneCaptureRequest): AnkiScreenshotPreparation {
        if (
            request.videoInput == null ||
            request.resolvedTiming == null
        ) {
            return AnkiScreenshotPreparation.Failed(stillEncoder.encode(request))
        }
        val prepared = try {
            withTimeout(sceneTimeoutMillis) {
                sceneCaptureService().prepare(request)
            }
        } catch (_: TimeoutCancellationException) {
            return AnkiScreenshotPreparation.Failed(stillEncoder.encode(request))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return AnkiScreenshotPreparation.Failed(stillEncoder.encode(request))
        }
        if (prepared !is AnkiScreenshotPreparation.Animated) {
            return prepared.withStillFallback(request)
        }

        var transferred = false
        return try {
            prepared.withStillFallback(request).also { transferred = true }
        } finally {
            if (!transferred) prepared.animation.file.delete()
        }
    }

    private suspend fun AnkiScreenshotPreparation.withStillFallback(
        request: SceneCaptureRequest,
    ): AnkiScreenshotPreparation {
        return when (this) {
            is AnkiScreenshotPreparation.Animated -> {
                if (stillFallback != null) this else copy(stillFallback = stillEncoder.encode(request))
            }
            is AnkiScreenshotPreparation.Still -> {
                if (still != null) this else copy(still = stillEncoder.encode(request))
            }
            is AnkiScreenshotPreparation.Failed -> {
                if (stillFallback != null) this else copy(stillFallback = stillEncoder.encode(request))
            }
        }
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

    private companion object {
        const val DEFAULT_SCENE_TIMEOUT_MILLIS = 60_000L
    }
}
