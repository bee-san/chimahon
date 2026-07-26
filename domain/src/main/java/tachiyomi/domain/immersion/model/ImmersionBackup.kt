// SPDX-License-Identifier: MIT

package tachiyomi.domain.immersion.model

import kotlinx.serialization.Serializable

@Serializable
data class ImmersionPortableArchive(
    val formatVersion: Int,
    val sourceSchemaVersion: Int,
    val createdAtEpochMillis: Long,
    val includesRawText: Boolean,
    val tables: List<ImmersionPortableTable>,
) {
    init {
        require(formatVersion > 0)
        require(sourceSchemaVersion > 0)
        require(createdAtEpochMillis >= 0)
        require(tables.map { it.name }.distinct().size == tables.size)
    }
}

@Serializable
data class ImmersionPortableTable(
    val name: String,
    val columns: List<ImmersionPortableColumn>,
    val rows: List<ImmersionPortableRow>,
) {
    init {
        require(name.isNotBlank())
        require(columns.isNotEmpty())
        require(columns.map { it.name }.distinct().size == columns.size)
        require(columns.any { it.primaryKeyPosition > 0 })
        require(rows.all { it.cells.size == columns.size })
    }
}

@Serializable
data class ImmersionPortableColumn(
    val name: String,
    val affinity: ImmersionPortableAffinity,
    val primaryKeyPosition: Int,
) {
    init {
        require(name.isNotBlank())
        require(primaryKeyPosition >= 0)
    }
}

@Serializable
enum class ImmersionPortableAffinity {
    TEXT,
    INTEGER,
    REAL,
    BLOB,
}

@Serializable
data class ImmersionPortableRow(
    val cells: List<ImmersionPortableCell>,
)

@Serializable
data class ImmersionPortableCell(
    val kind: ImmersionPortableCellKind,
    val textValue: String? = null,
    val integerValue: Long? = null,
    val realValue: Double? = null,
    val blobValue: ByteArray? = null,
) {
    init {
        val presentValues = listOfNotNull(textValue, integerValue, realValue, blobValue).size
        require((kind == ImmersionPortableCellKind.NULL && presentValues == 0) || presentValues == 1)
        require(kind != ImmersionPortableCellKind.TEXT || textValue != null)
        require(kind != ImmersionPortableCellKind.INTEGER || integerValue != null)
        require(kind != ImmersionPortableCellKind.REAL || realValue != null)
        require(kind != ImmersionPortableCellKind.BLOB || blobValue != null)
    }
}

@Serializable
enum class ImmersionPortableCellKind {
    NULL,
    TEXT,
    INTEGER,
    REAL,
    BLOB,
}

@Serializable
data class ImmersionMergeReport(
    val insertedRows: Long,
    val unchangedRows: Long,
    val skippedByTombstoneRows: Long,
    val quarantinedConflicts: Long,
    val rebuiltRollupRows: Long,
    val disposition: ImmersionMergeDisposition,
    val verification: ImmersionMergeVerification,
) {
    init {
        require(insertedRows >= 0)
        require(unchangedRows >= 0)
        require(skippedByTombstoneRows >= 0)
        require(quarantinedConflicts >= 0)
        require(rebuiltRollupRows >= 0)
    }
}

@Serializable
enum class ImmersionMergeDisposition {
    COMPLETED,
    RESUMED,
    ALREADY_COMPLETE,
}

@Serializable
data class ImmersionMergeVerification(
    val archiveDigest: String,
    val eligibleRows: Long,
    val accountedRows: Long,
    val firstRollupRows: Long,
    val secondRollupRows: Long,
    val firstRollupDigest: String,
    val secondRollupDigest: String,
    val entityCounts: ImmersionMergeEntityCounts,
    val integrity: ImmersionIntegrityReport,
) {
    init {
        require(archiveDigest.isNotBlank())
        require(eligibleRows >= 0)
        require(accountedRows >= 0)
        require(firstRollupRows >= 0)
        require(secondRollupRows >= 0)
        require(firstRollupDigest.isNotBlank())
        require(secondRollupDigest.isNotBlank())
    }

    val isHealthy: Boolean
        get() = eligibleRows == accountedRows &&
            firstRollupRows == secondRollupRows &&
            firstRollupDigest == secondRollupDigest &&
            integrity.isHealthy
}

@Serializable
data class ImmersionMergeEntityCounts(
    val titles: Long,
    val sessions: Long,
    val events: Long,
    val sourceUnits: Long,
    val words: Long,
    val characters: Long,
    val lookups: Long,
    val ankiOperations: Long,
    val goals: Long,
) {
    init {
        require(titles >= 0)
        require(sessions >= 0)
        require(events >= 0)
        require(sourceUnits >= 0)
        require(words >= 0)
        require(characters >= 0)
        require(lookups >= 0)
        require(ankiOperations >= 0)
        require(goals >= 0)
    }
}

@Serializable
data class ImmersionDeletionPreview(
    val sessions: Long,
    val activeDurationMillis: Long,
    val grossCharacters: Long,
    val sourceUnits: Long,
    val words: Long,
    val characters: Long,
    val selectionDigest: String? = null,
    val databaseRevision: Long? = null,
) {
    init {
        require(sessions >= 0)
        require(activeDurationMillis >= 0)
        require(grossCharacters >= 0)
        require(sourceUnits >= 0)
        require(words >= 0)
        require(characters >= 0)
        require((selectionDigest == null) == (databaseRevision == null)) {
            "Scoped deletion identity and revision must be supplied together"
        }
        require(selectionDigest == null || selectionDigest.isNotBlank()) {
            "Scoped deletion identity cannot be blank"
        }
        require(databaseRevision == null || databaseRevision >= 0) {
            "Database revision cannot be negative"
        }
    }
}

@Serializable
data class ImmersionMaintenanceSummary(
    val databaseBytes: Long,
    val sessions: Long,
    val events: Long,
    val sourceUnits: Long,
    val rawTextSourceUnits: Long,
    val rawTextBytes: Long,
    val words: Long,
    val characters: Long,
    val quarantinedConflicts: Long,
    val lastRawTextCleanupAtEpochMillis: Long?,
) {
    init {
        require(databaseBytes >= 0)
        require(sessions >= 0)
        require(events >= 0)
        require(sourceUnits >= 0)
        require(rawTextSourceUnits >= 0)
        require(rawTextBytes >= 0)
        require(words >= 0)
        require(characters >= 0)
        require(quarantinedConflicts >= 0)
        require(lastRawTextCleanupAtEpochMillis == null || lastRawTextCleanupAtEpochMillis >= 0)
    }
}
