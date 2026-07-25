package eu.kanade.tachiyomi.ui.player.scene

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

internal object SceneCaptureFiles {
    suspend fun sha256(file: File): String {
        require(file.isFile && file.canRead()) { "Scene output is not readable" }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            String.format(Locale.ROOT, "%02x", byte)
        }
    }

    fun atomicallyFinalize(
        source: File,
        outputDirectory: File,
        jobId: String,
        digest: String,
    ): SceneCapturedFile {
        require(source.isFile) { "Scene output is missing" }
        check(outputDirectory.mkdirs() || outputDirectory.isDirectory) {
            "Unable to create scene output directory"
        }
        val safeJobId = jobId.replace(UNSAFE_FILE_CHARACTERS, "_").take(64)
        val preferredBaseName = "chimahon_scene_$digest"
        val target = File(outputDirectory, "${safeJobId}_$preferredBaseName.webp")
        if (target.exists()) {
            throw FileAlreadyExistsException(target.absolutePath)
        }
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (throwable: Throwable) {
            // An implementation may report an I/O error after the rename became visible. Delete
            // only when our source disappeared, which avoids touching a pre-existing collision.
            if (!source.exists()) {
                target.delete()
            }
            throw throwable
        }
        return SceneCapturedFile(
            file = target,
            digest = digest,
            preferredBaseName = preferredBaseName,
        )
    }

    private val UNSAFE_FILE_CHARACTERS = Regex("[^A-Za-z0-9_-]")
}
