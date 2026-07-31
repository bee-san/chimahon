package eu.kanade.tachiyomi.ui.player.scene

internal data class SceneCommandCallbackPayload(
    val success: Boolean,
    val output: String,
)

internal fun SceneCommandResult.toCallbackPayload(): SceneCommandCallbackPayload {
    val output = (this as? SceneCommandResult.Success)?.output
    return if (output != null && output.length <= MAX_SCENE_CALLBACK_OUTPUT_CHARS) {
        SceneCommandCallbackPayload(success = true, output = output)
    } else {
        SceneCommandCallbackPayload(success = false, output = "")
    }
}

internal fun deliverSceneCommandCallback(
    result: SceneCommandResult,
    deliver: (SceneCommandCallbackPayload) -> Unit,
) {
    val payload = result.toCallbackPayload()
    runCatching {
        deliver(payload)
    }.onFailure {
        runCatching {
            deliver(SceneCommandCallbackPayload(success = false, output = ""))
        }
    }
}

internal const val MAX_SCENE_CALLBACK_OUTPUT_CHARS = 128 * 1024
