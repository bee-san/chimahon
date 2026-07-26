// SPDX-License-Identifier: MIT

package tachiyomi.data.immersion

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.MangaUpdateStrategyColumnAdapter
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.Reading_sessions
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.domain.immersion.model.AnalyticsBucketInventory
import tachiyomi.domain.immersion.model.AnalyticsInventoryMetrics
import tachiyomi.domain.immersion.model.AnalyticsSort
import tachiyomi.domain.immersion.model.AnkiInventoryFailure
import tachiyomi.domain.immersion.model.AnkiMatchConfidence
import tachiyomi.domain.immersion.model.AnkiOperationEvent
import tachiyomi.domain.immersion.model.AnkiOperationId
import tachiyomi.domain.immersion.model.AnkiOperationStatus
import tachiyomi.domain.immersion.model.AnkiOperationType
import tachiyomi.domain.immersion.model.AnkiSnapshotStatus
import tachiyomi.domain.immersion.model.CapabilityState
import tachiyomi.domain.immersion.model.CharacterVolume
import tachiyomi.domain.immersion.model.EventId
import tachiyomi.domain.immersion.model.EventType
import tachiyomi.domain.immersion.model.ExposureEvent
import tachiyomi.domain.immersion.model.ImmersionAnkiItem
import tachiyomi.domain.immersion.model.ImmersionAnkiSnapshot
import tachiyomi.domain.immersion.model.ImmersionDataException
import tachiyomi.domain.immersion.model.ImmersionLocalDate
import tachiyomi.domain.immersion.model.ImmersionPortableCell
import tachiyomi.domain.immersion.model.ImmersionPortableCellKind
import tachiyomi.domain.immersion.model.ImmersionReindexRequest
import tachiyomi.domain.immersion.model.ImmersionSessionStart
import tachiyomi.domain.immersion.model.ImmersionSourceUnit
import tachiyomi.domain.immersion.model.ImmersionTitle
import tachiyomi.domain.immersion.model.IndexTerminalReason
import tachiyomi.domain.immersion.model.IndexedCharacter
import tachiyomi.domain.immersion.model.IndexedWord
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.LegacyDailyAggregate
import tachiyomi.domain.immersion.model.LegacyImportBatch
import tachiyomi.domain.immersion.model.LegacyImportIdentity
import tachiyomi.domain.immersion.model.LegacyImportResultState
import tachiyomi.domain.immersion.model.LegacyImportSourceKind
import tachiyomi.domain.immersion.model.LocalDateRange
import tachiyomi.domain.immersion.model.LookupEvent
import tachiyomi.domain.immersion.model.LookupStatus
import tachiyomi.domain.immersion.model.MaturityTier
import tachiyomi.domain.immersion.model.MediaKind
import tachiyomi.domain.immersion.model.MillisecondDuration
import tachiyomi.domain.immersion.model.NetCharacterProgress
import tachiyomi.domain.immersion.model.NonNegativeCounter
import tachiyomi.domain.immersion.model.PersistenceErrorCode
import tachiyomi.domain.immersion.model.PersistenceResult
import tachiyomi.domain.immersion.model.SessionEvent
import tachiyomi.domain.immersion.model.SessionId
import tachiyomi.domain.immersion.model.SessionStatus
import tachiyomi.domain.immersion.model.SourceKind
import tachiyomi.domain.immersion.model.SourceUnitId
import tachiyomi.domain.immersion.model.StatsFilter
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.model.UnicodeCodePoint
import tachiyomi.domain.immersion.service.AnkiOperationToken
import tachiyomi.domain.immersion.service.PendingAnkiOperation
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Execution(ExecutionMode.SAME_THREAD)
class SqlDelightImmersionRepositoryTest {

    private val databaseDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var allStatsResetCallbacks = 0
    private val deletedSessionCallbacks = mutableListOf<SessionId>()
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: SqlDelightImmersionRepository

