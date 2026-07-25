package eu.kanade.tachiyomi.ui.player.scene

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneMediaProbeTest {
    @Test
    fun `ordinary eight bit SDR video is safe`() {
        assertTrue(
            SceneMediaProbe.inspect(
                """
                pix_fmt=yuv420p
                color_transfer=bt709
                color_primaries=bt709
                bits_per_raw_sample=8
                profile=Main
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `ten bit and HDR video are rejected`() {
        listOf(
            "pix_fmt=yuv420p10le\ncolor_transfer=bt709",
            "pix_fmt=yuv420p\ncolor_transfer=smpte2084",
            "pix_fmt=yuv420p\ncolor_primaries=bt2020",
            "pix_fmt=yuv420p\nbits_per_raw_sample=10",
        ).forEach { output ->
            assertFalse(SceneMediaProbe.inspect(output))
        }
    }

    @Test
    fun `protected and unprobeable media fail closed`() {
        listOf(
            "pix_fmt=yuv420p\nside_data_type=Encryption info",
            "pix_fmt=unknown",
            "",
        ).forEach { output ->
            assertFalse(SceneMediaProbe.inspect(output))
        }
    }

    @Test
    fun `audio probe requires a clear audio stream`() {
        assertTrue(SceneMediaProbe.inspectAudio("codec_type=audio\ncodec_name=aac"))
        assertFalse(SceneMediaProbe.inspectAudio("codec_type=audio\nside_data_type=cenc"))
    }
}
