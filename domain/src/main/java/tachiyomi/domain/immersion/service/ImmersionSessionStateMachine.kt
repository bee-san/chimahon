package tachiyomi.domain.immersion.service

enum class ImmersionSessionState {
    NOT_STARTED,
    ACTIVE,
    PAUSED,
    IDLE,
    BACKGROUND,
    FINALIZED,
}

enum class SessionTransition {
    START,
    PAUSE,
    RESUME,
    IDLE_TIMEOUT,
    FOREGROUND_LOST,
    FOREGROUND_RESTORED,
    FINALIZE,
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
            SessionTransition.START -> ImmersionSessionState.ACTIVE
            else -> illegal(state, transition)
        }
        ImmersionSessionState.ACTIVE -> when (transition) {
            SessionTransition.PAUSE -> ImmersionSessionState.PAUSED
            SessionTransition.RESUME -> ImmersionSessionState.ACTIVE
            SessionTransition.IDLE_TIMEOUT -> ImmersionSessionState.IDLE
            SessionTransition.FOREGROUND_LOST -> ImmersionSessionState.BACKGROUND
            SessionTransition.FINALIZE -> ImmersionSessionState.FINALIZED
            else -> illegal(state, transition)
        }
        ImmersionSessionState.PAUSED -> when (transition) {
            SessionTransition.PAUSE -> ImmersionSessionState.PAUSED
            SessionTransition.RESUME -> ImmersionSessionState.ACTIVE
            SessionTransition.FOREGROUND_LOST -> ImmersionSessionState.BACKGROUND
            SessionTransition.FINALIZE -> ImmersionSessionState.FINALIZED
            else -> illegal(state, transition)
        }
        ImmersionSessionState.IDLE -> when (transition) {
            SessionTransition.RESUME -> ImmersionSessionState.ACTIVE
            SessionTransition.IDLE_TIMEOUT -> ImmersionSessionState.IDLE
            SessionTransition.FOREGROUND_LOST -> ImmersionSessionState.BACKGROUND
            SessionTransition.FINALIZE -> ImmersionSessionState.FINALIZED
            else -> illegal(state, transition)
        }
        ImmersionSessionState.BACKGROUND -> when (transition) {
            SessionTransition.FOREGROUND_LOST -> ImmersionSessionState.BACKGROUND
            SessionTransition.FOREGROUND_RESTORED -> ImmersionSessionState.PAUSED
            SessionTransition.FINALIZE -> ImmersionSessionState.FINALIZED
            else -> illegal(state, transition)
        }
        ImmersionSessionState.FINALIZED -> when (transition) {
            SessionTransition.FINALIZE -> ImmersionSessionState.FINALIZED
            else -> illegal(state, transition)
        }
    }

    fun isActive(state: ImmersionSessionState): Boolean = state == ImmersionSessionState.ACTIVE

    private fun illegal(
        state: ImmersionSessionState,
        transition: SessionTransition,
    ): Nothing = throw IllegalSessionTransition(state, transition)
}
