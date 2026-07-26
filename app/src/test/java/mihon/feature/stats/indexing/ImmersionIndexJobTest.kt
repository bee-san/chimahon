// SPDX-License-Identifier: MIT

package mihon.feature.stats.indexing

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.service.ImmersionDiagnosticErrorCode
import tachiyomi.domain.immersion.service.ImmersionStatsDiagnosticsStore

class ImmersionIndexJobTest {

    @Test
    fun `short batch with a failure retries instead of completing the worker`() {
        decideImmersionIndexBatch(
            claimed = 1,
            failures = 1,
            batchSize = 32,
        ) shouldBe ImmersionIndexBatchDecision.RETRY
    }

    @Test
    fun `short clean batch completes the worker`() {
        decideImmersionIndexBatch(
            claimed = 1,
            failures = 0,
            batchSize = 32,
        ) shouldBe ImmersionIndexBatchDecision.SUCCESS
    }

    @Test
    fun `full batch continues until the bounded run limit`() {
        decideImmersionIndexBatch(
            claimed = 32,
            failures = 1,
            batchSize = 32,
        ) shouldBe ImmersionIndexBatchDecision.CONTINUE
    }

    @Test
    fun `index failure diagnostics are recorded only for failed batches`() {
        val diagnostics = ImmersionStatsDiagnosticsStore()

        recordImmersionIndexFailure(diagnostics, failureCount = 0)
        diagnostics.state.value.lastIndexError shouldBe null

        recordImmersionIndexFailure(diagnostics, failureCount = 2)
        diagnostics.state.value.lastIndexError shouldBe ImmersionDiagnosticErrorCode.INDEXING_FAILED
        diagnostics.state.value.lastRollupError shouldBe null
    }
}
