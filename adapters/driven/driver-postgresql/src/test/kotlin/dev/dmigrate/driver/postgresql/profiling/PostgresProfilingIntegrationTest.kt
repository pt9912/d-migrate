package dev.dmigrate.driver.postgresql.profiling

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.postgresql.PostgresDriver
import dev.dmigrate.profiling.model.DeterminationStatus
import dev.dmigrate.profiling.types.LogicalType
import dev.dmigrate.profiling.types.TargetLogicalType
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.testcontainers.postgresql.PostgreSQLContainer

private val IntegrationTag = NamedTag("integration")

class PostgresProfilingIntegrationTest : FunSpec({

    tags(IntegrationTag)

    val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("profiling_test")
        .withUsername("test")
        .withPassword("test")

    val introspection = PostgresSchemaIntrospectionAdapter()
    val data = PostgresProfilingDataAdapter()
    val resolver = PostgresLogicalTypeResolver()

    beforeSpec {
        DatabaseDriverRegistry.register(PostgresDriver())
        container.start()

        val config = ConnectionConfig(
            dialect = DatabaseDialect.POSTGRESQL,
            host = container.host,
            port = container.firstMappedPort,
            database = "profiling_test",
            user = "test",
            password = "test",
        )
        val pool = HikariConnectionPoolFactory.create(config)
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE users (
                        id SERIAL PRIMARY KEY,
                        name VARCHAR(100) NOT NULL,
                        email VARCHAR(254) UNIQUE,
                        age INTEGER,
                        score NUMERIC(5,2)
                    )
                """.trimIndent())
                stmt.execute("INSERT INTO users (name, email, age, score) VALUES ('Alice', 'alice@example.com', 30, 95.50)")
                stmt.execute("INSERT INTO users (name, email, age, score) VALUES ('Bob', 'bob@example.com', 25, 87.20)")
                stmt.execute("INSERT INTO users (name, email, age, score) VALUES ('Charlie', '', NULL, 92.10)")
                stmt.execute("INSERT INTO users (name, email, age, score) VALUES ('Alice', 'alice2@example.com', 30, 88.00)")
                stmt.execute("INSERT INTO users (name, email, age, score) VALUES ('Eve', NULL, 22, NULL)")
            }
        }
        pool.close()
    }

    afterSpec { container.stop() }

    fun pool() = HikariConnectionPoolFactory.create(ConnectionConfig(
        DatabaseDialect.POSTGRESQL, container.host, container.firstMappedPort,
        "profiling_test", "test", "test",
    ))

    test("resolver: varchar → STRING") { resolver.resolve("character varying") shouldBe LogicalType.STRING }
    test("resolver: integer → INTEGER") { resolver.resolve("integer") shouldBe LogicalType.INTEGER }

    test("listTables includes users") {
        pool().use { p -> introspection.listTables(p).any { it.name == "users" } shouldBe true }
    }

    test("listColumns detects PK, unique, and types") {
        pool().use { p ->
            val cols = introspection.listColumns(p, "users")
            cols.first { it.name == "id" }.isPrimaryKey shouldBe true
            cols.first { it.name == "email" }.isUnique shouldBe true
            cols.first { it.name == "age" }.nullable shouldBe true
        }
    }

    test("rowCount") {
        pool().use { p -> data.rowCount(p, "users") shouldBe 5 }
    }

    test("columnMetrics text column") {
        pool().use { p ->
            val m = data.columnMetrics(p, "users", "email", "character varying")
            m.nonNullCount shouldBe 4
            m.nullCount shouldBe 1
            m.emptyStringCount shouldBe 1
        }
    }

    test("topValues deterministic") {
        pool().use { p ->
            val top = data.topValues(p, "users", "name", 10)
            top[0].value shouldBe "Alice"
            top[0].count shouldBe 2
        }
    }

    test("numericStats with stddev") {
        pool().use { p ->
            val stats = data.numericStats(p, "users", "score")
            stats shouldNotBe null
            stats!!.stddev shouldNotBe null // PostgreSQL has stddev_pop
        }
    }

    test("targetTypeCompatibility FULL_SCAN") {
        pool().use { p ->
            val compat = data.targetTypeCompatibility(p, "users", "name",
                listOf(TargetLogicalType.INTEGER, TargetLogicalType.STRING))
            compat shouldHaveSize 2
            compat.first { it.targetType == TargetLogicalType.INTEGER }.determinationStatus shouldBe DeterminationStatus.FULL_SCAN
            compat.first { it.targetType == TargetLogicalType.INTEGER }.incompatibleCount shouldBe 5
            compat.first { it.targetType == TargetLogicalType.STRING }.compatibleCount shouldBe 5
        }
    }
})
