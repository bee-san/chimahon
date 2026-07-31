package mihon.feature.stats.recorder

import eu.kanade.domain.base.BasePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import tachiyomi.domain.immersion.service.AnkiOperationRecorder
import tachiyomi.domain.immersion.service.FinalizeReason
import tachiyomi.domain.immersion.service.ImmersionRecorder
import tachiyomi.domain.immersion.service.ImmersionStatsPreferences
import tachiyomi.domain.immersion.service.PauseReason
import tachiyomi.domain.immersion.service.ResumeReason

class ImmersionRecorderLifecycleCoordinator(
    private val recorder: ImmersionRecorder,
    private val basePreferences: BasePreferences,
    private val statsPreferences: ImmersionStatsPreferences,
    private val ankiOperationRecorder: AnkiOperationRecorder,
    private val onAnkiRepairsPersisted: () -> Unit = {},
) {
    private var scope: CoroutineScope? = null

    fun initialize(scope: CoroutineScope) {
        if (this.scope != null) return
        this.scope = scope
        scope.launch {
            recorder.setIncognito(basePreferences.incognitoMode().get())
            recorder.recoverAbandonedSessions()
            retryPendingAnkiOperations()
        }
        basePreferences.incognitoMode().changes()
            .onEach { enabled ->
                recorder.setIncognito(enabled)
                if (!enabled) retryPendingAnkiOperations()
            }
            .launchIn(scope)
        statsPreferences.captureEnabled().changes()
            .onEach { enabled ->
                if (enabled) {
                    retryPendingAnkiOperations()
                } else {
                    recorder.finalize(FinalizeReason.CAPTURE_DISABLED)
                }
            }
            .launchIn(scope)
    }

    private suspend fun retryPendingAnkiOperations() {
        if (ankiOperationRecorder.retryPending() > 0) {
            onAnkiRepairsPersisted()
        }
    }

    fun onForeground() {
        scope?.launch { recorder.resume(ResumeReason.FOREGROUND) }
    }

    fun onBackground() {
        scope?.launch { recorder.pause(PauseReason.BACKGROUND) }
    }
}
