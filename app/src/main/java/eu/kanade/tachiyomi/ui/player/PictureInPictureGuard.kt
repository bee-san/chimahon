package eu.kanade.tachiyomi.ui.player

internal class PictureInPictureGuard(
    initiallyAvailable: Boolean,
    private val onRejected: (IllegalStateException) -> Unit = {},
) {
    var isAvailable = initiallyAvailable
        private set

    fun runIfAvailable(operation: () -> Boolean): Boolean {
        if (!isAvailable) return false

        return try {
            operation()
        } catch (error: IllegalStateException) {
            isAvailable = false
            onRejected(error)
            false
        }
    }
}
