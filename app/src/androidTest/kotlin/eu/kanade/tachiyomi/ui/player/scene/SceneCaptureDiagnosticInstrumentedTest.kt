package eu.kanade.tachiyomi.ui.player.scene

import android.graphics.Bitmap
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.test.platform.app.InstrumentationRegistry
import chimahon.anki.AnkiScreenshotPreparation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reproduces the "falls back to static" report against the real FFmpegKit pipeline.
 *
 * Every gate in the capture pipeline surfaces the same toast, so this walks each candidate cause in
 * order and prints a verdict per gate. Fixtures are synthesized on-device with the bundled FFmpeg
 * (`testsrc2` + `ffv1` + the matroska muxer) rather than checked in, which keeps the release
 * gate's asset provenance/SHA-256 requirements out of scope.
 *
 * Read the results with:
 * `adb logcat -s scene-diag:V`
 */
class SceneCaptureDiagnosticInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val executor = FfmpegKitSceneCommandExecutor()
    private val workDir: File
        get() = File(context.cacheDir, "scene_diag").apply { mkdirs() }

    @Test
    fun causeA_mimeMappingGate() {
        val mime = MimeTypeMap.getSingleton()
        val extensionToMime = mime.getMimeTypeFromExtension("avif")
        val mimeToExtension = mime.getExtensionFromMimeType("image/avif")
        val passes = extensionToMime?.equals("image/avif", true) == true &&
            mimeToExtension?.equals("avif", true) == true

        report(
            "CAUSE_A mimeMapping extensionToMime=$extensionToMime " +
                "mimeToExtension=$mimeToExtension passes=$passes",
        )
    }

    @Test
    fun causeB_av1EncoderCriteria() {
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder }
            .filter { info -> info.supportedTypes.any { it.equals("video/av01", true) } }

        report("CAUSE_B av1EncoderCount=${encoders.size}")
        var anyAccepted = false
        encoders.forEach { info ->
            runCatching {
                val caps = info.getCapabilitiesForType("video/av01")
                val enc = caps.encoderCapabilities
                val video = caps.videoCapabilities
                val planar = caps.colorFormats.contains(
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                )
                val semiPlanar = caps.colorFormats.contains(
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                )
                val flexible = caps.colorFormats.contains(
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                )
                val cq = enc?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
                ) == true
                val q35 = enc?.qualityRange?.contains(35) == true
                val sizeRate = video?.areSizeAndRateSupported(640, 640, 8.0) == true
                val accepted = planar && cq && q35 && sizeRate
                if (accepted) anyAccepted = true
                report(
                    "CAUSE_B encoder=${info.name} hw=${info.isHardwareAccelerated} " +
                        "sw=${info.isSoftwareOnly} planar=$planar semiPlanar=$semiPlanar " +
                        "flexible=$flexible cq=$cq q35=$q35 sizeRate=$sizeRate " +
                        "ACCEPTED=$accepted qualityRange=${enc?.qualityRange}",
                )
            }.onFailure { report("CAUSE_B encoder=${info.name} capsError=$it") }
        }
        report("CAUSE_B anyEncoderAccepted=$anyAccepted")
    }

    @Test
    fun causeC_tenBitProbeGate() = runBlocking {
        val eightBit = synthesize("sdr8.mkv", "yuv420p")
        val tenBit = synthesize("sdr10.mkv", "yuv420p10le")
        report("CAUSE_C fixtures eightBit=${eightBit?.length()} tenBit=${tenBit?.length()}")

        listOf("8-bit" to eightBit, "10-bit" to tenBit).forEach { (label, file) ->
            if (file == null) {
                report("CAUSE_C $label FIXTURE_FAILED")
                return@forEach
            }
            val probe = executor.executeFfprobe(
                SceneFfmpegArguments.videoProbe(localInput(file), file.absolutePath, null),
            )
            if (probe !is SceneCommandResult.Success) {
                report("CAUSE_C $label ffprobe FAILED")
                return@forEach
            }
            val rejection = SceneMediaProbe.rejectionFor(probe.output)
            report(
                "CAUSE_C $label probeOutput=${probe.output.replace('\n', '|')} " +
                    "rejection=${rejection?.reason ?: "NONE(accepted)"}",
            )
        }
    }

    /** End-to-end: does the real pipeline produce a valid animated AVIF on this device at all? */
    @Test
    fun endToEnd_realPipeline() = runBlocking {
        val eightBit = synthesize("e2e8.mkv", "yuv420p")
        val tenBit = synthesize("e2e10.mkv", "yuv420p10le")

        listOf("8-bit" to eightBit, "10-bit" to tenBit).forEach { (label, file) ->
            if (file == null) {
                report("E2E $label FIXTURE_FAILED")
                return@forEach
            }
            val service = AndroidSceneCaptureService(context)
            val started = System.currentTimeMillis()
            val result = runCatching {
                service.prepare(requestFor(file))
            }.getOrElse {
                report("E2E $label threw=$it")
                return@forEach
            }
            val elapsed = System.currentTimeMillis() - started
            val outcome = when (result) {
                is AnkiScreenshotPreparation.Animated -> {
                    val info = AnimatedAvifValidator.validate(result.animation.file)
                    val bytes = result.animation.file.length()
                    result.animation.file.delete()
                    "ANIMATED bytes=$bytes info=$info"
                }
                is AnkiScreenshotPreparation.Failed -> "FAILED(static fallback)"
                is AnkiScreenshotPreparation.Still -> "STILL"
            }
            report("E2E $label outcome=$outcome elapsedMs=$elapsed")
        }
        assertTrue("diagnostic only", true)
    }

    /**
     * Builds a short clip with the bundled FFmpeg. `ffv1` is used because the build has no
     * software AV1/x264 encoder, and matroska because that is the container Jellyfin serves.
     */
    private suspend fun synthesize(name: String, pixelFormat: String): File? {
        val out = File(workDir, name)
        out.delete()
        val result = executor.executeFfmpeg(
            arrayOf(
                "-f", "lavfi",
                "-i", "testsrc2=size=320x180:rate=8:duration=4",
                "-c:v", "ffv1",
                "-pix_fmt", pixelFormat,
                "-f", "matroska",
                "-y", out.absolutePath,
            ),
        )
        return out.takeIf { result is SceneCommandResult.Success && it.isFile && it.length() > 0 }
    }

    private fun localInput(file: File) = SceneVideoInputSpec(
        value = file.absolutePath,
        kind = SceneVideoInputKind.LOCAL_FILE,
        headers = emptyList(),
    )

    private fun requestFor(file: File): SceneCaptureRequest {
        val input = localInput(file)
        // A real 1x1 bitmap stands in for the captured screenshot; only its lifecycle is exercised.
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return SceneCaptureRequest(
            videoInput = input,
            sentenceAudioInput = input,
            resolvedTiming = SceneResolvedTiming(
                animationRange = SceneTimeRange(0.5, 2.5),
                audioRange = SceneTimeRange(0.5, 2.5),
            ),
            stillFallback = OwnedBitmap(bitmap),
        )
    }

    private fun report(message: String) {
        Log.i("scene-diag", message)
    }
}
