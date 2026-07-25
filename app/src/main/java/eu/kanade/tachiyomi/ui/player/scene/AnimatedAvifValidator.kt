package eu.kanade.tachiyomi.ui.player.scene

import java.io.File
import java.io.FileInputStream

internal data class AnimatedAvifInfo(
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val totalDurationMillis: Long,
)

internal object AnimatedAvifValidator {
    fun validate(file: File): AnimatedAvifInfo? {
        if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES) return null
        val bytes = ByteArray(file.length().toInt())
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read <= 0) return null
                offset += read
            }
            if (input.read() != -1) return null
        }
        return runCatching { Parser(bytes).parse() }.getOrNull()
    }

    private class Parser(
        private val bytes: ByteArray,
    ) {
        private var boxCount = 0

        fun parse(): AnimatedAvifInfo? {
            val top = boxes(0, bytes.size) ?: return null
            if (top.firstOrNull()?.type != "ftyp") return null
            val ftyp = top.only("ftyp") ?: return null
            val meta = top.only("meta") ?: return null
            val moov = top.only("moov") ?: return null
            val mdat = top.only("mdat") ?: return null
            if (!validFileType(ftyp) || meta.dataSize < 4 || u32(meta.start) != 0L || mdat.dataSize == 0) {
                return null
            }

            val track = parseMovie(moov) ?: return null
            if (track.frames !in FRAME_RANGE || track.frames != track.sizedFrames) return null
            if (track.duration != track.declaredDuration || track.duration > track.timescale * MAX_SECONDS) {
                return null
            }
            val doubledRate = track.frames.toLong() * track.timescale * 2
            if (doubledRate !in 15L * track.duration..17L * track.duration) return null
            if (track.sampleBytes > mdat.dataSize) return null

            val millis = (track.duration * 1_000L + track.timescale / 2) / track.timescale
            return AnimatedAvifInfo(track.width, track.height, track.frames, millis)
        }

        private fun validFileType(box: Box): Boolean {
            if (box.dataSize < 16 || (box.dataSize - 8) % 4 != 0 || fourCc(box.start) != "avis") {
                return false
            }
            val brands = (box.start + 8 until box.end step 4).map(::fourCc)
            return "avif" in brands && "MA1B" in brands
        }

        private fun parseMovie(movie: Box): Track? {
            val track = boxes(movie.start, movie.end)?.only("trak") ?: return null
            val media = boxes(track.start, track.end)?.only("mdia") ?: return null
            val mediaBoxes = boxes(media.start, media.end) ?: return null
            val (timescale, declaredDuration) = parseMediaHeader(mediaBoxes.only("mdhd") ?: return null) ?: return null
            val mediaInfo = mediaBoxes.only("minf") ?: return null
            val sampleTable = boxes(mediaInfo.start, mediaInfo.end)?.only("stbl") ?: return null
            val sampleBoxes = boxes(sampleTable.start, sampleTable.end) ?: return null
            val (width, height) = parseSampleDescription(sampleBoxes.only("stsd") ?: return null) ?: return null
            val timing = parseTiming(sampleBoxes.only("stts") ?: return null) ?: return null
            val sizes = parseSizes(sampleBoxes.only("stsz") ?: return null) ?: return null
            return Track(
                width = width,
                height = height,
                frames = timing.frames,
                duration = timing.duration,
                timescale = timescale,
                declaredDuration = declaredDuration,
                sizedFrames = sizes.frames,
                sampleBytes = sizes.bytes,
            )
        }

        private fun parseMediaHeader(box: Box): Pair<Long, Long>? {
            val version = u8(box.start)
            val timescaleOffset = when (version) {
                0 -> box.start + 12
                1 -> box.start + 20
                else -> return null
            }
            val durationOffset = timescaleOffset + 4
            val required = durationOffset + if (version == 0) 4 else 8
            if (required > box.end) return null
            val timescale = u32(timescaleOffset)
            val duration = if (version == 0) u32(durationOffset) else u64(durationOffset)
            return if (timescale > 0 && duration > 0) timescale to duration else null
        }

        private fun parseSampleDescription(box: Box): Pair<Int, Int>? {
            if (box.dataSize < 8 || u32(box.start) != 0L || u32(box.start + 4) != 1L) return null
            val entry = boxes(box.start + 8, box.end)?.singleOrNull() ?: return null
            if (entry.type != "av01" || entry.dataSize < VISUAL_SAMPLE_ENTRY_BYTES) return null
            val width = u16(entry.start + 24)
            val height = u16(entry.start + 26)
            if (width !in DIMENSION_RANGE || height !in DIMENSION_RANGE) return null
            val children = boxes(entry.start + VISUAL_SAMPLE_ENTRY_BYTES, entry.end) ?: return null
            val av1Config = children.only("av1C") ?: return null
            return if (av1Config.dataSize >= 4) width to height else null
        }

        private fun parseTiming(box: Box): Timing? {
            if (box.dataSize < 16 || u32(box.start) != 0L) return null
            val entries = u32(box.start + 4)
            if (entries !in 1..MAX_TIMING_ENTRIES ||
                box.dataSize.toLong() != 8L + entries * 8L
            ) {
                return null
            }
            var frames = 0L
            var duration = 0L
            var offset = box.start + 8
            repeat(entries.toInt()) {
                val count = u32(offset)
                val delta = u32(offset + 4)
                if (count == 0L || delta == 0L || frames + count > FRAME_RANGE.last) return null
                frames += count
                duration += count * delta
                offset += 8
            }
            return Timing(frames.toInt(), duration)
        }

        private fun parseSizes(box: Box): Sizes? {
            if (box.dataSize < 12 || u32(box.start) != 0L) return null
            val commonSize = u32(box.start + 4)
            val frames = u32(box.start + 8)
            if (frames !in FRAME_RANGE.first.toLong()..FRAME_RANGE.last.toLong()) return null
            if (commonSize != 0L) {
                return if (box.dataSize == 12) Sizes(frames.toInt(), commonSize * frames) else null
            }
            if (box.dataSize.toLong() != 12L + frames * 4L) return null
            var total = 0L
            var offset = box.start + 12
            repeat(frames.toInt()) {
                val size = u32(offset)
                if (size == 0L) return null
                total += size
                offset += 4
            }
            return Sizes(frames.toInt(), total)
        }

        private fun boxes(start: Int, end: Int): List<Box>? {
            if (start !in 0..end || end > bytes.size) return null
            val result = mutableListOf<Box>()
            var offset = start
            while (offset < end) {
                if (end - offset < BOX_HEADER_BYTES || ++boxCount > MAX_BOXES) return null
                val size = u32(offset)
                if (size < BOX_HEADER_BYTES || size > Int.MAX_VALUE) return null
                val boxEnd = offset.toLong() + size
                if (boxEnd > end) return null
                result += Box(fourCc(offset + 4), offset + BOX_HEADER_BYTES, boxEnd.toInt())
                offset = boxEnd.toInt()
            }
            return result.takeIf { offset == end }
        }

        private fun List<Box>.only(type: String) = filter { it.type == type }.singleOrNull()

        private fun u8(offset: Int) = bytes[offset].toInt() and 0xff
        private fun u16(offset: Int) = (u8(offset) shl 8) or u8(offset + 1)
        private fun u32(offset: Int) =
            (u8(offset).toLong() shl 24) or
                (u8(offset + 1).toLong() shl 16) or
                (u8(offset + 2).toLong() shl 8) or
                u8(offset + 3).toLong()

        private fun u64(offset: Int): Long {
            if (u8(offset) and 0x80 != 0) return -1
            var value = 0L
            repeat(8) { value = (value shl 8) or u8(offset + it).toLong() }
            return value
        }

        private fun fourCc(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)

        private data class Box(val type: String, val start: Int, val end: Int) {
            val dataSize get() = end - start
        }

        private data class Timing(val frames: Int, val duration: Long)
        private data class Sizes(val frames: Int, val bytes: Long)
        private data class Track(
            val width: Int,
            val height: Int,
            val frames: Int,
            val duration: Long,
            val timescale: Long,
            val declaredDuration: Long,
            val sizedFrames: Int,
            val sampleBytes: Long,
        )
    }

    private val DIMENSION_RANGE = 1..640
    private val FRAME_RANGE = 2..80
    private const val MAX_FILE_BYTES = 10L * 1024L * 1024L
    private const val MAX_SECONDS = 10L
    private const val MAX_TIMING_ENTRIES = 80L
    private const val MAX_BOXES = 64
    private const val BOX_HEADER_BYTES = 8
    private const val VISUAL_SAMPLE_ENTRY_BYTES = 78
}
