package mihon.feature.stats.retention

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import mihon.feature.stats.retention.ImmersionRetentionJob.Companion.cutoffEpochMillis
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.RawTextRetention
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The retention sweep runs on a 24-hour schedule as of the fix for the statistics
 * rollup never being registered. Two policies carry no age bound, and for those
 * `deleteRawText` degrades to "every row that has raw text" -- correct for the
 * maintenance action the user tapped, wrong for a background sweep.
 *
 * `effectiveRawTextRetention` also forces NEVER whenever the raw-text disclosure
 * is unanswered, so a restored collection can hold raw text while the effective
 * policy reads NEVER. An unbounded scheduled delete would discard it silently.
 */
class ImmersionRetentionSweepBoundTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `only the windowed policies produce an age bound`() {
        RawTextRetention.NEVER.cutoffEpochMillis(now).shouldBeNull()
        RawTextRetention.UNTIL_DELETED.cutoffEpochMillis(now).shouldBeNull()

        RawTextRetention.THIRTY_DAYS.cutoffEpochMillis(now) shouldBe
            now - TimeUnit.DAYS.toMillis(30)
        RawTextRetention.ONE_YEAR.cutoffEpochMillis(now) shouldBe
            now - TimeUnit.DAYS.toMillis(365)
    }

    /**
     * Source-text assertion: `doWork` resolves its repository and preferences
     * through Injekt and is driven by WorkManager input data, neither of which a
     * plain JVM test in this module can stand up.
     */
    @Test
    fun `an unbounded delete requires the caller to opt in`() {
        val source = File(
            "src/main/java/mihon/feature/stats/retention/ImmersionRetentionJob.kt",
        ).readText()

        val guarded = Regex(
            """cutoff == null && !inputData\.getBoolean\(ALLOW_UNBOUNDED, false\) -> 0L""",
        )
        check(guarded.containsMatchIn(source)) {
            "The scheduled sweep must skip a delete that carries no age bound"
        }

        // The periodic request must not opt in; the user-initiated one must.
        val periodic = source.substringAfter("fun setupTask(").substringBefore("fun start(")
        check(!periodic.contains(ALLOW_UNBOUNDED_KEY)) {
            "The periodic sweep must not allow an unbounded delete"
        }
        check(source.substringAfter("fun start(").contains("$ALLOW_UNBOUNDED_KEY to true")) {
            "The user-initiated sweep must keep clearing raw text outright"
        }
    }

    private companion object {
        const val ALLOW_UNBOUNDED_KEY = "ALLOW_UNBOUNDED"
    }
}
