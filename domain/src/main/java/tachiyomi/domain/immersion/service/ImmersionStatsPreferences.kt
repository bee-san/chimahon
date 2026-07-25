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
    fun captureEnabled() = preferenceStore.getBoolean(CAPTURE_ENABLED, false)

    fun indexingEnabled() = preferenceStore.getBoolean(INDEXING_ENABLED, false)

    fun uiEnabled() = preferenceStore.getBoolean(UI_ENABLED, false)

    fun ankiSyncEnabled() = preferenceStore.getBoolean(ANKI_SYNC_ENABLED, false)

    fun goalsEnabled() = preferenceStore.getBoolean(GOALS_ENABLED, false)

    fun includeLegacyAggregates() = preferenceStore.getBoolean(INCLUDE_LEGACY_AGGREGATES, true)

    fun readerIdleTimeoutSeconds() = preferenceStore.getInt(
        READER_IDLE_TIMEOUT_SECONDS,
        DEFAULT_READER_IDLE_TIMEOUT_SECONDS,
    )

    fun videoBufferingGraceSeconds() = preferenceStore.getInt(
        VIDEO_BUFFERING_GRACE_SECONDS,
        DEFAULT_VIDEO_BUFFERING_GRACE_SECONDS,
    )

    fun rawTextRetention() = preferenceStore.getEnum(
        RAW_TEXT_RETENTION,
        RawTextRetention.UNTIL_DELETED,
    )

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
        const val DEFAULT_READER_IDLE_TIMEOUT_SECONDS = 120
        const val DEFAULT_VIDEO_BUFFERING_GRACE_SECONDS = 5

        const val CAPTURE_ENABLED = "immersion_stats_capture_enabled"
        const val INDEXING_ENABLED = "immersion_stats_indexing_enabled"
        const val UI_ENABLED = "immersion_stats_ui_enabled"
        const val ANKI_SYNC_ENABLED = "immersion_stats_anki_sync_enabled"
        const val GOALS_ENABLED = "immersion_stats_goals_enabled"
        const val INCLUDE_LEGACY_AGGREGATES = "immersion_stats_include_legacy_aggregates"
        const val READER_IDLE_TIMEOUT_SECONDS = "immersion_stats_reader_idle_timeout_seconds"
        const val VIDEO_BUFFERING_GRACE_SECONDS = "immersion_stats_video_buffering_grace_seconds"
        const val RAW_TEXT_RETENTION = "immersion_stats_raw_text_retention"
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
