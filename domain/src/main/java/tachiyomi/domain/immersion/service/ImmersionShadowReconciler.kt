package tachiyomi.domain.immersion.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tachiyomi.domain.immersion.model.EventId
import tachiyomi.domain.immersion.model.SessionId
import kotlin.math.absoluteValue

data class ImmersionShadowTotals(
    val activeDurationMillis: Long,
    val netCharacters: Long,
) {
    init {
        require(activeDurationMillis >= 0) { "Active duration cannot be negative" }
    }
}

sealed interface ReadingTimeTolerance {
    data object Exact : ReadingTimeTolerance

    data class DocumentedPolicyChange(
        val maximumDiscrepancyMillis: Long,
        val policy: ActiveTimePolicyDifference,
    ) : ReadingTimeTolerance {
        init {
            require(maximumDiscrepancyMillis >= 0) { "Maximum discrepancy cannot be negative" }
        }
    }
}

enum class ActiveTimePolicyDifference {
    BACKGROUND_EXCLUDED,
    IDLE_EXCLUDED,
    BUFFERING_EXCLUDED,
}

data class ImmersionShadowIdentity(
    val sessionIds: List<SessionId>,
    val eventIds: List<EventId>,
)

sealed interface ImmersionShadowResult {
    data object Matched : ImmersionShadowResult

    data class Diverged(
        val timeDiscrepancyMillis: Long,
        val netCharacterDiscrepancy: Long,
        val duplicateSessionIds: Set<SessionId>,
        val duplicateEventIds: Set<EventId>,
    ) : ImmersionShadowResult
}

object ImmersionShadowReconciler {
    fun reconcile(
        recorded: ImmersionShadowTotals,
        legacy: ImmersionShadowTotals,
        identity: ImmersionShadowIdentity,
        readingTimeTolerance: ReadingTimeTolerance,
    ): ImmersionShadowResult {
        val timeDiscrepancy = recorded.activeDurationMillis - legacy.activeDurationMillis
        val netDiscrepancy = recorded.netCharacters - legacy.netCharacters
        val duplicateSessionIds = identity.sessionIds.duplicates()
        val duplicateEventIds = identity.eventIds.duplicates()
        val allowedTimeDiscrepancy = when (readingTimeTolerance) {
            ReadingTimeTolerance.Exact -> 0
            is ReadingTimeTolerance.DocumentedPolicyChange ->
                readingTimeTolerance.maximumDiscrepancyMillis
        }
        return if (
            timeDiscrepancy.absoluteValue <= allowedTimeDiscrepancy &&
            netDiscrepancy == 0L &&
            duplicateSessionIds.isEmpty() &&
            duplicateEventIds.isEmpty()
        ) {
            ImmersionShadowResult.Matched
        } else {
            ImmersionShadowResult.Diverged(
                timeDiscrepancyMillis = timeDiscrepancy,
                netCharacterDiscrepancy = netDiscrepancy,
                duplicateSessionIds = duplicateSessionIds,
                duplicateEventIds = duplicateEventIds,
            )
        }
    }
}

data class ImmersionShadowDiagnostic(
    val scope: ImmersionShadowScope,
    val result: ImmersionShadowResult,
)

enum class ImmersionShadowScope {
    SESSION,
    DAY,
}

class ImmersionShadowMonitor {
    private val mutableLastDiagnostic = MutableStateFlow<ImmersionShadowDiagnostic?>(null)
    val lastDiagnostic: StateFlow<ImmersionShadowDiagnostic?> = mutableLastDiagnostic.asStateFlow()

    fun compare(
        scope: ImmersionShadowScope,
        recorded: ImmersionShadowTotals,
        legacy: ImmersionShadowTotals,
        identity: ImmersionShadowIdentity,
        readingTimeTolerance: ReadingTimeTolerance,
    ): ImmersionShadowResult {
        val result = ImmersionShadowReconciler.reconcile(
            recorded = recorded,
            legacy = legacy,
            identity = identity,
            readingTimeTolerance = readingTimeTolerance,
        )
        mutableLastDiagnostic.value = ImmersionShadowDiagnostic(scope, result)
        return result
    }
}

private fun <T> Iterable<T>.duplicates(): Set<T> {
    val seen = mutableSetOf<T>()
    return filterNot(seen::add).toSet()
}
