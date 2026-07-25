package eu.kanade.tachiyomi.ui.player.scene

import android.content.Context
import androidx.core.content.edit

/**
 * Persists numeric scene-capture measurements without retaining media identity, input URLs,
 * headers, or FFmpeg options.
 */
internal class SceneCaptureMetricsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun record(metrics: SceneCaptureMetrics) {
        if (!metrics.isValid()) return

        synchronized(PERSISTENCE_LOCK) {
            val sampleCount = preferences.getLong(KEY_SAMPLE_COUNT, 0L).saturatedAdd(1L)
            val totalFrameCount = preferences.getLong(KEY_TOTAL_FRAME_COUNT, 0L)
                .saturatedAdd(metrics.frameCount.toLong())
            val totalOutputBytes = preferences.getLong(KEY_TOTAL_OUTPUT_BYTES, 0L)
                .saturatedAdd(metrics.outputBytes)
            val totalOutputDurationMillis = preferences.getLong(KEY_TOTAL_OUTPUT_DURATION_MILLIS, 0L)
                .saturatedAdd(metrics.outputDurationMillis)
            val totalWallTimeMillis = preferences.getLong(KEY_TOTAL_WALL_TIME_MILLIS, 0L)
                .saturatedAdd(metrics.wallTimeMillis)

            preferences.edit {
                putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                putLong(KEY_SAMPLE_COUNT, sampleCount)
                putLong(KEY_TOTAL_FRAME_COUNT, totalFrameCount)
                putLong(KEY_TOTAL_OUTPUT_BYTES, totalOutputBytes)
                putLong(KEY_TOTAL_OUTPUT_DURATION_MILLIS, totalOutputDurationMillis)
                putLong(KEY_TOTAL_WALL_TIME_MILLIS, totalWallTimeMillis)
                putInt(KEY_LAST_FRAME_COUNT, metrics.frameCount)
                putLong(KEY_LAST_OUTPUT_BYTES, metrics.outputBytes)
                putLong(KEY_LAST_OUTPUT_DURATION_MILLIS, metrics.outputDurationMillis)
                putLong(KEY_LAST_WALL_TIME_MILLIS, metrics.wallTimeMillis)
                putLong(
                    KEY_MAX_OUTPUT_BYTES,
                    maxOf(preferences.getLong(KEY_MAX_OUTPUT_BYTES, 0L), metrics.outputBytes),
                )
                putLong(
                    KEY_MAX_WALL_TIME_MILLIS,
                    maxOf(preferences.getLong(KEY_MAX_WALL_TIME_MILLIS, 0L), metrics.wallTimeMillis),
                )
            }
        }
    }

    private fun SceneCaptureMetrics.isValid(): Boolean {
        return frameCount in SCENE_MIN_FRAME_COUNT..SCENE_MAX_FRAME_COUNT &&
            outputBytes in 1..SCENE_MAX_OUTPUT_BYTES &&
            outputDurationMillis > 0L &&
            wallTimeMillis >= 0L
    }

    private fun Long.saturatedAdd(value: Long): Long {
        if (value < 0L) return Long.MAX_VALUE
        if (this < 0L) return value
        if (this > Long.MAX_VALUE - value) return Long.MAX_VALUE
        return this + value
    }

    private companion object {
        private const val PREFERENCES_NAME = "scene_capture_metrics"
        private const val SCHEMA_VERSION = 1
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_SAMPLE_COUNT = "sample_count"
        private const val KEY_TOTAL_FRAME_COUNT = "total_frame_count"
        private const val KEY_TOTAL_OUTPUT_BYTES = "total_output_bytes"
        private const val KEY_TOTAL_OUTPUT_DURATION_MILLIS = "total_output_duration_ms"
        private const val KEY_TOTAL_WALL_TIME_MILLIS = "total_wall_time_ms"
        private const val KEY_LAST_FRAME_COUNT = "last_frame_count"
        private const val KEY_LAST_OUTPUT_BYTES = "last_output_bytes"
        private const val KEY_LAST_OUTPUT_DURATION_MILLIS = "last_output_duration_ms"
        private const val KEY_LAST_WALL_TIME_MILLIS = "last_wall_time_ms"
        private const val KEY_MAX_OUTPUT_BYTES = "max_output_bytes"
        private const val KEY_MAX_WALL_TIME_MILLIS = "max_wall_time_ms"
        private val PERSISTENCE_LOCK = Any()
    }
}
