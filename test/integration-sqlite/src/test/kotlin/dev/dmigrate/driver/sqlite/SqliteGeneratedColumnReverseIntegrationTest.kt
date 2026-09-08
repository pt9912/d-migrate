package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.metadata.GeneratedColumnNotes
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * Was SQLite mit einer generierten Spalte macht — und was der Reverse darueber
 * sagt.
 *
 * `PRAGMA table_info` blendet sie aus: sie zaehlt dort als versteckt. Die
 * Spalte fehlt im Modell deshalb **ganz**, nicht nur ihr Ausdruck. Ein
 * `schema generate` erzeugte daraus eine Tabelle mit fehlenden Spalten, und
 * ein Vergleich plante sie bei jedem Lauf erneut als fehlend.
 *
 * Solange das neutrale Modell keine Form fuer berechnete Spalten hat, ist die
 * Meldung das Einzige, was dazwischensteht. Nachtragen als **gewoehnliche**
 * Spalte waere schlimmer: sie waere beschreibbar, und der naechste Import
 * schriebe hinein, was der Server selbst berechnen wollte.
 */
class SqliteGeneratedColumnReverseIntegrationTest : FunSpec({

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

    test("a generated column is absent from the model, and the reverse says so") {
        val pool = newPool()
        try {
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { s ->
                    s.execute(
                        """CREATE TABLE order_line (
                             id INTEGER PRIMARY KEY,
                             qty INTEGER NOT NULL,
                             price REAL NOT NULL,
                             total REAL GENERATED ALWAYS AS (qty * price) STORED,
                             virt REAL GENERATED ALWAYS AS (qty + 1) VIRTUAL)""",
                    )
                }
            }

            val read = SqliteSchemaReader().read(pool)
            val table = read.schema.tables.getValue("order_line")

            // Der gemessene Ist-Zustand: die Spalten sind weg.
            table.columns.keys.toList() shouldContainExactly listOf("id", "qty", "price")

            val notes = read.notes.filter { it.code == GeneratedColumnNotes.COLUMN_ABSENT }
            withClue(read.notes.map { "${it.code}:${it.objectName}" }.toString()) {
                notes.map { it.objectName } shouldContainExactly
                    listOf("order_line.total", "order_line.virt")
            }
            // Die Art steht in der Meldung — STORED und VIRTUAL sind
            // verschiedene Zusicherungen.
            notes.single { it.objectName == "order_line.total" }.message.contains("STORED") shouldBe true
            notes.single { it.objectName == "order_line.virt" }.message.contains("VIRTUAL") shouldBe true
        } finally {
            pool.close()
        }
    }

    test("a table without generated columns stays quiet") {
        val pool = newPool()
        try {
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { s ->
                    s.execute("CREATE TABLE plain (id INTEGER PRIMARY KEY, nm TEXT NOT NULL)")
                }
            }
            SqliteSchemaReader().read(pool).notes
                .none { it.code == GeneratedColumnNotes.COLUMN_ABSENT } shouldBe true
        } finally {
            pool.close()
        }
    }
})
