package mihon.feature.stats.legacy

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import exh.log.xLogE
import kotlinx.coroutines.CancellationException
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
            Result.success(
                androidx.work.workDataOf(
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
