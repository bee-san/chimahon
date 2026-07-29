package mihon.feature.stats.sync

import android.content.Context
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.sync.models.SyncSettings
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.sync.service.SyncData
import eu.kanade.tachiyomi.data.sync.service.SyncService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.immersion.model.ImmersionPortableAffinity
import tachiyomi.domain.immersion.model.ImmersionPortableArchive
import tachiyomi.domain.immersion.model.ImmersionPortableCell
import tachiyomi.domain.immersion.model.ImmersionPortableCellKind
import tachiyomi.domain.immersion.model.ImmersionPortableColumn
import tachiyomi.domain.immersion.model.ImmersionPortableRow
import tachiyomi.domain.immersion.model.ImmersionPortableTable

class ImmersionStatsSyncTest {

    @Test
    fun `remote merge preserves local immersion stats for upload`() {
        val localArchive = immersionArchive(createdAtEpochMillis = 10L, "local")

        val merged = syncService().merge(
            local = SyncData(backup = Backup(backupImmersionStats = localArchive)),
            remote = SyncData(backup = Backup()),
        ).backup!!

        merged.backupImmersionStats shouldBe localArchive
    }

    @Test
    fun `remote merge preserves remote immersion stats for restore`() {
        val remoteArchive = immersionArchive(createdAtEpochMillis = 20L, "remote")

        val merged = syncService().merge(
            local = SyncData(backup = Backup()),
            remote = SyncData(backup = Backup(backupImmersionStats = remoteArchive)),
        ).backup!!

        merged.backupImmersionStats shouldBe remoteArchive
    }

    @Test
    fun `remote merge unions immersion stats without duplicating identical rows`() {
        val merged = syncService().merge(
            local = SyncData(
                backup = Backup(
                    backupImmersionStats = immersionArchive(
                        createdAtEpochMillis = 10L,
                        "shared",
                        "local",
                    ),
                ),
            ),
            remote = SyncData(
                backup = Backup(
                    backupImmersionStats = immersionArchive(
                        createdAtEpochMillis = 20L,
                        "shared",
                        "remote",
                    ),
                ),
            ),
        ).backup!!.backupImmersionStats!!

        merged.createdAtEpochMillis shouldBe 20L
        merged.tables.single().rows
            .map { it.cells.single().textValue }
            .shouldContainExactlyInAnyOrder("shared", "local", "remote")
    }

    @Test
    fun `stats-only remote backup has sync and restore content`() {
        val remote = Backup(backupImmersionStats = immersionArchive())

        remote.hasSyncEntriesOrImmersionStats().shouldBeTrue()
        hasRestorableRemoteSyncData(
            filteredFavorites = emptyList(),
            remoteBackup = remote,
        ).shouldBeTrue()
    }

    @Test
    fun `backup without entries or immersion stats remains empty`() {
        val empty = Backup()

        empty.hasSyncEntriesOrImmersionStats().shouldBeFalse()
        hasRestorableRemoteSyncData(
            filteredFavorites = emptyList(),
            remoteBackup = empty,
        ).shouldBeFalse()
    }

    @Test
    fun `sync preferences persist immersion stats and raw text choices`() {
        val storedPreferences = mutableMapOf<String, InMemoryPreferenceStore.InMemoryPreference<Boolean>>()
        val preferenceStore = mockk<PreferenceStore>()
        every { preferenceStore.getBoolean(any(), any()) } answers {
            val key = firstArg<String>()
            val defaultValue = secondArg<Boolean>()
            storedPreferences.getOrPut(key) {
                InMemoryPreferenceStore.InMemoryPreference(key, null, defaultValue)
            }
        }
        val preferences = SyncPreferences(preferenceStore)

        preferences.getSyncSettings().immersionStats.shouldBeTrue()
        preferences.getSyncSettings().immersionRawText.shouldBeFalse()

        preferences.setSyncSettings(
            SyncSettings(
                immersionStats = false,
                immersionRawText = true,
            ),
        )

        preferences.getSyncSettings().immersionStats.shouldBeFalse()
        preferences.getSyncSettings().immersionRawText.shouldBeTrue()
    }

    @Test
    fun `disabled immersion restore leaves remote archive untouched but filters local restore`() {
        val archive = immersionArchive(1L, "remote")
        val remote = Backup(backupImmersionStats = archive)

        val filtered = remote.filterImmersionStatsForRestore(enabled = false)

        remote.backupImmersionStats shouldBe archive
        remote.hasSyncEntriesOrImmersionStats().shouldBeTrue()
        filtered.backupImmersionStats.shouldBeNull()
        filtered.hasSyncEntriesOrImmersionStats().shouldBeFalse()
        remote.filterImmersionStatsForRestore(enabled = true).backupImmersionStats shouldBe archive
    }

