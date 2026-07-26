// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.reader.viewer

import kotlinx.coroutines.CancellationException
import tachiyomi.domain.immersion.model.LookupStatus
import tachiyomi.domain.immersion.service.InteractionProvenance
import tachiyomi.domain.immersion.service.LookupIntentToken

internal sealed interface InheritedLookupAttribution {
    data class Begin(
        val provenance: InteractionProvenance?,
    ) : InheritedLookupAttribution

    data class Suppressed(
        val lookupToken: LookupIntentToken,
    ) : InheritedLookupAttribution
}

internal fun resolveInheritedLookupAttribution(
    inheritedLookupToken: LookupIntentToken?,
    fallbackProvenance: InteractionProvenance?,
): InheritedLookupAttribution {
    if (inheritedLookupToken == null) {
        return InheritedLookupAttribution.Begin(fallbackProvenance)
    }
    val sessionId = inheritedLookupToken.sessionId
        ?: return InheritedLookupAttribution.Suppressed(inheritedLookupToken)
    return InheritedLookupAttribution.Begin(
        InteractionProvenance(
            sessionId = sessionId,
            sourceUnitId = inheritedLookupToken.sourceUnitId,
        ),
    )
}

internal fun resolveLookupResultStatus(
    hasResults: Boolean,
    error: String?,
): LookupStatus = when {
    hasResults -> LookupStatus.SUCCESS
    error != null -> LookupStatus.FAILED
    else -> LookupStatus.EMPTY
}

internal fun resolveLookupExceptionStatus(error: Exception): LookupStatus =
    if (error is CancellationException) {
        LookupStatus.CANCELLED
    } else {
        LookupStatus.FAILED
    }
