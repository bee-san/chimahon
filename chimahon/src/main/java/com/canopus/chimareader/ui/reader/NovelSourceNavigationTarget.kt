// SPDX-License-Identifier: MIT

package com.canopus.chimareader.ui.reader

import com.canopus.chimareader.data.epub.EpubBook
import com.canopus.chimareader.data.epub.EpubParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer

data class NovelSourceNavigationTarget(
    val documentId: String,
    val chapterIndex: Int,
    val sectionId: String,
    val rangeStart: Int,
    val rangeEndExclusive: Int,
    val normalizedTextHash: String,
    val parserRevision: Int,
) {
    init {
        require(documentId.isNotBlank())
        require(chapterIndex >= 0)
        require(sectionId.isNotBlank())
        require(rangeStart >= 0 && rangeEndExclusive > rangeStart)
        require(normalizedTextHash.isSha256())
        require(parserRevision == SUPPORTED_NOVEL_SOURCE_PARSER_REVISION)
    }

    fun matches(document: EpubBook): Boolean {
        val matchingSection = document.getChapterHref(chapterIndex)
            ?.substringBefore('#')
            ?.takeIf(String::isNotBlank) == sectionId
        if (!matchingSection) return false
        val chapterMarkup = runCatching {
            EpubParser().parseChapter(document, chapterIndex)
        }.getOrNull() ?: return false
        val sourceText = novelSourceRangeText(
            chapterMarkup = chapterMarkup,
            rangeStart = rangeStart,
            rangeEndExclusive = rangeEndExclusive,
        ) ?: return false
        return sourceText.normalizedSha256() == normalizedTextHash
    }
}

fun resolveNovelSourceNavigationTarget(
    document: EpubBook,
    locatorParts: List<String>,
): NovelSourceNavigationTarget? {
    if (locatorParts.size != NOVEL_SOURCE_LOCATOR_PART_COUNT) return null
    val sourceKey = locatorParts[0]
    val documentId = locatorParts[1]
    val sectionId = locatorParts[2]
    val rangeStart = locatorParts[3].toLongOrNull()
        ?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
        ?.toInt()
        ?: return null
    val rangeEndExclusive = locatorParts[4].toLongOrNull()
        ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
        ?.toInt()
        ?: return null
    val normalizedTextHash = locatorParts[5]
    val parserRevision = locatorParts[6].toIntOrNull() ?: return null
    if (
        sourceKey != "$NOVEL_SOURCE_KEY_PREFIX:$documentId" ||
        documentId.isBlank() ||
        sectionId.isBlank() ||
        rangeEndExclusive <= rangeStart ||
        !normalizedTextHash.isSha256() ||
        parserRevision != SUPPORTED_NOVEL_SOURCE_PARSER_REVISION
    ) {
        return null
    }
    val chapterIndex = document.linearSpineItems.indices.firstOrNull { index ->
        document.getChapterHref(index)
            ?.substringBefore('#')
            ?.takeIf(String::isNotBlank) == sectionId
    } ?: return null
    val chapterMarkup = EpubParser().parseChapter(document, chapterIndex) ?: return null
    val sourceText = novelSourceRangeText(
        chapterMarkup = chapterMarkup,
        rangeStart = rangeStart,
        rangeEndExclusive = rangeEndExclusive,
    ) ?: return null
    if (sourceText.normalizedSha256() != normalizedTextHash) return null
    return NovelSourceNavigationTarget(
        documentId = documentId,
        chapterIndex = chapterIndex,
        sectionId = sectionId,
        rangeStart = rangeStart,
        rangeEndExclusive = rangeEndExclusive,
        normalizedTextHash = normalizedTextHash,
        parserRevision = parserRevision,
    )
}

internal fun novelSourceRangeText(
    chapterMarkup: String,
    rangeStart: Int,
    rangeEndExclusive: Int,
): String? {
    if (rangeStart < 0 || rangeEndExclusive <= rangeStart) return null
    val document = Jsoup.parse(chapterMarkup)
    document.select("rt, rp, script, style, template, noscript").remove()
    val text = buildString {
        appendNovelSourceText(document.body(), this)
    }
    val codePointCount = text.codePointCount(0, text.length)
    if (rangeEndExclusive > codePointCount) return null
    val startOffset = text.offsetByCodePoints(0, rangeStart)
    val endOffset = text.offsetByCodePoints(0, rangeEndExclusive)
    return text.substring(startOffset, endOffset)
}

private fun appendNovelSourceText(node: Node, destination: StringBuilder) {
    if (node is TextNode) {
        destination.append(node.wholeText)
        return
    }
    node.childNodes().forEach { child ->
        appendNovelSourceText(child, destination)
    }
}

private fun String.normalizedSha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(
            Normalizer.normalize(this, Normalizer.Form.NFC)
                .toByteArray(StandardCharsets.UTF_8),
        )
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun String.isSha256(): Boolean =
    length == SHA_256_HEX_LENGTH && all { it in '0'..'9' || it in 'a'..'f' }

private const val NOVEL_SOURCE_LOCATOR_PART_COUNT = 7
private const val NOVEL_SOURCE_KEY_PREFIX = "novel"
private const val SHA_256_HEX_LENGTH = 64
const val SUPPORTED_NOVEL_SOURCE_PARSER_REVISION = 1
