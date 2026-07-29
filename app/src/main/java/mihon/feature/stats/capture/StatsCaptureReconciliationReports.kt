package mihon.feature.stats.capture

import com.canopus.chimareader.stats.capture.NovelCaptureReconciliationReporter

/**
 * Drops process-local shadow-rollout evidence after all or part of the stats database is erased.
 */
internal fun clearStatsCaptureReconciliationReports() {
    NovelCaptureReconciliationReporter.clear()
    MangaCaptureReconciliationReporter.clear()
    VideoCaptureReconciliationReporter.clear()
}
