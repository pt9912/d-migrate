package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.data.UnsupportedTriggerModeException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists

class SqliteSchemaSyncTest : FunSpec({

    lateinit var dbFile: Path
    lateinit var pool: ConnectionPool

    beforeEach {
        dbFile = Files.createTempFile("d-migrate-sqlite-sync-", ".db")
        dbFile.deleteIfExists()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.SQLITE,
                host = null,
                port = null,
                database = dbFile.absolutePathString(),
                user = null,
                password = null,
            )
        )

        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "CREATE TABLE sync_autoinc (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT)"
                )
                stmt.execute(
                    "CREATE TABLE sync_plain_pk (" +
                        "id INTEGER PRIMARY KEY, " +
                        "name TEXT)"
                )
            }
        }
    }

    afterEach {
        pool.close()
        dbFile.deleteIfExists()
    }

    val schemaSync = SqliteSchemaSync()
    val idColumn = listOf(ColumnDescriptor("id", nullable = false))

    test("reseed autoincrement table updates next generated value") {
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO sync_autoinc (id, name) VALUES (7, 'manual')")
            }

            val adjustments = schemaSync.reseedGenerators(conn, "sync_autoinc", idColumn)

            adjustments.single().column shouldBe "id"
            adjustments.single().sequenceName shouldBe null
            adjustments.single().newValue shouldBe 8

            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO sync_autoinc (name) VALUES ('next')", java.sql.Statement.RETURN_GENERATED_KEYS)
                stmt.generatedKeys.use { keys ->
                    keys.next() shouldBe true
                    keys.getLong(1) shouldBe 8L
                }
            }
        }
    }

    test("no adjustment for table without autoincrement") {
        pool.borrow().use { conn ->
            schemaSync.reseedGenerators(conn, "sync_plain_pk", idColumn) shouldBe emptyList()
        }
    }

    test("truncate empty autoincrement table clears sqlite_sequence") {
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO sync_autoinc (id, name) VALUES (5, 'manual')")
                stmt.execute("DELETE FROM sync_autoinc")
            }

            val adjustments = schemaSync.reseedGenerators(
                conn = conn,
                table = "sync_autoinc",
                importedColumns = emptyList(),
                truncatePerformed = true,
            )

            adjustments.single().newValue shouldBe 1
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO sync_autoinc (name) VALUES ('next')", java.sql.Statement.RETURN_GENERATED_KEYS)
                stmt.generatedKeys.use { keys ->
                    keys.next() shouldBe true
                    keys.getLong(1) shouldBe 1L
                }
            }
        }
    }

    test("trigger disable mode is unsupported") {
        pool.borrow().use { conn ->
            shouldThrow<UnsupportedTriggerModeException> {
                schemaSync.disableTriggers(conn, "sync_autoinc")
            }
        }
    }

    test("trigger strict mode is unsupported") {
        pool.borrow().use { conn ->
            shouldThrow<UnsupportedTriggerModeException> {
                schemaSync.assertNoUserTriggers(conn, "sync_autoinc")
            }
        }
    }
})
