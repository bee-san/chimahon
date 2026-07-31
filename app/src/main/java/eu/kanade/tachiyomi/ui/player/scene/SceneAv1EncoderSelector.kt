package eu.kanade.tachiyomi.ui.player.scene

import kotlin.math.abs
import kotlin.math.min

internal data class SceneVideoDimensions(
    val width: Int,
    val height: Int,
)

internal data class Av1EncoderCandidate(
    val name: String,
    val supportsPlanarYuv420: Boolean,
    val supportsConstantQuality: Boolean,
    val supportsTargetQuality: Boolean,
    val widthAlignment: Int,
    val heightAlignment: Int,
    val minimumWidth: Int = 1,
    val minimumHeight: Int = 1,
    val maximumWidth: Int = Int.MAX_VALUE,
    val maximumHeight: Int = Int.MAX_VALUE,
    val supportedWidthsForHeight: (Int) -> IntRange? = {
        minimumWidth..maximumWidth
    },
    val supportsSizeAndRate: (SceneVideoDimensions, Double) -> Boolean,
)

internal data class Av1EncoderSelection(
    val name: String,
    val contentSize: SceneVideoDimensions,
    val outputSize: SceneVideoDimensions,
)

internal fun selectAv1Encoder(
    source: SceneVideoDimensions,
    candidates: Sequence<Av1EncoderCandidate>,
    frameRate: Double = SCENE_FRAME_RATE,
    maxOutputDimension: Int = SCENE_MAX_OUTPUT_DIMENSION,
): Av1EncoderSelection? {
    if (source.width <= 0 || source.height <= 0) return null
    if (!frameRate.isFinite() || frameRate <= 0.0) return null
    val boundedOutputDimension = min(maxOutputDimension, SCENE_MAX_OUTPUT_DIMENSION)
    if (boundedOutputDimension < SCENE_PIXEL_ALIGNMENT) return null

    var bestSelection: Av1EncoderSelection? = null
    candidates.forEach { candidate ->
        if (candidate.name.isBlank() ||
            !candidate.supportsPlanarYuv420 ||
            !candidate.supportsConstantQuality ||
            !candidate.supportsTargetQuality ||
            candidate.widthAlignment <= 0 ||
            candidate.heightAlignment <= 0 ||
            candidate.minimumWidth <= 0 ||
            candidate.minimumHeight <= 0 ||
            candidate.maximumWidth < candidate.minimumWidth ||
            candidate.maximumHeight < candidate.minimumHeight
        ) {
            return@forEach
        }

        val widthAlignment = combinedAlignment(candidate.widthAlignment, SCENE_PIXEL_ALIGNMENT)
            ?: return@forEach
        val heightAlignment = combinedAlignment(candidate.heightAlignment, SCENE_PIXEL_ALIGNMENT)
            ?: return@forEach
        if (widthAlignment > boundedOutputDimension || heightAlignment > boundedOutputDimension) {
            return@forEach
        }
        val checkedSizes = mutableSetOf<SceneVideoDimensions>()
        val queriedHeights = mutableSetOf<Int>()
        val widthRanges = mutableMapOf<Int, IntRange?>()
        val selection = (boundedOutputDimension downTo SCENE_PIXEL_ALIGNMENT)
            .firstNotNullOfOrNull { contentCap ->
                val contentSize = scaledSceneSize(
                    source = source,
                    maxOutputDimension = contentCap,
                    widthAlignment = SCENE_PIXEL_ALIGNMENT,
                    heightAlignment = SCENE_PIXEL_ALIGNMENT,
                ) ?: return@firstNotNullOfOrNull null
                val outputSize = supportedCanvasSize(
                    contentSize = contentSize,
                    candidate = candidate,
                    widthAlignment = widthAlignment,
                    heightAlignment = heightAlignment,
                    frameRate = frameRate,
                    maxOutputDimension = boundedOutputDimension,
                    checkedSizes = checkedSizes,
                    queriedHeights = queriedHeights,
                    widthRanges = widthRanges,
                ) ?: return@firstNotNullOfOrNull null
                Av1EncoderSelection(
                    name = candidate.name,
                    contentSize = contentSize,
                    outputSize = outputSize,
                )
            }
            ?: return@forEach
        if (selection.isBetterThan(bestSelection, source)) {
            bestSelection = selection
        }
    }
    return bestSelection
}

