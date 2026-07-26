package eu.kanade.tachiyomi.glance

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.stats.statsDurationParts
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.LocalDateRange
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.service.ImmersionAnalyticsService
import tachiyomi.domain.immersion.service.ImmersionStatsPreferences
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.LocalDate

class ReadingStatsWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode
        get() = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val lockedMessage = lockedWidgetMessage(context)
        if (isAppLocked()) {
            provideContent { WidgetLockedState(lockedMessage) }
            return
        }

        val stats = when (val loadState = loadTodayStats()) {
            is ReadingStatsWidgetLoadState.Available -> loadState.stats
            ReadingStatsWidgetLoadState.Unavailable -> {
                val unavailableMessage = context.stringResource(KMR.strings.stats_widget_unavailable)
                provideContent { WidgetLockedState(unavailableMessage) }
                return
            }
        }
        val timeString = formatReadingTime(context, stats.activeTimeMillis)
        val charactersPerHour = stats.charactersPerHour

        val labelCharacters = context.getString(R.string.widget_stat_characters)
        val labelReadingTime = context.getString(R.string.widget_stat_reading_time)
        val labelSpeed = context.getString(R.string.widget_stat_speed)
        val labelMinedCards = context.getString(R.string.widget_stat_mined_cards)

        provideContent {
            val intent = mainActivityIntent(context, Constants.SHORTCUT_STATS)
            val size = LocalSize.current
            val isCompact = size.width < COMPACT_THRESHOLD || size.height < COMPACT_THRESHOLD

            Column(
                modifier = GlanceModifier
                    .widgetContainer()
                    .clickable(actionStartActivity(intent))
                    .padding(8.dp),
            ) {
                Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                    StatCard(
                        iconRes = R.drawable.ic_text_fields_24dp,
                        value = "%,d".format(stats.characters),
                        label = labelCharacters,
                        modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                    )
                    Spacer(modifier = GlanceModifier.width(6.dp))
                    StatCard(
                        iconRes = R.drawable.ic_schedule_24dp,
                        value = timeString,
                        label = labelReadingTime,
                        modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                    )
                }

                if (!isCompact) {
                    Spacer(modifier = GlanceModifier.height(6.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                        StatCard(
                            iconRes = R.drawable.ic_speed_24dp,
                            value = charactersPerHour?.let { "%,d".format(it) } ?: "—",
                            label = labelSpeed,
                            modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                        )
                        Spacer(modifier = GlanceModifier.width(6.dp))
                        StatCard(
                            iconRes = R.drawable.ic_style_24dp,
                            value = stats.cardsCreated.toString(),
                            label = labelMinedCards,
                            modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }

    private fun formatReadingTime(context: Context, totalTimeMs: Long): String {
        val parts = readingStatsWidgetDurationParts(totalTimeMs)
        val minuteText = context.pluralStringResource(
            KMR.plurals.stats_widget_duration_minutes,
            parts.minutes.toInt(),
            parts.minutes,
        )
        val hours = parts.hours ?: return minuteText
        return context.stringResource(
            KMR.strings.stats_duration_hours_minutes,
            context.pluralStringResource(
                KMR.plurals.stats_widget_duration_hours,
                hours.toInt(),
                hours,
            ),
            minuteText,
        )
    }

    private suspend fun loadTodayStats(): ReadingStatsWidgetLoadState {
        val preferences = Injekt.get<ImmersionStatsPreferences>()
        val basis = preferences.dashboardCharacterMetric().get()
        val today = ImmersionLocalDate.from(LocalDate.now())
        return readingStatsWidgetLoadState(
            runCatching {
                val overview = Injekt.get<ImmersionAnalyticsService>().overview(
                    StatsFilter(
                        dateRange = LocalDateRange(today, today),
                        includeLegacyAggregates = preferences.includeLegacyAggregates().get(),
                        characterMetric = basis,
                        includeRereadsAndReplays = preferences.dashboardIncludeRereads().get(),
                    ),
                ).value.comparison.current
                ReadingStatsWidgetData(
                    characters = overview.characters.valueFor(basis),
                    activeTimeMillis = overview.activeTime.value,
                    charactersPerHour = overview.readingSpeedPerHour(basis)?.toInt(),
                    cardsCreated = overview.cardsCreated.value,
                )
            },
        )
    }

    companion object {
        private val COMPACT_THRESHOLD = 180.dp
    }
}

internal data class ReadingStatsWidgetData(
    val characters: Long = 0,
    val activeTimeMillis: Long = 0,
    val charactersPerHour: Int? = null,
    val cardsCreated: Long = 0,
)

internal data class ReadingStatsWidgetDurationParts(
    val hours: Long?,
    val minutes: Long,
)

internal sealed interface ReadingStatsWidgetLoadState {
    data class Available(val stats: ReadingStatsWidgetData) : ReadingStatsWidgetLoadState

    data object Unavailable : ReadingStatsWidgetLoadState
}

internal fun readingStatsWidgetLoadState(
    result: Result<ReadingStatsWidgetData>,
): ReadingStatsWidgetLoadState = result.fold(
    onSuccess = ReadingStatsWidgetLoadState::Available,
    onFailure = { ReadingStatsWidgetLoadState.Unavailable },
)

internal fun readingStatsWidgetDurationParts(totalTimeMs: Long): ReadingStatsWidgetDurationParts {
    val parts = statsDurationParts(totalTimeMs)
    return ReadingStatsWidgetDurationParts(
        hours = parts.hours.takeIf { it > 0L },
        minutes = parts.minutes,
    )
}
