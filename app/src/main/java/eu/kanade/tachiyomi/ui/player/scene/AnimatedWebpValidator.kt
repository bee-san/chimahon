package eu.kanade.tachiyomi.ui.player.scene

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream

internal const val SCENE_MAX_OUTPUT_BYTES = 10L * 1024L * 1024L
internal const val SCENE_MIN_FRAME_COUNT = 2
internal const val SCENE_MAX_FRAME_COUNT = 80

internal data class AnimatedWebpInfo(
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val totalDurationMillis: Long,
    val loopCount: Int,
)

internal sealed interface AnimatedWebpValidation {
    data class Valid(val info: AnimatedWebpInfo) : AnimatedWebpValidation
    data class Invalid(val reason: String) : AnimatedWebpValidation
}

internal object AnimatedWebpValidator {
    fun validate(
        file: File,
        maxBytes: Long = SCENE_MAX_OUTPUT_BYTES,
    ): AnimatedWebpValidation {
        if (!file.isFile) return AnimatedWebpValidation.Invalid("Output file is missing")
        val fileLength = file.length()
        if (fileLength > maxBytes) return AnimatedWebpValidation.Invalid("Output exceeds size limit")
        if (fileLength < RIFF_HEADER_SIZE) return AnimatedWebpValidation.Invalid("Truncated RIFF header")

        return runCatching {
            BufferedInputStream(FileInputStream(file)).use { input ->
                parse(input, fileLength)
            }
        }.getOrElse {
            AnimatedWebpValidation.Invalid(it.message ?: "Invalid animated WebP")
        }
    }

    private fun parse(input: BufferedInputStream, fileLength: Long): AnimatedWebpValidation {
        var position = 0L
        fun read(count: Int): ByteArray {
            val bytes = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = input.read(bytes, offset, count - offset)
                if (read < 0) throw EOFException("Truncated WebP data")
                offset += read
            }
            position += count
            return bytes
        }

