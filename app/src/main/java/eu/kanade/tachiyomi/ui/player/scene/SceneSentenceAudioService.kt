package eu.kanade.tachiyomi.ui.player.scene

import android.content.Context
import android.net.Uri
import chimahon.anki.AnkiMediaFileOwnership
import chimahon.anki.AnkiMediaNaming
import chimahon.anki.AnkiMediaSource
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.LogRedirectionStrategy
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun interface SceneSentenceAudioService {
    suspend fun prepare(request: SceneCaptureRequest): AnkiMediaSource?
}

internal fun interface PlayerFfmpegSessionExecutor {
    suspend fun execute(
        arguments: Array<String>,
        onCancellationDeferred: () -> Unit,
        onCancelledSessionFinished: () -> Unit,
    ): Boolean
}

internal object FfmpegKitPlayerSessionExecutor : PlayerFfmpegSessionExecutor {
    override suspend fun execute(
        arguments: Array<String>,
        onCancellationDeferred: () -> Unit,
        onCancelledSessionFinished: () -> Unit,
    ): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val cancellationRequested = AtomicBoolean(false)
            val cancellationCleanupFinished = AtomicBoolean(false)
            val sessionCompleted = AtomicBoolean(false)
            val terminal = AtomicBoolean(false)
            val sessionReference = AtomicReference<FFmpegSession?>()
            val startGate = Any()
            var sessionStarted = false

            fun finishCancelledSession() {
                if (
                    cancellationRequested.get() &&
                    cancellationCleanupFinished.compareAndSet(false, true)
                ) {
                    runCatching(onCancelledSessionFinished)
                }
            }

            fun requestCancellation() {
                if (cancellationRequested.compareAndSet(false, true)) {
                    runCatching(onCancellationDeferred)
                }
                terminal.set(true)
                synchronized(startGate) {
                    val session = sessionReference.get()
                    if (sessionCompleted.get() || !sessionStarted || session == null) {
                        finishCancelledSession()
                    } else {
                        runCatching(session::cancel)
                    }
                }
            }

            continuation.invokeOnCancellation { requestCancellation() }

