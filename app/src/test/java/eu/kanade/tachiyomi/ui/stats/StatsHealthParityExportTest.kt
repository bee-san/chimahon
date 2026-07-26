// SPDX-License-Identifier: MIT

package eu.kanade.tachiyomi.ui.stats

import com.canopus.chimareader.stats.capture.NovelReconciliationEntry
import com.canopus.chimareader.stats.capture.NovelReconciliationReport
import com.canopus.chimareader.stats.capture.NovelReconciliationScope
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import mihon.feature.stats.capture.MangaReconciliationEntry
import mihon.feature.stats.capture.MangaReconciliationReport
import mihon.feature.stats.capture.MangaReconciliationScope
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.model.EventId
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.service.ImmersionAdapterDiagnostics
import tachiyomi.domain.immersion.service.ImmersionCaptureAdapter
import tachiyomi.domain.immersion.service.ImmersionDiagnosticErrorCode
import tachiyomi.domain.immersion.service.ImmersionShadowResult
import tachiyomi.domain.immersion.service.ImmersionStatsDiagnostics

class StatsHealthParityExportTest {

    @Test
    fun `parity entries aggregate deterministically by media and scope`() {
        val export = statsHealthParityExport(
            diagnostics = diagnostics(),
            rollupBacklogRangeCount = 7,
            rollupBacklogEventCount = 11,
            novelReport = novelReport(),
            mangaReport = mangaReport(),
            createdAtEpochMillis = 123,
        )

        export.parity shouldContainExactly listOf(
            StatsParityAggregateExport(
                media = StatsParityMedia.NOVEL,
                scope = StatsParityScope.SESSION,
                observations = 2,
                evidenceAvailable = true,
                matched = 1,
                diverged = 1,
                nonComparable = 0,
            ),
            StatsParityAggregateExport(
                media = StatsParityMedia.NOVEL,
                scope = StatsParityScope.DAY,
                observations = 1,
                evidenceAvailable = true,
                matched = 0,
                diverged = 0,
                nonComparable = 1,
            ),
            StatsParityAggregateExport(
                media = StatsParityMedia.MANGA,
                scope = StatsParityScope.SESSION,
                observations = 1,
                evidenceAvailable = true,
                matched = 0,
                diverged = 0,
                nonComparable = 1,
            ),
            StatsParityAggregateExport(
                media = StatsParityMedia.MANGA,
                scope = StatsParityScope.DAY,
                observations = 1,
                evidenceAvailable = true,
                matched = 1,
                diverged = 0,
                nonComparable = 0,
            ),
            StatsParityAggregateExport(
                media = StatsParityMedia.VIDEO,
                scope = StatsParityScope.SESSION,
                observations = 0,
                evidenceAvailable = false,
                matched = 0,
                diverged = 0,
                nonComparable = 0,
            ),
            StatsParityAggregateExport(
                media = StatsParityMedia.VIDEO,
                scope = StatsParityScope.DAY,
                observations = 0,
                evidenceAvailable = false,
                matched = 0,
                diverged = 0,
                nonComparable = 0,
            ),
        )
        export.diagnostics.droppedCommandCount shouldBe 2
        export.diagnostics.lastWriteErrorCode shouldBe "DATABASE_BUSY"
        export.diagnostics.rollupBacklogRangeCount shouldBe 7
        export.diagnostics.rollupBacklogEventCount shouldBe 11
        export.diagnostics.adapters shouldContainExactly listOf(
            StatsAdapterDiagnosticsExport(
                adapter = StatsCaptureAdapter.NOVEL,
                droppedSnapshotCount = 3,
                droppedSemanticCommandCount = 0,
                workerFailureCount = 0,
            ),
            StatsAdapterDiagnosticsExport(
                adapter = StatsCaptureAdapter.MANGA,
                droppedSnapshotCount = 0,
                droppedSemanticCommandCount = 4,
                workerFailureCount = 0,
            ),
            StatsAdapterDiagnosticsExport(
                adapter = StatsCaptureAdapter.VIDEO,
                droppedSnapshotCount = 0,
                droppedSemanticCommandCount = 0,
                workerFailureCount = 5,
            ),
        )
    }

    @Test
    fun `health JSON is deterministic and omits report keys and identifiers`() {
        val first = healthDocument()
        val second = healthDocument()
        val encoded = first.bytes.decodeToString()

        first.fileName shouldBe "chimahon-stats-health-parity.json"
        first.mimeType shouldBe "application/json"
        first.bytes.contentEquals(second.bytes) shouldBe true
        PRIVATE_VALUES.forEach { privateValue ->
            encoded.contains(privateValue) shouldBe false
        }
        Json.parseToJsonElement(encoded).keysRecursively()
            .intersect(FORBIDDEN_FIELDS) shouldBe emptySet()
    }

