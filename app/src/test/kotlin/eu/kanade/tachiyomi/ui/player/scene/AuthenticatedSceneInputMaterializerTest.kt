package eu.kanade.tachiyomi.ui.player.scene

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File

class AuthenticatedSceneInputMaterializerTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `authenticated direct remote is downloaded with scoped request metadata`() = runBlocking {
        val source = "https://media.example/protected/video.webm?token=url-secret"
        val body = "direct-video-payload".encodeToByteArray()
        val factory = RecordingExchangeFactory(
            mapOf(
                source to FakeResponse(
                    contentType = "video/webm",
                    body = body,
                ),
            ),
        )
        val input = authenticatedInput(source)

        val result = materializer(factory).materialize(
            input = input,
            directory = directory.resolve("direct"),
        )

        val materialized = assertInstanceOf(
            SceneAuthenticatedMaterializationResult.Materialized::class.java,
            result,
        )
        assertFalse(materialized.isFullyLocalHls)
        assertEquals(SceneVideoInputKind.LOCAL_FILE, materialized.input.kind)
        assertArrayEquals(body, File(materialized.input.value).readBytes())
        assertTrue(materialized.input.headers.isEmpty())
        assertEquals(
            listOf(SceneInputOption("probesize", "1048576")),
            materialized.input.inputOptions,
        )
        assertNull(materialized.input.externalAudioValue)

