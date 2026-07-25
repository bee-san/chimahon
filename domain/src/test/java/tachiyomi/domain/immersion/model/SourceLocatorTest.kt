package tachiyomi.domain.immersion.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class SourceLocatorTest {
    private val hash = ContentHash("a".repeat(64))

    @Test
    fun `novel locator is stable and changes with its range`() {
        val locator = NovelSourceLocator(
            sourceKey = "novel:book",
            documentId = "document",
            sectionId = "chapter-1",
            rangeStart = 10,
            rangeEndExclusive = 30,
            normalizedTextHash = hash,
            parserRevision = 1,
        )

        locator.canonicalKey() shouldBe locator.copy().canonicalKey()
        locator.canonicalKey() shouldNotBe locator.copy(rangeStart = 11).canonicalKey()
    }

    @Test
    fun `length-prefixed keys cannot collide through separator content`() {
        val first = NovelSourceLocator("a|b", "c", "d", 1, 2, hash, 1)
        val second = NovelSourceLocator("a", "b|c", "d", 1, 2, hash, 1)

        first.canonicalKey() shouldNotBe second.canonicalKey()
    }

    @Test
    fun `same manga page index in two chapters is distinct`() {
        val chapterOne = MangaSourceLocator(mangaId = 1, chapterId = 10, pageIndex = 0)
        val chapterTwo = MangaSourceLocator(mangaId = 1, chapterId = 11, pageIndex = 0)

        chapterOne.canonicalKey() shouldNotBe chapterTwo.canonicalKey()
    }

    @Test
    fun `manga OCR identity requires a complete OCR version tuple`() {
        shouldThrow<IllegalArgumentException> {
            MangaSourceLocator(
                mangaId = 1,
                chapterId = 2,
                pageIndex = 3,
                ocrEngineId = "engine",
            )
        }
        shouldThrow<IllegalArgumentException> {
            MangaSourceLocator(
                mangaId = 1,
                chapterId = 2,
                pageIndex = 3,
                ocrBlockId = "block",
            )
        }
    }

    @Test
    fun `subtitle timestamps and track participate in identity`() {
        val first = SubtitleSourceLocator("video:title", "episode", "ja", 4, 1_000, 2_000, hash)

        first.canonicalKey() shouldNotBe first.copy(cueStartMillis = 1_001).canonicalKey()
        first.canonicalKey() shouldNotBe first.copy(subtitleTrackId = "en").canonicalKey()
    }
}
