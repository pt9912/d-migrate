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

    test("a UNIQUE index with a WHERE predicate stays an index and keeps the predicate") {
        withDb(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, a TEXT, b TEXT)",
            "CREATE UNIQUE INDEX ix_t ON t (a) WHERE b IS NOT NULL",
        ) { pool ->
            val t = reader.read(pool).schema.tables["t"]!!
            t.columns["a"]!!.unique shouldBe false
            t.constraints.filter { it.type == ConstraintType.UNIQUE } shouldBe emptyList()
            val index = t.indices.single()
            index.name shouldBe "ix_t"
            index.unique shouldBe true
            index.where shouldBe "b IS NOT NULL"
        }
    }

    test("two UNIQUE indexes on one table both stay indexes") {
        withDb(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, a TEXT, b TEXT)",
            "CREATE UNIQUE INDEX ix_a ON t (a)",
            "CREATE UNIQUE INDEX ix_ab ON t (a, b)",
        ) { pool ->
            val t = reader.read(pool).schema.tables["t"]!!
            t.columns["a"]!!.unique shouldBe false
            t.constraints.filter { it.type == ConstraintType.UNIQUE } shouldBe emptyList()
            t.indices.associate { it.name to it.columns.map(IndexColumn::name) } shouldBe
                mapOf("ix_a" to listOf("a"), "ix_ab" to listOf("a", "b"))
            t.indices.all { it.unique } shouldBe true
        }
    }

    // Gegenprobe: Ursprung `u` (Tabellen-UNIQUE) bleibt ein Constraint, auch neben dem Fix fuer Ursprung `c`.
    test("a table-level UNIQUE (a, b) constraint stays a constraint and yields no index") {
        withDb("CREATE TABLE t (id INTEGER PRIMARY KEY, a TEXT, b TEXT, UNIQUE (a, b))") { pool ->
            val t = reader.read(pool).schema.tables["t"]!!
            t.indices shouldBe emptyList()
            val unique = t.constraints.single { it.type == ConstraintType.UNIQUE }
            unique.columns shouldBe listOf("a", "b")
        }
    }

    test("a UNIQUE index on a WITHOUT ROWID table stays an index; the primary key is not an index") {
        withDb(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, a TEXT NOT NULL) WITHOUT ROWID",
            "CREATE UNIQUE INDEX ix_t ON t (a)",
        ) { pool ->
            val t = reader.read(pool).schema.tables["t"]!!
            t.columns["a"]!!.unique shouldBe false
            t.constraints.filter { it.type == ConstraintType.UNIQUE } shouldBe emptyList()
            t.indices.single() shouldBe IndexDefinition(
                name = "ix_t",
                columns = listOf(IndexColumn("a")),
                unique = true,
            )
        }
    }

    // Die Eindeutigkeit des Ausdrucks-Index geht nicht verloren; sein Text kommt ohne umschliessende Klammer zurueck.
    test("a UNIQUE expression index stays an index with unique = true and does not set column.unique") {
        withDb(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, a TEXT)",
            "CREATE UNIQUE INDEX ix_t ON t (lower(a))",
        ) { pool ->
            val t = reader.read(pool).schema.tables["t"]!!
            t.columns["a"]!!.unique shouldBe false
            val index = t.indices.single()
            index.name shouldBe "ix_t"
            index.unique shouldBe true
            index.columns.single().expression shouldBe "lower(a)"
        }
    }
})
