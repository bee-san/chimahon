package mihon.feature.stats.sync

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import tachiyomi.domain.immersion.model.ImmersionPortableAffinity
import tachiyomi.domain.immersion.model.ImmersionPortableArchive
import tachiyomi.domain.immersion.model.ImmersionPortableCell
import tachiyomi.domain.immersion.model.ImmersionPortableCellKind
import tachiyomi.domain.immersion.model.ImmersionPortableRow
import tachiyomi.domain.immersion.model.ImmersionPortableTable

internal fun Backup.hasSyncEntriesOrImmersionStats(): Boolean =
    backupManga.isNotEmpty() ||
        backupAnime.isNotEmpty() ||
        backupNovels.isNotEmpty() ||
        backupImmersionStats != null

internal fun hasRestorableRemoteSyncData(
    filteredFavorites: List<BackupManga>,
    remoteBackup: Backup,
): Boolean =
    filteredFavorites.isNotEmpty() ||
        remoteBackup.backupAnime.isNotEmpty() ||
        remoteBackup.backupNovels.isNotEmpty() ||
        remoteBackup.backupImmersionStats != null

internal fun Backup.filterImmersionStatsForRestore(enabled: Boolean): Backup =
    if (enabled) this else copy(backupImmersionStats = null)

internal fun Backup.hasSameSyncPayloadAs(other: Backup?): Boolean =
    other != null &&
        copy(backupImmersionStats = null) == other.copy(backupImmersionStats = null) &&
        backupImmersionStats.hasSameSyncContentAs(other.backupImmersionStats)

internal fun mergeImmersionPortableArchives(
    local: ImmersionPortableArchive?,
    remote: ImmersionPortableArchive?,
): ImmersionPortableArchive? {
    if (local == null) return remote?.canonicalizeForSync(remote.includesRawText)
    if (remote == null) return local.canonicalizeForSync(local.includesRawText)

    require(local.formatVersion == remote.formatVersion) {
        "Cannot merge immersion sync archives with different format versions"
    }
    require(local.sourceSchemaVersion == remote.sourceSchemaVersion) {
        "Cannot merge immersion sync archives with different schema versions"
    }
    val localTableNames = local.tables.mapTo(mutableSetOf()) { it.name }
    val remoteTableNames = remote.tables.mapTo(mutableSetOf()) { it.name }
    require(localTableNames == remoteTableNames) {
        "Cannot merge immersion sync archives with different table sets"
    }

    // The archive created from current local settings is authoritative for raw-text privacy.
    val includesRawText = local.includesRawText
    val canonicalLocal = local.canonicalizeForSync(includesRawText)
    val canonicalRemote = remote.canonicalizeForSync(includesRawText)
    val localTables = canonicalLocal.tables.associateBy { it.name }
    val remoteTables = canonicalRemote.tables.associateBy { it.name }
    val mergedTables = localTableNames.sorted().map { tableName ->
        val localTable = checkNotNull(localTables[tableName])
        val remoteTable = checkNotNull(remoteTables[tableName])
        require(localTable.columns == remoteTable.columns) {
            "Cannot merge immersion sync table $tableName with different columns"
        }
        localTable.copy(
            rows = (localTable.rows + remoteTable.rows)
                .distinctBy(ImmersionPortableRow::syncKey)
                .sortedWith(localTable.rowComparator()),
        )
    }

    return ImmersionPortableArchive(
        formatVersion = local.formatVersion,
        sourceSchemaVersion = local.sourceSchemaVersion,
        createdAtEpochMillis = maxOf(local.createdAtEpochMillis, remote.createdAtEpochMillis),
        includesRawText = includesRawText,
        tables = mergedTables,
    )
}

private fun ImmersionPortableArchive?.hasSameSyncContentAs(
    other: ImmersionPortableArchive?,
): Boolean {
    if (this == null || other == null) return this == null && other == null
    if (
        formatVersion != other.formatVersion ||
        sourceSchemaVersion != other.sourceSchemaVersion ||
        createdAtEpochMillis != other.createdAtEpochMillis ||
        includesRawText != other.includesRawText
    ) {
        return false
    }
    val first = canonicalizeForSync(includesRawText)
    val second = other.canonicalizeForSync(other.includesRawText)
    return first.tables.size == second.tables.size &&
        first.tables.zip(second.tables).all { (firstTable, secondTable) ->
            firstTable.name == secondTable.name &&
                firstTable.columns == secondTable.columns &&
                firstTable.rows.map(ImmersionPortableRow::syncKey) ==
                secondTable.rows.map(ImmersionPortableRow::syncKey)
        }
}

private fun ImmersionPortableArchive.canonicalizeForSync(
    outputIncludesRawText: Boolean,
): ImmersionPortableArchive =
    copy(
        includesRawText = outputIncludesRawText,
        tables = tables
            .map { table ->
                table.canonicalizeForSync(
                    preservePrivateText = outputIncludesRawText && includesRawText,
                )
            }
            .sortedBy { it.name },
    )

