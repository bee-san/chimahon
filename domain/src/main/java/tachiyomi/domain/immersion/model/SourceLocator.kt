package tachiyomi.domain.immersion.model

sealed interface SourceLocator {
    val mediaKind: MediaKind
    val sourceKind: SourceKind

    /**
     * Stable, unambiguous identity input. This is suitable for hashing into a source-unit ID and
     * deliberately contains no raw source text.
     */
    fun canonicalKey(): String
}

data class ParsedSourceLocator(
    val sourceKind: SourceKind,
    val parts: List<String>,
) {
    init {
        require(parts.isNotEmpty()) { "A source locator must contain at least one part" }
    }
}

/**
 * Parses the length-prefixed format emitted by [SourceLocator.canonicalKey]. Invalid or
 * forward-incompatible locators fail closed so historical navigation never opens the wrong item.
 */
fun parseCanonicalSourceLocator(value: String): ParsedSourceLocator? {
    val separator = value.indexOf('|')
    if (separator <= 0) return null
    val sourceKind = runCatching { SourceKind.valueOf(value.substring(0, separator)) }.getOrNull() ?: return null
    val parts = mutableListOf<String>()
    var cursor = separator + 1
    while (cursor < value.length) {
        val colon = value.indexOf(':', startIndex = cursor)
        if (colon <= cursor) return null
        val length = value.substring(cursor, colon).toIntOrNull() ?: return null
        if (length < 0) return null
        val partStart = colon + 1
        val partEnd = partStart + length
        if (partEnd > value.length) return null
        parts += value.substring(partStart, partEnd)
        cursor = partEnd
        if (cursor == value.length) break
        if (value[cursor] != '|') return null
        cursor++
    }
    return parts.takeIf { it.isNotEmpty() }?.let { ParsedSourceLocator(sourceKind, it) }
}

@JvmInline
value class ContentHash(val value: String) {
    init {
        require(value.length == SHA_256_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Content hash must be a lowercase SHA-256 hex value"
        }
    }

    private companion object {
        const val SHA_256_HEX_LENGTH = 64
    }
}

data class NovelSourceLocator(
    val sourceKey: String,
    val documentId: String,
    val sectionId: String,
    val rangeStart: Long,
    val rangeEndExclusive: Long,
    val normalizedTextHash: ContentHash,
    val parserRevision: Int,
) : SourceLocator {
    init {
        requireIdentifiers(sourceKey, documentId, sectionId)
        require(rangeStart >= 0 && rangeEndExclusive > rangeStart) { "Novel range must be non-empty and non-negative" }
        require(parserRevision > 0) { "Parser revision must be positive" }
    }

    override val mediaKind = MediaKind.NOVEL
    override val sourceKind = SourceKind.NOVEL_RANGE

    override fun canonicalKey(): String = canonicalSourceKey(
        sourceKind,
        sourceKey,
        documentId,
        sectionId,
        rangeStart.toString(),
        rangeEndExclusive.toString(),
        normalizedTextHash.value,
        parserRevision.toString(),
    )
}

data class MangaSourceLocator(
    val mangaId: Long,
    val chapterId: Long,
    val pageIndex: Int,
    val ocrEngineId: String? = null,
    val ocrRevision: Int? = null,
    val ocrBlockId: String? = null,
    val normalizedTextHash: ContentHash? = null,
) : SourceLocator {
    init {
        require(mangaId >= 0 && chapterId >= 0 && pageIndex >= 0) { "Manga locator values cannot be negative" }
        require(
            listOf(ocrEngineId, ocrRevision, normalizedTextHash).all { it == null } ||
                listOf(ocrEngineId, ocrRevision, normalizedTextHash).all { it != null },
        ) { "OCR engine, revision, and text hash must be supplied together" }
        require(ocrBlockId == null || ocrEngineId != null) {
            "OCR block identity requires an OCR engine, revision, and text hash"
        }
        require(ocrEngineId == null || ocrEngineId.isNotBlank()) { "OCR engine ID cannot be blank" }
        require(ocrRevision == null || ocrRevision > 0) { "OCR revision must be positive" }
        require(ocrBlockId == null || ocrBlockId.isNotBlank()) { "OCR block ID cannot be blank" }
    }

    override val mediaKind = MediaKind.MANGA
    override val sourceKind = if (ocrBlockId == null) SourceKind.MANGA_PAGE else SourceKind.MANGA_OCR_BLOCK

    override fun canonicalKey(): String = canonicalSourceKey(
        sourceKind,
        mangaId.toString(),
        chapterId.toString(),
        pageIndex.toString(),
        ocrEngineId.orEmpty(),
        ocrRevision?.toString().orEmpty(),
        ocrBlockId.orEmpty(),
        normalizedTextHash?.value.orEmpty(),
    )
}

data class SubtitleSourceLocator(
    val sourceKey: String,
    val episodeMediaId: String,
    val subtitleTrackId: String,
    val cueIndex: Int,
    val cueStartMillis: Long,
    val cueEndMillis: Long,
    val normalizedTextHash: ContentHash,
) : SourceLocator {
    init {
        requireIdentifiers(sourceKey, episodeMediaId, subtitleTrackId)
        require(cueIndex >= 0 && cueStartMillis >= 0 && cueEndMillis > cueStartMillis) {
            "Subtitle cue range must be non-empty and non-negative"
        }
    }

    override val mediaKind = MediaKind.VIDEO
    override val sourceKind = SourceKind.SUBTITLE_CUE

    override fun canonicalKey(): String = canonicalSourceKey(
        sourceKind,
        sourceKey,
        episodeMediaId,
        subtitleTrackId,
        cueIndex.toString(),
        cueStartMillis.toString(),
        cueEndMillis.toString(),
        normalizedTextHash.value,
    )
}

data class VideoOcrSourceLocator(
    val sourceKey: String,
    val episodeMediaId: String,
    val timestampBucketMillis: Long,
    val frameIdentity: String,
    val ocrRegionId: String,
    val ocrEngineId: String,
    val ocrRevision: Int,
    val normalizedTextHash: ContentHash,
) : SourceLocator {
    init {
        requireIdentifiers(sourceKey, episodeMediaId, frameIdentity, ocrRegionId, ocrEngineId)
        require(timestampBucketMillis >= 0) { "Video OCR timestamp cannot be negative" }
        require(ocrRevision > 0) { "OCR revision must be positive" }
    }

    override val mediaKind = MediaKind.VIDEO
    override val sourceKind = SourceKind.VIDEO_OCR_REGION

    override fun canonicalKey(): String = canonicalSourceKey(
        sourceKind,
        sourceKey,
        episodeMediaId,
        timestampBucketMillis.toString(),
        frameIdentity,
        ocrRegionId,
        ocrEngineId,
        ocrRevision.toString(),
        normalizedTextHash.value,
    )
}

private fun requireIdentifiers(vararg values: String) {
    require(values.none { it.isBlank() }) { "Source locator identifiers cannot be blank" }
}

private fun canonicalSourceKey(kind: SourceKind, vararg parts: String): String = buildString {
    append(kind.name)
    parts.forEach { part ->
        append('|')
        append(part.length)
        append(':')
        append(part)
    }
}
