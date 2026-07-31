package eu.kanade.tachiyomi.ui.stats

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.AnalyticsTitleCoverage
import tachiyomi.domain.immersion.model.AnalyticsTitleDayHighlights
import tachiyomi.domain.immersion.model.AnalyticsTitleRow
import tachiyomi.domain.immersion.model.AnalyticsTitleUnitProgress
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.ImmersionSession
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.MillisecondDuration
import tachiyomi.domain.immersion.model.NetCharacterProgress
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.ReadingMetrics
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SessionStatus
import tachiyomi.domain.immersion.model.TitleId

class StatsTitleMaintenanceScreenModelTest {

    private val sourceTitleId = TitleId("00000000-0000-0000-0000-000000000001")

    @Test
    fun `split request trims input and preserves inclusive date range`() {
        val request = createTitleSplitRequest(
            sourceTitleId = sourceTitleId,
            displayTitle = "  Second season  ",
            startDate = " 2026-01-02 ",
            endDate = "2026-03-04",
        )

        request?.sourceTitleId shouldBe sourceTitleId
        request?.displayTitle shouldBe "Second season"
        request?.dateRange?.start shouldBe ImmersionLocalDate.parse("2026-01-02")
        request?.dateRange?.endInclusive shouldBe ImmersionLocalDate.parse("2026-03-04")
    }

    @Test
    fun `split request rejects blank title malformed dates and reversed range`() {
        createTitleSplitRequest(sourceTitleId, " ", "2026-01-01", "2026-01-02").shouldBeNull()
        createTitleSplitRequest(sourceTitleId, "Title", "not-a-date", "2026-01-02").shouldBeNull()
        createTitleSplitRequest(sourceTitleId, "Title", "2026-01-03", "2026-01-02").shouldBeNull()
    }

    @Test
    fun `session relink targets exclude current and incompatible titles and search stable identity`() {
        val compatible = title(
            id = "00000000-0000-0000-0000-000000000002",
            displayTitle = "Compatible",
            sourceKey = "novel:match-key",
        )
        val titles = listOf(
            title(sourceTitleId.value, "Current", "novel:current"),
            compatible,
            title(
                id = "00000000-0000-0000-0000-000000000003",
                displayTitle = "Wrong media",
                sourceKey = "manga:wrong",
                mediaKind = MediaKind.MANGA,
            ),
            title(
                id = "00000000-0000-0000-0000-000000000004",
                displayTitle = "Wrong profile",
                sourceKey = "novel:profile",
                profileId = "other",
            ),
            title(
                id = "00000000-0000-0000-0000-000000000005",
                displayTitle = "Wrong language",
                sourceKey = "novel:language",
                languageTag = LanguageTag("ko"),
            ),
        )

        sessionRelinkTargets(session(), titles, "match-key") shouldContainExactly
            listOf(compatible)
        sessionRelinkTargets(session(), titles, "", limit = 1) shouldContainExactly
            listOf(compatible)
    }

    private fun session() = ImmersionSession(
        id = SessionId("00000000-0000-0000-0000-000000000100"),
        deviceId = "device",
        titleId = sourceTitleId,
        mediaKind = MediaKind.NOVEL,
        languageTag = LanguageTag("ja"),
        profileId = "jp",
        startedAtEpochMillis = 1_000,
        endedAtEpochMillis = 2_000,
        startZoneId = "UTC",
        startOffsetSeconds = 0,
        status = SessionStatus.COMPLETED,
        activeDuration = MillisecondDuration(1_000),
        elapsedDuration = MillisecondDuration(1_000),
        grossCharacters = NonNegativeCounter.ZERO,
        uniqueSourceCharacters = NonNegativeCounter.ZERO,
        netCharacters = NetCharacterProgress.ZERO,
        sourceUnitCount = NonNegativeCounter.ZERO,
        lastSequence = 0,
        lastHeartbeatAtEpochMillis = 1_000,
        captureVersion = 1,
        schemaVersion = 1,
        legacyImport = false,
    )

    private fun title(
        id: String,
        displayTitle: String,
        sourceKey: String,
        mediaKind: MediaKind = MediaKind.NOVEL,
        profileId: String = "jp",
        languageTag: LanguageTag? = LanguageTag("ja"),
    ) = AnalyticsTitleRow(
        titleId = TitleId(id),
        displayTitle = displayTitle,
        mediaKind = mediaKind,
        sourceKey = sourceKey,
        profileId = profileId,
        languageTag = languageTag,
        libraryId = null,
        trackerId = null,
        mediaId = null,
        status = null,
        totalUnits = null,
        totalCharacterEstimate = null,
        deletedAtEpochMillis = null,
        metrics = ReadingMetrics(),
        coverage = AnalyticsTitleCoverage(),
        firstActiveDate = ImmersionLocalDate(1),
        lastActiveDate = ImmersionLocalDate(1),
        activeDays = 1,
        calendarSpanDays = 1,
        averageCharactersPerActiveDay = 0.0,
        averageActiveTimePerActiveDayMillis = 0.0,
        dayHighlights = AnalyticsTitleDayHighlights(null, null, null),
        unitProgress = AnalyticsTitleUnitProgress(),
        estimate = null,
        speedRankingEligible = false,
        progress = null,
        completed = null,
    )
}
