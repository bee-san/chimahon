package eu.kanade.tachiyomi.ui.player.scene

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.FileTime

class SceneStaleJobCleanerTest {
    @TempDir
    lateinit var cacheRoot: File

    @Test
    fun `old jobs are removed while fresh current and unrelated directories remain`() = runTest {
        val jobsRoot = createJobsRoot(SceneStaleJobRoot.CAPTURE)
        val oldJob = createJob(jobsRoot, "old")
        val freshJob = createJob(jobsRoot, "fresh")
        val currentJob = createJob(jobsRoot, "current")
        val unrelatedJob = createJob(
            File(cacheRoot, "${SceneStaleJobRoot.CAPTURE.directoryName}_backup").apply {
                assertTrue(mkdir())
            },
            "old",
        )
        setModified(oldJob, OLD_MILLIS)
        setModified(freshJob, FRESH_MILLIS)
        setModified(currentJob, OLD_MILLIS)
        setModified(unrelatedJob, OLD_MILLIS)

        val result = SceneStaleJobCleaner.clean(
            cacheRoot = cacheRoot,
            jobRoot = SceneStaleJobRoot.CAPTURE,
            excludedChildName = currentJob.name,
            nowMillis = NOW_MILLIS,
            staleAfterMillis = STALE_AFTER_MILLIS,
        )

        assertFalse(oldJob.exists())
        assertTrue(freshJob.isDirectory)
        assertTrue(currentJob.isDirectory)
        assertTrue(unrelatedJob.isDirectory)
        assertEquals(1, result.jobDirectoriesDeleted)
    }

    @Test
    fun `symlink children and symlink job roots are never removed or followed`() = runTest {
        val jobsRoot = createJobsRoot(SceneStaleJobRoot.CAPTURE)
        val externalChildTarget = createJob(File(cacheRoot, "external-child").apply { assertTrue(mkdir()) }, "target")
        val childSentinel = File(externalChildTarget, "sentinel").apply { writeText("keep") }
        val childLink = jobsRoot.toPath().resolve("old-link")
        Files.createSymbolicLink(childLink, externalChildTarget.toPath())

        val externalRootTarget = File(cacheRoot, "external-root").apply { assertTrue(mkdir()) }
        val rootSentinel = File(externalRootTarget, "sentinel").apply { writeText("keep") }
        val rootLink = cacheRoot.toPath().resolve(SceneStaleJobRoot.SENTENCE_AUDIO.directoryName)
        Files.createSymbolicLink(rootLink, externalRootTarget.toPath())

        SceneStaleJobCleaner.clean(
            cacheRoot = cacheRoot,
            jobRoot = SceneStaleJobRoot.CAPTURE,
            nowMillis = NOW_MILLIS,
            staleAfterMillis = STALE_AFTER_MILLIS,
        )
        val rootResult = SceneStaleJobCleaner.clean(
            cacheRoot = cacheRoot,
            jobRoot = SceneStaleJobRoot.SENTENCE_AUDIO,
            nowMillis = NOW_MILLIS,
            staleAfterMillis = STALE_AFTER_MILLIS,
        )

        assertTrue(Files.exists(childLink, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isSymbolicLink(childLink))
        assertTrue(childSentinel.isFile)
        assertTrue(Files.exists(rootLink, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isSymbolicLink(rootLink))
        assertTrue(rootSentinel.isFile)
        assertEquals(0, rootResult.childrenScanned)
    }

    @Test
    fun `root scans and deletions stop at their configured bounds`() = runTest {
        val jobsRoot = createJobsRoot(SceneStaleJobRoot.CAPTURE)
        val jobs = List(10) { index ->
            createJob(jobsRoot, "job-$index").also { setModified(it, FRESH_MILLIS) }
        }
        val scanLimited = SceneStaleJobCleaner.clean(
            cacheRoot = cacheRoot,
            jobRoot = SceneStaleJobRoot.CAPTURE,
            nowMillis = NOW_MILLIS,
            staleAfterMillis = STALE_AFTER_MILLIS,
            limits = SceneStaleJobCleanupLimits(
                maxChildrenScanned = 3,
                maxJobDirectoriesDeleted = 10,
                maxTreeEntriesVisited = 100,
            ),
        )
        jobs.forEach { setModified(it, OLD_MILLIS) }

        val deleteLimited = SceneStaleJobCleaner.clean(
            cacheRoot = cacheRoot,
            jobRoot = SceneStaleJobRoot.CAPTURE,
            nowMillis = NOW_MILLIS,
            staleAfterMillis = STALE_AFTER_MILLIS,
            limits = SceneStaleJobCleanupLimits(
                maxChildrenScanned = 10,
                maxJobDirectoriesDeleted = 2,
                maxTreeEntriesVisited = 100,
            ),
        )

        assertEquals(3, scanLimited.childrenScanned)
        assertEquals(0, scanLimited.jobDirectoriesDeleted)
        assertEquals(2, deleteLimited.jobDirectoriesDeleted)
        assertEquals(8, jobs.count { it.exists() })
    }

    @Test
    fun `recursive deletion stops at its tree entry bound`() = runTest {
        val jobsRoot = createJobsRoot(SceneStaleJobRoot.CAPTURE)
        val oldJob = File(jobsRoot, "large-old-job").apply {
            assertTrue(mkdir())
            repeat(10) { index ->
                File(this, "frame-$index").writeText("frame")
            }
        }
        setModified(oldJob, OLD_MILLIS)

        val result = SceneStaleJobCleaner.clean(
            cacheRoot = cacheRoot,
            jobRoot = SceneStaleJobRoot.CAPTURE,
            nowMillis = NOW_MILLIS,
            staleAfterMillis = STALE_AFTER_MILLIS,
            limits = SceneStaleJobCleanupLimits(
                maxChildrenScanned = 1,
                maxJobDirectoriesDeleted = 1,
                maxTreeEntriesVisited = 3,
            ),
        )

        assertEquals(3, result.treeEntriesVisited)
        assertEquals(0, result.jobDirectoriesDeleted)
        assertTrue(oldJob.isDirectory)
        assertTrue(oldJob.listFiles().orEmpty().isNotEmpty())
    }

    private fun createJobsRoot(root: SceneStaleJobRoot): File {
        return File(cacheRoot, root.directoryName).apply {
            assertTrue(mkdir())
        }
    }

    private fun createJob(root: File, name: String): File {
        return File(root, name).apply {
            assertTrue(mkdir())
            File(this, "artifact").writeText("data")
        }
    }

    private fun setModified(file: File, millis: Long) {
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(millis))
    }

    private companion object {
        const val NOW_MILLIS = 10L * 24L * 60L * 60L * 1_000L
        const val STALE_AFTER_MILLIS = 24L * 60L * 60L * 1_000L
        const val OLD_MILLIS = NOW_MILLIS - STALE_AFTER_MILLIS - 1L
        const val FRESH_MILLIS = NOW_MILLIS - STALE_AFTER_MILLIS + 1L
    }
}