    private fun healthDocument() = statsHealthParityDocument(
        diagnostics = diagnostics(),
        rollupBacklogRangeCount = 7,
        rollupBacklogEventCount = 11,
        novelReport = novelReport(),
        mangaReport = mangaReport(),
        createdAtEpochMillis = 123,
    )

    private fun diagnostics() = ImmersionStatsDiagnostics(
        queueDepth = 1,
        maximumQueueDepth = 3,
        lastWriteLatencyMillis = 7,
        lastWriteError = ImmersionDiagnosticErrorCode.DATABASE_BUSY,
        lastIndexError = ImmersionDiagnosticErrorCode.INDEXING_FAILED,
        droppedCommandCount = NonNegativeCounter(2),
        abandonedRecoveryCount = NonNegativeCounter(1),
        rollupLagEventCount = NonNegativeCounter(4),
        adapterDiagnostics = ImmersionCaptureAdapter.entries.associateWith { adapter ->
            when (adapter) {
                ImmersionCaptureAdapter.NOVEL -> ImmersionAdapterDiagnostics(
                    droppedSnapshotCount = NonNegativeCounter(3),
                )
                ImmersionCaptureAdapter.MANGA -> ImmersionAdapterDiagnostics(
                    droppedSemanticCommandCount = NonNegativeCounter(4),
                )
                ImmersionCaptureAdapter.VIDEO -> ImmersionAdapterDiagnostics(
                    workerFailureCount = NonNegativeCounter(5),
                )
            }
        },
    )

    private fun novelReport() = NovelReconciliationReport(
        entries = listOf(
            NovelReconciliationEntry(
                scope = NovelReconciliationScope.SESSION,
                key = PRIVATE_NOVEL_KEY,
                legacyComparable = true,
                result = ImmersionShadowResult.Matched,
            ),
            NovelReconciliationEntry(
                scope = NovelReconciliationScope.SESSION,
                key = PRIVATE_NOVEL_DIVERGENCE_KEY,
                legacyComparable = true,
                result = divergence(),
            ),
            NovelReconciliationEntry(
                scope = NovelReconciliationScope.DAY,
                key = PRIVATE_NOVEL_DAY_KEY,
                legacyComparable = false,
                result = null,
            ),
        ),
    )

    private fun mangaReport() = MangaReconciliationReport(
        entries = listOf(
            MangaReconciliationEntry(
                scope = MangaReconciliationScope.SESSION,
                key = PRIVATE_MANGA_KEY,
                legacyComparable = false,
                result = null,
            ),
            MangaReconciliationEntry(
                scope = MangaReconciliationScope.DAY,
                key = PRIVATE_MANGA_DAY_KEY,
                legacyComparable = true,
                result = ImmersionShadowResult.Matched,
            ),
        ),
    )

    private fun divergence() = ImmersionShadowResult.Diverged(
        timeDiscrepancyMillis = 1,
        netCharacterDiscrepancy = 2,
        duplicateSessionIds = setOf(SessionId(PRIVATE_SESSION_ID)),
        duplicateEventIds = setOf(EventId(PRIVATE_EVENT_ID)),
    )

    private fun JsonElement.keysRecursively(): Set<String> = when (this) {
        is JsonObject -> keys + values.flatMap { it.keysRecursively() }
        is JsonArray -> flatMap { it.keysRecursively() }.toSet()
        else -> emptySet()
    }

    private companion object {
        const val PRIVATE_NOVEL_KEY = "private-novel-title-and-locator"
        const val PRIVATE_NOVEL_DIVERGENCE_KEY = "private-novel-session-key"
        const val PRIVATE_NOVEL_DAY_KEY = "private-novel-day-key"
        const val PRIVATE_MANGA_KEY = "private-manga-session-key"
        const val PRIVATE_MANGA_DAY_KEY = "private-manga-day-key"
        const val PRIVATE_SESSION_ID = "00000000-0000-4000-8000-000000000001"
        const val PRIVATE_EVENT_ID = "00000000-0000-4000-8000-000000000002"

        val PRIVATE_VALUES = setOf(
            PRIVATE_NOVEL_KEY,
            PRIVATE_NOVEL_DIVERGENCE_KEY,
            PRIVATE_NOVEL_DAY_KEY,
            PRIVATE_MANGA_KEY,
            PRIVATE_MANGA_DAY_KEY,
            PRIVATE_SESSION_ID,
            PRIVATE_EVENT_ID,
        )
        val FORBIDDEN_FIELDS = setOf(
            "rawText",
            "raw_text",
            "title",
            "titles",
            "titleId",
            "title_id",
            "locator",
            "sourceLocator",
            "source_locator",
            "sessionId",
            "sessionIds",
            "session_id",
            "session_ids",
            "eventId",
            "eventIds",
            "event_id",
            "event_ids",
            "key",
            "reportKey",
            "report_key",
        )
    }
}
