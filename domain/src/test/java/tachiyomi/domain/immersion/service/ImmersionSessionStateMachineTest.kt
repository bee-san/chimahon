// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ImmersionSessionStateMachineTest {
    @Test
    fun `foreground active idle active background finalize flow is explicit`() {
        var state = ImmersionSessionState.NOT_STARTED

        state = ImmersionSessionStateMachine.transition(state, SessionTransition.BEGIN_START)
        state shouldBe ImmersionSessionState.STARTING
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
        state = ImmersionSessionStateMachine.transition(state, SessionTransition.BEGIN_FINALIZE)
        state shouldBe ImmersionSessionState.FINALIZING
        state = ImmersionSessionStateMachine.transition(state, SessionTransition.FINALIZE)
        state shouldBe ImmersionSessionState.FINALIZED
    }

    @Test
    fun `transition table accepts only documented transitions`() {
        val allowed = mapOf(
            ImmersionSessionState.NOT_STARTED to setOf(
                SessionTransition.BEGIN_START,
                SessionTransition.SUPPRESS,
            ),
            ImmersionSessionState.STARTING to setOf(
                SessionTransition.START,
                SessionTransition.FAIL,
            ),
            ImmersionSessionState.ACTIVE to setOf(
                SessionTransition.PAUSE,
                SessionTransition.RESUME,
                SessionTransition.IDLE_TIMEOUT,
                SessionTransition.FOREGROUND_LOST,
                SessionTransition.BEGIN_FINALIZE,
                SessionTransition.FAIL,
            ),
            ImmersionSessionState.PAUSED to setOf(
                SessionTransition.PAUSE,
                SessionTransition.RESUME,
                SessionTransition.FOREGROUND_LOST,
                SessionTransition.BEGIN_FINALIZE,
                SessionTransition.FAIL,
            ),
            ImmersionSessionState.IDLE to setOf(
                SessionTransition.PAUSE,
                SessionTransition.RESUME,
                SessionTransition.IDLE_TIMEOUT,
                SessionTransition.FOREGROUND_LOST,
                SessionTransition.BEGIN_FINALIZE,
                SessionTransition.FAIL,
            ),
            ImmersionSessionState.BACKGROUND to setOf(
                SessionTransition.FOREGROUND_LOST,
                SessionTransition.FOREGROUND_RESTORED,
                SessionTransition.BEGIN_FINALIZE,
                SessionTransition.FAIL,
            ),
            ImmersionSessionState.FINALIZING to setOf(
                SessionTransition.FINALIZE,
                SessionTransition.FAIL,
            ),
            ImmersionSessionState.FINALIZED to setOf(
                SessionTransition.FINALIZE,
                SessionTransition.RESET,
            ),
            ImmersionSessionState.FAILED to setOf(SessionTransition.RESET),
            ImmersionSessionState.SUPPRESSED to setOf(
                SessionTransition.SUPPRESS,
                SessionTransition.RESET,
            ),
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
