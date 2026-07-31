package eu.kanade.tachiyomi.ui.player.scene

import java.io.ByteArrayOutputStream

/**
 * Removes Android's AV1CodecConfigurationRecord and restores temporal-unit boundaries that raw
 * packet output loses. FFmpeg's MediaCodec wrapper incorrectly prepends the record to frame data.
 */
internal object MediaCodecAv1StreamNormalizer {
    fun normalize(input: ByteArray): ByteArray? {
        if (input.size < AV1C_HEADER_SIZE + 1) return null
        val start = if (isAv1CodecConfigurationRecord(input)) AV1C_HEADER_SIZE else 0
        val obus = parseObus(input, start) ?: return null
        if (obus.none { it.type == OBU_SEQUENCE_HEADER } ||
            obus.count { it.type == OBU_FRAME || it.type == OBU_FRAME_HEADER } < 2
        ) {
            return null
        }

        val output = ByteArrayOutputStream(input.size + obus.size * TEMPORAL_DELIMITER.size)
        var frameStarted = false
        if (obus.first().type != OBU_TEMPORAL_DELIMITER) {
            output.write(TEMPORAL_DELIMITER)
        }
        obus.forEach { obu ->
            when (obu.type) {
                OBU_TEMPORAL_DELIMITER -> {
                    if (!output.endsWithTemporalDelimiter()) {
                        output.write(TEMPORAL_DELIMITER)
                    }
                    frameStarted = false
                }
                OBU_FRAME,
                OBU_FRAME_HEADER,
                -> {
                    if (frameStarted) output.write(TEMPORAL_DELIMITER)
                    output.write(input, obu.offset, obu.length)
                    frameStarted = true
                }
                else -> output.write(input, obu.offset, obu.length)
            }
        }
        return output.toByteArray()
    }

    private fun isAv1CodecConfigurationRecord(input: ByteArray): Boolean {
        val first = input[0].toInt() and 0xff
        return first and 0x80 != 0 && first and 0x7f == 1
    }

    private fun parseObus(input: ByteArray, start: Int): List<Obu>? {
        val result = mutableListOf<Obu>()
        var offset = start
        while (offset < input.size) {
            val header = input[offset].toInt() and 0xff
            if (header and 0x80 != 0 || header and 0x01 != 0 || header and 0x02 == 0) return null
            val extensionBytes = if (header and 0x04 != 0) 1 else 0
            val sizeOffset = offset + 1 + extensionBytes
            if (sizeOffset >= input.size) return null
            val size = readLeb128(input, sizeOffset) ?: return null
            val payloadOffset = sizeOffset + size.bytes
            val end = payloadOffset.toLong() + size.value
            if (end > input.size || end > Int.MAX_VALUE) return null
            result += Obu(
                type = header shr 3 and 0x0f,
                offset = offset,
                length = end.toInt() - offset,
            )
            offset = end.toInt()
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun readLeb128(input: ByteArray, offset: Int): Leb128? {
        var value = 0L
        for (index in 0 until MAX_LEB128_BYTES) {
            val position = offset + index
            if (position >= input.size) return null
            val byte = input[position].toInt() and 0xff
            value = value or ((byte and 0x7f).toLong() shl (index * 7))
            if (byte and 0x80 == 0) return Leb128(value, index + 1)
        }
        return null
    }

    private fun ByteArrayOutputStream.endsWithTemporalDelimiter(): Boolean {
        val bytes = toByteArray()
        return bytes.size >= TEMPORAL_DELIMITER.size &&
            bytes[bytes.lastIndex - 1] == TEMPORAL_DELIMITER[0] &&
            bytes[bytes.lastIndex] == TEMPORAL_DELIMITER[1]
    }

    private data class Obu(val type: Int, val offset: Int, val length: Int)
    private data class Leb128(val value: Long, val bytes: Int)

    private val TEMPORAL_DELIMITER = byteArrayOf(0x12, 0x00)
    private const val AV1C_HEADER_SIZE = 4
    private const val MAX_LEB128_BYTES = 8
    private const val OBU_SEQUENCE_HEADER = 1
    private const val OBU_TEMPORAL_DELIMITER = 2
    private const val OBU_FRAME_HEADER = 3
    private const val OBU_FRAME = 6
}
