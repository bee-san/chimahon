package eu.kanade.tachiyomi.ui.player.scene

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal object SceneMediaProbe {
    fun inspect(output: String): Boolean {
        return isSafeVideo(output, parseValues(output))
    }

    fun inspectVideo(output: String): SceneVideoDimensions? {
        val values = parseValues(output)
        if (!isSafeVideo(output, values)) return null

        val width = values.firstOrNull { it.first == "width" }?.second?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: return null
        val height = values.firstOrNull { it.first == "height" }?.second?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: return null
        val sampleAspectRatio = values
            .firstOrNull { it.first == "sample_aspect_ratio" }
            ?.second
            .toSampleAspectRatio()
        val displayWidthValue = width.toDouble() * sampleAspectRatio
        if (!displayWidthValue.isFinite() || displayWidthValue !in 1.0..Int.MAX_VALUE.toDouble()) {
            return null
        }
        val displayWidth = displayWidthValue.roundToInt()
        val rotationValue = values.firstOrNull { it.first == "rotation" }?.second
            ?.toDoubleOrNull()
            ?: 0.0
        if (!rotationValue.isFinite()) return null
        val normalizedRotationValue = ((rotationValue % 360.0) + 360.0) % 360.0
        val rotation = normalizedRotationValue.roundToInt()
        if (abs(normalizedRotationValue - rotation) > ROTATION_EPSILON || rotation % 90 != 0) {
            return null
        }
        val normalizedRotation = ((rotation % 360) + 360) % 360
        return if (normalizedRotation == 90 || normalizedRotation == 270) {
            SceneVideoDimensions(width = height, height = displayWidth)
        } else {
            SceneVideoDimensions(width = displayWidth, height = height)
        }
    }

    fun inspectAudio(output: String): Boolean {
        val normalized = output.lowercase(Locale.ROOT)
        return PROTECTION_MARKERS.none(normalized::contains) && "codec_type=audio" in normalized
    }

    private fun isSafeVideo(
        output: String,
        values: List<Pair<String, String>>,
    ): Boolean {
        val normalized = output.lowercase(Locale.ROOT)
        if (PROTECTION_MARKERS.any(normalized::contains)) {
            return false
        }
        val pixelFormat = values.firstOrNull { it.first == "pix_fmt" }?.second
            ?: return false
        if (pixelFormat in setOf("none", "unknown")) {
            return false
        }
        val transfer = values.firstOrNull { it.first == "color_transfer" }?.second.orEmpty()
        val primaries = values.firstOrNull { it.first == "color_primaries" }?.second.orEmpty()
        if (transfer in HDR_TRANSFERS || primaries == "bt2020") {
            return false
        }
        return true
    }

    private fun parseValues(output: String): List<Pair<String, String>> {
        return output.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator).trim().lowercase(Locale.ROOT) to
                        line.substring(separator + 1).trim().lowercase(Locale.ROOT)
                }
            }
            .toList()
    }

    private fun String?.toSampleAspectRatio(): Double {
        val parts = this?.split(':', limit = 2)
        val numerator = parts?.getOrNull(0)?.toLongOrNull()
        val denominator = parts?.getOrNull(1)?.toLongOrNull()
        if (numerator == null || denominator == null || numerator <= 0L || denominator <= 0L) {
            return 1.0
        }
        return numerator.toDouble() / denominator.toDouble()
    }

    private val HDR_TRANSFERS = setOf("smpte2084", "arib-std-b67")
    private const val ROTATION_EPSILON = 0.001
    private val PROTECTION_MARKERS = setOf("cenc", "cbcs", "crypto", "encrypted", "encryption", "drm")
}
