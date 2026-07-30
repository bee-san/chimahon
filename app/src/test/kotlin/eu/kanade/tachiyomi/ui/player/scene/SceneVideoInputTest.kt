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
    fun `AV1 encode writes raw MediaCodec packets`() {
        val input = supportedInput()
        val arguments = SceneFfmpegArguments.av1MediaCodecPackets(
            input = input,
            acquiredInputValue = "https://media.example/video.mp4",
            range = SceneTimeRange(1.25, 11.25),
            outputFile = "/cache/output.obu",
            encoderName = TEST_AV1_ENCODER_NAME,
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
        assertTrue(arguments.containsAll(listOf("-frames:v", "80", "-f", "data")))
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
        assertEquals(SceneFfmpegArguments.FRAME_FILTER, arguments[arguments.indexOf("-vf") + 1])
        assertTrue(SceneFfmpegArguments.FRAME_FILTER.contains("force_divisible_by=16"))
        assertFalse(arguments.contains("avif"))
        assertFalse(arguments.contains("-loop"))
    }

    @Test
    fun `AVIF remux copies the normalized OBU stream`() {
        assertEquals(
            listOf(
                "-f",
                "obu",
                "-framerate",
                "8",
                "-i",
                "/cache/input.obu",
                "-map",
                "0:v:0",
                "-c:v",
                "copy",
                "-loop",
                "0",
                "-f",
                "avif",
                "-y",
                "/cache/output.avif",
            ),
            SceneFfmpegArguments.animatedAvifFromObu(
                inputFile = "/cache/input.obu",
                outputFile = "/cache/output.avif",
            ).toList(),
        )
    }

    @Test
    fun `all native input commands restrict decoders without breaking ordinary media`() {
        val input = supportedInput()
        val range = SceneTimeRange(1.25, 2.25)
        val caFile = "/files/cacert.pem"
        val commands = listOf(
            SceneFfmpegArguments.av1MediaCodecPackets(
                input = input,
                acquiredInputValue = input.value,
                range = range,
                outputFile = "/cache/scene.obu",
                encoderName = TEST_AV1_ENCODER_NAME,
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
            SceneFfmpegArguments.av1MediaCodecPackets(
                input = input,
                acquiredInputValue = safValue,
                range = range,
                outputFile = "/cache/scene.obu",
                encoderName = TEST_AV1_ENCODER_NAME,
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
            .av1MediaCodecPackets(
                input = input,
                acquiredInputValue = input.value,
                range = range,
                outputFile = "/cache/scene.obu",
                encoderName = TEST_AV1_ENCODER_NAME,
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
