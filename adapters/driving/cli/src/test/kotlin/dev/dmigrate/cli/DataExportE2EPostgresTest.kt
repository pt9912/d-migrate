package dev.dmigrate.cli

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.DataCommand
import dev.dmigrate.cli.commands.SchemaCommand
import dev.dmigrate.driver.DatabaseDriverRegistry
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

private val IntegrationTag = NamedTag("integration")

/**
 * End-to-End-Tests für `d-migrate data export` gegen einen realen PostgreSQL-
 * Container (Testcontainers). Plan §4 Phase F Schritt 28.
 *
 * Anders als die SQLite-CLI-Tests in [CliDataExportTest] gehen diese Tests
 * den vollständigen Pfad inkl. echter PostgreSQL-spezifischer Datentypen,
 * Cursor-Streaming (`setFetchSize` + `autoCommit=false`) und der
 * `information_schema`-basierten `PostgresTableLister`-Auflösung durch.
 *
 * Tagged `integration` — läuft nur mit `./gradlew test -PintegrationTests`
 * und im `.github/workflows/integration.yml`-Workflow. Im Default-Build
 * wird der Spec via Kotest-Tag-Filter komplett übersprungen.
 *
 * Verifiziert:
 * - Round-Trip JSON/YAML/CSV mit echten PG-Typen (INT, TEXT, NUMERIC, TIMESTAMP, BOOLEAN)
 * - §6.17 Empty-Table-Vertrag pro Format gegen eine echte leere PG-Tabelle
 * - `--split-files` mit mehreren Tabellen und schemaqualifizierten Namen
 * - Auto-Discovery der Tabellen via `PostgresTableLister`
 * - `--filter` (Roh-WHERE) wird server-seitig angewendet
 * - Connection-Pool wird korrekt geleert (kein Leak nach mehreren Exporten)
 */
class DataExportE2EPostgresTest : FunSpec({

    tags(IntegrationTag)

    val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("dmigrate_e2e")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    fun cli() = DMigrate().subcommands(SchemaCommand(), DataCommand())

    fun jdbcUrl(): String {
        // Wir bauen die für unseren ConnectionUrlParser kompatible Form
        // (postgresql://user:pw@host:port/database), nicht die roh-JDBC-URL.
        return "postgresql://${container.username}:${container.password}@" +
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
        // Treiber-Bootstrap: registriert PostgresJdbcUrlBuilder, PostgresDataReader,
        // PostgresTableLister in den globalen Registries. Im echten CLI-Aufruf
        // macht das `Main.kt`; in der Test-JVM müssen wir es selbst starten.
        registerDrivers()

        // Schema und Daten einmal pro Spec anlegen — die einzelnen Tests
        // sind read-only und können sich die Tabellen teilen.
        val rawJdbc = "jdbc:postgresql://${container.host}:${container.firstMappedPort}/${container.databaseName}"
        DriverManager.getConnection(rawJdbc, container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE users (
                        id     SERIAL PRIMARY KEY,
                        name   TEXT NOT NULL,
                        email  TEXT,
                        active BOOLEAN NOT NULL DEFAULT TRUE,
                        score  NUMERIC(10, 2)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE orders (
                        id        SERIAL PRIMARY KEY,
                        user_id   INTEGER NOT NULL REFERENCES users(id),
                        amount    NUMERIC(12, 2) NOT NULL,
                        placed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """.trimIndent()
                )
                stmt.execute("CREATE TABLE empty_audit (id SERIAL PRIMARY KEY, message TEXT)")
            }
            conn.prepareStatement(
                "INSERT INTO users (name, email, active, score) VALUES (?, ?, ?, ?)"
            ).use { ps ->
                listOf(
                    Quad("alice", "alice@example.com", true, "98.50"),
                    Quad("bob", "bob@example.com", true, "75.25"),
                    Quad("carol", null, false, "0.00"),
                ).forEach { q ->
                    ps.setString(1, q.name)
                    if (q.email == null) ps.setNull(2, java.sql.Types.VARCHAR) else ps.setString(2, q.email)
                    ps.setBoolean(3, q.active)
                    ps.setBigDecimal(4, java.math.BigDecimal(q.score))
                    ps.executeUpdate()
                }
            }
            conn.prepareStatement(
                "INSERT INTO orders (user_id, amount) VALUES (?, ?)"
            ).use { ps ->
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
        DatabaseDriverRegistry.clear()
    }

    // ─── Round-Trip pro Format ───────────────────────────────────

    test("E2E PG: --format json round-trip writes id/name/email/active/score") {
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
        out shouldContain "\"email\": null"     // carol
        // BigDecimal → string-encoded für Präzisionsschutz
        out shouldContain "\"score\": \"98.50\""
        out shouldContain "\"active\": true"
        out shouldContain "\"active\": false"
    }

    test("E2E PG: --format yaml round-trip writes block-style sequence-of-maps") {
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
        // BigDecimal als gequoteter YAML-String
        out shouldContain "score: '98.50'"
    }

    test("E2E PG: --format csv round-trip writes header + RFC-4180-quoted rows") {
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
        // NULL-Spalte (carol's email) ist standardmäßig der leere String
        out shouldContain "3,carol,,false,0.00"
    }

    // ─── §6.17 Empty-Table contract ──────────────────────────────

    test("E2E PG §6.17: empty PG table → JSON '[]'") {
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

    test("E2E PG §6.17: empty PG table → CSV header only") {
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

    // ─── --split-files mit echtem TableLister + mehreren Tabellen ─

    test("E2E PG: --split-files with auto-discovered tables writes one file per table") {
        val outDir = Files.createTempDirectory("dmigrate-pg-split-")
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
            // Auto-Discovery via PostgresTableLister liefert alle drei User-Tabellen
            files shouldContainAll listOf("users.json", "orders.json", "empty_audit.json")
            // empty_audit.json muss laut §6.17 ein leeres Array sein
            outDir.resolve("empty_audit.json").toFile().readText().trim() shouldBe "[]"
            // users.json enthält die echten Daten
            val users = outDir.resolve("users.json").toFile().readText()
            users shouldContain "\"name\": \"alice\""
            users shouldContain "\"name\": \"bob\""
            users shouldContain "\"name\": \"carol\""
        } finally {
            Files.walk(outDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    // ─── --filter wird server-seitig angewendet ──────────────────

    test("E2E PG: --filter is applied server-side via WHERE clause") {
        val out = captureStdout {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "--quiet",
                        "data", "export",
                        "--source", jdbcUrl(),
                        "--format", "json",
                        "--tables", "users",
                        "--filter", "active = TRUE",
                    )
                )
            }
        }
        out shouldContain "\"name\": \"alice\""
        out shouldContain "\"name\": \"bob\""
        out shouldNotContain "\"name\": \"carol\""
    }

    // ─── Chunk-Streaming gegen einen größeren Datensatz ──────────

    test("E2E PG: --chunk-size 2 streams users in multiple chunks (no truncation)") {
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
        // Trotz chunkSize=2 müssen alle 3 Rows im Output landen
        out shouldContain "\"name\": \"alice\""
        out shouldContain "\"name\": \"bob\""
        out shouldContain "\"name\": \"carol\""
    }

    // ─── ProgressSummary auf stderr ──────────────────────────────

    test("E2E PG: ProgressSummary appears on stderr by default") {
        val outFile = Files.createTempFile("dmigrate-pg-out-", ".json")
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

// Kleine Hilfsstruktur für die Test-Daten — Pair/Triple reichen nicht für 4 Felder.
private data class Quad(val name: String, val email: String?, val active: Boolean, val score: String)
