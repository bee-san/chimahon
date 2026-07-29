package eu.kanade.tachiyomi.ui.stats

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StatsRecentsPrivacyControllerTest {

    @Test
    fun `android 13 recents protection survives adjacent stats screens`() {
        val host = RecordingHost()
        val controller = StatsRecentsPrivacyController(
            supportsRecentsScreenshotControl = true,
            host = host,
            initialResumed = true,
        )

        controller.acquire(globalSecureScreenEnabled = false)
        controller.release(globalSecureScreenEnabled = false)
        controller.acquire(globalSecureScreenEnabled = false)
        controller.commitPendingRelease()
        controller.release(globalSecureScreenEnabled = false)
        controller.commitPendingRelease()

        host.actions shouldBe listOf(
            HostAction.RecentsScreenshot(enabled = false),
            HostAction.RecentsScreenshot(enabled = false),
            HostAction.RecentsScreenshot(enabled = false),
            HostAction.RecentsScreenshot(enabled = false),
            HostAction.RecentsScreenshot(enabled = true),
        )
        controller.canDetach shouldBe true
    }

    @Test
    fun `legacy fallback secures pause and restores global policy on resume`() {
        val host = RecordingHost()
        val controller = StatsRecentsPrivacyController(
            supportsRecentsScreenshotControl = false,
            host = host,
            initialResumed = true,
        )

        controller.acquire(globalSecureScreenEnabled = false)
        controller.onPause()
        controller.updateGlobalPolicy(globalSecureScreenEnabled = false)
        controller.onResume(globalSecureScreenEnabled = false)

        host.actions shouldBe listOf(
            HostAction.SecureScreen(enabled = false),
            HostAction.SecureScreen(enabled = true),
            HostAction.SecureScreen(enabled = true),
            HostAction.SecureScreen(enabled = false),
        )
    }

    @Test
    fun `legacy fallback defers release while activity is paused`() {
        val host = RecordingHost()
        val controller = StatsRecentsPrivacyController(
            supportsRecentsScreenshotControl = false,
            host = host,
            initialResumed = true,
        )

        controller.acquire(globalSecureScreenEnabled = false)
        controller.onPause()
        controller.release(globalSecureScreenEnabled = false)
        controller.commitPendingRelease()

        controller.canDetach shouldBe false
        host.actions.last() shouldBe HostAction.SecureScreen(enabled = true)

        controller.onResume(globalSecureScreenEnabled = false)

        controller.canDetach shouldBe true
        host.actions.last() shouldBe HostAction.SecureScreen(enabled = false)
    }

    @Test
    fun `global secure policy matches always incognito and never modes`() {
        statsGlobalSecureScreenEnabled(
            SecurityPreferences.SecureScreenMode.ALWAYS,
            incognitoMode = false,
        ) shouldBe true
        statsGlobalSecureScreenEnabled(
            SecurityPreferences.SecureScreenMode.INCOGNITO,
            incognitoMode = true,
        ) shouldBe true
        statsGlobalSecureScreenEnabled(
            SecurityPreferences.SecureScreenMode.INCOGNITO,
            incognitoMode = false,
        ) shouldBe false
        statsGlobalSecureScreenEnabled(
            SecurityPreferences.SecureScreenMode.NEVER,
            incognitoMode = true,
        ) shouldBe false
    }

    private class RecordingHost : StatsRecentsPrivacyHost {
        val actions = mutableListOf<HostAction>()

        override fun setRecentsScreenshotEnabled(enabled: Boolean) {
            actions += HostAction.RecentsScreenshot(enabled)
        }

        override fun setSecureScreen(enabled: Boolean) {
            actions += HostAction.SecureScreen(enabled)
        }
    }

    private sealed interface HostAction {
        data class RecentsScreenshot(val enabled: Boolean) : HostAction

        data class SecureScreen(val enabled: Boolean) : HostAction
    }
}
