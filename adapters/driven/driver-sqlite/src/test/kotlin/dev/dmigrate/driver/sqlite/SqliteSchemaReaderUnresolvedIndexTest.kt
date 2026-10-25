package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.Connection
import java.sql.DriverManager

/**
 * Ein Index, dessen Ausdrucksschluessel sich nicht aufloesen laesst, faellt aus dem Schema heraus (lieber gar nicht als
 * verkuerzt) — und wird als Notiz `R206` gemeldet, nicht still verworfen (Folge von GitHub-Issue #53).
 */
class SqliteSchemaReaderUnresolvedIndexTest : FunSpec({

    val reader = SqliteSchemaReader()

    fun pool(conn: Connection) = object : ConnectionPool {
        override val dialect = DatabaseDialect.SQLITE
        override fun borrow(): DatabaseConnection = JdbcDatabaseConnection(conn)
        override fun activeConnections(): Int = 1
        override fun close() {}
    }

    fun withDb(vararg statements: String, block: (ConnectionPool) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { stmt -> statements.forEach { stmt.execute(it.trim()) } }
            block(pool(conn))
        }
    }

    test("ein aufloesbarer Ausdrucks-Index bleibt ein Index, ohne R206") {
        withDb(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, a TEXT)",
            "CREATE INDEX ix ON t ((lower(a)))",
        ) { pool ->
            val result = reader.read(pool)
            result.schema.tables.getValue("t").indices.map { it.name } shouldBe listOf("ix")
            result.notes.none { it.code == "R206" } shouldBe true
        }
    }

    test("ein Ausdrucks-Index mit nicht aufloesbarem Schluessel wird als R206 gemeldet, nicht still verworfen") {
        // Das Erzeugungs-SQL im Katalog traegt keine Schluessel: der Scanner findet nichts, `index_xinfo` meldet fuer die
        // Ausdrucksposition keinen Spaltennamen.
        withDb(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, a TEXT)",
            "CREATE INDEX ix ON t ((lower(a)))",
            "PRAGMA writable_schema = ON",
            "UPDATE sqlite_master SET sql = 'CREATE INDEX ix ON t' WHERE type = 'index' AND name = 'ix'",
        ) { pool ->
            val result = reader.read(pool)
            result.schema.tables.getValue("t").indices shouldBe emptyList()
            val note = result.notes.single { it.code == "R206" }
            note.severity shouldBe SchemaReadSeverity.WARNING
            note.objectName shouldBe "t.ix"
        }
    }
})
