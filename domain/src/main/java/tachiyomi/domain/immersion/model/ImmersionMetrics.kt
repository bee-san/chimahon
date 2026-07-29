package tachiyomi.domain.immersion.model

import kotlinx.serialization.Serializable

@Serializable
data class CharacterVolume(
    val gross: NonNegativeCounter = NonNegativeCounter.ZERO,
    val uniqueSource: NonNegativeCounter = NonNegativeCounter.ZERO,
    val netProgress: NetCharacterProgress = NetCharacterProgress.ZERO,
) {
    fun valueFor(metric: CharacterMetric): Long = when (metric) {
        CharacterMetric.GROSS -> gross.value
        CharacterMetric.UNIQUE_SOURCE -> uniqueSource.value
        CharacterMetric.NET_PROGRESS -> netProgress.value
    }
}

@Serializable
data class CharacterCoverage(
    val encounteredTargetScriptCharacters: NonNegativeCounter = NonNegativeCounter.ZERO,
    val representedInAnki: NonNegativeCounter = NonNegativeCounter.ZERO,
) {
    init {
        require(representedInAnki.value <= encounteredTargetScriptCharacters.value) {
            "Anki-represented characters cannot exceed encountered target-script characters"
        }
    }

    fun ratio(): Double? {
        if (encounteredTargetScriptCharacters.value == 0L) return null
        return representedInAnki.value.toDouble() / encounteredTargetScriptCharacters.value.toDouble()
    }
}

@Serializable
data class ReadingMetrics(
    val activeTime: MillisecondDuration = MillisecondDuration(0),
    val characters: CharacterVolume = CharacterVolume(),
    val distinctCharacters: NonNegativeCounter = NonNegativeCounter.ZERO,
    val newCharacters: NonNegativeCounter = NonNegativeCounter.ZERO,
    val sourceUnits: NonNegativeCounter = NonNegativeCounter.ZERO,
    val sessions: NonNegativeCounter = NonNegativeCounter.ZERO,
    val cardsCreated: NonNegativeCounter = NonNegativeCounter.ZERO,
    val cardsUpdated: NonNegativeCounter = NonNegativeCounter.ZERO,
    val characterCoverage: CharacterCoverage = CharacterCoverage(),
) {
    fun readingSpeedPerHour(metric: CharacterMetric): Double? {
        val characterCount = characters.valueFor(metric)
        return ratePer(characterCount, activeTime.value, MILLIS_PER_HOUR)
    }

    fun miningRatePerTenThousandGrossCharacters(): Double? =
        ratePer(cardsCreated.value, characters.gross.value, TEN_THOUSAND)

    companion object {
        private const val MILLIS_PER_HOUR = 3_600_000.0
        private const val TEN_THOUSAND = 10_000.0

        private fun ratio(numerator: Long, denominator: Long): Double? {
            if (denominator == 0L) return null
            return numerator.toDouble() / denominator.toDouble()
        }

        private fun ratePer(numerator: Long, denominator: Long, scale: Double): Double? {
            if (denominator <= 0L || numerator < 0L) return null
            return numerator.toDouble() / denominator.toDouble() * scale
        }
    }
}
