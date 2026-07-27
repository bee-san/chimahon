// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.ImmersionGoal
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.TitleId
import java.time.DayOfWeek

class StatsGoalFactoryTest {

    @Test
    fun `daily active-time goal stores minutes and fractional rest-day schedule`() {
        val goal = createGoal(
            values = values(
                kind = StatsGoalKind.DAILY,
                metric = ACTIVE_TIME_GOAL_METRIC,
                target = 30.0,
                multipliers = suggestedStatsGoalWeekdayMultipliers(),
            ),
        )

        goal?.type shouldBe "PERPETUAL_DAILY"
        goal?.period shouldBe "DAILY"
        goal?.target shouldBe 1_800_000.0
        goal?.restDayPolicy shouldBe "SKIP"
        decodeStatsGoalWeekdayMultipliers(goal?.weekdayMultipliers) shouldContainExactly
            mapOf(
                DayOfWeek.MONDAY to 1.0,
                DayOfWeek.TUESDAY to 1.0,
                DayOfWeek.WEDNESDAY to 1.0,
                DayOfWeek.THURSDAY to 1.0,
                DayOfWeek.FRIDAY to 1.0,
                DayOfWeek.SATURDAY to 0.5,
                DayOfWeek.SUNDAY to 0.0,
            )
    }

    @Test
    fun `date-bound total requires and retains an ordered deadline`() {
        createGoal(
            values = values(
                kind = StatsGoalKind.DATE_BOUND_TOTAL,
                endDate = null,
            ),
        ).shouldBeNull()

        val deadline = date("2026-08-31")
        val goal = createGoal(
            values = values(
                kind = StatsGoalKind.DATE_BOUND_TOTAL,
                endDate = deadline,
            ),
        )

        goal?.type shouldBe "DATE_BOUND_TOTAL"
        goal?.period shouldBe "TOTAL"
        goal?.endDate shouldBe deadline
    }

    @Test
    fun `finish-title goal requires title scope and uses source-unit progress`() {
        createGoal(
            values = values(kind = StatsGoalKind.FINISH_TITLE_BY_DATE),
            scope = scope(titleId = null),
        ).shouldBeNull()

        val goal = createGoal(
            values = values(kind = StatsGoalKind.FINISH_TITLE_BY_DATE),
            scope = scope(titleId = TITLE),
        )

        goal?.type shouldBe "FINISH_TITLE_BY_DATE"
        goal?.metric shouldBe SOURCE_UNITS_GOAL_METRIC
        goal?.target shouldBe 10_000.0
        goal?.titleId shouldBe TITLE
    }

    @Test
    fun `manual habit has one check-in target per scheduled day`() {
        val goal = createGoal(
            values = values(
                kind = StatsGoalKind.MANUAL,
                metric = "gross_characters",
                target = 99.0,
                endDate = null,
            ),
        )

        goal?.type shouldBe "MANUAL"
        goal?.metric shouldBe "manual"
        goal?.target shouldBe 1.0
        goal?.period shouldBe "DAILY"
    }

    @Test
    fun `prospective edit preserves identity creation scope and historical child-row key`() {
        val existing = persistedGoal()
        val edited = editStatsGoalProspectively(
            existing = existing,
            values = values(
                kind = StatsGoalKind.DATE_BOUND_TOTAL,
                target = 20_000.0,
                startDate = date("2026-07-01"),
                endDate = date("2026-09-01"),
            ),
            prospectiveStartDate = date("2026-07-26"),
            nowEpochMillis = 2_000,
        )

        edited?.id shouldBe existing.id
        edited?.createdAtEpochMillis shouldBe existing.createdAtEpochMillis
        edited?.updatedAtEpochMillis shouldBe 2_000
        edited?.startDate shouldBe date("2026-07-26")
        edited?.titleId shouldBe existing.titleId
        edited?.mediaKind shouldBe existing.mediaKind
        edited?.profileId shouldBe existing.profileId
        edited?.languageTag shouldBe existing.languageTag
    }

