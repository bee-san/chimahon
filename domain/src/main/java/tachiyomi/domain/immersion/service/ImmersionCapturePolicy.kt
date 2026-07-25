// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.service

data class CapturePolicyContext(
    val captureEnabled: Boolean,
    val incognito: Boolean,
    val titleExcluded: Boolean = false,
)

sealed interface CapturePolicyDecision {
    data object Allowed : CapturePolicyDecision

    data class Suppressed(val reason: CaptureSuppressionReason) : CapturePolicyDecision
}

enum class CaptureSuppressionReason {
    INCOGNITO,
    FEATURE_DISABLED,
    TITLE_EXCLUDED,
    NO_ACTIVE_SESSION,
}

object ImmersionCapturePolicy {
    fun evaluate(context: CapturePolicyContext): CapturePolicyDecision = when {
        context.incognito -> CapturePolicyDecision.Suppressed(CaptureSuppressionReason.INCOGNITO)
        !context.captureEnabled -> CapturePolicyDecision.Suppressed(CaptureSuppressionReason.FEATURE_DISABLED)
        context.titleExcluded -> CapturePolicyDecision.Suppressed(CaptureSuppressionReason.TITLE_EXCLUDED)
        else -> CapturePolicyDecision.Allowed
    }
}
