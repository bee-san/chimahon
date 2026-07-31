package eu.kanade.tachiyomi.ui.player.scene

import android.graphics.Bitmap
import chimahon.anki.AnkiScreenshotPreparation
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AndroidSceneCaptureServiceTest {
    @TempDir
    lateinit var tempDirectory: File

    @Test
    fun `successful capture encodes directly to animated AVIF in one command`() = runTest {
        val executor = RecordingExecutor(writeOutput = true)
        val service = service(
            executor = executor,
            validate = { AnimatedAvifInfo(320, 180, 24, 3_000) },
        )

        val result = service.prepare(request())

        val animated = result as AnkiScreenshotPreparation.Animated
        assertEquals("avif", animated.animation.extension)
        assertTrue(animated.animation.preferredBaseName.startsWith("chimahon_scene_"))
        assertEquals(1, executor.ffmpegArguments.size)
        assertArrayEquals(
            expectedAv1Arguments(animated.animation.file.absolutePath),
            executor.ffmpegArguments[0],
        )
        animated.animation.file.delete()
    }

    @Test
    fun `missing compatible AV1 encoder falls back before encode`() = runTest {
        val executor = RecordingExecutor(writeOutput = true)
        val service = service(
            executor = executor,
            av1Encoder = { null },
        )

        val result = service.prepare(request())

        assertTrue(result is AnkiScreenshotPreparation.Failed)
        assertEquals(1, executor.probeCalls)
        assertEquals(0, executor.ffmpegCalls)
        assertFalse(tempDirectory.resolve("scene").exists())
    }

    @Test
    fun `capture selects an encoder for probed dimensions and applies its exact output`() = runTest {
        val executor = RecordingExecutor(writeOutput = true)
        var selectedFor: SceneVideoDimensions? = null
        val service = service(
            executor = executor,
            validate = { AnimatedAvifInfo(320, 192, 24, 3_000) },
            av1Encoder = { source ->
                selectedFor = source
                Av1EncoderSelection(
                    name = TEST_AV1_ENCODER_NAME,
                    contentSize = SceneVideoDimensions(width = 320, height = 180),
                    outputSize = SceneVideoDimensions(width = 320, height = 192),
                )
            },
        )

        val result = service.prepare(request())

        assertTrue(result is AnkiScreenshotPreparation.Animated)
        assertEquals(SceneVideoDimensions(width = 320, height = 180), selectedFor)
        val encodeArguments = executor.ffmpegArguments.first().toList()
        assertEquals(
            SceneFfmpegArguments.frameFilter(
                contentSize = SceneVideoDimensions(width = 320, height = 180),
                outputSize = SceneVideoDimensions(width = 320, height = 192),
            ),
            encodeArguments[encodeArguments.indexOf("-vf") + 1],
        )
        (result as AnkiScreenshotPreparation.Animated).animation.file.delete()
    }

    @Test
    fun `capture rejects output dimensions that differ from the codec selection`() = runTest {
        val executor = RecordingExecutor(writeOutput = true)
        val service = service(
            executor = executor,
            validate = { AnimatedAvifInfo(320, 180, 24, 3_000) },
            av1Encoder = {
                Av1EncoderSelection(
                    name = TEST_AV1_ENCODER_NAME,
                    contentSize = SceneVideoDimensions(width = 320, height = 180),
                    outputSize = SceneVideoDimensions(width = 320, height = 192),
                )
            },
        )

        val result = service.prepare(request())

        assertTrue(result is AnkiScreenshotPreparation.Failed)
        assertTrue(tempDirectory.resolve("scene").listFiles().isNullOrEmpty())
    }

    @Test
    fun `cancellation while returning a completed capture deletes the undelivered output`() = runTest {
        val executor = RecordingExecutor(writeOutput = true)
        val callerJob = Job(currentCoroutineContext()[Job])
        val service = service(
            executor = executor,
            validate = {
                callerJob.cancel()
                AnimatedAvifInfo(320, 180, 24, 3_000)
            },
        )

        var cancelled = false
        try {
            withContext(callerJob) {
                service.prepare(request())
            }
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertTrue(tempDirectory.resolve("scene").listFiles().isNullOrEmpty())
    }

    @Test
    fun `probe argument failure closes its input lease and fails closed`() = runTest {
        var closeCalls = 0
        val service = service(
            executor = RecordingExecutor(writeOutput = true),
            inputAcquirer = SceneInputAcquirer { input ->
                object : SceneInputLease {
                    override val ffmpegValue = input.value
                    override val tlsCaFile: String? = null

                    override fun close() {
                        closeCalls++
                    }
                }
            },
        )

        val result = runCatching { service.prepare(request()) }

        assertEquals(1, closeCalls)
        assertTrue(result.getOrNull() is AnkiScreenshotPreparation.Failed)
    }

    @Test
    fun `encode argument failure closes both acquired input leases`() = runTest {
        var acquisitions = 0
        var closeCalls = 0
        val service = service(
            executor = RecordingExecutor(writeOutput = true),
            inputAcquirer = SceneInputAcquirer { input ->
                acquisitions++
                object : SceneInputLease {
                    override val ffmpegValue = input.value
                    override val tlsCaFile = if (acquisitions == 1) "/files/cacert.pem" else null

                    override fun close() {
                        closeCalls++
                    }
                }
            },
        )

        val result = service.prepare(request())

        assertTrue(result is AnkiScreenshotPreparation.Failed)
        assertEquals(2, acquisitions)
        assertEquals(2, closeCalls)
        assertTrue(tempDirectory.resolve("scene").listFiles().isNullOrEmpty())
    }

    @Test
    fun `failed validation deletes partial output`() = runTest {
        val executor = RecordingExecutor(writeOutput = true)
        val sceneDirectory = tempDirectory.resolve("scene")
        val service = service(
            executor = executor,
            validate = { null },
        )

        val result = service.prepare(request())

        assertTrue(result is AnkiScreenshotPreparation.Failed)
        assertTrue(sceneDirectory.listFiles().isNullOrEmpty())
    }

    @Test
    fun `cancellation reaches native encode and defers file cleanup until native return`() = runTest {
        val executor = RecordingExecutor(writeOutput = true, suspendEncode = true)
        val service = service(executor = executor)
        val preparation = launch { service.prepare(request()) }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { executor.encodeStarted.await() }
        }
        val output = File(executor.ffmpegArguments.last().last())

        preparation.cancelAndJoin()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { executor.cancellationObserved.await() }
        }

        assertTrue(output.isFile)

        executor.finishNative()

        assertFalse(output.exists())
    }

    private fun service(
        executor: RecordingExecutor,
        inputAcquirer: SceneInputAcquirer = SceneInputAcquirer { input ->
            object : SceneInputLease {
                override val ffmpegValue = input.value
                override val tlsCaFile = "/files/cacert.pem"

                override fun close() = Unit
            }
        },
        validate: (File) -> AnimatedAvifInfo? = {
            AnimatedAvifInfo(320, 180, 24, 3_000)
        },
        av1Encoder: (SceneVideoDimensions) -> Av1EncoderSelection? = { source ->
            selectAv1Encoder(
                source = source,
                candidates = sequenceOf(
                    Av1EncoderCandidate(
                        name = TEST_AV1_ENCODER_NAME,
                        supportsPlanarYuv420 = true,
                        supportsConstantQuality = true,
                        supportsTargetQuality = true,
                        widthAlignment = 2,
                        heightAlignment = 2,
                        supportsSizeAndRate = { _, _ -> true },
                    ),
                ),
            )
        },
    ): AndroidSceneCaptureService {
        return AndroidSceneCaptureService.forTests(
            sceneDirectory = tempDirectory.resolve("scene"),
            inputAcquirer = inputAcquirer,
            commandExecutor = executor,
            validate = validate,
            av1Encoder = av1Encoder,
        )
    }

    private fun request(): SceneCaptureRequest {
        val input = SceneVideoInputSpec(
            value = "https://media.example/video.mp4",
            kind = SceneVideoInputKind.REMOTE_HTTP,
            headers = listOf("User-Agent" to "Chimahon"),
        )
        val bitmap = mockk<Bitmap>(relaxed = true)
        every { bitmap.isRecycled } returns false
        return SceneCaptureRequest(
            videoInput = input,
            sentenceAudioInput = input,
            resolvedTiming = SceneResolvedTiming(
                animationRange = SceneTimeRange(1.25, 4.25),
                audioRange = SceneTimeRange(1.25, 4.25),
            ),
            stillFallback = OwnedBitmap(bitmap),
        )
    }

    private fun expectedAv1Arguments(output: String): Array<String> {
        return arrayOf(
            "-codec_whitelist",
            SceneFfmpegArguments.ALLOWED_INPUT_DECODERS,
            "-tls_verify",
            "1",
            "-ca_file",
            "/files/cacert.pem",
            "-protocol_whitelist",
            "http,https,tls,tcp,crypto",
            "-rw_timeout",
            "15000000",
            "-headers",
            "User-Agent: Chimahon\r\n",
            "-ss",
            "1.25",
            "-i",
            "https://media.example/video.mp4",
            "-map",
            "0:v:0",
            "-an",
            "-sn",
            "-dn",
            "-t",
            "3",
            "-vf",
            SceneFfmpegArguments.frameFilter(
                contentSize = SceneVideoDimensions(width = 320, height = 180),
                outputSize = SceneVideoDimensions(width = 320, height = 180),
            ),
            "-frames:v",
            "80",
            "-c:v",
            "av1_mediacodec",
            "-codec_name",
            TEST_AV1_ENCODER_NAME,
            "-bitrate_mode",
            "cq",
            "-global_quality",
            "35",
            "-ndk_codec",
            "1",
            "-pix_fmt",
            "yuv420p",
            "-loop",
            "0",
            "-f",
            "avif",
            "-y",
            output,
        )
    }

    private class RecordingExecutor(
        private val writeOutput: Boolean,
        private val suspendEncode: Boolean = false,
    ) : SceneCommandExecutor {
        var probeCalls = 0
        var ffmpegCalls = 0
        val ffmpegArguments = mutableListOf<Array<String>>()
        val encodeStarted = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()
        private lateinit var onEncodeFinished: () -> Unit

        override suspend fun executeFfmpeg(
            arguments: Array<String>,
            onNativeFinished: () -> Unit,
        ): SceneCommandResult {
            ffmpegCalls++
            ffmpegArguments += arguments
            val output = File(arguments.last())
            if (writeOutput) {
                output.writeBytes(byteArrayOf(1, 2, 3))
            }
            if (suspendEncode && output.extension == "avif") {
                onEncodeFinished = onNativeFinished
                encodeStarted.complete(Unit)
                return suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation {
                        cancellationObserved.complete(Unit)
                    }
                }
            }
            return try {
                SceneCommandResult.Success()
            } finally {
                onNativeFinished()
            }
        }

        override suspend fun executeFfprobe(
            arguments: Array<String>,
            onNativeFinished: () -> Unit,
        ): SceneCommandResult {
            return try {
                probeCalls++
                SceneCommandResult.Success(
                    "width=320\nheight=180\npix_fmt=yuv420p\ncolor_transfer=bt709\n" +
                        "color_primaries=bt709\nbits_per_raw_sample=8",
                )
            } finally {
                onNativeFinished()
            }
        }

        fun finishNative() {
            onEncodeFinished()
        }
    }

    private companion object {
        const val TEST_AV1_ENCODER_NAME = "c2.android.av1.encoder"
    }
}
