package dev.dmigrate.driver.mysql.profiling

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.mysql.MysqlDriver
import dev.dmigrate.profiling.ProfilingAdapterSet
import dev.dmigrate.profiling.model.DeterminationStatus
import dev.dmigrate.profiling.types.LogicalType
import dev.dmigrate.profiling.types.TargetLogicalType
import dev.dmigrate.profiling.service.ProfileDatabaseService
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.testcontainers.mysql.MySQLContainer
import java.sql.DriverManager

private val IntegrationTag = NamedTag("integration")

class MysqlProfilingIntegrationTest : FunSpec({

    tags(IntegrationTag)

    val primaryDb = "profiling_test"
    val tenantDb = "profiling_tenant"

    val container = MySQLContainer("mysql:8")
        .withDatabaseName(primaryDb)
        .withUsername("test")
        .withPassword("test")

    val introspection = MysqlSchemaIntrospectionAdapter()
    val data = MysqlProfilingDataAdapter()
    val resolver = MysqlLogicalTypeResolver()
    val profileService = ProfileDatabaseService(ProfilingAdapterSet(introspection, data, resolver))

    beforeSpec {
        DatabaseDriverRegistry.register(MysqlDriver())
        container.start()

        val config = ConnectionConfig(
            dialect = DatabaseDialect.MYSQL,
            host = container.host,
            port = container.firstMappedPort,
            database = primaryDb,
            user = "test",
            password = "test",
            params = mapOf("allowPublicKeyRetrieval" to "true"),
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

        DriverManager.getConnection(
            "jdbc:mysql://${container.host}:${container.firstMappedPort}/?allowPublicKeyRetrieval=true&useSSL=false",
            "root",
            container.password,
        ).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE DATABASE `$tenantDb`")
                stmt.execute("GRANT ALL PRIVILEGES ON `$tenantDb`.* TO 'test'@'%'")
                stmt.execute("""
                    CREATE TABLE `$tenantDb`.users (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        name VARCHAR(100) NOT NULL,
                        email VARCHAR(254) UNIQUE,
                        age INT,
                        score DECIMAL(5,2)
                    )
                """.trimIndent())
                stmt.execute(
                    "INSERT INTO `$tenantDb`.users (name, email, age, score) " +
                        "VALUES ('Tenant Alice', 'tenant@example.com', 41, 77.70)"
                )
            }
        }
    }

    afterSpec {
        if (container.isRunning) container.stop()
        DatabaseDriverRegistry.clear()
    }

    fun pool() = HikariConnectionPoolFactory.create(ConnectionConfig(
        DatabaseDialect.MYSQL, container.host, container.firstMappedPort,
        primaryDb, "test", "test",
        params = mapOf("allowPublicKeyRetrieval" to "true"),
    ))

    test("resolver: varchar → STRING") { resolver.resolve("varchar(100)") shouldBe LogicalType.STRING }
    test("resolver: int → INTEGER") { resolver.resolve("int") shouldBe LogicalType.INTEGER }
    test("resolver: tinyint(1) → BOOLEAN") { resolver.resolve("tinyint(1)") shouldBe LogicalType.BOOLEAN }

    test("listTables includes users") {
        pool().use { p -> introspection.listTables(p).any { it.name == "users" } shouldBe true }
    }

    test("listTables respects an explicitly requested MySQL database") {
        pool().use { p ->
            val tables = introspection.listTables(p, tenantDb)
            tables.map { it.name } shouldContainExactly listOf("users")
            tables.single().schema shouldBe tenantDb
        }
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

    test("profile service uses the requested MySQL database when schema is passed") {
        pool().use { p ->
            val defaultProfile = profileService.profile(p, "MySQL", tables = listOf("users"))
            defaultProfile.tables.single().rowCount shouldBe 5

            val tenantProfile = profileService.profile(p, "MySQL", schema = tenantDb, tables = listOf("users"))
            tenantProfile.schemaName shouldBe tenantDb
            tenantProfile.tables.single().rowCount shouldBe 1
            tenantProfile.tables.single().columns.first { it.name == "name" }.topValues.single().value shouldBe "Tenant Alice"
        }
    }

    // ── Security: malicious identifiers (0.9.1 Phase A §5.4) ───

    test("security: table with embedded backtick is profiled safely") {
        pool().use { p ->
            p.borrow().createStatement().use { stmt ->
                stmt.execute("CREATE TABLE `my``table` (`col``1` TEXT, `val` INT)")
                stmt.execute("INSERT INTO `my``table` VALUES ('a', 1)")
            }
            introspection.listColumns(p, "my`table").size shouldBe 2
            data.rowCount(p, "my`table") shouldBe 1
            data.columnMetrics(p, "my`table", "col`1", "text").nonNullCount shouldBe 1
        }
    }

    test("security: table named with reserved word is profiled safely") {
        pool().use { p ->
            p.borrow().createStatement().use { stmt ->
                stmt.execute("CREATE TABLE `select` (`where` TEXT, `from` INT)")
                stmt.execute("INSERT INTO `select` VALUES ('x', 42)")
            }
            introspection.listColumns(p, "select").size shouldBe 2
            data.rowCount(p, "select") shouldBe 1
            data.columnMetrics(p, "select", "where", "text").nonNullCount shouldBe 1
        }
    }

    test("security: semicolon-injection attempt does not corrupt database") {
        pool().use { p ->
            p.borrow().createStatement().use { stmt ->
                stmt.execute("CREATE TABLE `users; DROP TABLE users --` (`id` INT)")
                stmt.execute("INSERT INTO `users; DROP TABLE users --` VALUES (1)")
            }
            data.rowCount(p, "users; DROP TABLE users --") shouldBe 1
            data.rowCount(p, "users") shouldBe 5
        }
    }
})
