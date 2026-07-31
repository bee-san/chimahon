package mihon.feature.stats.goals

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.AnalyticsGoalProgress
import tachiyomi.domain.immersion.model.ImmersionGoal
import java.time.ZoneId
import java.time.ZonedDateTime

class ImmersionGoalReminderJobTest {

    @Test
    fun `pending count excludes completed zero target and rest day goals`() {
        pendingGoalReminderCount(
            listOf(
                progress(todayAchieved = 4.0, todayTarget = 5.0),
                progress(todayAchieved = 5.0, todayTarget = 5.0),
                progress(todayAchieved = 0.0, todayTarget = 0.0),
                progress(todayAchieved = 0.0, todayTarget = 5.0, isRestDay = true),
            ),
        ) shouldBe 1
    }

    @Test
    fun `initial delay chooses today before reminder and tomorrow at or after it`() {
        val zone = ZoneId.of("America/New_York")
        goalReminderInitialDelay(
            ZonedDateTime.of(2026, 7, 27, 19, 30, 0, 0, zone),
            20,
        ).toMinutes() shouldBe 30
        goalReminderInitialDelay(
            ZonedDateTime.of(2026, 7, 27, 20, 0, 0, 0, zone),
            20,
        ).toHours() shouldBe 24
    }

    private fun progress(
        todayAchieved: Double,
        todayTarget: Double,
        isRestDay: Boolean = false,
    ) = AnalyticsGoalProgress(
        goal = ImmersionGoal(
            id = "goal-$todayAchieved-$todayTarget-$isRestDay",
            type = "PERPETUAL_DAILY",
            metric = "gross_characters",
            target = 5.0,
            period = "DAILY",
            startDate = null,
            endDate = null,
            mediaKind = null,
            profileId = null,
            languageTag = null,
            titleId = null,
            weekdayMultipliers = null,
            restDayPolicy = "SKIP",
            state = "ACTIVE",
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        ),
        achieved = todayAchieved,
        target = todayTarget,
        pacePerDay = null,
        projectedCompletionDate = null,
        achievedAtEpochMillis = null,
        isRestDay = isRestDay,
        todayAchieved = todayAchieved,
        todayTarget = todayTarget,
    )
}
