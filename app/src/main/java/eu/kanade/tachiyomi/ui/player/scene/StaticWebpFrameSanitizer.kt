package eu.kanade.tachiyomi.ui.player.scene

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Reduces an Android-encoded static WebP to chunks that are valid inside an ANMF frame.
 *
 * FFmpeg 6 removes a leading VP8X chunk while muxing copied WebP frames, but it leaves metadata
 * chunks behind. Those chunks are legal at the top level of a static WebP and illegal inside ANMF.
 */
internal object StaticWebpFrameSanitizer {
    fun sanitize(
        source: File,
        destination: File,
        maxBytes: Long = SCENE_MAX_OUTPUT_BYTES,
    ): Boolean {
        destination.delete()
        if (!source.isFile || source.length() !in RIFF_HEADER_SIZE.toLong()..maxBytes) {
            return false
        }

        val sanitized = runCatching {
            sanitize(source.readBytes(), maxBytes)
        }.getOrNull() ?: return false

        return runCatching {
            destination.outputStream().buffered().use { it.write(sanitized) }
            destination.isFile && destination.length() == sanitized.size.toLong()
        }.getOrDefault(false).also { written ->
            if (!written) destination.delete()
        }
    }

    private fun sanitize(bytes: ByteArray, maxBytes: Long): ByteArray? {
        if (bytes.size < RIFF_HEADER_SIZE || bytes.size.toLong() > maxBytes) return null
        if (bytes.asAscii(0, 4) != "RIFF" || bytes.asAscii(8, 4) != "WEBP") return null
        if (bytes.readUInt32Le(4) != bytes.size.toLong() - RIFF_PREFIX_SIZE) return null

        var position = RIFF_HEADER_SIZE
        var extendedHeaderSeen = false
        var alphaChunk: ByteArray? = null
        var imageChunk: ByteArray? = null
        var imageType: String? = null

        while (position < bytes.size) {
            if (bytes.size - position < CHUNK_HEADER_SIZE) return null
            val chunkStart = position
            val type = bytes.asAscii(position, 4)
            val payloadSize = bytes.readUInt32Le(position + 4)
            val paddedPayloadSize = payloadSize + (payloadSize and 1L)
            val chunkSize = CHUNK_HEADER_SIZE.toLong() + paddedPayloadSize
            if (payloadSize <= 0L || chunkSize > bytes.size.toLong() - position) return null
            position += chunkSize.toInt()

            when (type) {
                "VP8X" -> {
                    if (extendedHeaderSeen || chunkStart != RIFF_HEADER_SIZE || payloadSize != VP8X_PAYLOAD_SIZE) {
                        return null
                    }
                    extendedHeaderSeen = true
                }
                "ICCP", "EXIF", "XMP " -> Unit
                "ALPH" -> {
                    if (alphaChunk != null || imageChunk != null) return null
                    alphaChunk = bytes.copyOfRange(chunkStart, position)
                }
                "VP8 ", "VP8L" -> {
                    if (imageChunk != null || (type == "VP8L" && alphaChunk != null)) return null
                    imageType = type
                    imageChunk = bytes.copyOfRange(chunkStart, position)
                }
                else -> return null
            }
        }

        val image = imageChunk ?: return null
        if (imageType != "VP8 " && alphaChunk != null) return null
        val imagePayload = ByteArrayOutputStream().apply {
            alphaChunk?.let(::write)
            write(image)
        }.toByteArray()
        val riffSize = WEBP_SIGNATURE_SIZE + imagePayload.size
        if (riffSize.toLong() + RIFF_PREFIX_SIZE > maxBytes || riffSize > UInt.MAX_VALUE.toLong()) {
            return null
        }

        return ByteArrayOutputStream(RIFF_PREFIX_SIZE + riffSize).apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            writeUInt32Le(riffSize.toLong())
            write("WEBP".toByteArray(Charsets.US_ASCII))
            write(imagePayload)
        }.toByteArray()
    }

    private fun ByteArray.asAscii(offset: Int, length: Int): String {
        return String(this, offset, length, Charsets.US_ASCII)
    }

    private fun ByteArray.readUInt32Le(offset: Int): Long {
        return (this[offset].toLong() and 0xffL) or
            ((this[offset + 1].toLong() and 0xffL) shl 8) or
            ((this[offset + 2].toLong() and 0xffL) shl 16) or
            ((this[offset + 3].toLong() and 0xffL) shl 24)
    }

    private fun ByteArrayOutputStream.writeUInt32Le(value: Long) {
        write((value and 0xff).toInt())
        write(((value ushr 8) and 0xff).toInt())
        write(((value ushr 16) and 0xff).toInt())
        write(((value ushr 24) and 0xff).toInt())
    }

    private const val RIFF_PREFIX_SIZE = 8
    private const val RIFF_HEADER_SIZE = 12
    private const val WEBP_SIGNATURE_SIZE = 4
    private const val CHUNK_HEADER_SIZE = 8
    private const val VP8X_PAYLOAD_SIZE = 10L
}
