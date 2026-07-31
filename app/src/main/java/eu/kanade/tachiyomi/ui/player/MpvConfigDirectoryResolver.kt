package eu.kanade.tachiyomi.ui.player

internal fun resolveMpvConfigDirectory(
    internalConfigDirectory: String,
    useExternalConfigDirectory: Boolean,
    externalConfigDirectory: () -> String?,
    onExternalFailure: (Exception) -> Unit = {},
): String {
    if (!useExternalConfigDirectory) return internalConfigDirectory

    return try {
        externalConfigDirectory()?.takeIf { it.isNotBlank() } ?: internalConfigDirectory
    } catch (error: Exception) {
        onExternalFailure(error)
        internalConfigDirectory
    }
}
