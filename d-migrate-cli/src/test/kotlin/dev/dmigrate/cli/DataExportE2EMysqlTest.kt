package dev.dmigrate.cli

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.DataCommand
import dev.dmigrate.cli.commands.SchemaCommand
import dev.dmigrate.driver.connection.JdbcUrlBuilderRegistry
import dev.dmigrate.driver.data.DataReaderRegistry
import dev.dmigrate.driver.mysql.MysqlDriver
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.testcontainers.mysql.MySQLContainer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.sql.DriverManager

private val MysqlIntegrationTag = NamedTag("integration")

/**
 * End-to-End-Tests für `d-migrate data export` gegen einen realen MySQL-
 * Container (Testcontainers). Plan §4 Phase F Schritt 29.
 *
 * Geht den vollständigen Pfad inkl. echter MySQL-spezifischer Datentypen,
 * Cursor-Streaming via `useCursorFetch=true` und der `information_schema.tables`-
 * basierten `MysqlTableLister`-Auflösung durch.
 *
 * Tagged `integration` — läuft nur mit `./gradlew test -PintegrationTests`
 * und im `.github/workflows/integration.yml`-Workflow.
 *
 * Verifiziert (analog zum PostgreSQL-Test, aber mit MySQL-Quirks):
 * - Round-Trip JSON/YAML/CSV mit echten MySQL-Typen (INT, VARCHAR, DECIMAL, TINYINT, DATETIME)
 * - §6.17 Empty-Table-Vertrag pro Format gegen eine echte leere MySQL-Tabelle
 * - `--split-files` mit Auto-Discovery via `MysqlTableLister`
 * - `--filter` (Roh-WHERE) wird server-seitig angewendet
 * - Cursor-Streaming `useCursorFetch=true` (Plan §3.3 / §6.13)
 */
