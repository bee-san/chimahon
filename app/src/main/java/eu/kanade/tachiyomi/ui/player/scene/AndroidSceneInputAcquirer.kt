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

    private fun acquireContentUri(value: String): SceneInputLease? {
        val descriptor = runCatching {
            applicationContext.contentResolver.openFileDescriptor(Uri.parse(value), "r")
        }.getOrNull() ?: return null
        return object : SceneInputLease {
            override val ffmpegValue = "/proc/self/fd/${descriptor.fd}"
            override val tlsCaFile: String? = null

            override fun close() = descriptor.close()
        }
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