private fun ImmersionPortableTable.canonicalizeForSync(
    preservePrivateText: Boolean,
): ImmersionPortableTable {
    val primaryKeyColumns = columns
        .withIndex()
        .filter { it.value.primaryKeyPosition > 0 }
        .sortedBy { it.value.primaryKeyPosition }
    require(primaryKeyColumns.map { it.value.primaryKeyPosition } == (1..primaryKeyColumns.size).toList()) {
        "Cannot sync immersion table $name with an invalid primary key"
    }

    val sanitizedRows = rows.map { row ->
        val cells = row.cells.mapIndexed { index, cell ->
            val column = columns[index]
            require(cell.kind == ImmersionPortableCellKind.NULL || cell.kind.matches(column.affinity)) {
                "Cannot sync immersion table $name with a cell that does not match ${column.name}"
            }
            if (!preservePrivateText && name to column.name in IMMERSION_PRIVATE_TEXT_COLUMNS) {
                ImmersionPortableCell(ImmersionPortableCellKind.NULL)
            } else {
                cell
            }
        }
        require(primaryKeyColumns.none { cells[it.index].kind == ImmersionPortableCellKind.NULL }) {
            "Cannot sync immersion table $name with a null primary key"
        }
        ImmersionPortableRow(cells)
    }
    return copy(
        rows = sanitizedRows
            .distinctBy(ImmersionPortableRow::syncKey)
            .sortedWith(rowComparator()),
    )
}

private fun ImmersionPortableTable.rowComparator(): Comparator<ImmersionPortableRow> {
    val primaryKeyIndices = columns
        .withIndex()
        .filter { it.value.primaryKeyPosition > 0 }
        .sortedBy { it.value.primaryKeyPosition }
        .map { it.index }
    return Comparator { first, second ->
        compareCellLists(
            first = primaryKeyIndices.map(first.cells::get),
            second = primaryKeyIndices.map(second.cells::get),
        ).takeIf { it != 0 }
            ?: compareCellLists(first.cells, second.cells)
    }
}

private fun compareCellLists(
    first: List<ImmersionPortableCell>,
    second: List<ImmersionPortableCell>,
): Int {
    first.indices.forEach { index ->
        first[index].compareTo(second[index]).takeIf { it != 0 }?.let { return it }
    }
    return first.size.compareTo(second.size)
}

private fun ImmersionPortableCell.compareTo(other: ImmersionPortableCell): Int {
    kind.compareTo(other.kind).takeIf { it != 0 }?.let { return it }
    return when (kind) {
        ImmersionPortableCellKind.NULL -> 0
        ImmersionPortableCellKind.TEXT -> checkNotNull(textValue).compareTo(checkNotNull(other.textValue))
        ImmersionPortableCellKind.INTEGER -> checkNotNull(integerValue).compareTo(checkNotNull(other.integerValue))
        ImmersionPortableCellKind.REAL -> checkNotNull(realValue).compareTo(checkNotNull(other.realValue))
        ImmersionPortableCellKind.BLOB -> compareBlobs(checkNotNull(blobValue), checkNotNull(other.blobValue))
    }
}

private fun compareBlobs(first: ByteArray, second: ByteArray): Int {
    repeat(minOf(first.size, second.size)) { index ->
        val comparison = (first[index].toInt() and 0xff).compareTo(second[index].toInt() and 0xff)
        if (comparison != 0) return comparison
    }
    return first.size.compareTo(second.size)
}

private fun ImmersionPortableCellKind.matches(affinity: ImmersionPortableAffinity): Boolean =
    when (affinity) {
        ImmersionPortableAffinity.TEXT -> this == ImmersionPortableCellKind.TEXT
        ImmersionPortableAffinity.INTEGER -> this == ImmersionPortableCellKind.INTEGER
        ImmersionPortableAffinity.REAL -> this == ImmersionPortableCellKind.REAL
        ImmersionPortableAffinity.BLOB -> this == ImmersionPortableCellKind.BLOB
    }

private data class ImmersionPortableCellSyncKey(
    val kind: ImmersionPortableCellKind,
    val textValue: String?,
    val integerValue: Long?,
    val realValue: Double?,
    val blobValue: ByteArraySyncKey?,
)

private class ByteArraySyncKey(
    private val value: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ByteArraySyncKey && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()
}

private fun ImmersionPortableRow.syncKey(): List<ImmersionPortableCellSyncKey> =
    cells.map(ImmersionPortableCell::syncKey)

private fun ImmersionPortableCell.syncKey(): ImmersionPortableCellSyncKey =
    ImmersionPortableCellSyncKey(
        kind = kind,
        textValue = textValue,
        integerValue = integerValue,
        realValue = realValue,
        blobValue = blobValue?.let(::ByteArraySyncKey),
    )

private val IMMERSION_PRIVATE_TEXT_COLUMNS = setOf(
    "immersion_source_unit" to "raw_text",
    "immersion_source_unit" to "raw_text_encoding",
    "immersion_goal_check_in" to "note",
)
