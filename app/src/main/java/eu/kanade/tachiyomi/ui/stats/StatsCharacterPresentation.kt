package eu.kanade.tachiyomi.ui.stats

import tachiyomi.domain.immersion.model.AnalyticsCharacterSummary
import tachiyomi.domain.immersion.model.ImmersionAnkiItem
import tachiyomi.domain.immersion.model.UnicodeCodePoint
import tachiyomi.domain.immersion.repository.ImmersionAnkiRepository
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.ln

internal fun characterFrequencyLevel(
    occurrenceCount: Long,
    maximumOccurrenceCount: Long,
    levels: Int = 5,
): Int {
    require(occurrenceCount >= 0)
    require(maximumOccurrenceCount >= 0)
    require(levels > 0)
    if (occurrenceCount == 0L || maximumOccurrenceCount == 0L) return 0
    val ratio = ln(occurrenceCount.toDouble() + 1.0) /
        ln(maximumOccurrenceCount.toDouble() + 1.0)
    return ceil(ratio * levels).toInt().coerceIn(1, levels)
}

internal fun characterCoverageTarget(
    summary: AnalyticsCharacterSummary,
    targetPercent: Int,
    planningDays: Int = 30,
): CharacterCoverageTarget {
    require(targetPercent in 1..100)
    require(planningDays > 0)
    val target = ceil(summary.distinctCharacters * targetPercent / 100.0).toLong()
    val remaining = (target - summary.matureInAnki).coerceAtLeast(0)
    return CharacterCoverageTarget(
        targetCharacters = target,
        remainingCharacters = remaining,
        dailyPlanningSuggestion = ceil(remaining / planningDays.toDouble()).toLong(),
    )
}

internal fun characterDisplayText(
    rendered: String,
    codePoint: UnicodeCodePoint,
    hasGlyph: (String) -> Boolean,
): String =
    rendered.takeIf { it.isNotBlank() && hasGlyph(it) }
        ?: "U+%04X".format(Locale.ROOT, codePoint.value)

internal suspend fun loadCharacterAnkiItems(
    repository: ImmersionAnkiRepository,
    profileIds: Collection<String>,
    codePoint: UnicodeCodePoint,
): List<ImmersionAnkiItem> = repository.findCharacterItems(
    profileIds = profileIds
        .filter(String::isNotBlank)
        .distinct(),
    codePoint = codePoint,
).distinctBy { it.snapshotId to it.cardId }

internal data class CharacterCoverageTarget(
    val targetCharacters: Long,
    val remainingCharacters: Long,
    val dailyPlanningSuggestion: Long,
)
