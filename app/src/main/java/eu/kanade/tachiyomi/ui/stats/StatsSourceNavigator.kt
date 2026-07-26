// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import android.content.Context
import com.canopus.chimareader.data.BookStorage
import com.canopus.chimareader.ui.reader.NovelReaderActivity
import com.canopus.chimareader.ui.reader.NovelSourceNavigationTarget
import com.canopus.chimareader.ui.reader.resolveNovelSourceNavigationTarget
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.episode.interactor.GetEpisode
import tachiyomi.domain.immersion.model.AnalyticsSourceOccurrence
import tachiyomi.domain.immersion.model.SourceKind
import tachiyomi.domain.immersion.model.parseCanonicalSourceLocator
import tachiyomi.domain.manga.interactor.GetManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

object StatsSourceNavigator {
    suspend fun open(context: Context, occurrence: AnalyticsSourceOccurrence): Boolean {
        val destination = withIOContext { resolve(context, occurrence) } ?: return false
        when (destination) {
            is Destination.Manga -> context.startActivity(
                ReaderActivity.newIntent(
                    context = context,
                    mangaId = destination.mangaId,
                    chapterId = destination.chapterId,
                    page = destination.pageIndex,
                ),
            )
            is Destination.Novel -> NovelReaderActivity.launch(
                context = context,
                bookDir = destination.bookDirectory,
                sourceTarget = destination.sourceTarget,
            )
            is Destination.Video -> context.startActivity(
                PlayerActivity.newIntent(
                    context = context,
                    animeId = destination.animeId,
                    episodeId = destination.episodeId,
                    startPositionMillis = destination.positionMillis,
                ),
            )
        }
        return true
    }

    private suspend fun resolve(
        context: Context,
        occurrence: AnalyticsSourceOccurrence,
    ): Destination? {
        val locator = parseCanonicalSourceLocator(occurrence.canonicalLocator) ?: return null
        if (locator.sourceKind != occurrence.sourceKind) return null
        return when (locator.sourceKind) {
            SourceKind.NOVEL_RANGE -> {
                val documentId = locator.parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
                val metadata = BookStorage.loadAllBooks(context)
                    .firstOrNull { BookStorage.bookIdentityKey(it) == documentId }
                    ?: return null
                val directory = BookStorage.getBookDirectory(context, metadata.id)
                    .takeIf(File::isDirectory)
                    ?: return null
                val document = runCatching { BookStorage.loadEpub(directory) }.getOrNull() ?: return null
                val sourceTarget = resolveNovelSourceNavigationTarget(document, locator.parts)
                    ?.takeIf { it.documentId == documentId }
                    ?: return null
                Destination.Novel(directory, sourceTarget)
            }
            SourceKind.MANGA_PAGE,
            SourceKind.MANGA_OCR_BLOCK,
            -> {
                val mangaId = locator.parts.getOrNull(0)?.toLongOrNull() ?: return null
                val chapterId = locator.parts.getOrNull(1)?.toLongOrNull() ?: return null
                val pageIndex = locator.parts.getOrNull(2)?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
                val manga = Injekt.get<GetManga>().await(mangaId) ?: return null
                val chapter = Injekt.get<GetChapter>().await(chapterId) ?: return null
                if (chapter.mangaId != manga.id) return null
                Destination.Manga(manga.id, chapter.id, pageIndex)
            }
            SourceKind.SUBTITLE_CUE,
            SourceKind.VIDEO_OCR_REGION,
            -> {
                val animeId = locator.parts.getOrNull(0)
                    ?.removePrefix("video:")
                    ?.toLongOrNull()
                    ?: return null
                val episodeId = locator.parts.getOrNull(1)
                    ?.removePrefix("episode:")
                    ?.toLongOrNull()
                    ?: return null
                val positionPart = if (locator.sourceKind == SourceKind.SUBTITLE_CUE) 4 else 2
                val positionMillis = locator.parts.getOrNull(positionPart)
                    ?.toLongOrNull()
                    ?.takeIf { it >= 0 }
                    ?: return null
                val anime = Injekt.get<GetAnime>().await(animeId) ?: return null
                val episode = Injekt.get<GetEpisode>().await(episodeId) ?: return null
                if (episode.animeId != anime.id) return null
                Destination.Video(anime.id, episode.id, positionMillis)
            }
        }
    }

    private sealed interface Destination {
        data class Manga(
            val mangaId: Long,
            val chapterId: Long,
            val pageIndex: Int,
        ) : Destination

        data class Novel(
            val bookDirectory: File,
            val sourceTarget: NovelSourceNavigationTarget,
        ) : Destination

        data class Video(
            val animeId: Long,
            val episodeId: Long,
            val positionMillis: Long,
        ) : Destination
    }
}