    @Test
    fun `blob rows use content equality for shortcut and merge deduplication`() {
        val first = Backup(backupImmersionStats = blobArchive(byteArrayOf(0, 1, -1)))
        val sameContent = Backup(backupImmersionStats = blobArchive(byteArrayOf(0, 1, -1)))
        val differentContent = Backup(backupImmersionStats = blobArchive(byteArrayOf(0, 2, -1)))

        first.hasSameSyncPayloadAs(sameContent).shouldBeTrue()
        first.hasSameSyncPayloadAs(differentContent).shouldBeFalse()

        val merged = mergeImmersionPortableArchives(
            first.backupImmersionStats,
            sameContent.backupImmersionStats,
        )!!
        merged.tables.single().rows.size shouldBe 1
        merged.tables.single().rows.single().cells[1].blobValue!!
            .contentEquals(byteArrayOf(0, 1, -1))
            .shouldBeTrue()
    }

    @Test
    fun `merge canonicalizes table and row ordering regardless of device direction`() {
        val titleColumns = listOf(textColumn("id", primaryKeyPosition = 1))
        val eventColumns = listOf(textColumn("id", primaryKeyPosition = 1))
        val first = archive(
            createdAtEpochMillis = 10L,
            tables = arrayOf(
                table("immersion_title", titleColumns, row(textCell("z-title"))),
                table("immersion_event", eventColumns, row(textCell("b-event"))),
            ),
        )
        val second = archive(
            createdAtEpochMillis = 20L,
            tables = arrayOf(
                table("immersion_event", eventColumns, row(textCell("a-event"))),
                table("immersion_title", titleColumns, row(textCell("a-title"))),
            ),
        )

        val forward = checkNotNull(mergeImmersionPortableArchives(first, second))
        val reverse = checkNotNull(mergeImmersionPortableArchives(second, first))

        Backup(backupImmersionStats = forward)
            .hasSameSyncPayloadAs(Backup(backupImmersionStats = reverse))
            .shouldBeTrue()
        forward.tables.map { it.name }
            .shouldContainExactly("immersion_event", "immersion_title")
        forward.tables.first().rows.map { it.cells.single().textValue }
            .shouldContainExactly("a-event", "b-event")
    }

    @Test
    fun `merge rejects mismatched table sets and columns`() {
        val title = archive(
            tables = arrayOf(
                table(
                    "immersion_title",
                    listOf(textColumn("id", primaryKeyPosition = 1)),
                    row(textCell("title")),
                ),
            ),
        )
        val event = archive(
            tables = arrayOf(
                table(
                    "immersion_event",
                    listOf(textColumn("id", primaryKeyPosition = 1)),
                    row(textCell("event")),
                ),
            ),
        )
        shouldThrow<IllegalArgumentException> {
            mergeImmersionPortableArchives(title, event)
        }

        val extraColumn = archive(
            tables = arrayOf(
                table(
                    "immersion_title",
                    listOf(
                        textColumn("id", primaryKeyPosition = 1),
                        textColumn("title"),
                    ),
                    row(textCell("title"), textCell("name")),
                ),
            ),
        )
        shouldThrow<IllegalArgumentException> {
            mergeImmersionPortableArchives(title, extraColumn)
        }
    }

    @Test
    fun `local raw text opt out redacts all private fields from merged upload`() {
        val sourceColumns = listOf(
            textColumn("id", primaryKeyPosition = 1),
            blobColumn("raw_text"),
            textColumn("raw_text_encoding"),
        )
        val checkInColumns = listOf(
            textColumn("id", primaryKeyPosition = 1),
            textColumn("note"),
        )
        val local = archive(
            includesRawText = false,
            tables = arrayOf(
                table(
                    "immersion_source_unit",
                    sourceColumns,
                    row(textCell("local"), blobCell("local text"), textCell("UTF-8")),
                ),
                table(
                    "immersion_goal_check_in",
                    checkInColumns,
                    row(textCell("local"), textCell("local note")),
                ),
            ),
        )
        val remote = archive(
            includesRawText = true,
            tables = arrayOf(
                table(
                    "immersion_source_unit",
                    sourceColumns,
                    row(textCell("remote"), blobCell("remote text"), textCell("UTF-8")),
                ),
                table(
                    "immersion_goal_check_in",
                    checkInColumns,
                    row(textCell("remote"), textCell("remote note")),
                ),
            ),
        )

        val merged = checkNotNull(mergeImmersionPortableArchives(local, remote))

        merged.includesRawText.shouldBeFalse()
        merged.tables.forEach { table ->
            table.rows.forEach { row ->
                row.cells.drop(1).forEach { it.kind shouldBe ImmersionPortableCellKind.NULL }
            }
        }
    }

    @Test
    fun `local raw text opt in preserves private fields from both archives`() {
        val columns = listOf(
            textColumn("id", primaryKeyPosition = 1),
            blobColumn("raw_text"),
            textColumn("raw_text_encoding"),
        )
        val local = archive(
            includesRawText = true,
            tables = arrayOf(
                table(
                    "immersion_source_unit",
                    columns,
                    row(textCell("local"), blobCell("local text"), textCell("UTF-8")),
                ),
            ),
        )
        val remote = archive(
            includesRawText = true,
            tables = arrayOf(
                table(
                    "immersion_source_unit",
                    columns,
                    row(textCell("remote"), blobCell("remote text"), textCell("UTF-8")),
                ),
            ),
        )

        val merged = checkNotNull(mergeImmersionPortableArchives(local, remote))
        val rows = merged.tables.single().rows.associateBy { it.cells[0].textValue }

        merged.includesRawText.shouldBeTrue()
        rows.getValue("local").cells[1].blobValue!!.decodeToString() shouldBe "local text"
        rows.getValue("remote").cells[1].blobValue!!.decodeToString() shouldBe "remote text"
        rows.values.forEach { it.cells[2].textValue shouldBe "UTF-8" }
    }

