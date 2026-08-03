package mihon.core.migration.migrations

import android.app.Application
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import mihon.feature.stats.retention.ImmersionRetentionJob
import mihon.feature.stats.rollup.ImmersionRollupJob

/**
 * Registers the immersion-statistics periodic workers.
 *
 * Both jobs declared a `setupTask` that nothing ever called, so the 12-hour
 * rollup drain and the 24-hour retention sweep never ran. Rollups are what the
 * dashboard reads, so a queue of dirty ranges -- from a legacy import, or from
 * ordinary reading, since finalizing a session marks its ranges dirty without
 * writing any event -- sat unrepaired and every metric showed zero.
 *
 * Registration is deliberately unconditional. `captureEnabled` and
 * `indexingEnabled` default to true while `uiEnabled` defaults to false
 * (`ImmersionStatsPreferences`), so dirty ranges accumulate before the user ever
 * opens the preview. Gating the drain on the UI toggle would mean the backlog is
 * only repaired once the screen is opened -- the bug being fixed. Both
 * `setupTask` bodies use `ExistingPeriodicWorkPolicy.KEEP`, so re-running this
 * migration is idempotent.
 *
 * Note that `Migration.ALWAYS` does not mean "every launch":
 * `MigrationStrategyFactory` short-circuits to `NoopMigrationStrategy` when the
 * stored version already equals the current one. That is fine here, because
 * WorkManager persists periodic work across process death and reboots, so the
 * enqueue only has to happen once per install or upgrade. It would not be fine
 * for a preference-gated registration, which is a second reason this one is not
 * gated: a toggle flipped on the same app version would never be noticed.
 */
class SetupImmersionStatsJobsMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val context = migrationContext.get<Application>() ?: return false
        ImmersionRollupJob.setupTask(context)
        ImmersionRetentionJob.setupTask(context)
        return true
    }
}
