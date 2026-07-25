package eu.kanade.tachiyomi.ui.player.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneTimingTest {
    @Test
    fun `subtitle timing is converted to media clock exactly once`() {
        val result = resolve(
            snapshot = snapshot(anchor = 7.0, speed = 2.0, delay = 1.0),
            mpv = subtitleCandidate(2.0, 4.0),
        )

        assertEquals(SceneTimeRange(5.0, 9.0), result?.animationRange)
    }

    @Test
    fun `incomplete mpv endpoints fall back to parsed cue`() {
        val result = SceneTimingResolver.resolve(
            snapshot = snapshot(anchor = 6.0),
            mpvSubtitleRange = SceneRangeEndpointPair(
                startSeconds = 5.0,
                endSeconds = null,
                clockDomain = SceneClockDomain.SUBTITLE,
                provenance = SceneRangeProvenance.MPV_SUBTITLE_PROPERTIES,
            ),
            parsedSubtitleRanges = listOf(
                subtitleCandidate(5.0, 7.0, SceneRangeProvenance.PARSED_SUBTITLE_CUE),
            ),
            playbackFallback = mediaCandidate(5.5, 6.5),
        )

        assertEquals(SceneTimeRange(5.0, 7.0), result?.animationRange)
    }

    @Test
    fun `long cue has independent animation and audio caps`() {
        val result = resolve(
            snapshot = snapshot(anchor = 20.0, duration = 60.0),
            mpv = subtitleCandidate(0.0, 50.0),
        )

        assertEquals(10.0, result?.animationRange?.durationSeconds)
        assertEquals(30.0, result?.audioRange?.durationSeconds)
        assertTrue(result!!.animationRange.contains(20.0))
        assertTrue(result.audioRange.contains(20.0))
    }

    @Test
    fun `short cue expands to minimum duration`() {
        val result = SceneTimingResolver.deriveCappedRange(
            sourceRange = SceneTimeRange(2.0, 2.1),
            anchorSeconds = 2.05,
            capSeconds = 10.0,
            mediaDurationSeconds = 20.0,
        )

        assertEquals(0.25, result?.durationSeconds ?: 0.0, 1e-9)
        assertTrue(result!!.contains(2.05))
    }

    @Test
    fun `candidate outside known media is rejected`() {
        assertNull(
            resolve(
                snapshot = snapshot(anchor = 10.0, duration = 10.0),
                mpv = subtitleCandidate(9.0, 11.0),
                fallback = mediaCandidate(11.0, 12.0),
            ),
        )
    }

    @Test
    fun `ocr padding uses media clock and clamps to media bounds`() {
        assertEquals(
            SceneRangeCandidate(
                startSeconds = 0.0,
                endSeconds = 2.1,
                clockDomain = SceneClockDomain.MEDIA,
                provenance = SceneRangeProvenance.OCR_CAPTURE,
            ),
            SceneTimingResolver.ocrRange(
                anchorSeconds = 0.1,
                paddingSeconds = 2.0,
                mediaDurationSeconds = 20.0,
            ),
        )
    }

    private fun resolve(
        snapshot: SceneTimingSnapshot,
        mpv: SceneRangeCandidate?,
        fallback: SceneRangeCandidate = mediaCandidate(0.5, 1.5),
    ): SceneResolvedTiming? {
        return SceneTimingResolver.resolve(
            snapshot = snapshot,
            mpvSubtitleRange = mpv?.let {
                SceneRangeEndpointPair(
                    startSeconds = it.startSeconds,
                    endSeconds = it.endSeconds,
                    clockDomain = it.clockDomain,
                    provenance = it.provenance,
                )
            },
            parsedSubtitleRanges = emptyList(),
            playbackFallback = fallback,
        )
    }

    private fun snapshot(
        anchor: Double,
        speed: Double = 1.0,
        delay: Double = 0.0,
        duration: Double? = null,
    ) = SceneTimingSnapshot(anchor, speed, delay, duration)

    private fun subtitleCandidate(
        start: Double,
        end: Double,
        provenance: SceneRangeProvenance = SceneRangeProvenance.MPV_SUBTITLE_PROPERTIES,
    ) = SceneRangeCandidate(start, end, SceneClockDomain.SUBTITLE, provenance)

    private fun mediaCandidate(
        start: Double,
        end: Double,
    ) = SceneRangeCandidate(
        start,
        end,
        SceneClockDomain.MEDIA,
        SceneRangeProvenance.PLAYBACK_POSITION,
    )
}
