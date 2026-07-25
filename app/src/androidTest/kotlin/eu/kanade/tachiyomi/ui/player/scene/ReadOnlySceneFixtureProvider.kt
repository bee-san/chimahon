package eu.kanade.tachiyomi.ui.player.scene

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Exposes the licensed scene fixture through a seekable descriptor while rejecting every write
 * mode. This exercises FFmpegKit's real read-only SAF/content-URI path rather than wrapping a
 * caller-writable cache file in the production FileProvider.
 */
class ReadOnlySceneFixtureProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "video/mp4"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        requireFixtureUri(uri)
        val columns = projection
            ?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
            ?.toTypedArray()
            .orEmpty()
            .ifEmpty { arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE) }
        return MatrixCursor(columns, 1).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> FIXTURE_ASSET
                        OpenableColumns.SIZE -> fixtureFile().length()
                        else -> null
                    }
                },
            )
        }
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        requireFixtureUri(uri)
        require(mode == "r") { "Scene fixture is read-only" }
        return ParcelFileDescriptor.open(fixtureFile(), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri = throw UnsupportedOperationException("Scene fixture is read-only")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Scene fixture is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Scene fixture is read-only")

    private fun requireFixtureUri(uri: Uri) {
        require(uri.pathSegments.singleOrNull() == FIXTURE_ASSET) {
            "Unknown scene fixture"
        }
    }

    private fun fixtureFile(): File {
        val providerContext = requireNotNull(context)
        val file = File(providerContext.cacheDir, FIXTURE_ASSET)
        if (!file.isFile || file.length() <= 0L) {
            providerContext.assets.open(FIXTURE_ASSET).use { input ->
                file.outputStream().use(input::copyTo)
            }
        }
        return file
    }

    companion object {
        const val FIXTURE_ASSET = "scene_capture_rotated_sdr.mp4"
    }
}
