package eu.kanade.tachiyomi.ui.player.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneTimingTest {
    @Test
    fun `subtitle clock is converted exactly once`() {
        val result = resolve(
            snapshot = snapshot(anchor = 7.0, speed = 2.0, delay = 1.0),
            mpv = subtitleCandidate(2.0, 4.0),
        )

        assertEquals(SceneTimeRange(5.0, 9.0), result?.sourceRange)
    }

    @Test
    fun `media clock is not adjusted`() {
        val result = resolve(
            snapshot = snapshot(anchor = 7.0, speed = 2.0, delay = 1.0),
            fallback = mediaCandidate(5.0, 9.0, SceneRangeProvenance.PLAYBACK_POSITION),
        )

        assertEquals(SceneTimeRange(5.0, 9.0), result?.sourceRange)
    }

    @Test
    fun `negative delay and fractional subtitle speed are snapshotted`() {
        val result = resolve(
            snapshot = snapshot(anchor = 1.5, speed = 0.5, delay = -0.5),
            mpv = subtitleCandidate(2.0, 6.0),
        )

        assertEquals(SceneTimeRange(0.5, 2.5), result?.sourceRange)
    }

    @Test
    fun `invalid mpv pair falls back to parsed cue without mixing endpoints`() {
        val result = resolve(
            snapshot = snapshot(anchor = 6.0),
            mpv = subtitleCandidate(Double.NaN, 7.0),
            parsed = listOf(subtitleCandidate(5.0, 7.0, SceneRangeProvenance.PARSED_SUBTITLE_CUE)),
        )

        assertEquals(SceneRangeProvenance.PARSED_SUBTITLE_CUE, result?.provenance)
        assertEquals(SceneTimeRange(5.0, 7.0), result?.sourceRange)
    }

    @Test
    fun `incomplete mpv endpoint pairs fall back without mixing provenance`() {
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
            playbackFallback = mediaCandidate(
                5.5,
                6.5,
                SceneRangeProvenance.PLAYBACK_POSITION,
            ),
        )

        assertEquals(SceneRangeProvenance.PARSED_SUBTITLE_CUE, result?.provenance)
        assertEquals(SceneTimeRange(5.0, 7.0), result?.sourceRange)
    }

    @Test
    fun `out of range mpv and parsed cues fall back to playback position`() {
        val result = resolve(
            snapshot = snapshot(anchor = 20.0, duration = 60.0),
            mpv = subtitleCandidate(1.0, 2.0),
            parsed = listOf(subtitleCandidate(70.0, 71.0, SceneRangeProvenance.PARSED_SUBTITLE_CUE)),
            fallback = mediaCandidate(19.0, 21.0, SceneRangeProvenance.PLAYBACK_POSITION),
        )

        assertEquals(SceneRangeProvenance.PLAYBACK_POSITION, result?.provenance)
        assertEquals(SceneTimeRange(19.0, 21.0), result?.sourceRange)
    }

    @Test
    fun `anchor tolerance accepts frame-edge rounding`() {
        val result = resolve(
            snapshot = snapshot(anchor = 4.1),
            mpv = subtitleCandidate(2.0, 4.0),
        )

        assertEquals(SceneRangeProvenance.MPV_SUBTITLE_PROPERTIES, result?.provenance)
    }

    @Test
    fun `reversed infinite negative and out of media candidates are rejected`() {
        val invalid = listOf(
            subtitleCandidate(4.0, 2.0),
            subtitleCandidate(2.0, Double.POSITIVE_INFINITY),
            subtitleCandidate(-2.0, 2.0),
            subtitleCandidate(9.0, 11.0),
        )

        invalid.forEach { candidate ->
            assertNull(
                resolve(
                    snapshot = snapshot(anchor = 1.0, duration = 10.0),
                    mpv = candidate,
                    fallback = mediaCandidate(-2.0, -1.0, SceneRangeProvenance.PLAYBACK_POSITION),
                ),
            )
        }
    }

    @Test
    fun `long cue is center trimmed around playback anchor`() {
        val result = SceneTimingResolver.deriveCappedRange(
            sourceRange = SceneTimeRange(0.0, 100.0),
            anchorSeconds = 70.0,
            capSeconds = 10.0,
            mediaDurationSeconds = 120.0,
        )

        assertEquals(SceneTimeRange(65.0, 75.0), result)
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
    fun `range clamps at media start while preserving requested duration`() {
        val result = SceneTimingResolver.deriveCappedRange(
            sourceRange = SceneTimeRange(0.0, 1.0),
            anchorSeconds = 0.1,
            capSeconds = 10.0,
            mediaDurationSeconds = 20.0,
        )

        assertEquals(SceneTimeRange(0.0, 1.0), result)
    }

    @Test
    fun `range clamps at known media end while preserving requested duration`() {
        val result = SceneTimingResolver.deriveCappedRange(
            sourceRange = SceneTimeRange(19.0, 20.0),
            anchorSeconds = 19.9,
            capSeconds = 10.0,
            mediaDurationSeconds = 20.0,
        )

        assertEquals(SceneTimeRange(19.0, 20.0), result)
    }

    @Test
    fun `known media shorter than minimum has no animated range`() {
        assertNull(
            SceneTimingResolver.deriveCappedRange(
                sourceRange = SceneTimeRange(0.0, 0.2),
                anchorSeconds = 0.1,
                capSeconds = 10.0,
                mediaDurationSeconds = 0.2,
            ),
        )
    }

    @Test
    fun `animation and audio caps are derived independently`() {
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
    fun `ocr padding uses captured media time and media bounds`() {
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
        mpv: SceneRangeCandidate? = null,
        parsed: List<SceneRangeCandidate> = emptyList(),
        fallback: SceneRangeCandidate = mediaCandidate(
            0.5,
            1.5,
            SceneRangeProvenance.PLAYBACK_POSITION,
        ),
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
            parsedSubtitleRanges = parsed,
            playbackFallback = fallback,
        )
    }

    private fun snapshot(
        anchor: Double,
        speed: Double = 1.0,
        delay: Double = 0.0,
        duration: Double? = null,
    ) = SceneTimingSnapshot(
        anchorSeconds = anchor,
        subtitleSpeed = speed,
        subtitleDelaySeconds = delay,
        mediaDurationSeconds = duration,
    )

    private fun subtitleCandidate(
        start: Double,
        end: Double,
        provenance: SceneRangeProvenance = SceneRangeProvenance.MPV_SUBTITLE_PROPERTIES,
    ) = SceneRangeCandidate(start, end, SceneClockDomain.SUBTITLE, provenance)

    private fun mediaCandidate(
        start: Double,
        end: Double,
        provenance: SceneRangeProvenance,
    ) = SceneRangeCandidate(start, end, SceneClockDomain.MEDIA, provenance)
}
