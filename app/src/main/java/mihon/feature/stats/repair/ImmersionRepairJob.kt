// SPDX-License-Identifier: MIT

package mihon.feature.stats.repair

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import exh.log.xLogE
import kotlinx.coroutines.CancellationException
import mihon.feature.stats.rollup.ImmersionRollupJob
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.repository.ImmersionMaintenanceRepository
import tachiyomi.domain.immersion.service.ImmersionRepairReason
import tachiyomi.domain.immersion.service.ImmersionStatsDiagnosticsStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class ImmersionRepairJob(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
    private val repository: ImmersionMaintenanceRepository = Injekt.get()
    private val diagnostics: ImmersionStatsDiagnosticsStore = Injekt.get()

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(SESSION_ID)
            ?.let { runCatching { SessionId(it) }.getOrNull() }
            ?: return Result.failure()
        val reason = inputData.getString(REASON)
            ?.let { runCatching { ImmersionRepairReason.valueOf(it) }.getOrNull() }
            ?: return Result.failure()
        return try {
            val repairedAt = System.currentTimeMillis()
            val repaired = repository.repairSessionCounters(
                sessionId = sessionId,
                repairedAtEpochMillis = repairedAt,
            )
            if (repaired) {
                diagnostics.recordRepair(repairedAt)
                ImmersionRollupJob.start(applicationContext)
            }
            Result.success(
                workDataOf(
                    REPAIRED to repaired,
                    REASON to reason.name,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            xLogE("Immersion session repair failed", error)
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_PREFIX = "immersion-statistics-session-repair"
        private const val SESSION_ID = "session_id"
        private const val REASON = "reason"
        const val REPAIRED = "repaired"

        fun start(
            context: Context,
            sessionId: SessionId,
            reason: ImmersionRepairReason,
        ) {
            val request = OneTimeWorkRequestBuilder<ImmersionRepairJob>()
                .setInputData(
                    workDataOf(
                        SESSION_ID to sessionId.value,
                        REASON to reason.name,
                    ),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$UNIQUE_WORK_PREFIX-${sessionId.value}",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
