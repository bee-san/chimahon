package tachiyomi.domain.immersion.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ImmersionCapturePolicyTest {
    @Test
    fun `incognito is the highest priority hard write barrier`() {
        ImmersionCapturePolicy.evaluate(
            CapturePolicyContext(
                captureEnabled = false,
                incognito = true,
                titleExcluded = true,
            ),
        ) shouldBe CapturePolicyDecision.Suppressed(CaptureSuppressionReason.INCOGNITO)
    }

    @Test
    fun `disabled and excluded capture are suppressed before queueing`() {
        ImmersionCapturePolicy.evaluate(
            CapturePolicyContext(captureEnabled = false, incognito = false),
        ) shouldBe CapturePolicyDecision.Suppressed(CaptureSuppressionReason.FEATURE_DISABLED)

        ImmersionCapturePolicy.evaluate(
            CapturePolicyContext(captureEnabled = true, incognito = false, titleExcluded = true),
        ) shouldBe CapturePolicyDecision.Suppressed(CaptureSuppressionReason.TITLE_EXCLUDED)

        ImmersionCapturePolicy.evaluate(
            CapturePolicyContext(captureEnabled = true, incognito = false),
        ) shouldBe CapturePolicyDecision.Allowed
    }
}
