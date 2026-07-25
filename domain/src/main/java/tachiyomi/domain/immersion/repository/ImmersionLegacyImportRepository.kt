// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.repository

import tachiyomi.domain.immersion.model.LegacyAggregateRow
import tachiyomi.domain.immersion.model.LegacyImportBatch
import tachiyomi.domain.immersion.model.LegacyImportIdentity
import tachiyomi.domain.immersion.model.LegacyImportResult

interface ImmersionLegacyImportRepository {
    suspend fun importLegacyBatch(batch: LegacyImportBatch): LegacyImportResult

    suspend fun getImportResult(identity: LegacyImportIdentity): LegacyImportResult?

    suspend fun getLegacyAggregates(): List<LegacyAggregateRow>
}
