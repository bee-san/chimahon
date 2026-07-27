// SPDX-License-Identifier: MIT

package mihon.feature.stats.goals

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.immersion.model.AnalyticsGoalProgress
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.service.ImmersionAnalyticsService
import tachiyomi.domain.immersion.service.ImmersionStatsPreferences
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class ImmersionGoalReminderJob(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
    private val analytics: ImmersionAnalyticsService = Injekt.get()
    private val preferences: ImmersionStatsPreferences = Injekt.get()

    override suspend fun doWork(): Result {
        if (!preferences.goalsEnabled().get() || !preferences.goalRemindersEnabled().get()) {
            return Result.success()
        }
        val today = ZonedDateTime.now().toLocalDate().toEpochDay()
        if (preferences.lastGoalReminderEpochDay().get() == today) return Result.success()
        return try {
            val pending = pendingGoalReminderCount(analytics.goals(StatsFilter()).value)
            if (pending > 0) {
                applicationContext.showGoalReminder(pending)
                preferences.lastGoalReminderEpochDay().set(today)
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "immersion-statistics-goal-reminder"
        internal const val REMINDER_HOUR = 20

        fun setEnabled(context: Context, enabled: Boolean) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
                context.cancelNotification(Notifications.ID_GOAL_REMINDER)
                return
            }
            val delay = goalReminderInitialDelay(ZonedDateTime.now(), REMINDER_HOUR)
            val request = PeriodicWorkRequestBuilder<ImmersionGoalReminderJob>(1, TimeUnit.DAYS)
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}

internal fun pendingGoalReminderCount(goals: List<AnalyticsGoalProgress>): Int =
    goals.count {
        !it.isRestDay &&
            it.todayTarget > 0.0 &&
            it.todayAchieved < it.todayTarget
    }

internal fun goalReminderInitialDelay(
    now: ZonedDateTime,
    reminderHour: Int,
): Duration {
    require(reminderHour in 0..23)
    val today = now.withHour(reminderHour).withMinute(0).withSecond(0).withNano(0)
    val next = if (today.isAfter(now)) today else today.plusDays(1)
    return Duration.between(now, next)
}

private fun Context.showGoalReminder(pendingGoals: Int) {
    val intent = Intent(this, MainActivity::class.java).apply {
        action = Constants.SHORTCUT_STATS
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    val pendingIntent = PendingIntent.getActivity(
        this,
        Notifications.ID_GOAL_REMINDER,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = notificationBuilder(Notifications.CHANNEL_GOAL_REMINDERS) {
        setSmallIcon(R.drawable.ic_chimahon)
        setContentTitle(stringResource(KMR.strings.stats_goal_reminder_title))
        setContentText(
            pluralStringResource(
                KMR.plurals.stats_goal_reminder_body,
                pendingGoals,
                pendingGoals,
            ),
        )
        setContentIntent(pendingIntent)
        setAutoCancel(true)
        setOnlyAlertOnce(true)
    }.build()
    notify(Notifications.ID_GOAL_REMINDER, notification)
}
