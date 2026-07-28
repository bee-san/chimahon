package eu.kanade.tachiyomi.ui.player.scene

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SceneCapturePipelineTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `capture probes extracts encodes muxes validates hashes and cleans intermediates`() = runTest {
        val executor = FakeCommandExecutor()
        val inputAcquirer = FakeInputAcquirer()
        val progress = mutableListOf<SceneCaptureProgress>()
        val metrics = mutableListOf<SceneCaptureMetrics>()
        val pipeline = pipeline(
            executor = executor,
            inputAcquirer = inputAcquirer,
            dispatcher = StandardTestDispatcher(testScheduler),
            onProgress = progress::add,
            onMetrics = metrics::add,
        )

        val result = pipeline.capture(request())

        val success = assertInstanceOf(SceneCaptureResult.Success::class.java, result)
        assertTrue(success.output.file.isFile)
        assertTrue(success.output.file.name.startsWith("job-1_chimahon_scene_"))
        assertEquals(64, success.output.digest.length)
        assertEquals("chimahon_scene_${success.output.digest}", success.output.preferredBaseName)
        assertEquals(2, success.info.frameCount)
        assertEquals(2, success.metrics.frameCount)
        assertTrue(success.metrics.wallTimeMillis >= 0L)
        assertEquals(2, inputAcquirer.acquireCount)
        assertEquals(2, inputAcquirer.closeCount)
        assertEquals(2, executor.ffmpegCalls)
        assertEquals(
            listOf(
                SceneCaptureProgress.Preparing,
                SceneCaptureProgress.Extracting,
                SceneCaptureProgress.Encoding(frameIndex = 1, frameCount = 2),
                SceneCaptureProgress.Encoding(frameIndex = 2, frameCount = 2),
                SceneCaptureProgress.Muxing,
                SceneCaptureProgress.Hashing,
            ),
            progress,
        )
        assertEquals(listOf(success.metrics), metrics)
        assertJobFilesCleaned()

        success.output.close()
        assertFalse(success.output.file.exists())
    }

    @Test
    fun `HDR probe selects typed unsupported fallback before extraction`() = runTest {
        val executor = FakeCommandExecutor(
            probeOutput = """
                pix_fmt=yuv420p10le
                color_transfer=smpte2084
                bits_per_raw_sample=10
                duration=60
            """.trimIndent(),
        )
        val pipeline = pipeline(executor, dispatcher = StandardTestDispatcher(testScheduler))

        assertEquals(
            SceneCaptureResult.Unsupported(SceneCaptureUnsupportedReason.HDR_OR_TEN_BIT),
            pipeline.capture(request()),
        )
        assertEquals(0, executor.ffmpegCalls)
        assertJobFilesCleaned()
    }

    @Test
    fun `protected input is rejected before ffprobe or extraction opens it`() = runTest {
        val executor = FakeCommandExecutor()
        val inputAcquirer = FakeInputAcquirer()
        val pipeline = pipeline(
            executor = executor,
            inputAcquirer = inputAcquirer,
            protectionChecker = SceneInputProtectionChecker { _, _ ->
                SceneInputProtectionResult.Protected(SceneCaptureUnsupportedReason.ENCRYPTED)
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        assertEquals(
            SceneCaptureResult.Unsupported(SceneCaptureUnsupportedReason.ENCRYPTED),
            pipeline.capture(request()),
        )
        assertEquals(0, inputAcquirer.acquireCount)
        assertEquals(0, executor.ffmpegCalls)
        assertEquals(0, executor.ffprobeCalls)
        assertJobFilesCleaned()
    }

    @Test
    fun `authenticated HLS is rejected before FFprobe or FFmpeg`() = runTest {
        val executor = FakeCommandExecutor()
        val inputAcquirer = FakeInputAcquirer()
        val playlistUrl = "https://media.example/master.m3u8"
        val protectionChecker = RecursiveSceneInputProtectionChecker(
            documentReader = SceneProtectionDocumentReader { resource, _ ->
                SceneProtectionDocument(
                    resolvedValue = resource.value,
                    bytes = """
                        #EXTM3U
                        #EXTINF:4,
                        segment.ts
                        #EXT-X-ENDLIST
                    """.trimIndent().encodeToByteArray(),
                    complete = true,
                    contentType = "application/vnd.apple.mpegurl",
                )
            },
        )
        val pipeline = pipeline(
            executor = executor,
            inputAcquirer = inputAcquirer,
            protectionChecker = protectionChecker,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val request = request().copy(
            input = request().input.copy(
                value = playlistUrl,
                kind = SceneVideoInputKind.REMOTE_HTTP,
                headers = listOf("Authorization" to "Bearer secret"),
            ),
        )

        assertEquals(
            SceneCaptureResult.Failure(SceneCaptureFailureReason.PROTECTION_CHECK_FAILED),
            pipeline.capture(request),
        )
        assertEquals(0, inputAcquirer.acquireCount)
        assertEquals(0, executor.ffmpegCalls)
        assertEquals(0, executor.ffprobeCalls)
        assertJobFilesCleaned()
    }

    @Test
    fun `fewer than two extracted frames uses typed frame-count failure`() = runTest {
        val executor = FakeCommandExecutor(extractedFrameCount = 1)
        val pipeline = pipeline(executor, dispatcher = StandardTestDispatcher(testScheduler))

        assertEquals(
            SceneCaptureResult.Failure(SceneCaptureFailureReason.INVALID_FRAME_COUNT),
            pipeline.capture(request()),
        )
        assertJobFilesCleaned()
    }

    @Test
    fun `frame encoder failure is typed and intermediates are cleaned`() = runTest {
        val executor = FakeCommandExecutor()
        val pipeline = pipeline(
            executor = executor,
            encoder = SceneWebpFrameEncoder { _, _ -> false },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        assertEquals(
            SceneCaptureResult.Failure(SceneCaptureFailureReason.FRAME_ENCODING_FAILED),
            pipeline.capture(request()),
        )
        assertEquals(1, executor.ffmpegCalls)
        assertJobFilesCleaned()
    }

    @Test
    fun `invalid animated WebP includes the validator diagnostic`() = runTest {
        val pipeline = pipeline(
            executor = FakeCommandExecutor(muxedOutput = byteArrayOf(1, 2, 3)),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        assertEquals(
            SceneCaptureResult.Failure(
                reason = SceneCaptureFailureReason.INVALID_ANIMATED_WEBP,
                detail = "Truncated RIFF header",
            ),
            pipeline.capture(request()),
        )
        assertJobFilesCleaned()
    }

    @Test
    fun `hard timeout is a failure distinct from cancellation and closes input lease`() = runTest {
        val executor = FakeCommandExecutor(blockProbe = true)
        val inputAcquirer = FakeInputAcquirer()
        val pipeline = pipeline(
            executor = executor,
            inputAcquirer = inputAcquirer,
            timeoutMillis = 100,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        assertEquals(
            SceneCaptureResult.Failure(SceneCaptureFailureReason.TIMEOUT),
            pipeline.capture(request()),
        )
        assertEquals(1, inputAcquirer.closeCount)
        assertJobFilesCleaned()
    }

    @Test
    fun `caller cancellation before probe session completes closes the input lease`() = runTest {
        val executor = FakeCommandExecutor(blockProbe = true)
        val inputAcquirer = FakeInputAcquirer()
        val pipeline = pipeline(
            executor = executor,
            inputAcquirer = inputAcquirer,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val captureJob = launch(start = CoroutineStart.UNDISPATCHED) {
            pipeline.capture(request())
        }
        runCurrent()
        assertEquals(1, inputAcquirer.acquireCount)

        captureJob.cancelAndJoin()

        assertEquals(1, inputAcquirer.closeCount)
        assertJobFilesCleaned()
    }

    @Test
    fun `deferred native cancellation retains lease and late artifacts until callback`() = runTest {
        val executor = DeferredCancellationExecutor()
        val inputAcquirer = FakeInputAcquirer()
        var cleanupCalls = 0
        val pipeline = pipeline(
            executor = executor,
            inputAcquirer = inputAcquirer,
            dispatcher = StandardTestDispatcher(testScheduler),
            jobDirectoryCleaner = {
                cleanupCalls++
                it.deleteRecursively()
            },
        )
        val captureJob = launch(start = CoroutineStart.UNDISPATCHED) {
            pipeline.capture(request())
        }
        runCurrent()
        executor.sessionStarted.await()

        captureJob.cancelAndJoin()

        val jobDirectory = directory.resolve("scene_capture_jobs/job-1")
        assertTrue(jobDirectory.isDirectory)
        assertEquals(0, cleanupCalls)
        assertEquals(0, inputAcquirer.closeCount)
        jobDirectory.resolve("native-late-write.tmp").writeText("late")

        val finishCancelledSession = executor.finishCancelledSession.await()
        finishCancelledSession()
        finishCancelledSession()

        assertTrue(captureJob.isCancelled)
        assertEquals(1, cleanupCalls)
        assertEquals(1, inputAcquirer.closeCount)
        assertJobFilesCleaned()
    }

    @Test
    fun `timeout after atomic finalization deletes the guarded output`() = runTest {
        val finalizedFile = CompletableDeferred<File>()
        val pipeline = pipeline(
            executor = FakeCommandExecutor(),
            timeoutMillis = 100,
            dispatcher = StandardTestDispatcher(testScheduler),
            afterFinalization = { output ->
                finalizedFile.complete(output.file)
                awaitCancellation()
            },
        )

        assertEquals(
            SceneCaptureResult.Failure(SceneCaptureFailureReason.TIMEOUT),
            pipeline.capture(request()),
        )
        assertFalse(finalizedFile.await().exists())
        assertTrue(
            directory.resolve("scene_capture_outputs")
                .listFiles()
                .orEmpty()
                .isEmpty(),
        )
        assertJobFilesCleaned()
    }

    @Test
    fun `caller cancellation after atomic finalization deletes the guarded output`() = runTest {
        val finalizedFile = CompletableDeferred<File>()
        val pipeline = pipeline(
            executor = FakeCommandExecutor(),
            dispatcher = StandardTestDispatcher(testScheduler),
            afterFinalization = { output ->
                finalizedFile.complete(output.file)
                awaitCancellation()
            },
        )
        val captureJob = launch(start = CoroutineStart.UNDISPATCHED) {
            pipeline.capture(request())
        }
        runCurrent()
        val output = finalizedFile.await()
        assertTrue(output.isFile)

        captureJob.cancelAndJoin()

        assertFalse(output.exists())
        assertTrue(
            directory.resolve("scene_capture_outputs")
                .listFiles()
                .orEmpty()
                .isEmpty(),
        )
        assertJobFilesCleaned()
    }

    @Test
    fun `cleanup exception after finalization closes the owned output`() = runTest {
        val pipeline = pipeline(
            executor = FakeCommandExecutor(),
            dispatcher = StandardTestDispatcher(testScheduler),
            jobDirectoryCleaner = { throw IllegalStateException("cleanup failed") },
        )

        assertEquals(
            SceneCaptureResult.Failure(SceneCaptureFailureReason.IO_FAILURE),
            pipeline.capture(request()),
        )
        assertTrue(
            directory.resolve("scene_capture_outputs")
                .listFiles()
                .orEmpty()
                .isEmpty(),
        )
    }

    @Test
    fun `session cancellation is returned without a failure fallback`() = runTest {
        val executor = FakeCommandExecutor(probeCancelled = true)
        val pipeline = pipeline(executor, dispatcher = StandardTestDispatcher(testScheduler))

        assertEquals(SceneCaptureResult.Cancelled, pipeline.capture(request()))
        assertJobFilesCleaned()
    }

    @Test
    fun `read only input acquisition failure is typed`() = runTest {
        val pipeline = pipeline(
            executor = FakeCommandExecutor(),
            inputAcquirer = FakeInputAcquirer(
                unsupportedReason = SceneCaptureUnsupportedReason.CONTENT_URI_UNAVAILABLE,
            ),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        assertEquals(
            SceneCaptureResult.Unsupported(SceneCaptureUnsupportedReason.CONTENT_URI_UNAVAILABLE),
            pipeline.capture(request()),
        )
        assertJobFilesCleaned()
    }

    private fun pipeline(
        executor: SceneCommandExecutor,
        inputAcquirer: FakeInputAcquirer = FakeInputAcquirer(),
        protectionChecker: SceneInputProtectionChecker = SceneInputProtectionChecker { input, _ ->
            SceneInputProtectionResult.Clear(input)
        },
        encoder: SceneWebpFrameEncoder = SceneWebpFrameEncoder { _, output ->
            output.writeBytes(byteArrayOf(1))
            true
        },
        timeoutMillis: Long = 60_000,
        dispatcher: CoroutineDispatcher,
        onProgress: (SceneCaptureProgress) -> Unit = {},
        onMetrics: (SceneCaptureMetrics) -> Unit = {},
        jobDirectoryCleaner: (File) -> Boolean = File::deleteRecursively,
        afterFinalization: suspend (SceneCapturedFile) -> Unit = {},
    ): SceneCapturePipeline {
        return SceneCapturePipeline(
            cacheRoot = directory,
            commandExecutor = executor,
            inputAcquirer = inputAcquirer,
            inputProtectionChecker = protectionChecker,
            frameEncoder = encoder,
            timeoutMillis = timeoutMillis,
            jobIdProvider = { "job-1" },
            workerDispatcher = dispatcher,
            onProgress = onProgress,
            onMetrics = onMetrics,
            jobDirectoryCleaner = jobDirectoryCleaner,
            afterFinalization = afterFinalization,
        )
    }

    private fun request() = SceneCapturePipelineRequest(
        input = SceneVideoInputSpec(
            value = "/video.mp4",
            kind = SceneVideoInputKind.LOCAL_FILE,
            headers = emptyList(),
            inputOptions = emptyList(),
            externalAudioValue = null,
            identity = SceneVideoIdentity(
                episodeId = 1L,
                sourceId = 2L,
                quality = "1080p",
                inputDigest = "digest",
            ),
        ),
        animationRange = SceneTimeRange(2.0, 4.0),
    )

    private fun assertJobFilesCleaned() {
        val jobs = directory.resolve("scene_capture_jobs")
        assertTrue(!jobs.exists() || jobs.listFiles().orEmpty().isEmpty())
    }

    private class FakeInputAcquirer(
        private val unsupportedReason: SceneCaptureUnsupportedReason? = null,
    ) : SceneInputAcquirer {
        var acquireCount = 0
        var closeCount = 0

        override suspend fun acquire(input: SceneVideoInputSpec): SceneInputAcquisition {
            acquireCount++
            unsupportedReason?.let { return SceneInputAcquisition.Unsupported(it) }
            return SceneInputAcquisition.Acquired(
                object : SceneInputLease {
                    override val ffmpegValue = input.value

                    override fun close() {
                        closeCount++
                    }
                },
            )
        }
    }

    private class FakeCommandExecutor(
        private val probeOutput: String = """
            pix_fmt=yuv420p
            color_transfer=bt709
            color_primaries=bt709
            color_space=bt709
            bits_per_raw_sample=8
            duration=60
        """.trimIndent(),
        private val extractedFrameCount: Int = 2,
        private val blockProbe: Boolean = false,
        private val probeCancelled: Boolean = false,
        private val muxedOutput: ByteArray = animatedWebp(),
    ) : SceneCommandExecutor {
        var ffmpegCalls = 0
        var ffprobeCalls = 0

        override suspend fun executeFfprobe(
            arguments: Array<String>,
            onCancellationDeferred: () -> Unit,
            onCancelledSessionFinished: () -> Unit,
        ): SceneCommandResult {
            ffprobeCalls++
            if (blockProbe) awaitCancellation()
            if (probeCancelled) return SceneCommandResult.Cancelled
            return SceneCommandResult.Success(probeOutput)
        }

        override suspend fun executeFfmpeg(
            arguments: Array<String>,
            onCancellationDeferred: () -> Unit,
            onCancelledSessionFinished: () -> Unit,
        ): SceneCommandResult {
            ffmpegCalls++
            val output = arguments.last()
            if (arguments.contains("image2")) {
                repeat(extractedFrameCount) { index ->
                    File(output.replace("%03d", (index + 1).toString().padStart(3, '0')))
                        .writeBytes(byteArrayOf(1))
                }
            } else {
                File(output).writeBytes(muxedOutput)
            }
            return SceneCommandResult.Success()
        }
    }

    private class DeferredCancellationExecutor : SceneCommandExecutor {
        val sessionStarted = CompletableDeferred<Unit>()
        val finishCancelledSession = CompletableDeferred<() -> Unit>()

        override suspend fun executeFfprobe(
            arguments: Array<String>,
            onCancellationDeferred: () -> Unit,
            onCancelledSessionFinished: () -> Unit,
        ): SceneCommandResult {
            sessionStarted.complete(Unit)
            return try {
                awaitCancellation()
            } catch (e: CancellationException) {
                onCancellationDeferred()
                finishCancelledSession.complete(onCancelledSessionFinished)
                throw e
            }
        }

        override suspend fun executeFfmpeg(
            arguments: Array<String>,
            onCancellationDeferred: () -> Unit,
            onCancelledSessionFinished: () -> Unit,
        ): SceneCommandResult {
            error("FFmpeg must not start while probe cancellation is pending")
        }
    }

    private companion object {
        fun animatedWebp(): ByteArray {
            return riff(
                chunk(
                    "VP8X",
                    ByteArray(10).apply {
                        this[0] = 0x02
                        writeUInt24(4, 63)
                        writeUInt24(7, 63)
                    },
                ),
                chunk("ANIM", ByteArray(6)),
                anmf(),
                anmf(),
            )
        }

        fun anmf(): ByteArray {
            val payload = ByteArrayOutputStream().apply {
                write(
                    ByteArray(16).apply {
                        writeUInt24(6, 31)
                        writeUInt24(9, 31)
                        writeUInt24(12, 125)
                    },
                )
                write(chunk("VP8 ", byteArrayOf(1, 2)))
            }.toByteArray()
            return chunk("ANMF", payload)
        }

        fun riff(vararg chunks: ByteArray): ByteArray {
            val payload = ByteArrayOutputStream().apply {
                write("WEBP".toByteArray())
                chunks.forEach(::write)
            }.toByteArray()
            return ByteArrayOutputStream().apply {
                write("RIFF".toByteArray())
                writeUInt32(payload.size.toLong())
                write(payload)
            }.toByteArray()
        }

        fun chunk(name: String, payload: ByteArray): ByteArray {
            return ByteArrayOutputStream().apply {
                write(name.toByteArray())
                writeUInt32(payload.size.toLong())
                write(payload)
                if (payload.size % 2 != 0) write(0)
            }.toByteArray()
        }

        fun ByteArray.writeUInt24(offset: Int, value: Int) {
            this[offset] = (value and 0xff).toByte()
            this[offset + 1] = ((value ushr 8) and 0xff).toByte()
            this[offset + 2] = ((value ushr 16) and 0xff).toByte()
        }

        fun ByteArrayOutputStream.writeUInt32(value: Long) {
            write((value and 0xff).toInt())
            write(((value ushr 8) and 0xff).toInt())
            write(((value ushr 16) and 0xff).toInt())
            write(((value ushr 24) and 0xff).toInt())
        }
    }
}
