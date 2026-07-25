package eu.kanade.tachiyomi.ui.player.scene

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

internal data class SceneVideoIdentity(
    val episodeId: Long?,
    val sourceId: Long?,
    val quality: String,
    val inputDigest: String,
)

internal enum class SceneVideoInputKind {
    LOCAL_FILE,
    CONTENT_URI,
    REMOTE_HTTP,
}

internal data class SceneInputOption(
    val name: String,
    val value: String,
) {
    override fun toString(): String = "SceneInputOption(name=$name, value=<redacted>)"
}

internal data class SceneVideoInputSpec(
    val value: String,
    val kind: SceneVideoInputKind,
    val headers: List<Pair<String, String>>,
    val inputOptions: List<SceneInputOption>,
    val externalAudioValue: String?,
    val identity: SceneVideoIdentity,
) {
    override fun toString(): String {
        return "SceneVideoInputSpec(" +
            "value=${SceneSecretRedactor.redactUrl(value)}, " +
            "kind=$kind, " +
            "headers=${SceneSecretRedactor.redactHeaders(headers)}, " +
            "inputOptions=${inputOptions.map(SceneInputOption::name)}, " +
            "externalAudioValue=${externalAudioValue?.let(SceneSecretRedactor::redactUrl)}, " +
            "identity=$identity)"
    }
}

internal enum class SceneUnsupportedReason {
    NO_VIDEO,
    TRANSIENT_INPUT,
    UNSUPPORTED_SCHEME,
    UNSAFE_INPUT_OPTION,
    NON_SEEKABLE,
    TORRENT,
    ENCRYPTED,
    DRM,
    HDR,
    CONTENT_URI_UNAVAILABLE,
}

internal sealed interface SceneVideoInputResolution {
    data class Supported(val input: SceneVideoInputSpec) : SceneVideoInputResolution
    data class Unsupported(
        val reason: SceneUnsupportedReason,
        val diagnostic: String,
    ) : SceneVideoInputResolution
}

internal data class SceneVideoInputSnapshot(
    val originalVideoValue: String,
    val playableValue: String?,
    val externalAudioValue: String?,
    val headers: List<Pair<String, String>>,
    val ffmpegStreamArgs: List<Pair<String, String>>,
    val ffmpegVideoArgs: List<Pair<String, String>>,
    val episodeId: Long?,
    val sourceId: Long?,
    val quality: String,
    val seekable: Boolean? = null,
    val encrypted: Boolean = false,
    val drmProtected: Boolean = false,
    val torrent: Boolean = false,
) {
    override fun toString(): String {
        return "SceneVideoInputSnapshot(" +
            "originalVideoValue=${SceneSecretRedactor.redactUrl(originalVideoValue)}, " +
            "playableValue=${playableValue?.let(SceneSecretRedactor::redactUrl)}, " +
            "externalAudioValue=${externalAudioValue?.let(SceneSecretRedactor::redactUrl)}, " +
            "headers=${SceneSecretRedactor.redactHeaders(headers)}, " +
            "ffmpegStreamArgs=${ffmpegStreamArgs.map { it.first }}, " +
            "ffmpegVideoArgs=${ffmpegVideoArgs.map { it.first }}, " +
            "episodeId=$episodeId, sourceId=$sourceId, quality=$quality, " +
            "seekable=$seekable, encrypted=$encrypted, drmProtected=$drmProtected, torrent=$torrent)"
    }
}

internal object SceneInputOriginPolicy {
    fun hasSameHttpOrigin(
        firstValue: String,
        secondValue: String,
    ): Boolean {
        val first = NetworkOrigin.from(firstValue) ?: return false
        val second = NetworkOrigin.from(secondValue) ?: return false
        return first == second
    }

    fun hasReferer(options: List<SceneInputOption>): Boolean {
        return options.any { it.name.equals("referer", ignoreCase = true) }
    }

    fun hasAnyHeadersOrReferer(
        headers: List<Pair<String, String>>,
        options: List<SceneInputOption>,
    ): Boolean {
        return headers.isNotEmpty() || hasReferer(options)
    }

    fun hasNonPortableHeadersOrReferer(
        headers: List<Pair<String, String>>,
        options: List<SceneInputOption>,
    ): Boolean {
        return headers.any { (name, _) ->
            name.lowercase(Locale.ROOT) !in PORTABLE_CROSS_ORIGIN_HEADERS
        } || hasReferer(options)
    }

