package eu.kanade.tachiyomi.ui.player.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SceneMiningLogTest {
    @Test
    fun `remote values keep only scheme and host`() {
        assertEquals(
            "https://media.example/<redacted>",
            redactSceneValue("https://media.example/a/b.mkv?token=super-secret&x-amz-signature=abc"),
        )
        // The scheme is echoed as written, so an uppercase input stays uppercase.
        assertEquals(
            "HTTP://media.example/<redacted>",
            redactSceneValue("HTTP://media.example/a/b.mkv"),
        )
        assertEquals("<blank>", redactSceneValue(null))
        assertEquals("<blank>", redactSceneValue("  "))
    }

    @Test
    fun `local paths and content uris stay readable`() {
        assertEquals("/video/episode.mkv", redactSceneValue("/video/episode.mkv"))
        assertEquals(
            "content://media/external/video/1",
            redactSceneValue("content://media/external/video/1"),
        )
    }

    @Test
    fun `ffmpeg output keeps its diagnostics but loses embedded credentials`() {
        val redacted = redactSceneLogLine(
            "https://media.example/ep.mkv?token=secret: Server returned 403 Forbidden",
        )

        assertEquals(
            "https://media.example/<redacted> Server returned 403 Forbidden",
            redacted,
        )
        assertFalse(redacted.contains("secret"))
    }

    @Test
    fun `every url on a multi line report is redacted`() {
        val redacted = redactSceneLogLine(
            """
            [tls @ 0x1] error opening https://a.example/x?sig=one
            [http @ 0x2] retry https://b.example/y?sig=two failed
            """.trimIndent(),
        )

        assertFalse(redacted.contains("sig="))
        assertEquals(2, Regex("<redacted>").findAll(redacted).count())
    }
}
