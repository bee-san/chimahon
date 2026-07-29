package tachiyomi.domain.immersion.service

enum class ImmersionSessionState {
    NOT_STARTED,
    STARTING,
    ACTIVE,
    PAUSED,
    IDLE,
    BACKGROUND,
    FINALIZING,
    FINALIZED,
    FAILED,
    SUPPRESSED,
}

enum class SessionTransition {
    BEGIN_START,
    START,
    SUPPRESS,
    PAUSE,
    RESUME,
    IDLE_TIMEOUT,
    FOREGROUND_LOST,
    FOREGROUND_RESTORED,
    BEGIN_FINALIZE,
    FINALIZE,
    FAIL,
    RESET,
}

class IllegalSessionTransition(
    state: ImmersionSessionState,
    transition: SessionTransition,
) : IllegalStateException("Cannot apply $transition while session is $state")

object ImmersionSessionStateMachine {
    fun transition(
        state: ImmersionSessionState,
        transition: SessionTransition,
    ): ImmersionSessionState = when (state) {
        ImmersionSessionState.NOT_STARTED -> when (transition) {
            SessionTransition.BEGIN_START -> ImmersionSessionState.STARTING
            SessionTransition.SUPPRESS -> ImmersionSessionState.SUPPRESSED
            else -> illegal(state, transition)
        }
        ImmersionSessionState.STARTING -> when (transition) {
            SessionTransition.START -> ImmersionSessionState.ACTIVE
            SessionTransition.FAIL -> ImmersionSessionState.FAILED
            else -> illegal(state, transition)
        }
        ImmersionSessionState.ACTIVE -> when (transition) {
            SessionTransition.PAUSE -> ImmersionSessionState.PAUSED
            SessionTransition.RESUME -> ImmersionSessionState.ACTIVE
            SessionTransition.IDLE_TIMEOUT -> ImmersionSessionState.IDLE
            SessionTransition.FOREGROUND_LOST -> ImmersionSessionState.BACKGROUND
            SessionTransition.BEGIN_FINALIZE -> ImmersionSessionState.FINALIZING
            SessionTransition.FAIL -> ImmersionSessionState.FAILED
            else -> illegal(state, transition)
        }
        ImmersionSessionState.PAUSED -> when (transition) {
            SessionTransition.PAUSE -> ImmersionSessionState.PAUSED
            SessionTransition.RESUME -> ImmersionSessionState.ACTIVE
            SessionTransition.FOREGROUND_LOST -> ImmersionSessionState.BACKGROUND
            SessionTransition.BEGIN_FINALIZE -> ImmersionSessionState.FINALIZING
            SessionTransition.FAIL -> ImmersionSessionState.FAILED
            else -> illegal(state, transition)
        }
        ImmersionSessionState.IDLE -> when (transition) {
            SessionTransition.PAUSE -> ImmersionSessionState.PAUSED
            SessionTransition.RESUME -> ImmersionSessionState.ACTIVE
            SessionTransition.IDLE_TIMEOUT -> ImmersionSessionState.IDLE
            SessionTransition.FOREGROUND_LOST -> ImmersionSessionState.BACKGROUND
            SessionTransition.BEGIN_FINALIZE -> ImmersionSessionState.FINALIZING
            SessionTransition.FAIL -> ImmersionSessionState.FAILED
            else -> illegal(state, transition)
        }
        ImmersionSessionState.BACKGROUND -> when (transition) {
            SessionTransition.FOREGROUND_LOST -> ImmersionSessionState.BACKGROUND
            SessionTransition.FOREGROUND_RESTORED -> ImmersionSessionState.PAUSED
            SessionTransition.BEGIN_FINALIZE -> ImmersionSessionState.FINALIZING
            SessionTransition.FAIL -> ImmersionSessionState.FAILED
            else -> illegal(state, transition)
        }
        ImmersionSessionState.FINALIZING -> when (transition) {
            SessionTransition.FINALIZE -> ImmersionSessionState.FINALIZED
            SessionTransition.FAIL -> ImmersionSessionState.FAILED
            else -> illegal(state, transition)
        }
        ImmersionSessionState.FINALIZED -> when (transition) {
            SessionTransition.FINALIZE -> ImmersionSessionState.FINALIZED
            SessionTransition.RESET -> ImmersionSessionState.NOT_STARTED
            else -> illegal(state, transition)
        }
        ImmersionSessionState.FAILED -> when (transition) {
            SessionTransition.RESET -> ImmersionSessionState.NOT_STARTED
            else -> illegal(state, transition)
        }
        ImmersionSessionState.SUPPRESSED -> when (transition) {
            SessionTransition.SUPPRESS -> ImmersionSessionState.SUPPRESSED
            SessionTransition.RESET -> ImmersionSessionState.NOT_STARTED
            else -> illegal(state, transition)
        }
    }

    fun isActive(state: ImmersionSessionState): Boolean = state == ImmersionSessionState.ACTIVE

    private fun illegal(
        state: ImmersionSessionState,
        transition: SessionTransition,
    ): Nothing = throw IllegalSessionTransition(state, transition)
}
