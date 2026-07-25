// SPDX-License-Identifier: MIT

package mihon.feature.stats.indexing

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import exh.log.xLogE
import kotlinx.coroutines.CancellationException
import mihon.feature.stats.rollup.ImmersionRollupJob
import tachiyomi.domain.immersion.service.ImmersionIndexingEngine
import tachiyomi.domain.immersion.service.ImmersionStatsPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class ImmersionIndexJob(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
    private val engine: ImmersionIndexingEngine = Injekt.get()
    private val preferences: ImmersionStatsPreferences = Injekt.get()

    override suspend fun doWork(): Result {
        if (!preferences.indexingEnabled().get()) return Result.success()
        return try {
            var processed = 0
            var failures = 0
            repeat(MAX_BATCHES_PER_RUN) {
                val batch = engine.processBatch()
                processed += batch.claimed
                failures += batch.failed
                if (batch.claimed < ImmersionIndexingEngine.DEFAULT_BATCH_SIZE) {
                    ImmersionRollupJob.start(applicationContext)
                    return Result.success(
                        androidx.work.workDataOf(
                            "processed" to processed,
                            "failures" to failures,
                        ),
                    )
                }
            }
            Result.retry()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            xLogE("Immersion indexing failed", error)
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "immersion-statistics-index"
        private const val MAX_BATCHES_PER_RUN = 4

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<ImmersionIndexJob>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