    @BeforeEach
    fun setUp() {
        allStatsResetCallbacks = 0
        deletedSessionCallbacks.clear()
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).value
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).value
        val database = createDatabase(driver)
        repository = SqlDelightImmersionRepository(
            AndroidDatabaseHandler(
                db = database,
                driver = driver,
                queryDispatcher = databaseDispatcher,
                transactionDispatcher = databaseDispatcher,
            ),
            onAllStatsReset = { allStatsResetCallbacks++ },
            onSessionDeleted = deletedSessionCallbacks::add,
        )
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `fresh schema contains every immersion foundation table and metadata row`() {
        val expectedTables = setOf(
            "immersion_title",
            "immersion_session",
            "immersion_source_unit",
            "immersion_source_fts",
            "immersion_event",
            "immersion_source_exposure",
            "immersion_word",
            "immersion_word_occurrence",
            "immersion_character",
            "immersion_character_occurrence",
            "immersion_lookup",
            "immersion_anki_operation",
            "immersion_anki_snapshot",
            "immersion_anki_item",
            "immersion_anki_character",
            "immersion_daily_rollup",
            "immersion_lifetime_rollup",
            "immersion_applied_event",
            "immersion_goal",
            "immersion_goal_check_in",
            "immersion_goal_achievement",
            "immersion_import_ledger",
            "immersion_rollup_state",
            "immersion_rollup_dirty",
            "immersion_sync_peer",
            "immersion_tombstone",
            "immersion_merge_conflict",
            "immersion_exclusion",
            "immersion_retention_state",
        )

        queryStrings(
            """
            SELECT name
            FROM sqlite_master
            WHERE type = 'table'
                AND name LIKE 'immersion_%'
                AND name NOT LIKE 'immersion_source_fts_%'
            """.trimIndent(),
        ).toSet() shouldBe expectedTables
        queryLong("SELECT count(*) FROM immersion_rollup_state WHERE scope_key = 'global'") shouldBe 1
    }

    @Test
    fun `migration chain from 47 is additive and preserves an existing database`() {
        val migrationDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            migrationDriver.execute(null, "CREATE TABLE preexisting_sentinel(value TEXT NOT NULL)", 0).value
            migrationDriver.execute(null, "INSERT INTO preexisting_sentinel VALUES ('kept')", 0).value

            Database.Schema.migrate(
                driver = migrationDriver,
                oldVersion = 47,
                newVersion = Database.Schema.version,
            ).value

            queryStrings(migrationDriver, "SELECT value FROM preexisting_sentinel") shouldContainExactly listOf("kept")
            queryLong(migrationDriver, "SELECT count(*) FROM immersion_rollup_state") shouldBe 1
            queryLong(
                migrationDriver,
                "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'immersion_event'",
            ) shouldBe 1
            queryLong(
                migrationDriver,
                "SELECT count(*) FROM pragma_table_info('immersion_session') WHERE name = 'legacy_cards_total'",
            ) shouldBe 1
            queryLong(
                migrationDriver,
                "SELECT count(*) FROM pragma_table_info('immersion_source_unit') WHERE name = 'ocr_quality'",
            ) shouldBe 1
            queryLong(
                migrationDriver,
                "SELECT count(*) FROM pragma_table_info('immersion_anki_snapshot') WHERE name = 'is_current'",
            ) shouldBe 1
            queryLong(
                migrationDriver,
                "SELECT count(*) FROM pragma_table_info('immersion_anki_item') WHERE name = 'match_confidence'",
            ) shouldBe 1
            queryLong(
                migrationDriver,
                "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'immersion_anki_character'",
            ) shouldBe 1
            queryLong(
                migrationDriver,
                "SELECT count(*) FROM pragma_table_info('immersion_daily_rollup') WHERE name IN ('provenance_state', 'replay_state')",
            ) shouldBe 2
            queryLong(
                migrationDriver,
                "SELECT count(*) FROM pragma_table_info('immersion_lifetime_rollup') WHERE name = 'replay_state'",
            ) shouldBe 1
            queryLong(
                migrationDriver,
                "SELECT count(*) FROM pragma_table_info('immersion_event') WHERE name = 'local_date'",
            ) shouldBe 1
            queryImmersionSchema(migrationDriver) shouldContainExactly queryImmersionSchema(driver)
            assertLegacySessionConstraints(migrationDriver)
        } finally {
            migrationDriver.close()
        }
    }

    @Test
    fun `migration 58 repairs historical successful lookup counts without changing legacy aggregates`() = runTest {
        prepareSession()
        val statuses = listOf(
            LookupStatus.SUCCESS,
            LookupStatus.EMPTY,
            LookupStatus.FAILED,
            LookupStatus.CANCELLED,
        )
        repository.appendEventBatch(
            statuses.mapIndexed { index, status ->
                LookupEvent(
                    id = eventId(900 + index),
                    sessionId = SESSION_ID,
                    sequence = index + 1L,
                    occurredAtEpochMillis = 1_100L + index,
                    timezoneOffsetSeconds = 0,
                    lookupId = "migration-lookup-$status",
                    sourceUnitId = null,
                    queryHash = "migration-query-$status",
                    rawQuery = null,
                    normalizedHeadword = null,
                    normalizedReading = null,
                    partOfSpeech = null,
                    dictionaryId = null,
                    resultId = null,
                    status = status,
                )
            },
        ) shouldContainExactly List(statuses.size) { PersistenceResult.Applied }
        repository.createSession(
            sessionStart(
                id = sessionId(901),
                startedAt = 2_000,
            ),
        ) shouldBe PersistenceResult.Applied
        repository.createSession(
            sessionStart(
                id = sessionId(902),
                startedAt = 3_000,
            ),
        ) shouldBe PersistenceResult.Applied

        driver.execute(
            null,
            "UPDATE immersion_event SET lookup_delta = 1 WHERE type = 'LOOKUP'",
            0,
        ).value
        driver.execute(
            null,
            "UPDATE immersion_session SET lookup_count = 4 WHERE id = '${SESSION_ID.value}'",
            0,
        ).value
        driver.execute(
            null,
            """
            UPDATE immersion_session
            SET
                lookup_count = 7,
                legacy_import = 1,
                legacy_local_date = 0,
                legacy_metric_quality = 'LEGACY_AMBIGUOUS'
            WHERE id = '${sessionId(901).value}'
            """.trimIndent(),
            0,
        ).value
        driver.execute(
            null,
            "UPDATE immersion_session SET lookup_count = 9 WHERE id = '${sessionId(902).value}'",
            0,
        ).value

        Database.Schema.migrate(
            driver = driver,
            oldVersion = 58,
            newVersion = Database.Schema.version,
        ).value

        queryStrings(
            """
            SELECT lookup.status || ':' || event.lookup_delta
            FROM immersion_lookup AS lookup
            JOIN immersion_event AS event ON event.id = lookup.event_id
            ORDER BY lookup.status
            """.trimIndent(),
        ) shouldContainExactly listOf(
            "CANCELLED:0",
            "EMPTY:0",
            "FAILED:0",
            "SUCCESS:1",
        )
        queryLong("SELECT lookup_count FROM immersion_session WHERE id = '${SESSION_ID.value}'") shouldBe 1
        queryLong("SELECT lookup_count FROM immersion_session WHERE id = '${sessionId(901).value}'") shouldBe 7
        queryLong("SELECT lookup_count FROM immersion_session WHERE id = '${sessionId(902).value}'") shouldBe 0
        queryLong(
            "SELECT count(*) FROM immersion_rollup_dirty WHERE reason = 'LOOKUP_SUCCESS_REPAIR'",
        ) shouldBe 1
    }

    @Test
    fun `OCR quality persists with its source unit`() = runTest {
        prepareSession()
        val event = exposure(sequence = 1, eventNumber = 1).let {
            it.copy(
                source = it.source.copy(
                    sourceKind = SourceKind.MANGA_OCR_BLOCK,
                    ocrEngineId = "lens",
                    ocrVersion = 2,
                    ocrQuality = CapabilityState.PARTIAL,
                ),
            )
        }

        repository.appendExposure(event) shouldBe PersistenceResult.Applied

        queryStrings("SELECT ocr_quality FROM immersion_source_unit") shouldContainExactly
            listOf(CapabilityState.PARTIAL.name)
    }

    @Test
    fun `legacy import is atomic replay safe and creates no fabricated detail`() = runTest {
        val batch = legacyBatch()

        repository.importLegacyBatch(batch).state shouldBe LegacyImportResultState.IMPORTED
        repository.importLegacyBatch(batch).state shouldBe LegacyImportResultState.ALREADY_IMPORTED

        queryLong("SELECT count(*) FROM immersion_session WHERE legacy_import = 1") shouldBe 1
        queryLong("SELECT count(*) FROM immersion_import_ledger") shouldBe 1
        queryLong("SELECT count(*) FROM immersion_event") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_source_unit") shouldBe 0
        repository.getLegacyAggregates().single().let { row ->
            row.activeDuration shouldBe MillisecondDuration(90_050)
            row.characters shouldBe NonNegativeCounter(2_000)
            row.cardsTotal shouldBe NonNegativeCounter(4)
            row.recordCount shouldBe NonNegativeCounter(1)
        }
    }

    @Test
    fun `new source hash updates a stable legacy session instead of duplicating it`() = runTest {
        repository.importLegacyBatch(legacyBatch())

        repository.importLegacyBatch(
            legacyBatch(
                contentHash = "b".repeat(64),
                characters = 2_500,
            ),
        ).state shouldBe LegacyImportResultState.IMPORTED

        queryLong("SELECT count(*) FROM immersion_session WHERE legacy_import = 1") shouldBe 1
        queryLong("SELECT gross_characters FROM immersion_session WHERE legacy_import = 1") shouldBe 2_500
        queryLong("SELECT count(*) FROM immersion_import_ledger") shouldBe 2
    }

    @Test
    fun `ledger failure rolls the whole legacy source transaction back`() = runTest {
        driver.execute(
            null,
            """
            CREATE TRIGGER simulate_legacy_ledger_crash
            BEFORE INSERT ON immersion_import_ledger
            BEGIN
                SELECT RAISE(ABORT, 'simulated crash');
            END
            """.trimIndent(),
            0,
        ).value

        runCatching { repository.importLegacyBatch(legacyBatch()) }
            .exceptionOrNull() shouldNotBe null

        queryLong("SELECT count(*) FROM immersion_title") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_session") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_import_ledger") shouldBe 0
    }

    @Test
    fun `partial legacy import persists typed counts with its aggregate`() = runTest {
        val batch = legacyBatch().copy(
            failedCount = NonNegativeCounter(2),
            errorSummary = "INVALID_DATE:2",
        )

        val result = repository.importLegacyBatch(batch)

        result.state shouldBe LegacyImportResultState.PARTIAL
        result.importedCount shouldBe NonNegativeCounter(1)
        result.failedCount shouldBe NonNegativeCounter(2)
        repository.getImportResult(batch.identity)?.let { stored ->
            stored.state shouldBe LegacyImportResultState.PARTIAL
            stored.errorSummary shouldBe "INVALID_DATE:2"
        } shouldNotBe null
    }

    @Test
    fun `session and exposure retries are idempotent`() = runTest {
        prepareSession()
        val event = exposure(sequence = 1, eventNumber = 1)

        repository.createSession(sessionStart()) shouldBe PersistenceResult.AlreadyApplied
        repository.appendExposure(event) shouldBe PersistenceResult.Applied
        repository.appendExposure(event) shouldBe PersistenceResult.AlreadyApplied

        repository.getSession(SESSION_ID)?.let { session ->
            session.lastSequence shouldBe 1
            session.activeDuration shouldBe MillisecondDuration(1_000)
            session.grossCharacters shouldBe NonNegativeCounter(100)
            session.uniqueSourceCharacters shouldBe NonNegativeCounter(90)
            session.sourceUnitCount shouldBe NonNegativeCounter(1)
        } shouldNotBe null
        queryLong("SELECT count(*) FROM immersion_event") shouldBe 1
        queryLong("SELECT count(*) FROM immersion_source_exposure") shouldBe 1
    }

    @Test
    fun `ordered lifecycle batch is atomic idempotent and advances active time and signed net progress`() = runTest {
        prepareSession()
        val batch = listOf(
            SessionEvent(
                id = eventId(1),
                sessionId = SESSION_ID,
                sequence = 1,
                occurredAtEpochMillis = 1_000,
                timezoneOffsetSeconds = 0,
                type = EventType.SESSION_STARTED,
            ),
            SessionEvent(
                id = eventId(2),
                sessionId = SESSION_ID,
                sequence = 2,
                occurredAtEpochMillis = 1_500,
                timezoneOffsetSeconds = 0,
                type = EventType.HEARTBEAT,
                activeDuration = MillisecondDuration(500),
            ),
            SessionEvent(
                id = eventId(3),
                sessionId = SESSION_ID,
                sequence = 3,
                occurredAtEpochMillis = 1_500,
                timezoneOffsetSeconds = 0,
                type = EventType.PAUSED,
            ),
            SessionEvent(
                id = eventId(4),
                sessionId = SESSION_ID,
                sequence = 4,
                occurredAtEpochMillis = 1_600,
                timezoneOffsetSeconds = 0,
                type = EventType.PROGRESS,
                netCharacters = NetCharacterProgress(-25),
            ),
        )

        repository.appendEventBatch(batch) shouldContainExactly List(4) { PersistenceResult.Applied }
        repository.appendEventBatch(batch) shouldContainExactly List(4) { PersistenceResult.AlreadyApplied }

        repository.getSession(SESSION_ID)?.let { session ->
            session.lastSequence shouldBe 4
            session.activeDuration shouldBe MillisecondDuration(500)
            session.grossCharacters shouldBe NonNegativeCounter.ZERO
            session.netCharacters shouldBe NetCharacterProgress(-25)
            session.lastHeartbeatAtEpochMillis shouldBe 1_600
        } shouldNotBe null
        queryLong("SELECT count(*) FROM immersion_event") shouldBe 4
        queryLong("SELECT count(*) FROM immersion_source_exposure") shouldBe 0
    }

    @Test
    fun `event identity conflict is typed and leaves totals unchanged`() = runTest {
        prepareSession()
        repository.appendExposure(exposure(sequence = 1, eventNumber = 1)) shouldBe PersistenceResult.Applied

        val error = runCatching {
            repository.appendExposure(
                exposure(sequence = 1, eventNumber = 1).copy(
                    grossCharacters = NonNegativeCounter(999),
                ),
            )
        }.exceptionOrNull()

        error shouldNotBe null
        (error as ImmersionDataException).code shouldBe PersistenceErrorCode.IDENTITY_CONFLICT
        repository.getSession(SESSION_ID)?.grossCharacters shouldBe NonNegativeCounter(100)
        queryLong("SELECT count(*) FROM immersion_event") shouldBe 1
    }

    @Test
    fun `exposure append rolls back source event and counters after a simulated crash`() = runTest {
        prepareSession()
        driver.execute(
            null,
            """
            CREATE TRIGGER simulate_exposure_crash
            BEFORE INSERT ON immersion_source_exposure
            BEGIN
                SELECT RAISE(ABORT, 'simulated crash');
            END
            """.trimIndent(),
            0,
        ).value

        runCatching { repository.appendExposure(exposure(sequence = 1, eventNumber = 1)) }
            .exceptionOrNull() shouldNotBe null

        queryLong("SELECT count(*) FROM immersion_source_unit") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_event") shouldBe 0
        repository.getSession(SESSION_ID)?.let { session ->
            session.lastSequence shouldBe 0
            session.grossCharacters shouldBe NonNegativeCounter.ZERO
        } shouldNotBe null
    }

    @Test
    fun `session deletion cascades events exposures and unreferenced private source`() = runTest {
        prepareSession()
        repository.appendExposure(exposure(sequence = 1, eventNumber = 1))

        repository.deleteSession(SESSION_ID) shouldBe true
        deletedSessionCallbacks shouldContainExactly listOf(SESSION_ID)

        queryLong("SELECT count(*) FROM immersion_session") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_event") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_source_exposure") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_source_unit") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_tombstone WHERE entity_type = 'SESSION'") shouldBe 1
        queryLong("SELECT count(*) FROM immersion_tombstone WHERE entity_type = 'SOURCE_UNIT'") shouldBe 1
    }

    @Test
    fun `session deletion promotes the next exposure for a shared indexed source`() = runTest {
        val firstAt = 1_100L
        val secondAt = 2_100L
        val secondSession = sessionId(2)
        repository.upsertTitle(title()) shouldBe PersistenceResult.Applied
        repository.createSession(sessionStart(startedAt = 1_000)) shouldBe PersistenceResult.Applied
        repository.createSession(
            sessionStart(id = secondSession, startedAt = 2_000),
        ) shouldBe PersistenceResult.Applied
        repository.appendExposure(
            exposure(sequence = 1, eventNumber = 11).copy(
                occurredAtEpochMillis = firstAt,
                source = source(firstAt).copy(firstExposedAtEpochMillis = firstAt),
            ),
        ) shouldBe PersistenceResult.Applied
        repository.appendExposure(
            exposure(sequence = 1, eventNumber = 12).copy(
                sessionId = secondSession,
                occurredAtEpochMillis = secondAt,
                source = source(secondAt),
            ),
        ) shouldBe PersistenceResult.Applied
        repository.storeIndexResult(
            sourceUnitId = SOURCE_ID,
            tokenizerId = "test",
            tokenizerVersion = 1,
            normalizationVersion = 1,
            indexedVersion = 1,
            indexedAtEpochMillis = 2_200,
            tokenizationConfidence = 1.0,
            terminalReason = null,
            words = listOf(indexedWord("word-shared", "猫", ordinal = 0)),
            characters = listOf(indexedCharacter('猫', 1)),
        )
        queryLong("SELECT first_seen_at FROM immersion_word WHERE id = 'word-shared'") shouldBe firstAt
        queryLong(
            "SELECT first_seen_at FROM immersion_character WHERE code_point = ${'猫'.code}",
        ) shouldBe firstAt

        repository.deleteSession(SESSION_ID) shouldBe true

        queryLong("SELECT count(*) FROM immersion_source_unit") shouldBe 1
        queryLong("SELECT first_exposed_at FROM immersion_source_unit") shouldBe secondAt
        queryLong("SELECT last_exposed_at FROM immersion_source_unit") shouldBe secondAt
        queryLong("SELECT first_seen_at FROM immersion_word WHERE id = 'word-shared'") shouldBe secondAt
        queryLong(
            "SELECT first_seen_at FROM immersion_character WHERE code_point = ${'猫'.code}",
        ) shouldBe secondAt
        repository.inventoryMetrics(StatsFilter()).let {
            it.newWords shouldBe 1
            it.newCharacters shouldBe 1
        }
    }

    @Test
    fun `session deletion preserves pre-event first time when only the latest exposure is removed`() = runTest {
        val firstEventAt = 1_100L
        val secondEventAt = 2_100L
        val secondSession = sessionId(3)
        repository.upsertTitle(title()) shouldBe PersistenceResult.Applied
        repository.createSession(sessionStart(startedAt = 1_000)) shouldBe PersistenceResult.Applied
        repository.createSession(
            sessionStart(id = secondSession, startedAt = 2_000),
        ) shouldBe PersistenceResult.Applied
        repository.appendExposure(
            exposure(sequence = 1, eventNumber = 13).copy(
                occurredAtEpochMillis = firstEventAt,
                source = source(firstEventAt),
            ),
        ) shouldBe PersistenceResult.Applied
        repository.appendExposure(
            exposure(sequence = 1, eventNumber = 14).copy(
                sessionId = secondSession,
                occurredAtEpochMillis = secondEventAt,
                source = source(secondEventAt),
            ),
        ) shouldBe PersistenceResult.Applied

        repository.deleteSession(secondSession) shouldBe true

        queryLong("SELECT first_exposed_at FROM immersion_source_unit") shouldBe 1_000L
        queryLong("SELECT last_exposed_at FROM immersion_source_unit") shouldBe firstEventAt
    }

    @Test
    fun `title deletion is restricted while a session exists`() = runTest {
        prepareSession()

        runCatching {
            driver.execute(
                null,
                "DELETE FROM immersion_title WHERE id = '${TITLE_ID.value}'",
                0,
            ).value
        }.exceptionOrNull() shouldNotBe null

        queryLong("SELECT count(*) FROM immersion_title") shouldBe 1
    }

    @Test
    fun `pagination remains stable when every session has the same timestamp`() = runTest {
        repository.upsertTitle(title())
        val ids = (1..5).map { number -> sessionId(number) }
        ids.forEach { id ->
            repository.createSession(sessionStart(id = id, startedAt = 10_000))
        }

        val first = repository.sessionsPage(limit = 2)
        val second = repository.sessionsPage(cursor = first.nextCursor, limit = 2)
        val third = repository.sessionsPage(cursor = second.nextCursor, limit = 2)

        val expected = ids.sortedByDescending { it.value }
        (first.items + second.items + third.items).map { it.id } shouldContainExactly expected
        third.nextCursor shouldBe null
    }

    @Test
    fun `abandoned recovery only finalizes sessions before the heartbeat cutoff`() = runTest {
        repository.upsertTitle(title())
        val stale = sessionId(1)
        val current = sessionId(2)
        repository.createSession(sessionStart(id = stale, startedAt = 100))
        repository.createSession(sessionStart(id = current, startedAt = 1_000))

        repository.recoverAbandonedSessions(500) shouldBe 1

        repository.getSession(stale)?.let { session ->
            session.status shouldBe SessionStatus.ABANDONED
            session.endedAtEpochMillis shouldBe 100
            session.elapsedDuration shouldBe MillisecondDuration(0)
        }
        repository.getSession(current)?.status shouldBe SessionStatus.ACTIVE
    }

    @Test
    fun `abandoned recovery ends at the last persisted active boundary`() = runTest {
        prepareSession()
        repository.appendEventBatch(
            listOf(
                SessionEvent(
                    id = eventId(1),
                    sessionId = SESSION_ID,
                    sequence = 1,
                    occurredAtEpochMillis = 4_000,
                    timezoneOffsetSeconds = 0,
                    type = EventType.HEARTBEAT,
                    activeDuration = MillisecondDuration(1_000),
                ),
            ),
        )

        repository.recoverAbandonedSessions(5_000) shouldBe 1
        repository.getSession(SESSION_ID)?.let { session ->
            session.status shouldBe SessionStatus.ABANDONED
            session.endedAtEpochMillis shouldBe 4_000
            session.elapsedDuration shouldBe MillisecondDuration(3_000)
            session.activeDuration shouldBe MillisecondDuration(1_000)
        } shouldNotBe null
    }

    @Test
    fun `finalization is idempotent`() = runTest {
        prepareSession()

        repository.finalizeSession(
            sessionId = SESSION_ID,
            status = SessionStatus.COMPLETED,
            endedAtEpochMillis = 2_000,
            elapsedDuration = MillisecondDuration(1_000),
        ) shouldBe PersistenceResult.Applied
        repository.finalizeSession(
            sessionId = SESSION_ID,
            status = SessionStatus.COMPLETED,
            endedAtEpochMillis = 2_000,
            elapsedDuration = MillisecondDuration(1_000),
        ) shouldBe PersistenceResult.AlreadyApplied
    }

    @Test
    fun `concurrent reads never observe a partially applied batch`() = runTest {
        prepareSession()
        val batch = listOf(
            exposure(sequence = 1, eventNumber = 1),
            exposure(sequence = 2, eventNumber = 2),
        )

        val observedSequences = coroutineScope {
            val write = async(Dispatchers.IO) { repository.appendExposureBatch(batch) }
            val reads = List(32) {
                async(Dispatchers.IO) { repository.getSession(SESSION_ID)?.lastSequence ?: -1 }
            }
            write.await() shouldContainExactly listOf(PersistenceResult.Applied, PersistenceResult.Applied)
            reads.awaitAll()
        }

        observedSequences.filterNot { it == 0L || it == 2L } shouldBe emptyList()
        repository.getSession(SESSION_ID)?.lastSequence shouldBe 2
    }

    @Test
    fun `invalid persisted enum is reported as typed corruption`() = runTest {
        prepareSession()
        driver.execute(
            null,
            "UPDATE immersion_session SET media_kind = 'BROKEN' WHERE id = '${SESSION_ID.value}'",
            0,
        ).value

        val error = runCatching { repository.getSession(SESSION_ID) }.exceptionOrNull()

        error shouldNotBe null
        (error as ImmersionDataException).code shouldBe PersistenceErrorCode.CORRUPT_VALUE
    }

    @Test
    fun `lookup and Anki interactions advance one sequence and only successful writes affect counters`() = runTest {
        prepareSession()
        val lookup = LookupEvent(
            id = eventId(71),
            sessionId = SESSION_ID,
            sequence = 1,
            occurredAtEpochMillis = 1_100,
            timezoneOffsetSeconds = 0,
            lookupId = UUID.randomUUID().toString(),
            sourceUnitId = null,
            queryHash = "query-hash",
            rawQuery = null,
            normalizedHeadword = "読む",
            normalizedReading = "よむ",
            partOfSpeech = "verb",
            dictionaryId = "test-dictionary",
            resultId = "result-1",
            status = LookupStatus.SUCCESS,
        )
        val operation = AnkiOperationEvent(
            id = eventId(72),
            sessionId = SESSION_ID,
            sequence = 2,
            occurredAtEpochMillis = 1_200,
            timezoneOffsetSeconds = 0,
            operationId = AnkiOperationId(UUID.randomUUID().toString()),
            sourceUnitId = null,
            expressionHash = "expression-hash",
            normalizedExpression = "読む",
            normalizedReading = "よむ",
            operationType = AnkiOperationType.UPDATE,
            status = AnkiOperationStatus.SUCCESS,
            noteId = 42,
        )

        repository.appendEventBatch(listOf(lookup, operation)) shouldContainExactly listOf(
            PersistenceResult.Applied,
            PersistenceResult.Applied,
        )
        repository.appendEventBatch(listOf(lookup, operation)) shouldContainExactly listOf(
            PersistenceResult.AlreadyApplied,
            PersistenceResult.AlreadyApplied,
        )

        queryLong("SELECT lookup_count FROM immersion_session") shouldBe 1
        queryLong("SELECT cards_created FROM immersion_session") shouldBe 0
        queryLong("SELECT cards_updated FROM immersion_session") shouldBe 1
        queryLong("SELECT count(*) FROM immersion_lookup WHERE raw_query IS NULL AND query_hash = 'query-hash'") shouldBe 1
        queryLong("SELECT count(*) FROM immersion_anki_operation WHERE note_id = 42 AND status = 'SUCCESS'") shouldBe 1
        repository.getSession(SESSION_ID)?.lastSequence shouldBe 2
    }

    @Test
    fun `only successful lookup status increments aggregates while every status remains persisted`() = runTest {
        prepareSession()
        val statuses = listOf(
            LookupStatus.SUCCESS,
            LookupStatus.EMPTY,
            LookupStatus.FAILED,
            LookupStatus.CANCELLED,
        )
        val lookups = statuses.mapIndexed { index, status ->
            val sequence = index + 1L
            LookupEvent(
                id = eventId(73 + index),
                sessionId = SESSION_ID,
                sequence = sequence,
                occurredAtEpochMillis = 1_000 + sequence * 100,
                timezoneOffsetSeconds = 0,
                lookupId = UUID.randomUUID().toString(),
                sourceUnitId = null,
                queryHash = "query-$status",
                rawQuery = null,
                normalizedHeadword = "読む",
                normalizedReading = "よむ",
                partOfSpeech = "verb",
                dictionaryId = "test-dictionary",
                resultId = if (status == LookupStatus.SUCCESS) "result-success" else null,
                status = status,
            )
        }

        repository.appendEventBatch(lookups) shouldContainExactly List(statuses.size) {
            PersistenceResult.Applied
        }
        repository.appendEventBatch(lookups) shouldContainExactly List(statuses.size) {
            PersistenceResult.AlreadyApplied
        }

        queryLong("SELECT count(*) FROM immersion_lookup") shouldBe statuses.size.toLong()
        queryStrings("SELECT status FROM immersion_lookup ORDER BY status") shouldContainExactly
            statuses.map { it.name }.sorted()
        queryLong("SELECT count(*) FROM immersion_event WHERE type = 'LOOKUP'") shouldBe statuses.size.toLong()
        queryStrings(
            """
            SELECT lookup.status || ':' || event.lookup_delta
            FROM immersion_lookup AS lookup
            JOIN immersion_event AS event ON event.id = lookup.event_id
            ORDER BY lookup.status
            """.trimIndent(),
        ) shouldContainExactly listOf(
            "CANCELLED:0",
            "EMPTY:0",
            "FAILED:0",
            "SUCCESS:1",
        )
        queryLong("SELECT sum(lookup_delta) FROM immersion_event") shouldBe 1
        queryLong("SELECT lookup_count FROM immersion_session") shouldBe 1
        repository.getSession(SESSION_ID)?.lastSequence shouldBe statuses.size.toLong()

        repository.finalizeSession(
            sessionId = SESSION_ID,
            status = SessionStatus.COMPLETED,
            endedAtEpochMillis = 2_000,
            elapsedDuration = MillisecondDuration(1_000),
        )
        repository.overview().lookups shouldBe NonNegativeCounter(1)

        val date = ImmersionLocalDate.parse("1970-01-01")
        val range = LocalDateRange(date, date)
        repeat(2) {
            repository.rebuildRollups(
                range = range,
                rollupVersion = 2,
                nowEpochMillis = 3_000L + it,
            ).let { result ->
                result.eventCount shouldBe statuses.size.toLong()
                result.sessionCount shouldBe 1
                result.rowCount shouldBe 1
            }
            repository.dailyRollups(range).single().metrics.successfulLookups shouldBe NonNegativeCounter(1)
            queryLong("SELECT sum(lookups) FROM immersion_daily_rollup") shouldBe 1
        }
    }

    @Test
    fun `externally successful Anki operation can be repaired without fabricating a session event`() = runTest {
        val operationId = AnkiOperationId(UUID.randomUUID().toString())
        val pending = PendingAnkiOperation(
            token = AnkiOperationToken(
                operationId = operationId,
                sessionId = SESSION_ID,
                sourceUnitId = SOURCE_ID,
                expressionHash = "expression-hash",
                normalizedExpression = "読む",
                normalizedReading = "よむ",
            ),
            operationType = AnkiOperationType.CREATE,
            status = AnkiOperationStatus.SUCCESS,
            noteId = 99,
            errorCode = null,
        )

        repository.storeUnlinkedAnkiOperation(pending, occurredAtEpochMillis = 2_000) shouldBe true
        repository.storeUnlinkedAnkiOperation(pending, occurredAtEpochMillis = 3_000) shouldBe true

        queryLong("SELECT count(*) FROM immersion_anki_operation") shouldBe 1
        queryLong("SELECT count(*) FROM immersion_anki_operation WHERE event_id IS NULL AND session_id IS NULL") shouldBe 1
        queryLong("SELECT count(*) FROM immersion_event") shouldBe 0
        repository.exportPortableArchive(
            includeRawText = false,
            createdAtEpochMillis = 4_000,
        ).tables.single { it.name == "immersion_anki_operation" }.rows shouldBe emptyList()
    }

    @Test
    fun `portable merge discards legacy unlinked Anki operation rows`() = runTest {
        prepareSession()
        repository.appendEventBatch(
            listOf(
                AnkiOperationEvent(
                    id = eventId(94),
                    sessionId = SESSION_ID,
                    sequence = 1,
                    occurredAtEpochMillis = 1_100,
                    timezoneOffsetSeconds = 0,
                    operationId = AnkiOperationId(UUID.randomUUID().toString()),
                    sourceUnitId = null,
                    expressionHash = "portable-anki-expression",
                    normalizedExpression = "読む",
                    normalizedReading = "よむ",
                    operationType = AnkiOperationType.CREATE,
                    status = AnkiOperationStatus.SUCCESS,
                    noteId = 99,
                ),
            ),
        ) shouldContainExactly listOf(PersistenceResult.Applied)
        val linkedArchive = repository.exportPortableArchive(
            includeRawText = false,
            createdAtEpochMillis = 2_000,
        )
        val linkedTable = linkedArchive.tables.single { it.name == "immersion_anki_operation" }
        val legacyCells = linkedTable.rows.single().cells.toMutableList()
        listOf("event_id", "session_id", "source_unit_id", "word_id").forEach { columnName ->
            val index = linkedTable.columns.indexOfFirst { it.name == columnName }
            legacyCells[index] = ImmersionPortableCell(ImmersionPortableCellKind.NULL)
        }
        val legacyArchive = linkedArchive.copy(
            tables = listOf(
                linkedTable.copy(
                    rows = listOf(linkedTable.rows.single().copy(cells = legacyCells)),
                ),
            ),
        )

        val targetDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(targetDriver).value
            targetDriver.execute(null, "PRAGMA foreign_keys = ON", 0).value
            val target = SqlDelightImmersionRepository(
                AndroidDatabaseHandler(
                    createDatabase(targetDriver),
                    targetDriver,
                    databaseDispatcher,
                    databaseDispatcher,
                ),
            )

            target.mergePortableArchive(legacyArchive, 3_000).insertedRows shouldBe 0
            queryLong(targetDriver, "SELECT count(*) FROM immersion_anki_operation") shouldBe 0
        } finally {
            targetDriver.close()
        }
    }

    @Test
    fun `Anki repair preserves operation session and source identity and advances counters once`() = runTest {
        prepareSession()
        repository.appendExposure(exposure(sequence = 1, eventNumber = 95)) shouldBe PersistenceResult.Applied
        val operationId = AnkiOperationId(UUID.randomUUID().toString())
        val pending = PendingAnkiOperation(
            token = AnkiOperationToken(
                operationId = operationId,
                sessionId = SESSION_ID,
                sourceUnitId = SOURCE_ID,
                expressionHash = "expression-hash",
                normalizedExpression = "読む",
                normalizedReading = "よむ",
                occurredAtEpochMillis = 1_500,
                timezoneOffsetSeconds = 0,
            ),
            operationType = AnkiOperationType.CREATE,
            status = AnkiOperationStatus.SUCCESS,
            noteId = 99,
            errorCode = null,
        )

        repository.repairAnkiOperation(pending, repairedAtEpochMillis = 2_000) shouldBe true
        repository.repairAnkiOperation(pending, repairedAtEpochMillis = 3_000) shouldBe true

        queryLong("SELECT count(*) FROM immersion_anki_operation WHERE id = '${operationId.value}'") shouldBe 1
        queryStrings(
            """
            SELECT session_id || ':' || source_unit_id
            FROM immersion_anki_operation
            WHERE id = '${operationId.value}'
            """.trimIndent(),
        ) shouldContainExactly listOf("${SESSION_ID.value}:${SOURCE_ID.value}")
        queryLong(
            "SELECT count(*) FROM immersion_anki_operation WHERE event_id IS NOT NULL",
        ) shouldBe 1
        queryLong(
            "SELECT count(*) FROM immersion_event WHERE type = 'ANKI_OPERATION' AND cards_created_delta = 1",
        ) shouldBe 1
        queryLong("SELECT cards_created FROM immersion_session WHERE id = '${SESSION_ID.value}'") shouldBe 1
        queryLong("SELECT last_sequence FROM immersion_session WHERE id = '${SESSION_ID.value}'") shouldBe 2

        repository.finalizeSession(
            sessionId = SESSION_ID,
            status = SessionStatus.COMPLETED,
            endedAtEpochMillis = 3_500,
            elapsedDuration = MillisecondDuration(2_500),
        )
        repository.overview().cardsCreated shouldBe NonNegativeCounter(1)
        val date = ImmersionLocalDate.parse("1970-01-01")
        repository.rebuildRollups(
            range = LocalDateRange(date, date),
            rollupVersion = 2,
            nowEpochMillis = 4_000,
        )
        queryLong("SELECT sum(cards_created) FROM immersion_daily_rollup") shouldBe 1
    }

    @Test
    fun `legacy pending Anki repair derives local time from the repair zone`() = runTest {
        prepareSession()
        val operationId = AnkiOperationId(UUID.randomUUID().toString())
        val repairedAt = Instant.parse("2026-07-01T00:30:00Z")
        val repairZone = ZoneId.of("America/Los_Angeles")
        val pending = PendingAnkiOperation(
            token = AnkiOperationToken(
                operationId = operationId,
                sessionId = SESSION_ID,
                sourceUnitId = null,
                expressionHash = "legacy-expression-hash",
                normalizedExpression = "読む",
                normalizedReading = "よむ",
            ),
            operationType = AnkiOperationType.CREATE,
            status = AnkiOperationStatus.SUCCESS,
            noteId = 100,
            errorCode = null,
        )

        repository.repairAnkiOperation(
            operation = pending,
            repairedAtEpochMillis = repairedAt.toEpochMilli(),
            repairZoneId = repairZone,
        ) shouldBe true

        queryLong(
            "SELECT occurred_at FROM immersion_event WHERE anki_operation_id = '${operationId.value}'",
        ) shouldBe repairedAt.toEpochMilli()
        queryLong(
            "SELECT timezone_offset_seconds FROM immersion_event WHERE anki_operation_id = '${operationId.value}'",
        ) shouldBe repairZone.rules.getOffset(repairedAt).totalSeconds.toLong()
        queryLong(
            "SELECT local_date FROM immersion_event WHERE anki_operation_id = '${operationId.value}'",
        ) shouldBe repairedAt.atZone(repairZone).toLocalDate().toEpochDay()
    }

    @Test
    fun `distinct successful updates to one Anki note remain independently idempotent`() = runTest {
        prepareSession()
        val operations = List(2) { index ->
            PendingAnkiOperation(
                token = AnkiOperationToken(
                    operationId = AnkiOperationId(UUID.randomUUID().toString()),
                    sessionId = SESSION_ID,
                    sourceUnitId = null,
                    expressionHash = "update-expression-hash-$index",
                    normalizedExpression = "読む",
                    normalizedReading = "よむ",
                    occurredAtEpochMillis = 1_100L + index,
                    timezoneOffsetSeconds = 0,
                ),
                operationType = AnkiOperationType.UPDATE,
                status = AnkiOperationStatus.SUCCESS,
                noteId = 99,
                errorCode = null,
            )
        }

        operations.forEachIndexed { index, operation ->
            repository.repairAnkiOperation(
                operation = operation,
                repairedAtEpochMillis = 2_000L + index,
            ) shouldBe true
        }
        operations.forEachIndexed { index, operation ->
            repository.repairAnkiOperation(
                operation = operation,
                repairedAtEpochMillis = 3_000L + index,
            ) shouldBe true
        }

        queryLong(
            "SELECT count(*) FROM immersion_anki_operation WHERE note_id = 99 AND type = 'UPDATE'",
        ) shouldBe 2
        queryLong(
            "SELECT count(*) FROM immersion_event WHERE type = 'ANKI_OPERATION' AND cards_updated_delta = 1",
        ) shouldBe 2
        queryLong("SELECT cards_updated FROM immersion_session WHERE id = '${SESSION_ID.value}'") shouldBe 2
        queryLong("SELECT last_sequence FROM immersion_session WHERE id = '${SESSION_ID.value}'") shouldBe 2
    }

    @Test
    fun `index claim retry schedule and terminal requeue are deterministic`() = runTest {
        prepareSession()
        repository.appendExposure(
            exposure(sequence = 1, eventNumber = 81).let {
                it.copy(source = it.source.copy(rawText = "猫"))
            },
        )

        val first = repository.claimWork(targetVersion = 1, limit = 10, nowEpochMillis = 1_000).single()
        first.languageTag shouldBe LanguageTag("ja")
        first.attemptCount shouldBe 0
        repository.markFailure(first.sourceUnitId, "TOKENIZER_FAILURE", nextAttemptAtEpochMillis = 5_000)
        repository.claimWork(targetVersion = 1, limit = 10, nowEpochMillis = 4_999) shouldBe emptyList()
        repository.claimWork(targetVersion = 1, limit = 10, nowEpochMillis = 5_000).single().attemptCount shouldBe 1

        repository.storeIndexResult(
            sourceUnitId = first.sourceUnitId,
            tokenizerId = "characters-only",
            tokenizerVersion = 1,
            normalizationVersion = 1,
            indexedVersion = 1,
            indexedAtEpochMillis = 5_000,
            tokenizationConfidence = null,
            terminalReason = IndexTerminalReason.UNSUPPORTED_LANGUAGE,
            words = emptyList(),
            characters = listOf(indexedCharacter('猫', 1)),
        )
        queryLong("SELECT count(*) FROM immersion_source_unit WHERE indexing_status = 'UNAVAILABLE'") shouldBe 1
        repository.pendingCount(1) shouldBe 0

        repository.requeue(
            ImmersionReindexRequest(languageTag = LanguageTag("ja"), titleId = TITLE_ID),
            targetVersion = 1,
        ) shouldBe 1
        repository.pendingCount(1) shouldBe 1
    }

    @Test
    fun `reindex replaces split merge occurrences preserves first seen and cleans validated orphans`() = runTest {
        prepareSession()
        repository.appendExposure(
            exposure(sequence = 1, eventNumber = 82).let {
                it.copy(
                    source = it.source.copy(
                        rawText = "猫を見る",
                        firstExposedAtEpochMillis = 1_000,
                        lastExposedAtEpochMillis = 1_100,
                    ),
                )
            },
        )
        val sourceId = SOURCE_ID
        repository.storeIndexResult(
            sourceUnitId = sourceId,
            tokenizerId = "test-v1",
            tokenizerVersion = 1,
            normalizationVersion = 1,
            indexedVersion = 1,
            indexedAtEpochMillis = 2_000,
            tokenizationConfidence = 0.9,
            terminalReason = null,
            words = listOf(indexedWord("word-cat", "猫", ordinal = 0)),
            characters = listOf(indexedCharacter('猫', 1)),
        )

        repository.requeue(ImmersionReindexRequest(titleId = TITLE_ID), targetVersion = 2) shouldBe 1
        repository.claimWork(targetVersion = 2, limit = 10, nowEpochMillis = 3_000).single().sourceUnitId shouldBe sourceId
        repository.storeIndexResult(
            sourceUnitId = sourceId,
            tokenizerId = "test-v2",
            tokenizerVersion = 2,
            normalizationVersion = 1,
            indexedVersion = 2,
            indexedAtEpochMillis = 3_000,
            tokenizationConfidence = 0.95,
            terminalReason = null,
            words = listOf(
                indexedWord("word-see", "見る", "みる", ordinal = 0),
                indexedWord("word-cat-see", "猫を見る", "ねこをみる", ordinal = 1),
            ),
            characters = listOf(
                indexedCharacter('猫', 1),
                indexedCharacter('見', 1, firstOrdinal = 1),
            ),
        )

        queryLong("SELECT count(*) FROM immersion_word WHERE id = 'word-cat'") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_word_occurrence") shouldBe 2
        queryLong("SELECT first_seen_at FROM immersion_word WHERE id = 'word-see'") shouldBe 1_000
        queryLong("SELECT count(*) FROM immersion_character WHERE rendered = '猫'") shouldBe 1
        queryLong("SELECT count(*) FROM immersion_character WHERE rendered = '見'") shouldBe 1
        queryStrings("SELECT tokenizer_id FROM immersion_source_unit") shouldContainExactly listOf("test-v2")
        queryLong("SELECT countable_characters FROM immersion_source_unit") shouldBe 2
        queryLong("SELECT han_characters FROM immersion_source_unit") shouldBe 2
    }

    @Test
    fun `reindex only recomputes inventory identities attached to its source`() = runTest {
        prepareSession()
        val unrelatedSourceId = SourceUnitId("00000000-0000-0000-0000-000000000102")
        repository.appendExposure(exposure(sequence = 1, eventNumber = 821)) shouldBe PersistenceResult.Applied
        repository.appendExposure(
            exposure(sequence = 2, eventNumber = 822).let {
                it.copy(
                    source = it.source.copy(
                        id = unrelatedSourceId,
                        canonicalLocator = "novel:test:chapter-2:0-100",
                        chapterOrSectionId = "chapter-2",
                    ),
                )
            },
        ) shouldBe PersistenceResult.Applied
        repository.storeIndexResult(
            sourceUnitId = SOURCE_ID,
            tokenizerId = "test-v1",
            tokenizerVersion = 1,
            normalizationVersion = 1,
            indexedVersion = 1,
            indexedAtEpochMillis = 2_000,
            tokenizationConfidence = 1.0,
            terminalReason = null,
            words = listOf(indexedWord("word-cat", "猫", ordinal = 0)),
            characters = listOf(indexedCharacter('猫', 1)),
        )
        repository.storeIndexResult(
            sourceUnitId = unrelatedSourceId,
            tokenizerId = "test-v1",
            tokenizerVersion = 1,
            normalizationVersion = 1,
            indexedVersion = 1,
            indexedAtEpochMillis = 2_000,
            tokenizationConfidence = 1.0,
            terminalReason = null,
            words = listOf(indexedWord("word-dog", "犬", ordinal = 0)),
            characters = listOf(indexedCharacter('犬', 1)),
        )
        driver.execute(
            null,
            """
            CREATE TRIGGER reject_unrelated_word_recompute
            BEFORE UPDATE ON immersion_word
            WHEN OLD.id = 'word-dog'
            BEGIN
                SELECT RAISE(ABORT, 'unrelated word was recomputed');
            END
            """.trimIndent(),
            0,
        ).value
        driver.execute(
            null,
            """
            CREATE TRIGGER reject_unrelated_character_recompute
            BEFORE UPDATE ON immersion_character
            WHEN OLD.code_point = ${'犬'.code}
            BEGIN
                SELECT RAISE(ABORT, 'unrelated character was recomputed');
            END
            """.trimIndent(),
            0,
        ).value

        repository.storeIndexResult(
            sourceUnitId = SOURCE_ID,
            tokenizerId = "test-v2",
            tokenizerVersion = 2,
            normalizationVersion = 1,
            indexedVersion = 2,
            indexedAtEpochMillis = 3_000,
            tokenizationConfidence = 1.0,
            terminalReason = null,
            words = listOf(indexedWord("word-see", "見る", "みる", ordinal = 0)),
            characters = listOf(indexedCharacter('見', 1)),
        )

        queryStrings("SELECT id FROM immersion_word ORDER BY id") shouldContainExactly
            listOf("word-dog", "word-see")
        queryStrings("SELECT rendered FROM immersion_character ORDER BY rendered") shouldContainExactly
            listOf("犬", "見")
    }

    @Test
    fun `identical text at different locators keeps distinct source provenance`() = runTest {
        prepareSession()
        val secondSourceId = SourceUnitId("00000000-0000-0000-0000-000000000102")
        repository.appendExposure(
            exposure(sequence = 1, eventNumber = 83).let {
                it.copy(source = it.source.copy(rawText = "猫"))
            },
        )
        repository.appendExposure(
            exposure(sequence = 2, eventNumber = 84).let {
                it.copy(
                    source = it.source.copy(
                        id = secondSourceId,
                        canonicalLocator = "novel:test:chapter-2:0-100",
                        chapterOrSectionId = "chapter-2",
                        rawText = "猫",
                    ),
                )
            },
        )

        listOf(SOURCE_ID, secondSourceId).forEach { sourceId ->
            repository.storeIndexResult(
                sourceUnitId = sourceId,
                tokenizerId = "test-v1",
                tokenizerVersion = 1,
                normalizationVersion = 1,
                indexedVersion = 1,
                indexedAtEpochMillis = 2_000,
                tokenizationConfidence = 1.0,
                terminalReason = null,
                words = listOf(indexedWord("word-cat", "猫", ordinal = 0)),
                characters = listOf(indexedCharacter('猫', 1)),
            )
        }

        queryLong("SELECT count(*) FROM immersion_source_unit WHERE normalized_text_hash = 'sha256:test'") shouldBe 2
        queryLong("SELECT count(*) FROM immersion_word_occurrence WHERE word_id = 'word-cat'") shouldBe 2
        queryLong("SELECT count(DISTINCT source_unit_id) FROM immersion_word_occurrence") shouldBe 2
    }

    @Test
    fun `Anki snapshots activate atomically retain failed cache and recompute maturity locally`() = runTest {
        repository.upsertTitle(title().copy(profileId = "profile")) shouldBe PersistenceResult.Applied
        repository.createSession(sessionStart().copy(profileId = "profile")) shouldBe PersistenceResult.Applied
        repository.appendExposure(
            exposure(sequence = 1, eventNumber = 85).let {
                it.copy(source = it.source.copy(rawText = "猫語"))
            },
        )
        repository.storeIndexResult(
            sourceUnitId = SOURCE_ID,
            tokenizerId = "test",
            tokenizerVersion = 1,
            normalizationVersion = 1,
            indexedVersion = 1,
            indexedAtEpochMillis = 1_000,
            tokenizationConfidence = 1.0,
            terminalReason = null,
            words = listOf(indexedWord("word-cat-language", "猫語", "ねこご", 0)),
            characters = listOf(indexedCharacter('猫', 1), indexedCharacter('語', 1, 1)),
        )
        repository.activateSnapshot(
            ankiSnapshot("snapshot-1", requestedAt = 1_000),
            listOf(ankiItem("snapshot-1", intervalDays = 10, tier = MaturityTier.YOUNG)),
        )
        repository.getCurrentSnapshot("profile")?.id shouldBe "snapshot-1"
        repository.getCurrentItems("profile").single().characters shouldBe
            setOf(UnicodeCodePoint('猫'.code), UnicodeCodePoint('語'.code))
        repository.getWordCoverage("profile", LanguageTag("ja")).let {
            it.encountered shouldBe 1
            it.coveredReadingAware shouldBe 1
            it.coveredHeadwordOrCharacter shouldBe 1
        }
        repository.getCharacterCoverage("profile", LanguageTag("ja")).let {
            it.encountered shouldBe 2
            it.coveredHeadwordOrCharacter shouldBe 2
        }

        repository.activateSnapshot(
            ankiSnapshot("snapshot-2", requestedAt = 2_000),
            listOf(ankiItem("snapshot-2", intervalDays = 30, tier = MaturityTier.MATURE)),
        )
        queryLong("SELECT count(*) FROM immersion_anki_snapshot WHERE is_current = 1") shouldBe 1
        queryStrings("SELECT id FROM immersion_anki_snapshot WHERE is_current = 1") shouldContainExactly
            listOf("snapshot-2")
        queryLong("SELECT is_stale FROM immersion_anki_snapshot WHERE id = 'snapshot-1'") shouldBe 1

        repository.recordSnapshotAttempt(
            ankiSnapshot(
                id = "snapshot-failed",
                requestedAt = 3_000,
                status = AnkiSnapshotStatus.FAILED,
                failure = AnkiInventoryFailure.PROVIDER_ERROR,
                current = false,
            ),
        )
        repository.getCurrentSnapshot("profile")?.let {
            it.id shouldBe "snapshot-2"
            it.isStale shouldBe true
        } shouldNotBe null
        repository.getLatestSnapshot("profile")?.id shouldBe "snapshot-failed"

        repository.recomputeCurrentMaturity(
            profileId = "profile",
            matureIntervalDays = 40,
            recomputedAtEpochMillis = 4_000,
        )
        repository.getCurrentItems("profile").single().maturityTier shouldBe MaturityTier.YOUNG
        repository.getCurrentSnapshot("profile")?.matureIntervalDays shouldBe 40

        repository.clearSnapshots("profile")
        repository.getCurrentSnapshot("profile") shouldBe null
        queryLong("SELECT count(*) FROM immersion_anki_item") shouldBe 0
    }

    @Test
    fun `integrity report is healthy for valid event backed data`() = runTest {
        prepareSession()
        repository.appendExposure(exposure(sequence = 1, eventNumber = 1))
        repository.finalizeSession(
            sessionId = SESSION_ID,
            status = SessionStatus.COMPLETED,
            endedAtEpochMillis = 2_000,
            elapsedDuration = MillisecondDuration(1_000),
        )

        repository.validateInvariants(expectedRollupVersion = 1).isHealthy shouldBe true
    }

    @Test
    fun `rollup rebuild splits midnight and serves deterministic analytics pages`() = runTest {
        val midnight = Instant.parse("2026-07-02T00:00:00Z").toEpochMilli()
        val range = LocalDateRange(
            ImmersionLocalDate.parse("2026-07-01"),
            ImmersionLocalDate.parse("2026-07-02"),
        )
        repository.upsertTitle(title()) shouldBe PersistenceResult.Applied
        repository.createSession(
            sessionStart(startedAt = midnight - 30 * 60 * 1_000),
        ) shouldBe PersistenceResult.Applied
        val source = source(midnight + 30 * 60 * 1_000).copy(
            firstExposedAtEpochMillis = midnight + 30 * 60 * 1_000,
            lastExposedAtEpochMillis = midnight + 30 * 60 * 1_000,
        )
        repository.appendExposure(
            exposure(sequence = 1, eventNumber = 501).copy(
                occurredAtEpochMillis = midnight + 30 * 60 * 1_000,
                activeDuration = MillisecondDuration(60 * 60 * 1_000),
                source = source,
            ),
        ) shouldBe PersistenceResult.Applied
        repository.appendExposure(
            exposure(sequence = 2, eventNumber = 502).copy(
                occurredAtEpochMillis = midnight + 40 * 60 * 1_000,
                activeDuration = MillisecondDuration(10 * 60 * 1_000),
                grossCharacters = NonNegativeCounter(50),
                uniqueSourceCharacters = NonNegativeCounter.ZERO,
                netCharacters = NetCharacterProgress.ZERO,
                replayOrdinal = 1,
                source = source.copy(lastExposedAtEpochMillis = midnight + 40 * 60 * 1_000),
            ),
        ) shouldBe PersistenceResult.Applied
        repository.storeIndexResult(
            sourceUnitId = SOURCE_ID,
            tokenizerId = "test",
            tokenizerVersion = 1,
            normalizationVersion = 1,
            indexedVersion = 1,
            indexedAtEpochMillis = midnight + 41 * 60 * 1_000,
            tokenizationConfidence = 1.0,
            terminalReason = null,
            words = listOf(indexedWord("word-rollup", "猫", "ねこ", 0)),
            characters = listOf(indexedCharacter('猫', 2)),
        )
        repository.finalizeSession(
            SESSION_ID,
            SessionStatus.COMPLETED,
            midnight + 41 * 60 * 1_000,
            MillisecondDuration(71 * 60 * 1_000),
        )

        repository.dirtyRollupRanges(20).map { it.start }.toSet() shouldBe
            setOf(range.start, range.endInclusive)
        repository.rebuildRollups(range, 2, midnight + 60 * 60 * 1_000).let {
            it.eventCount shouldBe 2
            it.sessionCount shouldBe 1
            it.rowCount shouldBe 3
        }

        val daily = repository.dailyRollups(range)
        daily.size shouldBe 3
        daily.sumOf { it.metrics.activeTime.value } shouldBe 70 * 60 * 1_000L
        daily.single { it.date == range.start && !it.replay }.let {
            it.metrics.activeTime.value shouldBe 30 * 60 * 1_000L
            it.metrics.sessions.value shouldBe 1
            it.metrics.characters.gross.value shouldBe 0
        }
        daily.single { it.date == range.endInclusive && !it.replay }.let {
            it.metrics.activeTime.value shouldBe 30 * 60 * 1_000L
            it.metrics.characters.gross.value shouldBe 100
            it.metrics.wordsEncountered.value shouldBe 1
            it.metrics.uniqueWords.value shouldBe 1
            it.metrics.distinctCharacters.value shouldBe 1
            it.metrics.sourceUnits.value shouldBe 1
        }
        daily.single { it.replay }.let {
            it.metrics.activeTime.value shouldBe 10 * 60 * 1_000L
            it.metrics.characters.gross.value shouldBe 50
            it.metrics.wordsEncountered.value shouldBe 1
        }
        repository.availableDateRange(StatsFilter()) shouldBe range
        repository.vocabularyPage(
            StatsFilter(dateRange = range),
            AnalyticsSort.MOST_OCCURRENCES,
            0,
            100,
        ).items.single().let {
            it.headword shouldBe "猫"
            it.occurrenceCount shouldBe 2
            it.maturity shouldBe MaturityTier.UNKNOWN
        }
        repository.vocabularyPage(
            StatsFilter(dateRange = range, includeRereadsAndReplays = false),
            AnalyticsSort.MOST_OCCURRENCES,
            0,
            100,
        ).items.single().occurrenceCount shouldBe 1
        repository.vocabularyPage(
            StatsFilter(dateRange = range, maturityTiers = setOf(MaturityTier.MATURE)),
            AnalyticsSort.MOST_OCCURRENCES,
            0,
            100,
        ).items shouldBe emptyList()
        repository.vocabularyPage(
            StatsFilter(dateRange = range, maturityTiers = setOf(MaturityTier.UNKNOWN)),
            AnalyticsSort.MOST_OCCURRENCES,
            0,
            100,
        ).items.single().headword shouldBe "猫"
        repository.characterPage(
            StatsFilter(dateRange = range),
            AnalyticsSort.MOST_OCCURRENCES,
            0,
            100,
        ).items.single().let {
            it.rendered shouldBe "猫"
            it.occurrenceCount shouldBe 4
        }
        repository.characterPage(
            StatsFilter(dateRange = range, includeRereadsAndReplays = false),
            AnalyticsSort.MOST_OCCURRENCES,
            0,
            100,
        ).items.single().occurrenceCount shouldBe 2
        repository.inventoryMetrics(StatsFilter(dateRange = range)).let {
            it.uniqueWords shouldBe 1
            it.newWords shouldBe 1
            it.distinctCharacters shouldBe 1
            it.newCharacters shouldBe 1
        }
        repository.titleInventoryMetrics(StatsFilter(dateRange = range))[TITLE_ID].let {
            it?.uniqueWords shouldBe 1
            it?.distinctCharacters shouldBe 1
        }
        val matureFilter = StatsFilter(
            dateRange = range,
            maturityTiers = setOf(MaturityTier.MATURE),
        )
        repository.inventoryMetrics(matureFilter) shouldBe AnalyticsInventoryMetrics()
        repository.titleInventoryMetrics(matureFilter) shouldBe emptyMap()
        repository.bucketInventoryMetrics(matureFilter, listOf(range)) shouldContainExactly
            listOf(AnalyticsBucketInventory())
        repository.dirtyRollupRanges(1) shouldBe emptyList()
        queryLong("SELECT count(*) FROM immersion_lifetime_rollup") shouldBe 2
        queryLong("SELECT count(*) FROM immersion_applied_event") shouldBe 2
    }

    @Test
    fun `bucket inventory keeps globally new identities distinct across titles days and replays`() = runTest {
        val firstDate = ImmersionLocalDate.parse("2026-07-01")
        val secondDate = ImmersionLocalDate.parse("2026-07-02")
        val firstDayStart = Instant.parse("2026-07-01T00:00:00Z").toEpochMilli()
        val secondDayStart = Instant.parse("2026-07-02T00:00:00Z").toEpochMilli()
        val buckets = listOf(
            LocalDateRange(firstDate, firstDate),
            LocalDateRange(secondDate, secondDate),
        )
        val titleA = TitleId("00000000-0000-0000-0000-000000000011")
        val titleB = TitleId("00000000-0000-0000-0000-000000000012")
        val sessionA = sessionId(11)
        val sessionB = sessionId(12)
        val sourceA = SourceUnitId("00000000-0000-0000-0000-000000000111")
        val replaySource = SourceUnitId("00000000-0000-0000-0000-000000000112")
        val sourceB = SourceUnitId("00000000-0000-0000-0000-000000000113")
        val secondDaySource = SourceUnitId("00000000-0000-0000-0000-000000000114")
        val catWord = indexedWord("word-cat", "猫", "ねこ", 0)
        val foxWord = indexedWord("word-fox", "狐", "きつね", 0)
        val dogWord = indexedWord("word-dog", "犬", "いぬ", 1)
        val catCharacter = indexedCharacter('猫', 1)
        val foxCharacter = indexedCharacter('狐', 1)
        val dogCharacter = indexedCharacter('犬', 1, firstOrdinal = 1)

        repository.upsertTitle(
            title().copy(
                id = titleA,
                sourceKey = "novel:inventory-a",
                displayTitle = "Inventory A",
            ),
        ) shouldBe PersistenceResult.Applied
        repository.upsertTitle(
            title().copy(
                id = titleB,
                sourceKey = "novel:inventory-b",
                displayTitle = "Inventory B",
            ),
        ) shouldBe PersistenceResult.Applied
        repository.createSession(
            sessionStart(sessionA, firstDayStart + 9 * 60 * 60 * 1_000).copy(titleId = titleA),
        ) shouldBe PersistenceResult.Applied
        repository.createSession(
            sessionStart(sessionB, firstDayStart + 10 * 60 * 60 * 1_000).copy(titleId = titleB),
        ) shouldBe PersistenceResult.Applied

        suspend fun append(
            eventNumber: Int,
            sessionId: SessionId,
            sequence: Long,
            occurredAt: Long,
            sourceId: SourceUnitId,
            titleId: TitleId,
            locator: String,
            replayOrdinal: Int = 0,
        ) {
            repository.appendExposure(
                ExposureEvent(
                    id = eventId(eventNumber),
                    sessionId = sessionId,
                    sequence = sequence,
                    occurredAtEpochMillis = occurredAt,
                    timezoneOffsetSeconds = 0,
                    source = source(occurredAt).copy(
                        id = sourceId,
                        titleId = titleId,
                        canonicalLocator = locator,
                        normalizedTextHash = "sha256:$locator",
                        firstExposedAtEpochMillis = occurredAt,
                    ),
                    activeDuration = MillisecondDuration(1_000),
                    grossCharacters = NonNegativeCounter(1),
                    uniqueSourceCharacters = NonNegativeCounter(if (replayOrdinal == 0) 1 else 0),
                    netCharacters = NetCharacterProgress.ZERO,
                    replayOrdinal = replayOrdinal,
                    exposurePolicy = "COUNT_ONCE_PER_SOURCE",
                ),
            ) shouldBe PersistenceResult.Applied
        }

        append(
            eventNumber = 701,
            sessionId = sessionA,
            sequence = 1,
            occurredAt = firstDayStart + 10 * 60 * 60 * 1_000,
            sourceId = sourceA,
            titleId = titleA,
            locator = "inventory-a:primary",
        )
        repository.appendEventBatch(
            listOf(
                LookupEvent(
                    id = eventId(700),
                    sessionId = sessionA,
                    sequence = 2,
                    occurredAtEpochMillis = firstDayStart + 10 * 60 * 60 * 1_000,
                    timezoneOffsetSeconds = 0,
                    lookupId = "inventory-non-exposure-event",
                    sourceUnitId = sourceA,
                    queryHash = "sha256:inventory-non-exposure-event",
                    rawQuery = null,
                    normalizedHeadword = "猫",
                    normalizedReading = "ねこ",
                    partOfSpeech = "noun",
                    dictionaryId = "test-dictionary",
                    resultId = "cat-result",
                    status = LookupStatus.SUCCESS,
                ),
            ),
        ) shouldContainExactly listOf(PersistenceResult.Applied)
        append(
            eventNumber = 702,
            sessionId = sessionA,
            sequence = 3,
            occurredAt = firstDayStart + 11 * 60 * 60 * 1_000,
            sourceId = replaySource,
            titleId = titleA,
            locator = "inventory-a:replay",
            replayOrdinal = 1,
        )
        append(
            eventNumber = 703,
            sessionId = sessionB,
            sequence = 1,
            occurredAt = firstDayStart + 12 * 60 * 60 * 1_000,
            sourceId = sourceB,
            titleId = titleB,
            locator = "inventory-b:first-day",
        )
        append(
            eventNumber = 704,
            sessionId = sessionB,
            sequence = 2,
            occurredAt = secondDayStart + 10 * 60 * 60 * 1_000,
            sourceId = secondDaySource,
            titleId = titleB,
            locator = "inventory-b:second-day",
        )

        suspend fun index(
            sourceId: SourceUnitId,
            words: List<IndexedWord>,
            characters: List<IndexedCharacter>,
        ) {
            repository.storeIndexResult(
                sourceUnitId = sourceId,
                tokenizerId = "test",
                tokenizerVersion = 1,
                normalizationVersion = 1,
                indexedVersion = 1,
                indexedAtEpochMillis = secondDayStart + 12 * 60 * 60 * 1_000,
                tokenizationConfidence = 1.0,
                terminalReason = null,
                words = words,
                characters = characters,
            )
        }

        index(sourceA, listOf(catWord), listOf(catCharacter))
        index(replaySource, listOf(foxWord), listOf(foxCharacter))
        index(sourceB, listOf(catWord), listOf(catCharacter))
        index(secondDaySource, listOf(catWord, dogWord), listOf(catCharacter, dogCharacter))

        repository.bucketInventoryMetrics(StatsFilter(), buckets) shouldContainExactly listOf(
            AnalyticsBucketInventory(
                metrics = AnalyticsInventoryMetrics(
                    distinctCharacters = 2,
                    newCharacters = 2,
                    uniqueWords = 2,
                    newWords = 2,
                ),
                cumulative = AnalyticsInventoryMetrics(
                    distinctCharacters = 2,
                    newCharacters = 2,
                    uniqueWords = 2,
                    newWords = 2,
                ),
            ),
            AnalyticsBucketInventory(
                metrics = AnalyticsInventoryMetrics(
                    distinctCharacters = 2,
                    newCharacters = 1,
                    uniqueWords = 2,
                    newWords = 1,
                ),
                cumulative = AnalyticsInventoryMetrics(
                    distinctCharacters = 3,
                    newCharacters = 3,
                    uniqueWords = 3,
                    newWords = 3,
                ),
            ),
        )
        repository.bucketInventoryMetrics(
            StatsFilter(includeRereadsAndReplays = false),
            buckets,
        ) shouldContainExactly listOf(
            AnalyticsBucketInventory(
                metrics = AnalyticsInventoryMetrics(
                    distinctCharacters = 1,
                    newCharacters = 1,
                    uniqueWords = 1,
                    newWords = 1,
                ),
                cumulative = AnalyticsInventoryMetrics(
                    distinctCharacters = 1,
                    newCharacters = 1,
                    uniqueWords = 1,
                    newWords = 1,
                ),
            ),
            AnalyticsBucketInventory(
                metrics = AnalyticsInventoryMetrics(
                    distinctCharacters = 2,
                    newCharacters = 1,
                    uniqueWords = 2,
                    newWords = 1,
                ),
                cumulative = AnalyticsInventoryMetrics(
                    distinctCharacters = 2,
                    newCharacters = 2,
                    uniqueWords = 2,
                    newWords = 2,
                ),
            ),
        )
        repository.bucketInventoryMetrics(
            StatsFilter(titleIds = setOf(titleB)),
            buckets,
        ) shouldContainExactly listOf(
            AnalyticsBucketInventory(
                metrics = AnalyticsInventoryMetrics(
                    distinctCharacters = 1,
                    uniqueWords = 1,
                ),
                cumulative = AnalyticsInventoryMetrics(
                    distinctCharacters = 1,
                    uniqueWords = 1,
                ),
            ),
            AnalyticsBucketInventory(
                metrics = AnalyticsInventoryMetrics(
                    distinctCharacters = 2,
                    newCharacters = 1,
                    uniqueWords = 2,
                    newWords = 1,
                ),
                cumulative = AnalyticsInventoryMetrics(
                    distinctCharacters = 2,
                    newCharacters = 1,
                    uniqueWords = 2,
                    newWords = 1,
                ),
            ),
        )

        repository.deleteSession(sessionA) shouldBe true
        repository.inventoryMetrics(StatsFilter()) shouldBe
            AnalyticsInventoryMetrics(
                distinctCharacters = 2,
                newCharacters = 2,
                uniqueWords = 2,
                newWords = 2,
            )
        repository.titleInventoryMetrics(
            StatsFilter(),
        )[titleB] shouldBe AnalyticsInventoryMetrics(
            distinctCharacters = 2,
            newCharacters = 2,
            uniqueWords = 2,
            newWords = 2,
        )
        repository.bucketInventoryMetrics(StatsFilter(), buckets) shouldContainExactly listOf(
            AnalyticsBucketInventory(
                metrics = AnalyticsInventoryMetrics(
                    distinctCharacters = 1,
                    newCharacters = 1,
                    uniqueWords = 1,
                    newWords = 1,
                ),
                cumulative = AnalyticsInventoryMetrics(
                    distinctCharacters = 1,
                    newCharacters = 1,
                    uniqueWords = 1,
                    newWords = 1,
                ),
            ),
            AnalyticsBucketInventory(
                metrics = AnalyticsInventoryMetrics(
                    distinctCharacters = 2,
                    newCharacters = 1,
                    uniqueWords = 2,
                    newWords = 1,
                ),
                cumulative = AnalyticsInventoryMetrics(
                    distinctCharacters = 2,
                    newCharacters = 2,
                    uniqueWords = 2,
                    newWords = 2,
                ),
            ),
        )
    }

    @Test
    fun `portable archive omits private text and merges idempotently`() = runTest {
        repository.upsertTitle(title()) shouldBe PersistenceResult.Applied
        repository.createSession(sessionStart()) shouldBe PersistenceResult.Applied
        repository.appendExposure(
            exposure(sequence = 1, eventNumber = 600).copy(
                source = source(lastExposedAt = 1_100).copy(rawText = "猫を読む"),
            ),
        ) shouldBe PersistenceResult.Applied
        repository.finalizeSession(
            SESSION_ID,
            SessionStatus.COMPLETED,
            2_000,
            MillisecondDuration(1_000),
        ) shouldBe PersistenceResult.Applied

        val privateArchive = repository.exportPortableArchive(
            includeRawText = false,
            createdAtEpochMillis = 3_000,
        )
        privateArchive.includesRawText shouldBe false
        privateArchive.tables.single { it.name == "immersion_source_unit" }.let { table ->
            val rawTextIndex = table.columns.indexOfFirst { it.name == "raw_text" }
            table.rows.single().cells[rawTextIndex].kind.name shouldBe "NULL"
        }
        val fullArchive = repository.exportPortableArchive(
            includeRawText = true,
            createdAtEpochMillis = 3_001,
        )
        fullArchive.tables.single { it.name == "immersion_source_unit" }.let { table ->
            val rawTextIndex = table.columns.indexOfFirst { it.name == "raw_text" }
            table.rows.single().cells[rawTextIndex].blobValue shouldNotBe null
        }

        val targetDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(targetDriver).value
            targetDriver.execute(null, "PRAGMA foreign_keys = ON", 0).value
            val target = SqlDelightImmersionRepository(
                AndroidDatabaseHandler(
                    createDatabase(targetDriver),
                    targetDriver,
                    Dispatchers.IO,
                    Dispatchers.IO,
                ),
            )

            target.mergePortableArchive(privateArchive, 4_000).let {
                it.insertedRows shouldNotBe 0
                it.quarantinedConflicts shouldBe 0
            }
            target.overview().let {
                it.sessions shouldBe NonNegativeCounter(1)
                it.grossCharacters shouldBe NonNegativeCounter(100)
            }
            target.mergePortableArchive(privateArchive, 4_001).let {
                it.insertedRows shouldBe 0
                it.quarantinedConflicts shouldBe 0
                it.unchangedRows shouldNotBe 0
            }
            target.mergePortableArchive(fullArchive, 4_002).quarantinedConflicts shouldBe 0
            target.exportPortableArchive(true, 4_003)
                .tables
                .single { it.name == "immersion_source_unit" }
                .let { table ->
                    val rawTextIndex = table.columns.indexOfFirst { it.name == "raw_text" }
                    table.rows.single().cells[rawTextIndex].blobValue shouldNotBe null
                }
        } finally {
            targetDriver.close()
        }
    }

    @Test
    fun `portable merge canonicalizes historical lookup counters before rebuilding rollups`() = runTest {
        prepareSession()
        repository.appendExposure(exposure(sequence = 1, eventNumber = 909)) shouldBe PersistenceResult.Applied
        val statuses = listOf(
            LookupStatus.SUCCESS,
            LookupStatus.EMPTY,
            LookupStatus.FAILED,
            LookupStatus.CANCELLED,
        )
        repository.appendEventBatch(
            statuses.mapIndexed { index, status ->
                LookupEvent(
                    id = eventId(910 + index),
                    sessionId = SESSION_ID,
                    sequence = index + 2L,
                    occurredAtEpochMillis = 1_100L + index,
                    timezoneOffsetSeconds = 0,
                    lookupId = "portable-lookup-$status",
                    sourceUnitId = null,
                    queryHash = "portable-query-$status",
                    rawQuery = null,
                    normalizedHeadword = null,
                    normalizedReading = null,
                    partOfSpeech = null,
                    dictionaryId = null,
                    resultId = null,
                    status = status,
                )
            },
        ) shouldContainExactly List(statuses.size) { PersistenceResult.Applied }
        repository.finalizeSession(
            sessionId = SESSION_ID,
            status = SessionStatus.COMPLETED,
            endedAtEpochMillis = 2_000,
            elapsedDuration = MillisecondDuration(1_000),
        )
        driver.execute(
            null,
            "UPDATE immersion_event SET lookup_delta = 1 WHERE type = 'LOOKUP'",
            0,
        ).value
        driver.execute(
            null,
            "UPDATE immersion_session SET lookup_count = 4 WHERE id = '${SESSION_ID.value}'",
            0,
        ).value
        val historicalArchive = repository.exportPortableArchive(
            includeRawText = false,
            createdAtEpochMillis = 3_000,
        )

        val targetDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(targetDriver).value
            targetDriver.execute(null, "PRAGMA foreign_keys = ON", 0).value
            val target = SqlDelightImmersionRepository(
                AndroidDatabaseHandler(
                    createDatabase(targetDriver),
                    targetDriver,
                    Dispatchers.IO,
                    Dispatchers.IO,
                ),
            )

            target.mergePortableArchive(historicalArchive, 4_000).let { report ->
                report.quarantinedConflicts shouldBe 0
                report.rebuiltRollupRows shouldBe 1
            }
            target.overview().lookups shouldBe NonNegativeCounter(1)
            queryStrings(
                targetDriver,
                """
                SELECT lookup.status || ':' || event.lookup_delta
                FROM immersion_lookup AS lookup
                JOIN immersion_event AS event ON event.id = lookup.event_id
                ORDER BY lookup.status
                """.trimIndent(),
            ) shouldContainExactly listOf(
                "CANCELLED:0",
                "EMPTY:0",
                "FAILED:0",
                "SUCCESS:1",
            )
            queryLong(targetDriver, "SELECT sum(lookups) FROM immersion_daily_rollup") shouldBe 1
            queryLong(targetDriver, "SELECT sum(lookups) FROM immersion_lifetime_rollup") shouldBe 1

            target.mergePortableArchive(historicalArchive, 4_001).let { report ->
                report.insertedRows shouldBe 0
                report.quarantinedConflicts shouldBe 0
            }
            target.overview().lookups shouldBe NonNegativeCounter(1)
        } finally {
            targetDriver.close()
        }
    }

    @Test
    fun `portable merge quarantines same identity with a different payload`() = runTest {
        repository.upsertTitle(title()) shouldBe PersistenceResult.Applied
        val archive = repository.exportPortableArchive(false, 3_000)
        repository.upsertTitle(
            title().copy(
                displayTitle = "Conflicting title",
                updatedAtEpochMillis = 3_500,
            ),
        ) shouldBe PersistenceResult.Applied

        repository.mergePortableArchive(archive, 4_000).quarantinedConflicts shouldBe 1
        queryLong("SELECT count(*) FROM immersion_merge_conflict") shouldBe 1
        queryStrings("SELECT display_title FROM immersion_title").single() shouldBe "Conflicting title"
        repository.resolveMergeConflictsKeepingLocal() shouldBe 1
        repository.maintenanceSummary().quarantinedConflicts shouldBe 0
        queryStrings("SELECT resolution_state FROM immersion_merge_conflict").single() shouldBe
            "RESOLVED_KEEP_LOCAL"
    }

    @Test
    fun `portable merge rejects a future immersion schema`() = runTest {
        val archive = repository.exportPortableArchive(false, 3_000)
        runCatching {
            repository.mergePortableArchive(
                archive.copy(sourceSchemaVersion = Int.MAX_VALUE),
                4_000,
            )
        }.exceptionOrNull() shouldNotBe null
    }

    @Test
    fun `session tombstone prevents an older archive from resurrecting deleted activity`() = runTest {
        repository.upsertTitle(title()) shouldBe PersistenceResult.Applied
        repository.createSession(sessionStart()) shouldBe PersistenceResult.Applied
        repository.appendExposure(exposure(sequence = 1, eventNumber = 601)) shouldBe PersistenceResult.Applied
        repository.storeIndexResult(
            sourceUnitId = SOURCE_ID,
            tokenizerId = "test",
            tokenizerVersion = 1,
            normalizationVersion = 1,
            indexedVersion = 1,
            indexedAtEpochMillis = 1_200,
            tokenizationConfidence = 1.0,
            terminalReason = null,
            words = listOf(indexedWord("word-tombstoned-source", "猫", ordinal = 0)),
            characters = listOf(indexedCharacter('猫', 1)),
        )
        repository.finalizeSession(
            SESSION_ID,
            SessionStatus.COMPLETED,
            2_000,
            MillisecondDuration(1_000),
        ) shouldBe PersistenceResult.Applied
        val rollupDate = ImmersionLocalDate.parse("1970-01-01")
        repository.rebuildRollups(
            LocalDateRange(rollupDate, rollupDate),
            rollupVersion = 2,
            nowEpochMillis = 2_500,
        )
        queryLong("SELECT count(*) FROM immersion_daily_rollup") shouldBe 1
        val oldArchive = repository.exportPortableArchive(false, 3_000)

        repository.deleteSession(SESSION_ID) shouldBe true
        queryLong("SELECT count(*) FROM immersion_tombstone WHERE entity_type = 'SESSION'") shouldBe 1
        queryLong("SELECT count(*) FROM immersion_tombstone WHERE entity_type = 'SOURCE_UNIT'") shouldBe 1
        queryLong("SELECT count(*) FROM immersion_event") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_source_unit") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_word") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_character") shouldBe 0

        repository.mergePortableArchive(oldArchive, 4_000).let {
            it.skippedByTombstoneRows shouldNotBe 0
            it.quarantinedConflicts shouldBe 0
        }
        repository.overview().sessions shouldBe NonNegativeCounter.ZERO
        queryLong("SELECT count(*) FROM immersion_event") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_source_unit") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_word") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_character") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_daily_rollup") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_lifetime_rollup") shouldBe 0
    }

    @Test
    fun `portable merge promotes shared source boundaries when a newer archive tombstones the first session`() =
        runTest {
            val firstAt = 1_100L
            val secondAt = 2_100L
            val secondSession = sessionId(4)
            repository.upsertTitle(title()) shouldBe PersistenceResult.Applied
            repository.createSession(sessionStart(startedAt = 1_000)) shouldBe PersistenceResult.Applied
            repository.createSession(
                sessionStart(id = secondSession, startedAt = 2_000),
            ) shouldBe PersistenceResult.Applied
            repository.appendExposure(
                exposure(sequence = 1, eventNumber = 610).copy(
                    occurredAtEpochMillis = firstAt,
                    source = source(firstAt).copy(firstExposedAtEpochMillis = firstAt),
                ),
            ) shouldBe PersistenceResult.Applied
            repository.appendExposure(
                exposure(sequence = 1, eventNumber = 611).copy(
                    sessionId = secondSession,
                    occurredAtEpochMillis = secondAt,
                    source = source(secondAt),
                ),
            ) shouldBe PersistenceResult.Applied
            repository.storeIndexResult(
                sourceUnitId = SOURCE_ID,
                tokenizerId = "test",
                tokenizerVersion = 1,
                normalizationVersion = 1,
                indexedVersion = 1,
                indexedAtEpochMillis = 2_200,
                tokenizationConfidence = 1.0,
                terminalReason = null,
                words = listOf(indexedWord("word-portable-shared", "猫", ordinal = 0)),
                characters = listOf(indexedCharacter('猫', 1)),
            )
            repository.finalizeSession(
                SESSION_ID,
                SessionStatus.COMPLETED,
                1_500,
                MillisecondDuration(500),
            ) shouldBe PersistenceResult.Applied
            repository.finalizeSession(
                secondSession,
                SessionStatus.COMPLETED,
                2_500,
                MillisecondDuration(500),
            ) shouldBe PersistenceResult.Applied
            val olderArchive = repository.exportPortableArchive(false, 3_000)

            repository.deleteSession(SESSION_ID) shouldBe true
            val deletionArchive = repository.exportPortableArchive(false, 4_000)

            val targetDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                Database.Schema.create(targetDriver).value
                targetDriver.execute(null, "PRAGMA foreign_keys = ON", 0).value
                val target = SqlDelightImmersionRepository(
                    AndroidDatabaseHandler(
                        createDatabase(targetDriver),
                        targetDriver,
                        databaseDispatcher,
                        databaseDispatcher,
                    ),
                )

                target.mergePortableArchive(olderArchive, 5_000).quarantinedConflicts shouldBe 0
                queryLong(
                    targetDriver,
                    "SELECT first_exposed_at FROM immersion_source_unit WHERE id = '${SOURCE_ID.value}'",
                ) shouldBe firstAt

                target.mergePortableArchive(deletionArchive, 6_000).let { report ->
                    report.quarantinedConflicts shouldBe 0
                }
                queryLong(
                    targetDriver,
                    "SELECT first_exposed_at FROM immersion_source_unit WHERE id = '${SOURCE_ID.value}'",
                ) shouldBe secondAt
                queryLong(
                    targetDriver,
                    "SELECT last_exposed_at FROM immersion_source_unit WHERE id = '${SOURCE_ID.value}'",
                ) shouldBe secondAt
                queryLong(
                    targetDriver,
                    "SELECT first_seen_at FROM immersion_word WHERE id = 'word-portable-shared'",
                ) shouldBe secondAt
                queryLong(
                    targetDriver,
                    "SELECT first_seen_at FROM immersion_character WHERE code_point = ${'猫'.code}",
                ) shouldBe secondAt
                target.inventoryMetrics(StatsFilter()).let {
                    it.newWords shouldBe 1
                    it.newCharacters shouldBe 1
                }

                target.mergePortableArchive(deletionArchive, 7_000).quarantinedConflicts shouldBe 0
            } finally {
                targetDriver.close()
            }
        }

    @Test
    fun `raw text deletion clears search without changing aggregate totals`() = runTest {
        repository.upsertTitle(title()) shouldBe PersistenceResult.Applied
        repository.createSession(sessionStart()) shouldBe PersistenceResult.Applied
        repository.appendExposure(
            exposure(sequence = 1, eventNumber = 602).copy(
                source = source(lastExposedAt = 1_100).copy(rawText = "猫を読む"),
            ),
        ) shouldBe PersistenceResult.Applied
        repository.storeIndexResult(
            sourceUnitId = SOURCE_ID,
            tokenizerId = "test-v1",
            tokenizerVersion = 1,
            normalizationVersion = 1,
            indexedVersion = 1,
            indexedAtEpochMillis = 1_150,
            tokenizationConfidence = 1.0,
            terminalReason = null,
            words = listOf(indexedWord("word-retained-after-raw-deletion", "猫", ordinal = 0)),
            characters = listOf(indexedCharacter('猫', 1)),
        )
        repository.appendEventBatch(
            listOf(
                LookupEvent(
                    id = eventId(603),
                    sessionId = SESSION_ID,
                    sequence = 2,
                    occurredAtEpochMillis = 1_200,
                    timezoneOffsetSeconds = 0,
                    lookupId = "raw-text-deletion-lookup",
                    sourceUnitId = SOURCE_ID,
                    queryHash = "sha256:lookup-query",
                    rawQuery = "猫",
                    normalizedHeadword = "猫",
                    normalizedReading = "ねこ",
                    partOfSpeech = "noun",
                    dictionaryId = "test-dictionary",
                    resultId = "cat-result",
                    status = LookupStatus.SUCCESS,
                ),
            ),
        ) shouldContainExactly listOf(PersistenceResult.Applied)
        repository.finalizeSession(
            SESSION_ID,
            SessionStatus.COMPLETED,
            2_000,
            MillisecondDuration(1_000),
        ) shouldBe PersistenceResult.Applied
        repository.sourceSearch(StatsFilter(), "猫", 0, 10).items.size shouldBe 1
        val before = repository.overview()

        repository.previewRawTextDeletion(
            titleId = TITLE_ID,
            beforeEpochMillis = 1_500,
        ) shouldBe 2
        repository.deleteRawText(
            titleId = TITLE_ID,
            beforeEpochMillis = 1_500,
            updatedAtEpochMillis = 3_000,
        ) shouldBe 2

        repository.sourceSearch(StatsFilter(), "猫", 0, 10).items shouldBe emptyList()
        queryLong("SELECT count(*) FROM immersion_source_unit WHERE raw_text IS NOT NULL") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_lookup WHERE raw_query IS NOT NULL") shouldBe 0
        queryStrings("SELECT normalized_text_hash FROM immersion_source_unit") shouldContainExactly
            listOf("sha256:test")
        queryStrings("SELECT query_hash FROM immersion_lookup") shouldContainExactly
            listOf("sha256:lookup-query")
        queryLong(
            "SELECT count(*) FROM immersion_word WHERE id = 'word-retained-after-raw-deletion'",
        ) shouldBe 1
        queryLong("SELECT count(*) FROM immersion_word_occurrence WHERE source_unit_id = '${SOURCE_ID.value}'") shouldBe 1
        queryLong("SELECT count(*) FROM immersion_character WHERE rendered = '猫'") shouldBe 1
        queryLong(
            "SELECT count(*) FROM immersion_character_occurrence WHERE source_unit_id = '${SOURCE_ID.value}'",
        ) shouldBe 1
        repository.overview() shouldBe before
        repository.deleteRawText(
            titleId = TITLE_ID,
            beforeEpochMillis = 1_500,
            updatedAtEpochMillis = 4_000,
        ) shouldBe 0
        repository.previewRawTextDeletion(
            titleId = TITLE_ID,
            beforeEpochMillis = 1_500,
        ) shouldBe 0
    }

    @Test
    fun `title capture exclusion can be enabled and removed`() = runTest {
        repository.isTitleCaptureExcluded(TITLE_ID) shouldBe false

        repository.setTitleCaptureExcluded(TITLE_ID, excluded = true, updatedAtEpochMillis = 1_000)
        repository.isTitleCaptureExcluded(TITLE_ID) shouldBe true

        repository.setTitleCaptureExcluded(TITLE_ID, excluded = false, updatedAtEpochMillis = 2_000)
        repository.isTitleCaptureExcluded(TITLE_ID) shouldBe false
    }

    @Test
    fun `full reset previews impact and tombstones prevent archive resurrection`() = runTest {
        repository.upsertTitle(title()) shouldBe PersistenceResult.Applied
        repository.createSession(sessionStart()) shouldBe PersistenceResult.Applied
        repository.appendExposure(exposure(sequence = 1, eventNumber = 603)) shouldBe PersistenceResult.Applied
        repository.finalizeSession(
            SESSION_ID,
            SessionStatus.COMPLETED,
            2_000,
            MillisecondDuration(1_000),
        ) shouldBe PersistenceResult.Applied
        val archive = repository.exportPortableArchive(false, 3_000)
        repository.previewAllStatsDeletion().let {
            it.sessions shouldBe 1
            it.grossCharacters shouldBe 100
            it.sourceUnits shouldBe 1
        }

        repository.resetAllStats("device-reset", 4_000).sessions shouldBe 1
        allStatsResetCallbacks shouldBe 1
        repository.overview().sessions shouldBe NonNegativeCounter.ZERO
        queryLong("SELECT count(*) FROM immersion_title") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_tombstone") shouldNotBe 0

        repository.mergePortableArchive(archive, 5_000).let {
            it.skippedByTombstoneRows shouldNotBe 0
            it.quarantinedConflicts shouldBe 0
        }
        repository.overview().sessions shouldBe NonNegativeCounter.ZERO
        queryLong("SELECT count(*) FROM immersion_title") shouldBe 0
    }

    @Test
    fun `primary session list uses its stable ordering index`() {
        val details = queryStrings(
            """
            EXPLAIN QUERY PLAN
            SELECT *
            FROM immersion_session
            WHERE status != 'DELETED'
            ORDER BY started_at DESC, id DESC
            LIMIT 20
            """.trimIndent(),
            column = 3,
        )

        details.shouldNotBeEmpty()
        details.any { "immersion_session_time_index" in it } shouldBe true
    }

    @Test
    fun `date-filtered analytics use the materialized local-date index`() {
        val details = queryStrings(
            """
            EXPLAIN QUERY PLAN
            SELECT *
            FROM immersion_event
            WHERE local_date BETWEEN 20000 AND 20365
            ORDER BY local_date, session_id, id
            """.trimIndent(),
            column = 3,
        )

        details.shouldNotBeEmpty()
        details.any { "immersion_event_local_date_scope_index" in it } shouldBe true
    }

    @Test
    fun `inventory first-event lookup uses entity and source-time indexes`() {
        val details = queryStrings(
            """
            EXPLAIN QUERY PLAN
            SELECT min(event.id)
            FROM immersion_word_occurrence AS occurrence
            JOIN immersion_source_exposure AS exposure
                ON exposure.source_unit_id = occurrence.source_unit_id
            JOIN immersion_event AS event
                ON event.id = exposure.event_id
                AND event.occurred_at = 1000
            WHERE occurrence.word_id = 'word-id'
            """.trimIndent(),
            column = 3,
        )

        details.shouldNotBeEmpty()
        details.any { "immersion_word_occurrence_word_index" in it } shouldBe true
        details.any { "immersion_source_exposure_source_time_index" in it } shouldBe true
    }

    @Test
    fun `one-year multi-title rollup fixture remains range bounded`() = runTest {
        val start = ImmersionLocalDate.parse("2025-01-01")
        driver.execute(
            null,
            """
            WITH RECURSIVE
                dates(day) AS (
                    SELECT ${start.epochDay}
                    UNION ALL
                    SELECT day + 1 FROM dates WHERE day < ${start.epochDay + 364}
                ),
                titles(number) AS (
                    SELECT 1
                    UNION ALL
                    SELECT number + 1 FROM titles WHERE number < 100
                )
            INSERT INTO immersion_daily_rollup(
                scope_key,
                local_date,
                media_kind,
                title_id,
                gross_characters,
                rollup_version
            )
            SELECT
                day || ':' || number,
                day,
                'NOVEL',
                printf('00000000-0000-0000-0000-%012d', number),
                100,
                2
            FROM dates
            CROSS JOIN titles
            """.trimIndent(),
            0,
        ).value

        repository.dailyRollups(
            LocalDateRange(start, ImmersionLocalDate(start.epochDay + 364)),
        ).let {
            it.size shouldBe 36_500
            it.sumOf { row -> row.metrics.characters.gross.value } shouldBe 3_650_000
        }
    }

    private suspend fun prepareSession() {
        repository.upsertTitle(title()) shouldBe PersistenceResult.Applied
        repository.createSession(sessionStart()) shouldBe PersistenceResult.Applied
    }

    private fun title() = ImmersionTitle(
        id = TITLE_ID,
        mediaKind = MediaKind.NOVEL,
        sourceKey = "novel:test",
        languageTag = LanguageTag("ja"),
        displayTitle = "Test title",
        createdAtEpochMillis = 1_000,
        updatedAtEpochMillis = 1_000,
    )

    private fun sessionStart(
        id: SessionId = SESSION_ID,
        startedAt: Long = 1_000,
    ) = ImmersionSessionStart(
        id = id,
        deviceId = "test-device",
        titleId = TITLE_ID,
        mediaKind = MediaKind.NOVEL,
        languageTag = LanguageTag("ja"),
        startedAtEpochMillis = startedAt,
        startZoneId = "UTC",
        startOffsetSeconds = 0,
        captureVersion = 1,
        schemaVersion = 1,
    )

    private fun exposure(
        sequence: Long,
        eventNumber: Int,
    ) = ExposureEvent(
        id = eventId(eventNumber),
        sessionId = SESSION_ID,
        sequence = sequence,
        occurredAtEpochMillis = 1_000 + sequence * 100,
        timezoneOffsetSeconds = 0,
        source = source(lastExposedAt = 1_000 + sequence * 100),
        activeDuration = MillisecondDuration(1_000),
        grossCharacters = NonNegativeCounter(100),
        uniqueSourceCharacters = NonNegativeCounter(90),
        netCharacters = NetCharacterProgress(80),
        exposurePolicy = "COUNT_ONCE_PER_SOURCE",
    )

    private fun source(lastExposedAt: Long) = ImmersionSourceUnit(
        id = SOURCE_ID,
        titleId = TITLE_ID,
        sourceKind = SourceKind.NOVEL_RANGE,
        canonicalLocator = "novel:test:chapter-1:0-100",
        normalizedTextHash = "sha256:test",
        chapterOrSectionId = "chapter-1",
        sourceStart = 0,
        sourceEnd = 100,
        firstExposedAtEpochMillis = 1_000,
        lastExposedAtEpochMillis = lastExposedAt,
        characterCounts = CharacterVolume(
            gross = NonNegativeCounter(100),
            uniqueSource = NonNegativeCounter(90),
            netProgress = NetCharacterProgress(80),
        ),
    )

    private fun indexedWord(
        id: String,
        headword: String,
        reading: String = "",
        ordinal: Long,
    ) = IndexedWord(
        id = id,
        languageTag = LanguageTag("ja"),
        normalizedHeadword = headword,
        normalizedReading = reading,
        displayHeadword = headword,
        displayReading = reading.takeIf(String::isNotEmpty),
        tokenOrdinal = ordinal,
        surfaceText = headword,
    )

    private fun indexedCharacter(
        character: Char,
        count: Long,
        firstOrdinal: Long = 0,
    ) = IndexedCharacter(
        codePoint = UnicodeCodePoint(character.code),
        unicodeName = Character.getName(character.code),
        unicodeCategory = "OTHER_LETTER",
        unicodeScript = Character.UnicodeScript.of(character.code).name,
        occurrenceCount = NonNegativeCounter(count),
        firstOrdinal = firstOrdinal,
    )

    private fun ankiSnapshot(
        id: String,
        requestedAt: Long,
        status: AnkiSnapshotStatus = AnkiSnapshotStatus.COMPLETE,
        failure: AnkiInventoryFailure? = null,
        current: Boolean = true,
    ) = ImmersionAnkiSnapshot(
        id = id,
        profileId = "profile",
        deckScope = "Mining",
        requestedAtEpochMillis = requestedAt,
        completedAtEpochMillis = requestedAt + 100,
        capabilityVersion = 1,
        capabilityState = CapabilityState.AVAILABLE,
        providerVersion = "2.24",
        supportsNoteModificationTime = true,
        supportsCardModificationTime = false,
        supportsReviewHistory = false,
        status = status,
        errorCode = failure,
        itemCount = if (status == AnkiSnapshotStatus.COMPLETE) 1 else 0,
        noteCount = if (status == AnkiSnapshotStatus.COMPLETE) 1 else 0,
        matureIntervalDays = 21,
        mappingHash = "mapping",
        queryDurationMillis = 10,
        isComplete = status == AnkiSnapshotStatus.COMPLETE,
        isPartial = failure == AnkiInventoryFailure.PARTIAL_RESULT,
        isCurrent = current,
        isStale = false,
    )

    private fun ankiItem(
        snapshotId: String,
        intervalDays: Int,
        tier: MaturityTier,
    ) = ImmersionAnkiItem(
        snapshotId = snapshotId,
        noteId = 1,
        cardId = 10,
        noteTypeId = 20,
        deckId = 30,
        languageTag = LanguageTag("ja"),
        normalizedWord = "猫語",
        normalizedReading = "ねこご",
        characters = setOf(UnicodeCodePoint('猫'.code), UnicodeCodePoint('語'.code)),
        cardType = 2,
        queue = 2,
        intervalDays = intervalDays,
        due = 100,
        repetitions = 4,
        lapses = 1,
        ease = 2_500,
        noteModifiedAtEpochSeconds = 900,
        matchConfidence = AnkiMatchConfidence.READING_AWARE,
        ambiguityCount = 1,
        maturityTier = tier,
        firstMatureAtEpochMillis = if (tier == MaturityTier.MATURE) 2_000 else null,
    )

    private fun queryLong(sql: String): Long = queryLong(driver, sql)

    private fun queryStrings(
        sql: String,
        column: Int = 0,
    ): List<String> = queryStrings(driver, sql, column)

    private fun legacyBatch(
        contentHash: String = "a".repeat(64),
        characters: Long = 2_000,
    ) = LegacyImportBatch(
        identity = LegacyImportIdentity(
            sourceKey = "novels/test/statistics.json",
            sourceVersion = 1,
            contentHash = contentHash,
        ),
        sourceKind = LegacyImportSourceKind.NOVEL_JSON,
        aggregates = listOf(
            LegacyDailyAggregate(
                sessionId = sessionId(900),
                titleId = TITLE_ID,
                titleSourceKey = "legacy:novel:test",
                displayTitle = "Legacy test",
                mediaKind = MediaKind.NOVEL,
                profileId = "default",
                languageTag = LanguageTag("ja"),
                localDate = tachiyomi.domain.immersion.model.ImmersionLocalDate.parse("2024-01-02"),
                startAnchorEpochMillis = 1_704_153_600_000,
                startZoneId = "UTC",
                startOffsetSeconds = 0,
                activeDuration = MillisecondDuration(90_050),
                originalReadingTimeSeconds = 90.05,
                characters = NonNegativeCounter(characters),
                cardsTotal = NonNegativeCounter(4),
                completed = true,
                metadataJson = """{"maximumReadingSpeed":2400}""",
            ),
        ),
        importedAtEpochMillis = 1_704_153_700_000,
    )

    companion object {
        private val TITLE_ID = TitleId("00000000-0000-0000-0000-000000000001")
        private val SESSION_ID = sessionId(1)
        private val SOURCE_ID = SourceUnitId("00000000-0000-0000-0000-000000000101")

        private fun sessionId(number: Int) =
            SessionId("00000000-0000-0000-0000-${number.toString().padStart(12, '0')}")

        private fun eventId(number: Int) =
            EventId("00000000-0000-0000-0001-${number.toString().padStart(12, '0')}")

        private fun createDatabase(driver: JdbcSqliteDriver) =
            Database(
                driver = driver,
                historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
                mangasAdapter = Mangas.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = MangaUpdateStrategyColumnAdapter,
                    memoAdapter = MemoColumnAdapter,
                ),
                chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
                reading_sessionsAdapter = Reading_sessions.Adapter(read_atAdapter = DateColumnAdapter),
            )

        private fun queryLong(
            driver: JdbcSqliteDriver,
            sql: String,
        ): Long = driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                check(cursor.next().value)
                QueryResult.Value(cursor.getLong(0)!!)
            },
            parameters = 0,
        ).value

        private fun queryStrings(
            driver: JdbcSqliteDriver,
            sql: String,
            column: Int = 0,
        ): List<String> = driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                val result = mutableListOf<String>()
                while (cursor.next().value) {
                    result += cursor.getString(column)!!
                }
                QueryResult.Value(result)
            },
            parameters = 0,
        ).value

        private fun queryImmersionSchema(driver: JdbcSqliteDriver): List<String> {
            val definitions = driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT type, name, sql
                    FROM sqlite_master
                    WHERE name LIKE 'immersion_%'
                        AND sql IS NOT NULL
                        AND NOT (
                            type = 'table'
                            AND name IN (
                                'immersion_session',
                                'immersion_source_unit',
                                'immersion_anki_snapshot',
                                'immersion_anki_item',
                                'immersion_daily_rollup',
                                'immersion_lifetime_rollup',
                                'immersion_event'
                            )
                        )
                    ORDER BY type, name
                """.trimIndent(),
                mapper = { cursor ->
                    val result = mutableListOf<String>()
                    while (cursor.next().value) {
                        val sql = cursor.getString(2)!!
                            .replace(Regex("\\s+"), " ")
                            .trim()
                        result += "${cursor.getString(0)}|${cursor.getString(1)}|$sql"
                    }
                    QueryResult.Value(result)
                },
                parameters = 0,
            ).value
            val sessionColumns = driver.executeQuery(
                identifier = null,
                sql = "PRAGMA table_info('immersion_session')",
                mapper = { cursor ->
                    val result = mutableListOf<String>()
                    while (cursor.next().value) {
                        result += listOf(
                            cursor.getString(1),
                            cursor.getString(2),
                            cursor.getLong(3),
                            cursor.getString(4),
                            cursor.getLong(5),
                        ).joinToString("|", prefix = "column|")
                    }
                    QueryResult.Value(result)
                },
                parameters = 0,
            ).value
            return definitions + sessionColumns
        }

        private fun assertLegacySessionConstraints(driver: JdbcSqliteDriver) {
            driver.execute(null, "PRAGMA foreign_keys = ON", 0).value
            driver.execute(
                null,
                """
                INSERT INTO immersion_title(
                    id, media_kind, source_key, display_title, created_at, updated_at
                ) VALUES (
                    '00000000-0000-0000-0000-000000009999',
                    'NOVEL',
                    'constraint-test',
                    'Constraint test',
                    0,
                    0
                )
                """.trimIndent(),
                0,
            ).value
            runCatching {
                driver.execute(
                    null,
                    """
                    INSERT INTO immersion_session(
                        id,
                        device_id,
                        title_id,
                        media_kind,
                        started_at,
                        start_zone_id,
                        start_offset_seconds,
                        status,
                        capture_version,
                        schema_version,
                        legacy_import
                    ) VALUES (
                        '00000000-0000-0000-0000-000000009998',
                        'test',
                        '00000000-0000-0000-0000-000000009999',
                        'NOVEL',
                        0,
                        'UTC',
                        0,
                        'COMPLETED',
                        1,
                        1,
                        1
                    )
                    """.trimIndent(),
                    0,
                ).value
            }.exceptionOrNull() shouldNotBe null
        }
    }
}
