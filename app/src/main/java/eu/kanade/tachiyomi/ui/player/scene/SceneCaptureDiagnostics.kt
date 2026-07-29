package eu.kanade.tachiyomi.ui.player.scene

import exh.log.xLogE
import exh.log.xLogW

/**
 * Named the gate that accepted or refused a scene capture.
 *
 * The capture pipeline has roughly forty distinct refusal paths that all surface as the same
 * "the animated scene could not be created" toast, and neither FFmpeg's output nor its exit code
 * survives ([FfmpegKitSceneCommandExecutor] discards both). Without this, a fallback is
 * indistinguishable from any other, which is why an unrelated container was suspected first.
 *
 * Logging goes through this seam rather than calling [xLogE] directly so the capture service stays
 * a pure JVM unit under test — `android.util.Log` is not mocked in the unit-test source set.
 *
 * Implementations must never record a URL, header value, or query string: credentials reach this
 * pipeline through both (Jellyfin authenticates with an `api_key` query parameter). Gate names,
 * pixel formats, codec names, and redacted hosts only.
 */
internal interface SceneCaptureDiagnostics {
    /** A gate refused the capture. */
    fun reject(message: String)

    /** A gate refused the capture because of [error]. */
    fun reject(message: String, error: Throwable)

    /** A gate accepted, or reports a measurement worth keeping (timings, selected encoder). */
    fun accept(message: String)

    companion object {
        /** Discards everything; for tests that assert behavior rather than logging. */
        val None = object : SceneCaptureDiagnostics {
            override fun reject(message: String) = Unit
            override fun reject(message: String, error: Throwable) = Unit
            override fun accept(message: String) = Unit
        }
    }
}

internal object AndroidSceneCaptureDiagnostics : SceneCaptureDiagnostics {
    override fun reject(message: String) {
        xLogE("$LOG_TAG $message")
    }

    override fun reject(message: String, error: Throwable) {
        xLogE("$LOG_TAG $message", error)
    }

    override fun accept(message: String) {
        xLogW("$LOG_TAG $message")
    }

    private const val LOG_TAG = "scene-capture"
}
