package eu.kanade.tachiyomi.ui.player.scene

import android.content.Context
import chimahon.anki.AnkiMediaFileOwnership
import chimahon.anki.AnkiMediaSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SceneSentenceAudioServiceTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `service extracts from frozen manifest and removes checked graph`() = runTest {
        var executedArguments: List<String>? = null
        val service = service(
            executor = PlayerFfmpegSessionExecutor { arguments, _, _ ->
                executedArguments = arguments.toList()
                File(arguments.last()).writeBytes(byteArrayOf(1, 2, 3))
                true
            },
            protectionChecker = freezingProtectionChecker(),
        )

        val result = service.prepare(request())

        val source = assertInstanceOf(AnkiMediaSource.FileSource::class.java, result)
        assertTrue(source.file.isFile)
        assertEquals(AnkiMediaFileOwnership.DELETE_AFTER_STORE_ATTEMPT, source.ownership)
        val arguments = requireNotNull(executedArguments)
        val acquiredInput = arguments[arguments.indexOf("-i") + 1]
        assertTrue(acquiredInput.endsWith("checked_input/playlist_000.m3u8"))
        assertEquals(
            "User-Agent: Chimahon\r\nX-Playback-Mode: scene\r\n",
            arguments[arguments.indexOf("-headers") + 1],
        )
        assertJobDirectoriesCleaned()

        assertTrue(source.file.delete())
    }

    @Test
    fun `normal extraction failure removes output and frozen manifests`() = runTest {
        val service = service(
            executor = PlayerFfmpegSessionExecutor { arguments, _, _ ->
                File(arguments.last()).writeBytes(byteArrayOf(1))
                false
            },
            protectionChecker = freezingProtectionChecker(),
        )

        assertNull(service.prepare(request()))

        assertJobDirectoriesCleaned()
        assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith("chimahon_sentence_audio_") })
    }

    @Test
    fun `deferred cancellation cleanup waits for native session completion`() = runTest {
        val sessionStarted = CompletableDeferred<Unit>()
        val finishCancelledSession = CompletableDeferred<() -> Unit>()
        val service = service(
            executor = PlayerFfmpegSessionExecutor { _, onCancellationDeferred, onCancelledSessionFinished ->
                sessionStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    onCancellationDeferred()
                    finishCancelledSession.complete(onCancelledSessionFinished)
                }
            },
            protectionChecker = freezingProtectionChecker(),
        )
        val job = launch {
            service.prepare(request())
        }
        sessionStarted.await()

        job.cancelAndJoin()

        val jobsRoot = directory.resolve("scene_sentence_audio_jobs")
        assertTrue(jobsRoot.listFiles().orEmpty().isNotEmpty())
        assertTrue(directory.listFiles().orEmpty().any { it.name.startsWith("chimahon_sentence_audio_") })

        finishCancelledSession.await().invoke()

        assertJobDirectoriesCleaned()
        assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith("chimahon_sentence_audio_") })
    }

    @Test
    fun `protection failure removes the job and never starts FFmpeg`() = runTest {
        var executed = false
        val service = service(
            executor = PlayerFfmpegSessionExecutor { _, _, _ ->
                executed = true
                true
            },
            protectionChecker = SceneInputProtectionChecker { _, workingDirectory ->
                workingDirectory.mkdir()
                workingDirectory.resolve("partial.m3u8").writeText("#EXTM3U")
                SceneInputProtectionResult.Unavailable
            },
        )

        assertNull(service.prepare(request()))

        assertFalse(executed)
        assertJobDirectoriesCleaned()
    }

    @Test
    fun `cross origin external audio with credentials is rejected before reader and FFmpeg`() = runTest {
        var protectionCalled = false
        var executed = false
        val service = service(
            executor = PlayerFfmpegSessionExecutor { _, _, _ ->
                executed = true
                true
            },
            protectionChecker = SceneInputProtectionChecker { _, _ ->
                protectionCalled = true
                SceneInputProtectionResult.Unavailable
            },
        )
        val input = defaultInput().copy(
            headers = listOf(
                "Authorization" to "Bearer video-secret",
                "Cookie" to "session=video-secret",
            ),
            inputOptions = listOf(SceneInputOption("referer", "https://media.example/watch")),
            externalAudioValue = "https://audio.other.example/track.m3u8",
        )

        assertNull(service.prepare(request(input)))

        assertFalse(protectionCalled)
        assertFalse(executed)
        assertJobDirectoriesCleaned()
    }

    @Test
    fun `same origin external audio retains credentials through protection and arguments`() = runTest {
        var protectedInput: SceneVideoInputSpec? = null
        var executedArguments: List<String>? = null
        val service = service(
            executor = PlayerFfmpegSessionExecutor { arguments, _, _ ->
                executedArguments = arguments.toList()
                File(arguments.last()).writeBytes(byteArrayOf(1))
                true
            },
            protectionChecker = SceneInputProtectionChecker { input, _ ->
                protectedInput = input
                SceneInputProtectionResult.Clear(input)
            },
        )
        val input = defaultInput().copy(
            headers = listOf(
                "Authorization" to "Bearer same-origin-secret",
                "Cookie" to "session=same-origin-secret",
            ),
            inputOptions = listOf(SceneInputOption("referer", "https://media.example/watch")),
            externalAudioValue = "https://media.example/audio/track.mp4",
        )

        val result = service.prepare(request(input))

        val source = assertInstanceOf(AnkiMediaSource.FileSource::class.java, result)
        assertEquals(input.headers, requireNotNull(protectedInput).headers)
        assertEquals(input.inputOptions, requireNotNull(protectedInput).inputOptions)
        val arguments = requireNotNull(executedArguments)
        assertEquals(
            "Authorization: Bearer same-origin-secret\r\n" +
                "Cookie: session=same-origin-secret\r\n",
            arguments[arguments.indexOf("-headers") + 1],
        )
        assertEquals(
            "https://media.example/watch",
            arguments[arguments.indexOf("-referer") + 1],
        )
        assertTrue(source.file.delete())
        assertJobDirectoriesCleaned()
    }

    @Test
    fun `cross origin external audio without headers keeps only validated neutral options`() = runTest {
        var protectedInput: SceneVideoInputSpec? = null
        var executedArguments: List<String>? = null
        val service = service(
            executor = PlayerFfmpegSessionExecutor { arguments, _, _ ->
                executedArguments = arguments.toList()
                File(arguments.last()).writeBytes(byteArrayOf(1))
                true
            },
            protectionChecker = SceneInputProtectionChecker { input, workingDirectory ->
                protectedInput = input
                assertTrue(workingDirectory.mkdir())
                val frozen = workingDirectory.resolve("playlist_000.m3u8")
                frozen.writeText("#EXTM3U\n#EXTINF:2,\nhttps://audio.other.example/audio.aac")
                SceneInputProtectionResult.Clear(
                    input.copy(
                        value = frozen.absolutePath,
                        kind = SceneVideoInputKind.LOCAL_FILE,
                    ),
                )
            },
        )
        val input = defaultInput().copy(
            headers = emptyList(),
            inputOptions = listOf(
                SceneInputOption("user_agent", "Chimahon"),
                SceneInputOption("rw_timeout", "5000000"),
            ),
            externalAudioValue = "https://audio.other.example/track.m3u8",
        )

        val result = service.prepare(request(input))

        val source = assertInstanceOf(AnkiMediaSource.FileSource::class.java, result)
        assertTrue(requireNotNull(protectedInput).headers.isEmpty())
        assertEquals(input.inputOptions, requireNotNull(protectedInput).inputOptions)
        val arguments = requireNotNull(executedArguments)
        assertFalse("-headers" in arguments)
        assertEquals("Chimahon", arguments[arguments.indexOf("-user_agent") + 1])
        assertEquals("5000000", arguments[arguments.indexOf("-rw_timeout") + 1])
        assertTrue(source.file.delete())
        assertJobDirectoriesCleaned()
    }

    private fun service(
        executor: PlayerFfmpegSessionExecutor,
        protectionChecker: SceneInputProtectionChecker,
    ): FrozenSceneSentenceAudioService {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.cacheDir } returns directory
        return FrozenSceneSentenceAudioService(
            context = context,
            executor = executor,
            protectionChecker = protectionChecker,
        )
    }

    private fun freezingProtectionChecker(): SceneInputProtectionChecker {
        return SceneInputProtectionChecker { input, workingDirectory ->
            assertTrue(workingDirectory.mkdir())
            val frozen = workingDirectory.resolve("playlist_000.m3u8")
            frozen.writeText(
                """
                #EXTM3U
                #EXTINF:2,
                https://media.example/audio.aac
                #EXT-X-ENDLIST
                """.trimIndent(),
            )
            SceneInputProtectionResult.Clear(
                input.copy(
                    value = frozen.absolutePath,
                    kind = SceneVideoInputKind.LOCAL_FILE,
                ),
            )
        }
    }

    private fun request(input: SceneVideoInputSpec = defaultInput()): SceneCaptureRequest {
        val range = SceneTimeRange(1.0, 2.0)
        return mockk<SceneCaptureRequest>().also { request ->
            every { request.videoInput } returns SceneVideoInputResolution.Supported(
                input,
            )
            every { request.resolvedTiming } returns SceneResolvedTiming(
                sourceRange = range,
                animationRange = range,
                audioRange = range,
                provenance = SceneRangeProvenance.PARSED_SUBTITLE_CUE,
            )
            every { request.selectedExternalAudioRequired } returns false
            every { request.selectedAudioFfmpegIndex } returns 1
        }
    }

    private fun defaultInput(): SceneVideoInputSpec {
        return SceneVideoInputSpec(
            value = "https://media.example/master.m3u8",
            kind = SceneVideoInputKind.REMOTE_HTTP,
            headers = listOf(
                "User-Agent" to "Chimahon",
                "X-Playback-Mode" to "scene",
            ),
            inputOptions = emptyList(),
            externalAudioValue = null,
            identity = SceneVideoIdentity(1L, 2L, "test", "digest"),
        )
    }

    private fun assertJobDirectoriesCleaned() {
        val jobsRoot = directory.resolve("scene_sentence_audio_jobs")
        assertTrue(!jobsRoot.exists() || jobsRoot.listFiles().orEmpty().isEmpty())
    }
}
