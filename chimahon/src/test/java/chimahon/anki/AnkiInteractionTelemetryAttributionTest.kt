// SPDX-License-Identifier: MIT

package chimahon.anki

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SourceUnitId
import tachiyomi.domain.immersion.service.InteractionProvenance
import tachiyomi.domain.immersion.service.LookupIntentToken

class AnkiInteractionTelemetryAttributionTest {

    @Test
    fun `missing lookup token retains ambient attribution`() {
        assertSame(
            AnkiInteractionTelemetryAttribution.Ambient,
            null.toAnkiInteractionTelemetryAttribution(),
        )
    }

    @Test
    fun `missing lookup token suppresses telemetry when ambient attribution is disabled`() {
        assertSame(
            AnkiInteractionTelemetryAttribution.Suppressed,
            null.toAnkiInteractionTelemetryAttribution(
                allowAmbientAttribution = false,
            ),
        )
    }

    @Test
    fun `lookup token without a session suppresses Anki telemetry`() {
        assertSame(
            AnkiInteractionTelemetryAttribution.Suppressed,
            lookupToken(sessionId = null, sourceUnitId = null)
                .toAnkiInteractionTelemetryAttribution(),
        )
    }

    @Test
    fun `lookup session and source become explicit Anki provenance`() {
        val sessionId = SessionId("00000000-0000-4000-8000-000000000001")
        val sourceUnitId = SourceUnitId("00000000-0000-4000-8000-000000000002")

        assertEquals(
            AnkiInteractionTelemetryAttribution.Explicit(
                InteractionProvenance(
                    sessionId = sessionId,
                    sourceUnitId = sourceUnitId,
                ),
            ),
            lookupToken(sessionId, sourceUnitId).toAnkiInteractionTelemetryAttribution(),
        )
    }

    private fun lookupToken(
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
}
