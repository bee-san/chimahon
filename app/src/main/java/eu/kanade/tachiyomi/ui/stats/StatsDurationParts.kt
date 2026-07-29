package eu.kanade.tachiyomi.ui.stats

internal data class StatsDurationParts(
    val hours: Long,
    val minutes: Long,
    val seconds: Long,
    val lessThanSecond: Boolean,
)

internal fun statsDurationParts(millis: Long): StatsDurationParts {
    require(millis >= 0)
    val totalSeconds = millis / 1_000
    return StatsDurationParts(
        hours = totalSeconds / 3_600,
        minutes = (totalSeconds / 60) % 60,
        seconds = totalSeconds % 60,
        lessThanSecond = millis in 1..999,
    )
}
