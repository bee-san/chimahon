package eu.kanade.tachiyomi.ui.player.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Diagnostic for the "falls back to static" report on the Jellyfin extension.
 *
 * Jellyfin's `?static=True` endpoint serves the untranscoded original, which for anime is
 * overwhelmingly 10-bit (HEVC Main 10 or H.264 Hi10P). Ordinary streaming extensions serve 8-bit
 * H.264 transcodes. These cases pin that asymmetry to a specific gate, and record that the
 * rejection is reported precisely enough to identify it from a single log line.
 */
class SceneMediaProbeTenBitDiagnosticTest {
    @Test
    fun `eight bit h264 transcode as served by ordinary extensions is accepted`() {
        assertNull(
            SceneMediaProbe.rejectionFor(
                """
                pix_fmt=yuv420p
                color_transfer=unknown
                color_primaries=unknown
                bits_per_raw_sample=8
                profile=High
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `ten bit hevc original as served by jellyfin static is refused for bit depth`() {
        val rejection = SceneMediaProbe.rejectionFor(
            """
            pix_fmt=yuv420p10le
            color_transfer=unknown
            color_primaries=unknown
            bits_per_raw_sample=10
            profile=Main 10
            """.trimIndent(),
        )

        assertTrue(rejection is SceneMediaProbe.Rejection.HighBitDepth, "was $rejection")
        assertEquals("high-bit-depth", rejection?.reason)
    }

    @Test
    fun `ten bit h264 hi10p original is refused on pixel format alone`() {
        // ffprobe often omits bits_per_raw_sample for Hi10P, so pix_fmt must carry the decision.
        val rejection = SceneMediaProbe.rejectionFor(
            """
            pix_fmt=yuv420p10le
            profile=High 10
            """.trimIndent(),
        )

        assertTrue(rejection is SceneMediaProbe.Rejection.HighBitDepth, "was $rejection")
    }

    @Test
    fun `true HDR is reported separately from ordinary ten bit`() {
        // Distinguishing these matters: plain 10-bit only needs downconversion, which the output
        // `-pix_fmt yuv420p` already performs, whereas HDR additionally needs tonemapping.
        val pq = SceneMediaProbe.rejectionFor("pix_fmt=yuv420p\ncolor_transfer=smpte2084")
        val bt2020 = SceneMediaProbe.rejectionFor("pix_fmt=yuv420p\ncolor_primaries=bt2020")

        assertTrue(pq is SceneMediaProbe.Rejection.Hdr, "was $pq")
        assertTrue(bt2020 is SceneMediaProbe.Rejection.Hdr, "was $bt2020")
    }

    @Test
    fun `protection markers still fail closed ahead of any format inspection`() {
        val rejection = SceneMediaProbe.rejectionFor("pix_fmt=yuv420p\nside_data_type=Encryption info")

        assertEquals(SceneMediaProbe.Rejection.ProtectionMarker, rejection)
    }
}