    private data class NetworkOrigin(
        val scheme: String,
        val host: String,
        val port: Int,
    ) {
        companion object {
            fun from(value: String): NetworkOrigin? {
                val uri = runCatching { URI(value) }.getOrNull() ?: return null
                val scheme = uri.scheme?.lowercase(Locale.ROOT)
                    ?.takeIf { it == "http" || it == "https" }
                    ?: return null
                val host = uri.host?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank)
                    ?: return null
                val port = when {
                    uri.port >= 0 -> uri.port
                    scheme == "https" -> 443
                    else -> 80
                }
                return NetworkOrigin(scheme, host, port)
            }
        }
    }

    private val PORTABLE_CROSS_ORIGIN_HEADERS = setOf(
        "accept",
        "accept-charset",
        "accept-encoding",
        "accept-language",
        "cache-control",
        "pragma",
        "user-agent",
    )
}

internal object SceneVideoInputResolver {
    fun resolve(snapshot: SceneVideoInputSnapshot): SceneVideoInputResolution {
        if (snapshot.originalVideoValue.isBlank() && snapshot.playableValue.isNullOrBlank()) {
            return unsupported(SceneUnsupportedReason.NO_VIDEO, "No video input is available")
        }
        if (snapshot.drmProtected) {
            return unsupported(SceneUnsupportedReason.DRM, "DRM-protected video input is unsupported")
        }
        if (snapshot.encrypted) {
            return unsupported(SceneUnsupportedReason.ENCRYPTED, "Encrypted video input is unsupported")
        }
        if (!snapshot.headers.all(::isValidHeader)) {
            return unsupported(
                SceneUnsupportedReason.UNSAFE_INPUT_OPTION,
                "Video input contains an invalid HTTP header",
            )
        }

        val options = buildList {
            val allOptions = snapshot.ffmpegStreamArgs + snapshot.ffmpegVideoArgs
            allOptions.forEach { (rawName, value) ->
                val normalizedName = rawName.removePrefix("-").lowercase(Locale.ROOT)
                if (normalizedName !in ALLOWED_INPUT_OPTIONS) {
                    return unsupported(
                        SceneUnsupportedReason.UNSAFE_INPUT_OPTION,
                        "Unsupported FFmpeg input option: ${SceneSecretRedactor.redactOptionName(rawName)}",
                    )
                }
                if (value.indexOf('\u0000') >= 0) {
                    return unsupported(
                        SceneUnsupportedReason.UNSAFE_INPUT_OPTION,
                        "FFmpeg input option contains invalid data",
                    )
                }
                if (!isValidInputOption(normalizedName, value)) {
                    return unsupported(
                        SceneUnsupportedReason.UNSAFE_INPUT_OPTION,
                        "FFmpeg input option contains an invalid value",
                    )
                }
                add(SceneInputOption(normalizedName, value))
            }
        }

        val playableValue = snapshot.playableValue
            ?.takeIf(String::isNotBlank)
            ?.takeUnless(::isTransientMpvValue)
        val normalizedPlayable = playableValue?.let(::normalizeInput)
        if (playableValue != null && normalizedPlayable == null) {
            return unsupported(
                SceneUnsupportedReason.UNSUPPORTED_SCHEME,
                "Unsupported player video input scheme",
            )
        }
        val normalizedOriginal = snapshot.originalVideoValue
            .takeIf(String::isNotBlank)
            ?.takeUnless(::isTransientMpvValue)
            ?.let(::normalizeInput)
        val isTorrentPlayback = snapshot.torrent || isTorrent(snapshot.originalVideoValue)
        var normalizedInput = if (isTorrentPlayback) {
            val stablePlayable = normalizedPlayable
                ?.takeIf { (_, kind) ->
                    kind == SceneVideoInputKind.LOCAL_FILE || kind == SceneVideoInputKind.CONTENT_URI
                }
            if (snapshot.seekable != true || stablePlayable == null) {
                return unsupported(
                    SceneUnsupportedReason.TORRENT,
                    "Torrent playback has no stable seekable local input",
                )
            }
            stablePlayable
        } else {
            if (snapshot.seekable != true) {
                return unsupported(
                    SceneUnsupportedReason.NON_SEEKABLE,
                    "Video input was not proven to be seekable",
                )
            }
            normalizedPlayable ?: normalizedOriginal
                ?: return if (
                    isTransientMpvValue(snapshot.originalVideoValue) ||
                    snapshot.playableValue?.let(::isTransientMpvValue) == true
                ) {
                    unsupported(
                        SceneUnsupportedReason.TRANSIENT_INPUT,
                        "Only a transient player input is available",
                    )
                } else {
                    unsupported(
                        SceneUnsupportedReason.UNSUPPORTED_SCHEME,
                        "Unsupported video input scheme",
                    )
                }
        }
        if (
            !isTorrentPlayback &&
            normalizedPlayable != null &&
            normalizedPlayable.second == SceneVideoInputKind.REMOTE_HTTP &&
            snapshot.originalVideoValue.isNotBlank() &&
            (
                normalizedOriginal == null ||
                    !SceneInputOriginPolicy.hasSameHttpOrigin(
                        normalizedOriginal.first,
                        normalizedPlayable.first,
                    )
                ) &&
            SceneInputOriginPolicy.hasNonPortableHeadersOrReferer(snapshot.headers, options)
        ) {
            normalizedInput = normalizedOriginal
                ?: return unsupported(
                    SceneUnsupportedReason.UNSAFE_INPUT_OPTION,
                    "Player video input changed origin with origin-bound request metadata",
                )
        }
        val normalizedAudio = snapshot.externalAudioValue
            ?.takeIf(String::isNotBlank)
            ?.let(::normalizeInput)
            ?.first

        val scopedHeaders: List<Pair<String, String>>
        val scopedOptions: List<SceneInputOption>
        if (normalizedInput.second == SceneVideoInputKind.REMOTE_HTTP) {
            scopedHeaders = snapshot.headers.toList()
            scopedOptions = options
        } else {
            scopedHeaders = emptyList()
            scopedOptions = options.filterNot { it.name.equals("referer", ignoreCase = true) }
        }

        val identitySource = listOf(
            snapshot.episodeId?.toString().orEmpty(),
            snapshot.sourceId?.toString().orEmpty(),
            snapshot.quality,
            snapshot.originalVideoValue,
        ).joinToString("\u001f")
        return SceneVideoInputResolution.Supported(
            SceneVideoInputSpec(
                value = normalizedInput.first,
                kind = normalizedInput.second,
                headers = scopedHeaders,
                inputOptions = scopedOptions,
                externalAudioValue = normalizedAudio,
                identity = SceneVideoIdentity(
                    episodeId = snapshot.episodeId,
                    sourceId = snapshot.sourceId,
                    quality = snapshot.quality,
                    inputDigest = identitySource.sha256Hex(),
                ),
            ),
        )
    }

