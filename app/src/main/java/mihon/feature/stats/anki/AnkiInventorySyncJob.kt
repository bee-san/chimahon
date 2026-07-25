package mihon.feature.stats.anki

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
import chimahon.anki.AnkiCardCreator
import chimahon.anki.AnkiProfile
import chimahon.anki.Marker
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import exh.log.xLogE
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.immersion.model.AnkiInventoryFailure
import tachiyomi.domain.immersion.model.AnkiMaturityAggregation
import tachiyomi.domain.immersion.model.AnkiSnapshotStatus
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.service.AnkiInventoryConfiguration
import tachiyomi.domain.immersion.service.AnkiInventorySynchronizer
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class AnkiInventorySyncJob(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
    private val synchronizer: AnkiInventorySynchronizer = Injekt.get()
    private val dictionaryPreferences: DictionaryPreferences = Injekt.get()

    override suspend fun doWork(): Result {
        val configurations = dictionaryPreferences.profileStore.getProfiles()
            .filter(AnkiProfile::ankiEnabled)
            .mapNotNull(AnkiProfile::toInventoryConfiguration)
        if (configurations.isEmpty()) return Result.success()

        return try {
            val results = configurations.map { synchronizer.refresh(it) }
            val shouldRetry = results.any { result ->
                result.status == AnkiSnapshotStatus.FAILED &&
                    result.failure in setOf(
                        AnkiInventoryFailure.PARTIAL_RESULT,
                        AnkiInventoryFailure.PROVIDER_ERROR,
                    )
            }
            if (shouldRetry) {
                Result.retry()
            } else {
                Result.success(
                    workDataOf(
                        "profiles" to results.size,
                        "items" to results.sumOf { it.itemCount },
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            xLogE("Anki inventory refresh failed", error)
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "immersion-anki-inventory-periodic"
        private const val MANUAL_WORK_NAME = "immersion-anki-inventory-manual"

        fun setupTask(context: Context) {
            val request = PeriodicWorkRequestBuilder<AnkiInventorySyncJob>(24, TimeUnit.HOURS)
                .setConstraints(defaultConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<AnkiInventorySyncJob>()
                .setConstraints(defaultConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(MANUAL_WORK_NAME)
        }

        private fun defaultConstraints() =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(true)
                .build()
    }
}

internal fun AnkiProfile.toInventoryConfiguration(): AnkiInventoryConfiguration? {
    if (id.isBlank() || ankiDeck.isBlank() || ankiModel.isBlank()) return null
    val mapping = AnkiCardCreator.parseFieldMap(ankiFieldMap)
    fun mappedField(marker: String): String? =
        mapping.entries.firstOrNull { (_, template) ->
            template.contains("{$marker}", ignoreCase = true)
        }?.key

    val expressionField = ankiStatsExpressionField.ifBlank {
        mappedField(Marker.EXPRESSION).orEmpty()
    }
    if (expressionField.isBlank()) return null
    val readingField = ankiStatsReadingField.ifBlank {
        mappedField(Marker.READING).orEmpty()
    }.ifBlank { null }
    val characterField = ankiStatsCharacterField.ifBlank {
        mappedField("character").orEmpty()
    }.ifBlank { null }
    return AnkiInventoryConfiguration(
        profileId = id,
        enabled = ankiEnabled,
        deckName = ankiDeck,
        noteTypeName = ankiModel,
        languageTag = LanguageTag(languageCode.ifBlank { DEFAULT_STATS_LANGUAGE }),
        expressionField = expressionField,
        readingField = readingField,
        characterField = characterField,
        matureIntervalDays = ankiStatsMatureIntervalDays.coerceAtLeast(1),
        maturityAggregation = runCatching {
            AnkiMaturityAggregation.valueOf(ankiStatsMaturityAggregation)
        }.getOrDefault(AnkiMaturityAggregation.MAX_INTERVAL),
    )
}

private const val DEFAULT_STATS_LANGUAGE = "ja"
