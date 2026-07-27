// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.model

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Stable identity for a stats title independent of its mutable display metadata or local
 * library availability.
 */
data class ImmersionTitleIdentity(
    val id: TitleId,
    val mediaKind: MediaKind,
    val sourceKey: String,
    val profileId: String,
    val libraryId: Long? = null,
    val mediaId: String? = null,
) {
    init {
        require(sourceKey.isNotBlank()) { "Title source key cannot be blank" }
        require(libraryId == null || libraryId >= 0) { "Library ID cannot be negative" }
    }
}

object ImmersionTitleIdentityAdapter {
    fun manga(
        mangaId: Long,
        profileId: String,
    ): ImmersionTitleIdentity {
        require(mangaId >= 0) { "Manga ID cannot be negative" }
        val sourceKey = "manga:$mangaId"
        return nativeIdentity(
            mediaKind = MediaKind.MANGA,
            namespace = MANGA_TITLE_NAMESPACE,
            sourceKey = sourceKey,
            profileId = profileId,
            libraryId = mangaId,
            mediaId = mangaId.toString(),
        )
    }

    fun novel(
        documentId: String,
        profileId: String,
    ): ImmersionTitleIdentity {
        require(documentId.isNotBlank()) { "Novel document identity cannot be blank" }
        val sourceKey = "novel:$documentId"
        return nativeIdentity(
            mediaKind = MediaKind.NOVEL,
            namespace = NOVEL_TITLE_NAMESPACE,
            sourceKey = sourceKey,
            profileId = profileId,
            mediaId = documentId,
        )
    }

    fun video(
        animeId: Long,
        profileId: String,
    ): ImmersionTitleIdentity {
        require(animeId >= 0) { "Anime ID cannot be negative" }
        val sourceKey = "video:$animeId"
        return nativeIdentity(
            mediaKind = MediaKind.VIDEO,
            namespace = VIDEO_TITLE_NAMESPACE,
            sourceKey = sourceKey,
            profileId = profileId,
            libraryId = animeId,
            mediaId = animeId.toString(),
        )
    }

    /**
     * Retains the identity algorithm used by legacy imports. Legacy sources remain distinct from
     * event-backed library titles until a reviewed merge explicitly links them.
     */
    fun legacy(
        mediaKind: MediaKind,
        sourceKey: String,
        profileId: String,
    ): ImmersionTitleIdentity {
        require(sourceKey.isNotBlank()) { "Legacy title source key cannot be blank" }
        return ImmersionTitleIdentity(
            id = TitleId(
                sha256Uuid(
                    "legacy-title\u0000$mediaKind\u0000$sourceKey\u0000$profileId",
                ),
            ),
            mediaKind = mediaKind,
            sourceKey = sourceKey,
            profileId = profileId,
        )
    }

    private fun nativeIdentity(
        mediaKind: MediaKind,
        namespace: String,
        sourceKey: String,
        profileId: String,
        libraryId: Long? = null,
        mediaId: String? = null,
    ) = ImmersionTitleIdentity(
        id = TitleId(nameUuid(namespace, "$sourceKey|$profileId")),
        mediaKind = mediaKind,
        sourceKey = sourceKey,
        profileId = profileId,
        libraryId = libraryId,
        mediaId = mediaId,
    )

    private fun nameUuid(namespace: String, value: String): String =
        UUID.nameUUIDFromBytes(
            "$namespace\u0000$value".toByteArray(StandardCharsets.UTF_8),
        ).toString()

    private fun sha256Uuid(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }

    private const val MANGA_TITLE_NAMESPACE = "immersion-title-manga"
    private const val NOVEL_TITLE_NAMESPACE = "immersion-title-novel"
    private const val VIDEO_TITLE_NAMESPACE = "immersion-title-video"
}
