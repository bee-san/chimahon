// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.reader.viewer

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.LookupStatus
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SourceUnitId
import tachiyomi.domain.immersion.service.InteractionProvenance
import tachiyomi.domain.immersion.service.LookupIntentToken

class InheritedLookupAttributionTest {

    @Test
    fun `missing parent token retains the caller attribution`() {
        val fallback = InteractionProvenance(sessionId(1), sourceUnitId(1))

        resolveInheritedLookupAttribution(
            inheritedLookupToken = null,
            fallbackProvenance = fallback,
        ) shouldBe InheritedLookupAttribution.Begin(fallback)
    }

    @Test
    fun `tracked parent freezes its session and source for descendants`() {
        val parent = token(sessionId(1), sourceUnitId(1))

        resolveInheritedLookupAttribution(
            inheritedLookupToken = parent,
            fallbackProvenance = InteractionProvenance(sessionId(2), sourceUnitId(2)),
        ) shouldBe InheritedLookupAttribution.Begin(
            InteractionProvenance(sessionId(1), sourceUnitId(1)),
        )
    }

    @Test
    fun `suppressed parent cannot fall back to a later ambient session`() {
        val parent = token(sessionId = null, sourceUnitId = null)

        resolveInheritedLookupAttribution(
            inheritedLookupToken = parent,
            fallbackProvenance = InteractionProvenance(sessionId(2), sourceUnitId(2)),
        ) shouldBe InheritedLookupAttribution.Suppressed(parent)
    }

    @Test
    fun `result status distinguishes success empty and failure`() {
        resolveLookupResultStatus(
            hasResults = true,
            error = "Partial lookup warning",
        ) shouldBe LookupStatus.SUCCESS
        resolveLookupResultStatus(
            hasResults = false,
            error = null,
        ) shouldBe LookupStatus.EMPTY
        resolveLookupResultStatus(
            hasResults = false,
            error = "Lookup failed",
        ) shouldBe LookupStatus.FAILED
    }

    @Test
    fun `completed deferred cancellation remains a cancelled lookup`() {
        resolveLookupExceptionStatus(
            CancellationException("Lookup dismissed"),
        ) shouldBe LookupStatus.CANCELLED
        resolveLookupExceptionStatus(
            IllegalStateException("Lookup failed"),
        ) shouldBe LookupStatus.FAILED
    }

    private fun token(
        sessionId: SessionId?,
        sourceUnitId: SourceUnitId?,
    ) = LookupIntentToken(
        id = "lookup",
        sessionId = sessionId,
        sourceUnitId = sourceUnitId,
        queryHash = "query-hash",
        rawQuery = null,
        accepted = sessionId != null,
    )

    private fun sessionId(value: Int) =
        SessionId("00000000-0000-4000-8000-${value.toString().padStart(12, '0')}")

    private fun sourceUnitId(value: Int) =
        SourceUnitId("00000000-0000-4000-8001-${value.toString().padStart(12, '0')}")
}
