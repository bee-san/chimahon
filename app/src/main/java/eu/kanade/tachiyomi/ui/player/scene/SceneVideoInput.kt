package eu.kanade.tachiyomi.ui.player.scene

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal enum class SceneVideoInputKind {
    LOCAL_FILE,
    CONTENT_URI,
    REMOTE_HTTP,
}

internal data class SceneVideoInputSpec(
    val value: String,
    val kind: SceneVideoInputKind,
    val headers: List<Pair<String, String>>,
    val videoStreamIndex: Int? = null,
    val audioStreamIndex: Int? = null,
)

internal data class SceneVideoInputSnapshot(
    val originalVideoValue: String,
    val playableValue: String?,
    val headers: List<Pair<String, String>>,
    val ffmpegStreamArgs: List<Pair<String, String>>,
    val ffmpegVideoArgs: List<Pair<String, String>>,
    val seekable: Boolean?,
    val videoStreamIndex: Int? = null,
    val audioStreamIndex: Int? = null,
)

internal object SceneVideoInputResolver {
    fun resolve(snapshot: SceneVideoInputSnapshot): SceneVideoInputSpec? {
        if (snapshot.originalVideoValue.isBlank() && snapshot.playableValue.isNullOrBlank()) {
            sceneLog { "resolve: rejected, both originalVideoValue and playableValue blank" }
            return null
        }
        if (isDash(snapshot.originalVideoValue) || isDash(snapshot.playableValue)) {
            sceneLog { "resolve: rejected, DASH input is unsupported" }
            return null
        }
        if (snapshot.ffmpegStreamArgs.isNotEmpty() || snapshot.ffmpegVideoArgs.isNotEmpty()) {
            sceneLog {
                "resolve: rejected, extension supplied ffmpeg args " +
                    "(stream=${snapshot.ffmpegStreamArgs.size} video=${snapshot.ffmpegVideoArgs.size})"
            }
            return null
        }
        if (snapshot.seekable != true) {
            sceneLog { "resolve: rejected, input not seekable (seekable=${snapshot.seekable})" }
            return null
        }

        val original = snapshot.originalVideoValue.takeIf(String::isNotBlank)
        if (original != null && isTransient(original)) {
            sceneLog { "resolve: rejected, originalVideoValue has a transient scheme" }
            return null
        }
        val normalized = original?.let(::normalizeInput)
            ?: snapshot.playableValue?.takeIf(String::isNotBlank)?.let { playable ->
                if (isTransient(playable)) {
                    sceneLog { "resolve: rejected, playableValue has a transient scheme" }
                    return null
                }
                normalizeInput(playable)
            }
            ?: run {
                sceneLog {
                    "resolve: rejected, unrecognized input scheme " +
                        "original=${redactSceneValue(snapshot.originalVideoValue)} " +
                        "playable=${redactSceneValue(snapshot.playableValue)}"
                }
                return null
            }

        val headers = when (normalized.second) {
            SceneVideoInputKind.REMOTE_HTTP -> validateRemoteInput(normalized.first, snapshot.headers)
                ?: run {
                    sceneLog { "resolve: rejected, remote input failed validation (credentials or headers)" }
                    return null
                }
            SceneVideoInputKind.LOCAL_FILE,
            SceneVideoInputKind.CONTENT_URI,
            -> emptyList()
        }

        return SceneVideoInputSpec(
            value = normalized.first,
            kind = normalized.second,
            headers = headers,
            videoStreamIndex = snapshot.videoStreamIndex?.takeIf { it >= 0 },
            audioStreamIndex = snapshot.audioStreamIndex?.takeIf { it >= 0 },
        )
    }

