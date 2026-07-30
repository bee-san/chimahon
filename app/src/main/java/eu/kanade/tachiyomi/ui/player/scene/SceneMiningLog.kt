package eu.kanade.tachiyomi.ui.player.scene

import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import java.net.URI
import java.util.Locale

internal const val SCENE_LOG_TAG = "SceneMining"

/**
 * Scene mining downgrades to a still image on any failure, so every early return needs to say why.
 *
 * Fixed at [LogPriority.INFO] because release-derived builds drop anything lower. Emits
 * [SCENE_LOG_TAG] as the real logcat tag rather than as a message prefix, so that
 * `adb logcat -s SceneMining` selects the whole trace; the house `logcat` helper in
 * `tachiyomi.core.common` keeps the calling class as the tag and would leave the filter empty.
 * The calling class is named in each message instead, since that is what the tag gave up.
 */
internal inline fun Any.sceneLog(
    throwable: Throwable? = null,
    message: () -> String,
    // Positional, so the String first parameter picks the top-level tag-first overload rather than
    // the `Any.logcat` extension that is also in scope here.
) = logcat(SCENE_LOG_TAG, LogPriority.INFO) {
    val caller = this::class.java.simpleName.takeIf(String::isNotBlank) ?: "Scene"
    buildString {
        append(caller).append(": ").append(message())
        if (throwable != null) append('\n').append(throwable.asLog())
    }
}

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
