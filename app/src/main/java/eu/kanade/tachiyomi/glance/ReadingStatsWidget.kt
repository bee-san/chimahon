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
import tachiyomi.core.common.Constants
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.LocalDateRange
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.service.ImmersionAnalyticsService
import tachiyomi.domain.immersion.service.ImmersionStatsPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class ReadingStatsWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode
        get() = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val lockedMessage = lockedWidgetMessage(context)
        if (isAppLocked()) {
            provideContent { WidgetLockedState(lockedMessage) }
            return
        }

        val stats = loadTodayStats()
        val timeString = formatReadingTime(stats.activeTimeMillis)
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

    private fun formatReadingTime(totalTimeMs: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(totalTimeMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(totalTimeMs) % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private suspend fun loadTodayStats(): TodayStats {
        val preferences = Injekt.get<ImmersionStatsPreferences>()
        val basis = preferences.dashboardCharacterMetric().get()
        val today = ImmersionLocalDate.from(LocalDate.now())
        return runCatching {
            val overview = Injekt.get<ImmersionAnalyticsService>().overview(
                StatsFilter(
                    dateRange = LocalDateRange(today, today),
                    includeLegacyAggregates = preferences.includeLegacyAggregates().get(),
                    characterMetric = basis,
                    includeRereadsAndReplays = preferences.dashboardIncludeRereads().get(),
                ),
            ).value.comparison.current
            TodayStats(
                characters = overview.characters.valueFor(basis),
                activeTimeMillis = overview.activeTime.value,
                charactersPerHour = overview.readingSpeedPerHour(basis)?.toInt(),
                cardsCreated = overview.cardsCreated.value,
            )
        }.getOrElse {
            TodayStats()
        }
    }

    private data class TodayStats(
        val characters: Long = 0,
        val activeTimeMillis: Long = 0,
        val charactersPerHour: Int? = null,
        val cardsCreated: Long = 0,
    )

    companion object {
        private val COMPACT_THRESHOLD = 180.dp
    }
}
