package eu.kanade.tachiyomi.ui.player.scene

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class AndroidSceneWebpFrameEncoder : SceneWebpFrameEncoder {
    override suspend fun encode(
        pngFile: File,
        webpFile: File,
    ): Boolean = withContext(Dispatchers.IO) {
        val bitmap = BitmapFactory.decodeFile(
            pngFile.absolutePath,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return@withContext false
        var encodedFile: File? = null

        try {
            val temporaryFile = File.createTempFile(
                "${webpFile.name}.",
                ".encoded",
                webpFile.parentFile,
            )
            encodedFile = temporaryFile
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            val encoded = temporaryFile.outputStream().buffered().use { output ->
                bitmap.compress(format, WEBP_QUALITY, output)
            }
            encoded &&
                temporaryFile.isFile &&
                temporaryFile.length() > 0L &&
                StaticWebpFrameSanitizer.sanitize(temporaryFile, webpFile)
        } finally {
            bitmap.recycle()
            encodedFile?.delete()
            if (!webpFile.isFile || webpFile.length() <= 0L) {
                webpFile.delete()
            }
        }
    }

    private companion object {
        const val WEBP_QUALITY = 60
    }
}
