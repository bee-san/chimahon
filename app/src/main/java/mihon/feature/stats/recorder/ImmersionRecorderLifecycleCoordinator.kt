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
) {
    private var scope: CoroutineScope? = null

    fun initialize(scope: CoroutineScope) {
        if (this.scope != null) return
        this.scope = scope
        scope.launch {
            recorder.setIncognito(basePreferences.incognitoMode().get())
            recorder.recoverAbandonedSessions()
            ankiOperationRecorder.retryPending()
        }
        basePreferences.incognitoMode().changes()
            .onEach(recorder::setIncognito)
            .launchIn(scope)
        statsPreferences.captureEnabled().changes()
            .onEach { enabled ->
                if (!enabled) recorder.finalize(FinalizeReason.CAPTURE_DISABLED)
            }
            .launchIn(scope)
    }

    fun onForeground() {
        scope?.launch { recorder.resume(ResumeReason.FOREGROUND) }
    }

    fun onBackground() {
        scope?.launch { recorder.pause(PauseReason.BACKGROUND) }
    }
}
