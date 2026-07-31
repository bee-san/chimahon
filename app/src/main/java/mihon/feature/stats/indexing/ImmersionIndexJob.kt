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
import tachiyomi.domain.immersion.service.ImmersionDiagnosticErrorCode
import tachiyomi.domain.immersion.service.ImmersionDiagnosticStage
import tachiyomi.domain.immersion.service.ImmersionIndexingEngine
import tachiyomi.domain.immersion.service.ImmersionStatsDiagnosticsStore
import tachiyomi.domain.immersion.service.ImmersionStatsPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class ImmersionIndexJob(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
    private val engine: ImmersionIndexingEngine = Injekt.get()
    private val diagnostics: ImmersionStatsDiagnosticsStore = Injekt.get()
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
                recordImmersionIndexFailure(diagnostics, batch.failed)
                when (
                    decideImmersionIndexBatch(
                        claimed = batch.claimed,
                        failures = failures,
                        batchSize = ImmersionIndexingEngine.DEFAULT_BATCH_SIZE,
                    )
                ) {
                    ImmersionIndexBatchDecision.CONTINUE -> Unit
                    ImmersionIndexBatchDecision.RETRY -> {
                        ImmersionRollupJob.start(applicationContext)
                        return Result.retry()
                    }
                    ImmersionIndexBatchDecision.SUCCESS -> {
                        diagnostics.recordIndexSuccess(System.currentTimeMillis())
                        ImmersionRollupJob.start(applicationContext)
                        return Result.success(
                            androidx.work.workDataOf(
                                "processed" to processed,
                                "failures" to failures,
                            ),
                        )
                    }
                }
            }
            Result.retry()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recordImmersionIndexFailure(diagnostics)
            xLogE("Immersion indexing failed", error)
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "immersion-statistics-index"
        private const val MAX_BATCHES_PER_RUN = 4

        fun start(context: Context) {
            if (!Injekt.get<ImmersionStatsPreferences>().indexingEnabled().get()) {
                cancel(context)
                return
            }
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

        fun setEnabled(context: Context, enabled: Boolean) {
            Injekt.get<ImmersionStatsPreferences>().indexingEnabled().set(enabled)
            if (enabled) {
                start(context)
            } else {
                cancel(context)
            }
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}

internal fun recordImmersionIndexFailure(
    diagnostics: ImmersionStatsDiagnosticsStore,
    failureCount: Int = 1,
) {
    require(failureCount >= 0)
    if (failureCount > 0) {
        diagnostics.recordError(
            ImmersionDiagnosticStage.INDEX,
            ImmersionDiagnosticErrorCode.INDEXING_FAILED,
        )
    }
}

internal enum class ImmersionIndexBatchDecision {
    CONTINUE,
    SUCCESS,
    RETRY,
}

internal fun decideImmersionIndexBatch(
    claimed: Int,
    failures: Int,
    batchSize: Int,
): ImmersionIndexBatchDecision {
    require(batchSize > 0)
    require(claimed in 0..batchSize)
    require(failures >= 0)
    return when {
        claimed == batchSize -> ImmersionIndexBatchDecision.CONTINUE
        failures > 0 -> ImmersionIndexBatchDecision.RETRY
        else -> ImmersionIndexBatchDecision.SUCCESS
    }
}
