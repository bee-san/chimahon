package eu.kanade.presentation.more.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.manga.components.MangaCoverHide
import eu.kanade.tachiyomi.ui.stats.StatsTitleItem
import eu.kanade.tachiyomi.ui.stats.StatsTitlesState
import eu.kanade.tachiyomi.util.lang.toCountString
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun StatsTitlesContent(
    state: StatsTitlesState.Success,
    paddingValues: PaddingValues,
    onTitleClick: (StatsTitleItem) -> Unit,
) {
    if (state.titles.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(KMR.strings.stats_titles_empty_profile),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        contentPadding = paddingValues,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.titles, key = { "${it.isNovel}_${it.id}" }) { item ->
            StatsTitleListItem(
                item = item,
                onClick = { onTitleClick(item) },
            )
        }
    }
}

@Composable
private fun StatsTitleListItem(
    item: StatsTitleItem,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Cover Art
        Box(
            modifier = Modifier
                .width(56.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp)),
        ) {
            if (item.isNovel) {
                val file = item.coverData as? File
                if (file != null && file.exists()) {
                    AsyncImage(
                        model = file,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    MangaCoverHide.Book(
                        modifier = Modifier.fillMaxSize(),
                        size = MangaCover.Size.Medium,
                    )
                }
            } else {
                val manga = item.coverData as? Manga
                if (manga != null) {
                    MangaCover.Book(
                        modifier = Modifier.fillMaxSize(),
                        data = manga.asMangaCover(),
                        size = MangaCover.Size.Medium,
                    )
                } else {
                    MangaCoverHide.Book(
                        modifier = Modifier.fillMaxSize(),
                        size = MangaCover.Size.Medium,
                    )
                }
            }
        }

        // Details
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, end = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            item.author?.let { author ->
                Text(
                    text = author,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            val infoText = when {
                item.readDurationMs > 0 && item.charactersRead > 0 -> stringResource(
                    KMR.strings.stats_title_read_summary,
                    stringResource(
                        KMR.strings.stats_title_read_duration,
                        formatLegacyDuration(item.readDurationMs),
                    ),
                    pluralStringResource(
                        KMR.plurals.stats_character_count,
                        item.charactersRead,
                        item.charactersRead.toCountString(),
                    ),
                )
                item.readDurationMs > 0 -> stringResource(
                    KMR.strings.stats_title_read_duration,
                    formatLegacyDuration(item.readDurationMs),
                )
                item.lastReadDate != null -> stringResource(
                    KMR.strings.stats_last_read,
                    item.lastReadDate.format(
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                            .withLocale(Locale.getDefault()),
                    ),
                )
                else -> stringResource(KMR.strings.stats_unread)
            }
            Text(
                text = infoText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun formatLegacyDuration(durationMs: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
    val minuteText = pluralStringResource(
        KMR.plurals.stats_duration_minutes,
        minutes.toInt(),
        minutes,
    )
    if (hours == 0L) return minuteText
    return stringResource(
        KMR.strings.stats_duration_hours_minutes,
        pluralStringResource(KMR.plurals.stats_duration_hours, hours.toInt(), hours),
        minuteText,
    )
}
