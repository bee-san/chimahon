package eu.kanade.tachiyomi.ui.player.scene

import kotlinx.coroutines.CancellationException

/**
 * A known-length byte source that supports position-independent reads.
 *
 * Implementations may return fewer bytes than requested. Returning zero or a negative value before
 * [length] is reached is treated as a truncated source.
 */
internal interface IsoBmffRandomAccessByteSource : AutoCloseable {
    val length: Long

    suspend fun readAt(
        offset: Long,
        destination: ByteArray,
        destinationOffset: Int,
        byteCount: Int,
    ): Int

    override fun close() = Unit
}

internal data class IsoBmffInspectionLimits(
    val maxDepth: Int = 16,
    val maxBoxCount: Int = 16_384,
    val maxBytesRead: Long = 256L * 1024L,
) {
    init {
        require(maxDepth >= 0)
        require(maxBoxCount > 0)
        require(maxBytesRead > 0)
    }
}

internal enum class IsoBmffProtectionBox(
    val fourCc: String,
) {
    PSSH("pssh"),
    SINF("sinf"),
    SCHM("schm"),
    ENCRYPTED_VIDEO_SAMPLE_ENTRY("encv"),
    ENCRYPTED_AUDIO_SAMPLE_ENTRY("enca"),
}

internal enum class IsoBmffIndeterminateReason {
    MALFORMED,
    TRUNCATED,
    INTEGER_OVERFLOW,
    DEPTH_LIMIT,
    BOX_COUNT_LIMIT,
    READ_LIMIT,
    SOURCE_FAILURE,
}

internal sealed interface IsoBmffProtectionInspection {
    data object Clear : IsoBmffProtectionInspection

    data class Protected(
        val evidence: IsoBmffProtectionBox,
    ) : IsoBmffProtectionInspection

    data class Indeterminate(
        val reason: IsoBmffIndeterminateReason,
    ) : IsoBmffProtectionInspection
}

/**
 * Structurally inspects ISO-BMFF boxes without reading media payloads.
 *
 * Only box sequences defined by ISO-BMFF as containers are traversed. In particular, bytes inside
 * `mdat` and unknown box payloads are never searched for marker text.
 */
