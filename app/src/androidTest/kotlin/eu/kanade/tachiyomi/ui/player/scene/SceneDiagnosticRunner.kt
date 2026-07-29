package eu.kanade.tachiyomi.ui.player.scene

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Runs scene-capture diagnostics without booting the real [eu.kanade.tachiyomi.App].
 *
 * The app's Injekt graph eagerly constructs `NetworkHelper` -> `AndroidCookieJar` ->
 * `CookieManager.getInstance()`, which hard-crashes on emulator images whose bundled WebView cannot
 * initialize (`BuildInfo` NPEs on a null `PackageInfo.applicationInfo`). The scene pipeline needs
 * only a `Context` for `cacheDir` plus the packaged native FFmpeg, so substituting a plain
 * [Application] keeps the pipeline under test while sidestepping an unrelated platform defect.
 *
 * Not wired into `defaultConfig`; select it explicitly:
 * `am instrument -w app.chimahon.dev.test/eu.kanade.tachiyomi.ui.player.scene.SceneDiagnosticRunner`
 */
class SceneDiagnosticRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application {
        return super.newApplication(classLoader, Application::class.java.name, context)
    }
}
