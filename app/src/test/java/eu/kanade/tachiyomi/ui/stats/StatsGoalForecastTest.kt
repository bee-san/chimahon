package eu.kanade.tachiyomi.ui.stats

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.AnalyticsGoalProgress
import tachiyomi.domain.immersion.model.CapabilityState
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionLocalDate

class StatsGoalForecastTest {

    @Test
    fun `available forecast with a date can show its projection`() {
        statsGoalForecastPresentation(progress()) shouldBe StatsGoalForecastPresentation.AVAILABLE
    }

    @Test
    fun `partial forecast does not present its calculated date as precise`() {
        statsGoalForecastPresentation(
            progress(confidence = CapabilityState.PARTIAL),
        ) shouldBe StatsGoalForecastPresentation.PARTIAL
    }

    @Test
    fun `unavailable forecast suppresses even a defensive projected date`() {
        statsGoalForecastPresentation(
            progress(confidence = CapabilityState.UNAVAILABLE),
        ) shouldBe StatsGoalForecastPresentation.UNAVAILABLE
    }

    @Test
    fun `completed and daily goals do not need a completion forecast`() {
        statsGoalForecastPresentation(
            progress(achieved = 100.0),
        ) shouldBe StatsGoalForecastPresentation.NONE
        statsGoalForecastPresentation(
            progress(goal = persistedGoal(period = "DAILY", type = "PERPETUAL_DAILY")),
        ) shouldBe StatsGoalForecastPresentation.NONE
    }

    @Test
    fun `missing projected date is disclosed as unavailable`() {
        statsGoalForecastPresentation(
            progress(projectedCompletionDate = null),
        ) shouldBe StatsGoalForecastPresentation.UNAVAILABLE
    }

    private fun progress(
        goal: ImmersionGoal = persistedGoal(),
        achieved: Double = 25.0,
        confidence: CapabilityState = CapabilityState.AVAILABLE,
        projectedCompletionDate: ImmersionLocalDate? = ImmersionLocalDate.parse("2026-08-31"),
    ) = AnalyticsGoalProgress(
        goal = goal,
        achieved = achieved,
        target = 100.0,
        pacePerDay = 5.0,
        projectedCompletionDate = projectedCompletionDate,
        achievedAtEpochMillis = null,
        targetToDate = 100.0,
        forecastConfidence = confidence,
    )

    private fun persistedGoal(
        period: String = "TOTAL",
        type: String = "DATE_BOUND_TOTAL",
    ) = ImmersionGoal(
        id = "goal",
        type = type,
        metric = "gross_characters",
        target = 100.0,
        period = period,
        startDate = ImmersionLocalDate.parse("2026-07-01"),
        endDate = ImmersionLocalDate.parse("2026-08-31"),
        mediaKind = null,
        profileId = null,
        languageTag = null,
        titleId = null,
        weekdayMultipliers = null,
        restDayPolicy = null,
        state = "ACTIVE",
        createdAtEpochMillis = 1_000,
        updatedAtEpochMillis = 1_000,
    )
}
