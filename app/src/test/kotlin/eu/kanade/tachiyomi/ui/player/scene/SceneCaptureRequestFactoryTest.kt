package eu.kanade.tachiyomi.ui.player.scene

import android.graphics.Bitmap
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SceneCaptureRequestFactoryTest {
    @Test
    fun `request preserves raw subtitle candidate while resolving media range once`() = runTest {
        val mpv = SceneMpvSnapshot(
            anchorMediaSeconds = 13.0,
            mediaDurationSeconds = 120.0,
            subtitleStartSeconds = null,
            subtitleEndSeconds = null,
            subtitleSpeed = 1.0,
            subtitleDelaySeconds = 2.0,
            playableValue = "/video.mp4",
            selectedAudioId = null,
            selectedAudioFfmpegIndex = null,
            selectedExternalAudioValue = null,
            selectedAudioIsExternal = false,
            seekable = true,
        )
        val reader = mockk<SceneMpvSnapshotReader>()
        every { reader.read() } returnsMany listOf(mpv, mpv)
        val bitmap = mockk<Bitmap>(relaxed = true)
        every { bitmap.isRecycled } returns false
        val rawSubtitleCandidate = SceneRangeCandidate(
            startSeconds = 10.0,
            endSeconds = 12.0,
            clockDomain = SceneClockDomain.SUBTITLE,
            provenance = SceneRangeProvenance.PARSED_SUBTITLE_CUE,
        )

        val request = SceneCaptureRequestFactory(reader).captureSubtitle(
            videoSnapshot = {
                SceneVideoInputSnapshot(
                    originalVideoValue = "/video.mp4",
                    playableValue = "/video.mp4",
                    externalAudioValue = null,
                    headers = emptyList(),
                    ffmpegStreamArgs = emptyList(),
                    ffmpegVideoArgs = emptyList(),
                    episodeId = 1L,
                    sourceId = 2L,
                    quality = "1080p",
                    seekable = true,
                )
            },
            parsedSubtitleCandidates = listOf(rawSubtitleCandidate),
            playbackFallback = null,
            captureFallback = { bitmap },
        )

        assertNotNull(request)
        assertEquals(rawSubtitleCandidate, request?.sourceRangeCandidate)
        assertEquals(SceneTimeRange(12.0, 14.0), request?.resolvedTiming?.sourceRange)

        request?.close()
    }
}
