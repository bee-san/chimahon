// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

internal const val DEFAULT_STATS_READER_IDLE_TIMEOUT_SECONDS = 120

internal val STATS_READER_IDLE_TIMEOUT_SECONDS = listOf(30, 60, 120, 300)

internal enum class StatsReaderIdleTimeoutUnit {
    SECONDS,
    MINUTES,
}

internal data class StatsReaderIdleTimeoutDisplay(
    val amount: Int,
    val unit: StatsReaderIdleTimeoutUnit,
)

internal fun validatedStatsReaderIdleTimeoutSeconds(seconds: Int): Int? =
    seconds.takeIf { it in STATS_READER_IDLE_TIMEOUT_SECONDS }

internal fun normalizeStatsReaderIdleTimeoutSeconds(seconds: Int): Int =
    validatedStatsReaderIdleTimeoutSeconds(seconds) ?: DEFAULT_STATS_READER_IDLE_TIMEOUT_SECONDS

internal fun statsReaderIdleTimeoutDisplay(seconds: Int): StatsReaderIdleTimeoutDisplay {
    val validated = requireNotNull(validatedStatsReaderIdleTimeoutSeconds(seconds)) {
        "Unsupported reader idle timeout"
    }
    return if (validated < 60) {
        StatsReaderIdleTimeoutDisplay(validated, StatsReaderIdleTimeoutUnit.SECONDS)
    } else {
        StatsReaderIdleTimeoutDisplay(validated / 60, StatsReaderIdleTimeoutUnit.MINUTES)
    }
}
