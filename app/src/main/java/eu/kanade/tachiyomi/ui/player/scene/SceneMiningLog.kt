package eu.kanade.tachiyomi.ui.player.scene

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.net.URI
import java.util.Locale

internal const val SCENE_LOG_TAG = "SceneMining"

/**
 * Scene mining downgrades to a still image on any failure, so every early return needs to say why.
 * Fixed at [LogPriority.INFO] because release-derived builds drop anything lower.
 */
internal inline fun Any.sceneLog(
    throwable: Throwable? = null,
    message: () -> String,
) = logcat(
    priority = LogPriority.INFO,
    throwable = throwable,
    tag = SCENE_LOG_TAG,
    message = message,
)

/**
 * Remote scene inputs are rejected outright when they carry credentials, so logging one verbatim
 * would defeat that check. Keeps only the scheme and host; paths can be signed too.
 */
internal fun redactSceneValue(value: String?): String {
    if (value.isNullOrBlank()) return "<blank>"
    val lowered = value.lowercase(Locale.ROOT)
    if (!lowered.startsWith("http://") && !lowered.startsWith("https://")) return value
    val uri = runCatching { URI(value) }.getOrNull() ?: return "<unparsable-http-url>"
    return "${uri.scheme}://${uri.host ?: "<no-host>"}/<redacted>"
}

/**
 * FFmpeg echoes the input URL into its own diagnostics, so its output is redacted line by line
 * rather than as a single value: a URL can appear anywhere inside otherwise useful error text.
 */
internal fun redactSceneLogLine(line: String): String =
    EMBEDDED_HTTP_URL.replace(line) { match -> redactSceneValue(match.value) }

private val EMBEDDED_HTTP_URL = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

internal fun SceneVideoInputSpec.describe(): String =
    "kind=$kind value=${redactSceneValue(value)} videoStreamIndex=$videoStreamIndex " +
        "audioStreamIndex=$audioStreamIndex headerCount=${headers.size}"
