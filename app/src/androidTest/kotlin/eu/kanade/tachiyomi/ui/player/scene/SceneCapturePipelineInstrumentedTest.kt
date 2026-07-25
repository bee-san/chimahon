package eu.kanade.tachiyomi.ui.player.scene

import android.graphics.BitmapFactory
import android.graphics.Movie
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Non-skippable device/emulator coverage for the packaged native and Android-only media path.
 *
 * The checked-in fixture is synthetic, CC0, SDR, and carries 90-degree display rotation. Its
 * provenance and digest live next to the asset.
 */
@LargeTest
@SdkSuppress(minSdkVersion = 26)
@RunWith(AndroidJUnit4::class)
class SceneCapturePipelineInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testRoot = File(context.cacheDir, "scene-capture-instrumented-${UUID.randomUUID()}")

    @After
    fun cleanUp() {
        testRoot.deleteRecursively()
    }

    @Test
    fun localAndReadOnlySafInputsCompleteTheRealMediaPipeline() = runBlocking {
        assertTrue(testRoot.mkdirs())
        val fixture = File(testRoot, "fixture.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(ReadOnlySceneFixtureProvider.FIXTURE_ASSET)
            .use { input ->
                fixture.outputStream().use(input::copyTo)
            }
        assertTrue(fixture.isFile && fixture.length() > 0L)

        val executor = FfmpegKitSceneCommandExecutor()
        val pipeline = SceneCapturePipeline(
            cacheRoot = testRoot,
            commandExecutor = executor,
            inputAcquirer = AndroidSceneInputAcquirer(context),
            inputProtectionChecker = AndroidSceneInputProtectionChecker(context),
            frameEncoder = AndroidSceneWebpFrameEncoder(),
            timeoutMillis = PIPELINE_TIMEOUT_MILLIS,
        )

        assertSuccessfulCapture(
            pipeline = pipeline,
            value = fixture.absolutePath,
            kind = SceneVideoInputKind.LOCAL_FILE,
        )

        val testContext = InstrumentationRegistry.getInstrumentation().context
        val contentUri = Uri.Builder()
            .scheme("content")
            .authority("${testContext.packageName}.scene_fixture")
            .appendPath(ReadOnlySceneFixtureProvider.FIXTURE_ASSET)
            .build()
        val writeAttempt = runCatching {
            context.contentResolver.openOutputStream(contentUri)?.use { Unit }
        }
        assertTrue("Fixture provider unexpectedly accepted a write", writeAttempt.isFailure)
        assertSuccessfulCapture(
            pipeline = pipeline,
            value = contentUri.toString(),
            kind = SceneVideoInputKind.CONTENT_URI,
        )
    }

    private suspend fun assertSuccessfulCapture(
        pipeline: SceneCapturePipeline,
        value: String,
        kind: SceneVideoInputKind,
    ) {
        val result = pipeline.capture(
            SceneCapturePipelineRequest(
                input = SceneVideoInputSpec(
                    value = value,
                    kind = kind,
                    headers = emptyList(),
                    inputOptions = emptyList(),
                    externalAudioValue = null,
                    identity = SceneVideoIdentity(
                        episodeId = 1L,
                        sourceId = 2L,
                        quality = "instrumented",
                        inputDigest = "instrumented",
                    ),
                ),
                animationRange = SceneTimeRange(
                    startSeconds = 0.0,
                    endSeconds = 0.75,
                ),
            ),
        )
        assertTrue("Unexpected capture result: $result", result is SceneCaptureResult.Success)
        val success = result as SceneCaptureResult.Success
        try {
            assertTrue(success.output.file.isFile)
            assertTrue(success.metrics.frameCount in SCENE_MIN_FRAME_COUNT..SCENE_MAX_FRAME_COUNT)
            assertTrue(AnimatedWebpValidator.validate(success.output.file) is AnimatedWebpValidation.Valid)
            assertEquals(64, success.info.width)
            assertEquals(96, success.info.height)
            assertEquals(0, success.info.loopCount)
            assertEquals(success.metrics.frameCount, success.info.frameCount)
            assertEquals(success.info.frameCount * 125L, success.info.totalDurationMillis)

            val firstFrame = BitmapFactory.decodeFile(success.output.file.absolutePath)
            assertNotNull("Android could not decode the animated WebP first frame", firstFrame)
            try {
                assertFalse("Unexpected wide output; display rotation was not applied", firstFrame!!.width > firstFrame.height)
            } finally {
                firstFrame?.recycle()
            }

            val movie = Movie.decodeFile(success.output.file.absolutePath)
            assertNotNull("Android Movie could not open the animated WebP", movie)
            assertTrue("Animated WebP reported no playback duration", movie!!.duration() > 0)
        } finally {
            success.output.close()
        }
    }

    private companion object {
        const val PIPELINE_TIMEOUT_MILLIS = 60_000L
    }
}
