// SPDX-License-Identifier: MIT

package mihon.feature.stats.capture

import com.canopus.chimareader.stats.capture.NovelCaptureReconciliationReporter

/**
 * Drops process-local shadow-rollout evidence after the underlying stats database is erased.
 */
internal fun clearStatsCaptureReconciliationReports() {
    NovelCaptureReconciliationReporter.clear()
    MangaCaptureReconciliationReporter.clear()
    VideoCaptureReconciliationReporter.clear()
}
