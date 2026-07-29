package tachiyomi.domain.immersion.model

object AnalyticsCharacterPriorityFormula {
    const val VERSION = 1

    data class Components(
        val frequency: Double,
        val jlpt: Double,
        val grade: Double,
    ) {
        init {
            require(frequency >= 0 && frequency.isFinite())
            require(jlpt >= 0 && jlpt.isFinite())
            require(grade >= 0 && grade.isFinite())
        }

        fun score(mode: AnalyticsCharacterPriorityMode): Double = when (mode) {
            AnalyticsCharacterPriorityMode.FREQUENCY -> frequency
            AnalyticsCharacterPriorityMode.JLPT -> jlpt
            AnalyticsCharacterPriorityMode.GRADE -> grade
            AnalyticsCharacterPriorityMode.MIXED ->
                frequency * FREQUENCY_WEIGHT +
                    jlpt * JLPT_WEIGHT +
                    grade * GRADE_WEIGHT
        }
    }

    fun components(
        frequencyRank: Long?,
        jlptLevel: Int?,
        gradeLevel: Int?,
    ): Components {
        val frequency = frequencyRank
            ?.takeIf { it > 0 }
            ?.let { PRIORITY_SCALE / it.toDouble() }
            ?: 0.0
        val jlpt = jlptLevel
            ?.takeIf { it in 1..5 }
            ?.let { (6 - it) * (PRIORITY_SCALE / 5.0) }
            ?: 0.0
        val grade = gradeLevel
            ?.takeIf { it > 0 }
            ?.coerceAtMost(MAX_GRADE_LEVEL)
            ?.let { (MAX_GRADE_LEVEL + 1 - it) * (PRIORITY_SCALE / MAX_GRADE_LEVEL) }
            ?: 0.0
        return Components(frequency, jlpt, grade)
    }

    fun score(
        frequencyRank: Long?,
        jlptLevel: Int?,
        gradeLevel: Int?,
        mode: AnalyticsCharacterPriorityMode,
    ): Double = components(frequencyRank, jlptLevel, gradeLevel).score(mode)

    private const val PRIORITY_SCALE = 1_000_000.0
    private const val MAX_GRADE_LEVEL = 13
    private const val FREQUENCY_WEIGHT = 0.60
    private const val JLPT_WEIGHT = 0.25
    private const val GRADE_WEIGHT = 0.15
}
