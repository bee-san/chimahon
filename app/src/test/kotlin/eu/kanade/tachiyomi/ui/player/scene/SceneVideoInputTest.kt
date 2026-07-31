package eu.kanade.tachiyomi.ui.player.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneVideoInputTest {
    @Test
    fun `local file SAF and public HLS are supported`() {
        assertSupported(snapshot("/video/episode.mkv"))
        assertSupported(snapshot("file:///video/episode.mkv"))
        assertSupported(snapshot("content://media/external/video/1"))
        val hls = resolve(
            snapshot(
                "https://media.example/episode.m3u8?keyframe=1&design=2",
                headers = ALLOWED_HEADERS,
            ),
        )

        val input = requireNotNull(hls)
        assertEquals(SceneVideoInputKind.REMOTE_HTTP, input.kind)
        assertEquals(ALLOWED_HEADERS, input.headers)
    }

    @Test
    fun `sensitive request metadata and signed URLs are rejected`() {
        listOf(
            snapshot(
                "https://media.example/video.mp4",
                headers = listOf("Authorization" to "Bearer secret"),
            ),
            snapshot(
                "https://media.example/video.mp4?token=secret",
            ),
            snapshot(
                "https://media.example/video.mp4?to%6ben=secret",
            ),
            snapshot(
                "https://user:password@media.example/video.mp4",
            ),
        ).forEach { value ->
            assertNull(resolve(value))
        }
    }

    @Test
    fun `DASH transient and unsafe inputs are rejected`() {
        val cases = listOf(
            snapshot("https://media.example/manifest.mpd"),
            snapshot("ytdl://abc"),
            snapshot(
                "https://media.example/video.mp4",
                ffmpegStreamArgs = listOf("-referer" to "https://private.example"),
            ),
            snapshot("https://media.example/video.mp4", seekable = false),
        )

        cases.forEach { assertNull(resolve(it)) }
    }

    @Test
    fun `AV1 encode muxes MediaCodec output directly into animated AVIF`() {
        val input = supportedInput()
        val arguments = SceneFfmpegArguments.animatedAvifMediaCodec(
            input = input,
            acquiredInputValue = "https://media.example/video.mp4",
            range = SceneTimeRange(1.25, 11.25),
            outputFile = "/cache/output.avif",
            encoderName = TEST_AV1_ENCODER_NAME,
            contentSize = SceneVideoDimensions(width = 640, height = 360),
            outputSize = SceneVideoDimensions(width = 640, height = 360),
            tlsCaFile = "/files/cacert.pem",
        ).toList()

        assertTrue(
            arguments.containsAll(
                listOf(
                    "-c:v",
                    "av1_mediacodec",
                    "-codec_name",
                    TEST_AV1_ENCODER_NAME,
                    "-bitrate_mode",
                    "cq",
                    "-global_quality",
                    "35",
                ),
            ),
        )
        assertTrue(arguments.containsAll(listOf("-ndk_codec", "1", "-pix_fmt", "yuv420p")))
        assertTrue(arguments.containsAll(listOf("-frames:v", "80", "-loop", "0", "-f", "avif")))
        assertTrue(
            arguments.containsAll(
                listOf(
                    "-tls_verify",
                    "1",
                    "-ca_file",
                    "/files/cacert.pem",
                    "-protocol_whitelist",
                    "http,https,tls,tcp,crypto",
                    "-rw_timeout",
                    "15000000",
                ),
            ),
        )
        assertEquals(1, arguments.count { it == "-c:v" })
        assertEquals(
            "fps=8,scale=w=640:h=360,setsar=1",
            arguments[arguments.indexOf("-vf") + 1],
        )
        assertEquals("/cache/output.avif", arguments.last())
    }

    @Test
    fun `AV1 encode pads aspect preserving content into the codec canvas`() {
        val arguments = SceneFfmpegArguments.animatedAvifMediaCodec(
            input = supportedInput(),
            acquiredInputValue = "https://media.example/video.mp4",
            range = SceneTimeRange(1.25, 11.25),
            outputFile = "/cache/output.avif",
            encoderName = TEST_AV1_ENCODER_NAME,
            contentSize = SceneVideoDimensions(width = 320, height = 180),
            outputSize = SceneVideoDimensions(width = 320, height = 192),
            tlsCaFile = "/files/cacert.pem",
        ).toList()

        assertEquals(
            "fps=8,scale=w=320:h=180,setsar=1,pad=w=320:h=192:x=0:y=6:color=black",
            arguments[arguments.indexOf("-vf") + 1],
        )
    }

    @Test
    fun `AV1 padding uses explicit chroma aligned offsets`() {
        assertEquals(
            "fps=8,scale=w=318:h=178,setsar=1,pad=w=320:h=180:x=0:y=0:color=black",
            SceneFfmpegArguments.frameFilter(
                contentSize = SceneVideoDimensions(width = 318, height = 178),
                outputSize = SceneVideoDimensions(width = 320, height = 180),
            ),
        )
    }

    @Test
    fun `all native input commands restrict decoders without breaking ordinary media`() {
        val input = supportedInput()
        val range = SceneTimeRange(1.25, 2.25)
        val caFile = "/files/cacert.pem"
        val commands = listOf(
            SceneFfmpegArguments.animatedAvifMediaCodec(
                input = input,
                acquiredInputValue = input.value,
                range = range,
                outputFile = "/cache/scene.avif",
                encoderName = TEST_AV1_ENCODER_NAME,
                contentSize = SceneVideoDimensions(width = 640, height = 360),
                outputSize = SceneVideoDimensions(width = 640, height = 360),
                tlsCaFile = caFile,
            ),
            SceneFfmpegArguments.videoProbe(input, input.value, caFile),
            SceneFfmpegArguments.audioProbe(input, input.value, caFile),
            SceneFfmpegArguments.sentenceAudio(input, input.value, range, "/cache/audio.m4a", caFile),
        )

        commands.forEach { command ->
            val arguments = command.toList()
            val whitelist = arguments[arguments.indexOf("-codec_whitelist") + 1].split(',')
            assertTrue(whitelist.containsAll(listOf("h264", "hevc", "aac", "av1", "libdav1d")))
            assertFalse("magicyuv" in whitelist)
            val inputIndex = arguments.indexOf("-i").takeIf { it >= 0 } ?: arguments.lastIndex
            assertTrue(arguments.indexOf("-codec_whitelist") < inputIndex)
        }
    }

    @Test
    fun `embedded MP4 subtitles do not block scene probe or encode`() {
        val input = supportedInput()
        val commands = listOf(
            SceneFfmpegArguments.videoProbe(input, input.value, "/files/cacert.pem"),
            SceneFfmpegArguments.animatedAvifMediaCodec(
                input = input,
                acquiredInputValue = input.value,
                range = SceneTimeRange(1.25, 2.25),
                outputFile = "/cache/scene.avif",
                encoderName = TEST_AV1_ENCODER_NAME,
                contentSize = SceneVideoDimensions(width = 640, height = 360),
                outputSize = SceneVideoDimensions(width = 640, height = 360),
                tlsCaFile = "/files/cacert.pem",
            ),
        )

        commands.forEach { command ->
            val whitelist = command[command.indexOf("-codec_whitelist") + 1].split(',')
            assertTrue("mov_text" in whitelist, "mov_text missing from $whitelist")
        }
    }

    /**
     * SAF documents reach FFmpeg as FFmpegKit's `saf:<id>.<ext>` pseudo-URL, because reopening a
     * `/proc/self/fd/N` path re-checks permissions against the real file and loses the SAF grant.
     * `-protocol_whitelist` would filter that scheme out, so it must stay confined to remote input.
     */
    @Test
    fun `content uri commands pass a saf value through without restricting protocols`() {
        val input = SceneVideoInputSpec(
            value = "content://com.android.externalstorage.documents/tree/primary%3AAnime",
            kind = SceneVideoInputKind.CONTENT_URI,
            headers = emptyList(),
        )
        val safValue = "saf:37.mp4"
        val range = SceneTimeRange(1.25, 2.25)
        val commands = listOf(
            SceneFfmpegArguments.animatedAvifMediaCodec(
                input = input,
                acquiredInputValue = safValue,
                range = range,
                outputFile = "/cache/scene.avif",
                encoderName = TEST_AV1_ENCODER_NAME,
                contentSize = SceneVideoDimensions(width = 640, height = 360),
                outputSize = SceneVideoDimensions(width = 640, height = 360),
            ),
            SceneFfmpegArguments.videoProbe(input, safValue),
            SceneFfmpegArguments.audioProbe(input, safValue),
            SceneFfmpegArguments.sentenceAudio(input, safValue, range, "/cache/audio.m4a"),
        )

        commands.forEach { command ->
            val arguments = command.toList()
            assertTrue(safValue in arguments, "saf value missing from $arguments")
            assertFalse("-protocol_whitelist" in arguments, "saf scheme would be filtered out")
            // The content uri itself must never reach ffmpeg -- it is not an openable path.
            assertFalse(arguments.any { it.startsWith("content://") }, arguments.toString())
        }
    }

    @Test
    fun `sentence audio maps the frozen selected stream`() {
        val input = supportedInput().copy(videoStreamIndex = 2, audioStreamIndex = 3)
        val range = SceneTimeRange(1.25, 2.25)
        val caFile = "/files/cacert.pem"
        val audio = SceneFfmpegArguments
            .sentenceAudio(input, input.value, range, "/cache/audio.m4a", caFile)
            .toList()
        val video = SceneFfmpegArguments
            .animatedAvifMediaCodec(
                input = input,
                acquiredInputValue = input.value,
                range = range,
                outputFile = "/cache/scene.avif",
                encoderName = TEST_AV1_ENCODER_NAME,
                contentSize = SceneVideoDimensions(width = 640, height = 360),
                outputSize = SceneVideoDimensions(width = 640, height = 360),
                tlsCaFile = caFile,
            )
            .toList()
        val videoProbe = SceneFfmpegArguments.videoProbe(input, input.value, caFile).toList()
        val probe = SceneFfmpegArguments.audioProbe(input, input.value, caFile).toList()

        assertEquals("0:2", video[video.indexOf("-map") + 1])
        assertEquals("2", videoProbe[videoProbe.indexOf("-select_streams") + 1])
        assertEquals("0:3", audio[audio.indexOf("-map") + 1])
        assertEquals("3", probe[probe.indexOf("-select_streams") + 1])
    }

    private fun assertSupported(snapshot: SceneVideoInputSnapshot) {
        assertNotNull(resolve(snapshot))
    }

    private fun resolve(snapshot: SceneVideoInputSnapshot): SceneVideoInputSpec? {
        return SceneVideoInputResolver.resolve(snapshot)
    }

    private fun supportedInput(): SceneVideoInputSpec {
        return requireNotNull(resolve(snapshot("https://media.example/video.mp4")))
    }

    private fun snapshot(
        value: String,
        headers: List<Pair<String, String>> = emptyList(),
        ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
        seekable: Boolean = true,
    ) = SceneVideoInputSnapshot(
        originalVideoValue = value,
        playableValue = value,
        headers = headers,
        ffmpegStreamArgs = ffmpegStreamArgs,
        ffmpegVideoArgs = emptyList(),
        seekable = seekable,
    )

    private companion object {
        const val TEST_AV1_ENCODER_NAME = "c2.android.av1.encoder"
        val ALLOWED_HEADERS = listOf(
            "User-Agent" to "Chimahon",
            "Accept" to "*/*",
            "Accept-Encoding" to "identity",
            "Accept-Language" to "en-GB",
            "Cache-Control" to "no-cache",
            "Origin" to "https://media.example",
            "Pragma" to "no-cache",
            "Referer" to "https://media.example/player",
        )
    }
}
