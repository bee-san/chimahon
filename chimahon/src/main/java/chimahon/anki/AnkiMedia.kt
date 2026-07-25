package chimahon.anki

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class AnkiScreenshotMode(val storageValue: String) {
    FULL("full"),
    CROP("crop"),
    NONE("no_screenshot"),
    ANIMATED_SCENE("animated_scene"),
    ;

    companion object {
        fun fromStorageValue(value: String): AnkiScreenshotMode {
            return entries.firstOrNull { it.storageValue == value } ?: FULL
        }
    }
}

enum class AnkiMediaFileOwnership {
    CALLER_RETAINS,
    DELETE_AFTER_STORE_ATTEMPT,
}

sealed interface AnkiMediaSource {
    val preferredBaseName: String
    val extension: String

    data class Bytes(
        val data: ByteArray,
        override val preferredBaseName: String,
        override val extension: String,
    ) : AnkiMediaSource

    data class FileSource(
        val file: File,
        override val preferredBaseName: String,
        override val extension: String,
        val ownership: AnkiMediaFileOwnership,
    ) : AnkiMediaSource
}

enum class AnkiUnsupportedVideoReason {
    NON_SEEKABLE,
    HDR_OR_TEN_BIT,
    TORRENT,
    ENCRYPTED,
    DRM,
    UNSAFE_INPUT,
    UNAVAILABLE_CONTENT_URI,
    UNSUPPORTED_INPUT,
}

sealed interface AnkiMediaWarning {
    data class UnsupportedVideo(val reason: AnkiUnsupportedVideoReason) : AnkiMediaWarning
    data object SceneGenerationFailed : AnkiMediaWarning
    data object AnimatedStorageFailed : AnkiMediaWarning
    data object StillStorageFailed : AnkiMediaWarning
    data class PossibleOrphanedMedia(val count: Int) : AnkiMediaWarning
}

sealed interface AnkiScreenshotPreparation {
    data class Animated(
        val animation: AnkiMediaSource.FileSource,
        val stillFallback: AnkiMediaSource.Bytes?,
    ) : AnkiScreenshotPreparation

    data class Still(
        val still: AnkiMediaSource.Bytes?,
    ) : AnkiScreenshotPreparation

    data class ExpectedNonVideo(
        val still: AnkiMediaSource.Bytes?,
    ) : AnkiScreenshotPreparation

    data class UnsupportedVideo(
        val reason: AnkiUnsupportedVideoReason,
        val stillFallback: AnkiMediaSource.Bytes?,
    ) : AnkiScreenshotPreparation

    data class GenerationFailed(
        val stillFallback: AnkiMediaSource.Bytes?,
    ) : AnkiScreenshotPreparation

    data object Cancelled : AnkiScreenshotPreparation
}

fun interface LazyAnkiScreenshotProvider {
    suspend fun prepare(): AnkiScreenshotPreparation
}

fun interface LazyAnkiMediaProvider {
    suspend fun prepare(): AnkiMediaSource?
}

data class AnkiMediaRequest(
    val screenshotMode: AnkiScreenshotMode,
    val screenshotProvider: LazyAnkiScreenshotProvider? = null,
    val sentenceAudioProvider: LazyAnkiMediaProvider? = null,
    val onCommitStarted: () -> Unit = {},
    val onFinished: () -> Unit = {},
) {
    private val finished = AtomicBoolean(false)

    /**
     * Releases app-owned capture state exactly once, including when the final
     * duplicate decision never invokes either lazy provider.
     */
    fun finish() {
        if (finished.compareAndSet(false, true)) {
            onFinished()
        }
    }
}

internal data class StoredScreenshotMedia(
    val filename: String?,
    val warnings: List<AnkiMediaWarning>,
)

internal class AnkiScreenshotMediaCommitter(
    private val store: suspend (AnkiMediaSource) -> String,
) {
    suspend fun store(preparation: AnkiScreenshotPreparation?): StoredScreenshotMedia {
        return when (preparation) {
            null,
            AnkiScreenshotPreparation.Cancelled,
            -> StoredScreenshotMedia(filename = null, warnings = emptyList())

            is AnkiScreenshotPreparation.Animated -> storeAnimated(preparation)

            is AnkiScreenshotPreparation.Still -> storeStill(
                still = preparation.still,
                warnings = emptyList(),
            )

            is AnkiScreenshotPreparation.ExpectedNonVideo -> storeStill(
                still = preparation.still,
                warnings = emptyList(),
            )

            is AnkiScreenshotPreparation.UnsupportedVideo -> storeStill(
                still = preparation.stillFallback,
                warnings = listOf(AnkiMediaWarning.UnsupportedVideo(preparation.reason)),
            )

            is AnkiScreenshotPreparation.GenerationFailed -> storeStill(
                still = preparation.stillFallback,
                warnings = listOf(AnkiMediaWarning.SceneGenerationFailed),
            )
        }
    }

    private suspend fun storeAnimated(
        preparation: AnkiScreenshotPreparation.Animated,
    ): StoredScreenshotMedia {
        return try {
            StoredScreenshotMedia(
                filename = store(preparation.animation),
                warnings = emptyList(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            storeStill(
                still = preparation.stillFallback,
                warnings = listOf(AnkiMediaWarning.AnimatedStorageFailed),
            )
        }
    }

    private suspend fun storeStill(
        still: AnkiMediaSource.Bytes?,
        warnings: List<AnkiMediaWarning>,
    ): StoredScreenshotMedia {
        if (still == null) {
            return StoredScreenshotMedia(filename = null, warnings = warnings)
        }
        return try {
            StoredScreenshotMedia(
                filename = store(still),
                warnings = warnings,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            StoredScreenshotMedia(
                filename = null,
                warnings = warnings + AnkiMediaWarning.StillStorageFailed,
            )
        }
    }
}

internal fun AnkiScreenshotPreparation.ownedFileSources(): List<AnkiMediaSource.FileSource> {
    return when (this) {
        is AnkiScreenshotPreparation.Animated -> listOf(animation)
        else -> emptyList()
    }
}

internal fun AnkiMediaSource.releaseOwnedFile() {
    if (
        this is AnkiMediaSource.FileSource &&
        ownership == AnkiMediaFileOwnership.DELETE_AFTER_STORE_ATTEMPT
    ) {
        file.delete()
    }
}

object AnkiMediaNaming {
    suspend fun sceneFileSource(
        file: File,
        ownership: AnkiMediaFileOwnership = AnkiMediaFileOwnership.DELETE_AFTER_STORE_ATTEMPT,
    ): AnkiMediaSource.FileSource {
        val digest = sha256(file)
        return AnkiMediaSource.FileSource(
            file = file,
            preferredBaseName = "chimahon_scene_$digest",
            extension = "webp",
            ownership = ownership,
        )
    }

    suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        require(file.isFile && file.canRead()) { "Media file is not readable" }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHex()
    }

    fun sha256(data: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(data).toHex()
    }

    fun safeExtension(value: String, fallback: String): String {
        return value
            .substringBefore('?')
            .substringAfterLast('.', value)
            .replace(Regex("[^A-Za-z0-9]"), "")
            .ifBlank { fallback }
            .lowercase(Locale.ROOT)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