        fun skip(count: Long) {
            var remaining = count
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                    position += skipped
                } else {
                    if (input.read() < 0) throw EOFException("Truncated WebP chunk")
                    remaining--
                    position++
                }
            }
        }

        val riffHeader = read(RIFF_HEADER_SIZE)
        if (riffHeader.asAscii(0, 4) != "RIFF") {
            return AnimatedWebpValidation.Invalid("Missing RIFF signature")
        }
        if (riffHeader.asAscii(8, 4) != "WEBP") {
            return AnimatedWebpValidation.Invalid("Missing WEBP signature")
        }
        val declaredRiffSize = riffHeader.readUInt32Le(4)
        if (declaredRiffSize != fileLength - 8L) {
            return AnimatedWebpValidation.Invalid("RIFF size does not match file length")
        }

        var canvasWidth: Int? = null
        var canvasHeight: Int? = null
        var animationHeaderCount = 0
        var loopCount: Int? = null
        val frames = mutableListOf<FrameBounds>()
        var expectedTopLevelChunk = ExpectedTopLevelChunk.VP8X

        while (position < fileLength) {
            if (fileLength - position < CHUNK_HEADER_SIZE) {
                return AnimatedWebpValidation.Invalid("Truncated chunk header")
            }
            val chunkHeader = read(CHUNK_HEADER_SIZE)
            val type = chunkHeader.asAscii(0, 4)
            val payloadSize = chunkHeader.readUInt32Le(4)
            val paddedPayloadSize = payloadSize + (payloadSize and 1L)
            if (type == "ANMF" && frames.size >= SCENE_MAX_FRAME_COUNT) {
                return AnimatedWebpValidation.Invalid("Animation frame count is out of bounds")
            }
            if (payloadSize > Int.MAX_VALUE || position > fileLength - paddedPayloadSize) {
                return AnimatedWebpValidation.Invalid("Chunk exceeds RIFF bounds")
            }

            when (type) {
                "VP8X" -> {
                    if (canvasWidth != null) {
                        return AnimatedWebpValidation.Invalid("Duplicate VP8X chunk")
                    }
                    if (expectedTopLevelChunk != ExpectedTopLevelChunk.VP8X) {
                        return AnimatedWebpValidation.Invalid("VP8X chunk is out of structural order")
                    }
                    if (payloadSize != VP8X_PAYLOAD_SIZE.toLong()) {
                        return AnimatedWebpValidation.Invalid("Invalid VP8X chunk size")
                    }
                    val payload = read(VP8X_PAYLOAD_SIZE)
                    if (payload[0].toInt() and VP8X_ANIMATION_FLAG == 0) {
                        return AnimatedWebpValidation.Invalid("VP8X animation flag is missing")
                    }
                    canvasWidth = payload.readUInt24Le(4) + 1
                    canvasHeight = payload.readUInt24Le(7) + 1
                    if (canvasWidth <= 0 || canvasHeight <= 0) {
                        return AnimatedWebpValidation.Invalid("Invalid WebP canvas")
                    }
                    expectedTopLevelChunk = ExpectedTopLevelChunk.ANIM
                }
                "ANIM" -> {
                    animationHeaderCount++
                    if (animationHeaderCount > 1) {
                        return AnimatedWebpValidation.Invalid("Duplicate ANIM chunk")
                    }
                    if (expectedTopLevelChunk != ExpectedTopLevelChunk.ANIM) {
                        return AnimatedWebpValidation.Invalid("ANIM chunk is out of structural order")
                    }
                    if (payloadSize != ANIM_PAYLOAD_SIZE.toLong()) {
                        return AnimatedWebpValidation.Invalid("Invalid ANIM chunk size")
                    }
                    val payload = read(ANIM_PAYLOAD_SIZE)
                    loopCount = payload.readUInt16Le(4)
                    if (loopCount != 0) {
                        return AnimatedWebpValidation.Invalid("Animation is not infinitely looping")
                    }
                    expectedTopLevelChunk = ExpectedTopLevelChunk.ANMF
                }
                "ANMF" -> {
                    if (expectedTopLevelChunk != ExpectedTopLevelChunk.ANMF) {
                        return AnimatedWebpValidation.Invalid("ANMF chunk is out of structural order")
                    }
                    if (payloadSize < ANMF_HEADER_SIZE) {
                        return AnimatedWebpValidation.Invalid("Truncated ANMF chunk")
                    }
                    val payload = read(ANMF_HEADER_SIZE.toInt())
                    val x = payload.readUInt24Le(0).toLong() * 2L
                    val y = payload.readUInt24Le(3).toLong() * 2L
                    val width = payload.readUInt24Le(6).toLong() + 1L
                    val height = payload.readUInt24Le(9).toLong() + 1L
                    val durationMillis = payload.readUInt24Le(12)
                    if (width <= 0L || height <= 0L || durationMillis <= 0) {
                        return AnimatedWebpValidation.Invalid("Invalid ANMF geometry or duration")
                    }
                    var nestedBytesRemaining = payloadSize - ANMF_HEADER_SIZE
                    var alphaChunkCount = 0
                    var imageChunkCount = 0
                    while (nestedBytesRemaining > 0L) {
                        if (nestedBytesRemaining < CHUNK_HEADER_SIZE) {
                            return AnimatedWebpValidation.Invalid("Truncated ANMF image chunk")
                        }
                        val nestedHeader = read(CHUNK_HEADER_SIZE)
                        nestedBytesRemaining -= CHUNK_HEADER_SIZE
                        val nestedType = nestedHeader.asAscii(0, 4)
                        val nestedPayloadSize = nestedHeader.readUInt32Le(4)
                        val nestedPaddedSize = nestedPayloadSize + (nestedPayloadSize and 1L)
                        if (nestedPayloadSize <= 0L || nestedPaddedSize > nestedBytesRemaining) {
                            return AnimatedWebpValidation.Invalid("ANMF image chunk exceeds frame bounds")
                        }
                        when (nestedType) {
                            "ALPH" -> {
                                alphaChunkCount++
                                if (alphaChunkCount > 1 || imageChunkCount > 0) {
                                    return AnimatedWebpValidation.Invalid("Invalid ANMF alpha chunk")
                                }
                            }
                            "VP8 ", "VP8L" -> {
                                imageChunkCount++
                                if (imageChunkCount > 1) {
                                    return AnimatedWebpValidation.Invalid("Duplicate ANMF image data")
                                }
                            }
                            else -> return AnimatedWebpValidation.Invalid("Unsupported ANMF image chunk")
                        }
                        skip(nestedPaddedSize)
                        nestedBytesRemaining -= nestedPaddedSize
                    }
                    if (imageChunkCount != 1) {
                        return AnimatedWebpValidation.Invalid("ANMF frame has no image data")
                    }
                    frames += FrameBounds(x, y, width, height, durationMillis)
                }
                else -> {
                    return AnimatedWebpValidation.Invalid(
                        "Unsupported top-level chunk breaks animated WebP structural order",
                    )
                }
            }

            if (payloadSize and 1L != 0L) {
                read(1)
            }
        }

        val width = canvasWidth ?: return AnimatedWebpValidation.Invalid("Missing VP8X chunk")
        val height = canvasHeight ?: return AnimatedWebpValidation.Invalid("Missing VP8X chunk")
        if (animationHeaderCount != 1 || loopCount == null) {
            return AnimatedWebpValidation.Invalid("Missing ANIM chunk")
        }
        if (frames.size !in SCENE_MIN_FRAME_COUNT..SCENE_MAX_FRAME_COUNT) {
            return AnimatedWebpValidation.Invalid("Animation frame count is out of bounds")
        }
        val canvasWidthLong = width.toLong()
        val canvasHeightLong = height.toLong()
        if (frames.any { frame ->
                frame.x > canvasWidthLong - frame.width ||
                    frame.y > canvasHeightLong - frame.height
            }
        ) {
            return AnimatedWebpValidation.Invalid("Animation frame exceeds canvas")
        }

        return AnimatedWebpValidation.Valid(
            AnimatedWebpInfo(
                width = width,
                height = height,
                frameCount = frames.size,
                totalDurationMillis = frames.sumOf { it.durationMillis.toLong() },
                loopCount = loopCount,
            ),
        )
    }

    private data class FrameBounds(
        val x: Long,
        val y: Long,
        val width: Long,
        val height: Long,
        val durationMillis: Int,
    )

    private enum class ExpectedTopLevelChunk {
        VP8X,
        ANIM,
        ANMF,
    }

    private fun ByteArray.asAscii(offset: Int, length: Int): String {
        return String(this, offset, length, Charsets.US_ASCII)
    }

    private fun ByteArray.readUInt16Le(offset: Int): Int {
        return (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun ByteArray.readUInt24Le(offset: Int): Int {
        return (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16)
    }

    private fun ByteArray.readUInt32Le(offset: Int): Long {
        return (this[offset].toLong() and 0xffL) or
            ((this[offset + 1].toLong() and 0xffL) shl 8) or
            ((this[offset + 2].toLong() and 0xffL) shl 16) or
            ((this[offset + 3].toLong() and 0xffL) shl 24)
    }

    private const val RIFF_HEADER_SIZE = 12
    private const val CHUNK_HEADER_SIZE = 8
    private const val VP8X_PAYLOAD_SIZE = 10
    private const val ANIM_PAYLOAD_SIZE = 6
    private const val ANMF_HEADER_SIZE = 16L
    private const val VP8X_ANIMATION_FLAG = 0x02
}
