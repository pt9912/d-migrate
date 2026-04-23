package dev.dmigrate.driver.mysql

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.data.UnsupportedTriggerModeException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.mysql.MySQLContainer

private val MysqlSchemaSyncIntegrationTag = NamedTag("integration")

class MysqlSchemaSyncIntegrationTest : FunSpec({

    tags(MysqlSchemaSyncIntegrationTag)

    val container = MySQLContainer("mysql:8.0")
        .withDatabaseName("dmigrate_test")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    var pool: ConnectionPool? = null

    beforeSpec {
        container.start()
        DatabaseDriverRegistry.register(MysqlDriver())

        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.MYSQL,
                host = container.host,
                port = container.firstMappedPort,
                database = container.databaseName,
                user = container.username,
                password = container.password,
                params = mapOf("allowPublicKeyRetrieval" to "true"),
            )
        )

        pool!!.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "CREATE TABLE sync_auto (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "name VARCHAR(100)" +
                        ") ENGINE=InnoDB"
                )
                stmt.execute(
                    "CREATE TABLE sync_empty (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "name VARCHAR(100)" +
                        ") ENGINE=InnoDB"
                )
            }
        }
    }

    afterSpec {
        pool?.close()
        if (container.isRunning) container.stop()
        DatabaseDriverRegistry.clear()
    }

    beforeTest {
        pool!!.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM sync_auto")
                stmt.execute("ALTER TABLE sync_auto AUTO_INCREMENT = 1")
                stmt.execute("DELETE FROM sync_empty")
                stmt.execute("ALTER TABLE sync_empty AUTO_INCREMENT = 1")
            }
        }
    }

    val schemaSync = MysqlSchemaSync()
    val idColumn = listOf(ColumnDescriptor("id", nullable = false))

    test("reseed auto increment uses next max plus one") {
        pool!!.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO sync_auto (id, name) VALUES (7, 'manual')")
            }

            val adjustments = schemaSync.reseedGenerators(conn, "sync_auto", idColumn)

            adjustments.single().column shouldBe "id"
            adjustments.single().sequenceName shouldBe null
            adjustments.single().newValue shouldBe 8

            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO sync_auto (name) VALUES ('next')", java.sql.Statement.RETURN_GENERATED_KEYS)
                stmt.generatedKeys.use { keys ->
                    keys.next() shouldBe true
                    keys.getLong(1) shouldBe 8L
                }
            }
        }
    }

    test("no adjustment when max is null without truncate signal") {
        pool!!.borrow().use { conn ->
            schemaSync.reseedGenerators(conn, "sync_empty", idColumn) shouldBe emptyList()
        }
    }

    test("truncate empty table resets auto increment to one") {
        pool!!.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO sync_auto (id, name) VALUES (5, 'manual')")
                stmt.execute("DELETE FROM sync_auto")
            }

            val adjustments = schemaSync.reseedGenerators(
                conn = conn,
                table = "sync_auto",
                importedColumns = emptyList(),
                truncatePerformed = true,
            )

            adjustments.single().newValue shouldBe 1
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO sync_auto (name) VALUES ('next')", java.sql.Statement.RETURN_GENERATED_KEYS)
                stmt.generatedKeys.use { keys ->
                    keys.next() shouldBe true
                    keys.getLong(1) shouldBe 1L
                }
            }
        }
    }

})
