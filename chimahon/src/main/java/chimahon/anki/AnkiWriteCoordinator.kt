package chimahon.anki

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal sealed interface AnkiWriteTarget {
    data object Add : AnkiWriteTarget
    data class Overwrite(val noteId: Long) : AnkiWriteTarget
}

internal sealed interface AnkiWritePreparation<out T> {
    data class Ready<T>(val value: T) : AnkiWritePreparation<T>
    data object Cancelled : AnkiWritePreparation<Nothing>
}

internal class AnkiCommitTransition(
    private val parentJob: Job?,
) {
    private var started = false

    suspend fun commit(
        onStarted: () -> Unit,
        transaction: suspend () -> AnkiResult,
    ): AnkiResult {
        check(!started) { "Anki commit transition may only be entered once" }
        return withContext(NonCancellable) {
            // Re-check the owning job after entering the non-cancellable context.
            // This closes the suspension window between cancellable commit setup
            // and the adjacent commit-start callback/provider mutation.
            parentJob?.ensureActive()
            started = true
            onStarted()
            transaction()
        }
    }
}

/**
 * A bounded, stable lock registry. Hash collisions only serialize unrelated
 * writes; unlike removable keyed mutexes, queued waiters can never become
 * detached from the registry.
 */
internal class StripedAnkiWriteLocks(stripeCount: Int = 64) {
    private val stripes: Array<Mutex>

    init {
        require(stripeCount > 0)
        stripes = Array(stripeCount) { Mutex() }
    }

    fun forKey(key: String): Mutex {
        val index = (key.hashCode() and Int.MAX_VALUE) % stripes.size
        return stripes[index]
    }
}

internal class AnkiWriteCoordinator(
    private val locks: StripedAnkiWriteLocks = StripedAnkiWriteLocks(),
) {
    suspend fun <T> execute(
        lockKey: String,
        duplicateCheck: Boolean,
        duplicateAction: String,
        forceOpen: Boolean,
        findExisting: suspend () -> List<Long>,
        prepareWrite: suspend (AnkiWriteTarget) -> AnkiWritePreparation<T>,
        commitWrite: suspend (AnkiWriteTarget, T, AnkiCommitTransition) -> AnkiResult,
    ): AnkiResult {
        return locks.forKey(lockKey).withLock {
            val target = resolveTarget(
                duplicateCheck = duplicateCheck,
                duplicateAction = duplicateAction,
                forceOpen = forceOpen,
                findExisting = findExisting,
            )
            when (target) {
                is ResolvedTarget.Return -> target.result
                is ResolvedTarget.Write -> {
                    currentCoroutineContext().ensureActive()
                    when (val preparation = prepareWrite(target.target)) {
                        AnkiWritePreparation.Cancelled -> AnkiResult.Cancelled
                        is AnkiWritePreparation.Ready -> {
                            currentCoroutineContext().ensureActive()
                            val transition = AnkiCommitTransition(currentCoroutineContext()[Job])
                            // commitWrite may perform cancellable, reversible setup.
                            // It must enter transition.commit immediately before its
                            // first irreversible Anki provider mutation.
                            commitWrite(target.target, preparation.value, transition)
                        }
                    }
                }
            }
        }
    }

    private suspend fun resolveTarget(
        duplicateCheck: Boolean,
        duplicateAction: String,
        forceOpen: Boolean,
        findExisting: suspend () -> List<Long>,
    ): ResolvedTarget {
        if (!duplicateCheck && !forceOpen) {
            return ResolvedTarget.Write(AnkiWriteTarget.Add)
        }

        val existing = findExisting().firstOrNull()
            ?: return ResolvedTarget.Write(AnkiWriteTarget.Add)
        if (forceOpen) {
            return ResolvedTarget.Return(AnkiResult.OpenCard(existing))
        }
        return when (duplicateAction) {
            "prevent" -> ResolvedTarget.Return(AnkiResult.CardExists(existing))
            "open" -> ResolvedTarget.Return(AnkiResult.OpenCard(existing))
            "overwrite" -> ResolvedTarget.Write(AnkiWriteTarget.Overwrite(existing))
            else -> ResolvedTarget.Write(AnkiWriteTarget.Add)
        }
    }

    private sealed interface ResolvedTarget {
        data class Return(val result: AnkiResult) : ResolvedTarget
        data class Write(val target: AnkiWriteTarget) : ResolvedTarget
    }
}