    @Test
    fun `same primary key conflicts and tombstones both reach portable restore archive`() {
        val titleColumns = listOf(
            textColumn("id", primaryKeyPosition = 1),
            textColumn("title"),
        )
        val tombstoneColumns = listOf(
            textColumn("entity_type", primaryKeyPosition = 1),
            textColumn("entity_id", primaryKeyPosition = 2),
            integerColumn("deleted_at"),
        )
        val local = archive(
            tables = arrayOf(
                table(
                    "immersion_title",
                    titleColumns,
                    row(textCell("shared"), textCell("local value")),
                ),
                table("immersion_tombstone", tombstoneColumns),
            ),
        )
        val remote = archive(
            tables = arrayOf(
                table(
                    "immersion_tombstone",
                    tombstoneColumns,
                    row(textCell("TITLE"), textCell("shared"), integerCell(30L)),
                ),
                table(
                    "immersion_title",
                    titleColumns,
                    row(textCell("shared"), textCell("remote value")),
                ),
            ),
        )

        val merged = checkNotNull(mergeImmersionPortableArchives(local, remote))

        merged.tables.single { it.name == "immersion_title" }.rows
            .map { it.cells[1].textValue }
            .shouldContainExactly("local value", "remote value")
        merged.tables.single { it.name == "immersion_tombstone" }.rows.single().cells
            .map { it.textValue ?: it.integerValue?.toString() }
            .shouldContainExactly("TITLE", "shared", "30")
    }

    private fun immersionArchive(
        createdAtEpochMillis: Long = 1L,
        vararg ids: String,
    ) = archive(
        createdAtEpochMillis = createdAtEpochMillis,
        tables = if (ids.isEmpty()) {
            emptyArray()
        } else {
            arrayOf(
                ImmersionPortableTable(
                    name = "immersion_title",
                    columns = listOf(textColumn("id", primaryKeyPosition = 1)),
                    rows = ids.map { row(textCell(it)) },
                ),
            )
        },
    )

    private fun blobArchive(value: ByteArray) = archive(
        tables = arrayOf(
            table(
                "immersion_event",
                listOf(
                    textColumn("id", primaryKeyPosition = 1),
                    blobColumn("metadata_payload"),
                ),
                row(textCell("event"), blobCell(value)),
            ),
        ),
    )

    private fun archive(
        createdAtEpochMillis: Long = 1L,
        includesRawText: Boolean = false,
        tables: Array<ImmersionPortableTable>,
    ) = ImmersionPortableArchive(
        formatVersion = 1,
        sourceSchemaVersion = 1,
        createdAtEpochMillis = createdAtEpochMillis,
        includesRawText = includesRawText,
        tables = tables.toList(),
    )

    private fun table(
        name: String,
        columns: List<ImmersionPortableColumn>,
        vararg rows: ImmersionPortableRow,
    ) = ImmersionPortableTable(name, columns, rows.toList())

    private fun textColumn(name: String, primaryKeyPosition: Int = 0) =
        ImmersionPortableColumn(name, ImmersionPortableAffinity.TEXT, primaryKeyPosition)

    private fun integerColumn(name: String, primaryKeyPosition: Int = 0) =
        ImmersionPortableColumn(name, ImmersionPortableAffinity.INTEGER, primaryKeyPosition)

    private fun blobColumn(name: String, primaryKeyPosition: Int = 0) =
        ImmersionPortableColumn(name, ImmersionPortableAffinity.BLOB, primaryKeyPosition)

    private fun row(vararg cells: ImmersionPortableCell) = ImmersionPortableRow(cells.toList())

    private fun textCell(value: String) =
        ImmersionPortableCell(ImmersionPortableCellKind.TEXT, textValue = value)

    private fun integerCell(value: Long) =
        ImmersionPortableCell(ImmersionPortableCellKind.INTEGER, integerValue = value)

    private fun blobCell(value: String) = blobCell(value.encodeToByteArray())

    private fun blobCell(value: ByteArray) =
        ImmersionPortableCell(ImmersionPortableCellKind.BLOB, blobValue = value)

    private fun syncService() = TestSyncService()

    private class TestSyncService : SyncService(
        context = mockk<Context>(relaxed = true),
        json = Json { ignoreUnknownKeys = true },
        syncPreferences = mockk<SyncPreferences>().also {
            every { it.uniqueDeviceID() } returns "device"
        },
    ) {
        override suspend fun doSync(syncData: SyncData): Backup? = syncData.backup

        fun merge(local: SyncData, remote: SyncData): SyncData {
            return mergeSyncData(local, remote)
        }
    }
}
