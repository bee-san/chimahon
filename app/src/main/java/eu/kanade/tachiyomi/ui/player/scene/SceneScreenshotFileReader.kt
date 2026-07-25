package eu.kanade.tachiyomi.ui.player.scene

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Waits for an asynchronously written screenshot to stabilize before decoding it.
 *
 * A decoder can still reject a stable-but-incomplete file; that never ends the polling loop early.
 */
internal object SceneScreenshotFileReader {
    suspend fun <T> await(
        file: File,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        pollDelayMillis: Long = DEFAULT_POLL_DELAY_MILLIS,
        decoder: (File) -> T?,
    ): T? {
        require(maxAttempts > 0)
        require(pollDelayMillis >= 0L)

        var previousLength = -1L
        repeat(maxAttempts) { attempt ->
            currentCoroutineContext().ensureActive()
            val length = file.takeIf(File::isFile)?.length()?.coerceAtLeast(0L) ?: 0L
            if (length > 0L && length == previousLength) {
                decoder(file)?.let { return it }
            }
            previousLength = length
            if (attempt + 1 < maxAttempts) {
                delay(pollDelayMillis)
            }
        }

        currentCoroutineContext().ensureActive()
        return file
            .takeIf { it.isFile && it.length() > 0L }
            ?.let(decoder)
    }

    private const val DEFAULT_MAX_ATTEMPTS = 20
    private const val DEFAULT_POLL_DELAY_MILLIS = 25L
}
