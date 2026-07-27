// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import android.app.Activity
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.util.view.setSecureScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.WeakHashMap

@Composable
internal fun StatsRecentsPrivacy() {
    val activity = LocalActivity.current ?: return
    val lifecycleOwner = activity as? LifecycleOwner ?: return
    val securityPreferences = remember { Injekt.get<SecurityPreferences>() }
    val basePreferences = remember { Injekt.get<BasePreferences>() }
    val secureScreenMode by remember(securityPreferences) {
        securityPreferences.secureScreen().changes()
    }.collectAsState(securityPreferences.secureScreen().get())
    val incognitoMode by remember(basePreferences) {
        basePreferences.incognitoMode().changes()
    }.collectAsState(basePreferences.incognitoMode().get())
    val globalSecureScreenEnabled = statsGlobalSecureScreenEnabled(
        mode = secureScreenMode,
        incognitoMode = incognitoMode,
    )

    DisposableEffect(activity, lifecycleOwner, globalSecureScreenEnabled) {
        val lease = StatsRecentsPrivacyRegistry.acquire(
            activity = activity,
            lifecycle = lifecycleOwner.lifecycle,
            globalSecureScreenEnabled = globalSecureScreenEnabled,
        )
        onDispose {
            lease.release(globalSecureScreenEnabled)
        }
    }
}

internal fun statsGlobalSecureScreenEnabled(
    mode: SecurityPreferences.SecureScreenMode,
    incognitoMode: Boolean,
): Boolean {
    return mode == SecurityPreferences.SecureScreenMode.ALWAYS ||
        (mode == SecurityPreferences.SecureScreenMode.INCOGNITO && incognitoMode)
}

internal interface StatsRecentsPrivacyHost {
    fun setRecentsScreenshotEnabled(enabled: Boolean)

    fun setSecureScreen(enabled: Boolean)
}

internal class StatsRecentsPrivacyController(
    private val supportsRecentsScreenshotControl: Boolean,
    private val host: StatsRecentsPrivacyHost,
    initialResumed: Boolean,
) {
    private var activeLeaseCount = 0
    private var releasePending = false
    private var resumed = initialResumed
    private var globalSecureScreenEnabled = false

    val canDetach: Boolean
        get() = activeLeaseCount == 0 && !releasePending

    fun acquire(globalSecureScreenEnabled: Boolean) {
        this.globalSecureScreenEnabled = globalSecureScreenEnabled
        activeLeaseCount += 1
        releasePending = false
        applyPrivacy()
    }

    fun updateGlobalPolicy(globalSecureScreenEnabled: Boolean) {
        this.globalSecureScreenEnabled = globalSecureScreenEnabled
        applyPrivacy()
    }

    fun release(globalSecureScreenEnabled: Boolean) {
        check(activeLeaseCount > 0) { "Stats privacy lease released more than once" }
        this.globalSecureScreenEnabled = globalSecureScreenEnabled
        activeLeaseCount -= 1
        if (activeLeaseCount == 0) {
            releasePending = true
        }
        applyPrivacy()
    }

    fun commitPendingRelease() {
        if (activeLeaseCount > 0 || !releasePending || !resumed) return
        releasePending = false
        applyPrivacy()
    }

    fun onPause() {
        resumed = false
        applyPrivacy()
    }

    fun onResume(globalSecureScreenEnabled: Boolean) {
        this.globalSecureScreenEnabled = globalSecureScreenEnabled
        resumed = true
        if (activeLeaseCount == 0) {
            releasePending = false
        }
        applyPrivacy()
    }

    private fun applyPrivacy() {
        val protectingStats = activeLeaseCount > 0 || releasePending
        if (supportsRecentsScreenshotControl) {
            host.setRecentsScreenshotEnabled(!protectingStats)
        } else {
            host.setSecureScreen(
                globalSecureScreenEnabled || (protectingStats && !resumed),
            )
        }
    }
}

private object StatsRecentsPrivacyRegistry {
    private val entries = WeakHashMap<Activity, Entry>()

    fun acquire(
        activity: Activity,
        lifecycle: Lifecycle,
        globalSecureScreenEnabled: Boolean,
    ): Lease {
        val entry = entries.getOrPut(activity) {
            Entry(activity, lifecycle)
        }
        entry.acquire(globalSecureScreenEnabled)
        return Lease(entry)
    }

    private fun remove(entry: Entry) {
        if (entries[entry.activity] !== entry) return
        entries.remove(entry.activity)
        entry.detach()
    }

    class Entry(
        val activity: Activity,
        private val lifecycle: Lifecycle,
    ) : DefaultLifecycleObserver {
        private val controller = StatsRecentsPrivacyController(
            supportsRecentsScreenshotControl =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            host = AndroidStatsRecentsPrivacyHost(activity),
            initialResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        )
        private var globalSecureScreenEnabled = false
        private var releasePosted = false
        private var detached = false

        init {
            lifecycle.addObserver(this)
        }

        fun acquire(globalSecureScreenEnabled: Boolean) {
            this.globalSecureScreenEnabled = globalSecureScreenEnabled
            controller.acquire(globalSecureScreenEnabled)
        }

        fun updateGlobalPolicy(globalSecureScreenEnabled: Boolean) {
            this.globalSecureScreenEnabled = globalSecureScreenEnabled
            controller.updateGlobalPolicy(globalSecureScreenEnabled)
        }

        fun release(globalSecureScreenEnabled: Boolean) {
            this.globalSecureScreenEnabled = globalSecureScreenEnabled
            controller.release(globalSecureScreenEnabled)
            schedulePendingRelease()
        }

        override fun onPause(owner: LifecycleOwner) {
            controller.onPause()
        }

        override fun onResume(owner: LifecycleOwner) {
            controller.onResume(globalSecureScreenEnabled)
            if (controller.canDetach) {
                remove(this)
            }
        }

        override fun onDestroy(owner: LifecycleOwner) {
            remove(this)
        }

        fun detach() {
            if (detached) return
            detached = true
            lifecycle.removeObserver(this)
        }

        private fun schedulePendingRelease() {
            if (releasePosted) return
            releasePosted = true
            activity.window.decorView.post {
                releasePosted = false
                if (detached) return@post
                controller.commitPendingRelease()
                if (controller.canDetach) {
                    remove(this)
                }
            }
        }
    }

    class Lease internal constructor(
        private var entry: Entry?,
    ) {
        fun updateGlobalPolicy(globalSecureScreenEnabled: Boolean) {
            entry?.updateGlobalPolicy(globalSecureScreenEnabled)
        }

        fun release(globalSecureScreenEnabled: Boolean) {
            entry?.release(globalSecureScreenEnabled)
            entry = null
        }
    }
}

private class AndroidStatsRecentsPrivacyHost(
    private val activity: Activity,
) : StatsRecentsPrivacyHost {
    override fun setRecentsScreenshotEnabled(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(enabled)
        }
    }

    override fun setSecureScreen(enabled: Boolean) {
        activity.window.setSecureScreen(enabled)
    }
}
