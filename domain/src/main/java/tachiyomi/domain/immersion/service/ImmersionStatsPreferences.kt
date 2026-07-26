// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.service

import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.domain.immersion.model.AnalyticsBucketScale
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.CharacterMetric
import tachiyomi.domain.immersion.model.NovelNetProgressPolicy
import tachiyomi.domain.immersion.model.RawTextRetention

class ImmersionStatsPreferences(
    private val preferenceStore: PreferenceStore,
) {
    private val captureEnabledPreference = preferenceStore.getBoolean(CAPTURE_ENABLED, true)
    private val indexingEnabledPreference = preferenceStore.getBoolean(INDEXING_ENABLED, true)
    private val uiEnabledPreference = preferenceStore.getBoolean(UI_ENABLED, true)
    private val ankiSyncEnabledPreference = preferenceStore.getBoolean(ANKI_SYNC_ENABLED, true)
    private val goalsEnabledPreference = preferenceStore.getBoolean(GOALS_ENABLED, true)
    private val legacyWritesEnabledPreference = preferenceStore.getBoolean(LEGACY_WRITES_ENABLED, false)
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
        if (rolloutVersion < FEATURE_ROLLOUT_VERSION) {
            captureEnabled().set(true)
            indexingEnabled().set(true)
            uiEnabled().set(true)
            ankiSyncEnabled().set(true)
            goalsEnabled().set(true)
            legacyWritesEnabled().set(false)
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

    fun dashboardMediaKind() = preferenceStore.getString(DASHBOARD_MEDIA_KIND, "")

    fun dashboardProfileId() = preferenceStore.getString(DASHBOARD_PROFILE_ID, "")

    fun dashboardCharacterMetric() = preferenceStore.getEnum(
        DASHBOARD_CHARACTER_METRIC,
        CharacterMetric.GROSS,
    )

    fun dashboardIncludeRereads() = preferenceStore.getBoolean(DASHBOARD_INCLUDE_REREADS, true)

    fun dashboardSelectedTab() = preferenceStore.getString(DASHBOARD_SELECTED_TAB, "OVERVIEW")

    fun dashboardTrendScale() = preferenceStore.getEnum(
        DASHBOARD_TREND_SCALE,
        AnalyticsBucketScale.DAY,
    )

    fun dashboardTitleSort() = preferenceStore.getEnum(
        DASHBOARD_TITLE_SORT,
        AnalyticsSort.MOST_TIME,
    )

    fun dashboardVocabularySort() = preferenceStore.getEnum(
        DASHBOARD_VOCABULARY_SORT,
        AnalyticsSort.MOST_OCCURRENCES,
    )

    fun dashboardCharacterSort() = preferenceStore.getEnum(
        DASHBOARD_CHARACTER_SORT,
        AnalyticsSort.MOST_OCCURRENCES,
    )

    companion object {
        const val CURRENT_ROLLOUT_VERSION = 2
        const val CURRENT_RAW_TEXT_DISCLOSURE_VERSION = 1
        const val DEFAULT_READER_IDLE_TIMEOUT_SECONDS = 120
        const val DEFAULT_VIDEO_BUFFERING_GRACE_SECONDS = 5
        private const val FEATURE_ROLLOUT_VERSION = 1
        private const val RAW_TEXT_DISCLOSURE_ROLLOUT_VERSION = 2

        const val CAPTURE_ENABLED = "immersion_stats_capture_enabled"
        const val INDEXING_ENABLED = "immersion_stats_indexing_enabled"
        const val UI_ENABLED = "immersion_stats_ui_enabled"
        const val ANKI_SYNC_ENABLED = "immersion_stats_anki_sync_enabled"
        const val GOALS_ENABLED = "immersion_stats_goals_enabled"
        const val LEGACY_WRITES_ENABLED = "immersion_stats_legacy_writes_enabled"
        const val ROLLOUT_VERSION = "immersion_stats_rollout_version"
        const val INCLUDE_LEGACY_AGGREGATES = "immersion_stats_include_legacy_aggregates"
        const val READER_IDLE_TIMEOUT_SECONDS = "immersion_stats_reader_idle_timeout_seconds"
        const val VIDEO_BUFFERING_GRACE_SECONDS = "immersion_stats_video_buffering_grace_seconds"
        const val RAW_TEXT_RETENTION = "immersion_stats_raw_text_retention"
        const val RAW_TEXT_DISCLOSURE_VERSION = "immersion_stats_raw_text_disclosure_version"
        const val NOVEL_NET_PROGRESS_POLICY = "immersion_stats_novel_net_progress_policy"
        const val DASHBOARD_RANGE_PRESET = "immersion_stats_dashboard_range_preset"
        const val DASHBOARD_MEDIA_KIND = "immersion_stats_dashboard_media_kind"
        const val DASHBOARD_PROFILE_ID = "immersion_stats_dashboard_profile_id"
        const val DASHBOARD_CHARACTER_METRIC = "immersion_stats_dashboard_character_metric"
        const val DASHBOARD_INCLUDE_REREADS = "immersion_stats_dashboard_include_rereads"
        const val DASHBOARD_SELECTED_TAB = "immersion_stats_dashboard_selected_tab"
        const val DASHBOARD_TREND_SCALE = "immersion_stats_dashboard_trend_scale"
        const val DASHBOARD_TITLE_SORT = "immersion_stats_dashboard_title_sort"
        const val DASHBOARD_VOCABULARY_SORT = "immersion_stats_dashboard_vocabulary_sort"
        const val DASHBOARD_CHARACTER_SORT = "immersion_stats_dashboard_character_sort"
    }
}
