package mihon.feature.stats.legacy

import org.junit.jupiter.api.Test
import java.io.File

/**
 * The legacy import writes session rows and marks their rollup ranges dirty but
 * writes no events, so no other trigger ever fires for it: the recorder's
 * persistence observer needs events, and the index job needs exposure events.
 * Without an explicit hand-off the imported days stay dirty and the dashboard
 * reads zero next to a non-zero session count -- the reported symptom.
 *
 * This is a source-text assertion because `doWork` resolves its collaborators
 * through Injekt and enqueues through WorkManager, neither of which a plain JVM
 * test can stand up in this module.
 */
class LegacyStatsImportRollupHandoffTest {

    private val jobSource: String by lazy {
        File("src/main/java/mihon/feature/stats/legacy/LegacyStatsImportJob.kt").readText()
    }

    @Test
    fun `a successful import schedules the rollup drain`() {
        check(jobSource.contains("import mihon.feature.stats.rollup.ImmersionRollupJob")) {
            "LegacyStatsImportJob must import ImmersionRollupJob"
        }
        check(jobSource.contains("ImmersionRollupJob.start(applicationContext)")) {
            "LegacyStatsImportJob must enqueue the rollup drain after importing"
        }
    }

    @Test
    fun `the rollup is scheduled only when something was imported`() {
        // Matched as one structure rather than "predicate somewhere before call".
        // `substringBefore` returns the whole string when the needle is absent, so
        // a split assertion would also accept an unconditional call preceded by an
        // unrelated mention of the predicate.
        val guarded = Regex(
            """if \(report\.results\.isNotEmpty\(\)\) \{\s*ImmersionRollupJob\.start\(applicationContext\)""",
        )

        check(guarded.containsMatchIn(jobSource)) {
            "The rollup hand-off must be guarded on a non-empty import result"
        }
    }
}
