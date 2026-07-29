package eu.kanade.tachiyomi.ui.player.scene

import android.graphics.Bitmap
import chimahon.anki.AnkiScreenshotPreparation
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
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
    fun `successful capture normalizes AV1 packets then remuxes them to animated AVIF`() = runTest {
        val executor = RecordingExecutor(writeOutput = true)
        val service = service(
            executor = executor,
            validate = { AnimatedAvifInfo(320, 180, 24, 3_000) },
        )

        val result = service.prepare(request())

        val animated = result as AnkiScreenshotPreparation.Animated
        assertEquals("avif", animated.animation.extension)
        assertTrue(animated.animation.preferredBaseName.startsWith("chimahon_scene_"))
        assertEquals(2, executor.ffmpegArguments.size)
        assertArrayEquals(
            expectedAv1Arguments(animated.animation.file.absolutePath.replaceAfterLast('.', "obu")),
            executor.ffmpegArguments[0],
        )
        assertArrayEquals(
            expectedAvifRemuxArguments(
                animated.animation.file.absolutePath.replaceAfterLast('.', "obu"),
                animated.animation.file.absolutePath,
            ),
            executor.ffmpegArguments[1],
        )
        val intermediate = File(
            animated.animation.file.parentFile,
            "${animated.animation.file.nameWithoutExtension}.obu",
        )
        assertFalse(intermediate.exists())
        animated.animation.file.delete()
    }

    @Test
    fun `missing compatible AV1 encoder falls back before native work`() = runTest {
        val executor = RecordingExecutor(writeOutput = true)
        val service = service(
            executor = executor,
            av1EncoderName = { null },
        )

        val result = service.prepare(request())

        assertTrue(result is AnkiScreenshotPreparation.Failed)
        assertEquals(0, executor.probeCalls)
        assertEquals(0, executor.ffmpegCalls)
        assertFalse(tempDirectory.resolve("scene").exists())
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
    fun `cancellation reaches native remux and defers file cleanup until native return`() = runTest {
        val executor = RecordingExecutor(writeOutput = true, suspendRemux = true)
        val service = service(executor = executor)
        val preparation = launch { service.prepare(request()) }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { executor.remuxStarted.await() }
        }
        val remuxArguments = executor.ffmpegArguments.last()
        val intermediate = File(remuxArguments[remuxArguments.indexOf("-i") + 1])
        val output = File(remuxArguments.last())

        preparation.cancelAndJoin()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { executor.cancellationObserved.await() }
        }

        assertTrue(intermediate.isFile)
        assertTrue(output.isFile)

        executor.finishNative()

        assertFalse(intermediate.exists())
        assertFalse(output.exists())
    }

    private fun service(
        executor: RecordingExecutor,
        validate: (File) -> AnimatedAvifInfo? = {
            AnimatedAvifInfo(320, 180, 24, 3_000)
        },
        av1EncoderName: () -> String? = { TEST_AV1_ENCODER_NAME },
    ): AndroidSceneCaptureService {
        return AndroidSceneCaptureService.forTests(
            sceneDirectory = tempDirectory.resolve("scene"),
            inputAcquirer = SceneInputAcquirer { input ->
                object : SceneInputLease {
                    override val ffmpegValue = input.value
                    override val tlsCaFile = "/files/cacert.pem"

                    override fun close() = Unit
                }
            },
            commandExecutor = executor,
            validate = validate,
            av1EncoderName = av1EncoderName,
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
            SceneFfmpegArguments.FRAME_FILTER,
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
            "-f",
            "data",
            "-y",
            output,
        )
    }

    private fun expectedAvifRemuxArguments(input: String, output: String): Array<String> {
        return arrayOf(
            "-f",
            "obu",
            "-framerate",
            "8",
            "-i",
            input,
            "-map",
            "0:v:0",
            "-c:v",
            "copy",
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
        private val suspendRemux: Boolean = false,
    ) : SceneCommandExecutor {
        var probeCalls = 0
        var ffmpegCalls = 0
        val ffmpegArguments = mutableListOf<Array<String>>()
        val remuxStarted = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()
        private lateinit var onRemuxFinished: () -> Unit

        override suspend fun executeFfmpeg(
            arguments: Array<String>,
            onNativeFinished: () -> Unit,
        ): SceneCommandResult {
            ffmpegCalls++
            ffmpegArguments += arguments
            val output = File(arguments.last())
            if (writeOutput) {
                val bytes = when (output.extension) {
                    "obu" -> mediaCodecAv1PacketStream()
                    else -> byteArrayOf(1, 2, 3)
                }
                output.writeBytes(bytes)
            }
            if (suspendRemux && output.extension == "avif") {
                onRemuxFinished = onNativeFinished
                remuxStarted.complete(Unit)
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
                    "pix_fmt=yuv420p\ncolor_transfer=bt709\ncolor_primaries=bt709\nbits_per_raw_sample=8",
                )
            } finally {
                onNativeFinished()
            }
        }

        fun finishNative() {
            onRemuxFinished()
        }
    }

    private companion object {
        const val TEST_AV1_ENCODER_NAME = "c2.android.av1.encoder"
    }
}
