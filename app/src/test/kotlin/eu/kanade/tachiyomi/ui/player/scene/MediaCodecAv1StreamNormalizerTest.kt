package eu.kanade.tachiyomi.ui.player.scene

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MediaCodecAv1StreamNormalizerTest {
    @Test
    fun `strips av1C and restores temporal boundaries`() {
        assertArrayEquals(
            byteArrayOf(
                0x12,
                0x00,
                0x0a,
                0x01,
                0x00,
                0x32,
                0x01,
                0x11,
                0x12,
                0x00,
                0x32,
                0x01,
                0x22,
            ),
            MediaCodecAv1StreamNormalizer.normalize(mediaCodecAv1PacketStream()),
        )
    }

    @Test
    fun `rejects malformed and single-frame streams`() {
        assertNull(MediaCodecAv1StreamNormalizer.normalize(byteArrayOf(0x81.toByte(), 0x00)))
        assertNull(
            MediaCodecAv1StreamNormalizer.normalize(
                mediaCodecAv1PacketStream().dropLast(3).toByteArray(),
            ),
        )
    }
}

internal fun mediaCodecAv1PacketStream(): ByteArray {
    return byteArrayOf(
        0x81.toByte(),
        0x00,
        0x00,
        0x00,
        0x0a,
        0x01,
        0x00,
        0x32,
        0x01,
        0x11,
        0x32,
        0x01,
        0x22,
    )
}
