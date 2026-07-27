// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.glance

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.AnalyticsGoalProgress
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionLocalDate

class ReadingStatsWidgetLoadStateTest {

    @Test
    fun `successful zero totals remain available`() {
        val zeroStats = ReadingStatsWidgetData()

        readingStatsWidgetLoadState(Result.success(zeroStats)) shouldBe
            ReadingStatsWidgetLoadState.Available(zeroStats)
    }

    @Test
    fun `query failure becomes unavailable`() {
        readingStatsWidgetLoadState(
            Result.failure(IllegalStateException("database unavailable")),
        ) shouldBe ReadingStatsWidgetLoadState.Unavailable
    }

    @Test
    fun `widget chooses an incomplete active goal and reports today progress`() {
        val today = ImmersionLocalDate.parse("2026-07-27")
        val completed = goalProgress(
            id = "completed",
            startDate = ImmersionLocalDate.parse("2026-07-01"),
            achieved = 10.0,
            target = 10.0,
        )
        val incomplete = goalProgress(
            id = "incomplete",
            startDate = today,
            achieved = 12.5,
            target = 50.0,
        )
        val future = goalProgress(
            id = "future",
            startDate = ImmersionLocalDate.parse("2026-07-28"),
            achieved = 1.0,
            target = 100.0,
        )

        readingStatsWidgetGoalProgress(
            listOf(completed, future, incomplete),
            today,
        ) shouldBe 25
    }

    private fun goalProgress(
        id: String,
        startDate: ImmersionLocalDate,
        achieved: Double,
        target: Double,
    ) = AnalyticsGoalProgress(
        goal = ImmersionGoal(
            id = id,
            type = "PERPETUAL_DAILY",
            metric = "gross_characters",
            target = target,
            period = "DAILY",
            startDate = startDate,
            endDate = null,
            mediaKind = null,
            profileId = null,
            languageTag = null,
            titleId = null,
            weekdayMultipliers = null,
            restDayPolicy = "NONE",
            state = "ACTIVE",
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        ),
        achieved = achieved,
        target = target,
        pacePerDay = null,
        projectedCompletionDate = null,
        achievedAtEpochMillis = null,
        todayAchieved = achieved,
        todayTarget = target,
    )
}
