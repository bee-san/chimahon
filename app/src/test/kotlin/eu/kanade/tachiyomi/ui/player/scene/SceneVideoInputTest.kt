package eu.kanade.tachiyomi.ui.player.scene

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneVideoInputTest {
    @Test
    fun `local and file inputs are normalized`() {
        assertSupported("/video/file.mp4", SceneVideoInputKind.LOCAL_FILE, "/video/file.mp4")
        assertSupported("file:///video/file.mp4", SceneVideoInputKind.LOCAL_FILE, "/video/file.mp4")
    }

    @Test
    fun `content remote and HLS inputs are supported`() {
        assertSupported("content://media/video/42", SceneVideoInputKind.CONTENT_URI, "content://media/video/42")
        assertSupported("https://example.test/video.mp4", SceneVideoInputKind.REMOTE_HTTP, "https://example.test/video.mp4")
        assertSupported("https://example.test/master.m3u8", SceneVideoInputKind.REMOTE_HTTP, "https://example.test/master.m3u8")
    }

    @Test
    fun `safe actual player path takes precedence`() {
        val supported = resolve(
            input = "https://example.test/original.m3u8",
            playable = "https://cdn.example.test/resolved.m3u8",
        )

        assertEquals("https://cdn.example.test/resolved.m3u8", supported.input.value)
    }

    @Test
    fun `same origin playable with an explicit default port retains credentials and referer`() {
        val supported = resolve(
            input = "https://example.test/original.m3u8",
            playable = "https://example.test:443/resolved.m3u8",
            headers = listOf("Authorization" to "Bearer same-origin"),
            streamArgs = listOf("referer" to "https://example.test/watch"),
        )

        assertEquals("https://example.test:443/resolved.m3u8", supported.input.value)
        assertEquals(listOf("Authorization" to "Bearer same-origin"), supported.input.headers)
        assertEquals(
            listOf(SceneInputOption("referer", "https://example.test/watch")),
            supported.input.inputOptions,
        )
    }

    @Test
    fun `cross origin playable with origin bound metadata falls back to normalized original`() {
        val supported = resolve(
            input = "https://origin.example/video.m3u8",
            playable = "https://cdn.example/resolved.m3u8",
            headers = listOf("X-Playback-Token" to "video-secret"),
            streamArgs = listOf("referer" to "https://origin.example/watch"),
        )

        assertEquals("https://origin.example/video.m3u8", supported.input.value)
        assertEquals(listOf("X-Playback-Token" to "video-secret"), supported.input.headers)
        assertEquals(
            listOf(SceneInputOption("referer", "https://origin.example/watch")),
            supported.input.inputOptions,
        )
    }

    @Test
    fun `cross origin playable with credentials and no usable original is typed unsafe`() {
        val result = SceneVideoInputResolver.resolve(
            snapshot(
                input = "edl://transient-source",
                playable = "https://cdn.example/resolved.m3u8",
                headers = listOf("Authorization" to "Bearer source-secret"),
            ),
        )

        val unsupported = assertInstanceOf(SceneVideoInputResolution.Unsupported::class.java, result)
        assertEquals(SceneUnsupportedReason.UNSAFE_INPUT_OPTION, unsupported.reason)
    }

    @Test
    fun `credential free cross origin playable retains explicitly portable metadata`() {
        val supported = resolve(
            input = "https://origin.example/video.m3u8",
            playable = "https://cdn.example/resolved.m3u8",
            headers = listOf(
                "User-Agent" to "Chimahon",
                "Accept-Language" to "en-GB",
            ),
            streamArgs = listOf("rw_timeout" to "5000000"),
        )

        assertEquals("https://cdn.example/resolved.m3u8", supported.input.value)
        assertEquals(
            listOf("User-Agent" to "Chimahon", "Accept-Language" to "en-GB"),
            supported.input.headers,
        )
        assertEquals(
            listOf(SceneInputOption("rw_timeout", "5000000")),
            supported.input.inputOptions,
        )
    }

    @Test
    fun `local and content playable inputs strip network headers and referer`() {
        listOf(
            "/cache/resolved-video.mp4" to SceneVideoInputKind.LOCAL_FILE,
            "content://media/video/42" to SceneVideoInputKind.CONTENT_URI,
        ).forEach { (playable, expectedKind) ->
            val supported = resolve(
                input = "https://origin.example/video.m3u8",
                playable = playable,
                headers = listOf("Authorization" to "Bearer video-secret"),
                streamArgs = listOf(
                    "referer" to "https://origin.example/watch",
                    "rw_timeout" to "5000000",
                ),
            )

            assertEquals(expectedKind, supported.input.kind)
            assertTrue(supported.input.headers.isEmpty())
            assertEquals(
                listOf(SceneInputOption("rw_timeout", "5000000")),
                supported.input.inputOptions,
            )
        }
    }

    @Test
    fun `transient player path falls back to original source input`() {
        val supported = resolve(
            input = "content://media/video/42",
            playable = "fdclose://71",
        )

        assertEquals("content://media/video/42", supported.input.value)
    }

    @Test
    fun `transient original input is rejected`() {
        assertUnsupported("edl://%2", SceneUnsupportedReason.TRANSIENT_INPUT)
        assertUnsupported("fdclose://71", SceneUnsupportedReason.TRANSIENT_INPUT)
    }

    @Test
    fun `video-only DASH retains external audio only for sentence audio`() {
        val supported = resolve(
            input = "https://example.test/video-only.webm",
            audio = "https://example.test/audio-only.webm",
        )

        assertEquals("https://example.test/video-only.webm", supported.input.value)
        assertEquals("https://example.test/audio-only.webm", supported.input.externalAudioValue)
    }

    @Test
    fun `headers and safe source options become individual FFmpeg arguments`() {
        val supported = resolve(
            input = "https://example.test/video.m3u8",
            headers = listOf("Authorization" to "Bearer secret", "Referer" to "https://example.test"),
            streamArgs = listOf("rw_timeout" to "30000000"),
            videoArgs = listOf("-user_agent" to "Chimahon"),
        )
        val args = SceneFfmpegArguments.frameExtraction(
            input = supported.input,
            acquiredInputValue = supported.input.value,
            range = SceneTimeRange(2.0, 4.5),
            outputPattern = "/cache/frame_%03d.png",
        )

        assertTrue(args.containsAllInOrder("-rw_timeout", "30000000"))
        assertTrue(args.containsAllInOrder("-user_agent", "Chimahon"))
        assertTrue(args.containsAllInOrder("-headers", "Authorization: Bearer secret\r\nReferer: https://example.test\r\n"))
        assertTrue(args.containsAllInOrder("-ss", "2", "-i", "https://example.test/video.m3u8"))
        assertTrue(args.containsAllInOrder("-map", "0:v:0", "-an", "-sn", "-dn"))
        assertTrue(args.containsAllInOrder("-t", "2.5"))
        assertTrue(args.containsAllInOrder("-frames:v", "80"))
        assertTrue(args.takeLast(2) == listOf("-y", "/cache/frame_%03d.png"))
    }

    @Test
    fun `mux arguments copy static WebPs into an infinite animation`() {
        assertArrayEquals(
            arrayOf(
                "-framerate",
                "8",
                "-i",
                "/cache/frame_%03d.webp",
                "-c:v",
                "copy",
                "-loop",
                "0",
                "-f",
                "webp",
                "-y",
                "/cache/output.webp",
            ),
            SceneFfmpegArguments.animatedWebpMux(
                inputPattern = "/cache/frame_%03d.webp",
                outputFile = "/cache/output.webp",
            ),
        )
    }

    @Test
    fun `frozen local HLS keeps headers for probe and frame subrequests`() {
        val input = resolve(
            input = "https://media.example/master.m3u8",
            headers = listOf("User-Agent" to "Chimahon", "X-Playback-Mode" to "scene"),
        ).input.copy(
            value = "/cache/checked_input/playlist_000.m3u8",
            kind = SceneVideoInputKind.LOCAL_FILE,
            inputOptions = listOf(
                SceneInputOption("protocol_whitelist", "file,http,https,tcp,tls"),
            ),
        )
        val headerBlock = "User-Agent: Chimahon\r\nX-Playback-Mode: scene\r\n"

        val extraction = SceneFfmpegArguments.frameExtraction(
            input = input,
            acquiredInputValue = input.value,
            range = SceneTimeRange(2.0, 4.0),
            outputPattern = "/cache/frame_%03d.png",
        )
        val probe = SceneFfmpegArguments.videoProbe(
            input = input,
            acquiredInputValue = input.value,
        )

        assertTrue(extraction.containsAllInOrder("-headers", headerBlock, "-ss", "2", "-i", input.value))
        assertTrue(probe.containsAllInOrder("-headers", headerBlock, "-v", "error"))
        assertTrue(
            extraction.containsAllInOrder(
                "-protocol_whitelist",
                "file,http,https,tcp,tls",
                "-headers",
            ),
        )
        assertTrue(
            probe.containsAllInOrder(
                "-protocol_whitelist",
                "file,http,https,tcp,tls",
                "-headers",
            ),
        )
    }

    @Test
    fun `pipeline ownership options are rejected from both extension argument lists`() {
        val rejectedNames = listOf(
            "i",
            "map",
            "vf",
            "filter_complex",
            "c:v",
            "codec",
            "f",
            "y",
            "output",
        )
        rejectedNames.forEach { name ->
            val result = SceneVideoInputResolver.resolve(
                snapshot(streamArgs = listOf(name to "attacker-value")),
            )

            val unsupported = assertInstanceOf(SceneVideoInputResolution.Unsupported::class.java, result)
            assertEquals(SceneUnsupportedReason.UNSAFE_INPUT_OPTION, unsupported.reason)
        }
    }

    @Test
    fun `safe option values are constrained and cannot inject headers`() {
        val unsafeValues = listOf(
            "rw_timeout" to "-1",
            "rw_timeout" to "not-a-number",
            "reconnect" to "yes",
            "reconnect_delay_max" to "999999",
            "referer" to "file:///private/file",
            "user_agent" to "safe\r\nAuthorization: secret",
            "headers" to "Authorization: secret",
            "cookies" to "session=secret",
            "key_file" to "/private/key",
            "protocol_whitelist" to "file,http,https",
        )
        unsafeValues.forEach { option ->
            val result = SceneVideoInputResolver.resolve(
                snapshot(streamArgs = listOf(option)),
            )

            val unsupported = assertInstanceOf(SceneVideoInputResolution.Unsupported::class.java, result)
            assertEquals(SceneUnsupportedReason.UNSAFE_INPUT_OPTION, unsupported.reason)
        }
    }

    @Test
    fun `header names and values cannot inject additional headers`() {
        val unsafeHeaders = listOf(
            listOf("Authorization\r\nX-Evil" to "value"),
            listOf("Authorization" to "Bearer token\r\nX-Evil: value"),
            listOf("Bad Header" to "value"),
            listOf("X-Test" to "value\u0000suffix"),
        )

        unsafeHeaders.forEach { headers ->
            val result = SceneVideoInputResolver.resolve(snapshot(headers = headers))
            val unsupported = assertInstanceOf(SceneVideoInputResolution.Unsupported::class.java, result)
            assertEquals(SceneUnsupportedReason.UNSAFE_INPUT_OPTION, unsupported.reason)
        }
    }

    @Test
    fun `unknown seekability is rejected instead of assumed`() {
        val result = SceneVideoInputResolver.resolve(
            snapshot().copy(seekable = null),
        )

        val unsupported = assertInstanceOf(SceneVideoInputResolution.Unsupported::class.java, result)
        assertEquals(SceneUnsupportedReason.NON_SEEKABLE, unsupported.reason)
    }

    @Test
    fun `unsupported schemes torrent protected and non-seekable inputs are typed`() {
        assertUnsupported("ftp://example.test/video.mp4", SceneUnsupportedReason.UNSUPPORTED_SCHEME)
        assertUnsupported("magnet:?xt=urn:btih:abc", SceneUnsupportedReason.TORRENT)
        assertUnsupported("https://example.test/video.torrent", SceneUnsupportedReason.TORRENT)
        assertUnsupported("https://example.test/live.m3u8", SceneUnsupportedReason.NON_SEEKABLE, seekable = false)
        assertUnsupported("https://example.test/video.mpd", SceneUnsupportedReason.DRM, drm = true)
        assertUnsupported("https://example.test/video.m3u8", SceneUnsupportedReason.ENCRYPTED, encrypted = true)
    }

    @Test
    fun `torrent playback requires a stable seekable local or content player input`() {
        val local = resolve(
            input = "magnet:?xt=urn:btih:abc",
            playable = "/data/user/0/app.komikku/cache/torrent/video.mp4",
        )
        val content = resolve(
            input = "https://example.test/video.torrent",
            playable = "content://app.komikku.torrent/video/42",
        )

        assertEquals(SceneVideoInputKind.LOCAL_FILE, local.input.kind)
        assertEquals("/data/user/0/app.komikku/cache/torrent/video.mp4", local.input.value)
        assertEquals(SceneVideoInputKind.CONTENT_URI, content.input.kind)
        assertEquals("content://app.komikku.torrent/video/42", content.input.value)

        listOf(
            null,
            "fdclose://71",
            "https://127.0.0.1:8090/stream",
        ).forEach { playable ->
            val result = SceneVideoInputResolver.resolve(
                snapshot(
                    input = "magnet:?xt=urn:btih:abc",
                    playable = playable,
                ),
            )
            val unsupported = assertInstanceOf(SceneVideoInputResolution.Unsupported::class.java, result)
            assertEquals(SceneUnsupportedReason.TORRENT, unsupported.reason)
        }
        val nonSeekable = SceneVideoInputResolver.resolve(
            snapshot(
                input = "magnet:?xt=urn:btih:abc",
                playable = "/cache/torrent/video.mp4",
                seekable = false,
            ),
        )
        assertEquals(
            SceneUnsupportedReason.TORRENT,
            assertInstanceOf(SceneVideoInputResolution.Unsupported::class.java, nonSeekable).reason,
        )
    }

    @Test
    fun `secret headers options and signed URL values are redacted`() {
        val headers = SceneSecretRedactor.redactHeaders(
            listOf(
                "Authorization" to "Bearer secret",
                "Cookie" to "session=secret",
                "Referer" to "https://safe.test",
            ),
        )
        assertEquals("<redacted>", headers[0].second)
        assertEquals("<redacted>", headers[1].second)
        assertEquals("https://safe.test", headers[2].second)
        assertEquals(
            "https://safe.test/video?token=<redacted>",
            SceneSecretRedactor.redactHeaders(
                listOf("Referer" to "https://safe.test/video?token=secret"),
            ).single().second,
        )

        val url = SceneSecretRedactor.redactUrl(
            "https://cdn.test/video.m3u8?quality=1080&token=secret&X-Amz-Signature=abc#fragment",
        )
        assertTrue(url.contains("quality=1080"))
        assertTrue(url.contains("token=<redacted>"))
        assertTrue(url.contains("X-Amz-Signature=<redacted>"))
        assertFalse(url.contains("secret"))
        assertFalse(url.contains("abc"))
        assertEquals("<redacted>", SceneSecretRedactor.redactOptionName("headers"))
        assertEquals(
            "https://cdn.test/video.m3u8#<redacted>",
            SceneSecretRedactor.redactUrl("https://cdn.test/video.m3u8#token=secret"),
        )
        assertEquals(
            "https://<redacted>@cdn.test/video.m3u8?quality=1080",
            SceneSecretRedactor.redactUrl(
                "https://account:password@cdn.test/video.m3u8?quality=1080",
            ),
        )
        val customHeaders = SceneSecretRedactor.redactHeaders(
            listOf(
                "X-Playback-Token" to "token-secret",
                "X-Custom-Auth" to "auth-secret",
                "X-Session-Id" to "session-secret",
                "X-Video-Label" to "episode-1",
            ),
        )
        assertEquals("<redacted>", customHeaders[0].second)
        assertEquals("<redacted>", customHeaders[1].second)
        assertEquals("<redacted>", customHeaders[2].second)
        assertEquals("episode-1", customHeaders[3].second)
    }

    @Test
    fun `input snapshots and specs do not expose secrets through toString`() {
        val snapshot = snapshot(
            input = "https://account:password@cdn.test/video.m3u8?token=secret",
            headers = listOf(
                "Authorization" to "Bearer hidden",
                "X-Custom-Token" to "custom hidden",
            ),
            streamArgs = listOf("user_agent" to "Chimahon"),
        )
        val supported = assertInstanceOf(
            SceneVideoInputResolution.Supported::class.java,
            SceneVideoInputResolver.resolve(snapshot),
        )

        assertFalse(snapshot.toString().contains("secret"))
        assertFalse(snapshot.toString().contains("account"))
        assertFalse(snapshot.toString().contains("password"))
        assertFalse(snapshot.toString().contains("Bearer hidden"))
        assertFalse(snapshot.toString().contains("custom hidden"))
        assertFalse(supported.toString().contains("secret"))
        assertFalse(supported.toString().contains("account"))
        assertFalse(supported.toString().contains("password"))
        assertFalse(supported.toString().contains("Bearer hidden"))
        assertFalse(supported.toString().contains("custom hidden"))
    }

    private fun assertSupported(
        input: String,
        kind: SceneVideoInputKind,
        value: String,
    ) {
        val supported = resolve(input)
        assertEquals(kind, supported.input.kind)
        assertEquals(value, supported.input.value)
    }

    private fun assertUnsupported(
        input: String,
        reason: SceneUnsupportedReason,
        seekable: Boolean = true,
        drm: Boolean = false,
        encrypted: Boolean = false,
    ) {
        val result = SceneVideoInputResolver.resolve(
            snapshot(
                input = input,
                seekable = seekable,
                drm = drm,
                encrypted = encrypted,
            ),
        )
        val unsupported = assertInstanceOf(SceneVideoInputResolution.Unsupported::class.java, result)
        assertEquals(reason, unsupported.reason)
    }

    private fun resolve(
        input: String,
        playable: String? = null,
        audio: String? = null,
        headers: List<Pair<String, String>> = emptyList(),
        streamArgs: List<Pair<String, String>> = emptyList(),
        videoArgs: List<Pair<String, String>> = emptyList(),
    ): SceneVideoInputResolution.Supported {
        return assertInstanceOf(
            SceneVideoInputResolution.Supported::class.java,
            SceneVideoInputResolver.resolve(
                snapshot(
                    input = input,
                    playable = playable,
                    audio = audio,
                    headers = headers,
                    streamArgs = streamArgs,
                    videoArgs = videoArgs,
                ),
            ),
        )
    }

    private fun snapshot(
        input: String = "https://example.test/video.mp4",
        playable: String? = null,
        audio: String? = null,
        headers: List<Pair<String, String>> = emptyList(),
        streamArgs: List<Pair<String, String>> = emptyList(),
        videoArgs: List<Pair<String, String>> = emptyList(),
        seekable: Boolean = true,
        drm: Boolean = false,
        encrypted: Boolean = false,
    ) = SceneVideoInputSnapshot(
        originalVideoValue = input,
        playableValue = playable,
        externalAudioValue = audio,
        headers = headers,
        ffmpegStreamArgs = streamArgs,
        ffmpegVideoArgs = videoArgs,
        episodeId = 4L,
        sourceId = 8L,
        quality = "1080p",
        seekable = seekable,
        drmProtected = drm,
        encrypted = encrypted,
    )

    private fun Array<String>.containsAllInOrder(vararg expected: String): Boolean {
        var cursor = 0
        expected.forEach { value ->
            cursor = indexOfFirstFrom(cursor, value)
            if (cursor < 0) return false
            cursor++
        }
        return true
    }

    private fun Array<String>.indexOfFirstFrom(start: Int, value: String): Int {
        for (index in start until size) {
            if (this[index] == value) return index
        }
        return -1
    }
}