    private fun validateRemoteInput(
        value: String,
        headers: List<Pair<String, String>>,
    ): List<Pair<String, String>>? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.userInfo.isNullOrBlank() || uri.host.isNullOrBlank()) return null
        if (hasSensitiveQuery(uri.rawQuery.orEmpty())) return null
        if (!headers.all(::isAllowedHeader)) return null
        return headers
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

    private fun isAllowedHeader(header: Pair<String, String>): Boolean {
        val (name, value) = header
        return name.lowercase(Locale.ROOT) in ALLOWED_HTTP_HEADERS &&
            value.length <= MAX_HEADER_VALUE_LENGTH &&
            value.none { it == '\u0000' || it == '\r' || it == '\n' || (it.code < 0x20 && it != '\t') }
    }

    private fun hasSensitiveQuery(query: String): Boolean {
        query.split('&').forEach { parameter ->
            val name = runCatching {
                URLDecoder.decode(parameter.substringBefore('='), StandardCharsets.UTF_8.name())
                    .lowercase(Locale.ROOT)
            }.getOrNull() ?: return true
            if (name in SENSITIVE_QUERY_NAMES || SENSITIVE_QUERY_PREFIXES.any(name::startsWith)) {
                return true
            }
        }
        return false
    }

    private fun isDash(value: String?): Boolean {
        val path = value?.substringBefore('?')?.lowercase(Locale.ROOT).orEmpty()
        return path.endsWith(".mpd") || value?.startsWith("dash://", ignoreCase = true) == true
    }

    private fun isTransient(value: String): Boolean {
        val scheme = value.substringBefore("://", missingDelimiterValue = "").lowercase(Locale.ROOT)
        return scheme in TRANSIENT_SCHEMES ||
            value.startsWith("magnet:", ignoreCase = true) ||
            value.substringBefore('?').endsWith(".torrent", ignoreCase = true)
    }

    private val ALLOWED_HTTP_HEADERS = setOf(
        "user-agent",
        "accept",
        "accept-encoding",
        "accept-language",
        "cache-control",
        "origin",
        "pragma",
        "referer",
    )
    private val SENSITIVE_QUERY_NAMES = setOf(
        "access_token",
        "api_key",
        "auth",
        "authorization",
        "credential",
        "credentials",
        "key",
        "policy",
        "signature",
        "signed",
        "sig",
        "token",
    )
    private val SENSITIVE_QUERY_PREFIXES = setOf(
        "x-amz-",
        "x-goog-",
    )
    private val TRANSIENT_SCHEMES = setOf("blob", "data", "fd", "fdclose", "edl", "memory", "lavf", "ytdl")
    private const val MAX_HEADER_VALUE_LENGTH = 8_192
}

internal object SceneFfmpegArguments {
    fun av1MediaCodecPackets(
        input: SceneVideoInputSpec,
        acquiredInputValue: String,
        range: SceneTimeRange,
        outputFile: String,
        encoderName: String,
        contentSize: SceneVideoDimensions,
        outputSize: SceneVideoDimensions,
        tlsCaFile: String? = null,
    ): Array<String> {
        require(encoderName.isNotBlank()) { "AV1 encoder name must not be blank" }
        return buildList {
            addInputOptions(input, tlsCaFile)
            add("-ss")
            add(range.startSeconds.toFfmpegSeconds())
            add("-i")
            add(acquiredInputValue)
            add("-map")
            add(input.videoMapSelector())
            add("-an")
            add("-sn")
            add("-dn")
            add("-t")
            add(range.durationSeconds.toFfmpegSeconds())
            add("-vf")
            add(frameFilter(contentSize, outputSize))
            add("-frames:v")
            add(MAX_FRAME_COUNT.toString())
            add("-c:v")
            add("av1_mediacodec")
            add("-codec_name")
            add(encoderName)
            add("-bitrate_mode")
            add("cq")
            add("-global_quality")
            add("35")
            add("-ndk_codec")
            add("1")
            add("-pix_fmt")
            add("yuv420p")
            add("-f")
            add("data")
            add("-y")
            add(outputFile)
        }.toTypedArray()
    }

    fun animatedAvifFromObu(
        inputFile: String,
        outputFile: String,
    ): Array<String> {
        return arrayOf(
            "-f",
            "obu",
            "-framerate",
            FRAME_RATE.toInt().toString(),
            "-i",
            inputFile,
            "-map",
            "0:v:0",
            "-c:v",
            "copy",
            "-loop",
            "0",
            "-f",
            "avif",
            "-y",
            outputFile,
        )
    }

    fun videoProbe(
        input: SceneVideoInputSpec,
        acquiredInputValue: String,
        tlsCaFile: String? = null,
    ): Array<String> {
        return buildList {
            addInputOptions(input, tlsCaFile)
            add("-v")
            add("error")
            add("-select_streams")
            add(input.videoProbeSelector())
            add("-show_entries")
            add(
                "stream=width,height,sample_aspect_ratio,pix_fmt,color_transfer,color_primaries," +
                    "bits_per_raw_sample,profile:stream_side_data",
            )
            add("-of")
            add("default=noprint_wrappers=1")
            add(acquiredInputValue)
        }.toTypedArray()
    }

    fun audioProbe(
        input: SceneVideoInputSpec,
        acquiredInputValue: String,
        tlsCaFile: String? = null,
    ): Array<String> {
        return buildList {
            addInputOptions(input, tlsCaFile)
            add("-v")
            add("error")
            add("-select_streams")
            add(input.audioProbeSelector())
            add("-show_entries")
            add("stream=codec_type,codec_name:stream_side_data")
            add("-of")
            add("default=noprint_wrappers=1")
            add(acquiredInputValue)
        }.toTypedArray()
    }

