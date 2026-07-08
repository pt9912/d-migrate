package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.connection.asJdbc

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.SqliteNamedSequenceMode
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.sql.SQLException

/**
 * 0.9.7 SQLite-Sequence Phase D round-trip: generate
 * helper_table-DDL → install against `:memory:` → reverse-read →
 * verify that the reader filters out the helper objects and
 * reconstructs the sequence metadata + column-default bindings.
 */
class SqliteSequenceRoundTripIntegrationTest : FunSpec({

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

    fun helperTableOptions() = DdlGenerationOptions(
        dialectContext = DdlDialectContext.Sqlite(
            namedSequenceMode = SqliteNamedSequenceMode.HELPER_TABLE,
        ),
    )

    fun originalSchema(): SchemaDefinition = SchemaDefinition(
        name = "round-trip",
        version = "1.0.0",
        sequences = mapOf(
            "order_seq" to SequenceDefinition(
                start = 1000,
                increment = 2,
                minValue = 1,
                maxValue = 9999,
                cycle = true,
                cache = 20,
            ),
        ),
        tables = mapOf(
            "orders" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "order_number" to ColumnDefinition(
                        type = NeutralType.BigInteger,
                        default = DefaultValue.SequenceNextVal("order_seq"),
                    ),
                    "label" to ColumnDefinition(NeutralType.Text()),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    fun installSchema(pool: ConnectionPool, schema: SchemaDefinition) {
        val result = SqliteDdlGenerator().generate(schema, helperTableOptions())
        val sqls = result.statements
            .map { it.sql.trim() }
            .filter { it.isNotEmpty() && !isCommentOnly(it) }
        execDdl(pool, *sqls.toTypedArray())
    }

    test("round-trip: helper objects are filtered, sequences materialised, column default reconstructed") {
        val pool = newPool()
        val original = originalSchema()
        installSchema(pool, original)

        val reverse = SqliteSchemaReader().read(pool, SchemaReadOptions())

        // dmg_sequences disappears from the user-table map.
        reverse.schema.tables.shouldNotContainKey("dmg_sequences")
        reverse.schema.tables.shouldContainKey("orders")

        // Sequence-support triggers are filtered out of the trigger map.
        for ((key, _) in reverse.schema.triggers) {
            (key.contains("dmg_seq_")) shouldBe false
        }

        // Sequence metadata returned to the neutral model.
        val seq = reverse.schema.sequences["order_seq"]
        seq shouldNotBe null
        seq!!.increment shouldBe 2L
        seq.minValue shouldBe 1L
        seq.maxValue shouldBe 9999L
        seq.cycle shouldBe true
        seq.cache shouldBe 20

        // Column default is reconstructed as SequenceNextVal.
        val orderNumber = reverse.schema.tables["orders"]!!.columns["order_number"]!!
        orderNumber.default shouldBe DefaultValue.SequenceNextVal("order_seq")
    }

    test("reverse path emits W120 when a support trigger body is manually rewritten") {
        val pool = newPool()
        installSchema(pool, originalSchema())
        // Find the _bi trigger and drop+recreate it with the canonical
        // marker but a no-op body (no reference to dmg_sequences). The
        // marker stays authoritative, body integrity check fails →
        // W120.
        val biName = pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'dmg_seq_%_bi'",
                ).use { rs ->
                    check(rs.next())
                    rs.getString(1)
                }
            }
        }
        execDdl(pool, "DROP TRIGGER \"$biName\"")
        execDdl(
            pool,
            """
                CREATE TRIGGER "$biName"
                BEFORE INSERT ON "orders"
                FOR EACH ROW
                WHEN NEW."order_number" IS NULL
                BEGIN
                    /* d-migrate:sqlite-sequence-v1 object=sequence-trigger sequence=order_seq table=orders column=order_number */
                    SELECT 1;
                END;
            """.trimIndent(),
        )

        val reverse = SqliteSchemaReader().read(pool, SchemaReadOptions())
        reverse.notes.any { it.code == "W120" } shouldBe true
    }

    test("reverse path emits W116 when the marker is stripped from the _bi body") {
        val pool = newPool()
        installSchema(pool, originalSchema())
        val biName = pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'dmg_seq_%_bi'",
                ).use { rs ->
                    check(rs.next())
                    rs.getString(1)
                }
            }
        }
        execDdl(pool, "DROP TRIGGER \"$biName\"")
        // Re-create the trigger with the same name but NO marker
        // comment. The canonical name pattern matches → secondary
        // candidate → W116 since the _ai still has its marker.
        execDdl(
            pool,
            """
                CREATE TRIGGER "$biName"
                BEFORE INSERT ON "orders"
                FOR EACH ROW
                WHEN NEW."order_number" IS NULL
                BEGIN
                    UPDATE "dmg_sequences" SET "next_value" = "next_value" + 1
                        WHERE "name" = 'order_seq';
                END;
            """.trimIndent(),
        )

        val reverse = SqliteSchemaReader().read(pool, SchemaReadOptions())
        reverse.notes.any { it.code == "W116" } shouldBe true
    }
})

private fun isCommentOnly(sql: String): Boolean =
    sql.lineSequence().all { line ->
        val t = line.trim()
        t.isEmpty() || t.startsWith("--")
    }

private fun execDdl(pool: ConnectionPool, vararg sqls: String) {
    pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            for (sql in sqls) {
                try {
                    stmt.execute(sql)
                } catch (e: SQLException) {
                    throw SQLException(
                        "execDdl failed on statement:\n--- BEGIN ---\n$sql\n--- END ---",
                        e,
                    )
                }
            }
        }
    }
}
