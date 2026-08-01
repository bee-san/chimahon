package eu.kanade.tachiyomi.ui.player.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `ten bit SDR video is safe`() {
        listOf(
            "pix_fmt=yuv420p10le\ncolor_transfer=bt709",
            "pix_fmt=yuv420p\nbits_per_raw_sample=10",
            "pix_fmt=yuv420p10le\nprofile=Main 10",
        ).forEach { output ->
            assertTrue(SceneMediaProbe.inspect(output))
        }
    }

    @Test
    fun `HDR video is rejected`() {
        listOf(
            "pix_fmt=yuv420p\ncolor_transfer=smpte2084",
            "pix_fmt=yuv420p\ncolor_primaries=bt2020",
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
    fun `video inspection returns dimensions after display rotation`() {
        assertEquals(
            SceneVideoDimensions(width = 320, height = 180),
            SceneMediaProbe.inspectVideo(
                "width=320\nheight=180\npix_fmt=yuv420p\ncolor_transfer=bt709",
            ),
        )
        assertEquals(
            SceneVideoDimensions(width = 1080, height = 1920),
            SceneMediaProbe.inspectVideo(
                "width=1920\nheight=1080\npix_fmt=yuv420p\ncolor_transfer=bt709\nrotation=90",
            ),
        )
    }

    @Test
    fun `video inspection accepts only orthogonal display rotation`() {
        listOf(-90, 90, 270, 450).forEach { rotation ->
            assertEquals(
                SceneVideoDimensions(width = 180, height = 320),
                SceneMediaProbe.inspectVideo(
                    "width=320\nheight=180\npix_fmt=yuv420p\nrotation=$rotation",
                ),
            )
        }
        assertEquals(
            SceneVideoDimensions(width = 320, height = 180),
            SceneMediaProbe.inspectVideo(
                "width=320\nheight=180\npix_fmt=yuv420p\nrotation=180",
            ),
        )
        assertNull(
            SceneMediaProbe.inspectVideo(
                "width=320\nheight=180\npix_fmt=yuv420p\nrotation=45",
            ),
        )
    }

    @Test
    fun `video inspection applies sample aspect ratio before display rotation`() {
        assertEquals(
            SceneVideoDimensions(width = 768, height = 576),
            SceneMediaProbe.inspectVideo(
                "width=720\nheight=576\nsample_aspect_ratio=16:15\n" +
                    "pix_fmt=yuv420p\ncolor_transfer=bt709",
            ),
        )
        assertEquals(
            SceneVideoDimensions(width = 576, height = 768),
            SceneMediaProbe.inspectVideo(
                "width=720\nheight=576\nsample_aspect_ratio=16:15\n" +
                    "pix_fmt=yuv420p\ncolor_transfer=bt709\nrotation=90",
            ),
        )
    }

    @Test
    fun `video inspection requires safe positive dimensions`() {
        assertNull(SceneMediaProbe.inspectVideo("pix_fmt=yuv420p"))
        assertNull(SceneMediaProbe.inspectVideo("width=0\nheight=180\npix_fmt=yuv420p"))
        assertNull(
            SceneMediaProbe.inspectVideo(
                "width=320\nheight=180\npix_fmt=yuv420p\ncolor_transfer=smpte2084",
            ),
        )
    }

    @Test
    fun `audio probe requires a clear audio stream`() {
        assertTrue(SceneMediaProbe.inspectAudio("codec_type=audio\ncodec_name=aac"))
        assertFalse(SceneMediaProbe.inspectAudio("codec_type=audio\nside_data_type=cenc"))
    }
}
