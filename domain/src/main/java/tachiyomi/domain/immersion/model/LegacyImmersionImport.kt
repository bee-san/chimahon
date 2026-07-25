// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.model

import kotlinx.serialization.Serializable

@Serializable
enum class LegacyImportSourceKind {
    NOVEL_JSON,
    MANGA_JSON,
    ANKI_JSON,
}

@Serializable
enum class LegacyImportResultState {
    IMPORTED,
    PARTIAL,
    FAILED,
    ALREADY_IMPORTED,
}

@Serializable
enum class LegacyImportIssueCode {
    CORRUPT_JSON,
    INVALID_DATE,
    INVALID_VALUE,
    MISSING_REQUIRED_FIELD,
    TITLE_UNAVAILABLE,
    DATABASE_ERROR,
}

@Serializable
data class LegacyImportIdentity(
    val sourceKey: String,
    val sourceVersion: Int,
    val contentHash: String,
) {
    init {
        require(sourceKey.isNotBlank()) { "Legacy import source key cannot be blank" }
        require(sourceVersion > 0) { "Legacy import source version must be positive" }
        require(contentHash.matches(SHA_256_HEX)) { "Legacy import content hash must be SHA-256" }
    }
}

@Serializable
data class LegacyDailyAggregate(
    val sessionId: SessionId,
    val titleId: TitleId,
    val titleSourceKey: String,
    val displayTitle: String,
    val mediaKind: MediaKind,
    val profileId: String = "",
    val languageTag: LanguageTag? = null,
    val localDate: ImmersionLocalDate,
    val startAnchorEpochMillis: Long,
    val startZoneId: String,
    val startOffsetSeconds: Int,
    val activeDuration: MillisecondDuration,
    val originalReadingTimeSeconds: Double? = null,
    val characters: NonNegativeCounter = NonNegativeCounter.ZERO,
    val cardsTotal: NonNegativeCounter = NonNegativeCounter.ZERO,
    val completed: Boolean? = null,
    val metadataJson: String? = null,
) {
    init {
        require(titleSourceKey.isNotBlank()) { "Legacy title source key cannot be blank" }
        require(displayTitle.isNotBlank()) { "Legacy title display name cannot be blank" }
        require(profileId.isNotBlank() || profileId.isEmpty()) { "Legacy profile ID cannot contain only whitespace" }
        require(startAnchorEpochMillis >= 0) { "Legacy date anchor cannot be negative" }
        require(startZoneId.isNotBlank()) { "Legacy date anchor zone cannot be blank" }
        require(startOffsetSeconds in -18 * 60 * 60..18 * 60 * 60) {
            "Legacy date anchor offset is outside the valid UTC offset range"
        }
        require(originalReadingTimeSeconds == null || originalReadingTimeSeconds.isFinite()) {
            "Original legacy reading time must be finite"
        }
        require(originalReadingTimeSeconds == null || originalReadingTimeSeconds >= 0.0) {
            "Original legacy reading time cannot be negative"
        }
    }
}

@Serializable
data class LegacyImportBatch(
    val identity: LegacyImportIdentity,
    val sourceKind: LegacyImportSourceKind,
    val aggregates: List<LegacyDailyAggregate>,
    val skippedCount: NonNegativeCounter = NonNegativeCounter.ZERO,
    val failedCount: NonNegativeCounter = NonNegativeCounter.ZERO,
    val errorSummary: String? = null,
    val importedAtEpochMillis: Long,
) {
    init {
        require(importedAtEpochMillis >= 0) { "Legacy import timestamp cannot be negative" }
        require(errorSummary == null || errorSummary.isNotBlank()) {
            "Legacy import error summary cannot be blank"
        }
    }
}

@Serializable
data class LegacyImportResult(
    val identity: LegacyImportIdentity,
    val state: LegacyImportResultState,
    val importedCount: NonNegativeCounter,
    val skippedCount: NonNegativeCounter,
    val failedCount: NonNegativeCounter,
    val errorSummary: String?,
)

@Serializable
data class LegacyAggregateRow(
    val localDate: ImmersionLocalDate,
    val mediaKind: MediaKind,
    val profileId: String,
    val languageTag: LanguageTag?,
    val titleId: TitleId,
    val activeDuration: MillisecondDuration,
    val characters: NonNegativeCounter,
    val cardsTotal: NonNegativeCounter,
    val recordCount: NonNegativeCounter,
)

@Serializable
data class LegacyAggregateTotals(
    val activeDuration: MillisecondDuration = MillisecondDuration(0),
    val characters: NonNegativeCounter = NonNegativeCounter.ZERO,
    val cardsTotal: NonNegativeCounter = NonNegativeCounter.ZERO,
    val records: NonNegativeCounter = NonNegativeCounter.ZERO,
)

private val SHA_256_HEX = Regex("[0-9a-f]{64}")
