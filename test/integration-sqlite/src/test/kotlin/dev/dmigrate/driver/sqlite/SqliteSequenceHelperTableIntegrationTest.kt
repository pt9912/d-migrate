package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.SqliteNamedSequenceMode
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.sql.SQLException

/**
 * 0.9.7 SQLite-Sequence Phase C — Integrationstests gegen
 * in-memory SQLite. Plan §8.2: die generierte helper_table-DDL muss
 * gegen eine echte Datenbank ausführen und die Sequence-Semantik
 * (INSERT-Fälle, Boundary, Cycle, Erschöpfung, Multi-Sequence) muss
 * korrekt umgesetzt sein.
 *
 * Die Trigger-Bodies aus [SqliteSequenceEmulationTemplates] werden
 * gegen SQLite 3.x (via xerial sqlite-jdbc) ausgeführt; ein Spike-
 * Script ist nicht mehr nötig, sobald diese Tests grün laufen.
 */
class SqliteSequenceHelperTableIntegrationTest : FunSpec({

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

    fun simpleSequenceSchema(
        sequenceName: String = "order_seq",
        start: Long = 1000,
        increment: Long = 1,
        minValue: Long? = null,
        maxValue: Long? = null,
        cycle: Boolean = false,
    ): SchemaDefinition = SchemaDefinition(
        name = "seq-integration",
        version = "1.0.0",
        sequences = mapOf(
            sequenceName to SequenceDefinition(
                start = start,
                increment = increment,
                minValue = minValue,
                maxValue = maxValue,
                cycle = cycle,
            ),
        ),
        tables = mapOf(
            "orders" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "order_number" to ColumnDefinition(
                        type = NeutralType.BigInteger,
                        default = DefaultValue.SequenceNextVal(sequenceName),
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

    // ── Fall 1: INSERT ohne Sequence-Spalte ────────────────────────

    test("Fall 1 — INSERT omits sequence column → trigger assigns next_value") {
        val pool = newPool()
        installSchema(pool, simpleSequenceSchema(start = 1000))
        execDdl(pool, "INSERT INTO \"orders\" (\"id\", \"label\") VALUES (1, 'alpha')")
        readScalar<Long>(pool, "SELECT \"order_number\" FROM \"orders\" WHERE \"id\" = 1") shouldBe 1000L
        readScalar<Long>(pool, "SELECT \"next_value\" FROM \"dmg_sequences\" WHERE \"name\" = 'order_seq'") shouldBe 1001L
        readScalar<Long>(pool, "SELECT \"last_returned_value\" FROM \"dmg_sequences\" WHERE \"name\" = 'order_seq'") shouldBe 1000L
    }

    // ── Fall 2: INSERT mit explizitem NULL (W115 lossy) ────────────

    test("Fall 2 — INSERT NULL is treated as omitted (W115 lossy semantics)") {
        val pool = newPool()
        installSchema(pool, simpleSequenceSchema(start = 1000))
        execDdl(
            pool,
            "INSERT INTO \"orders\" (\"id\", \"order_number\", \"label\") VALUES (1, NULL, 'alpha')",
        )
        readScalar<Long>(pool, "SELECT \"order_number\" FROM \"orders\" WHERE \"id\" = 1") shouldBe 1000L
    }

    // ── Fall 3: INSERT mit explizitem Wert ─────────────────────────

    test("Fall 3 — INSERT with explicit non-NULL bypasses the trigger") {
        val pool = newPool()
        installSchema(pool, simpleSequenceSchema(start = 1000))
        execDdl(
            pool,
            "INSERT INTO \"orders\" (\"id\", \"order_number\", \"label\") VALUES (1, 9999, 'alpha')",
        )
        readScalar<Long>(pool, "SELECT \"order_number\" FROM \"orders\" WHERE \"id\" = 1") shouldBe 9999L
        // No sequence consumption on the explicit-value path.
        readScalar<Long>(pool, "SELECT \"next_value\" FROM \"dmg_sequences\" WHERE \"name\" = 'order_seq'") shouldBe 1000L
    }

    // ── Fall 4: DEFAULT VALUES ────────────────────────────────────

    test("Fall 4 — INSERT DEFAULT VALUES still triggers sequence assignment") {
        // INTEGER PRIMARY KEY + a sequence column: DEFAULT VALUES auto-fills
        // the PK via SQLite's rowid alias and the sequence column via the
        // _bi/_ai trigger pair (since no DEFAULT was emitted, NEW.v IS NULL).
        val schema = SchemaDefinition(
            name = "default-values",
            version = "1.0.0",
            sequences = mapOf("seq" to SequenceDefinition(start = 42)),
            tables = mapOf(
                "t" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true)),
                        "v" to ColumnDefinition(
                            type = NeutralType.BigInteger,
                            default = DefaultValue.SequenceNextVal("seq"),
                        ),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        val pool = newPool()
        installSchema(pool, schema)
        execDdl(pool, "INSERT INTO \"t\" DEFAULT VALUES")
        readScalar<Long>(pool, "SELECT \"v\" FROM \"t\"") shouldBe 42L
    }

    // ── Multi-Sequence pro Tabelle ─────────────────────────────────

    test("multi-sequence — two sequence-backed columns on the same table stay disjoint") {
        val pool = newPool()
        val schema = SchemaDefinition(
            name = "multi-seq",
            version = "1.0.0",
            sequences = mapOf(
                "seq_a" to SequenceDefinition(start = 100),
                "seq_b" to SequenceDefinition(start = 5000),
            ),
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = true),
                        "a_num" to ColumnDefinition(
                            type = NeutralType.BigInteger,
                            default = DefaultValue.SequenceNextVal("seq_a"),
                        ),
                        "b_num" to ColumnDefinition(
                            type = NeutralType.BigInteger,
                            default = DefaultValue.SequenceNextVal("seq_b"),
                        ),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        installSchema(pool, schema)
        execDdl(pool, "INSERT INTO \"orders\" (\"id\") VALUES (1)")
        execDdl(pool, "INSERT INTO \"orders\" (\"id\") VALUES (2)")

        readScalar<Long>(pool, "SELECT \"a_num\" FROM \"orders\" WHERE \"id\" = 1") shouldBe 100L
        readScalar<Long>(pool, "SELECT \"a_num\" FROM \"orders\" WHERE \"id\" = 2") shouldBe 101L
        readScalar<Long>(pool, "SELECT \"b_num\" FROM \"orders\" WHERE \"id\" = 1") shouldBe 5000L
        readScalar<Long>(pool, "SELECT \"b_num\" FROM \"orders\" WHERE \"id\" = 2") shouldBe 5001L
    }

    // ── Negativer Increment ────────────────────────────────────────

    test("descending sequence — negative increment yields decreasing values") {
        val pool = newPool()
        installSchema(
            pool,
            simpleSequenceSchema(start = 100, increment = -5, minValue = 0, maxValue = 100),
        )
        execDdl(pool, "INSERT INTO \"orders\" (\"id\") VALUES (1)")
        execDdl(pool, "INSERT INTO \"orders\" (\"id\") VALUES (2)")
        readScalar<Long>(pool, "SELECT \"order_number\" FROM \"orders\" WHERE \"id\" = 1") shouldBe 100L
        readScalar<Long>(pool, "SELECT \"order_number\" FROM \"orders\" WHERE \"id\" = 2") shouldBe 95L
    }

    // ── Cycle bei max_value ───────────────────────────────────────

    test("cycle — sequence resets to min_value after reaching max_value with cycle=true") {
        val pool = newPool()
        installSchema(
            pool,
            simpleSequenceSchema(start = 9, increment = 1, minValue = 1, maxValue = 10, cycle = true),
        )
        execDdl(pool, "INSERT INTO \"orders\" (\"id\") VALUES (1)") // 9
        execDdl(pool, "INSERT INTO \"orders\" (\"id\") VALUES (2)") // 10
        execDdl(pool, "INSERT INTO \"orders\" (\"id\") VALUES (3)") // cycle → 1

        readScalar<Long>(pool, "SELECT \"order_number\" FROM \"orders\" WHERE \"id\" = 1") shouldBe 9L
        readScalar<Long>(pool, "SELECT \"order_number\" FROM \"orders\" WHERE \"id\" = 2") shouldBe 10L
        readScalar<Long>(pool, "SELECT \"order_number\" FROM \"orders\" WHERE \"id\" = 3") shouldBe 1L
    }

    // ── Erschöpfung ohne cycle ────────────────────────────────────

    test("exhaustion — sequence without cycle raises ABORT on overshoot") {
        val pool = newPool()
        installSchema(
            pool,
            simpleSequenceSchema(start = 9, increment = 1, minValue = 1, maxValue = 10, cycle = false),
        )
        execDdl(pool, "INSERT INTO \"orders\" (\"id\") VALUES (1)") // 9
        execDdl(pool, "INSERT INTO \"orders\" (\"id\") VALUES (2)") // 10

        // Third insert sees exhausted=1 from the previous boundary check and
        // aborts.
        val ex = shouldThrow<SQLException> {
            execDdl(pool, "INSERT INTO \"orders\" (\"id\") VALUES (3)")
        }
        rootCauseMessage(ex) shouldContain "exhausted"
    }

    // ── Existenzprüfung in Trigger ────────────────────────────────

    test("safety — missing dmg_sequences row raises ABORT, not a silent NULL insert") {
        val pool = newPool()
        installSchema(pool, simpleSequenceSchema())
        // Manually remove the seed row to simulate operator-driven corruption.
        execDdl(pool, "DELETE FROM \"dmg_sequences\" WHERE \"name\" = 'order_seq'")
        val ex = shouldThrow<SQLException> {
            execDdl(pool, "INSERT INTO \"orders\" (\"id\") VALUES (1)")
        }
        rootCauseMessage(ex) shouldContain "not found"
    }
})

private fun rootCauseMessage(t: Throwable): String {
    var cur: Throwable? = t
    var deepest = t.message ?: ""
    while (cur != null) {
        val msg = cur.message
        if (!msg.isNullOrBlank()) deepest = msg
        cur = cur.cause
    }
    return deepest
}

private fun isCommentOnly(sql: String): Boolean =
    sql.lineSequence().all { line ->
        val t = line.trim()
        t.isEmpty() || t.startsWith("--")
    }

private fun execDdl(pool: ConnectionPool, vararg sqls: String) {
    pool.borrow().use { conn ->
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

private inline fun <reified T> readScalar(pool: ConnectionPool, query: String): T {
    pool.borrow().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery(query).use { rs ->
                check(rs.next()) { "empty result for query: $query" }
                @Suppress("UNCHECKED_CAST")
                return when (T::class) {
                    Long::class -> rs.getLong(1) as T
                    Int::class -> rs.getInt(1) as T
                    String::class -> rs.getString(1) as T
                    else -> error("unsupported scalar type ${T::class}")
                }
            }
        }
    }
}
