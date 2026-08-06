package chimahon.ocr

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.File

class OcrCacheManagerTest {

    @TempDir
    lateinit var tempDir: File

    private val json = Json { ignoreUnknownKeys = true }

    private val manga = mockk<Manga>(relaxed = true) {
        every { id } returns 2L
        every { ogTitle } returns "Manga"
    }
    private val source = mockk<Source>(relaxed = true) {
        every { id } returns 1L
    }

    private fun chapter(chapterId: Long) = mockk<Chapter>(relaxed = true) {
        every { id } returns chapterId
        every { name } returns "Chapter $chapterId"
        every { scanlator } returns null
        every { url } returns "/chapter/$chapterId"
    }

    private fun newManager(
        downloadProvider: DownloadProvider = mockk {
            every { findChapterDir(any(), any(), any(), any(), any()) } returns null
        },
    ): OcrCacheManager {
        val context = mockk<Context> {
            every { filesDir } returns tempDir
        }
        val downloadManager = mockk<DownloadManager> {
            every { isChapterDownloaded(any(), any(), any(), any(), any(), any()) } returns false
        }
        return OcrCacheManager(context, json, downloadManager, downloadProvider)
    }

    private fun block(text: String) = OcrTextBlock(
        xmin = 0.1f,
        ymin = 0.2f,
        xmax = 0.3f,
        ymax = 0.4f,
        lines = listOf(text),
        vertical = true,
    )

    private fun internalCacheFile(chapterId: Long): File {
        return File(tempDir, "ocr_cache/1/2/$chapterId.json")
    }

    @Test
    fun `reads after the first are served from the memo without touching disk`() = runTest {
        val chapter = chapter(3L)

        // Build the on-disk fixture with a separate manager so the manager under
        // test starts with a cold memo.
        newManager().apply {
            saveOcrBlocks(manga, chapter, source, pageIndex = 0, blocks = listOf(block("page0")), language = "ja")
            saveOcrBlocks(manga, chapter, source, pageIndex = 1, blocks = listOf(block("page1")), language = "ja")
        }

        val downloadProvider = mockk<DownloadProvider> {
            every { findChapterDir(any(), any(), any(), any(), any()) } returns null
        }
        val manager = newManager(downloadProvider)

        assertEquals(listOf(block("page0")), manager.loadOcrBlocks(manga, chapter, source, pageIndex = 0))

        // Delete the backing file: page 1 can now only come from the memo.
        internalCacheFile(3L).delete()
        assertEquals(listOf(block("page1")), manager.loadOcrBlocks(manga, chapter, source, pageIndex = 1))
        assertNull(manager.loadOcrBlocks(manga, chapter, source, pageIndex = 2))

        // The chapter location was resolved once for the whole chapter, not per page.
        verify(exactly = 1) { downloadProvider.findChapterDir(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `save refreshes the memo and persists to disk`() = runTest {
        val chapter = chapter(4L)
        val manager = newManager()

        manager.saveOcrBlocks(manga, chapter, source, pageIndex = 0, blocks = listOf(block("v1")), language = "ja")
        assertEquals(listOf(block("v1")), manager.loadOcrBlocks(manga, chapter, source, pageIndex = 0))

        // Overwrite the page; the memoized entry must serve the new data.
        manager.saveOcrBlocks(manga, chapter, source, pageIndex = 0, blocks = listOf(block("v2")), language = "ja")
        assertEquals(listOf(block("v2")), manager.loadOcrBlocks(manga, chapter, source, pageIndex = 0))

        // A fresh manager (cold memo) must read the same data back from disk.
        assertEquals(listOf(block("v2")), newManager().loadOcrBlocks(manga, chapter, source, pageIndex = 0))
    }

    @Test
    fun `deleteOcrForChapter invalidates the memo`() = runTest {
        val chapter = chapter(5L)
        val manager = newManager()

        manager.saveOcrBlocks(manga, chapter, source, pageIndex = 0, blocks = listOf(block("page0")), language = "ja")
        assertEquals(listOf(block("page0")), manager.loadOcrBlocks(manga, chapter, source, pageIndex = 0))

        manager.deleteOcrForChapter(manga, chapter, source)
        assertNull(manager.loadOcrBlocks(manga, chapter, source, pageIndex = 0))
    }

    @Test
    fun `corrupt cache file is not clobbered by a save`() = runTest {
        val chapter = chapter(6L)
        val corruptContent = "not json {{{"
        internalCacheFile(6L).apply {
            parentFile?.mkdirs()
            writeText(corruptContent)
        }

        val manager = newManager()
        assertNull(manager.loadOcrBlocks(manga, chapter, source, pageIndex = 0))

        manager.saveOcrBlocks(manga, chapter, source, pageIndex = 0, blocks = listOf(block("page0")), language = "ja")

        // The save must abort rather than overwrite the unreadable file.
        assertEquals(corruptContent, internalCacheFile(6L).readText())
        assertNull(manager.loadOcrBlocks(manga, chapter, source, pageIndex = 0))
    }

    @Test
    fun `memo evicts least recently used chapters`() = runTest {
        val downloadProvider = mockk<DownloadProvider> {
            every { findChapterDir(any(), any(), any(), any(), any()) } returns null
        }
        val manager = newManager(downloadProvider)

        // Load 5 chapters: one more than the memo holds, evicting the first.
        (1L..5L).forEach { manager.loadOcrBlocks(manga, chapter(it), source, pageIndex = 0) }
        verify(exactly = 5) { downloadProvider.findChapterDir(any(), any(), any(), any(), any()) }

        // Chapter 1 was evicted, so it resolves again; chapter 5 is still memoized.
        manager.loadOcrBlocks(manga, chapter(1L), source, pageIndex = 0)
        verify(exactly = 6) { downloadProvider.findChapterDir(any(), any(), any(), any(), any()) }
        manager.loadOcrBlocks(manga, chapter(5L), source, pageIndex = 0)
        verify(exactly = 6) { downloadProvider.findChapterDir(any(), any(), any(), any(), any()) }
    }
}
