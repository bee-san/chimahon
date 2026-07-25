// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.service

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.EventId
import tachiyomi.domain.immersion.model.SessionId
import java.util.UUID

class ImmersionShadowReconcilerTest {
    @Test
    fun `exact novel fixture matches only with exact net progress`() {
        val identity = ImmersionShadowIdentity(
            sessionIds = listOf(sessionId()),
            eventIds = listOf(eventId(), eventId()),
        )

        ImmersionShadowReconciler.reconcile(
            recorded = ImmersionShadowTotals(60_000, 500),
            legacy = ImmersionShadowTotals(60_000, 500),
            identity = identity,
            readingTimeTolerance = ReadingTimeTolerance.Exact,
        ) shouldBe ImmersionShadowResult.Matched

        ImmersionShadowReconciler.reconcile(
            recorded = ImmersionShadowTotals(60_000, 499),
            legacy = ImmersionShadowTotals(60_000, 500),
            identity = identity,
            readingTimeTolerance = ReadingTimeTolerance.Exact,
        ).shouldBeInstanceOf<ImmersionShadowResult.Diverged>()
    }

    @Test
    fun `time discrepancy is allowed only by a typed documented policy`() {
        val identity = ImmersionShadowIdentity(listOf(sessionId()), listOf(eventId()))
        val recorded = ImmersionShadowTotals(50_000, 100)
        val legacy = ImmersionShadowTotals(60_000, 100)

        ImmersionShadowReconciler.reconcile(
            recorded,
            legacy,
            identity,
            ReadingTimeTolerance.Exact,
        ).shouldBeInstanceOf<ImmersionShadowResult.Diverged>()

        ImmersionShadowReconciler.reconcile(
            recorded,
            legacy,
            identity,
            ReadingTimeTolerance.DocumentedPolicyChange(
                maximumDiscrepancyMillis = 10_000,
                policy = ActiveTimePolicyDifference.IDLE_EXCLUDED,
            ),
        ) shouldBe ImmersionShadowResult.Matched
    }

    @Test
    fun `duplicate identities have no tolerance`() {
        val sessionId = sessionId()
        val eventId = eventId()

        ImmersionShadowReconciler.reconcile(
            recorded = ImmersionShadowTotals(1, 1),
            legacy = ImmersionShadowTotals(1, 1),
            identity = ImmersionShadowIdentity(
                sessionIds = listOf(sessionId, sessionId),
                eventIds = listOf(eventId, eventId),
            ),
            readingTimeTolerance = ReadingTimeTolerance.DocumentedPolicyChange(
                maximumDiscrepancyMillis = 100,
                policy = ActiveTimePolicyDifference.BACKGROUND_EXCLUDED,
            ),
        ).shouldBeInstanceOf<ImmersionShadowResult.Diverged>()
    }

    @Test
    fun `shadow monitor retains only typed session or day diagnostics`() {
        val monitor = ImmersionShadowMonitor()
        val identity = ImmersionShadowIdentity(listOf(sessionId()), listOf(eventId()))

        monitor.compare(
            scope = ImmersionShadowScope.DAY,
            recorded = ImmersionShadowTotals(1_000, 10),
            legacy = ImmersionShadowTotals(1_000, 10),
            identity = identity,
            readingTimeTolerance = ReadingTimeTolerance.Exact,
        )

        monitor.lastDiagnostic.value shouldBe ImmersionShadowDiagnostic(
            scope = ImmersionShadowScope.DAY,
            result = ImmersionShadowResult.Matched,
        )
    }

    private fun sessionId() = SessionId(UUID.randomUUID().toString())

    private fun eventId() = EventId(UUID.randomUUID().toString())
}
