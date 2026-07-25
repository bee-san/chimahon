package tachiyomi.domain.immersion.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ImmersionSessionStateMachineTest {
    @Test
    fun `foreground active idle active background finalize flow is explicit`() {
        var state = ImmersionSessionState.NOT_STARTED

        state = ImmersionSessionStateMachine.transition(state, SessionTransition.START)
        state shouldBe ImmersionSessionState.ACTIVE
        state = ImmersionSessionStateMachine.transition(state, SessionTransition.IDLE_TIMEOUT)
        state shouldBe ImmersionSessionState.IDLE
        state = ImmersionSessionStateMachine.transition(state, SessionTransition.RESUME)
        state shouldBe ImmersionSessionState.ACTIVE
        state = ImmersionSessionStateMachine.transition(state, SessionTransition.FOREGROUND_LOST)
        state shouldBe ImmersionSessionState.BACKGROUND
        state = ImmersionSessionStateMachine.transition(state, SessionTransition.FOREGROUND_RESTORED)
        state shouldBe ImmersionSessionState.PAUSED
        state = ImmersionSessionStateMachine.transition(state, SessionTransition.RESUME)
        state shouldBe ImmersionSessionState.ACTIVE
        state = ImmersionSessionStateMachine.transition(state, SessionTransition.FINALIZE)
        state shouldBe ImmersionSessionState.FINALIZED
    }

    @Test
    fun `transition table accepts only documented transitions`() {
        val allowed = mapOf(
            ImmersionSessionState.NOT_STARTED to setOf(SessionTransition.START),
            ImmersionSessionState.ACTIVE to setOf(
                SessionTransition.PAUSE,
                SessionTransition.RESUME,
                SessionTransition.IDLE_TIMEOUT,
                SessionTransition.FOREGROUND_LOST,
                SessionTransition.FINALIZE,
            ),
            ImmersionSessionState.PAUSED to setOf(
                SessionTransition.PAUSE,
                SessionTransition.RESUME,
                SessionTransition.FOREGROUND_LOST,
                SessionTransition.FINALIZE,
            ),
            ImmersionSessionState.IDLE to setOf(
                SessionTransition.RESUME,
                SessionTransition.IDLE_TIMEOUT,
                SessionTransition.FOREGROUND_LOST,
                SessionTransition.FINALIZE,
            ),
            ImmersionSessionState.BACKGROUND to setOf(
                SessionTransition.FOREGROUND_LOST,
                SessionTransition.FOREGROUND_RESTORED,
                SessionTransition.FINALIZE,
            ),
            ImmersionSessionState.FINALIZED to setOf(SessionTransition.FINALIZE),
        )

        ImmersionSessionState.entries.forEach { state ->
            SessionTransition.entries.forEach { transition ->
                if (transition in allowed.getValue(state)) {
                    ImmersionSessionStateMachine.transition(state, transition)
                } else {
                    shouldThrow<IllegalSessionTransition> {
                        ImmersionSessionStateMachine.transition(state, transition)
                    }
                }
            }
        }
    }
}
