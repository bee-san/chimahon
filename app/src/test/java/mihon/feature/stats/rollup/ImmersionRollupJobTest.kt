// SPDX-License-Identifier: MIT

package mihon.feature.stats.rollup

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.immersion.service.ImmersionDiagnosticErrorCode
import tachiyomi.domain.immersion.service.ImmersionStatsDiagnosticsStore

class ImmersionRollupJobTest {

    @Test
    fun `rollup failure diagnostics use the rollup stage and typed code`() {
        val diagnostics = ImmersionStatsDiagnosticsStore()

        recordImmersionRollupFailure(diagnostics)

        diagnostics.state.value.lastRollupError shouldBe ImmersionDiagnosticErrorCode.ROLLUP_FAILED
        diagnostics.state.value.lastIndexError shouldBe null
    }
}
