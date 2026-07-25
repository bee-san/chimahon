package eu.kanade.tachiyomi.ui.player.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

class AnimatedAvifValidatorTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `accepts a structurally valid bounded animated AVIF`() {
        assertEquals(
            AnimatedAvifInfo(width = 64, height = 48, frameCount = 4, totalDurationMillis = 500),
            validate(avif()),
        )
    }

    @Test
    fun `requires bounded files and core AVIF boxes`() {
        assertNull(AnimatedAvifValidator.validate(directory.resolve("missing.avif")))
        assertNull(validate(byteArrayOf()))
        assertNull(validate(avif(includeMeta = false)))
        assertNull(validate(avif(mediaBytes = 0)))

        val oversized = directory.resolve("oversized.avif")
        RandomAccessFile(oversized, "rw").use { it.setLength(MAX_FILE_BYTES + 1) }
        assertNull(AnimatedAvifValidator.validate(oversized))
    }

    @Test
    fun `requires animated AVIF brands and an AV1 configuration`() {
        assertNull(validate(avif(majorBrand = "avif")))
        assertNull(validate(avif(brands = listOf("avif"))))
        assertNull(validate(avif(includeAv1Config = false)))
        assertNull(validate(avif(width = 641)))
    }

    @Test
    fun `requires two to eighty positively timed frames near eight fps`() {
        assertNull(validate(avif(frames = 1)))
        assertNull(validate(avif(frames = 81)))
        assertNull(validate(avif(frameDuration = 0)))
        assertNull(validate(avif(frameDuration = 2_000)))
    }

    @Test
    fun `requires matching nonzero sample sizes within media data`() {
        assertNull(validate(avif(sampleSizes = listOf(1, 1, 0, 1))))
        assertNull(validate(avif(sampleSizes = listOf(1, 1, 1))))
        assertNull(validate(avif(mediaBytes = 3)))
    }

    private fun avif(
        majorBrand: String = "avis",
        brands: List<String> = listOf("avif", "MA1B"),
        includeMeta: Boolean = true,
        includeAv1Config: Boolean = true,
        width: Int = 64,
        frames: Int = 4,
        frameDuration: Int = 1_000,
        sampleSizes: List<Int> = List(frames) { 1 },
        mediaBytes: Int = sampleSizes.sum(),
    ): ByteArray {
        val fileType = majorBrand.ascii() + ByteArray(4) + brands.fold(byteArrayOf()) { bytes, brand -> bytes + brand.ascii() }
        val sampleEntry = box(
            "av01",
            ByteArray(78).apply {
                writeUInt16(24, width)
                writeUInt16(26, 48)
            } + if (includeAv1Config) box("av1C", byteArrayOf(0x81.toByte(), 0, 0, 0)) else byteArrayOf(),
        )
        val sampleDescription = ByteArray(8).apply { writeUInt32(4, 1) } + sampleEntry
        val sampleTiming = ByteArray(16).apply {
            writeUInt32(4, 1)
            writeUInt32(8, frames)
            writeUInt32(12, frameDuration)
        }
        val sampleSizeTable = ByteArray(12 + sampleSizes.size * 4).apply {
            writeUInt32(8, sampleSizes.size)
            sampleSizes.forEachIndexed { index, size -> writeUInt32(12 + index * 4, size) }
        }
        val mediaHeader = ByteArray(24).apply {
            writeUInt32(12, 8_000)
            writeUInt32(16, frames * frameDuration)
        }
        val sampleTable =
            box("stsd", sampleDescription) +
                box("stts", sampleTiming) +
                box("stsz", sampleSizeTable)
        val movie = box(
            "moov",
            box(
                "trak",
                box(
                    "mdia",
                    box("mdhd", mediaHeader) +
                        box("minf", box("stbl", sampleTable)),
                ),
            ),
        )
        return box("ftyp", fileType) +
            (if (includeMeta) box("meta", ByteArray(4)) else byteArrayOf()) +
            movie +
            box("mdat", ByteArray(mediaBytes))
    }

    private fun validate(bytes: ByteArray): AnimatedAvifInfo? {
        val file = directory.resolve("scene-${System.nanoTime()}.avif")
        file.writeBytes(bytes)
        return AnimatedAvifValidator.validate(file)
    }

    private fun String.ascii() = toByteArray(Charsets.US_ASCII)

    private fun ByteArray.writeUInt16(offset: Int, value: Int) {
        this[offset] = (value ushr 8).toByte()
        this[offset + 1] = value.toByte()
    }

    private fun ByteArray.writeUInt32(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        return ByteArrayOutputStream().apply {
            writeUInt32(payload.size + 8)
            write(type.ascii())
            write(payload)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeUInt32(value: Int) {
        write(value ushr 24)
        write(value ushr 16)
        write(value ushr 8)
        write(value)
    }

    private companion object {
        const val MAX_FILE_BYTES = 10L * 1024L * 1024L
    }
}
