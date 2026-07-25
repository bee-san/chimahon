package eu.kanade.tachiyomi.ui.player.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneMediaProbeTest {
    @Test
    fun `eight bit finite video is supported`() {
        val result = SceneMediaProbe.parse(
            """
            pix_fmt=yuv420p
            color_transfer=bt709
            color_primaries=bt709
            bits_per_raw_sample=8
            duration=42.5
            format_name=mov,mp4,m4a,3gp,3g2,mj2
            """.trimIndent(),
        )

        val supported = assertInstanceOf(SceneMediaProbeResult.Supported::class.java, result)
        assertEquals(42.5, supported.durationSeconds)
    }

    @Test
    fun `ten bit pixel formats and sample depth are rejected`() {
        listOf("yuv420p10le", "gbrp12le", "p010le").forEach { pixelFormat ->
            assertEquals(
                SceneMediaProbeResult.Unsupported(SceneCaptureUnsupportedReason.HDR_OR_TEN_BIT),
                SceneMediaProbe.parse(
                    """
                    pix_fmt=$pixelFormat
                    color_transfer=bt709
                    bits_per_raw_sample=N/A
                    duration=12
                    """.trimIndent(),
                ),
            )
        }
        assertEquals(
            SceneMediaProbeResult.Unsupported(SceneCaptureUnsupportedReason.HDR_OR_TEN_BIT),
            SceneMediaProbe.parse(
                """
                pix_fmt=yuv420p
                color_transfer=bt709
                bits_per_raw_sample=10
                duration=12
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `PQ and HLG transfers are rejected`() {
        listOf("smpte2084", "arib-std-b67").forEach { transfer ->
            assertEquals(
                SceneMediaProbeResult.Unsupported(SceneCaptureUnsupportedReason.HDR_OR_TEN_BIT),
                SceneMediaProbe.parse(
                    """
                    pix_fmt=yuv420p
                    color_transfer=$transfer
                    bits_per_raw_sample=8
                    duration=12
                    """.trimIndent(),
                ),
            )
        }
    }

    @Test
    fun `BT2020 primaries and matrix are rejected`() {
        listOf(
            "color_primaries=bt2020\ncolor_space=bt709",
            "color_primaries=bt709\ncolor_space=bt2020nc",
        ).forEach { colors ->
            assertEquals(
                SceneMediaProbeResult.Unsupported(SceneCaptureUnsupportedReason.HDR_OR_TEN_BIT),
                SceneMediaProbe.parse(
                    """
                    pix_fmt=yuv420p
                    color_transfer=bt709
                    $colors
                    bits_per_raw_sample=8
                    duration=12
                    """.trimIndent(),
                ),
            )
        }
    }

    @Test
    fun `missing nonfinite and nonpositive durations are non seekable`() {
        listOf("N/A", "NaN", "Infinity", "0", "-1").forEach { duration ->
            assertEquals(
                SceneMediaProbeResult.Unsupported(SceneCaptureUnsupportedReason.NON_SEEKABLE),
                SceneMediaProbe.parse(
                    """
                    pix_fmt=yuv420p
                    duration=$duration
                    """.trimIndent(),
                ),
            )
        }
    }

    @Test
    fun `missing pixel format is invalid`() {
        assertEquals(
            SceneMediaProbeResult.Invalid,
            SceneMediaProbe.parse("duration=12"),
        )
    }

    @Test
    fun `encrypted codec tags and crypto formats are rejected`() {
        assertEquals(
            SceneMediaProbeResult.Unsupported(SceneCaptureUnsupportedReason.DRM),
            SceneMediaProbe.parse(
                """
                pix_fmt=yuv420p
                codec_tag_string=encv
                format_name=mov,mp4
                duration=12
                """.trimIndent(),
            ),
        )
        assertEquals(
            SceneMediaProbeResult.Unsupported(SceneCaptureUnsupportedReason.ENCRYPTED),
            SceneMediaProbe.parse(
                """
                pix_fmt=yuv420p
                codec_tag_string=avc1
                format_name=crypto
                duration=12
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `probe arguments request stream classification and format duration`() {
        val arguments = SceneMediaProbe.arguments(
            input = input(),
            acquiredInputValue = "/resolved/video.mp4",
        )
        val showEntries = arguments[arguments.indexOf("-show_entries") + 1]

        assertTrue(showEntries.contains("stream=pix_fmt,color_transfer"))
        assertTrue(showEntries.contains("codec_tag_string"))
        assertTrue(showEntries.contains("format=duration,format_name"))
        assertEquals("/resolved/video.mp4", arguments.last())
    }

    private fun input() = SceneVideoInputSpec(
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
    )
}
