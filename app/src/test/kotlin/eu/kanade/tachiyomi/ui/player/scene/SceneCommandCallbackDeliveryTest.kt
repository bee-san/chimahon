package eu.kanade.tachiyomi.ui.player.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneCommandCallbackDeliveryTest {
    @Test
    fun `small successful output is delivered intact`() {
        val payload = SceneCommandResult.Success("codec_type=video").toCallbackPayload()

        assertTrue(payload.success)
        assertEquals("codec_type=video", payload.output)
    }

    @Test
    fun `oversized output becomes a bounded failure callback`() {
        val payload = SceneCommandResult.Success(
            "x".repeat(MAX_SCENE_CALLBACK_OUTPUT_CHARS + 1),
        ).toCallbackPayload()

        assertFalse(payload.success)
        assertTrue(payload.output.isEmpty())
    }

    @Test
    fun `callback failure retries with an empty failure`() {
        val delivered = mutableListOf<SceneCommandCallbackPayload>()

        deliverSceneCommandCallback(SceneCommandResult.Success("probe")) { payload ->
            delivered += payload
            if (delivered.size == 1) error("Binder transaction rejected")
        }

        assertEquals(
            listOf(
                SceneCommandCallbackPayload(success = true, output = "probe"),
                SceneCommandCallbackPayload(success = false, output = ""),
            ),
            delivered,
        )
    }
}