            try {
                val session = FFmpegSession.create(
                    arguments,
                    { returnedSession ->
                        sessionCompleted.set(true)
                        finishCancelledSession()
                        if (terminal.compareAndSet(false, true)) {
                            continuation.resume(ReturnCode.isSuccess(returnedSession.returnCode))
                        }
                    },
                    DISCARD_LOG_CALLBACK,
                    DISCARD_STATISTICS_CALLBACK,
                    LogRedirectionStrategy.NEVER_PRINT_LOGS,
                )
                sessionReference.set(session)
                synchronized(startGate) {
                    if (!continuation.isActive || cancellationRequested.get()) {
                        runCatching(session::cancel)
                        finishCancelledSession()
                    } else {
                        sessionStarted = true
                        try {
                            FFmpegKitConfig.asyncFFmpegExecute(session)
                        } catch (e: Exception) {
                            sessionCompleted.set(true)
                            runCatching(session::cancel)
                            finishCancelledSession()
                            if (terminal.compareAndSet(false, true)) {
                                continuation.resumeWithException(e)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                requestCancellation()
                if (terminal.compareAndSet(false, true)) {
                    continuation.resumeWithException(e)
                }
            } catch (e: Exception) {
                sessionCompleted.set(true)
                finishCancelledSession()
                if (terminal.compareAndSet(false, true)) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    private val DISCARD_LOG_CALLBACK = LogCallback {}
    private val DISCARD_STATISTICS_CALLBACK = StatisticsCallback {}
}

/**
 * Extracts sentence audio exclusively from values already frozen in [SceneCaptureRequest].
 */
internal class FrozenSceneSentenceAudioService(
    context: Context,
    private val executor: PlayerFfmpegSessionExecutor = FfmpegKitPlayerSessionExecutor,
    private val protectionChecker: SceneInputProtectionChecker = AndroidSceneInputProtectionChecker(context),
) : SceneSentenceAudioService {
    private val applicationContext = context.applicationContext

    override suspend fun prepare(request: SceneCaptureRequest): AnkiMediaSource? {
        val input = (request.videoInput as? SceneVideoInputResolution.Supported)?.input ?: return null
        val range = request.resolvedTiming?.audioRange ?: return null
        if (request.selectedExternalAudioRequired && input.externalAudioValue == null) return null
        val externalAudioSelected = input.externalAudioValue != null
        val rawInput = input.externalAudioValue ?: input.value
        val protectedInput = selectedAudioInput(
            input = input,
            rawInput = rawInput,
            externalAudioSelected = externalAudioSelected,
        ) ?: return null
        val selectedAudioFfmpegIndex = if (input.externalAudioValue == null) {
            request.selectedAudioFfmpegIndex ?: return null
        } else {
            null
        }
        val jobDirectory = createJobDirectory() ?: return null
        val outputReference = AtomicReference<File?>()
        val cleanupDeferred = AtomicBoolean(false)
        val cleaned = AtomicBoolean(false)
        var transferred = false

        val cleanupAll: () -> Unit = {
            if (cleaned.compareAndSet(false, true)) {
                runCatching { outputReference.get()?.delete() }
                runCatching { jobDirectory.deleteRecursively() }
            }
        }

        try {
            val frozenInput = when (
                val protection = protectionChecker.check(
                    protectedInput,
                    File(jobDirectory, "checked_input"),
                )
            ) {
                is SceneInputProtectionResult.Clear -> protection.input
                is SceneInputProtectionResult.Protected,
                SceneInputProtectionResult.Unavailable,
                -> return null
            }
            val output = withContext(Dispatchers.IO) {
                File.createTempFile("chimahon_sentence_audio_", ".m4a", applicationContext.cacheDir)
            }
            outputReference.set(output)
            val acquiredInput = try {
                withContext(Dispatchers.IO) {
                    acquireInput(frozenInput.value)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return null
            val arguments = SceneSentenceAudioArguments.build(
                input = frozenInput,
                acquiredInput = acquiredInput,
                range = range,
                externalAudioSelected = externalAudioSelected,
                selectedAudioFfmpegIndex = selectedAudioFfmpegIndex,
                output = output,
            )
            val execution = withTimeoutOrNull(AUDIO_EXTRACTION_TIMEOUT_MILLIS) {
                executor.execute(
                    arguments = arguments,
                    onCancellationDeferred = { cleanupDeferred.set(true) },
                    onCancelledSessionFinished = cleanupAll,
                )
            }
            val succeeded = execution ?: return null
            cleanupDeferred.set(false)
            if (!succeeded) return null
            if (!output.isFile || output.length() <= 0L) return null

            val digest = AnkiMediaNaming.sha256(output)
            if (!runCatching { jobDirectory.deleteRecursively() }.getOrDefault(false)) return null
            transferred = true
            return AnkiMediaSource.FileSource(
                file = output,
                preferredBaseName = "chimahon_sentence_$digest",
                extension = "m4a",
                ownership = AnkiMediaFileOwnership.DELETE_AFTER_STORE_ATTEMPT,
            )
        } finally {
            if (!transferred && !cleanupDeferred.get()) {
                cleanupAll()
            }
        }
    }

    private suspend fun createJobDirectory(): File? = withContext(Dispatchers.IO) {
        val cacheRoot = applicationContext.cacheDir
        val root = File(cacheRoot, SceneStaleJobRoot.SENTENCE_AUDIO.directoryName)
        if (!root.mkdirs() && !root.isDirectory) return@withContext null
        val jobId = UUID.randomUUID().toString()
        SceneStaleJobCleaner.clean(
            cacheRoot = cacheRoot,
            jobRoot = SceneStaleJobRoot.SENTENCE_AUDIO,
            excludedChildName = jobId,
        )
        currentCoroutineContext().ensureActive()
        val directory = File(root, jobId)
        directory.takeIf { it.mkdir() }
    }

    private fun acquireInput(value: String): String? {
        return when {
            value.startsWith("content://", ignoreCase = true) -> {
                FFmpegKitConfig.getSafParameterForRead(applicationContext, Uri.parse(value))
                    ?.takeIf(String::isNotBlank)
            }
            value.startsWith("file://", ignoreCase = true) -> {
                Uri.parse(value).path?.takeIf(String::isNotBlank)
            }
            value.startsWith("/") ||
                value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true) -> value
            else -> null
        }
    }

    private fun String.sceneInputKind(): SceneVideoInputKind? {
        return when {
            startsWith("content://", ignoreCase = true) -> SceneVideoInputKind.CONTENT_URI
            startsWith("file://", ignoreCase = true) ||
                startsWith("/") -> SceneVideoInputKind.LOCAL_FILE
            startsWith("http://", ignoreCase = true) ||
                startsWith("https://", ignoreCase = true) -> SceneVideoInputKind.REMOTE_HTTP
            else -> null
        }
    }

    private fun selectedAudioInput(
        input: SceneVideoInputSpec,
        rawInput: String,
        externalAudioSelected: Boolean,
    ): SceneVideoInputSpec? {
        val kind = rawInput.sceneInputKind() ?: return null
        val crossOriginExternalAudio = externalAudioSelected &&
            !SceneInputOriginPolicy.hasSameHttpOrigin(input.value, rawInput)
        if (crossOriginExternalAudio) {
            if (SceneInputOriginPolicy.hasAnyHeadersOrReferer(input.headers, input.inputOptions)) {
                return null
            }
            if (
                input.inputOptions.any {
                    it.name.lowercase(Locale.ROOT) !in CROSS_ORIGIN_SAFE_AUDIO_OPTIONS
                }
            ) {
                return null
            }
        }
        return input.copy(
            value = rawInput,
            kind = kind,
            headers = if (crossOriginExternalAudio) emptyList() else input.headers,
            inputOptions = if (crossOriginExternalAudio) {
                input.inputOptions.filter {
                    it.name.lowercase(Locale.ROOT) in CROSS_ORIGIN_SAFE_AUDIO_OPTIONS
                }
            } else {
                input.inputOptions
            },
            externalAudioValue = null,
        )
    }

    private companion object {
        const val AUDIO_EXTRACTION_TIMEOUT_MILLIS = 60_000L
        val CROSS_ORIGIN_SAFE_AUDIO_OPTIONS = setOf(
            "analyzeduration",
            "http_persistent",
            "icy",
            "multiple_requests",
            "probesize",
            "reconnect",
            "reconnect_at_eof",
            "reconnect_delay_max",
            "reconnect_streamed",
            "rw_timeout",
            "seekable",
            "timeout",
            "tls_verify",
            "user_agent",
        )
    }
}

internal object SceneSentenceAudioArguments {
    private const val AUDIO_BITRATE = "128k"

    fun build(
        input: SceneVideoInputSpec,
        acquiredInput: String,
        range: SceneTimeRange,
        externalAudioSelected: Boolean,
        selectedAudioFfmpegIndex: Int?,
        output: File,
    ): Array<String> {
        val selectedAudioMap = if (externalAudioSelected) {
            "0:a:0"
        } else {
            "0:${requireNotNull(selectedAudioFfmpegIndex)}"
        }
        return buildList {
            input.inputOptions.forEach { option ->
                add("-${option.name}")
                add(option.value)
            }
            if (input.headers.isNotEmpty()) {
                add("-headers")
                add(
                    input.headers.joinToString(separator = "") { (name, value) ->
                        "$name: $value\r\n"
                    },
                )
            }
            add("-ss")
            add(range.startSeconds.toFfmpegSeconds())
            add("-i")
            add(acquiredInput)
            add("-t")
            add(range.durationSeconds.toFfmpegSeconds())
            add("-vn")
            add("-map")
            add(selectedAudioMap)
            add("-c:a")
            add("aac")
            add("-b:a")
            add(AUDIO_BITRATE)
            add("-y")
            add(output.absolutePath)
        }.toTypedArray()
    }

    private fun Double.toFfmpegSeconds(): String {
        return String.format(Locale.ROOT, "%.6f", this)
            .trimEnd('0')
            .trimEnd('.')
    }
}