    private fun normalizeInput(value: String): Pair<String, SceneVideoInputKind>? {
        return when {
            value.startsWith("content://", ignoreCase = true) -> {
                value to SceneVideoInputKind.CONTENT_URI
            }
            value.startsWith("file://", ignoreCase = true) -> {
                val path = runCatching { URI(value).path }.getOrNull()
                    ?.takeIf(String::isNotBlank)
                    ?: return null
                path to SceneVideoInputKind.LOCAL_FILE
            }
            value.startsWith("/") -> value to SceneVideoInputKind.LOCAL_FILE
            value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true) -> {
                value to SceneVideoInputKind.REMOTE_HTTP
            }
            else -> null
        }
    }

    private fun isTransientMpvValue(value: String): Boolean {
        val scheme = value.substringBefore("://", missingDelimiterValue = "").lowercase(Locale.ROOT)
        return scheme in TRANSIENT_MPV_SCHEMES
    }

    private fun isTorrent(value: String): Boolean {
        return value.startsWith("magnet:", ignoreCase = true) ||
            value.substringBefore('?').endsWith(".torrent", ignoreCase = true)
    }

    private fun isValidInputOption(name: String, value: String): Boolean {
        if (
            value.length > MAX_OPTION_VALUE_LENGTH ||
            value.any { it == '\u0000' || it == '\r' || it == '\n' || (it.code < 0x20 && it != '\t') }
        ) {
            return false
        }
        return when (name) {
            "analyzeduration", "probesize", "rw_timeout", "timeout" -> {
                value.toLongOrNull()?.let { it in 0..MAX_NUMERIC_OPTION_VALUE } == true
            }
            "http_persistent",
            "icy",
            "multiple_requests",
            "reconnect",
            "reconnect_at_eof",
            "reconnect_streamed",
            "seekable",
            "tls_verify",
            -> value in BOOLEAN_OPTION_VALUES
            "reconnect_delay_max" -> value.toIntOrNull()?.let { it in 0..MAX_RECONNECT_DELAY_SECONDS } == true
            "referer" -> value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true)
            "user_agent" -> value.isNotBlank()
            else -> false
        }
    }

    private fun isValidHeader(header: Pair<String, String>): Boolean {
        val (name, value) = header
        return name.isNotBlank() &&
            name.length <= MAX_HEADER_NAME_LENGTH &&
            HTTP_HEADER_NAME.matches(name) &&
            value.length <= MAX_HEADER_VALUE_LENGTH &&
            value.none { it == '\u0000' || it == '\r' || it == '\n' || (it.code < 0x20 && it != '\t') }
    }

    private fun unsupported(
        reason: SceneUnsupportedReason,
        diagnostic: String,
    ): SceneVideoInputResolution.Unsupported {
        return SceneVideoInputResolution.Unsupported(reason, diagnostic)
    }

    private fun String.sha256Hex(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private val TRANSIENT_MPV_SCHEMES = setOf(
        "fd",
        "fdclose",
        "edl",
        "memory",
        "lavf",
        "ytdl",
    )

    private val ALLOWED_INPUT_OPTIONS = setOf(
        "analyzeduration",
        "http_persistent",
        "icy",
        "multiple_requests",
        "probesize",
        "reconnect",
        "reconnect_at_eof",
        "reconnect_delay_max",
        "reconnect_streamed",
        "referer",
        "rw_timeout",
        "seekable",
        "timeout",
        "tls_verify",
        "user_agent",
    )
    private val BOOLEAN_OPTION_VALUES = setOf("0", "1", "false", "true")
    private const val MAX_OPTION_VALUE_LENGTH = 4_096
    private const val MAX_HEADER_NAME_LENGTH = 128
    private const val MAX_HEADER_VALUE_LENGTH = 8_192
    private const val MAX_NUMERIC_OPTION_VALUE = 3_600_000_000L
    private const val MAX_RECONNECT_DELAY_SECONDS = 300
    private val HTTP_HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
}

