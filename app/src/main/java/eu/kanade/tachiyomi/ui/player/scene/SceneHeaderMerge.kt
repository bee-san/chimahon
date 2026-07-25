package eu.kanade.tachiyomi.ui.player.scene

import java.util.Locale

/**
 * Combines source defaults with per-video overrides while retaining repeated source headers.
 */
internal fun mergeSceneHeaders(
    sourceHeaders: List<Pair<String, String>>,
    videoHeaders: List<Pair<String, String>>,
): List<Pair<String, String>> {
    val videoHeaderNames = videoHeaders
        .mapTo(mutableSetOf()) { (name, _) -> name.lowercase(Locale.ROOT) }
    return sourceHeaders.filterNot { (name, _) ->
        name.lowercase(Locale.ROOT) in videoHeaderNames
    } + videoHeaders
}
