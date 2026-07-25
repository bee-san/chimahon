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
import tachiyomi.domain.immersion.model.AnkiOperationEvent
import tachiyomi.domain.immersion.model.AnkiOperationId
import tachiyomi.domain.immersion.model.AnkiOperationStatus
import tachiyomi.domain.immersion.model.AnkiOperationType
import tachiyomi.domain.immersion.model.CapabilityState
import tachiyomi.domain.immersion.model.CharacterVolume
import tachiyomi.domain.immersion.model.EventId
import tachiyomi.domain.immersion.model.EventType
import tachiyomi.domain.immersion.model.ExposureEvent
import tachiyomi.domain.immersion.model.ImmersionDataException
import tachiyomi.domain.immersion.model.ImmersionSessionStart
import tachiyomi.domain.immersion.model.ImmersionSourceUnit
import tachiyomi.domain.immersion.model.ImmersionTitle
import tachiyomi.domain.immersion.model.LanguageTag
import tachiyomi.domain.immersion.model.LegacyDailyAggregate
import tachiyomi.domain.immersion.model.LegacyImportBatch
import tachiyomi.domain.immersion.model.LegacyImportIdentity
import tachiyomi.domain.immersion.model.LegacyImportResultState
import tachiyomi.domain.immersion.model.LegacyImportSourceKind
import tachiyomi.domain.immersion.model.LookupEvent
import tachiyomi.domain.immersion.model.LookupStatus
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
import tachiyomi.domain.immersion.model.TitleId
import tachiyomi.domain.immersion.service.AnkiOperationToken
import tachiyomi.domain.immersion.service.PendingAnkiOperation
import java.util.UUID

@Execution(ExecutionMode.SAME_THREAD)
class SqlDelightImmersionRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: SqlDelightImmersionRepository

    @BeforeEach
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).value
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).value
        val database = createDatabase(driver)
        repository = SqlDelightImmersionRepository(
            AndroidDatabaseHandler(
                db = database,
                driver = driver,
                queryDispatcher = Dispatchers.IO,
                transactionDispatcher = Dispatchers.IO,
            ),
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
            "immersion_daily_rollup",
            "immersion_lifetime_rollup",
            "immersion_applied_event",
            "immersion_goal",
            "immersion_goal_check_in",
            "immersion_goal_achievement",
            "immersion_import_ledger",
            "immersion_rollup_state",
            "immersion_sync_peer",
            "immersion_tombstone",
            "immersion_exclusion",
            "immersion_retention_state",
        )

        queryStrings(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'immersion_%'",
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
            queryImmersionSchema(migrationDriver) shouldContainExactly queryImmersionSchema(driver)
            assertLegacySessionConstraints(migrationDriver)
        } finally {
            migrationDriver.close()
        }
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
    fun `session deletion cascades events and exposures but retains reusable source`() = runTest {
        prepareSession()
        repository.appendExposure(exposure(sequence = 1, eventNumber = 1))

        repository.deleteSession(SESSION_ID) shouldBe true

        queryLong("SELECT count(*) FROM immersion_session") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_event") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_source_exposure") shouldBe 0
        queryLong("SELECT count(*) FROM immersion_source_unit") shouldBe 1
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

        observedSequences.toSet().all { it == 0L || it == 2L } shouldBe true
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
                            AND name IN ('immersion_session', 'immersion_source_unit')
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