        val request = factory.requests.single()
        assertEquals(source, request.url.toString())
        assertEquals("Bearer request-secret", request.header("Authorization"))
        assertEquals("session=request-secret", request.header("Cookie"))
        assertEquals("https://media.example", request.header("Origin"))
        assertEquals("https://media.example/watch?token=referer-secret", request.header("Referer"))
        assertEquals("SceneMaterializer/1.0", request.header("User-Agent"))
        assertEquals("identity", request.header("Accept-Encoding"))
    }

    @Test
    fun `authenticated HLS graph is fully local and credentials never enter FFmpeg arguments`() = runBlocking {
        val root = "https://media.example/path/master.m3u8?session=root-secret"
        val variant = "https://media.example/path/variant/media.m3u8?quality=720"
        val firstSegment = "https://media.example/path/segments/one.ts?token=segment-one"
        val secondSegment = "https://media.example/path/segments/two.ts?token=segment-two"
        val factory = RecordingExchangeFactory(
            mapOf(
                root to FakeResponse.hls(
                    """
                    #EXTM3U
                    #EXT-X-STREAM-INF:BANDWIDTH=100000
                    variant/media.m3u8?quality=720
                    """,
                ),
                variant to FakeResponse.hls(
                    """
                    #EXTM3U
                    #EXTINF:4,
                    ../segments/one.ts?token=segment-one
                    #EXTINF:4,
                    ../segments/two.ts?token=segment-two
                    #EXT-X-ENDLIST
                    """,
                ),
                firstSegment to FakeResponse(body = "segment-one".encodeToByteArray()),
                secondSegment to FakeResponse(body = "segment-two".encodeToByteArray()),
            ),
        )
        val input = authenticatedInput(root)
        val checker = MaterializingSceneInputProtectionChecker(
            materializer = materializer(factory),
            delegate = SceneInputProtectionChecker { localInput, _ ->
                SceneInputProtectionResult.Clear(
                    localInput.copy(
                        inputOptions = localInput.inputOptions + SceneInputOption(
                            name = "protocol_whitelist",
                            value = "file,http,https,tcp,tls",
                        ),
                    ),
                )
            },
        )

        val result = checker.check(
            input = input,
            workingDirectory = directory.resolve("checked"),
        )

        val clear = assertInstanceOf(SceneInputProtectionResult.Clear::class.java, result)
        assertEquals(SceneVideoInputKind.LOCAL_FILE, clear.input.kind)
        assertTrue(clear.input.headers.isEmpty())
        assertNull(clear.input.externalAudioValue)
        assertEquals(
            listOf(
                SceneInputOption("probesize", "1048576"),
                SceneInputOption("protocol_whitelist", "file"),
            ),
            clear.input.inputOptions,
        )

        val rootPlaylist = File(clear.input.value)
        val localDirectory = rootPlaylist.parentFile
        val rootText = rootPlaylist.readText()
        val variantText = localDirectory.resolve("playlist_001.m3u8").readText()
        val allPlaylistText = rootText + variantText
        assertTrue("playlist_001.m3u8" in rootText)
        assertTrue("media_0000.ts" in variantText)
        assertTrue("media_0001.ts" in variantText)
        assertFalse("https://" in allPlaylistText)
        assertFalse("secret" in allPlaylistText)
        assertFalse("?token=" in allPlaylistText)
        assertArrayEquals(
            "segment-one".encodeToByteArray(),
            localDirectory.resolve("media_0000.ts").readBytes(),
        )
        assertArrayEquals(
            "segment-two".encodeToByteArray(),
            localDirectory.resolve("media_0001.ts").readBytes(),
        )
        assertEquals(
            listOf(root, variant, firstSegment, secondSegment),
            factory.requests.map { it.url.toString() },
        )
        assertTrue(
            factory.requests.all {
                it.header("Authorization") == "Bearer request-secret" &&
                    it.header("Cookie") == "session=request-secret" &&
                    it.header("Origin") == "https://media.example" &&
                    it.header("Referer") == "https://media.example/watch?token=referer-secret"
            },
        )

        val arguments = SceneFfmpegArguments.frameExtraction(
            input = clear.input,
            acquiredInputValue = clear.input.value,
            range = SceneTimeRange(1.0, 3.0),
            outputPattern = directory.resolve("frame_%03d.png").absolutePath,
        )
        val joinedArguments = arguments.joinToString(separator = "\n")
        assertFalse("-headers" in arguments)
        assertFalse("-referer" in arguments)
        assertFalse("-user_agent" in arguments)
        assertFalse("-rw_timeout" in arguments)
        assertFalse("request-secret" in joinedArguments)
        assertFalse("referer-secret" in joinedArguments)
        assertFalse("root-secret" in joinedArguments)
        assertFalse("https://media.example" in joinedArguments)
        assertTrue(arguments.containsAllInOrder("-protocol_whitelist", "file"))
    }

    @Test
    fun `same origin redirects retain scoped credentials`() = runBlocking {
        val initial = "https://media.example/protected/video"
        val redirected = "https://media.example/final/video.webm?sig=redirect-secret"
        val factory = RecordingExchangeFactory(
            mapOf(
                initial to FakeResponse(
                    code = 302,
                    location = "../final/video.webm?sig=redirect-secret",
                ),
                redirected to FakeResponse(
                    contentType = "video/webm",
                    body = "redirected-video".encodeToByteArray(),
                ),
            ),
        )

        val result = materializer(factory).materialize(
            input = authenticatedInput(initial),
            directory = directory.resolve("same-origin-redirect"),
        )

        assertInstanceOf(SceneAuthenticatedMaterializationResult.Materialized::class.java, result)
        assertEquals(
            listOf(initial, redirected),
            factory.requests.map { it.url.toString() },
        )
        assertTrue(
            factory.requests.all {
                it.header("Authorization") == "Bearer request-secret" &&
                    it.header("Origin") == "https://media.example"
            },
        )
    }

    @Test
    fun `cross origin redirect is rejected before credentials leave the root origin`() = runBlocking {
        val initial = "https://media.example/protected/video"
        val crossOrigin = "https://evil.example/stolen.webm"
        val factory = RecordingExchangeFactory(
            mapOf(
                initial to FakeResponse(
                    code = 302,
                    location = crossOrigin,
                ),
                crossOrigin to FakeResponse(body = "must-not-be-read".encodeToByteArray()),
            ),
        )
        val outputDirectory = directory.resolve("cross-origin-redirect")

        val result = materializer(factory).materialize(
            input = authenticatedInput(initial),
            directory = outputDirectory,
        )

        assertEquals(SceneAuthenticatedMaterializationResult.Unavailable, result)
        assertEquals(listOf(initial), factory.requests.map { it.url.toString() })
        assertEquals("Bearer request-secret", factory.requests.single().header("Authorization"))
        assertFalse(outputDirectory.exists())
    }

    @Test
    fun `cross origin HLS reference is rejected without a subrequest`() = runBlocking {
        val root = "https://media.example/master.m3u8"
        val crossOrigin = "https://evil.example/segment.ts"
        val factory = RecordingExchangeFactory(
            mapOf(
                root to FakeResponse.hls(
                    """
                    #EXTM3U
                    #EXTINF:4,
                    $crossOrigin
                    #EXT-X-ENDLIST
                    """,
                ),
                crossOrigin to FakeResponse(body = "must-not-be-read".encodeToByteArray()),
            ),
        )
        val outputDirectory = directory.resolve("cross-origin-reference")

        val result = materializer(factory).materialize(
            input = authenticatedInput(root),
            directory = outputDirectory,
        )

        assertEquals(SceneAuthenticatedMaterializationResult.Unavailable, result)
        assertEquals(listOf(root), factory.requests.map { it.url.toString() })
        assertFalse(outputDirectory.exists())
    }

    @Test
    fun `HLS keys and structurally protected fragmented MP4 are rejected`() = runBlocking {
        val encryptedRoot = "https://media.example/encrypted.m3u8"
        val encryptedFactory = RecordingExchangeFactory(
            mapOf(
                encryptedRoot to FakeResponse.hls(
                    """
                    #EXTM3U
                    #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
                    #EXTINF:4,
                    segment.ts
                    #EXT-X-ENDLIST
                    """,
                ),
            ),
        )

        val encryptedResult = materializer(encryptedFactory).materialize(
            input = authenticatedInput(encryptedRoot),
            directory = directory.resolve("encrypted"),
        )

        assertEquals(
            SceneAuthenticatedMaterializationResult.Protected(
                SceneCaptureUnsupportedReason.ENCRYPTED,
            ),
            encryptedResult,
        )
        assertEquals(listOf(encryptedRoot), encryptedFactory.requests.map { it.url.toString() })

        val drmRoot = "https://media.example/drm.m3u8"
        val drmSegment = "https://media.example/segment.m4s"
        val drmFactory = RecordingExchangeFactory(
            mapOf(
                drmRoot to FakeResponse.hls(
                    """
                    #EXTM3U
                    #EXTINF:4,
                    segment.m4s
                    #EXT-X-ENDLIST
                    """,
                ),
                drmSegment to FakeResponse(
                    contentType = "video/iso.segment",
                    body = isoBmffBox("pssh"),
                ),
            ),
        )

        val drmResult = materializer(drmFactory).materialize(
            input = authenticatedInput(drmRoot),
            directory = directory.resolve("drm"),
        )

        assertEquals(
            SceneAuthenticatedMaterializationResult.Protected(
                SceneCaptureUnsupportedReason.DRM,
            ),
            drmResult,
        )
        assertEquals(
            listOf(drmRoot, drmSegment),
            drmFactory.requests.map { it.url.toString() },
        )
    }

    private fun materializer(
        factory: RecordingExchangeFactory,
    ): EagerSceneAuthenticatedInputMaterializer {
        return EagerSceneAuthenticatedInputMaterializer(
            fetcher = SameOriginSceneRemoteFetcher(factory),
            limits = SceneMaterializationLimits(
                timeoutMillis = 10_000L,
                maxManifestBytes = 64L * 1024L,
                maxResourceBytes = 1024L * 1024L,
                maxTotalBytes = 4L * 1024L * 1024L,
                maxResourceCount = 32,
                maxPlaylistCount = 8,
                maxPlaylistDepth = 4,
            ),
        )
    }

    private fun authenticatedInput(value: String): SceneVideoInputSpec {
        return SceneVideoInputSpec(
            value = value,
            kind = SceneVideoInputKind.REMOTE_HTTP,
            headers = listOf(
                "Authorization" to "Bearer request-secret",
                "Cookie" to "session=request-secret",
                "Origin" to "https://media.example",
            ),
            inputOptions = listOf(
                SceneInputOption(
                    "referer",
                    "https://media.example/watch?token=referer-secret",
                ),
                SceneInputOption("user_agent", "SceneMaterializer/1.0"),
                SceneInputOption("rw_timeout", "30000000"),
                SceneInputOption("probesize", "1048576"),
            ),
            externalAudioValue = "https://media.example/external-audio.m4a?token=audio-secret",
            identity = SceneVideoIdentity(
                episodeId = 1L,
                sourceId = 2L,
                quality = "720p",
                inputDigest = "input-digest",
            ),
        )
    }

    private fun isoBmffBox(type: String): ByteArray {
        require(type.length == 4)
        return byteArrayOf(0, 0, 0, 8) + type.encodeToByteArray()
    }

    private data class FakeResponse(
        val code: Int = 200,
        val location: String? = null,
        val contentType: String? = null,
        val contentEncoding: String? = null,
        val body: ByteArray = byteArrayOf(),
    ) {
        companion object {
            fun hls(contents: String): FakeResponse {
                return FakeResponse(
                    contentType = "application/vnd.apple.mpegurl",
                    body = contents.trimIndent().trim().plus("\n").encodeToByteArray(),
                )
            }
        }
    }

    private class RecordingExchangeFactory(
        private val responses: Map<String, FakeResponse>,
    ) : SceneRemoteExchangeFactory {
        val requests = mutableListOf<Request>()

        override suspend fun execute(request: Request): SceneRemoteExchange? {
            requests += request
            val response = responses[request.url.toString()] ?: return null
            return SceneRemoteExchange(
                code = response.code,
                location = response.location,
                contentType = response.contentType,
                contentEncoding = response.contentEncoding,
                contentLength = response.body.size.toLong(),
                body = ByteArrayInputStream(response.body),
            )
        }
    }

    private fun Array<String>.containsAllInOrder(vararg values: String): Boolean {
        var currentIndex = 0
        values.forEach { value ->
            val found = indexOfFirstFrom(currentIndex) { it == value }
            if (found < 0) return false
            currentIndex = found + 1
        }
        return true
    }

    private inline fun Array<String>.indexOfFirstFrom(
        startIndex: Int,
        predicate: (String) -> Boolean,
    ): Int {
        for (index in startIndex..lastIndex) {
            if (predicate(this[index])) return index
        }
        return -1
    }
}
