package tachiyomi.domain.immersion.service

import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
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

    companion object {
        const val DEFAULT_READER_IDLE_TIMEOUT_SECONDS = 120
        const val DEFAULT_VIDEO_BUFFERING_GRACE_SECONDS = 5

        const val CAPTURE_ENABLED = "immersion_stats_capture_enabled"
        const val INDEXING_ENABLED = "immersion_stats_indexing_enabled"
        const val UI_ENABLED = "immersion_stats_ui_enabled"
        const val ANKI_SYNC_ENABLED = "immersion_stats_anki_sync_enabled"
        const val GOALS_ENABLED = "immersion_stats_goals_enabled"
        const val READER_IDLE_TIMEOUT_SECONDS = "immersion_stats_reader_idle_timeout_seconds"
        const val VIDEO_BUFFERING_GRACE_SECONDS = "immersion_stats_video_buffering_grace_seconds"
        const val RAW_TEXT_RETENTION = "immersion_stats_raw_text_retention"
        const val NOVEL_NET_PROGRESS_POLICY = "immersion_stats_novel_net_progress_policy"
    }
}