internal object SceneFfmpegArguments {
    fun frameExtraction(
        input: SceneVideoInputSpec,
        acquiredInputValue: String,
        range: SceneTimeRange,
        outputPattern: String,
    ): Array<String> {
        return buildList {
            input.inputOptions.forEach { option ->
                add("-${option.name}")
                add(option.value)
            }
            if (input.headers.isNotEmpty()) {
                add("-headers")
                add(input.headers.toFfmpegHeaderBlock())
            }
            add("-ss")
            add(range.startSeconds.toFfmpegSeconds())
            add("-i")
            add(acquiredInputValue)
            add("-map")
            add("0:v:0")
            add("-an")
            add("-sn")
            add("-dn")
            add("-t")
            add(range.durationSeconds.toFfmpegSeconds())
            add("-vf")
            add(FRAME_FILTER)
            add("-fps_mode")
            add("passthrough")
            add("-frames:v")
            add(SCENE_MAX_FRAME_COUNT.toString())
            add("-f")
            add("image2")
            add("-y")
            add(outputPattern)
        }.toTypedArray()
    }

    fun animatedWebpMux(
        inputPattern: String,
        outputFile: String,
    ): Array<String> {
        return arrayOf(
            "-framerate",
            SCENE_FRAME_RATE.toString(),
            "-i",
            inputPattern,
            "-c:v",
            "copy",
            "-loop",
            "0",
            "-f",
            "webp",
            "-y",
            outputFile,
        )
    }

    fun videoProbe(
        input: SceneVideoInputSpec,
        acquiredInputValue: String,
    ): Array<String> {
        return buildList {
            input.inputOptions.forEach { option ->
                add("-${option.name}")
                add(option.value)
            }
            if (input.headers.isNotEmpty()) {
                add("-headers")
                add(input.headers.toFfmpegHeaderBlock())
            }
            add("-v")
            add("error")
            add("-select_streams")
            add("v:0")
            add("-show_entries")
            add(
                "stream=pix_fmt,color_transfer,color_primaries,color_space," +
                    "bits_per_raw_sample,codec_tag_string",
            )
            add("-of")
            add("default=noprint_wrappers=1")
            add(acquiredInputValue)
        }.toTypedArray()
    }

