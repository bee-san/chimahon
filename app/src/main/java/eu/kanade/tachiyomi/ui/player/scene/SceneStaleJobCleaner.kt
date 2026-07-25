package eu.kanade.tachiyomi.ui.player.scene

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal enum class SceneStaleJobRoot(
    val directoryName: String,
) {
    CAPTURE("scene_capture_jobs"),
    SENTENCE_AUDIO("scene_sentence_audio_jobs"),
}

internal data class SceneStaleJobCleanupLimits(
    val maxChildrenScanned: Int = 64,
    val maxJobDirectoriesDeleted: Int = 8,
    val maxTreeEntriesVisited: Int = 4_096,
) {
    init {
        require(maxChildrenScanned > 0)
        require(maxJobDirectoriesDeleted > 0)
        require(maxTreeEntriesVisited > 0)
    }
}

internal data class SceneStaleJobCleanupResult(
    val childrenScanned: Int,
    val jobDirectoriesDeleted: Int,
    val treeEntriesVisited: Int,
)

internal object SceneStaleJobCleaner {
    internal const val DEFAULT_STALE_AFTER_MILLIS = 24L * 60L * 60L * 1_000L

    suspend fun clean(
        cacheRoot: File,
        jobRoot: SceneStaleJobRoot,
        excludedChildName: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
        staleAfterMillis: Long = DEFAULT_STALE_AFTER_MILLIS,
        limits: SceneStaleJobCleanupLimits = SceneStaleJobCleanupLimits(),
    ): SceneStaleJobCleanupResult {
        require(nowMillis >= 0L)
        require(staleAfterMillis > 0L)

        val coroutineContext = currentCoroutineContext()
        coroutineContext.ensureActive()
        val emptyResult = SceneStaleJobCleanupResult(
            childrenScanned = 0,
            jobDirectoriesDeleted = 0,
            treeEntriesVisited = 0,
        )
        val cachePath = cacheRoot.toPath()
        if (
            Files.isSymbolicLink(cachePath) ||
            !Files.isDirectory(cachePath, LinkOption.NOFOLLOW_LINKS)
        ) {
            return emptyResult
        }
        val jobsPath = cachePath.resolve(jobRoot.directoryName)
        if (
            Files.isSymbolicLink(jobsPath) ||
            !Files.isDirectory(jobsPath, LinkOption.NOFOLLOW_LINKS)
        ) {
            return emptyResult
        }

        val cutoffMillis = if (nowMillis < staleAfterMillis) {
            Long.MIN_VALUE
        } else {
            nowMillis - staleAfterMillis
        }
        var childrenScanned = 0
        var jobDirectoriesDeleted = 0
        val visitBudget = TreeVisitBudget()

        try {
            Files.newDirectoryStream(jobsPath).use { children ->
                val iterator = children.iterator()
                while (
                    childrenScanned < limits.maxChildrenScanned &&
                    jobDirectoriesDeleted < limits.maxJobDirectoriesDeleted &&
                    visitBudget.entriesVisited < limits.maxTreeEntriesVisited &&
                    iterator.hasNext()
                ) {
                    coroutineContext.ensureActive()
                    val child = iterator.next()
                    childrenScanned += 1
                    if (
                        child.fileName.toString() == excludedChildName ||
                        Files.isSymbolicLink(child) ||
                        !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        continue
                    }
                    val lastModifiedMillis = runCatching {
                        Files.getLastModifiedTime(child, LinkOption.NOFOLLOW_LINKS).toMillis()
                    }.getOrNull() ?: continue
                    if (lastModifiedMillis > cutoffMillis) {
                        continue
                    }
                    try {
                        if (
                            deleteTreeWithoutFollowingLinks(
                                root = child,
                                visitBudget = visitBudget,
                                maxTreeEntriesVisited = limits.maxTreeEntriesVisited,
                            )
                        ) {
                            jobDirectoriesDeleted += 1
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Best effort: a later job can retry this stale directory.
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Cleanup must not prevent the requested capture from starting.
        }

        return SceneStaleJobCleanupResult(
            childrenScanned = childrenScanned,
            jobDirectoriesDeleted = jobDirectoriesDeleted,
            treeEntriesVisited = visitBudget.entriesVisited,
        )
    }

    private suspend fun deleteTreeWithoutFollowingLinks(
        root: Path,
        visitBudget: TreeVisitBudget,
        maxTreeEntriesVisited: Int,
    ): Boolean {
        val coroutineContext = currentCoroutineContext()
        var traversalCompleted = true
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                private fun reserveVisit(): Boolean {
                    coroutineContext.ensureActive()
                    if (visitBudget.entriesVisited >= maxTreeEntriesVisited) {
                        traversalCompleted = false
                        return false
                    }
                    visitBudget.entriesVisited += 1
                    return true
                }

                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    return if (reserveVisit()) {
                        FileVisitResult.CONTINUE
                    } else {
                        FileVisitResult.TERMINATE
                    }
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (!reserveVisit()) {
                        return FileVisitResult.TERMINATE
                    }
                    if (
                        file == root ||
                        attrs.isSymbolicLink ||
                        Files.isSymbolicLink(file)
                    ) {
                        traversalCompleted = false
                        return FileVisitResult.CONTINUE
                    }
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    coroutineContext.ensureActive()
                    traversalCompleted = false
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    coroutineContext.ensureActive()
                    if (exc != null) {
                        traversalCompleted = false
                        return FileVisitResult.CONTINUE
                    }
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return traversalCompleted && !Files.exists(root, LinkOption.NOFOLLOW_LINKS)
    }

    private class TreeVisitBudget {
        var entriesVisited: Int = 0
    }
}
