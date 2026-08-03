package mihon.feature.stats.legacy

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import exh.log.xLogE
import kotlinx.coroutines.CancellationException
import mihon.feature.stats.rollup.ImmersionRollupJob
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LegacyStatsImportJob(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
    private val importer: LegacyStatsImporter = Injekt.get()

    override suspend fun doWork(): Result =
        try {
            val report = importer.importAll()
            // The import writes session rows and marks their rollup ranges
            // dirty, but writes no events, so no other trigger fires for it:
            // the recorder's persistence observer needs events and the index
            // job needs exposure events. Without this hand-off the imported
            // days stay dirty and the dashboard reads zero.
            if (report.results.isNotEmpty()) {
                ImmersionRollupJob.start(applicationContext)
            }
            Result.success(
                workDataOf(
                    "importedSources" to report.results.size,
                    "issueCount" to report.issues.size,
                    "mismatchCount" to report.reconciliation.mismatches.size,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            xLogE("Legacy immersion statistics import failed", error)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }

    companion object {
        private const val UNIQUE_WORK_NAME = "legacy-immersion-statistics-import"
        private const val MAX_ATTEMPTS = 3

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<LegacyStatsImportJob>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
