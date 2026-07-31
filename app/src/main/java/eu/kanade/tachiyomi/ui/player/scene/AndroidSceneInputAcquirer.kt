package eu.kanade.tachiyomi.ui.player.scene

import android.content.Context
import android.net.Uri
import java.io.Closeable
import java.io.File

internal interface SceneInputLease : Closeable {
    val ffmpegValue: String
    val tlsCaFile: String?
}

internal fun interface SceneInputAcquirer {
    suspend fun acquire(input: SceneVideoInputSpec): SceneInputLease?
}

internal class AndroidSceneInputAcquirer(
    context: Context,
) : SceneInputAcquirer {
    private val applicationContext = context.applicationContext
    private val caBundle = File(applicationContext.filesDir, "cacert.pem")

    override suspend fun acquire(input: SceneVideoInputSpec): SceneInputLease? {
        return when (input.kind) {
            SceneVideoInputKind.CONTENT_URI -> acquireContentUri(input.value)
            SceneVideoInputKind.REMOTE_HTTP -> {
                val readableBundle = getCaBundle() ?: return null
                acquired(input.value, readableBundle.absolutePath)
            }
            SceneVideoInputKind.LOCAL_FILE -> acquired(input.value)
        }
    }

    private fun getCaBundle(): File? = synchronized(CA_BUNDLE_LOCK) {
        caBundle.takeIf { it.isFile && it.canRead() && it.length() > 0L } ?: runCatching {
            applicationContext.assets.open("cacert.pem").use { input ->
                caBundle.outputStream().use(input::copyTo)
            }
            caBundle.takeIf { it.isFile && it.canRead() && it.length() > 0L }
        }.getOrNull()
    }

    /**
     * FFmpeg must not be handed a `/proc/self/fd/N` path for a SAF document. Although FFmpegKit
     * runs in this process and so shares the descriptor table, opening that symlink by path
     * re-resolves to the real file and re-checks permissions against it. Shared storage is
     * FUSE-backed and `media_rw`-owned, and the SAF grant attaches to the descriptor rather than
     * to the path, so the reopen fails with `EACCES` and the probe rejects a perfectly good file.
     *
     * FFmpegKit's `saf:` protocol exists for this: it retains the [Uri] and opens the descriptor
     * from inside the native handler, so the grant still applies. The URI is encoded here and
     * registered only inside the dedicated FFmpegKit process.
     */
    private fun acquireContentUri(value: String): SceneInputLease? {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: run {
            sceneLog { "acquire: could not parse content uri" }
            return null
        }
        return acquired(SceneSafInput.encodeForRead(uri))
    }

    private fun acquired(
        value: String,
        caFile: String? = null,
    ): SceneInputLease {
        return object : SceneInputLease {
            override val ffmpegValue = value
            override val tlsCaFile = caFile

            override fun close() = Unit
        }
    }

    private companion object {
        val CA_BUNDLE_LOCK = Any()
    }
}