private fun supportedCanvasSize(
    contentSize: SceneVideoDimensions,
    candidate: Av1EncoderCandidate,
    widthAlignment: Int,
    heightAlignment: Int,
    frameRate: Double,
    maxOutputDimension: Int,
    checkedSizes: MutableSet<SceneVideoDimensions>,
    queriedHeights: MutableSet<Int>,
    widthRanges: MutableMap<Int, IntRange?>,
): SceneVideoDimensions? {
    val minimumWidth = maxOf(contentSize.width, candidate.minimumWidth)
        .alignUp(widthAlignment, maxOutputDimension)
        ?: return null
    val minimumHeight = maxOf(contentSize.height, candidate.minimumHeight)
        .alignUp(heightAlignment, maxOutputDimension)
        ?: return null
    val maximumWidth = min(candidate.maximumWidth, maxOutputDimension)
        .alignDown(widthAlignment)
    val maximumHeight = min(candidate.maximumHeight, maxOutputDimension)
        .alignDown(heightAlignment)
    if (minimumWidth > maximumWidth || minimumHeight > maximumHeight) return null

    var best: SceneVideoDimensions? = null
    var height = minimumHeight
    while (height <= maximumHeight) {
        val bestArea = best?.let { it.width.toLong() * it.height }
        if (bestArea != null && height.toLong() * minimumWidth >= bestArea) break

        val widthRange = if (queriedHeights.add(height)) {
            runCatching {
                candidate.supportedWidthsForHeight(height)
            }.getOrNull().also { widthRanges[height] = it }
        } else {
            widthRanges[height]
        }
        if (widthRange != null && !widthRange.isEmpty()) {
            val rangeMaximum = min(widthRange.last, maximumWidth)
            var width = maxOf(minimumWidth, widthRange.first)
                .alignUp(widthAlignment, rangeMaximum)
            while (width != null && width <= rangeMaximum) {
                if (bestArea != null && height.toLong() * width >= bestArea) break
                val outputSize = SceneVideoDimensions(width = width, height = height)
                val supported = checkedSizes.add(outputSize) &&
                    runCatching {
                        candidate.supportsSizeAndRate(outputSize, frameRate)
                    }.getOrDefault(false)
                if (supported) {
                    best = outputSize
                    if (outputSize == contentSize) return outputSize
                    break
                }
                width = (width + widthAlignment)
                    .takeIf { it <= rangeMaximum }
            }
        }
        height += heightAlignment
    }
    return best
}

private fun Int.alignUp(alignment: Int, maximum: Int): Int? {
    val aligned = ((toLong() + alignment - 1L) / alignment) * alignment
    return aligned
        .takeIf { it in alignment.toLong()..maximum.toLong() }
        ?.toInt()
}

