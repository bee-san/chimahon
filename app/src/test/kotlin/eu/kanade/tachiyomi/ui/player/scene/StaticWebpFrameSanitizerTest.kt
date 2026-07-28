package eu.kanade.tachiyomi.ui.player.scene

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File

class StaticWebpFrameSanitizerTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `simple lossy WebP is preserved`() {
        val sourceBytes = riff(chunk("VP8 ", byteArrayOf(1, 2, 3)))
        val source = directory.resolve("source.webp").apply { writeBytes(sourceBytes) }
        val destination = directory.resolve("sanitized.webp")

        assertTrue(StaticWebpFrameSanitizer.sanitize(source, destination))
        assertArrayEquals(sourceBytes, destination.readBytes())
    }

    @Test
    fun `extended metadata is removed while alpha and image chunks are preserved`() {
        val alpha = chunk("ALPH", byteArrayOf(1, 2, 3))
        val image = chunk("VP8 ", byteArrayOf(4, 5, 6))
        val source = directory.resolve("source.webp").apply {
            writeBytes(
                riff(
                    chunk("VP8X", ByteArray(10)),
                    chunk("ICCP", byteArrayOf(7)),
                    alpha,
                    image,
                    chunk("EXIF", byteArrayOf(8)),
                    chunk("XMP ", byteArrayOf(9)),
                ),
            )
        }
        val destination = directory.resolve("sanitized.webp")

        assertTrue(StaticWebpFrameSanitizer.sanitize(source, destination))
        assertArrayEquals(riff(alpha, image), destination.readBytes())
    }

    @Test
    fun `animated and malformed WebPs are rejected without an output`() {
        val destination = directory.resolve("sanitized.webp")
        val animated = directory.resolve("animated.webp").apply {
            writeBytes(
                riff(
                    chunk("VP8X", ByteArray(10)),
                    chunk("ANIM", ByteArray(6)),
                    chunk("ANMF", ByteArray(16)),
                ),
            )
        }
        assertFalse(StaticWebpFrameSanitizer.sanitize(animated, destination))
        assertFalse(destination.exists())

        val truncated = directory.resolve("truncated.webp").apply {
            writeBytes(riff(chunk("VP8 ", byteArrayOf(1))).copyOf(15))
        }
        assertFalse(StaticWebpFrameSanitizer.sanitize(truncated, destination))
        assertFalse(destination.exists())
    }

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val payload = ByteArrayOutputStream().apply {
            write("WEBP".toByteArray())
            chunks.forEach(::write)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray())
            writeUInt32(payload.size.toLong())
            write(payload)
        }.toByteArray()
    }

    private fun chunk(name: String, payload: ByteArray): ByteArray {
        return ByteArrayOutputStream().apply {
            write(name.toByteArray())
            writeUInt32(payload.size.toLong())
            write(payload)
            if (payload.size % 2 != 0) write(0)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeUInt32(value: Long) {
        write((value and 0xff).toInt())
        write(((value ushr 8) and 0xff).toInt())
        write(((value ushr 16) and 0xff).toInt())
        write(((value ushr 24) and 0xff).toInt())
    }
}