    fun sentenceAudio(
        input: SceneVideoInputSpec,
        acquiredInputValue: String,
        range: SceneTimeRange,
        outputFile: String,
        tlsCaFile: String? = null,
    ): Array<String> {
        return buildList {
            addInputOptions(input, tlsCaFile)
            add("-ss")
            add(range.startSeconds.toFfmpegSeconds())
            add("-i")
            add(acquiredInputValue)
            add("-map")
            add(input.audioSelector())
            add("-vn")
            add("-sn")
            add("-dn")
            add("-t")
            add(range.durationSeconds.toFfmpegSeconds())
            add("-c:a")
            add("aac")
            add("-b:a")
            add("128k")
            add("-y")
            add(outputFile)
        }.toTypedArray()
    }

    private fun MutableList<String>.addInputOptions(
        input: SceneVideoInputSpec,
        tlsCaFile: String?,
    ) {
        add("-codec_whitelist")
        add(ALLOWED_INPUT_DECODERS)
        if (input.kind == SceneVideoInputKind.REMOTE_HTTP) {
            require(!tlsCaFile.isNullOrBlank()) { "Remote scene input requires a CA bundle" }
            add("-tls_verify")
            add("1")
            add("-ca_file")
            add(tlsCaFile)
            add("-protocol_whitelist")
            add(REMOTE_PROTOCOLS)
            add("-rw_timeout")
            add(REMOTE_IO_TIMEOUT_MICROSECONDS)
        }
        if (input.headers.isNotEmpty()) {
            add("-headers")
            add(input.headers.joinToString(separator = "") { (name, value) -> "$name: $value\r\n" })
        }
    }

    private fun SceneVideoInputSpec.videoMapSelector(): String {
        return videoStreamIndex?.let { "0:$it" } ?: "0:v:0"
    }

    private fun SceneVideoInputSpec.videoProbeSelector(): String {
        return videoStreamIndex?.toString() ?: "v:0"
    }

    private fun SceneVideoInputSpec.audioSelector(): String {
        return audioStreamIndex?.let { "0:$it" } ?: "0:a:0"
    }

    private fun SceneVideoInputSpec.audioProbeSelector(): String {
        return audioStreamIndex?.toString() ?: "a:0"
    }

    private fun Double.toFfmpegSeconds(): String {
        return String.format(Locale.ROOT, "%.6f", this).trimEnd('0').trimEnd('.')
    }

    internal fun frameFilter(
        contentSize: SceneVideoDimensions,
        outputSize: SceneVideoDimensions,
    ): String {
        require(
            outputSize.width in SCENE_PIXEL_ALIGNMENT..SCENE_MAX_OUTPUT_DIMENSION &&
                outputSize.height in SCENE_PIXEL_ALIGNMENT..SCENE_MAX_OUTPUT_DIMENSION &&
                outputSize.width % SCENE_PIXEL_ALIGNMENT == 0 &&
                outputSize.height % SCENE_PIXEL_ALIGNMENT == 0,
        ) {
            "Scene output size must be even and no larger than $SCENE_MAX_OUTPUT_DIMENSION"
        }
        require(
            contentSize.width in SCENE_PIXEL_ALIGNMENT..outputSize.width &&
                contentSize.height in SCENE_PIXEL_ALIGNMENT..outputSize.height &&
                contentSize.width % SCENE_PIXEL_ALIGNMENT == 0 &&
                contentSize.height % SCENE_PIXEL_ALIGNMENT == 0,
        ) {
            "Scene content size must be even and fit inside the output"
        }
        return buildList {
            add("fps=8")
            add("scale=w=${contentSize.width}:h=${contentSize.height}")
            add("setsar=1")
            if (contentSize != outputSize) {
                val horizontalGap = outputSize.width - contentSize.width
                val verticalGap = outputSize.height - contentSize.height
                add(
                    "pad=w=${outputSize.width}:h=${outputSize.height}:" +
                        "x=${horizontalGap.centeredChromaOffset()}:" +
                        "y=${verticalGap.centeredChromaOffset()}:color=black",
                )
            }
        }.joinToString(separator = ",")
    }

    private fun Int.centeredChromaOffset(): Int {
        return (this / 2).let { center -> center - (center % SCENE_PIXEL_ALIGNMENT) }
    }

    internal const val FRAME_RATE = SCENE_FRAME_RATE
    internal const val MAX_FRAME_COUNT = 80
    private const val REMOTE_PROTOCOLS = "http,https,tls,tcp,crypto"
    private const val REMOTE_IO_TIMEOUT_MICROSECONDS = "15000000"
    internal const val ALLOWED_INPUT_DECODERS =
        "aac,ac3,alac,av1,dca,eac3,ffv1,flac,h263,h264,hevc,libdav1d,mjpeg,mov_text,mp3,mp3float," +
            "mpeg1video,mpeg2video,mpeg4,opus,pcm_f32le,pcm_s16le,pcm_s24le,pcm_s32le,png,prores," +
            "theora,truehd,vorbis,vp8,vp9"
}