    @Test
    fun `prospective edit rejects a deadline before the effective edit date`() {
        editStatsGoalProspectively(
            existing = persistedGoal(),
            values = values(
                kind = StatsGoalKind.DATE_BOUND_TOTAL,
                startDate = date("2026-07-01"),
                endDate = date("2026-07-25"),
            ),
            prospectiveStartDate = date("2026-07-26"),
            nowEpochMillis = 2_000,
        ).shouldBeNull()
    }

    @Test
    fun `restart history creates a new identity and keeps the original scope`() {
        val existing = persistedGoal()
        val replacement = restartStatsGoalHistory(
            existing = existing,
            replacementId = "replacement",
            values = values(
                kind = StatsGoalKind.DATE_BOUND_TOTAL,
                target = 20_000.0,
                startDate = date("2026-07-01"),
                endDate = date("2026-09-01"),
                editMode = StatsGoalEditMode.RESTART_HISTORY,
            ),
            restartDate = date("2026-07-26"),
            nowEpochMillis = 2_000,
        )

        replacement?.id shouldBe "replacement"
        replacement?.createdAtEpochMillis shouldBe 2_000
        replacement?.updatedAtEpochMillis shouldBe 2_000
        replacement?.startDate shouldBe date("2026-07-26")
        replacement?.titleId shouldBe existing.titleId
        replacement?.mediaKind shouldBe existing.mediaKind
        replacement?.profileId shouldBe existing.profileId
        replacement?.languageTag shouldBe existing.languageTag
    }

    @Test
    fun `all-rest and incomplete weekday schedules are invalid`() {
        val allRest = DayOfWeek.entries.associateWith { 0.0 }
        createGoal(values = values(multipliers = allRest)).shouldBeNull()

        createGoal(
            values = values(
                multipliers = mapOf(DayOfWeek.MONDAY to 1.0),
            ),
        ).shouldBeNull()
    }

    @Test
    fun `goal timezone policy keeps recorded dates and uses current timezone for today`() {
        STATS_GOAL_TIMEZONE_POLICY shouldBe
            StatsGoalTimezonePolicy.RECORDED_EVENT_DATE_CURRENT_DEVICE_TODAY
    }

    @Test
    fun `display helper distinguishes duration values from counts`() {
        val duration = statsGoalDisplayValue(ACTIVE_TIME_GOAL_METRIC, 90_000.0)
        val count = statsGoalDisplayValue("gross_characters", 90_000.0)

        duration.kind shouldBe StatsGoalDisplayKind.DURATION
        duration.durationMillis() shouldBe 90_000
        count.kind shouldBe StatsGoalDisplayKind.COUNT
        count.value shouldBe 90_000.0
    }

    private fun createGoal(
        values: StatsGoalEditorValues,
        scope: StatsGoalScope = scope(),
    ) = createStatsGoal(
        id = "goal",
        values = values,
        scope = scope,
        nowEpochMillis = 1_000,
    )

    private fun values(
        kind: StatsGoalKind = StatsGoalKind.DAILY,
        metric: String = "gross_characters",
        target: Double = 10_000.0,
        startDate: ImmersionLocalDate = date("2026-07-26"),
        endDate: ImmersionLocalDate? = date("2026-08-31"),
        multipliers: Map<DayOfWeek, Double> = defaultStatsGoalWeekdayMultipliers(),
        editMode: StatsGoalEditMode = StatsGoalEditMode.PROSPECTIVE,
    ) = StatsGoalEditorValues(
        kind = kind,
        metric = metric,
        inputTarget = target,
        startDate = startDate,
        endDate = endDate,
        weekdayMultipliers = multipliers,
        editMode = editMode,
    )

    private fun scope(titleId: TitleId? = TITLE) = StatsGoalScope(
        mediaKind = MediaKind.MANGA,
        profileId = "japanese",
        languageTag = LanguageTag("ja"),
        titleId = titleId,
    )

    private fun persistedGoal(): ImmersionGoal = createGoal(
        values = values(
            kind = StatsGoalKind.DAILY,
            startDate = date("2026-07-01"),
            endDate = null,
        ),
    )!!

    private fun date(value: String) = ImmersionLocalDate.parse(value)

    private companion object {
        val TITLE = TitleId("00000000-0000-0000-0000-000000000001")
    }
}
