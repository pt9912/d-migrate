package dev.dmigrate.driver.mysql.profiling

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.mysql.MysqlDriver
import dev.dmigrate.profiling.model.DeterminationStatus
import dev.dmigrate.profiling.types.LogicalType
import dev.dmigrate.profiling.types.TargetLogicalType
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.testcontainers.mysql.MySQLContainer

private val IntegrationTag = NamedTag("integration")

class MysqlProfilingIntegrationTest : FunSpec({

    tags(IntegrationTag)

    val container = MySQLContainer("mysql:8")
        .withDatabaseName("profiling_test")
        .withUsername("test")
        .withPassword("test")

    val introspection = MysqlSchemaIntrospectionAdapter()
    val data = MysqlProfilingDataAdapter()
    val resolver = MysqlLogicalTypeResolver()

    beforeSpec {
        DatabaseDriverRegistry.register(MysqlDriver())
        container.start()

        val config = ConnectionConfig(
            dialect = DatabaseDialect.MYSQL,
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
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        name VARCHAR(100) NOT NULL,
                        email VARCHAR(254) UNIQUE,
                        age INT,
                        score DECIMAL(5,2)
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
        DatabaseDialect.MYSQL, container.host, container.firstMappedPort,
        "profiling_test", "test", "test",
    ))

    test("resolver: varchar → STRING") { resolver.resolve("varchar(100)") shouldBe LogicalType.STRING }
    test("resolver: int → INTEGER") { resolver.resolve("int") shouldBe LogicalType.INTEGER }
    test("resolver: tinyint(1) → BOOLEAN") { resolver.resolve("tinyint(1)") shouldBe LogicalType.BOOLEAN }

    test("listTables includes users") {
        pool().use { p -> introspection.listTables(p).any { it.name == "users" } shouldBe true }
    }

    test("listColumns detects PK and unique") {
        pool().use { p ->
            val cols = introspection.listColumns(p, "users")
            cols.first { it.name == "id" }.isPrimaryKey shouldBe true
            cols.first { it.name == "email" }.isUnique shouldBe true
        }
    }

    test("rowCount") {
        pool().use { p -> data.rowCount(p, "users") shouldBe 5 }
    }

    test("columnMetrics text column") {
        pool().use { p ->
            val m = data.columnMetrics(p, "users", "email", "varchar(254)")
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
            stats!!.stddev shouldNotBe null
        }
    }

    test("targetTypeCompatibility FULL_SCAN") {
        pool().use { p ->
            val compat = data.targetTypeCompatibility(p, "users", "name",
                listOf(TargetLogicalType.INTEGER, TargetLogicalType.STRING))
            compat shouldHaveSize 2
            compat.first { it.targetType == TargetLogicalType.INTEGER }.determinationStatus shouldBe DeterminationStatus.FULL_SCAN
            compat.first { it.targetType == TargetLogicalType.STRING }.compatibleCount shouldBe 5
        }
    }
})
