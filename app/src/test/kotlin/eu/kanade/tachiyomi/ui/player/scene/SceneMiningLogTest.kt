package eu.kanade.tachiyomi.ui.player.scene

import logcat.LogPriority
import logcat.LogcatLogger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneMiningLogTest {
    private class RecordingLogger : LogcatLogger {
        val entries = mutableListOf<Triple<LogPriority, String, String>>()

        override fun isLoggable(priority: LogPriority) = true

        override fun log(priority: LogPriority, tag: String, message: String) {
            entries += Triple(priority, tag, message)
        }
    }

    private val logger = RecordingLogger()

    @AfterEach
    fun tearDown() {
        if (LogcatLogger.isInstalled) LogcatLogger.uninstall()
    }

    /**
     * A previous build emitted the calling class as the tag and `[SceneMining]` as a message
     * prefix, which made `adb logcat -s SceneMining` return nothing at all and read as though the
     * instrumentation had never fired.
     */
    @Test
    fun `scene logs are tagged so a tag-only logcat filter finds them`() {
        LogcatLogger.install(logger)

        sceneLog { "prepare: starting" }

        val (priority, tag, message) = logger.entries.single()
        assertEquals(SCENE_LOG_TAG, tag)
        // Release-derived builds install a logger with an INFO floor and would drop DEBUG.
        assertEquals(LogPriority.INFO, priority)
        assertTrue(message.endsWith("prepare: starting"), message)
        // The caller is still identifiable now that it no longer occupies the tag.
        assertTrue(message.startsWith("SceneMiningLogTest: "), message)
    }

    @Test
    fun `scene logs append the throwable so a swallowed cause survives`() {
        LogcatLogger.install(logger)

        sceneLog(throwable = IllegalStateException("boom")) { "prepare: threw" }

        val message = logger.entries.single().third
        assertTrue(message.contains("prepare: threw"), message)
        assertTrue(message.contains("IllegalStateException"), message)
        assertTrue(message.contains("boom"), message)
    }

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
