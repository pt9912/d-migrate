package dev.dmigrate.driver.sqlite.profiling

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.profiling.model.DeterminationStatus
import dev.dmigrate.profiling.types.LogicalType
import dev.dmigrate.profiling.types.TargetLogicalType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.sql.Connection
import java.sql.DriverManager

/**
 * In-memory SQLite pool that returns a non-closeable wrapper.
 * SQLite :memory: DBs are destroyed when the last connection closes,
 * so borrow() returns a proxy where close() is a no-op.
 */
private class InMemoryPool : ConnectionPool {
    private val realConn = DriverManager.getConnection("jdbc:sqlite::memory:")
    override val dialect = DatabaseDialect.SQLITE
    override fun borrow(): Connection = object : Connection by realConn {
        override fun close() { /* no-op — keep in-memory DB alive */ }
    }
    override fun activeConnections() = 0
    override fun close() = realConn.close()
}

class SqliteProfilingTest : FunSpec({

    val pool = InMemoryPool()
    val introspection = SqliteSchemaIntrospectionAdapter()
    val data = SqliteProfilingDataAdapter()
    val resolver = SqliteLogicalTypeResolver()

    beforeSpec {
        pool.borrow().createStatement().use { stmt ->
            stmt.execute("""
                CREATE TABLE users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    email TEXT,
                    age INTEGER,
                    score REAL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
            """.trimIndent())
            stmt.execute("INSERT INTO users (name, email, age, score) VALUES ('Alice', 'alice@example.com', 30, 95.5)")
            stmt.execute("INSERT INTO users (name, email, age, score) VALUES ('Bob', 'bob@example.com', 25, 87.2)")
            stmt.execute("INSERT INTO users (name, email, age, score) VALUES ('Charlie', '', NULL, 92.1)")
            stmt.execute("INSERT INTO users (name, email, age, score) VALUES ('Alice', 'alice2@example.com', 30, 88.0)")
            stmt.execute("INSERT INTO users (name, email, age, score) VALUES ('Eve', NULL, 22, NULL)")
        }
    }

    // ── LogicalTypeResolver ──────────────────────────────

    test("resolves TEXT to STRING") { resolver.resolve("TEXT") shouldBe LogicalType.STRING }
    test("resolves INTEGER to INTEGER") { resolver.resolve("INTEGER") shouldBe LogicalType.INTEGER }
    test("resolves REAL to DECIMAL") { resolver.resolve("REAL") shouldBe LogicalType.DECIMAL }
    test("resolves BLOB to BINARY") { resolver.resolve("BLOB") shouldBe LogicalType.BINARY }
    test("resolves empty type to UNKNOWN") { resolver.resolve("") shouldBe LogicalType.UNKNOWN }

    // ── SchemaIntrospection ──────────────────────────────

    test("listTables returns user tables") {
        val tables = introspection.listTables(pool)
        tables.any { it.name == "users" } shouldBe true
    }

    test("listColumns returns column metadata") {
        val columns = introspection.listColumns(pool, "users")
        columns.size shouldBe 6
        columns.first { it.name == "id" }.isPrimaryKey shouldBe true
        columns.first { it.name == "name" }.nullable shouldBe false
        columns.first { it.name == "email" }.nullable shouldBe true
    }

    // ── ProfilingData ────────────────────────────────────

    test("rowCount") {
        data.rowCount(pool, "users") shouldBe 5
    }

    test("columnMetrics for text column") {
        val m = data.columnMetrics(pool, "users", "email", "TEXT")
        m.nonNullCount shouldBe 4
        m.nullCount shouldBe 1
        m.emptyStringCount shouldBe 1
        m.distinctCount shouldBe 4
    }

    test("columnMetrics for integer column") {
        val m = data.columnMetrics(pool, "users", "age", "INTEGER")
        m.nonNullCount shouldBe 4
        m.nullCount shouldBe 1
    }

    test("topValues deterministic order") {
        val top = data.topValues(pool, "users", "name", 10)
        top shouldHaveSize 4
        top[0].value shouldBe "Alice"
        top[0].count shouldBe 2
    }

    test("numericStats for numeric column") {
        val stats = data.numericStats(pool, "users", "age")
        stats shouldNotBe null
        stats!!.min shouldBe 22.0
        stats.max shouldBe 30.0
        stats.stddev shouldBe null // SQLite fallback
    }

    test("temporalStats for datetime column") {
        val stats = data.temporalStats(pool, "users", "created_at")
        stats shouldNotBe null
    }

    test("targetTypeCompatibility with FULL_SCAN") {
        val compat = data.targetTypeCompatibility(
            pool, "users", "name",
            listOf(TargetLogicalType.INTEGER, TargetLogicalType.STRING),
        )
        compat shouldHaveSize 2

        val intCompat = compat.first { it.targetType == TargetLogicalType.INTEGER }
        intCompat.determinationStatus shouldBe DeterminationStatus.FULL_SCAN
        intCompat.incompatibleCount shouldBe 5 // all names are non-integer
        intCompat.exampleInvalidValues.size shouldBe 3

        val strCompat = compat.first { it.targetType == TargetLogicalType.STRING }
        strCompat.compatibleCount shouldBe 5
        strCompat.incompatibleCount shouldBe 0
    }
})
