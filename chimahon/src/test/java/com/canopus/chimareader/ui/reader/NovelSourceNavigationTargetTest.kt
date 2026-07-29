package com.canopus.chimareader.ui.reader

import com.canopus.chimareader.data.epub.EpubBook
import com.canopus.chimareader.data.epub.EpubManifest
import com.canopus.chimareader.data.epub.EpubSpine
import com.canopus.chimareader.data.epub.ManifestItem
import com.canopus.chimareader.data.epub.SpineItem
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class NovelSourceNavigationTargetTest {

    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `resolver restores the exact code point range and excludes ruby annotations`() {
        val document = document(
            """<html><body>A😀<ruby>漢<rt>かん</rt></ruby>B<script>bad</script>C</body></html>""",
        )
        val expectedText = "😀漢B"
        val target = resolveNovelSourceNavigationTarget(
            document = document,
            locatorParts = listOf(
                "novel:document-id",
                "document-id",
                "OPS/chapter.xhtml",
                "1",
                "4",
                expectedText.normalizedSha256(),
                SUPPORTED_NOVEL_SOURCE_PARSER_REVISION.toString(),
            ),
        )

        target shouldBe NovelSourceNavigationTarget(
            documentId = "document-id",
            chapterIndex = 0,
            sectionId = "OPS/chapter.xhtml",
            rangeStart = 1,
            rangeEndExclusive = 4,
            normalizedTextHash = expectedText.normalizedSha256(),
            parserRevision = SUPPORTED_NOVEL_SOURCE_PARSER_REVISION,
        )
        target?.matches(document) shouldBe true
    }

    @Test
    fun `range slicing counts supplementary unicode as one code point`() {
        novelSourceRangeText(
            chapterMarkup = "<html><body>A😀B</body></html>",
            rangeStart = 1,
            rangeEndExclusive = 2,
        ) shouldBe "😀"
    }

    @Test
    fun `resolved target fails closed when the loaded document content changes`() {
        val document = document("<html><body>original</body></html>")
        val target = requireNotNull(
            resolveNovelSourceNavigationTarget(
                document = document,
                locatorParts = listOf(
                    "novel:document-id",
                    "document-id",
                    "OPS/chapter.xhtml",
                    "0",
                    "8",
                    "original".normalizedSha256(),
                    SUPPORTED_NOVEL_SOURCE_PARSER_REVISION.toString(),
                ),
            ),
        )

        temporaryDirectory.resolve("OPS/chapter.xhtml")
            .writeText("<html><body>modified</body></html>")

        target.matches(document) shouldBe false
    }

    @Test
    fun `resolver fails closed for stale content unsupported parsers and missing sections`() {
        val document = document("<html><body>unchanged</body></html>")
        val baseParts = listOf(
            "novel:document-id",
            "document-id",
            "OPS/chapter.xhtml",
            "0",
            "9",
            "unchanged".normalizedSha256(),
            SUPPORTED_NOVEL_SOURCE_PARSER_REVISION.toString(),
        )

        resolveNovelSourceNavigationTarget(
            document,
            baseParts.toMutableList().apply { this[5] = "changed".normalizedSha256() },
        ).shouldBeNull()
        resolveNovelSourceNavigationTarget(
            document,
            baseParts.toMutableList().apply { this[6] = "2" },
        ).shouldBeNull()
        resolveNovelSourceNavigationTarget(
            document,
            baseParts.toMutableList().apply { this[2] = "OPS/missing.xhtml" },
        ).shouldBeNull()
    }

    private fun document(markup: String): EpubBook {
        val chapter = temporaryDirectory.resolve("OPS/chapter.xhtml")
        chapter.parent.createDirectories()
        chapter.writeText(markup)
        return EpubBook(
            manifest = EpubManifest(
                items = mapOf(
                    "chapter" to ManifestItem(
                        id = "chapter",
                        href = "chapter.xhtml",
                    ),
                ),
            ),
            spine = EpubSpine(items = listOf(SpineItem(idref = "chapter"))),
            contentDirectory = "OPS/",
            extractedDir = temporaryDirectory.toFile(),
        )
    }

    private fun String.normalizedSha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                Normalizer.normalize(this, Normalizer.Form.NFC)
                    .toByteArray(StandardCharsets.UTF_8),
            )
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
