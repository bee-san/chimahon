package tachiyomi.data.immersion

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.data.Database

/**
 * Guards the migration that introduces the immersion statistics schema.
 *
 * A shipped migration is immutable, and `47.sqm` creates `search_history`.
 * Anything that re-creates that table in a later migration breaks upgrades for
 * every existing install: the replay hits `table search_history already exists`
 * and the app can no longer open its database. A schema test that starts from a
 * fresh database cannot catch that, because the collision only appears on the
 * upgrade path — hence the explicit version-to-version migrations below.
 */
class ImmersionSchemaMigrationTest {

    private val immersionTables = listOf(
        "immersion_title",
        "immersion_session",
        "immersion_source_unit",
        "immersion_event",
        "immersion_character",
        "immersion_character_occurrence",
        "immersion_anki_operation",
        "immersion_anki_character",
        "immersion_goal",
        "immersion_daily_rollup",
        "immersion_hourly_rollup",
        "immersion_lifetime_rollup",
        "immersion_rollup_dirty",
        "immersion_applied_event",
        "immersion_rollup_state",
        "immersion_tombstone",
        "immersion_import_ledger",
        "immersion_merge_conflict",
        "immersion_portable_merge_checkpoint",
    )

    /** Tables belonging to the dropped word/vocabulary, lookup, and FTS scope. */
    private val droppedTables = listOf(
        "immersion_word",
        "immersion_word_occurrence",
        "immersion_lookup",
        "immersion_source_fts",
    )

    @Test
    fun `adding the immersion schema preserves search history from the previous migration`() {
        withDriver { driver ->
            driver.migrate(from = SEARCH_HISTORY_VERSION, to = SEARCH_HISTORY_VERSION + 1)
            driver.execute(
                null,
                "INSERT INTO search_history(scope, query, last_searched_at) VALUES ('manga', 'yotsuba', 1)",
                0,
            )

            driver.migrate(from = IMMERSION_VERSION, to = IMMERSION_VERSION + 1)

            driver.selectStrings("SELECT query FROM search_history") shouldBe listOf("yotsuba")
            driver.tableNames() shouldContainAll immersionTables
        }
    }

    @Test
    fun `the immersion migration replays over the shipped migration that precedes it`() {
        withDriver { driver ->
            driver.migrate(from = SEARCH_HISTORY_VERSION, to = IMMERSION_VERSION + 1)

            driver.tableNames() shouldContainAll (immersionTables + "search_history")
        }
    }

    @Test
    fun `a fresh install gets the immersion schema and none of the dropped tables`() {
        withDriver { driver ->
            Database.Schema.create(driver).value

            val tables = driver.tableNames()
            tables shouldContainAll immersionTables
            tables shouldNotContainAnyOf droppedTables
        }
    }

    @Test
    fun `the immersion migration creates none of the dropped tables`() {
        withDriver { driver ->
            driver.migrate(from = IMMERSION_VERSION, to = IMMERSION_VERSION + 1)

            driver.tableNames() shouldNotContainAnyOf droppedTables
        }
    }

    @Test
    fun `the rollup engine finds its seed state row`() {
        withDriver { driver ->
            driver.migrate(from = IMMERSION_VERSION, to = IMMERSION_VERSION + 1)

            driver.selectStrings("SELECT scope_key FROM immersion_rollup_state") shouldBe listOf("global")
        }
    }

    /**
     * Card counting stays idempotent because of a unique partial index on
     * `(note_id, type)`. Without it a retried Anki create inflates
     * `cards_created`, so assert the index survives the migration.
     */
    @Test
    fun `a repeated successful card operation for the same note is rejected`() {
        withDriver { driver ->
            driver.migrate(from = IMMERSION_VERSION, to = IMMERSION_VERSION + 1)

            fun insertCreate(id: String) = driver.execute(
                null,
                """
                INSERT INTO immersion_anki_operation(id, type, status, success, note_id, occurred_at)
                VALUES ('$id', 'CREATE', 'SUCCESS', 1, 7, 0)
                """.trimIndent(),
                0,
            )

            insertCreate("op-1")
            runCatching { insertCreate("op-2") }.isFailure shouldBe true

            driver.selectStrings("SELECT id FROM immersion_anki_operation") shouldBe listOf("op-1")
        }
    }

    private fun <T> withDriver(block: (SqlDriver) -> T): T {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return try {
            block(driver)
        } finally {
            driver.close()
        }
    }

    private fun SqlDriver.migrate(from: Long, to: Long) {
        Database.Schema.migrate(this, from, to).value
    }

    private fun SqlDriver.tableNames(): List<String> =
        selectStrings("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")

    private fun SqlDriver.selectStrings(sql: String): List<String> =
        executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                val values = mutableListOf<String>()
                while (cursor.next().value) {
                    values += cursor.getString(0)!!
                }
                QueryResult.Value(values.toList())
            },
            parameters = 0,
        ).value

    private companion object {
        /** Migration that creates `search_history`; already shipped, so immutable. */
        const val SEARCH_HISTORY_VERSION = 47L

        /** Migration that adds the immersion statistics schema. */
        const val IMMERSION_VERSION = 48L
    }
}