private fun scaledSceneSize(
    source: SceneVideoDimensions,
    maxOutputDimension: Int,
    widthAlignment: Int,
    heightAlignment: Int,
): SceneVideoDimensions? {
    val (fittedWidth, fittedHeight) = when {
        source.width <= maxOutputDimension && source.height <= maxOutputDimension -> {
            source.width to source.height
        }
        source.width >= source.height -> {
            maxOutputDimension to
                (maxOutputDimension.toLong() * source.height / source.width).toInt()
        }
        else -> {
            (maxOutputDimension.toLong() * source.width / source.height).toInt() to
                maxOutputDimension
        }
    }
    val maximumWidth = fittedWidth.alignDown(widthAlignment)
    val maximumHeight = fittedHeight.alignDown(heightAlignment)
    if (maximumWidth < widthAlignment || maximumHeight < heightAlignment) return null

    var best: SceneVideoDimensions? = null
    fun consider(width: Int, height: Int) {
        if (width !in widthAlignment..maximumWidth ||
            height !in heightAlignment..maximumHeight ||
            width % widthAlignment != 0 ||
            height % heightAlignment != 0
        ) {
            return
        }
        val candidate = SceneVideoDimensions(width = width, height = height)
        if (candidate.aspectErrorFrom(source) > MAX_CONTENT_ASPECT_ERROR) return
        if (candidate.isBetterContentThan(best, source)) {
            best = candidate
        }
    }

    alignedValueClosest(
        numerator = maximumWidth.toLong() * source.height,
        denominator = source.width.toLong(),
        alignment = heightAlignment,
        maximum = maximumHeight,
    )?.let { height -> consider(maximumWidth, height) }
    alignedValueClosest(
        numerator = maximumHeight.toLong() * source.width,
        denominator = source.height.toLong(),
        alignment = widthAlignment,
        maximum = maximumWidth,
    )?.let { width -> consider(width, maximumHeight) }
    return best
}

private fun alignedValueClosest(
    numerator: Long,
    denominator: Long,
    alignment: Int,
    maximum: Int,
): Int? {
    val alignedUnitDenominator = denominator * alignment
    val floorUnits = numerator / alignedUnitDenominator
    return sequenceOf(floorUnits, floorUnits + 1L)
        .filter { units -> units in 1..(maximum / alignment).toLong() }
        .map { units -> (units * alignment).toInt() }
        .distinct()
        .minWithOrNull(
            compareBy<Int> { value ->
                abs(numerator - value.toLong() * denominator)
            }.thenByDescending { it },
        )
}

private fun Int.alignDown(alignment: Int): Int {
    return this - (this % alignment)
}

private fun SceneVideoDimensions.isBetterContentThan(
    other: SceneVideoDimensions?,
    source: SceneVideoDimensions,
): Boolean {
    other ?: return true
    val area = width.toLong() * height
    val otherArea = other.width.toLong() * other.height
    if (area != otherArea) return area > otherArea
    val aspectError = aspectErrorFrom(source)
    val otherAspectError = other.aspectErrorFrom(source)
    if (aspectError != otherAspectError) return aspectError < otherAspectError
    if (width != other.width) return width > other.width
    return height > other.height
}

private fun Av1EncoderSelection.isBetterThan(
    other: Av1EncoderSelection?,
    source: SceneVideoDimensions,
): Boolean {
    other ?: return true
    if (contentSize != other.contentSize) {
        val thisIsBetter = contentSize.isBetterContentThan(other.contentSize, source)
        val otherIsBetter = other.contentSize.isBetterContentThan(contentSize, source)
        if (thisIsBetter != otherIsBetter) return thisIsBetter
    }
    val outputArea = outputSize.width.toLong() * outputSize.height
    val otherOutputArea = other.outputSize.width.toLong() * other.outputSize.height
    return outputArea < otherOutputArea
}

private fun SceneVideoDimensions.aspectErrorFrom(source: SceneVideoDimensions): Double {
    val scaledSourceWidth = width.toLong() * source.height
    val scaledOutputWidth = height.toLong() * source.width
    return abs(scaledSourceWidth - scaledOutputWidth).toDouble() / scaledOutputWidth
}

private fun combinedAlignment(first: Int, second: Int): Int? {
    var a = first
    var b = second
    while (b != 0) {
        val remainder = a % b
        a = b
        b = remainder
    }
    val combined = first.toLong() / a * second
    return combined.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
}

internal const val SCENE_MAX_OUTPUT_DIMENSION = 640
internal const val SCENE_PIXEL_ALIGNMENT = 2
internal const val SCENE_FRAME_RATE = 8.0
private const val MAX_CONTENT_ASPECT_ERROR = 0.002
