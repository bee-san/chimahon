// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.service

import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.domain.immersion.model.AnalyticsBucketScale
import tachiyomi.domain.immersion.model.AnalyticsCharacterPriorityMode
import tachiyomi.domain.immersion.model.AnalyticsCharacterRange
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.AnalyticsTitleAcquisitionBucketSize
import tachiyomi.domain.immersion.model.AnalyticsTitleCoverageFilter
import tachiyomi.domain.immersion.model.AnalyticsTitleSort
import tachiyomi.domain.immersion.model.AnalyticsTitleStateFilter
import tachiyomi.domain.immersion.model.CharacterMetric
import tachiyomi.domain.immersion.model.NovelNetProgressPolicy
import tachiyomi.domain.immersion.model.RawTextRetention

class ImmersionStatsPreferences(
    private val preferenceStore: PreferenceStore,
) {
    private val captureEnabledPreference = preferenceStore.getBoolean(CAPTURE_ENABLED, true)
    private val indexingEnabledPreference = preferenceStore.getBoolean(INDEXING_ENABLED, true)
    private val uiEnabledPreference = preferenceStore.getBoolean(UI_ENABLED, false)
    private val ankiSyncEnabledPreference = preferenceStore.getBoolean(ANKI_SYNC_ENABLED, false)
    private val goalsEnabledPreference = preferenceStore.getBoolean(GOALS_ENABLED, false)
    private val goalRemindersEnabledPreference = preferenceStore.getBoolean(
        GOAL_REMINDERS_ENABLED,
        false,
    )
    private val legacyWritesEnabledPreference = preferenceStore.getBoolean(LEGACY_WRITES_ENABLED, true)
    private val rolloutVersionPreference = preferenceStore.getInt(ROLLOUT_VERSION, 0)
    private val rawTextRetentionPreference = preferenceStore.getEnum(
        RAW_TEXT_RETENTION,
        RawTextRetention.NEVER,
    )
    private val rawTextDisclosureVersionPreference = preferenceStore.getInt(
        RAW_TEXT_DISCLOSURE_VERSION,
        0,
    )

    fun applyReleaseRolloutDefaults() {
        val rolloutVersion = rolloutVersionPreference.get()
        if (rolloutVersion >= CURRENT_ROLLOUT_VERSION) return
        if (rolloutVersion < SAFE_SHADOW_ROLLOUT_VERSION) {
            captureEnabled().set(true)
            indexingEnabled().set(true)
            uiEnabled().set(false)
            ankiSyncEnabled().set(false)
            goalsEnabled().set(false)
            goalRemindersEnabled().set(false)
            legacyWritesEnabled().set(true)
        }
        if (
            rolloutVersion < RAW_TEXT_DISCLOSURE_ROLLOUT_VERSION &&
            rawTextRetentionPreference.isSet()
        ) {
            rawTextDisclosureVersionPreference.set(CURRENT_RAW_TEXT_DISCLOSURE_VERSION)
        }
        rolloutVersionPreference.set(CURRENT_ROLLOUT_VERSION)
    }

    fun captureEnabled() = captureEnabledPreference

    fun indexingEnabled() = indexingEnabledPreference

    fun uiEnabled() = uiEnabledPreference

    fun ankiSyncEnabled() = ankiSyncEnabledPreference

    fun goalsEnabled() = goalsEnabledPreference

    fun goalRemindersEnabled() = goalRemindersEnabledPreference

    fun lastGoalReminderEpochDay() = preferenceStore.getLong(LAST_GOAL_REMINDER_EPOCH_DAY, -1)

    fun legacyWritesEnabled() = legacyWritesEnabledPreference

    fun includeLegacyAggregates() = preferenceStore.getBoolean(INCLUDE_LEGACY_AGGREGATES, true)

    fun readerIdleTimeoutSeconds() = preferenceStore.getInt(
        READER_IDLE_TIMEOUT_SECONDS,
        DEFAULT_READER_IDLE_TIMEOUT_SECONDS,
    )

    fun videoBufferingGraceSeconds() = preferenceStore.getInt(
        VIDEO_BUFFERING_GRACE_SECONDS,
        DEFAULT_VIDEO_BUFFERING_GRACE_SECONDS,
    )

    fun rawTextRetention() = rawTextRetentionPreference

    fun rawTextDisclosureRequired(): Boolean =
        rawTextDisclosureVersionPreference.get() < CURRENT_RAW_TEXT_DISCLOSURE_VERSION

    fun effectiveRawTextRetention(): RawTextRetention =
        if (rawTextDisclosureRequired()) {
            RawTextRetention.NEVER
        } else {
            rawTextRetentionPreference.get()
        }

    fun acknowledgeRawTextDisclosure(retention: RawTextRetention) {
        rawTextRetentionPreference.set(retention)
        rawTextDisclosureVersionPreference.set(CURRENT_RAW_TEXT_DISCLOSURE_VERSION)
    }

    fun novelNetProgressPolicy() = preferenceStore.getEnum(
        NOVEL_NET_PROGRESS_POLICY,
        NovelNetProgressPolicy.SIGNED_POSITION_DELTA,
    )

    fun dashboardRangePreset() = preferenceStore.getString(DASHBOARD_RANGE_PRESET, "TODAY")

    fun dashboardPeriodOffset() = preferenceStore.getInt(DASHBOARD_PERIOD_OFFSET, 0)

    fun dashboardCustomStart() = preferenceStore.getString(DASHBOARD_CUSTOM_START, "")

    fun dashboardCustomEnd() = preferenceStore.getString(DASHBOARD_CUSTOM_END, "")

    fun dashboardMediaKind() = preferenceStore.getString(DASHBOARD_MEDIA_KIND, "")

    fun dashboardProfileId() = preferenceStore.getString(DASHBOARD_PROFILE_ID, "")

    fun dashboardTitleId() = preferenceStore.getString(DASHBOARD_TITLE_ID, "")

    fun dashboardCharacterMetric() = preferenceStore.getEnum(
        DASHBOARD_CHARACTER_METRIC,
        CharacterMetric.GROSS,
    )

    fun dashboardIncludeRereads() = preferenceStore.getBoolean(DASHBOARD_INCLUDE_REREADS, true)

    fun dashboardMaturityTiers() = preferenceStore.getStringSet(DASHBOARD_MATURITY_TIERS, emptySet())

    fun dashboardProvenanceStates() = preferenceStore.getStringSet(DASHBOARD_PROVENANCE_STATES, emptySet())

    fun dashboardSelectedTab() = preferenceStore.getString(DASHBOARD_SELECTED_TAB, "OVERVIEW")

    fun dashboardTrendScale() = preferenceStore.getEnum(
        DASHBOARD_TREND_SCALE,
        AnalyticsBucketScale.DAY,
    )

    fun dashboardTrendMetric() = preferenceStore.getString(DASHBOARD_TREND_METRIC, "CHARACTERS")

    fun dashboardTitleSort() = preferenceStore.getEnum(
        DASHBOARD_TITLE_SORT,
        AnalyticsTitleSort.MOST_TIME,
    )

    fun dashboardTitleState() = preferenceStore.getEnum(
        DASHBOARD_TITLE_STATE,
        AnalyticsTitleStateFilter.ALL,
    )

    fun dashboardTitleCoverage() = preferenceStore.getEnum(
        DASHBOARD_TITLE_COVERAGE,
        AnalyticsTitleCoverageFilter.ALL,
    )

    fun dashboardTitleAcquisitionBucketSize() = preferenceStore.getEnum(
        DASHBOARD_TITLE_ACQUISITION_BUCKET_SIZE,
        AnalyticsTitleAcquisitionBucketSize.TEN_THOUSAND,
    )

    fun dashboardVocabularySort() = preferenceStore.getEnum(
        DASHBOARD_VOCABULARY_SORT,
        AnalyticsSort.MOST_OCCURRENCES,
    )

    fun dashboardVocabularyKnownness() =
        preferenceStore.getString(DASHBOARD_VOCABULARY_KNOWNNESS, "ALL")

    fun dashboardVocabularyScripts() =
        preferenceStore.getStringSet(DASHBOARD_VOCABULARY_SCRIPTS, emptySet())

    fun dashboardVocabularyCategories() =
        preferenceStore.getStringSet(DASHBOARD_VOCABULARY_CATEGORIES, emptySet())

    fun dashboardVocabularyPartOfSpeech() =
        preferenceStore.getString(DASHBOARD_VOCABULARY_PART_OF_SPEECH, "")

    fun dashboardVocabularyMinimumOccurrences() =
        preferenceStore.getLong(DASHBOARD_VOCABULARY_MINIMUM_OCCURRENCES, -1)

    fun dashboardVocabularyMaximumOccurrences() =
        preferenceStore.getLong(DASHBOARD_VOCABULARY_MAXIMUM_OCCURRENCES, -1)

    fun dashboardVocabularyMaximumFrequencyRank() =
        preferenceStore.getLong(DASHBOARD_VOCABULARY_MAXIMUM_FREQUENCY_RANK, -1)

    fun dashboardVocabularyExclusion() =
        preferenceStore.getString(DASHBOARD_VOCABULARY_EXCLUSION, "INCLUDED")

    fun dashboardCharacterSort() = preferenceStore.getEnum(
        DASHBOARD_CHARACTER_SORT,
        AnalyticsSort.MOST_OCCURRENCES,
    )

    fun dashboardCharacterScripts() =
        preferenceStore.getStringSet(DASHBOARD_CHARACTER_SCRIPTS, emptySet())

    fun dashboardCharacterRange() = preferenceStore.getEnum(
        DASHBOARD_CHARACTER_RANGE,
        AnalyticsCharacterRange.ENCOUNTERED,
    )

    fun dashboardCharacterPriorityMode() = preferenceStore.getEnum(
        DASHBOARD_CHARACTER_PRIORITY_MODE,
        AnalyticsCharacterPriorityMode.MIXED,
    )

    fun dashboardCharacterMaximumMissingFrequencyRank() =
        preferenceStore.getLong(DASHBOARD_CHARACTER_MAXIMUM_MISSING_FREQUENCY_RANK, 10_000)

    fun dashboardCharacterGridMode() =
        preferenceStore.getString(DASHBOARD_CHARACTER_GRID_MODE, "FREQUENCY")

    fun dashboardCharacterLayout() =
        preferenceStore.getString(DASHBOARD_CHARACTER_LAYOUT, "GRID")

    fun dashboardCharacterCoverageTargetPercent() =
        preferenceStore.getInt(DASHBOARD_CHARACTER_COVERAGE_TARGET_PERCENT, 90)

    fun dashboardAnkiWordCoverageTargetPercent() =
        preferenceStore.getInt(DASHBOARD_ANKI_WORD_COVERAGE_TARGET_PERCENT, 90)

    fun dashboardSelectedTitleId() = preferenceStore.getString(DASHBOARD_SELECTED_TITLE_ID, "")

    fun dashboardSelectedWordId() = preferenceStore.getString(DASHBOARD_SELECTED_WORD_ID, "")

    fun dashboardSelectedCharacter() = preferenceStore.getInt(DASHBOARD_SELECTED_CHARACTER, -1)

    fun dashboardSelectedSessionId() = preferenceStore.getString(DASHBOARD_SELECTED_SESSION_ID, "")

    companion object {
        const val CURRENT_ROLLOUT_VERSION = 3
        const val CURRENT_RAW_TEXT_DISCLOSURE_VERSION = 1
        const val DEFAULT_READER_IDLE_TIMEOUT_SECONDS = 120
        const val DEFAULT_VIDEO_BUFFERING_GRACE_SECONDS = 5
        private const val RAW_TEXT_DISCLOSURE_ROLLOUT_VERSION = 2
        private const val SAFE_SHADOW_ROLLOUT_VERSION = 3

        const val CAPTURE_ENABLED = "immersion_stats_capture_enabled"
        const val INDEXING_ENABLED = "immersion_stats_indexing_enabled"
        const val UI_ENABLED = "immersion_stats_ui_enabled"
        const val ANKI_SYNC_ENABLED = "immersion_stats_anki_sync_enabled"
        const val GOALS_ENABLED = "immersion_stats_goals_enabled"
        const val GOAL_REMINDERS_ENABLED = "immersion_stats_goal_reminders_enabled"
        const val LAST_GOAL_REMINDER_EPOCH_DAY = "immersion_stats_last_goal_reminder_epoch_day"
        const val LEGACY_WRITES_ENABLED = "immersion_stats_legacy_writes_enabled"
        const val ROLLOUT_VERSION = "immersion_stats_rollout_version"
        const val INCLUDE_LEGACY_AGGREGATES = "immersion_stats_include_legacy_aggregates"
        const val READER_IDLE_TIMEOUT_SECONDS = "immersion_stats_reader_idle_timeout_seconds"
        const val VIDEO_BUFFERING_GRACE_SECONDS = "immersion_stats_video_buffering_grace_seconds"
        const val RAW_TEXT_RETENTION = "immersion_stats_raw_text_retention"
        const val RAW_TEXT_DISCLOSURE_VERSION = "immersion_stats_raw_text_disclosure_version"
        const val NOVEL_NET_PROGRESS_POLICY = "immersion_stats_novel_net_progress_policy"
        const val DASHBOARD_RANGE_PRESET = "immersion_stats_dashboard_range_preset"
        const val DASHBOARD_PERIOD_OFFSET = "immersion_stats_dashboard_period_offset"
        const val DASHBOARD_CUSTOM_START = "immersion_stats_dashboard_custom_start"
        const val DASHBOARD_CUSTOM_END = "immersion_stats_dashboard_custom_end"
        const val DASHBOARD_MEDIA_KIND = "immersion_stats_dashboard_media_kind"
        const val DASHBOARD_PROFILE_ID = "immersion_stats_dashboard_profile_id"
        const val DASHBOARD_TITLE_ID = "immersion_stats_dashboard_title_id"
        const val DASHBOARD_CHARACTER_METRIC = "immersion_stats_dashboard_character_metric"
        const val DASHBOARD_INCLUDE_REREADS = "immersion_stats_dashboard_include_rereads"
        const val DASHBOARD_MATURITY_TIERS = "immersion_stats_dashboard_maturity_tiers"
        const val DASHBOARD_PROVENANCE_STATES = "immersion_stats_dashboard_provenance_states"
        const val DASHBOARD_SELECTED_TAB = "immersion_stats_dashboard_selected_tab"
        const val DASHBOARD_TREND_SCALE = "immersion_stats_dashboard_trend_scale"
        const val DASHBOARD_TREND_METRIC = "immersion_stats_dashboard_trend_metric"
        const val DASHBOARD_TITLE_SORT = "immersion_stats_dashboard_title_sort"
        const val DASHBOARD_TITLE_STATE = "immersion_stats_dashboard_title_state"
        const val DASHBOARD_TITLE_COVERAGE = "immersion_stats_dashboard_title_coverage"
        const val DASHBOARD_TITLE_ACQUISITION_BUCKET_SIZE =
            "immersion_stats_dashboard_title_acquisition_bucket_size"
        const val DASHBOARD_VOCABULARY_SORT = "immersion_stats_dashboard_vocabulary_sort"
        const val DASHBOARD_VOCABULARY_KNOWNNESS =
            "immersion_stats_dashboard_vocabulary_knownness"
        const val DASHBOARD_VOCABULARY_SCRIPTS =
            "immersion_stats_dashboard_vocabulary_scripts"
        const val DASHBOARD_VOCABULARY_CATEGORIES =
            "immersion_stats_dashboard_vocabulary_categories"
        const val DASHBOARD_VOCABULARY_PART_OF_SPEECH =
            "immersion_stats_dashboard_vocabulary_part_of_speech"
        const val DASHBOARD_VOCABULARY_MINIMUM_OCCURRENCES =
            "immersion_stats_dashboard_vocabulary_minimum_occurrences"
        const val DASHBOARD_VOCABULARY_MAXIMUM_OCCURRENCES =
            "immersion_stats_dashboard_vocabulary_maximum_occurrences"
        const val DASHBOARD_VOCABULARY_MAXIMUM_FREQUENCY_RANK =
            "immersion_stats_dashboard_vocabulary_maximum_frequency_rank"
        const val DASHBOARD_VOCABULARY_EXCLUSION =
            "immersion_stats_dashboard_vocabulary_exclusion"
        const val DASHBOARD_CHARACTER_SORT = "immersion_stats_dashboard_character_sort"
        const val DASHBOARD_CHARACTER_SCRIPTS = "immersion_stats_dashboard_character_scripts"
        const val DASHBOARD_CHARACTER_RANGE = "immersion_stats_dashboard_character_range"
        const val DASHBOARD_CHARACTER_PRIORITY_MODE =
            "immersion_stats_dashboard_character_priority_mode"
        const val DASHBOARD_CHARACTER_MAXIMUM_MISSING_FREQUENCY_RANK =
            "immersion_stats_dashboard_character_maximum_missing_frequency_rank"
        const val DASHBOARD_CHARACTER_GRID_MODE =
            "immersion_stats_dashboard_character_grid_mode"
        const val DASHBOARD_CHARACTER_LAYOUT = "immersion_stats_dashboard_character_layout"
        const val DASHBOARD_CHARACTER_COVERAGE_TARGET_PERCENT =
            "immersion_stats_dashboard_character_coverage_target_percent"
        const val DASHBOARD_ANKI_WORD_COVERAGE_TARGET_PERCENT =
            "immersion_stats_dashboard_anki_word_coverage_target_percent"
        const val DASHBOARD_SELECTED_TITLE_ID = "immersion_stats_dashboard_selected_title_id"
        const val DASHBOARD_SELECTED_WORD_ID = "immersion_stats_dashboard_selected_word_id"
        const val DASHBOARD_SELECTED_CHARACTER = "immersion_stats_dashboard_selected_character"
        const val DASHBOARD_SELECTED_SESSION_ID = "immersion_stats_dashboard_selected_session_id"
    }
}
