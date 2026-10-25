package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexSortDirection
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.Connection
import java.sql.DriverManager

/**
 * Ein `CREATE UNIQUE INDEX` (Ursprung `c` in `PRAGMA index_list`) bleibt ein Index; nur ein
 * `UNIQUE`-Constraint (Ursprung `u`) wird `column.unique` bzw. Constraint (GitHub-Issue #53).
 */
class SqliteSchemaReaderUniqueIndexTest : FunSpec({

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

    test("single-column UNIQUE INDEX stays an index and is not lifted to ColumnDefinition.unique") {
        withDb(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT)",
            "CREATE UNIQUE INDEX idx_email ON t (email)",
        ) { pool ->
            val t = reader.read(pool).schema.tables["t"]!!
            t.columns["email"]!!.unique shouldBe false
            t.indices.single() shouldBe IndexDefinition(
                name = "idx_email",
                columns = listOf(IndexColumn("email")),
                unique = true,
            )
        }
    }

    test("multi-column UNIQUE INDEX stays an index and does not become a constraint") {
        withDb(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, a TEXT, b TEXT, c TEXT)",
            "CREATE UNIQUE INDEX ix_t ON t (a, b, c)",
        ) { pool ->
            val t = reader.read(pool).schema.tables["t"]!!
            t.constraints.filter { it.type == ConstraintType.UNIQUE } shouldBe emptyList()
            t.indices.single() shouldBe IndexDefinition(
                name = "ix_t",
                columns = listOf(IndexColumn("a"), IndexColumn("b"), IndexColumn("c")),
                unique = true,
            )
        }
    }

    test("a UNIQUE index keeps its direction and a UNIQUE constraint on the same column beside it") {
        withDb(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, a TEXT UNIQUE)",
            "CREATE UNIQUE INDEX ix_t ON t (a DESC)",
        ) { pool ->
            val t = reader.read(pool).schema.tables["t"]!!
            t.columns["a"]!!.unique shouldBe true
            t.indices.single().columns shouldBe listOf(IndexColumn("a", direction = IndexSortDirection.DESC))
        }
    }

    test("a UNIQUE index on the primary key column is an index") {
        withDb(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, a TEXT)",
            "CREATE UNIQUE INDEX ix_t ON t (id)",
        ) { pool ->
            val t = reader.read(pool).schema.tables["t"]!!
            t.indices.single().name shouldBe "ix_t"
            t.indices.single().unique shouldBe true
        }
    }
})