internal class IsoBmffProtectionInspector(
    private val limits: IsoBmffInspectionLimits = IsoBmffInspectionLimits(),
) {
    suspend fun inspect(source: IsoBmffRandomAccessByteSource): IsoBmffProtectionInspection {
        if (source.length < 0L) {
            return IsoBmffProtectionInspection.Indeterminate(
                IsoBmffIndeterminateReason.MALFORMED,
            )
        }
        return try {
            Parser(source, source.length, limits).inspect()
            IsoBmffProtectionInspection.Clear
        } catch (found: ProtectedBoxFound) {
            IsoBmffProtectionInspection.Protected(found.evidence)
        } catch (stopped: InspectionStopped) {
            IsoBmffProtectionInspection.Indeterminate(stopped.reason)
        }
    }

    private class Parser(
        private val source: IsoBmffRandomAccessByteSource,
        private val sourceLength: Long,
        private val limits: IsoBmffInspectionLimits,
    ) {
        private var boxCount = 0
        private var bytesRead = 0L

        suspend fun inspect() {
            if (sourceLength < BASIC_HEADER_SIZE) {
                stop(IsoBmffIndeterminateReason.TRUNCATED)
            }
            val rootBoxCount = inspectBoxSequence(
                start = 0L,
                end = sourceLength,
                depth = 0,
            )
            if (rootBoxCount == 0L) {
                stop(IsoBmffIndeterminateReason.MALFORMED)
            }
        }

        private suspend fun inspectBoxSequence(
            start: Long,
            end: Long,
            depth: Int,
            expectedBoxCount: Long? = null,
        ): Long {
            if (depth > limits.maxDepth) {
                stop(IsoBmffIndeterminateReason.DEPTH_LIMIT)
            }
            if (start < 0L || end < start || end > sourceLength) {
                stop(IsoBmffIndeterminateReason.MALFORMED)
            }
            expectedBoxCount?.let { expected ->
                if (expected < 0L || expected > (end - start) / BASIC_HEADER_SIZE) {
                    stop(IsoBmffIndeterminateReason.MALFORMED)
                }
                if (expected > (limits.maxBoxCount - boxCount).toLong()) {
                    stop(IsoBmffIndeterminateReason.BOX_COUNT_LIMIT)
                }
            }

            var cursor = start
            var directBoxCount = 0L
            while (cursor < end) {
                if (expectedBoxCount != null && directBoxCount >= expectedBoxCount) {
                    stop(IsoBmffIndeterminateReason.MALFORMED)
                }
                if (boxCount >= limits.maxBoxCount) {
                    stop(IsoBmffIndeterminateReason.BOX_COUNT_LIMIT)
                }
                boxCount++

                val header = readHeader(cursor, end)
                PROTECTION_BOXES_BY_TYPE[header.type]?.let { evidence ->
                    throw ProtectedBoxFound(evidence)
                }
                inspectKnownContainer(header, depth)

                cursor = header.end
                directBoxCount++
            }
            if (expectedBoxCount != null && directBoxCount != expectedBoxCount) {
                stop(IsoBmffIndeterminateReason.MALFORMED)
            }
            return directBoxCount
        }

        private suspend fun inspectKnownContainer(
            header: BoxHeader,
            depth: Int,
        ) {
            when (header.type) {
                in SIMPLE_CONTAINER_TYPES -> {
                    inspectChildren(
                        start = header.payloadStart,
                        end = header.end,
                        depth = depth,
                    )
                }
                META_BOX_TYPE -> {
                    val prefix = readContainerPrefix(header, META_PREFIX_SIZE)
                    inspectChildren(prefix.end, header.end, depth)
                }
                SAMPLE_DESCRIPTION_BOX_TYPE -> {
                    val prefix = readContainerPrefix(header, SAMPLE_DESCRIPTION_PREFIX_SIZE)
                    val expectedEntries = readUnsignedInt(prefix.bytes, FULL_BOX_HEADER_SIZE)
                    inspectChildren(
                        start = prefix.end,
                        end = header.end,
                        depth = depth,
                        expectedBoxCount = expectedEntries,
                    )
                }
                ITEM_PROTECTION_BOX_TYPE -> {
                    val prefix = readContainerPrefix(header, ITEM_PROTECTION_PREFIX_SIZE)
                    val expectedEntries = readUnsignedShort(prefix.bytes, FULL_BOX_HEADER_SIZE)
                    inspectChildren(
                        start = prefix.end,
                        end = header.end,
                        depth = depth,
                        expectedBoxCount = expectedEntries,
                    )
                }
            }
        }

        private suspend fun inspectChildren(
            start: Long,
            end: Long,
            depth: Int,
            expectedBoxCount: Long? = null,
        ) {
            if (start == end && expectedBoxCount == null) return
            inspectBoxSequence(
                start = start,
                end = end,
                depth = depth + 1,
                expectedBoxCount = expectedBoxCount,
            )
        }

        private suspend fun readContainerPrefix(
            header: BoxHeader,
            byteCount: Int,
        ): Prefix {
            if (header.end - header.payloadStart < byteCount) {
                stop(IsoBmffIndeterminateReason.MALFORMED)
            }
            return Prefix(
                bytes = readExact(header.payloadStart, byteCount),
                end = header.payloadStart + byteCount,
            )
        }

        private suspend fun readHeader(
            start: Long,
            parentEnd: Long,
        ): BoxHeader {
            val remaining = parentEnd - start
            if (remaining < BASIC_HEADER_SIZE) {
                stop(IsoBmffIndeterminateReason.TRUNCATED)
            }

            val basicHeader = readExact(start, BASIC_HEADER_SIZE.toInt())
            val compactSize = readUnsignedInt(basicHeader, 0)
            val type = basicHeader.copyOfRange(4, 8).toString(Charsets.ISO_8859_1)

            var headerSize = BASIC_HEADER_SIZE
            val declaredSize = when (compactSize) {
                EXTENDED_SIZE_SENTINEL -> {
                    val extendedSizeBytes = readExact(
                        start + BASIC_HEADER_SIZE,
                        EXTENDED_SIZE_LENGTH,
                    )
                    headerSize += EXTENDED_SIZE_LENGTH
                    readUnsignedLong(extendedSizeBytes)
                }
                TO_PARENT_END_SIZE -> remaining
                else -> compactSize
            }
            if (type == UUID_BOX_TYPE) {
                headerSize += UUID_USER_TYPE_LENGTH
            }
            if (declaredSize < headerSize) {
                stop(IsoBmffIndeterminateReason.MALFORMED)
            }
            if (declaredSize > remaining) {
                stop(IsoBmffIndeterminateReason.TRUNCATED)
            }

            val end = start + declaredSize
            if (end < start || end > parentEnd) {
                stop(IsoBmffIndeterminateReason.INTEGER_OVERFLOW)
            }
            return BoxHeader(
                type = type,
                payloadStart = start + headerSize,
                end = end,
            )
        }

        private suspend fun readExact(
            offset: Long,
            byteCount: Int,
        ): ByteArray {
            if (
                offset < 0L ||
                byteCount < 0 ||
                offset > sourceLength ||
                byteCount.toLong() > sourceLength - offset
            ) {
                stop(IsoBmffIndeterminateReason.TRUNCATED)
            }
            if (byteCount.toLong() > limits.maxBytesRead - bytesRead) {
                stop(IsoBmffIndeterminateReason.READ_LIMIT)
            }

            val result = ByteArray(byteCount)
            var copied = 0
            while (copied < byteCount) {
                val count = try {
                    source.readAt(
                        offset = offset + copied,
                        destination = result,
                        destinationOffset = copied,
                        byteCount = byteCount - copied,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    stop(IsoBmffIndeterminateReason.SOURCE_FAILURE)
                }
                if (count <= 0) {
                    stop(IsoBmffIndeterminateReason.TRUNCATED)
                }
                if (count > byteCount - copied) {
                    stop(IsoBmffIndeterminateReason.SOURCE_FAILURE)
                }
                copied += count
                bytesRead += count
            }
            return result
        }
    }

    private data class BoxHeader(
        val type: String,
        val payloadStart: Long,
        val end: Long,
    )

    private data class Prefix(
        val bytes: ByteArray,
        val end: Long,
    )

    private class ProtectedBoxFound(
        val evidence: IsoBmffProtectionBox,
    ) : Exception()

    private class InspectionStopped(
        val reason: IsoBmffIndeterminateReason,
    ) : Exception()

    private companion object {
        const val BASIC_HEADER_SIZE = 8L
        const val EXTENDED_SIZE_LENGTH = 8
        const val UUID_USER_TYPE_LENGTH = 16
        const val FULL_BOX_HEADER_SIZE = 4
        const val META_PREFIX_SIZE = FULL_BOX_HEADER_SIZE
        const val SAMPLE_DESCRIPTION_PREFIX_SIZE = FULL_BOX_HEADER_SIZE + 4
        const val ITEM_PROTECTION_PREFIX_SIZE = FULL_BOX_HEADER_SIZE + 2
        const val EXTENDED_SIZE_SENTINEL = 1L
        const val TO_PARENT_END_SIZE = 0L

        const val META_BOX_TYPE = "meta"
        const val SAMPLE_DESCRIPTION_BOX_TYPE = "stsd"
        const val ITEM_PROTECTION_BOX_TYPE = "ipro"
        const val UUID_BOX_TYPE = "uuid"

        val SIMPLE_CONTAINER_TYPES = setOf(
            "moov",
            "trak",
            "mdia",
            "minf",
            "dinf",
            "stbl",
            "edts",
            "udta",
            "mvex",
            "moof",
            "traf",
            "mfra",
            "schi",
            "iprp",
            "ipco",
        )

        val PROTECTION_BOXES_BY_TYPE = IsoBmffProtectionBox.entries.associateBy { it.fourCc }

        fun readUnsignedInt(
            bytes: ByteArray,
            offset: Int,
        ): Long {
            var value = 0L
            repeat(4) { index ->
                value = (value shl 8) or (bytes[offset + index].toLong() and 0xffL)
            }
            return value
        }

        fun readUnsignedShort(
            bytes: ByteArray,
            offset: Int,
        ): Long {
            return (
                ((bytes[offset].toLong() and 0xffL) shl 8) or
                    (bytes[offset + 1].toLong() and 0xffL)
                )
        }

        fun readUnsignedLong(bytes: ByteArray): Long {
            if ((bytes.first().toInt() and 0x80) != 0) {
                stop(IsoBmffIndeterminateReason.INTEGER_OVERFLOW)
            }
            var value = 0L
            bytes.forEach { byte ->
                value = (value shl 8) or (byte.toLong() and 0xffL)
            }
            return value
        }

        fun stop(reason: IsoBmffIndeterminateReason): Nothing {
            throw InspectionStopped(reason)
        }
    }
}
