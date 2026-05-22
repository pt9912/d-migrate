package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Live-DB integration smoke for the SQLite trigger reverse-read.
 *
 * Plan §6 Sub-Slice D: "Trigger anlegen, Reverse-Read, Compare
 * bestaetigt Identitaet der Trigger-Definition". The reader is
 * exercised via `SqliteSchemaReader` against an in-memory SQLite DB
 * (Hikari with maximumPoolSize = 1 so the same connection survives
 * across the trigger CREATE, the reverse-read, and the compare).
 * A file-side `SchemaDefinition` built from the expected trigger
 * shape is compared to the reverse-read result via
 * `SchemaComparator` — `triggersChanged` must be empty, i.e. the
 * reader produces what the user would have written by hand.
 */
class SqliteTriggerReverseReadIntegrationTest : FunSpec({

    fun newPool(): ConnectionPool = HikariConnectionPoolFactory.create(
        ConnectionConfig(
            dialect = DatabaseDialect.SQLITE,
            host = null,
            port = null,
            database = ":memory:",
            user = null,
            password = null,
        ),
    )

    fun execDdl(pool: ConnectionPool, vararg sqls: String) {
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt -> for (sql in sqls) stmt.execute(sql) }
        }
    }

    fun fileSchemaWith(trigger: TriggerDefinition, triggerName: String = "trg"): SchemaDefinition =
        SchemaDefinition(
            name = "file-schema",
            version = "0",
            tables = mapOf(
                "t" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = false),
                        "name" to ColumnDefinition(NeutralType.Text(), required = false),
                    ),
                    primaryKey = listOf("id"),
                ),
                "log" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = false),
                        "ts" to ColumnDefinition(NeutralType.Text(), required = false),
                    ),
                ),
            ),
            triggers = mapOf("t::$triggerName" to trigger),
        )

    test("AFTER INSERT trigger reverse-reads identical to the file-side definition (no diff)") {
        val pool = newPool()
        try {
            execDdl(
                pool,
                "CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)",
                "CREATE TABLE log (id INTEGER, ts TEXT)",
                "CREATE TRIGGER trg AFTER INSERT ON t BEGIN INSERT INTO log (id) VALUES (NEW.id); END",
            )
            val live = SqliteSchemaReader().read(pool, SchemaReadOptions(includeTriggers = true)).schema
            val expected = fileSchemaWith(
                TriggerDefinition(
                    table = "t",
                    event = TriggerEvent.INSERT,
                    timing = TriggerTiming.AFTER,
                    forEach = TriggerForEach.ROW,
                    body = "INSERT INTO log (id) VALUES (NEW.id)",
                    sourceDialect = "sqlite",
                ),
            )
            val diff = SchemaComparator().compare(live, expected)
            diff.triggersAdded.shouldBeEmpty()
            diff.triggersRemoved.shouldBeEmpty()
            diff.triggersChanged.shouldBeEmpty()
        } finally {
            pool.close()
        }
    }

    test("WHEN-clause trigger reverse-reads identical (condition populated)") {
        val pool = newPool()
        try {
            execDdl(
                pool,
                "CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)",
                "CREATE TABLE log (id INTEGER, ts TEXT)",
                """
                CREATE TRIGGER trg AFTER UPDATE ON t
                  FOR EACH ROW WHEN NEW.name <> OLD.name
                BEGIN
                  UPDATE log SET ts = CURRENT_TIMESTAMP WHERE id = NEW.id;
                END
                """.trimIndent(),
            )
            val live = SqliteSchemaReader().read(pool, SchemaReadOptions(includeTriggers = true)).schema
            val expected = fileSchemaWith(
                TriggerDefinition(
                    table = "t",
                    event = TriggerEvent.UPDATE,
                    timing = TriggerTiming.AFTER,
                    forEach = TriggerForEach.ROW,
                    condition = "NEW.name <> OLD.name",
                    body = "UPDATE log SET ts = CURRENT_TIMESTAMP WHERE id = NEW.id",
                    sourceDialect = "sqlite",
                ),
            )
            val diff = SchemaComparator().compare(live, expected)
            diff.triggersChanged.shouldBeEmpty()
        } finally {
            pool.close()
        }
    }

    test("INSTEAD OF trigger on view reverse-reads identical") {
        val pool = newPool()
        try {
            execDdl(
                pool,
                "CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)",
                "CREATE TABLE log (id INTEGER, ts TEXT)",
                "CREATE VIEW vt AS SELECT id, name FROM t",
                "CREATE TRIGGER trg INSTEAD OF DELETE ON vt BEGIN DELETE FROM t WHERE id = OLD.id; END",
            )
            val live = SqliteSchemaReader().read(pool, SchemaReadOptions(includeTriggers = true)).schema
            // Note: the trigger is on view `vt`, not table `t` — adjust the
            // file-side definition's `table` accordingly. The view also
            // shows up in `live.views`, so the file-side schema needs it
            // too, otherwise the compare would surface a missing view.
            val expected = SchemaDefinition(
                name = "file-schema",
                version = "0",
                tables = mapOf(
                    "t" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.Integer, required = false),
                            "name" to ColumnDefinition(NeutralType.Text(), required = false),
                        ),
                        primaryKey = listOf("id"),
                    ),
                    "log" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.Integer, required = false),
                            "ts" to ColumnDefinition(NeutralType.Text(), required = false),
                        ),
                    ),
                ),
                triggers = mapOf(
                    "vt::trg" to TriggerDefinition(
                        table = "vt",
                        event = TriggerEvent.DELETE,
                        timing = TriggerTiming.INSTEAD_OF,
                        forEach = TriggerForEach.ROW,
                        body = "DELETE FROM t WHERE id = OLD.id",
                        sourceDialect = "sqlite",
                    ),
                ),
            )
            val diff = SchemaComparator().compare(live, expected)
            diff.triggersChanged.shouldBeEmpty()
        } finally {
            pool.close()
        }
    }
})
