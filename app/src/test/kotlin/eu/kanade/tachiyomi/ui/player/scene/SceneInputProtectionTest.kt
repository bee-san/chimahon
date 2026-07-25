package eu.kanade.tachiyomi.ui.player.scene

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SceneInputProtectionTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `clear HLS graph is frozen with local playlists and absolute media references`() = runTest {
        val reads = mutableListOf<String>()
        val checker = checker(
            documents = mapOf(
                MASTER_URL to hls(
                    """
                    #EXTM3U
                    #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",URI="subs/subs.m3u8"
                    #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=1000,URI=iframe/iframe.m3u8
                    #EXT-X-STREAM-INF:BANDWIDTH=100000,SUBTITLES="subs"
                    video/main.m3u8
                    """,
                    resolvedValue = MASTER_URL,
                ),
                SUBTITLE_URL to hls(
                    """
                    #EXTM3U
                    #EXTINF:4,
                    captions.vtt
                    #EXT-X-ENDLIST
                    """,
                    resolvedValue = SUBTITLE_URL,
                ),
                IFRAME_URL to hls(
                    """
                    #EXTM3U
                    #EXTINF:4,
                    frame.ts
                    #EXT-X-ENDLIST
                    """,
                    resolvedValue = IFRAME_URL,
                ),
                MEDIA_URL to hls(
                    """
                    #EXTM3U
                    #EXT-X-MAP:URI="../init.mp4"
                    #EXT-X-PART:DURATION=0.5,URI=part-0.m4s
                    #EXT-X-DATERANGE:ID="ad",X-ASSET-URI="../ad.json"
                    #EXTINF:4,
                    segment.ts?token=per-resource
                    #EXT-X-ENDLIST
                    """,
                    resolvedValue = MEDIA_URL,
                ),
            ),
            reads = reads,
        )
        val workingDirectory = directory.resolve("frozen")

        val result = checker.check(
            input(
                MASTER_URL,
                headers = listOf("User-Agent" to "Chimahon", "X-Playback-Mode" to "scene"),
            ),
            workingDirectory,
        )

        val clear = assertInstanceOf(SceneInputProtectionResult.Clear::class.java, result)
        assertEquals(SceneVideoInputKind.LOCAL_FILE, clear.input.kind)
        assertEquals(
            listOf("User-Agent" to "Chimahon", "X-Playback-Mode" to "scene"),
            clear.input.headers,
        )
        assertEquals(
            SceneInputOption(
                name = "protocol_whitelist",
                value = "file,http,https,tcp,tls",
            ),
            clear.input.inputOptions.last(),
        )
        assertTrue(clear.input.value.startsWith(workingDirectory.absolutePath))
        assertEquals(listOf(MASTER_URL, SUBTITLE_URL, IFRAME_URL, MEDIA_URL), reads)

        val frozenFiles = workingDirectory.listFiles().orEmpty().sortedBy(File::getName)
        assertEquals(4, frozenFiles.size)
        val rootText = File(clear.input.value).readText()
        assertFalse("subs/subs.m3u8" in rootText)
        assertFalse("iframe/iframe.m3u8" in rootText)
        assertFalse("video/main.m3u8" in rootText)
        Regex("""playlist_\d{3}\.m3u8""")
            .findAll(rootText)
            .map { it.value }
            .forEach { localReference ->
                assertTrue(workingDirectory.resolve(localReference).isFile)
            }

        val frozenGraph = frozenFiles.joinToString("\n") { it.readText() }
        assertTrue("https://media.example/subs/captions.vtt" in frozenGraph)
        assertTrue("https://media.example/iframe/frame.ts" in frozenGraph)
        assertTrue("https://media.example/init.mp4" in frozenGraph)
        assertTrue("URI=https://media.example/video/part-0.m4s" in frozenGraph)
        assertTrue("X-ASSET-URI=\"https://media.example/ad.json\"" in frozenGraph)
        assertTrue("https://media.example/video/segment.ts?token=per-resource" in frozenGraph)
    }

    @Test
    fun `source supplied protocol whitelist is rejected before the manifest is read`() = runTest {
        val reads = mutableListOf<String>()
        val checker = checker(
            mapOf(MASTER_URL to hls("#EXTM3U\n#EXTINF:4,\nsegment.ts")),
            reads,
        )

        assertEquals(
            SceneInputProtectionResult.Unavailable,
            checker.check(
                input(
                    MASTER_URL,
                    inputOptions = listOf(SceneInputOption("protocol_whitelist", "file,http")),
                ),
                directory.resolve("source-protocols"),
            ),
        )
        assertTrue(reads.isEmpty())
    }

    @Test
    fun `AES and session keys are rejected without fetching key URIs`() = runTest {
        listOf(
            "#EXT-X-KEY:METHOD=AES-128,URI=\"secret.key\"",
            "#EXT-X-SESSION-KEY:METHOD=AES-128,URI=\"session.key\"",
        ).forEachIndexed { index, declaration ->
            val reads = mutableListOf<String>()
            val checker = checker(
                mapOf(
                    MASTER_URL to hls(
                        """
                        #EXTM3U
                        $declaration
                        #EXTINF:4,
                        segment.ts
                        """,
                    ),
                ),
                reads,
            )

            assertEquals(
                SceneInputProtectionResult.Protected(SceneCaptureUnsupportedReason.ENCRYPTED),
                checker.check(input(MASTER_URL), directory.resolve("keys-$index")),
            )
            assertEquals(listOf(MASTER_URL), reads)
            assertFalse(reads.any { it.endsWith(".key") })
        }
    }

    @Test
    fun `sample AES and non-identity key formats are DRM`() = runTest {
        val checker = checker(
            mapOf(
                MASTER_URL to hls(
                    """
                    #EXTM3U
                    #EXT-X-KEY:METHOD=SAMPLE-AES,URI="key",KEYFORMAT="com.apple.streamingkeydelivery"
                    """,
                ),
            ),
        )

        assertEquals(
            SceneInputProtectionResult.Protected(SceneCaptureUnsupportedReason.DRM),
            checker.check(input(MASTER_URL), directory.resolve("drm")),
        )
    }

    @Test
    fun `METHOD NONE remains clear when it has no key URI`() = runTest {
        val checker = checker(
            mapOf(
                MASTER_URL to hls(
                    """
                    #EXTM3U
                    #EXT-X-KEY:METHOD=NONE
                    #EXTINF:4,
                    clear.ts
                    #EXT-X-ENDLIST
                    """,
                ),
            ),
        )

        val result = checker.check(input(MASTER_URL), directory.resolve("none"))

        assertInstanceOf(SceneInputProtectionResult.Clear::class.java, result)
    }

    @Test
    fun `nested protected rendition rejects the entire graph`() = runTest {
        val checker = checker(
            mapOf(
                MASTER_URL to hls(
                    """
                    #EXTM3U
                    #EXT-X-STREAM-INF:BANDWIDTH=100000
                    protected.m3u8
                    """,
                ),
                PROTECTED_URL to hls(
                    """
                    #EXTM3U
                    #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
                    """,
                    resolvedValue = PROTECTED_URL,
                ),
            ),
        )

        assertEquals(
            SceneInputProtectionResult.Protected(SceneCaptureUnsupportedReason.ENCRYPTED),
            checker.check(input(MASTER_URL), directory.resolve("nested-protected")),
        )
    }

    @Test
    fun `incomplete oversized missing and overdeep graphs fail closed`() = runTest {
        val truncated = checker(
            mapOf(MASTER_URL to hls("#EXTM3U", complete = false)),
        )
        val oversized = checker(
            mapOf(
                MASTER_URL to SceneProtectionDocument(
                    resolvedValue = MASTER_URL,
                    bytes = "#EXTM3U\n".encodeToByteArray() + ByteArray(1_048_577),
                    complete = true,
                    contentType = "application/vnd.apple.mpegurl",
                ),
            ),
        )
        val missing = checker(
            mapOf(
                MASTER_URL to hls(
                    """
                    #EXTM3U
                    #EXT-X-STREAM-INF:BANDWIDTH=1
                    missing.m3u8
                    """,
                ),
            ),
        )
        val deepDocuments = (0..5).associate { depth ->
            val url = "https://media.example/$depth.m3u8"
            url to hls(
                """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1
                ${depth + 1}.m3u8
                """,
                resolvedValue = url,
            )
        }
        val deep = checker(deepDocuments)

        assertEquals(
            SceneInputProtectionResult.Unavailable,
            truncated.check(input(MASTER_URL), directory.resolve("truncated")),
        )
        assertEquals(
            SceneInputProtectionResult.Unavailable,
            oversized.check(input(MASTER_URL), directory.resolve("oversized")),
        )
        assertEquals(
            SceneInputProtectionResult.Unavailable,
            missing.check(input(MASTER_URL), directory.resolve("missing")),
        )
        assertEquals(
            SceneInputProtectionResult.Unavailable,
            deep.check(input("https://media.example/0.m3u8"), directory.resolve("deep")),
        )
    }

    @Test
    fun `unresolved unsafe and cross-origin references fail closed`() = runTest {
        listOf(
            "{\$segment}",
            "file:///etc/passwd",
            "content://private.provider/segment",
            "data:text/plain,secret",
            "http://127.0.0.1/private.ts",
            "https://other.example/segment.ts",
        ).forEachIndexed { index, reference ->
            val checker = checker(
                mapOf(
                    MASTER_URL to hls(
                        """
                        #EXTM3U
                        #EXTINF:4,
                        $reference
                        #EXT-X-ENDLIST
                        """,
                    ),
                ),
            )

            assertEquals(
                SceneInputProtectionResult.Unavailable,
                checker.check(input(MASTER_URL), directory.resolve("unsafe-$index")),
                reference,
            )
        }
    }

    @Test
    fun `cross-origin and downgrade manifest redirects fail closed`() = runTest {
        listOf(
            "https://cdn.example/master.m3u8",
            "http://media.example/master.m3u8",
        ).forEachIndexed { index, resolved ->
            val checker = checker(
                mapOf(
                    MASTER_URL to hls(
                        """
                        #EXTM3U
                        #EXTINF:4,
                        segment.ts
                        """,
                        resolvedValue = resolved,
                    ),
                ),
            )

            assertEquals(
                SceneInputProtectionResult.Unavailable,
                checker.check(input(MASTER_URL), directory.resolve("redirect-$index")),
            )
        }
    }

    @Test
    fun `authenticated or referrer-bound HLS fails before graph freeze`() = runTest {
        val unsafeInputs = listOf(
            input(MASTER_URL, headers = listOf("Authorization" to "Bearer secret")),
            input(MASTER_URL, headers = listOf("Cookie" to "session=secret")),
            input(MASTER_URL, headers = listOf("X-Playback-Token" to "secret")),
            input(MASTER_URL, headers = listOf("Referer" to "https://app.example/watch")),
            input(MASTER_URL, headers = listOf("Origin" to "https://app.example")),
            input(
                MASTER_URL,
                inputOptions = listOf(
                    SceneInputOption("referer", "https://app.example/watch"),
                ),
            ),
        )
        unsafeInputs.forEachIndexed { index, unsafeInput ->
            val reads = mutableListOf<String>()
            val checker = checker(
                mapOf(
                    MASTER_URL to hls(
                        """
                        #EXTM3U
                        #EXTINF:4,
                        same-origin.ts
                        #EXT-X-ENDLIST
                        """,
                    ),
                ),
                reads,
            )
            val workingDirectory = directory.resolve("authenticated-$index")

            assertEquals(
                SceneInputProtectionResult.Unavailable,
                checker.check(unsafeInput, workingDirectory),
            )
            assertEquals(listOf(MASTER_URL), reads)
            assertFalse(workingDirectory.exists())
        }
    }

    @Test
    fun `request header policy strips credentials and referrer after origin change`() {
        val input = input(
            MASTER_URL,
            headers = listOf(
                "Authorization" to "Bearer secret",
                "Cookie" to "session=secret",
                "X-Playback-Token" to "secret",
                "Referer" to "https://app.example/watch?token=secret",
                "User-Agent" to "Chimahon",
                "X-Playback-Mode" to "scene",
            ),
        )

        assertEquals(
            input.headers,
            SceneProtectionHeaderPolicy.headersForRequest(
                input,
                "https://media.example/redirected.m3u8",
                redirectChainStayedOnRootOrigin = true,
            ),
        )
        assertEquals(
            listOf("User-Agent" to "Chimahon", "X-Playback-Mode" to "scene"),
            SceneProtectionHeaderPolicy.headersForRequest(
                input,
                "https://cdn.example/redirected.m3u8",
                redirectChainStayedOnRootOrigin = false,
            ),
        )
    }

    @Test
    fun `MPD input is rejected without handing it to FFmpeg`() = runTest {
        val checker = checker(
            mapOf(
                MPD_URL to SceneProtectionDocument(
                    resolvedValue = MPD_URL,
                    bytes = """
                        <?xml version="1.0"?>
                        <MPD><Period /></MPD>
                    """.trimIndent().encodeToByteArray(),
                    complete = true,
                    contentType = "application/dash+xml",
                ),
            ),
        )

        assertEquals(
            SceneInputProtectionResult.Unavailable,
            checker.check(input(MPD_URL), directory.resolve("mpd")),
        )
    }

    @Test
    fun `malformed URI-like attributes fail closed`() = runTest {
        val checker = checker(
            mapOf(
                MASTER_URL to hls(
                    """
                    #EXTM3U
                    #EXT-X-MAP:URI "init.mp4"
                    #EXTINF:4,
                    segment.ts
                    """,
                ),
            ),
        )

        assertEquals(
            SceneInputProtectionResult.Unavailable,
            checker.check(input(MASTER_URL), directory.resolve("malformed-uri")),
        )
    }

    @Test
    fun `structural ISO BMFF protection boxes are rejected as DRM`() = runTest {
        val mp4Url = "https://media.example/video.mp4"
        val checker = checker(
            mapOf(
                mp4Url to SceneProtectionDocument(
                    resolvedValue = mp4Url,
                    bytes = byteArrayOf(0, 0, 0, 8) + "pssh".encodeToByteArray(),
                    complete = true,
                    contentType = "video/mp4",
                ),
            ),
        )

        assertEquals(
            SceneInputProtectionResult.Protected(SceneCaptureUnsupportedReason.DRM),
            checker.check(input(mp4Url), directory.resolve("bmff")),
        )
    }

    @Test
    fun `incomplete remote ISO BMFF inspection fails closed`() = runTest {
        val mp4Url = "https://media.example/large-video.mp4"
        val checker = checker(
            mapOf(
                mp4Url to SceneProtectionDocument(
                    resolvedValue = mp4Url,
                    bytes = byteArrayOf(0, 0, 0, 8) + "ftyp".encodeToByteArray(),
                    complete = false,
                    contentType = "video/mp4",
                ),
            ),
        )

        assertEquals(
            SceneInputProtectionResult.Unavailable,
            checker.check(input(mp4Url), directory.resolve("incomplete-bmff")),
        )
    }

    @Test
    fun `authenticated remote MP4 is rejected before native redirect handling`() = runTest {
        val mp4Url = "https://media.example/video.mp4"
        val checker = checker(
            mapOf(
                mp4Url to SceneProtectionDocument(
                    resolvedValue = mp4Url,
                    bytes = byteArrayOf(0, 0, 0, 12) +
                        "ftyp".encodeToByteArray() +
                        "isom".encodeToByteArray(),
                    complete = true,
                    contentType = "video/mp4",
                ),
            ),
        )

        assertEquals(
            SceneInputProtectionResult.Unavailable,
            checker.check(
                input(
                    value = mp4Url,
                    headers = listOf("Authorization" to "Bearer secret"),
                ),
                directory.resolve("authenticated-mp4"),
            ),
        )
    }

    private fun checker(
        documents: Map<String, SceneProtectionDocument>,
        reads: MutableList<String> = mutableListOf(),
    ): RecursiveSceneInputProtectionChecker {
        return RecursiveSceneInputProtectionChecker(
            documentReader = SceneProtectionDocumentReader { resource, _ ->
                reads += resource.value
                documents[resource.value]
            },
        )
    }

    private fun input(
        value: String,
        headers: List<Pair<String, String>> = emptyList(),
        inputOptions: List<SceneInputOption> = emptyList(),
    ): SceneVideoInputSpec {
        return SceneVideoInputSpec(
            value = value,
            kind = SceneVideoInputKind.REMOTE_HTTP,
            headers = headers,
            inputOptions = inputOptions,
            externalAudioValue = null,
            identity = SceneVideoIdentity(
                episodeId = 1L,
                sourceId = 2L,
                quality = "test",
                inputDigest = "digest",
            ),
        )
    }

    private fun hls(
        body: String,
        resolvedValue: String = MASTER_URL,
        complete: Boolean = true,
    ): SceneProtectionDocument {
        return SceneProtectionDocument(
            resolvedValue = resolvedValue,
            bytes = body.trimIndent().encodeToByteArray(),
            complete = complete,
            contentType = "application/vnd.apple.mpegurl",
        )
    }

    private companion object {
        const val MASTER_URL = "https://media.example/master.m3u8"
        const val SUBTITLE_URL = "https://media.example/subs/subs.m3u8"
        const val IFRAME_URL = "https://media.example/iframe/iframe.m3u8"
        const val MEDIA_URL = "https://media.example/video/main.m3u8"
        const val PROTECTED_URL = "https://media.example/protected.m3u8"
        const val MPD_URL = "https://media.example/manifest.mpd"
    }
}
