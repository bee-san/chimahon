package mihon.core.migration.migrations

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import mihon.core.migration.Migration
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards startup registration of periodic workers.
 *
 * `setupTask` enqueues a PeriodicWorkRequest under a unique name. A job whose
 * `setupTask` has no caller compiles, passes every other unit test, and silently
 * never runs -- which is how the immersion rollup drain shipped dead and the
 * statistics dashboard came to report zero for every metric.
 *
 * These are source-text assertions because the module has no Robolectric and no
 * `androidx.work:work-testing`, so a JVM test cannot stand up WorkManager. The
 * tradeoff is the one `KMKDomainModuleImmersionTest` already accepts: renaming
 * `setupTask`, or registering a worker through some other mechanism, weakens
 * this to a passing no-op.
 */
class PeriodicWorkRegistrationTest {

    private val sources: List<File> by lazy {
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
    }

    private val allSource: String by lazy {
        sources.joinToString("\n") { it.readText() }
    }

    @Test
    fun `every setupTask has a call site`() {
        val declaring = sources.filter { it.readText().contains("fun setupTask(") }

        // Without this floor the test passes vacuously: `walkTopDown` on a root
        // that resolves to nothing yields an empty sequence, so a wrong working
        // directory would report zero orphans rather than failing.
        declaring.size shouldBeGreaterThanOrEqual EXPECTED_SETUP_TASK_DECLARATIONS

        val orphaned = declaring
            .filterNot { file ->
                // A qualified call from anywhere, e.g. a Setup*Migration.
                allSource.contains("${file.nameWithoutExtension}.setupTask(")
            }
            .filterNot { file ->
                // Or an unqualified self-call from a sibling entry point in the
                // same companion, the way AnkiInventorySyncJob.setEnabled does
                // it. Reachable, just not through a qualified reference.
                file.readText().substringAfter("fun setupTask(").contains("setupTask(")
            }
            .map { it.nameWithoutExtension }

        orphaned.shouldBeEmpty()
    }

    @Test
    fun `the immersion statistics workers are registered by a startup migration`() {
        val migration = File(
            "src/main/java/mihon/core/migration/migrations/SetupImmersionStatsJobsMigration.kt",
        ).readText()

        check(migration.contains("ImmersionRollupJob.setupTask(context)")) {
            "SetupImmersionStatsJobsMigration must register ImmersionRollupJob"
        }
        check(migration.contains("ImmersionRetentionJob.setupTask(context)")) {
            "SetupImmersionStatsJobsMigration must register ImmersionRetentionJob"
        }

        // Assert against the real list, not its source text. Commenting an entry
        // out is this file's idiom for disabling a migration -- there are already
        // 35 such lines -- and a text match cannot tell the two apart.
        val registered = migrations.filterIsInstance<SetupImmersionStatsJobsMigration>()
        registered shouldHaveSize 1
        registered.single().version shouldBe Migration.ALWAYS
    }

    /**
     * The drain must not be gated on the statistics preview toggle. Capture and
     * indexing default to on while the preview defaults to off, so dirty rollup
     * ranges accumulate before the screen is ever opened; gating registration on
     * `uiEnabled` would leave that backlog unrepaired until the user looked.
     */
    @Test
    fun `rollup registration is not gated on a preference`() {
        val migration = File(
            "src/main/java/mihon/core/migration/migrations/SetupImmersionStatsJobsMigration.kt",
        ).readText()
        val body = migration.substringAfter("override suspend fun invoke")

        check(!body.contains("uiEnabled")) {
            "Rollup registration must not be gated on uiEnabled()"
        }
        check(!body.contains("captureEnabled")) {
            "Rollup registration must not be gated on captureEnabled()"
        }
    }

    private companion object {
        /** Current count under `app/src/main/java`; raise it, never lower it. */
        const val EXPECTED_SETUP_TASK_DECLARATIONS = 11
    }
}