class DataExportE2EMysqlTest : FunSpec({

    tags(MysqlIntegrationTag)

    val container = MySQLContainer("mysql:8.0")
        .withDatabaseName("dmigrate_e2e")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    fun cli() = DMigrate().subcommands(SchemaCommand(), DataCommand())

    fun jdbcUrl(): String {
        return "mysql://${container.username}:${container.password}@" +
            "${container.host}:${container.firstMappedPort}/${container.databaseName}"
    }

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

    beforeSpec {
        container.start()
        // Treiber-Bootstrap für die Test-JVM
        registerDrivers()

        val rawJdbc = "jdbc:mysql://${container.host}:${container.firstMappedPort}/${container.databaseName}"
        DriverManager.getConnection(rawJdbc, container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE users (
                        id     INT AUTO_INCREMENT PRIMARY KEY,
                        name   VARCHAR(100) NOT NULL,
                        email  VARCHAR(254),
                        active TINYINT(1) NOT NULL DEFAULT 1,
                        score  DECIMAL(10, 2)
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
                        CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users(id)
                    ) ENGINE=InnoDB
                    """.trimIndent()
                )
                stmt.execute(
                    "CREATE TABLE empty_audit (id INT AUTO_INCREMENT PRIMARY KEY, message VARCHAR(255)) ENGINE=InnoDB"
                )
            }
            conn.prepareStatement(
                "INSERT INTO users (name, email, active, score) VALUES (?, ?, ?, ?)"
            ).use { ps ->
                listOf(
                    Triple("alice", "alice@example.com" to true, "98.50"),
                    Triple("bob", "bob@example.com" to true, "75.25"),
                    Triple("carol", null to false, "0.00"),
                ).forEach { (name, emailActive, score) ->
                    ps.setString(1, name)
                    val email = emailActive.first
                    if (email == null) ps.setNull(2, java.sql.Types.VARCHAR) else ps.setString(2, email)
                    ps.setBoolean(3, emailActive.second)
                    ps.setBigDecimal(4, java.math.BigDecimal(score))
                    ps.executeUpdate()
                }
            }
            conn.prepareStatement("INSERT INTO orders (user_id, amount) VALUES (?, ?)").use { ps ->
                listOf(1 to "12.50", 1 to "100.00", 2 to "5.99").forEach { (uid, amt) ->
                    ps.setInt(1, uid)
                    ps.setBigDecimal(2, java.math.BigDecimal(amt))
                    ps.executeUpdate()
                }
            }
        }
    }

    afterSpec {
        if (container.isRunning) container.stop()
        JdbcUrlBuilderRegistry.clear()
        DataReaderRegistry.clear()
    }

    // ─── Round-Trip pro Format ───────────────────────────────────

    test("E2E MySQL: --format json round-trip writes id/name/email/active/score") {
        val out = captureStdout {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "--quiet",
                        "data", "export",
                        "--source", jdbcUrl(),
                        "--format", "json",
                        "--tables", "users",
                    )
                )
            }
        }
        out shouldContain "\"name\": \"alice\""
        out shouldContain "\"email\": \"bob@example.com\""
        out shouldContain "\"email\": null"
        // BigDecimal → string-encoded für Präzisionsschutz
        out shouldContain "\"score\": \"98.50\""
        // MySQL TINYINT(1) wird vom Connector/J als Boolean abgebildet
        // (tinyInt1isBit=true ist der Default).
        out shouldContain "\"active\": true"
        out shouldContain "\"active\": false"
    }

    test("E2E MySQL: --format yaml round-trip writes block-style sequence-of-maps") {
        val out = captureStdout {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "--quiet",
                        "data", "export",
                        "--source", jdbcUrl(),
                        "--format", "yaml",
                        "--tables", "users",
                    )
                )
            }
        }
        out shouldContain "- id: 1\n  name: alice"
        out shouldContain "- id: 2\n  name: bob"
        out shouldContain "score: '98.50'"
    }

    test("E2E MySQL: --format csv round-trip writes header + data rows") {
        val out = captureStdout {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "--quiet",
                        "data", "export",
                        "--source", jdbcUrl(),
                        "--format", "csv",
                        "--tables", "users",
                    )
                )
            }
        }
        out shouldContain "id,name,email,active,score"
        out shouldContain "1,alice,alice@example.com,true,98.50"
        out shouldContain "3,carol,,false,0.00"
    }

    // ─── §6.17 Empty-Table contract ──────────────────────────────

    test("E2E MySQL §6.17: empty MySQL table → JSON '[]'") {
        val out = captureStdout {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "--quiet",
                        "data", "export",
                        "--source", jdbcUrl(),
                        "--format", "json",
                        "--tables", "empty_audit",
                    )
                )
            }
        }
        out.trim() shouldBe "[]"
    }

    test("E2E MySQL §6.17: empty MySQL table → CSV header only") {
        val out = captureStdout {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "--quiet",
                        "data", "export",
                        "--source", jdbcUrl(),
                        "--format", "csv",
                        "--tables", "empty_audit",
                    )
                )
            }
        }
        out shouldBe "id,message\n"
    }

    // ─── --split-files mit echtem TableLister ───────────────────

    test("E2E MySQL: --split-files with auto-discovered tables writes one file per table") {
        val outDir = Files.createTempDirectory("dmigrate-mysql-split-")
        try {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "--quiet",
                        "data", "export",
                        "--source", jdbcUrl(),
                        "--format", "json",
                        "--output", outDir.toString(),
                        "--split-files",
                    )
                )
            }
            val files = Files.list(outDir).use { stream -> stream.map { it.fileName.toString() }.toList() }
            files shouldContainAll listOf("users.json", "orders.json", "empty_audit.json")
            outDir.resolve("empty_audit.json").toFile().readText().trim() shouldBe "[]"
            val users = outDir.resolve("users.json").toFile().readText()
            users shouldContain "\"name\": \"alice\""
            users shouldContain "\"name\": \"bob\""
            users shouldContain "\"name\": \"carol\""
        } finally {
            Files.walk(outDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    // ─── --filter wird server-seitig angewendet ──────────────────

    test("E2E MySQL: --filter is applied server-side via WHERE clause") {
        val out = captureStdout {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "--quiet",
                        "data", "export",
                        "--source", jdbcUrl(),
                        "--format", "json",
                        "--tables", "users",
                        "--filter", "active = 1",
                    )
                )
            }
        }
        out shouldContain "\"name\": \"alice\""
        out shouldContain "\"name\": \"bob\""
        out shouldNotContain "\"name\": \"carol\""
    }

    // ─── Cursor-Streaming via useCursorFetch ─────────────────────

    test("E2E MySQL: --chunk-size 2 streams via useCursorFetch (no truncation)") {
        val out = captureStdout {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "--quiet",
                        "data", "export",
                        "--source", jdbcUrl(),
                        "--format", "json",
                        "--tables", "users",
                        "--chunk-size", "2",
                    )
                )
            }
        }
        out shouldContain "\"name\": \"alice\""
        out shouldContain "\"name\": \"bob\""
        out shouldContain "\"name\": \"carol\""
    }

    // ─── ProgressSummary auf stderr ──────────────────────────────

    test("E2E MySQL: ProgressSummary appears on stderr by default") {
        val outFile = Files.createTempFile("dmigrate-mysql-out-", ".json")
        try {
            val stderr = captureStderr {
                shouldNotThrowAny {
                    cli().parse(
                        listOf(
                            "data", "export",
                            "--source", jdbcUrl(),
                            "--format", "json",
                            "--tables", "users",
                            "--output", outFile.toString(),
                        )
                    )
                }
            }
            stderr shouldContain "Exported"
            stderr shouldContain "rows"
        } finally {
            Files.deleteIfExists(outFile)
        }
    }
})
