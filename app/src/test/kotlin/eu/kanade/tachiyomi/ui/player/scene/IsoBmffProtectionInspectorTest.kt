package eu.kanade.tachiyomi.ui.player.scene

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IsoBmffProtectionInspectorTest {
    @Test
    fun `late pssh after a large mdat is found without reading media bytes`() = runTest {
        val psshPayload = uint32(0) + ByteArray(16) { it.toByte() } + uint32(0)
        val source = ByteArraySource(
            box("ftyp", "isom".encodeToByteArray()) +
                box("mdat", ByteArray((1 shl 20) + 37) { 0x5a }) +
                extendedBox("pssh", psshPayload),
        )

        assertEquals(
            IsoBmffProtectionInspection.Protected(IsoBmffProtectionBox.PSSH),
            IsoBmffProtectionInspector().inspect(source),
        )
        assertTrue(source.bytesRead <= 32)
    }

    @Test
    fun `box markers in an ordinary payload do not produce false positives`() = runTest {
        val markerPayload = IsoBmffProtectionBox.entries
            .map { box(it.fourCc, byteArrayOf(1, 2, 3, 4)) }
            .reduce(ByteArray::plus)
        val source = ByteArraySource(
            box("ftyp", "isom".encodeToByteArray()) +
                box("mdat", markerPayload) +
                box("free"),
        )

        assertEquals(
            IsoBmffProtectionInspection.Clear,
            IsoBmffProtectionInspector().inspect(source),
        )
    }

    @Test
    fun `all structural protection box types are detected`() = runTest {
        val cases = listOf(
            IsoBmffProtectionBox.PSSH to box("pssh"),
            IsoBmffProtectionBox.SINF to box("moov", box("sinf")),
            IsoBmffProtectionBox.SCHM to box("moov", box("schi", box("schm"))),
            IsoBmffProtectionBox.ENCRYPTED_VIDEO_SAMPLE_ENTRY to sampleDescription("encv"),
            IsoBmffProtectionBox.ENCRYPTED_AUDIO_SAMPLE_ENTRY to sampleDescription("enca"),
        )

        cases.forEach { (evidence, bytes) ->
            assertEquals(
                IsoBmffProtectionInspection.Protected(evidence),
                IsoBmffProtectionInspector().inspect(ByteArraySource(bytes)),
            )
        }
    }

    @Test
    fun `clear nested MP4 and terminal size-zero mdat are accepted`() = runTest {
        val sampleDescription = sampleDescription("avc1", ByteArray(78))
        val moov = box(
            "moov",
            box(
                "trak",
                box(
                    "mdia",
                    box(
                        "minf",
                        box("stbl", sampleDescription),
                    ),
                ),
            ),
        )
        val source = ByteArraySource(
            box("ftyp", "isom".encodeToByteArray()) +
                moov +
                sizeZeroBox("mdat", byteArrayOf(1, 2, 3, 4)),
        )

        assertEquals(
            IsoBmffProtectionInspection.Clear,
            IsoBmffProtectionInspector().inspect(source),
        )
    }

    @Test
    fun `declared box beyond source length is truncated`() = runTest {
        val bytes = uint32(64) + fourCc("free")

        assertEquals(
            IsoBmffProtectionInspection.Indeterminate(
                IsoBmffIndeterminateReason.TRUNCATED,
            ),
            IsoBmffProtectionInspector().inspect(ByteArraySource(bytes)),
        )
    }

    @Test
    fun `unsigned extended size outside Long range is rejected as overflow`() = runTest {
        val overflowingSize = byteArrayOf(
            0x80.toByte(),
            0,
            0,
            0,
            0,
            0,
            0,
            0,
        )
        val bytes = uint32(1) + fourCc("free") + overflowingSize

        assertEquals(
            IsoBmffProtectionInspection.Indeterminate(
                IsoBmffIndeterminateReason.INTEGER_OVERFLOW,
            ),
            IsoBmffProtectionInspector().inspect(ByteArraySource(bytes)),
        )
    }

    @Test
    fun `box smaller than its header is malformed`() = runTest {
        val bytes = uint32(4) + fourCc("free")

        assertEquals(
            IsoBmffProtectionInspection.Indeterminate(
                IsoBmffIndeterminateReason.MALFORMED,
            ),
            IsoBmffProtectionInspector().inspect(ByteArraySource(bytes)),
        )
    }

    @Test
    fun `container recursion is depth bounded`() = runTest {
        val bytes = box(
            "moov",
            box(
                "trak",
                box(
                    "mdia",
                    box("minf", box("free")),
                ),
            ),
        )

        assertEquals(
            IsoBmffProtectionInspection.Indeterminate(
                IsoBmffIndeterminateReason.DEPTH_LIMIT,
            ),
            IsoBmffProtectionInspector(
                IsoBmffInspectionLimits(maxDepth = 2),
            ).inspect(ByteArraySource(bytes)),
        )
    }

    @Test
    fun `box traversal is count bounded`() = runTest {
        val bytes = box("free") + box("free") + box("free")

        assertEquals(
            IsoBmffProtectionInspection.Indeterminate(
                IsoBmffIndeterminateReason.BOX_COUNT_LIMIT,
            ),
            IsoBmffProtectionInspector(
                IsoBmffInspectionLimits(maxBoxCount = 2),
            ).inspect(ByteArraySource(bytes)),
        )
    }

    @Test
    fun `header reads are byte bounded`() = runTest {
        val bytes = box("free") + box("free")

        assertEquals(
            IsoBmffProtectionInspection.Indeterminate(
                IsoBmffIndeterminateReason.READ_LIMIT,
            ),
            IsoBmffProtectionInspector(
                IsoBmffInspectionLimits(maxBytesRead = 15),
            ).inspect(ByteArraySource(bytes)),
        )
    }

    private class ByteArraySource(
        private val bytes: ByteArray,
        private val maxChunkSize: Int = Int.MAX_VALUE,
    ) : IsoBmffRandomAccessByteSource {
        override val length = bytes.size.toLong()
        var bytesRead = 0
            private set

        override suspend fun readAt(
            offset: Long,
            destination: ByteArray,
            destinationOffset: Int,
            byteCount: Int,
        ): Int {
            if (offset >= bytes.size) return -1
            val count = minOf(
                byteCount,
                maxChunkSize,
                bytes.size - offset.toInt(),
            )
            bytes.copyInto(
                destination = destination,
                destinationOffset = destinationOffset,
                startIndex = offset.toInt(),
                endIndex = offset.toInt() + count,
            )
            bytesRead += count
            return count
        }
    }

    private companion object {
        fun sampleDescription(
            entryType: String,
            entryPayload: ByteArray = ByteArray(0),
        ): ByteArray {
            return box(
                type = "stsd",
                payload = uint32(0) + uint32(1) + box(entryType, entryPayload),
            )
        }

        fun box(
            type: String,
            payload: ByteArray = ByteArray(0),
        ): ByteArray {
            val size = 8L + payload.size
            return uint32(size) + fourCc(type) + payload
        }

        fun extendedBox(
            type: String,
            payload: ByteArray,
        ): ByteArray {
            val size = 16L + payload.size
            return uint32(1) + fourCc(type) + uint64(size) + payload
        }

        fun sizeZeroBox(
            type: String,
            payload: ByteArray,
        ): ByteArray {
            return uint32(0) + fourCc(type) + payload
        }

        fun fourCc(value: String): ByteArray {
            require(value.length == 4)
            return value.encodeToByteArray()
        }

        fun uint32(value: Long): ByteArray {
            require(value in 0..0xffff_ffffL)
            return ByteArray(4) { index ->
                (value ushr (24 - index * 8)).toByte()
            }
        }

        fun uint64(value: Long): ByteArray {
            require(value >= 0)
            return ByteArray(8) { index ->
                (value ushr (56 - index * 8)).toByte()
            }
        }
    }
}
