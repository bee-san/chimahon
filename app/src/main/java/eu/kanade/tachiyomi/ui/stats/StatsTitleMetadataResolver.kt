// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import android.app.Application
import androidx.compose.runtime.Immutable
import com.canopus.chimareader.data.BookStorage
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.immersion.model.AnalyticsTitleRow
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.manga.interactor.GetManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

enum class StatsTitleLinkState {
    AVAILABLE,
    UNAVAILABLE,
    LEGACY_ONLY,
}

@Immutable
data class StatsTitlePresentationMetadata(
    val titleId: TitleId,
    val localDisplayTitle: String?,
    val author: String?,
    val coverLocation: String?,
    val linkState: StatsTitleLinkState,
    val navigationPath: String? = null,
    val favorite: Boolean? = null,
)

internal data class StatsTitleLocalRecord(
    val displayTitle: String?,
    val author: String?,
    val coverLocation: String?,
    val navigationPath: String? = null,
    val favorite: Boolean? = null,
)

class StatsTitleMetadataResolver internal constructor(
    private val mangaLookup: suspend (Set<Long>) -> Map<Long, StatsTitleLocalRecord>,
    private val novelLookup: suspend (Set<String>) -> Map<String, StatsTitleLocalRecord>,
    private val videoLookup: suspend (Set<Long>) -> Map<Long, StatsTitleLocalRecord>,
) {
    suspend fun resolve(
        titles: List<AnalyticsTitleRow>,
    ): Map<TitleId, StatsTitlePresentationMetadata> = withIOContext {
        val mangaIds = titles
            .asSequence()
            .filter { it.mediaKind == MediaKind.MANGA }
            .mapNotNull(AnalyticsTitleRow::localLongId)
            .toSet()
        val novelIds = titles
            .asSequence()
            .filter { it.mediaKind == MediaKind.NOVEL }
            .mapNotNull(AnalyticsTitleRow::novelDocumentId)
            .toSet()
        val videoIds = titles
            .asSequence()
            .filter { it.mediaKind == MediaKind.VIDEO }
            .mapNotNull(AnalyticsTitleRow::localLongId)
            .toSet()
        val manga = mangaLookup(mangaIds)
        val novels = novelLookup(novelIds)
        val videos = videoLookup(videoIds)

        titles.associate { title ->
            val local = when (title.mediaKind) {
                MediaKind.MANGA -> title.localLongId()?.let(manga::get)
                MediaKind.NOVEL -> title.novelDocumentId()?.let(novels::get)
                MediaKind.VIDEO -> title.localLongId()?.let(videos::get)
            }
            title.titleId to StatsTitlePresentationMetadata(
                titleId = title.titleId,
                localDisplayTitle = local?.displayTitle,
                author = local?.author,
                coverLocation = local?.coverLocation,
                linkState = when {
                    local != null -> StatsTitleLinkState.AVAILABLE
                    title.sourceKey.startsWith(LEGACY_SOURCE_PREFIX) ->
                        StatsTitleLinkState.LEGACY_ONLY
                    else -> StatsTitleLinkState.UNAVAILABLE
                },
                navigationPath = local?.navigationPath,
                favorite = local?.favorite,
            )
        }
    }

    companion object {
        fun create(
            application: Application = Injekt.get(),
            getManga: GetManga = Injekt.get(),
            getAnime: GetAnime = Injekt.get(),
        ) = StatsTitleMetadataResolver(
            mangaLookup = { ids ->
                ids.mapNotNull { id ->
                    getManga.await(id)?.let { manga ->
                        id to StatsTitleLocalRecord(
                            displayTitle = manga.title,
                            author = manga.author,
                            coverLocation = manga.thumbnailUrl,
                            favorite = manga.favorite,
                        )
                    }
                }.toMap()
            },
            novelLookup = { documentIds ->
                BookStorage.loadAllBooks(application)
                    .asSequence()
                    .filter { BookStorage.bookIdentityKey(it) in documentIds }
                    .associate { metadata ->
                        val documentId = BookStorage.bookIdentityKey(metadata)
                        val directory = BookStorage.getBookDirectory(application, metadata.id)
                        val cover = metadata.cover
                            ?.let(::File)
                            ?.takeIf(File::isFile)
                            ?.absolutePath
                        documentId to StatsTitleLocalRecord(
                            displayTitle = metadata.title,
                            author = metadata.author,
                            coverLocation = cover,
                            navigationPath = directory.takeIf(File::isDirectory)?.absolutePath,
                            favorite = true,
                        )
                    }
            },
            videoLookup = { ids ->
                ids.mapNotNull { id ->
                    getAnime.await(id)?.let { anime ->
                        id to StatsTitleLocalRecord(
                            displayTitle = anime.title,
                            author = anime.author,
                            coverLocation = anime.thumbnailUrl,
                            favorite = anime.favorite,
                        )
                    }
                }.toMap()
            },
        )
    }
}

private fun AnalyticsTitleRow.localLongId(): Long? =
    if (deletedAtEpochMillis != null) {
        null
    } else {
        libraryId ?: mediaId?.toLongOrNull() ?: sourceKey.substringAfter(':', "").toLongOrNull()
    }

private fun AnalyticsTitleRow.novelDocumentId(): String? =
    if (deletedAtEpochMillis != null) {
        null
    } else {
        mediaId?.takeIf(String::isNotBlank)
            ?: sourceKey.removePrefix(NOVEL_SOURCE_PREFIX).takeIf(String::isNotBlank)
    }

private const val LEGACY_SOURCE_PREFIX = "legacy:"
private const val NOVEL_SOURCE_PREFIX = "novel:"
