package eu.kanade.tachiyomi.ui.player.scene

import java.util.Locale

internal object SceneMediaProbe {
    fun inspect(output: String): Boolean {
        val normalized = output.lowercase(Locale.ROOT)
        if (PROTECTION_MARKERS.any(normalized::contains)) {
            return false
        }
        val values = output.lineSequence()
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
        val pixelFormat = values.firstOrNull { it.first == "pix_fmt" }?.second
            ?: return false
        if (pixelFormat in setOf("none", "unknown")) {
            return false
        }
        val rawBits = values.firstOrNull { it.first == "bits_per_raw_sample" }
            ?.second
            ?.toIntOrNull()
        val transfer = values.firstOrNull { it.first == "color_transfer" }?.second.orEmpty()
        val primaries = values.firstOrNull { it.first == "color_primaries" }?.second.orEmpty()
        val profile = values.firstOrNull { it.first == "profile" }?.second.orEmpty()
        if (
            rawBits?.let { it > 8 } == true ||
            TEN_BIT_PIXEL_FORMAT.containsMatchIn(pixelFormat) ||
            transfer in HDR_TRANSFERS ||
            primaries == "bt2020" ||
            profile.contains("main 10")
        ) {
            return false
        }
        return true
    }

    fun inspectAudio(output: String): Boolean {
        val normalized = output.lowercase(Locale.ROOT)
        return PROTECTION_MARKERS.none(normalized::contains) && "codec_type=audio" in normalized
    }

    private val HDR_TRANSFERS = setOf("smpte2084", "arib-std-b67")
    private val TEN_BIT_PIXEL_FORMAT = Regex("(p0(?:10|12|16)|p(?:9|10|12|14|16)(?:le|be)?)(?:$|[^0-9])")
    private val PROTECTION_MARKERS = setOf("cenc", "cbcs", "crypto", "encrypted", "encryption", "drm")
}
