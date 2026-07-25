package eu.kanade.tachiyomi.ui.player.scene

import java.util.Locale

internal sealed interface SceneMediaProbeResult {
    data class Supported(
        val durationSeconds: Double,
    ) : SceneMediaProbeResult

    data class Unsupported(
        val reason: SceneCaptureUnsupportedReason,
    ) : SceneMediaProbeResult

    data object Invalid : SceneMediaProbeResult
}

internal object SceneMediaProbe {
    fun arguments(
        input: SceneVideoInputSpec,
        acquiredInputValue: String,
    ): Array<String> {
        val arguments = SceneFfmpegArguments.videoProbe(input, acquiredInputValue)
        val showEntriesIndex = arguments.indexOf("-show_entries")
        check(showEntriesIndex >= 0 && showEntriesIndex + 1 < arguments.size) {
            "Video probe arguments are missing -show_entries"
        }
        arguments[showEntriesIndex + 1] =
            "${arguments[showEntriesIndex + 1]}:format=duration,format_name"
        return arguments
    }

    fun parse(output: String): SceneMediaProbeResult {
        val values = output
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && '=' in it }
            .map { line -> line.substringBefore('=') to line.substringAfter('=') }
            .toMap()

        val duration = values["duration"]
            ?.takeUnless { it.equals("N/A", ignoreCase = true) }
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: return SceneMediaProbeResult.Unsupported(SceneCaptureUnsupportedReason.NON_SEEKABLE)

        val bitsPerRawSample = values["bits_per_raw_sample"]
            ?.takeUnless { it.equals("N/A", ignoreCase = true) }
            ?.toIntOrNull()
        val pixelFormat = values["pix_fmt"].orEmpty().lowercase(Locale.ROOT)
        val colorTransfer = values["color_transfer"].orEmpty().lowercase(Locale.ROOT)
        val colorPrimaries = values["color_primaries"].orEmpty().lowercase(Locale.ROOT)
        val colorSpace = values["color_space"].orEmpty().lowercase(Locale.ROOT)
        val codecTag = values["codec_tag_string"].orEmpty().lowercase(Locale.ROOT)
        val formatName = values["format_name"].orEmpty().lowercase(Locale.ROOT)
        if (codecTag in ENCRYPTED_CODEC_TAGS) {
            return SceneMediaProbeResult.Unsupported(SceneCaptureUnsupportedReason.DRM)
        }
        if ("crypto" in formatName) {
            return SceneMediaProbeResult.Unsupported(SceneCaptureUnsupportedReason.ENCRYPTED)
        }
        if (
            (bitsPerRawSample != null && bitsPerRawSample > 8) ||
            pixelFormat.isTenBitOrHigher() ||
            colorTransfer in HDR_TRANSFERS ||
            colorPrimaries in HDR_COLOR_VALUES ||
            colorSpace in HDR_COLOR_VALUES
        ) {
            return SceneMediaProbeResult.Unsupported(SceneCaptureUnsupportedReason.HDR_OR_TEN_BIT)
        }

        if (pixelFormat.isBlank()) return SceneMediaProbeResult.Invalid
        return SceneMediaProbeResult.Supported(duration)
    }

    private fun String.isTenBitOrHigher(): Boolean {
        if (isBlank()) return false
        if (startsWith("p010") || startsWith("p012") || startsWith("p016")) return true
        if (startsWith("y210") || startsWith("v210") || startsWith("x2rgb10")) return true
        return HIGH_BIT_DEPTH_PATTERN.containsMatchIn(this)
    }

    private val HIGH_BIT_DEPTH_PATTERN =
        Regex("""(?:^|[a-z_])(?:10|12|14|16)(?:le|be|$)""")
    private val HDR_TRANSFERS = setOf(
        "arib-std-b67",
        "smpte2084",
    )
    private val HDR_COLOR_VALUES = setOf(
        "bt2020",
        "bt2020c",
        "bt2020nc",
    )
    private val ENCRYPTED_CODEC_TAGS = setOf("enca", "encv")
}
