package eu.kanade.tachiyomi.ui.player.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SceneSentenceAudioArgumentsTest {
    @Test
    fun `selected external audio maps the first audio stream from its own input`() {
        val output = File("/cache/chimahon_sentence.m4a")
        val arguments = SceneSentenceAudioArguments.build(
            input = input(),
            acquiredInput = "https://cdn.test/audio.webm",
            range = SceneTimeRange(12.125, 15.875),
            externalAudioSelected = true,
            selectedAudioFfmpegIndex = null,
            output = output,
        ).toList()

        assertEquals("12.125", arguments[arguments.indexOf("-ss") + 1])
        assertEquals("3.75", arguments[arguments.indexOf("-t") + 1])
        assertEquals("aac", arguments[arguments.indexOf("-c:a") + 1])
        assertEquals("128k", arguments[arguments.indexOf("-b:a") + 1])
        assertEquals("0:a:0", arguments[arguments.indexOf("-map") + 1])
        assertFalse("copy" in arguments)
        assertTrue(arguments.indexOf("-y") < arguments.indexOf(output.absolutePath))
    }

    @Test
    fun `selected internal audio maps its exact frozen ffmpeg stream index`() {
        val arguments = SceneSentenceAudioArguments.build(
            input = input(externalAudioValue = null),
            acquiredInput = "https://cdn.test/video.m3u8",
            range = SceneTimeRange(12.125, 15.875),
            externalAudioSelected = false,
            selectedAudioFfmpegIndex = 3,
            output = File("/cache/chimahon_sentence.m4a"),
        ).toList()

        assertEquals("0:3", arguments[arguments.indexOf("-map") + 1])
    }

    @Test
    fun `validated frozen headers and input options are forwarded as argument array elements`() {
        val arguments = SceneSentenceAudioArguments.build(
            input = input(),
            acquiredInput = "https://cdn.test/audio.webm",
            range = SceneTimeRange(1.0, 2.0),
            externalAudioSelected = true,
            selectedAudioFfmpegIndex = null,
            output = File("/cache/out.m4a"),
        ).toList()

        assertEquals("5000000", arguments[arguments.indexOf("-rw_timeout") + 1])
        assertEquals(
            "Referer: https://app.test/\r\nUser-Agent: Chimahon\r\n",
            arguments[arguments.indexOf("-headers") + 1],
        )
    }

    @Test
    fun `headers remain input options for a local frozen HLS manifest`() {
        val frozenInput = input(externalAudioValue = null).copy(
            value = "/cache/checked_input/playlist_000.m3u8",
            kind = SceneVideoInputKind.LOCAL_FILE,
            inputOptions = input(externalAudioValue = null).inputOptions +
                SceneInputOption("protocol_whitelist", "file,http,https,tcp,tls"),
        )

        val arguments = SceneSentenceAudioArguments.build(
            input = frozenInput,
            acquiredInput = frozenInput.value,
            range = SceneTimeRange(1.0, 2.0),
            externalAudioSelected = false,
            selectedAudioFfmpegIndex = 3,
            output = File("/cache/out.m4a"),
        ).toList()

        assertEquals(
            "Referer: https://app.test/\r\nUser-Agent: Chimahon\r\n",
            arguments[arguments.indexOf("-headers") + 1],
        )
        assertEquals(
            "file,http,https,tcp,tls",
            arguments[arguments.indexOf("-protocol_whitelist") + 1],
        )
        assertTrue(arguments.indexOf("-protocol_whitelist") < arguments.indexOf("-i"))
        assertTrue(arguments.indexOf("-headers") < arguments.indexOf("-i"))
    }

    private fun input(
        externalAudioValue: String? = "https://cdn.test/audio.webm",
    ): SceneVideoInputSpec {
        return SceneVideoInputSpec(
            value = "https://cdn.test/video.m3u8",
            kind = SceneVideoInputKind.REMOTE_HTTP,
            headers = listOf(
                "Referer" to "https://app.test/",
                "User-Agent" to "Chimahon",
            ),
            inputOptions = listOf(SceneInputOption("rw_timeout", "5000000")),
            externalAudioValue = externalAudioValue,
            identity = SceneVideoIdentity(
                episodeId = 1L,
                sourceId = 2L,
                quality = "1080p",
                inputDigest = "digest",
            ),
        )
    }
}
