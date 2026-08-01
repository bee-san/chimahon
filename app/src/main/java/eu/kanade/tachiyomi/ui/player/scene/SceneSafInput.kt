package eu.kanade.tachiyomi.ui.player.scene

import android.net.Uri
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Keeps a content URI out of FFmpeg arguments until they reach the process that owns FFmpegKit.
 */
internal object SceneSafInput {
    private const val READ_PREFIX = "chimahon-saf-read:"

    fun encodeForRead(uri: Uri): String {
        require(uri.scheme.equals("content", ignoreCase = true))
        val encoded = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(uri.toString().toByteArray(StandardCharsets.UTF_8))
        return READ_PREFIX + encoded
    }

    fun decodeForRead(value: String): Uri? {
        if (!value.startsWith(READ_PREFIX)) return null
        val encoded = value.removePrefix(READ_PREFIX)
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
        }.getOrNull() ?: return null
        return Uri.parse(decoded)
            .takeIf { it.scheme.equals("content", ignoreCase = true) }
    }

    fun isReadToken(value: String): Boolean = value.startsWith(READ_PREFIX)
}
