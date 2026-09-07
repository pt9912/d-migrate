package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * Der Rueckweg eines Ausdrucks-Index gegen echtes SQLite.
 *
 * `PRAGMA index_xinfo` meldet fuer eine Ausdrucksposition `name = NULL` — ein
 * Index ueber `(a, UPPER(nm))` kam deshalb als `(a)` zurueck, und einer nur
 * ueber einem Ausdruck fiel ganz heraus. Beides ohne Meldung.
 *
 * Was kein Unit-Test zeigen kann: welche Form SQLite in `sqlite_master.sql`
 * wirklich ablegt. Der Server speichert die Anweisung so, wie sie gesendet
 * wurde — der Scanner arbeitet also auf gemessenem Text, nicht auf einer
 * normalisierten Zusage.
 */
class SqliteExpressionIndexReverseIntegrationTest : FunSpec({

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
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt -> for (sql in sqls) stmt.execute(sql) }
        }
    }

    test("an index over an expression comes back whole, key by key") {
        newPool().use { pool ->
            execDdl(
                pool,
                "CREATE TABLE t (a INTEGER, nm TEXT)",
                "CREATE INDEX ix_mixed ON t(a, UPPER(nm))",
                "CREATE INDEX ix_expr ON t(LOWER(nm))",
                "CREATE INDEX ix_plain ON t(a)",
                "CREATE INDEX ix_desc ON t(a DESC, LOWER(nm) COLLATE NOCASE)",
                "CREATE INDEX ix_where ON t(LOWER(nm)) WHERE a > 0",
            )

            val indices = SqliteSchemaReader().read(pool).schema
                .tables.getValue("t").indices.associateBy { it.name }

            withClue("gelesene Indizes: ${indices.keys}") {
                indices.keys shouldContainExactly setOf("ix_mixed", "ix_expr", "ix_plain", "ix_desc", "ix_where")
            }

            // Der Kern: die Ausdrucksposition traegt ihren Text, die
            // Spaltenposition ihren Namen.
            val mixed = indices.getValue("ix_mixed")
            mixed.columns.map { it.expression } shouldBe listOf(null, "UPPER(nm)")
            // `columnNames` laesst Ausdruecke aus -- das Etikett in `name` ist
            // keine Spalte und darf nirgends als eine gelesen werden.
            mixed.columnNames shouldContainExactly listOf("a")

            indices.getValue("ix_expr").columns.single().expression shouldBe "LOWER(nm)"
            // Eine gewoehnliche Spalte bleibt eine gewoehnliche Spalte.
            indices.getValue("ix_plain").columnNames shouldContainExactly listOf("a")
            indices.getValue("ix_plain").columns.single().expression shouldBe null

            // Richtung und COLLATE stehen hinter dem Schluessel und gehoeren
            // nicht in den Ausdruck -- sonst entstuende beim Generate ein
            // Ausdruck, den kein Zieldialekt kennt.
            indices.getValue("ix_desc").columns.map { it.expression } shouldBe listOf(null, "LOWER(nm)")

            // Die WHERE-Klausel darf nicht in die Schluesselliste geraten.
            indices.getValue("ix_where").columns.single().expression shouldBe "LOWER(nm)"
            indices.getValue("ix_where").where shouldBe "a > 0"
        }
    }
})
