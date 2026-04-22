package dev.dmigrate.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.DataCommand
import dev.dmigrate.cli.commands.SchemaCommand
import dev.dmigrate.driver.DatabaseDriverRegistry
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.mysql.MySQLContainer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.deleteIfExists

private val IntegrationTag = NamedTag("integration")

/**
 * End-to-End-Tests für `d-migrate data import` gegen einen realen MySQL-
 * Container (Testcontainers). Plan §4 Phase F Schritt 30.
 *
 * Tagged `integration` — läuft nur mit `./gradlew test -PintegrationTests`
 * und im `.github/workflows/integration.yml`-Workflow.
 */
class DataImportE2EMysqlTest : FunSpec({

    tags(IntegrationTag)

    val container = MySQLContainer("mysql:8.0")
        .withDatabaseName("dmigrate_e2e")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    fun cli() = DMigrate().subcommands(SchemaCommand(), DataCommand())

    fun jdbcUrl(): String =
        "mysql://${container.username}:${container.password}@" +
            "${container.host}:${container.firstMappedPort}/${container.databaseName}"

    fun rawJdbc(): String =
        "jdbc:mysql://${container.host}:${container.firstMappedPort}/${container.databaseName}"

    fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
        try { block() } finally { System.setOut(original) }
        return captured.toString(Charsets.UTF_8)
    }

    fun captureStderr(block: () -> Unit): String {
        val original = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true, Charsets.UTF_8))
        try { block() } finally { System.setErr(original) }
        return captured.toString(Charsets.UTF_8)
    }

    fun queryAll(table: String): List<Map<String, Any?>> =
        JdbcTestHelper.queryAll(rawJdbc(), table, container.username, container.password)

    fun cleanAll() {
        DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("SET FOREIGN_KEY_CHECKS = 0")
                stmt.execute("DELETE FROM orders")
                stmt.execute("ALTER TABLE orders AUTO_INCREMENT = 1")
                stmt.execute("DELETE FROM users")
                stmt.execute("ALTER TABLE users AUTO_INCREMENT = 1")
                stmt.execute("SET FOREIGN_KEY_CHECKS = 1")
            }
        }
    }

    fun exportToFile(format: String, outFile: java.nio.file.Path, tables: String = "users") {
        captureStdout {
            cli().parse(
                listOf(
                    "--quiet",
                    "data", "export",
                    "--source", jdbcUrl(),
                    "--format", format,
                    "--tables", tables,
                    "--output", outFile.toString(),
                )
            )
        }
    }

    beforeSpec {
        container.start()
        registerDrivers()

        DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE users (
                        id         INT AUTO_INCREMENT PRIMARY KEY,
                        name       VARCHAR(255) NOT NULL,
                        email      VARCHAR(255),
                        active     TINYINT(1) NOT NULL DEFAULT 1,
                        score      DECIMAL(10, 2),
                        updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE orders (
                        id        INT AUTO_INCREMENT PRIMARY KEY,
                        user_id   INT NOT NULL,
                        amount    DECIMAL(12, 2) NOT NULL,
                        placed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (user_id) REFERENCES users(id)
                    ) ENGINE=InnoDB
                    """.trimIndent()
                )
            }
        }
    }

    afterSpec {
        if (container.isRunning) container.stop()
        DatabaseDriverRegistry.clear()
    }

    beforeTest {
        cleanAll()
    }

    // ─── Round-Trip: JSON ────────────────────────────────────────

    test("JSON round-trip: export → import → row equivalence") {
        DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO users (name, email, active, score) VALUES ('alice', 'a@b.com', 1, 98.50)")
                stmt.execute("INSERT INTO users (name, email, active, score) VALUES ('bob', null, 0, 75.25)")
            }
        }

        val dataFile = Files.createTempFile("mysql-import-rt-", ".json")
        dataFile.deleteIfExists()
        try {
            exportToFile("json", dataFile)
            cleanAll()

            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", jdbcUrl(),
                        "--source", dataFile.toString(),
                        "--format", "json",
                        "--table", "users",
                    )
                )
            }

            val rows = queryAll("users")
            rows.size shouldBe 2
            rows.map { it["name"] } shouldContainExactlyInAnyOrder listOf("alice", "bob")
        } finally {
            Files.deleteIfExists(dataFile)
        }
    }

    // ─── AUTO_INCREMENT-Reseeding ────────────────────────────────

    test("AUTO_INCREMENT reseeding: next value = MAX(id)+1 after import") {
        DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO users (id, name, score) VALUES (10, 'manual', 0)")
            }
        }

        val dataFile = Files.createTempFile("mysql-reseed-", ".json")
        dataFile.deleteIfExists()
        try {
            exportToFile("json", dataFile)
            cleanAll()

            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", jdbcUrl(),
                        "--source", dataFile.toString(),
                        "--format", "json",
                        "--table", "users",
                    )
                )
            }

            DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
                conn.prepareStatement("SHOW TABLE STATUS LIKE 'users'").use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong("Auto_increment") shouldBe 11L
                    }
                }
            }
        } finally {
            Files.deleteIfExists(dataFile)
        }
    }

    // ─── --truncate ──────────────────────────────────────────────

    test("--truncate replaces pre-existing data") {
        // Seed source data for export
        DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO users (id, name, active, score) VALUES (1, 'alice', 1, 50)")
            }
        }

        val dataFile = Files.createTempFile("mysql-truncate-", ".json")
        dataFile.deleteIfExists()
        try {
            exportToFile("json", dataFile)
            // Add pre-existing data that should be removed by truncate
            DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("INSERT INTO users (id, name, active, score) VALUES (99, 'pre-existing', 1, 0)")
                }
            }

            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", jdbcUrl(),
                        "--source", dataFile.toString(),
                        "--format", "json",
                        "--table", "users",
                        "--truncate",
                    )
                )
            }

            val rows = queryAll("users")
            rows.size shouldBe 1
            rows[0]["name"] shouldBe "alice"
        } finally {
            Files.deleteIfExists(dataFile)
        }
    }

    // ─── --on-conflict update ────────────────────────────────────

    test("--on-conflict update upserts rows") {
        // Seed source with alice + bob
        DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO users (id, name, active, score) VALUES (1, 'alice', 1, 98.50)")
                stmt.execute("INSERT INTO users (id, name, active, score) VALUES (2, 'bob', 0, 75.25)")
            }
        }

        val dataFile = Files.createTempFile("mysql-upsert-", ".json")
        dataFile.deleteIfExists()
        try {
            exportToFile("json", dataFile)
            // Replace with stale data
            cleanAll()
            DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("INSERT INTO users (id, name, active, score) VALUES (1, 'OLD', 1, 0)")
                }
            }

            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", jdbcUrl(),
                        "--source", dataFile.toString(),
                        "--format", "json",
                        "--table", "users",
                        "--on-conflict", "update",
                    )
                )
            }

            val rows = queryAll("users")
            rows.size shouldBe 2
            rows.first { (it["id"] as Number).toInt() == 1 }["name"] shouldBe "alice"
            rows.first { (it["id"] as Number).toInt() == 2 }["name"] shouldBe "bob"
        } finally {
            Files.deleteIfExists(dataFile)
        }
    }

    // ─── --trigger-mode disable → Exit 2 ─────────────────────────

    test("--trigger-mode disable on MySQL → Exit 2") {
        val dataFile = Files.createTempFile("mysql-trigger-", ".json")
        Files.writeString(dataFile, """[{"id":1,"name":"test","active":1,"score":"0","updated_at":"2026-01-01 00:00:00"}]""")
        try {
            val stderr = captureStderr {
                val ex = shouldThrow<ProgramResult> {
                    cli().parse(
                        listOf(
                            "data", "import",
                            "--target", jdbcUrl(),
                            "--source", dataFile.toString(),
                            "--format", "json",
                            "--table", "users",
                            "--trigger-mode", "disable",
                        )
                    )
                }
                ex.statusCode shouldBe 2
            }
            stderr shouldContain "not supported"
        } finally {
            Files.deleteIfExists(dataFile)
        }
    }

    // ─── --disable-fk-checks ─────────────────────────────────────

    test("--disable-fk-checks allows child-before-parent import") {
        // Seed parent + child via SQL, then export both
        DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO users (id, name, active, score) VALUES (1, 'alice', 1, 50)")
                stmt.execute("INSERT INTO orders (id, user_id, amount) VALUES (1, 1, 100.00)")
            }
        }

        val ordersFile = Files.createTempFile("mysql-fk-orders-", ".json")
        val usersFile = Files.createTempFile("mysql-fk-users-", ".json")
        ordersFile.deleteIfExists()
        usersFile.deleteIfExists()
        try {
            exportToFile("json", ordersFile, "orders")
            exportToFile("json", usersFile, "users")
            cleanAll()

            // Import orders first (child before parent) — should work with FK checks disabled
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", jdbcUrl(),
                        "--source", ordersFile.toString(),
                        "--format", "json",
                        "--table", "orders",
                        "--disable-fk-checks",
                    )
                )
            }

            // Then import users (parent)
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", jdbcUrl(),
                        "--source", usersFile.toString(),
                        "--format", "json",
                        "--table", "users",
                    )
                )
            }

            queryAll("orders").size shouldBe 1
            queryAll("users").size shouldBe 1

            // FK checks should be re-enabled
            DriverManager.getConnection(rawJdbc(), container.username, container.password).use { conn ->
                conn.prepareStatement("SELECT @@foreign_key_checks").use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1) shouldBe 1
                    }
                }
            }
        } finally {
            Files.deleteIfExists(ordersFile)
            Files.deleteIfExists(usersFile)
        }
    }
})
