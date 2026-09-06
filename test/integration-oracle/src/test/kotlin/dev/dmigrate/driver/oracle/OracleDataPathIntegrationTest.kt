package dev.dmigrate.driver.oracle

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.oracle.OracleContainer
import java.time.Duration
import java.time.OffsetDateTime

/**
 * Datenpfad gegen echtes Oracle (ADR 0052 Slice 3) -- ALWAYS/BY-DEFAULT-
 * Identity-Toggle, MERGE-Konfliktmodi, `ALTER SEQUENCE ... RESTART`-Reseed,
 * CLOB/BLOB-Materialisierung, TIMESTAMP WITH TIME ZONE und FK-Disable
 * (per-Constraint statt globalem Schalter). Deckt genau die Annahmen ab,
 * die ohne echten Treiber nicht verifizierbar waren (siehe
 * docs/planning/in-progress/oracle-dialect-scoping.md, Slice-3-Notiz) --
 * zwei davon (`OVERRIDING SYSTEM VALUE` existiert fuer Oracle nicht;
 * `TIMESTAMP WITH TIME ZONE` liefert `oracle.sql.TIMESTAMPTZ`, kein
 * Standardtyp) waren falsch angenommen und wurden hier live widerlegt.
 */
class OracleDataPathIntegrationTest : FunSpec({

    val container = OracleContainer("gvenzl/oracle-free:23-slim-faststart")
        .withStartupTimeout(Duration.ofMinutes(5))

    lateinit var pool: ConnectionPool

    fun column(name: String) = ColumnDescriptor(name = name, nullable = true)

    fun chunk(table: String, columns: List<String>, vararg rows: Array<Any?>) = DataChunk(
        table = table,
        columns = columns.map(::column),
        rows = rows.toList(),
        chunkIndex = 0,
    )

    fun normalizeForAssertion(value: Any?): Any? = when (value) {
        is java.math.BigDecimal -> if (value.scale() <= 0) value.toInt() else value
        is java.sql.Clob -> value.getSubString(1, value.length().toInt())
        else -> value
    }

    /**
     * Roh-JDBC-Verifikation ausserhalb des Reader/Writer-Pfads. Normalisiert
     * zwei treibereigene Rueckgabetypen, die [OracleDataReader.mapValue] fuer
     * genau diesen Pfad nicht sieht (das ist bewusst reines Verifikations-SQL,
     * kein Import): Oracle-`NUMBER` liefert `getObject()` als `BigDecimal`
     * (keine Ganzzahl-Typen) und `CLOB` als Live-Locator.
     */
    fun queryRows(sql: String): List<List<Any?>> =
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    buildList {
                        while (rs.next()) {
                            add((1..rs.metaData.columnCount).map { normalizeForAssertion(rs.getObject(it)) })
                        }
                    }
                }
            }
        }

    fun importChunk(
        table: String,
        columns: List<String>,
        rows: List<Array<Any?>>,
        options: ImportOptions = ImportOptions(),
    ) = OracleDataWriter().openTable(pool, table, options).use { session ->
        val result = session.write(DataChunk(table, columns.map(::column), rows, 0))
        session.commitChunk()
        val finish = session.finishTable()
        result to finish
    }

    beforeSpec {
        container.start()
        DatabaseDriverRegistry.register(OracleDriver())
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.ORACLE,
                host = container.host,
                port = container.oraclePort,
                database = container.databaseName,
                user = container.username,
                password = container.password,
            ),
        )
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                // Durchgehend quoted-lowercase, wie es der reale schema-generate-Pfad
                // erzeugt (OracleDdlGenerator quotet jeden Bezeichner) -- Oracle faltet
                // UNQUOTED Bezeichner auf GROSSSCHREIBUNG, was den Schreibpfad (der
                // konsequent quoted-lowercase adressiert) sonst mit ORA-00942 treffen
                // wuerde (dieselbe Falle wie im Slice-2-Review, hier im Testaufbau).
                stmt.execute(
                    """
                    CREATE TABLE "customers" (
                        "id" NUMBER(9) GENERATED ALWAYS AS IDENTITY,
                        "name" VARCHAR2(100) NOT NULL,
                        "state" VARCHAR2(20),
                        CONSTRAINT "pk_customers" PRIMARY KEY ("id")
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE "customers_bd" (
                        "id" NUMBER(9) GENERATED BY DEFAULT AS IDENTITY,
                        "name" VARCHAR2(100) NOT NULL,
                        CONSTRAINT "pk_customers_bd" PRIMARY KEY ("id")
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    "CREATE TABLE \"plain\" (\"id\" NUMBER(9) PRIMARY KEY, \"label\" VARCHAR2(50))",
                )
                stmt.execute(
                    "CREATE TABLE \"docs\" (\"id\" NUMBER(9) PRIMARY KEY, \"body\" CLOB, \"blob_data\" BLOB)",
                )
                stmt.execute(
                    "CREATE TABLE \"events\" (\"id\" NUMBER(9) PRIMARY KEY, \"happened_at\" TIMESTAMP WITH TIME ZONE)",
                )
                stmt.execute("CREATE TABLE \"parent\" (\"id\" NUMBER(9) PRIMARY KEY)")
                stmt.execute(
                    """
                    CREATE TABLE "child" (
                        "id" NUMBER(9) PRIMARY KEY,
                        "parent_id" NUMBER(9) NOT NULL,
                        CONSTRAINT "fk_child_parent" FOREIGN KEY ("parent_id") REFERENCES "parent"("id")
                    )
                    """.trimIndent(),
                )
                stmt.execute("INSERT INTO \"parent\" (\"id\") VALUES (1)")
            }
        }
    }

    afterSpec {
        pool.close()
        container.stop()
    }

    test("insert with an explicit GENERATED ALWAYS identity value toggles to BY DEFAULT and reseeds afterwards") {
        val (result, finish) = importChunk(
            table = "customers",
            columns = listOf("id", "name", "state"),
            rows = listOf(arrayOf<Any?>(5, "alice", "open"), arrayOf<Any?>(9, "bob", null)),
        )
        result.rowsInserted shouldBe 2
        finish.shouldBeSuccessWithReseed(expectedNext = 10L)

        queryRows("SELECT \"id\", \"name\", \"state\" FROM \"customers\" ORDER BY \"id\"") shouldContainExactly listOf(
            listOf(5, "alice", "open"),
            listOf(9, "bob", null),
        )

        // Der Reseed muss greifen: die naechste server-vergebene Nummer folgt auf 9.
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { it.execute("INSERT INTO \"customers\" (\"name\") VALUES ('carol')") }
        }
        queryRows("SELECT \"id\" FROM \"customers\" WHERE \"name\" = 'carol'") shouldContainExactly listOf(listOf(10))
    }

    test("a GENERATED BY DEFAULT identity column accepts explicit values without any toggle DDL") {
        val (result, finish) = importChunk(
            table = "customers_bd",
            columns = listOf("id", "name"),
            rows = listOf(arrayOf<Any?>(3, "dan")),
        )
        result.rowsInserted shouldBe 1
        finish.shouldBeSuccessWithReseed(expectedNext = 4L)

        queryRows("SELECT \"id\", \"name\" FROM \"customers_bd\" ORDER BY \"id\"") shouldContainExactly
            listOf(listOf(3, "dan"))

        // Kollisionsfrei weiter: kein Toggle bedeutet auch kein staendiges Um-
        // und Zurueckschalten, nur das reguläre Reseed muss wirken.
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { it.execute("INSERT INTO \"customers_bd\" (\"name\") VALUES ('eve')") }
        }
        queryRows("SELECT \"id\" FROM \"customers_bd\" WHERE \"name\" = 'eve'") shouldContainExactly listOf(listOf(4))
    }

    test("skip counts inserted vs skipped exactly; update cannot distinguish and reports rowsUnknown") {
        importChunk("plain", listOf("id", "label"), listOf(arrayOf<Any?>(1, "one")))

        val (skip, _) = importChunk(
            "plain", listOf("id", "label"),
            listOf(arrayOf<Any?>(1, "ignored"), arrayOf<Any?>(2, "two")),
            ImportOptions(onConflict = OnConflict.SKIP),
        )
        skip.rowsInserted shouldBe 1
        skip.rowsSkipped shouldBe 1
        queryRows("SELECT \"label\" FROM \"plain\" WHERE \"id\" = 1") shouldContainExactly listOf(listOf("one"))

        val (update, _) = importChunk(
            "plain", listOf("id", "label"),
            listOf(arrayOf<Any?>(1, "updated"), arrayOf<Any?>(3, "three")),
            ImportOptions(onConflict = OnConflict.UPDATE),
        )
        update.rowsUnknown shouldBe 2
        queryRows("SELECT \"id\", \"label\" FROM \"plain\" ORDER BY \"id\"") shouldContainExactly listOf(
            listOf(1, "updated"), listOf(2, "two"), listOf(3, "three"),
        )
    }

    test("CLOB and BLOB round-trip as String/ByteArray through reader and writer, not live locators") {
        pool.borrow().asJdbc().use { conn ->
            conn.prepareStatement("INSERT INTO \"docs\" (\"id\", \"body\", \"blob_data\") VALUES (?, ?, ?)").use { ps ->
                ps.setInt(1, 1)
                ps.setString(2, "hello clob")
                ps.setBytes(3, byteArrayOf(1, 2, 3, 4))
                ps.execute()
            }
        }

        val chunks = OracleDataReader().streamTable(pool, "docs").use { it.toList() }
        val row = chunks.flatMap { it.rows }.single()
        row[1] shouldBe "hello clob"
        (row[2] as ByteArray).toList() shouldBe listOf<Byte>(1, 2, 3, 4)

        // Zurueckschreiben mit den materialisierten Werten funktioniert normal.
        importChunk("docs", listOf("id", "body", "blob_data"), listOf(arrayOf(2, row[1], row[2])))
        queryRows("SELECT \"body\" FROM \"docs\" WHERE \"id\" = 2") shouldContainExactly listOf(listOf("hello clob"))
    }

    test("TIMESTAMP WITH TIME ZONE round-trips through the reader as a standard OffsetDateTime") {
        val moment = OffsetDateTime.parse("2026-08-22T14:00:00+02:00")
        pool.borrow().asJdbc().use { conn ->
            conn.prepareStatement("INSERT INTO \"events\" (\"id\", \"happened_at\") VALUES (?, ?)").use { ps ->
                ps.setInt(1, 1)
                ps.setObject(2, moment)
                ps.execute()
            }
        }

        val chunks = OracleDataReader().streamTable(pool, "events").use { it.toList() }
        val value = chunks.flatMap { it.rows }.single()[1]
        // Muss ein Standardtyp sein (kein oracle.sql.TIMESTAMPTZ-Wrapper) --
        // sonst waere weder --verify-Kanonisierung noch der Export sauber moeglich.
        value.shouldBeInstanceOf<OffsetDateTime>()
        value.toInstant() shouldBe moment.toInstant()
    }

    test("truncateTables clears FK-referenced tables and the writer restores the constraint") {
        importChunk("child", listOf("id", "parent_id"), listOf(arrayOf<Any?>(1, 1)))
        queryRows("SELECT COUNT(*) FROM \"child\"") shouldContainExactly listOf(listOf(1))

        OracleDataWriter().truncateTables(pool, listOf("child"))
        queryRows("SELECT COUNT(*) FROM \"child\"") shouldContainExactly listOf(listOf(0))

        // FK ist wieder scharf: eine verwaiste Zeile muss abgelehnt werden.
        val orphanRejected = runCatching {
            importChunk("child", listOf("id", "parent_id"), listOf(arrayOf<Any?>(2, 999)))
        }.isFailure
        orphanRejected shouldBe true
    }

    test("disableFkChecks lets an orphan row through, and re-enables the constraint on finish") {
        val (result, finish) = importChunk(
            "child", listOf("id", "parent_id"), listOf(arrayOf<Any?>(3, 999)),
            ImportOptions(disableFkChecks = true, reseedSequences = false),
        )
        result.rowsInserted shouldBe 1
        // Re-Enable darf am waehrend der Deaktivierung eingefuegten,
        // bewusst nicht constraint-konformen Bestand NICHT scheitern
        // (ENABLE NOVALIDATE statt Oracles Default VALIDATE).
        finish.shouldBeInstanceOf<FinishTableResult.Success>()
        queryRows("SELECT \"id\" FROM \"child\" WHERE \"id\" = 3") shouldContainExactly listOf(listOf(3))

        // Aufgeraeumt: die Constraint ist wieder scharf.
        val orphanRejected = runCatching {
            importChunk("child", listOf("id", "parent_id"), listOf(arrayOf<Any?>(4, 999)))
        }.isFailure
        orphanRejected shouldBe true

        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM \"child\" WHERE \"id\" = 3") }
        }
    }

    test("streaming an empty table still yields the column header") {
        val chunks = OracleDataReader().streamTable(pool, "parent").use { it.toList() }
        chunks shouldHaveSize 1
        chunks.single().columns.map { it.name } shouldContainExactly listOf("id")
        // `parent` traegt eine Zeile aus dem Setup -- die Header-Garantie zaehlt
        // unabhaengig davon; keine weitere Assertion auf leere Rows hier.
    }

    test("streaming a genuinely empty table yields an empty-rows chunk") {
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM \"plain\"") }
        }
        val chunks = OracleDataReader().streamTable(pool, "plain").use { it.toList() }
        chunks shouldHaveSize 1
        chunks.single().rows.shouldBeEmpty()
    }
})

private fun FinishTableResult.shouldBeSuccessWithReseed(expectedNext: Long) {
    val success = this as FinishTableResult.Success
    success.adjustments.single().newValue shouldBe expectedNext
}
