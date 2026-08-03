package mihon.feature.stats.retention

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import exh.log.xLogE
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.immersion.model.RawTextRetention
import tachiyomi.domain.immersion.repository.ImmersionMaintenanceRepository
import tachiyomi.domain.immersion.service.ImmersionStatsPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class ImmersionRetentionJob(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
    private val repository: ImmersionMaintenanceRepository = Injekt.get()
    private val preferences: ImmersionStatsPreferences = Injekt.get()

    override suspend fun doWork(): Result =
        try {
            val now = System.currentTimeMillis()
            val retention = preferences.effectiveRawTextRetention()
            val cutoff = retention.cutoffEpochMillis(now)
            val affected = when {
                retention == RawTextRetention.UNTIL_DELETED -> 0L
                // A null cutoff carries no age bound, so `deleteRawText` degrades
                // to "every row that has raw text". That is what the user asked
                // for when they tap the maintenance action, and `start` says so
                // explicitly. The periodic sweep must never do it unasked:
                // `effectiveRawTextRetention` also returns NEVER whenever the
                // disclosure is unanswered, so a restored collection can hold raw
                // text while the effective policy reads NEVER, and an unbounded
                // scheduled delete would silently discard it.
                cutoff == null && !inputData.getBoolean(ALLOW_UNBOUNDED, false) -> 0L
                inputData.getBoolean(DRY_RUN, false) ->
                    repository.previewRawTextDeletion(beforeEpochMillis = cutoff)
                else -> repository.deleteRawText(
                    beforeEpochMillis = cutoff,
                    updatedAtEpochMillis = now,
                )
            }
            val compactedHeartbeats = if (inputData.getBoolean(DRY_RUN, false)) {
                0L
            } else {
                repository.compactFinalizedHeartbeats(
                    limit = HEARTBEAT_COMPACTION_BATCH_SIZE,
                    compactedAtEpochMillis = now,
                )
            }
            Result.success(
                workDataOf(
                    AFFECTED_PRIVATE_TEXT_RECORDS to affected,
                    COMPACTED_HEARTBEAT_EVENTS to compactedHeartbeats,
                    RETENTION_POLICY to retention.name,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            xLogE("Immersion raw-text retention failed", error)
            Result.retry()
        }

    companion object {
        private const val PERIODIC_WORK_NAME = "immersion-statistics-retention-periodic"
        private const val MANUAL_WORK_NAME = "immersion-statistics-retention-manual"
        private const val DRY_RUN = "dry_run"
        private const val ALLOW_UNBOUNDED = "allow_unbounded"
        private const val HEARTBEAT_COMPACTION_BATCH_SIZE = 100
        const val AFFECTED_PRIVATE_TEXT_RECORDS = "affected_private_text_records"
        const val COMPACTED_HEARTBEAT_EVENTS = "compacted_heartbeat_events"
        const val RETENTION_POLICY = "retention_policy"

        fun setupTask(context: Context) {
            val request = PeriodicWorkRequestBuilder<ImmersionRetentionJob>(24, TimeUnit.HOURS)
                .setConstraints(defaultConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Runs the sweep now, on the user's behalf. Both call sites are explicit
         * maintenance actions, so this path may honour a retention policy with no
         * age bound and clear raw text outright; the periodic sweep may not.
         */
        fun start(
            context: Context,
            dryRun: Boolean = false,
        ) {
            val request = OneTimeWorkRequestBuilder<ImmersionRetentionJob>()
                .setInputData(workDataOf(DRY_RUN to dryRun, ALLOW_UNBOUNDED to true))
                .setConstraints(defaultConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        internal fun RawTextRetention.cutoffEpochMillis(nowEpochMillis: Long): Long? =
            when (this) {
                RawTextRetention.NEVER -> null
                RawTextRetention.THIRTY_DAYS -> nowEpochMillis - TimeUnit.DAYS.toMillis(30)
                RawTextRetention.ONE_YEAR -> nowEpochMillis - TimeUnit.DAYS.toMillis(365)
                RawTextRetention.UNTIL_DELETED -> null
            }

        private fun defaultConstraints() =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(true)
                .build()
    }
}
