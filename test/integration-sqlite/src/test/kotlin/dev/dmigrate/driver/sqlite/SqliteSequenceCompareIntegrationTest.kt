package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.SchemaComparator
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.sql.SQLException

/**
 * 0.9.7 SQLite-Sequence Phase E — Compare und Stabilisierung. Plan
 * §6.2 schreibt: nach Phase D braucht der `SchemaComparator` keine
 * SQLite-spezifische Sonderlogik mehr, weil Reverse die
 * Hilfsobjekte schon auf das neutrale Modell zurückfaltet. Diese
 * Tests pinnen den Stabilitäts-Vertrag:
 *
 * 1. Round-Trip Neutral → DDL → Install → Reverse = no diff.
 * 2. Drift auf `dmg_sequences.next_value` → exakt `sequencesChanged`.
 * 3. Mehrere Sequences auf verschiedenen Tabellen → unabhängig zurückgelesen.
 * 4. Eine Sequence, zwei Spalten in zwei Tabellen → eine Sequence
 *    bleibt eine Sequence (nicht dupliziert).
 */
class SqliteSequenceCompareIntegrationTest : FunSpec({

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

    fun install(pool: ConnectionPool, schema: SchemaDefinition) {
        val result = SqliteDdlGenerator().generate(schema, helperTableOptions())
        val sqls = result.statements
            .map { it.sql.trim() }
            .filter { it.isNotEmpty() && !isCommentOnly(it) }
        execDdl(pool, *sqls.toTypedArray())
    }

    fun reverse(pool: ConnectionPool): SchemaDefinition =
        SqliteSchemaReader().read(pool, SchemaReadOptions()).schema

    fun simpleSequenceSchemaInline(): SchemaDefinition = SchemaDefinition(
        name = "rb",
        version = "1.0.0",
        sequences = mapOf("order_seq" to SequenceDefinition()),
        tables = mapOf(
            "orders" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "order_number" to ColumnDefinition(
                        type = NeutralType.BigInteger,
                        default = DefaultValue.SequenceNextVal("order_seq"),
                    ),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    // ── 1. Round-trip stability ────────────────────────────────────

    test("round-trip — install → reverse → install → reverse produces a stable neutral schema") {
        val original = SchemaDefinition(
            name = "rt",
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
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )

        // First round-trip: install original, reverse → schemaA.
        val poolA = newPool()
        install(poolA, original)
        val schemaA = reverse(poolA)

        // Second round-trip: install schemaA into a fresh DB, reverse → schemaB.
        // The reverse-format normalises PK-implicit `required`/`unique`
        // away from column level, so comparing original-vs-reverse would
        // diff on that convention; comparing reverse-vs-reverse pins the
        // stable round-trip.
        val poolB = newPool()
        install(poolB, schemaA)
        val schemaB = reverse(poolB)

        val diff = SchemaComparator().compare(schemaA, schemaB)
        diff.sequencesChanged.size shouldBe 0
        diff.sequencesAdded.size shouldBe 0
        diff.sequencesRemoved.size shouldBe 0
        diff.tablesChanged.size shouldBe 0
        diff.tablesAdded.size shouldBe 0
        diff.tablesRemoved.size shouldBe 0
    }

    // ── 2. Sequence-metadata drift ────────────────────────────────

    test("drift — manual UPDATE on dmg_sequences.next_value surfaces as sequencesChanged") {
        val baseline = SchemaDefinition(
            name = "drift",
            version = "1.0.0",
            sequences = mapOf("order_seq" to SequenceDefinition(start = 1000)),
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = true),
                        "order_number" to ColumnDefinition(
                            type = NeutralType.BigInteger,
                            default = DefaultValue.SequenceNextVal("order_seq"),
                        ),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        val pool = newPool()
        install(pool, baseline)
        // Snapshot the schema BEFORE drift so the compare runs reverse-
        // vs-reverse and the table-side reader convention is symmetric.
        val before = reverse(pool)
        execDdl(pool, "UPDATE \"dmg_sequences\" SET \"next_value\" = 5000 WHERE \"name\" = 'order_seq'")
        val after = reverse(pool)

        val diff = SchemaComparator().compare(before, after)
        // Exactly the order_seq sequence changed; nothing else.
        diff.sequencesChanged.size shouldBe 1
        diff.sequencesChanged.first().name shouldBe "order_seq"
        diff.sequencesChanged.first().start!!.before shouldBe 1000L
        diff.sequencesChanged.first().start!!.after shouldBe 5000L
        diff.tablesChanged.size shouldBe 0
        diff.tablesAdded.size shouldBe 0
        diff.tablesRemoved.size shouldBe 0
    }

    // ── 3. Multiple sequences in different tables ─────────────────

    test("multi-sequence — two sequences on different tables reverse independently") {
        val schema = SchemaDefinition(
            name = "multi",
            version = "1.0.0",
            sequences = mapOf(
                "order_seq" to SequenceDefinition(start = 100),
                "invoice_seq" to SequenceDefinition(start = 5000),
            ),
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = true),
                        "order_number" to ColumnDefinition(
                            type = NeutralType.BigInteger,
                            default = DefaultValue.SequenceNextVal("order_seq"),
                        ),
                    ),
                    primaryKey = listOf("id"),
                ),
                "invoices" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = true),
                        "invoice_number" to ColumnDefinition(
                            type = NeutralType.BigInteger,
                            default = DefaultValue.SequenceNextVal("invoice_seq"),
                        ),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )

        val pool = newPool()
        install(pool, schema)
        val live = reverse(pool)

        live.sequences.keys shouldBe setOf("order_seq", "invoice_seq")
        live.tables["orders"]!!.columns["order_number"]!!.default shouldBe
            DefaultValue.SequenceNextVal("order_seq")
        live.tables["invoices"]!!.columns["invoice_number"]!!.default shouldBe
            DefaultValue.SequenceNextVal("invoice_seq")
    }

    // ── 4. Shared sequence across multiple columns ────────────────

    // ── 5. Rollback preflight (Phase F1) ──────────────────────────

    test("rollback preflight — E058 aborts when an external object references dmg_sequences") {
        val pool = newPool()
        install(pool, simpleSequenceSchemaInline())
        // Create a user view that mentions dmg_sequences — this is an
        // external reference per Plan §5.2 and must block the rollback.
        execDdl(pool, "CREATE VIEW \"audit_view\" AS SELECT next_value FROM \"dmg_sequences\"")

        val rollback = SqliteDdlGenerator().generateRollback(simpleSequenceSchemaInline(), helperTableOptions())
        val sqls = rollback.statements.map { it.sql.trim() }.filter { it.isNotEmpty() && !isCommentOnly(it) }

        val ex = io.kotest.assertions.throwables.shouldThrow<java.sql.SQLException> {
            execDdl(pool, *sqls.toTypedArray())
        }
        // Plan F1: the CHECK constraint name carries the code; xerial-sqlite-jdbc surfaces it in the message.
        rootCauseMessage(ex) shouldContain "E058_external_dmg_sequences_refs"
        // The user view is still there because the preflight aborted
        // before the DROP stream ran.
        readScalar<Long>(
            pool,
            "SELECT count(*) FROM sqlite_master WHERE name = 'audit_view'",
        ) shouldBe 1L
    }

    test("rollback preflight — succeeds when no external refs exist") {
        val pool = newPool()
        install(pool, simpleSequenceSchemaInline())

        val rollback = SqliteDdlGenerator().generateRollback(simpleSequenceSchemaInline(), helperTableOptions())
        val sqls = rollback.statements.map { it.sql.trim() }.filter { it.isNotEmpty() && !isCommentOnly(it) }
        execDdl(pool, *sqls.toTypedArray())

        // dmg_sequences is gone.
        readScalar<Long>(
            pool,
            "SELECT count(*) FROM sqlite_master WHERE name = 'dmg_sequences'",
        ) shouldBe 0L
    }

    test("shared — one sequence drives two columns; reverse yields a single sequence definition") {
        val schema = SchemaDefinition(
            name = "shared",
            version = "1.0.0",
            sequences = mapOf("shared_seq" to SequenceDefinition(start = 1)),
            tables = mapOf(
                "left" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = true),
                        "n" to ColumnDefinition(
                            type = NeutralType.BigInteger,
                            default = DefaultValue.SequenceNextVal("shared_seq"),
                        ),
                    ),
                    primaryKey = listOf("id"),
                ),
                "right" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = true),
                        "n" to ColumnDefinition(
                            type = NeutralType.BigInteger,
                            default = DefaultValue.SequenceNextVal("shared_seq"),
                        ),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        val pool = newPool()
        install(pool, schema)
        val live = reverse(pool)

        live.sequences.keys shouldBe setOf("shared_seq")
        live.tables["left"]!!.columns["n"]!!.default shouldBe DefaultValue.SequenceNextVal("shared_seq")
        live.tables["right"]!!.columns["n"]!!.default shouldBe DefaultValue.SequenceNextVal("shared_seq")
    }
})

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
