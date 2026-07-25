package eu.kanade.tachiyomi.ui.player.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File

class AnimatedWebpValidatorTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `valid infinite animated WebP is accepted`() {
        val result = validate(webp())

        val valid = assertInstanceOf(AnimatedWebpValidation.Valid::class.java, result)
        assertEquals(2, valid.info.frameCount)
        assertEquals(200L, valid.info.totalDurationMillis)
        assertEquals(0, valid.info.loopCount)
    }

    @Test
    fun `declared RIFF size must match file`() {
        val bytes = webp().copyOf().also { it[4] = 0 }

        assertInvalid(bytes, "RIFF size")
    }

    @Test
    fun `WEBP signature is required`() {
        val bytes = webp().copyOf().also { it[8] = 'X'.code.toByte() }

        assertInvalid(bytes, "WEBP")
    }

    @Test
    fun `truncated and overflowing chunks are rejected`() {
        assertInvalid(webp().copyOf(webp().size - 1), "RIFF size")
        val overflowing = webp().copyOf().also {
            val firstChunkSizeOffset = 16
            it[firstChunkSizeOffset] = 0xff.toByte()
            it[firstChunkSizeOffset + 1] = 0xff.toByte()
            it[firstChunkSizeOffset + 2] = 0xff.toByte()
            it[firstChunkSizeOffset + 3] = 0x7f
        }
        assertInvalid(overflowing, "bounds")
    }

    @Test
    fun `odd chunk padding must stay inside RIFF bounds`() {
        val bytes = riff(
            chunk("ODD!", byteArrayOf(1), includePadding = false),
        )

        assertInvalid(bytes, "bounds")
    }

    @Test
    fun `animation flag is mandatory`() {
        assertInvalid(webp(animationFlag = false), "animation flag")
    }

    @Test
    fun `missing or duplicate structural chunks are rejected`() {
        assertInvalid(riff(vp8x()), "ANIM")
        assertInvalid(riff(vp8x(), anim(), anim(), anmf(), anmf()), "Duplicate ANIM")
        assertInvalid(riff(), "VP8X")
        assertInvalid(riff(vp8x(), vp8x(), anim(), anmf(), anmf()), "Duplicate VP8X")
    }

    @Test
    fun `structural chunks must appear in VP8X ANIM ANMF order`() {
        assertInvalid(riff(anim(), vp8x(), anmf(), anmf()), "structural order")
        assertInvalid(riff(vp8x(), anmf(), anim(), anmf()), "structural order")
        assertInvalid(riff(anmf(), vp8x(), anim(), anmf()), "structural order")
    }

    @Test
    fun `VP8X is first and structural chunks are contiguous`() {
        val unknown = chunk("JUNK", byteArrayOf(1, 2))

        assertInvalid(riff(unknown, vp8x(), anim(), anmf(), anmf()), "structural order")
        assertInvalid(riff(vp8x(), unknown, anim(), anmf(), anmf()), "structural order")
        assertInvalid(riff(vp8x(), anim(), unknown, anmf(), anmf()), "structural order")
        assertInvalid(riff(vp8x(), anim(), anmf(), unknown, anmf()), "structural order")
        assertInvalid(riff(vp8x(), anim(), anmf(), anmf(), unknown), "structural order")
    }

    @Test
    fun `non-zero loop count is rejected`() {
        assertInvalid(webp(loopCount = 1), "infinitely looping")
    }

    @Test
    fun `frame count must be between two and eighty`() {
        assertInvalid(riff(vp8x(), anim(), anmf()), "frame count")
        val tooMany = Array(81) { anmf() }
        assertInvalid(riff(vp8x(), anim(), *tooMany), "frame count")
    }

    @Test
    fun `frame eighty one is rejected before its payload is parsed`() {
        val frames = Array(80) { anmf() }

        assertInvalid(
            riff(vp8x(), anim(), *frames, declaredChunk("ANMF", Int.MAX_VALUE.toLong())),
            "frame count",
        )
    }

    @Test
    fun `frame duration must be positive`() {
        assertInvalid(webp(frameDuration = 0), "duration")
    }

    @Test
    fun `frame payload must contain one bounded image chunk`() {
        assertInvalid(
            riff(
                vp8x(),
                anim(),
                anmf(includeImageData = false),
                anmf(),
            ),
            "no image data",
        )
        assertInvalid(
            riff(
                vp8x(),
                anim(),
                anmf(extraImageChunk = true),
                anmf(),
            ),
            "Duplicate ANMF image",
        )
    }

    @Test
    fun `nested image padding is counted inside the ANMF payload`() {
        assertInstanceOf(
            AnimatedWebpValidation.Valid::class.java,
            validate(
                riff(
                    vp8x(),
                    anim(),
                    anmf(imagePayload = byteArrayOf(1)),
                    anmf(imagePayload = byteArrayOf(2)),
                ),
            ),
        )
        assertInvalid(
            riff(
                vp8x(),
                anim(),
                anmf(imagePayload = byteArrayOf(1), includeNestedPadding = false),
                anmf(),
            ),
            "frame bounds",
        )
    }

    @Test
    fun `frames must fit the declared canvas`() {
        val bytes = riff(
            vp8x(width = 32, height = 32),
            anim(),
            anmf(x = 20, width = 20, height = 20),
            anmf(),
        )

        assertInvalid(bytes, "canvas")
    }

    @Test
    fun `oversized output is rejected before parsing`() {
        val file = directory.resolve("oversized.webp")
        file.writeBytes(webp())

        assertInstanceOf(
            AnimatedWebpValidation.Invalid::class.java,
            AnimatedWebpValidator.validate(file, maxBytes = file.length() - 1),
        )
    }

    private fun validate(bytes: ByteArray): AnimatedWebpValidation {
        val file = directory.resolve("scene-${System.nanoTime()}.webp")
        file.writeBytes(bytes)
        return AnimatedWebpValidator.validate(file)
    }

    private fun assertInvalid(bytes: ByteArray, messagePart: String? = null) {
        val invalid = assertInstanceOf(AnimatedWebpValidation.Invalid::class.java, validate(bytes))
        if (messagePart != null) {
            assertTrue(invalid.reason.contains(messagePart, ignoreCase = true), invalid.reason)
        }
    }

    private fun webp(
        animationFlag: Boolean = true,
        loopCount: Int = 0,
        frameDuration: Int = 100,
    ): ByteArray {
        return riff(
            vp8x(animationFlag = animationFlag),
            anim(loopCount),
            anmf(duration = frameDuration),
            anmf(duration = frameDuration),
        )
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

    private fun vp8x(
        width: Int = 64,
        height: Int = 64,
        animationFlag: Boolean = true,
    ): ByteArray {
        val payload = ByteArray(10)
        payload[0] = if (animationFlag) 0x02 else 0
        payload.writeUInt24(4, width - 1)
        payload.writeUInt24(7, height - 1)
        return chunk("VP8X", payload)
    }

    private fun anim(loopCount: Int = 0): ByteArray {
        val payload = ByteArray(6)
        payload[4] = (loopCount and 0xff).toByte()
        payload[5] = ((loopCount ushr 8) and 0xff).toByte()
        return chunk("ANIM", payload)
    }

    private fun anmf(
        x: Int = 0,
        y: Int = 0,
        width: Int = 32,
        height: Int = 32,
        duration: Int = 100,
        includeImageData: Boolean = true,
        extraImageChunk: Boolean = false,
        imagePayload: ByteArray = byteArrayOf(1, 2),
        includeNestedPadding: Boolean = true,
    ): ByteArray {
        val header = ByteArray(16)
        header.writeUInt24(0, x / 2)
        header.writeUInt24(3, y / 2)
        header.writeUInt24(6, width - 1)
        header.writeUInt24(9, height - 1)
        header.writeUInt24(12, duration)
        val payload = ByteArrayOutputStream().apply {
            write(header)
            if (includeImageData) {
                write(chunk("VP8 ", imagePayload, includePadding = includeNestedPadding))
            }
            if (extraImageChunk) {
                write(chunk("VP8L", byteArrayOf(3, 4)))
            }
        }.toByteArray()
        return chunk("ANMF", payload)
    }

    private fun chunk(
        name: String,
        payload: ByteArray,
        includePadding: Boolean = true,
    ): ByteArray {
        return ByteArrayOutputStream().apply {
            write(name.toByteArray())
            writeUInt32(payload.size.toLong())
            write(payload)
            if (includePadding && payload.size % 2 != 0) write(0)
        }.toByteArray()
    }

    private fun declaredChunk(name: String, payloadSize: Long): ByteArray {
        return ByteArrayOutputStream().apply {
            write(name.toByteArray())
            writeUInt32(payloadSize)
        }.toByteArray()
    }

    private fun ByteArray.writeUInt24(offset: Int, value: Int) {
        this[offset] = (value and 0xff).toByte()
        this[offset + 1] = ((value ushr 8) and 0xff).toByte()
        this[offset + 2] = ((value ushr 16) and 0xff).toByte()
    }

    private fun ByteArrayOutputStream.writeUInt32(value: Long) {
        write((value and 0xff).toInt())
        write(((value ushr 8) and 0xff).toInt())
        write(((value ushr 16) and 0xff).toInt())
        write(((value ushr 24) and 0xff).toInt())
    }
}