    private fun List<Pair<String, String>>.toFfmpegHeaderBlock(): String {
        return joinToString(separator = "", postfix = "") { (name, value) ->
            "$name: $value\r\n"
        }
    }

    private fun Double.toFfmpegSeconds(): String {
        return String.format(Locale.ROOT, "%.6f", this)
            .trimEnd('0')
            .trimEnd('.')
    }

    internal const val SCENE_FRAME_RATE = 8
    private const val FRAME_FILTER =
        "fps=$SCENE_FRAME_RATE," +
            "scale=w='min(640,iw)':h='min(640,ih)':" +
            "force_original_aspect_ratio=decrease:force_divisible_by=2," +
            "setsar=1"
}

internal object SceneSecretRedactor {
    fun redactHeaders(headers: List<Pair<String, String>>): List<Pair<String, String>> {
        return headers.map { (name, value) ->
            name to if (isSecretHeaderName(name)) {
                REDACTED
            } else {
                redactUrl(value)
            }
        }
    }

    fun redactUrl(value: String): String {
        val withoutUserInfo = redactUserInfo(value)
        val question = withoutUserInfo.indexOf('?')
        if (question < 0) {
            val fragment = withoutUserInfo.indexOf('#')
            return if (fragment >= 0) {
                withoutUserInfo.substring(0, fragment + 1) + REDACTED
            } else {
                withoutUserInfo
            }
        }
        val fragment = withoutUserInfo.indexOf('#', startIndex = question)
        val queryEnd = if (fragment >= 0) fragment else withoutUserInfo.length
        val redactedQuery = withoutUserInfo.substring(question + 1, queryEnd)
            .split('&')
            .joinToString("&") { part ->
                val name = part.substringBefore('=')
                if (isSecretQueryName(name)) "$name=$REDACTED" else part
            }
        return buildString {
            append(withoutUserInfo, 0, question + 1)
            append(redactedQuery)
            if (fragment >= 0) {
                append('#')
                append(REDACTED)
            }
        }
    }

    fun redactOptionName(value: String): String {
        val normalized = value.removePrefix("-").lowercase(Locale.ROOT)
        return if (normalized in SECRET_OPTION_NAMES) REDACTED else value.take(64)
    }

    private fun redactUserInfo(value: String): String {
        val schemeEnd = value.indexOf("://")
        if (schemeEnd < 0) return value
        val authorityStart = schemeEnd + 3
        val authorityEnd = listOf(
            value.indexOf('/', authorityStart),
            value.indexOf('?', authorityStart),
            value.indexOf('#', authorityStart),
        )
            .filter { it >= 0 }
            .minOrNull()
            ?: value.length
        val userInfoEnd = value.lastIndexOf('@', authorityEnd - 1)
        if (userInfoEnd < authorityStart) return value
        return buildString(value.length) {
            append(value, 0, authorityStart)
            append(REDACTED)
            append(value, userInfoEnd, value.length)
        }
    }

    private fun isSecretHeaderName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower in SECRET_HEADER_NAMES || SECRET_HEADER_NAME_PARTS.any(lower::contains)
    }

    private fun isSecretQueryName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return SECRET_QUERY_PARTS.any(lower::contains)
    }

    private const val REDACTED = "<redacted>"
    private val SECRET_HEADER_NAMES = setOf(
        "authorization",
        "cookie",
        "proxy-authorization",
        "set-cookie",
        "x-api-key",
    )
    private val SECRET_OPTION_NAMES = setOf("cookies", "headers", "key_file")
    private val SECRET_HEADER_NAME_PARTS = setOf(
        "api-key",
        "apikey",
        "auth",
        "cookie",
        "credential",
        "jwt",
        "secret",
        "session",
        "token",
    )
    private val SECRET_QUERY_PARTS = setOf(
        "auth",
        "credential",
        "key",
        "policy",
        "signature",
        "signed",
        "sig",
        "token",
        "x-amz-",
        "x-goog-",
    )
}
