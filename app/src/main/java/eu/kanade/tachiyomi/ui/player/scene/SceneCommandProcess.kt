package eu.kanade.tachiyomi.ui.player.scene

import android.app.Application
import android.os.Build
import java.io.File

internal object SceneCommandProcess {
    // Must match android:process on IsolatedSceneCommandService in AndroidManifest.xml.
    const val SUFFIX = ":scene_processing"

    fun isCurrent(): Boolean = currentProcessName().endsWith(SUFFIX)

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        return runCatching {
            File("/proc/self/cmdline").readText().substringBefore('\u0000')
        }.getOrDefault("")
    }
}
