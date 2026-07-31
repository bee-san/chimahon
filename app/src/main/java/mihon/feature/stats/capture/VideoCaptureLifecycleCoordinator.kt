package mihon.feature.stats.capture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.immersion.service.FinalizeReason

/**
 * Owns the hand-off between episode-scoped video capture adapters.
 *
 * A new adapter must not reach the shared recorder until the previous adapter has drained and
 * finalized. Clearing [currentAdapter] before awaiting also prevents player callbacks that arrive
 * during the hand-off from being appended to the old episode.
 */
internal class VideoCaptureLifecycleCoordinator {

    private val transitionMutex = Mutex()
    private val stateLock = Any()
    private var activeCapture: ActiveCapture? = null
    private var generation = 0L

    val currentAdapter: VideoCaptureAdapter?
        get() = synchronized(stateLock) { activeCapture?.adapter }

    val currentEpisodeId: Long?
        get() = synchronized(stateLock) { activeCapture?.episodeId }

    fun withCurrentAdapter(
        adapter: VideoCaptureAdapter,
        block: () -> Unit,
    ): Boolean = synchronized(stateLock) {
        if (activeCapture?.adapter !== adapter) {
            false
        } else {
            block()
            true
        }
    }

    suspend fun switchEpisode(
        episodeId: Long,
        createAdapter: () -> VideoCaptureAdapter,
    ): VideoCaptureAdapter? = transitionMutex.withLock transition@{
        require(episodeId >= 0) { "Episode ID cannot be negative" }
        synchronized(stateLock) {
            activeCapture?.takeIf { it.episodeId == episodeId }
        }?.let { return@transition it.adapter }

        val (previous, transitionGeneration) = synchronized(stateLock) {
            generation += 1
            val previous = activeCapture
            activeCapture = null
            previous to generation
        }
        previous?.adapter?.finalize(FinalizeReason.TITLE_CHANGED)?.await()

        val next = createAdapter()
        val accepted = synchronized(stateLock) {
            if (generation != transitionGeneration) {
                false
            } else {
                activeCapture = ActiveCapture(episodeId, next)
                true
            }
        }
        if (!accepted) {
            next.finalize(FinalizeReason.TITLE_CHANGED)
            null
        } else {
            next
        }
    }

    fun finalizeCurrent(reason: FinalizeReason): CompletableDeferred<Unit>? {
        val previous = synchronized(stateLock) {
            generation += 1
            activeCapture.also { activeCapture = null }
        }
        return previous?.adapter?.finalize(reason)
    }

    private data class ActiveCapture(
        val episodeId: Long,
        val adapter: VideoCaptureAdapter,
    )
}
