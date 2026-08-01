package eu.kanade.tachiyomi.ui.player

internal class SurfacePlaybackLoadGate(
    private val loadNow: (String) -> Boolean,
) {
    private var isSurfaceReady = false
    private var isClosed = false
    private var pendingUrl: String? = null

    fun load(url: String) {
        if (isClosed) return

        if (isSurfaceReady && loadNow(url)) {
            pendingUrl = null
        } else {
            pendingUrl = url
        }
    }

    fun onSurfaceCreated() {
        if (isClosed) return

        isSurfaceReady = true
        retryPending()
    }

    fun retryPending() {
        if (isClosed || !isSurfaceReady) return

        val url = pendingUrl ?: return
        if (loadNow(url)) {
            pendingUrl = null
        }
    }

    fun onSurfaceDestroyed() {
        isSurfaceReady = false
    }

    fun close() {
        isClosed = true
        isSurfaceReady = false
        pendingUrl = null
    }
}
