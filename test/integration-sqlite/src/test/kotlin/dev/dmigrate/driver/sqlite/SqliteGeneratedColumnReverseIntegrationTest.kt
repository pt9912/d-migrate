package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.metadata.GeneratedColumnNotes
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
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

    test("generated columns come back with their expression, in their place") {
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

            // `table_info` verschweigt die beiden; ueber `table_xinfo` sind sie
            // da — und an ihrer Stelle, nicht hinten angehaengt.
            table.columns.keys.toList() shouldContainExactly listOf("id", "qty", "price", "total", "virt")

            withClue(read.notes.map { "${it.code}:${it.objectName}" }.toString()) {
                read.notes.none { it.code == GeneratedColumnNotes.COLUMN_ABSENT } shouldBe true
            }

            val stored = table.columns.getValue("total").generation as? ColumnGeneration.Computed
            val virtual = table.columns.getValue("virt").generation as? ColumnGeneration.Computed
            withClue(table.columns.getValue("total").generation.toString()) {
                stored.shouldNotBeNull()
                stored.stored shouldBe true
                // SQLite legt den Autorentext ab, nicht eine Normalform —
                // anders als PostgreSQL und MySQL.
                stored.expression shouldBe "qty * price"
            }
            withClue(table.columns.getValue("virt").generation.toString()) {
                virtual.shouldNotBeNull()
                virtual.stored shouldBe false
                virtual.expression shouldBe "qty + 1"
            }
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
