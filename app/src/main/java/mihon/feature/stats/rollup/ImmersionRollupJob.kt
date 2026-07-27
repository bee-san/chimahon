// SPDX-License-Identifier: MIT

package mihon.feature.stats.rollup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import chimahon.widget.ImmersionWidgetSignals
import exh.log.xLogE
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.LocalDateRange
import tachiyomi.domain.immersion.repository.ImmersionAnalyticsRepository
import tachiyomi.domain.immersion.service.ImmersionAnalyticsService
import tachiyomi.domain.immersion.service.ImmersionDiagnosticErrorCode
import tachiyomi.domain.immersion.service.ImmersionDiagnosticStage
import tachiyomi.domain.immersion.service.ImmersionStatsDiagnosticsStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class ImmersionRollupJob(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
    private val diagnostics: ImmersionStatsDiagnosticsStore = Injekt.get()
    private val service: ImmersionAnalyticsService = Injekt.get()
    private val repository: ImmersionAnalyticsRepository = Injekt.get()

    override suspend fun doWork(): Result =
        try {
            val requestedRange = inputData.requestedRange()
            val results = if (requestedRange != null) {
                listOf(service.rebuild(requestedRange))
            } else {
                service.repairDirtyRollups(BATCH_SIZE)
            }
            val remaining = repository.dirtyRollupRanges(1).isNotEmpty()
            val output = workDataOf(
                "ranges" to results.size,
                "rows" to results.sumOf { it.rowCount },
                "events" to results.sumOf { it.eventCount },
            )
            if (results.isNotEmpty()) {
                ImmersionWidgetSignals.notifyStatsChanged()
            }
            if (remaining) {
                Result.retry()
            } else {
                diagnostics.recordRollupSuccess(System.currentTimeMillis())
                Result.success(output)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recordImmersionRollupFailure(diagnostics)
            xLogE("Immersion rollup repair failed", error)
            Result.retry()
        }

    companion object {
        private const val PERIODIC_WORK_NAME = "immersion-statistics-rollup-periodic"
        private const val INCREMENTAL_WORK_NAME = "immersion-statistics-rollup-incremental"
        private const val MANUAL_WORK_NAME = "immersion-statistics-rollup-manual"
        private const val START_DATE = "start_date"
        private const val END_DATE = "end_date"
        private const val BATCH_SIZE = 31

        fun setupTask(context: Context) {
            val request = PeriodicWorkRequestBuilder<ImmersionRollupJob>(12, TimeUnit.HOURS)
                .setConstraints(defaultConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun start(context: Context) {
            enqueue(context, INCREMENTAL_WORK_NAME, Data.EMPTY, ExistingWorkPolicy.KEEP)
        }

        fun rebuild(
            context: Context,
            range: LocalDateRange,
        ) {
            val input = workDataOf(
                START_DATE to range.start.epochDay,
                END_DATE to range.endInclusive.epochDay,
            )
            enqueue(context, MANUAL_WORK_NAME, input, ExistingWorkPolicy.REPLACE)
        }

        fun cancelManual(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(MANUAL_WORK_NAME)
        }

        private fun enqueue(
            context: Context,
            name: String,
            input: Data,
            policy: ExistingWorkPolicy,
        ) {
            val request = OneTimeWorkRequestBuilder<ImmersionRollupJob>()
                .setInputData(input)
                .setConstraints(defaultConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
        }

        private fun defaultConstraints() =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(true)
                .build()
    }

    private fun Data.requestedRange(): LocalDateRange? {
        if (!keyValueMap.containsKey(START_DATE) || !keyValueMap.containsKey(END_DATE)) return null
        return LocalDateRange(
            ImmersionLocalDate(getLong(START_DATE, 0)),
            ImmersionLocalDate(getLong(END_DATE, 0)),
        )
    }
}

internal fun recordImmersionRollupFailure(diagnostics: ImmersionStatsDiagnosticsStore) {
    diagnostics.recordError(
        ImmersionDiagnosticStage.ROLLUP,
        ImmersionDiagnosticErrorCode.ROLLUP_FAILED,
    )
}
