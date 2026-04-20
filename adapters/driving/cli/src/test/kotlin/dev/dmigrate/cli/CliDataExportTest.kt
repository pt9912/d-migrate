package dev.dmigrate.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.DataCommand
import dev.dmigrate.cli.commands.SchemaCommand
import dev.dmigrate.driver.DatabaseDriverRegistry
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * CLI-Round-Trip-Tests für `d-migrate data export`.
 *
 * Plan §4 Phase E Schritt 27 — Integration-Tests gegen SQLite (kein Container
 * nötig). Verifiziert den vollständigen Pfad CLI-Args → NamedConnectionResolver
 * → ConnectionUrlParser → HikariConnectionPoolFactory → DataReaderRegistry →
 * StreamingExporter → Output-Stream.
 *
 * Tests werden gegen eine temporäre SQLite-Datei ausgeführt; die Tabellen
 * werden direkt über JDBC angelegt, nicht über das Schema-Subkommando, weil
 * `data export` schemaagnostisch gegen jede beliebige bestehende DB läuft.
 */
class CliDataExportTest : FunSpec({

    fun cli() = DMigrate().subcommands(SchemaCommand(), DataCommand())

    /**
     * Erstellt eine frische temporäre SQLite-DB mit zwei Tabellen `users`
     * (mit Daten) und `empty_table` (leer für §6.17). Gibt den absoluten
     * Pfad zurück.
     */
    fun createSampleDatabase(): Path {
        val db = Files.createTempFile("d-migrate-cli-test-", ".db")
        db.deleteIfExists()
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePathString()}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
                stmt.execute("CREATE TABLE empty_table (id INTEGER PRIMARY KEY, value TEXT)")
            }
            conn.prepareStatement("INSERT INTO users (id, name) VALUES (?, ?)").use { ps ->
                listOf(1 to "alice", 2 to "bob", 3 to "charlie").forEach { (id, name) ->
                    ps.setInt(1, id)
                    ps.setString(2, name)
                    ps.executeUpdate()
                }
            }
        }
        return db
    }

    /** Schreibt eine temporäre `.d-migrate.yaml` mit dem gegebenen Inhalt. */
    fun tempConfig(content: String): Path {
        val file = Files.createTempFile("d-migrate-test-", ".yaml")
        Files.writeString(file, content)
        return file
    }

    /**
     * Führt `block` aus und gibt das auf System.out geschriebene Bytes-Array
     * zurück. System.out wird vorher umgelenkt und nachher restauriert. Der
     * vom CLI gewrappte `NonClosingOutputStream` lässt System.out intakt.
     */
    fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val captured = ByteArrayOutputStream()
        val printStream = PrintStream(captured, true, Charsets.UTF_8)
        System.setOut(printStream)
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return captured.toString(Charsets.UTF_8)
    }

    fun captureStderr(block: () -> Unit): String {
        val original = System.err
        val captured = ByteArrayOutputStream()
        val printStream = PrintStream(captured, true, Charsets.UTF_8)
        System.setErr(printStream)
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return captured.toString(Charsets.UTF_8)
    }

    beforeSpec {
        // Plan §6.18 / Phase E Bootstrap — die echte Main.kt macht das beim
        // Programmstart, im Test-JVM müssen wir es selbst aufrufen.
        registerDrivers()
    }

    afterSpec {
        // Saubere Registry-Zustände zwischen Specs (andere CLI-Tests rufen
        // SchemaCommand auf, das die Registry nicht braucht — aber Hygiene).
        DatabaseDriverRegistry.clear()
    }

    // ─── Round-Trip: --source <name> aus .d-migrate.yaml ─────────

    test("§6.14 round-trip: --source name → .d-migrate.yaml → SQLite → JSON stdout") {
        val db = createSampleDatabase()
        val cfg = tempConfig(
            """
            database:
              connections:
                local: "sqlite:///${db.absolutePathString()}"
            """.trimIndent()
        )

        val out = captureStdout {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "-c", cfg.toString(),
                        "data", "export",
                        "--source", "local",
                        "--format", "json",
                        "--tables", "users",
                    )
                )
            }
        }
        // JSON-Writer schreibt einen Array-of-Objects (siehe Phase D Golden Master)
        out shouldContain "\"id\": 1"
        out shouldContain "\"name\": \"alice\""
        out shouldContain "\"id\": 3"
        out shouldContain "\"name\": \"charlie\""

        Files.deleteIfExists(db)
        Files.deleteIfExists(cfg)
    }

    test("URL --source bypasses .d-migrate.yaml entirely") {
        val db = createSampleDatabase()
        val out = captureStdout {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "csv",
                        "--tables", "users",
                    )
                )
            }
        }
        // CSV-Writer schreibt Header + Rows
        out shouldContain "id,name"
        out shouldContain "1,alice"
        out shouldContain "3,charlie"
        Files.deleteIfExists(db)
    }

    test("--format yaml writes block-style sequence-of-maps") {
        val db = createSampleDatabase()
        val out = captureStdout {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "yaml",
                        "--tables", "users",
                    )
                )
            }
        }
        out shouldContain "- id: 1\n  name: alice"
        out shouldContain "- id: 2\n  name: bob"
        out shouldContain "- id: 3\n  name: charlie"
        Files.deleteIfExists(db)
    }

    // ─── --output (single file) ──────────────────────────────────

    test("--output writes to a single file when one table is selected") {
        val db = createSampleDatabase()
        val outFile = Files.createTempFile("d-migrate-out-", ".json")
        outFile.deleteIfExists()
        try {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "json",
                        "--tables", "users",
                        "--output", outFile.toString(),
                    )
                )
            }
            outFile.exists() shouldBe true
            val written = outFile.readText()
            written shouldContain "\"id\": 1"
            written shouldContain "\"name\": \"alice\""
        } finally {
            Files.deleteIfExists(db)
            Files.deleteIfExists(outFile)
        }
    }

    // ─── --split-files / multiple tables ─────────────────────────

    test("--split-files writes one file per table into the output directory") {
        val db = createSampleDatabase()
        val outDir = Files.createTempDirectory("d-migrate-split-")
        try {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "json",
                        "--tables", "users,empty_table",
                        "--output", outDir.toString(),
                        "--split-files",
                    )
                )
            }
            val produced = Files.list(outDir).use { it.toList() }
            produced.map { it.fileName.toString() } shouldContainAll listOf("users.json", "empty_table.json")
            // §6.17: empty table → "[]"
            outDir.resolve("empty_table.json").readText().trim() shouldBe "[]"
        } finally {
            Files.deleteIfExists(db)
            Files.walk(outDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    test("multiple tables to stdout without --split-files → Exit 2") {
        val db = createSampleDatabase()
        try {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "json",
                        "--tables", "users,empty_table",
                    )
                )
            }
            ex.statusCode shouldBe 2
        } finally {
            Files.deleteIfExists(db)
        }
    }

    test("--split-files without --output → Exit 2") {
        val db = createSampleDatabase()
        try {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "json",
                        "--tables", "users",
                        "--split-files",
                    )
                )
            }
            ex.statusCode shouldBe 2
        } finally {
            Files.deleteIfExists(db)
        }
    }

    // ─── §6.17 Empty-Table contract via CLI ──────────────────────

    test("§6.17: empty table → JSON '[]'") {
        val db = createSampleDatabase()
        val out = captureStdout {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "json",
                        "--tables", "empty_table",
                    )
                )
            }
        }
        out.trim() shouldBe "[]"
        Files.deleteIfExists(db)
    }

    test("§6.17: empty table → CSV header only") {
        val db = createSampleDatabase()
        val out = captureStdout {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "csv",
                        "--tables", "empty_table",
                    )
                )
            }
        }
        out shouldBe "id,value\n"
        Files.deleteIfExists(db)
    }

    test("§6.17: empty table → CSV no content with --csv-no-header") {
        val db = createSampleDatabase()
        val out = captureStdout {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "csv",
                        "--tables", "empty_table",
                        "--csv-no-header",
                    )
                )
            }
        }
        out shouldBe ""
        Files.deleteIfExists(db)
    }

    // ─── Auto-table-discovery (--tables omitted) ─────────────────

    test("auto-discovers tables when --tables omitted, exports as split files") {
        val db = createSampleDatabase()
        val outDir = Files.createTempDirectory("d-migrate-auto-")
        try {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "json",
                        "--output", outDir.toString(),
                        "--split-files",
                    )
                )
            }
            val produced = Files.list(outDir).use { it.map { p -> p.fileName.toString() }.toList() }
            produced shouldContainAll listOf("users.json", "empty_table.json")
        } finally {
            Files.deleteIfExists(db)
            Files.walk(outDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    // ─── Exit-Code-Mapping (§6.10) ───────────────────────────────

    test("§6.10 Exit 7: connection name not found in .d-migrate.yaml") {
        val cfg = tempConfig(
            """
            database:
              connections:
                local: "sqlite:///tmp/exists.db"
            """.trimIndent()
        )
        val ex = shouldThrow<ProgramResult> {
            cli().parse(
                listOf(
                    "-c", cfg.toString(),
                    "data", "export",
                    "--source", "staging",     // <- not defined
                    "--format", "json",
                    "--tables", "users",
                )
            )
        }
        ex.statusCode shouldBe 7
        Files.deleteIfExists(cfg)
    }

    test("§6.10 Exit 7: missing \${ENV_VAR}") {
        val d = "${'$'}"
        val cfg = tempConfig(
            """
            database:
              connections:
                prod: "sqlite:///${d}{NOT_SET_ENV_VAR}/foo.db"
            """.trimIndent()
        )
        val ex = shouldThrow<ProgramResult> {
            cli().parse(
                listOf(
                    "-c", cfg.toString(),
                    "data", "export",
                    "--source", "prod",
                    "--format", "json",
                    "--tables", "users",
                )
            )
        }
        ex.statusCode shouldBe 7
        Files.deleteIfExists(cfg)
    }

    test("§6.10 Exit 7: --config <path> file does not exist") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(
                listOf(
                    "-c", "/does/not/exist.yaml",
                    "data", "export",
                    "--source", "local",
                    "--format", "json",
                )
            )
        }
        ex.statusCode shouldBe 7
    }

    test("§6.10 Exit 7: malformed URL → ConnectionUrlParser fails") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(
                listOf(
                    "data", "export",
                    "--source", "://broken",
                    "--format", "json",
                )
            )
        }
        ex.statusCode shouldBe 7
    }

    test("§6.10 Exit 5: SELECT against non-existent table") {
        val db = createSampleDatabase()
        try {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "json",
                        "--tables", "no_such_table",
                    )
                )
            }
            ex.statusCode shouldBe 5
        } finally {
            Files.deleteIfExists(db)
        }
    }

    // ─── F32: --filter wird tatsächlich ans SELECT durchgereicht ─

    test("F32: --filter narrows the JSON output via WHERE clause") {
        val db = createSampleDatabase()
        try {
            val out = captureStdout {
                shouldNotThrowAny {
                    cli().parse(
                        listOf(
                            "data", "export",
                            "--source", "sqlite:///${db.absolutePathString()}",
                            "--format", "json",
                            "--tables", "users",
                            "--filter", "id = 2",
                        )
                    )
                }
            }
            // Nur 'bob' (id=2) darf im Output landen, alice und charlie nicht.
            out shouldContain "\"id\": 2"
            out shouldContain "\"name\": \"bob\""
            out shouldNotContain "\"name\": \"alice\""
            out shouldNotContain "\"name\": \"charlie\""
        } finally {
            Files.deleteIfExists(db)
        }
    }

    test("F32: --filter applies to multiple tables in --split-files mode") {
        val db = createSampleDatabase()
        val outDir = Files.createTempDirectory("d-migrate-filter-")
        try {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "json",
                        "--tables", "users,empty_table",
                        "--output", outDir.toString(),
                        "--split-files",
                        "--filter", "id > 1",
                    )
                )
            }
            // users.json darf alice nicht enthalten — nur bob (id=2), charlie (id=3)
            val users = outDir.resolve("users.json").readText()
            users shouldContain "\"name\": \"bob\""
            users shouldContain "\"name\": \"charlie\""
            users shouldNotContain "\"name\": \"alice\""
            // empty_table bleibt leer (`[]`), das WHERE matched einfach keine Row.
            outDir.resolve("empty_table.json").readText().trim() shouldBe "[]"
        } finally {
            Files.deleteIfExists(db)
            Files.walk(outDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    test("F32: --filter empty/blank value behaves like no filter") {
        val db = createSampleDatabase()
        try {
            val out = captureStdout {
                shouldNotThrowAny {
                    cli().parse(
                        listOf(
                            "data", "export",
                            "--source", "sqlite:///${db.absolutePathString()}",
                            "--format", "json",
                            "--tables", "users",
                            "--filter", "   ",
                        )
                    )
                }
            }
            // Alle drei Rows müssen drin sein
            out shouldContain "\"name\": \"alice\""
            out shouldContain "\"name\": \"bob\""
            out shouldContain "\"name\": \"charlie\""
        } finally {
            Files.deleteIfExists(db)
        }
    }

    test("LF-013: --since-column/--since narrow the export via a parameterized predicate") {
        val db = createSampleDatabase()
        try {
            val out = captureStdout {
                shouldNotThrowAny {
                    cli().parse(
                        listOf(
                            "data", "export",
                            "--source", "sqlite:///${db.absolutePathString()}",
                            "--format", "json",
                            "--tables", "users",
                            "--since-column", "id",
                            "--since", "2",
                        )
                    )
                }
            }
            out shouldContain "\"id\": 2"
            out shouldContain "\"name\": \"bob\""
            out shouldContain "\"id\": 3"
            out shouldContain "\"name\": \"charlie\""
            out shouldNotContain "\"name\": \"alice\""
        } finally {
            Files.deleteIfExists(db)
        }
    }

    test("Invalid --filter DSL exits 2 before export") {
        val db = createSampleDatabase()
        try {
            val stderr = captureStderr {
                val ex = shouldThrow<ProgramResult> {
                    cli().parse(
                        listOf(
                            "data", "export",
                            "--source", "sqlite:///${db.absolutePathString()}",
                            "--format", "json",
                            "--tables", "users",
                            "--filter", "LIMIT 10",
                        )
                    )
                }
                ex.statusCode shouldBe 2
            }
            stderr shouldContain "Invalid --filter"
        } finally {
            Files.deleteIfExists(db)
        }
    }

    // ─── F33: --tables Identifier-Validierung (§6.7) ─────────────

    test("F33 §6.7: --tables 'weird name' (whitespace) → Exit 2") {
        val db = createSampleDatabase()
        try {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "json",
                        "--tables", "weird name",
                    )
                )
            }
            ex.statusCode shouldBe 2
        } finally {
            Files.deleteIfExists(db)
        }
    }

    test("F33 §6.7: --tables with SQL injection attempt → Exit 2") {
        val db = createSampleDatabase()
        try {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "json",
                        "--tables", "users; DROP TABLE users",
                    )
                )
            }
            ex.statusCode shouldBe 2
        } finally {
            Files.deleteIfExists(db)
        }
    }

    test("F33 §6.7: --tables with hyphen → Exit 2 (not in [A-Za-z0-9_])") {
        val db = createSampleDatabase()
        try {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "json",
                        "--tables", "user-table",
                    )
                )
            }
            ex.statusCode shouldBe 2
        } finally {
            Files.deleteIfExists(db)
        }
    }

    test("F33 §6.7: --tables 'public.users' (schema-qualified) → accepted") {
        // SQLite hat keine echten Schemas, der Name 'public.users' wird im
        // SELECT zu '"public"."users"' und SQLite wirft Exit 5 (Tabelle
        // existiert nicht). Wichtig hier: der Validator AKZEPTIERT das Pattern,
        // d.h. wir sollten Exit 5 (nicht 2) sehen.
        val db = createSampleDatabase()
        try {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "json",
                        "--tables", "public.users",
                    )
                )
            }
            ex.statusCode shouldBe 5   // not 2 — pattern was accepted
        } finally {
            Files.deleteIfExists(db)
        }
    }

    test("F33 §6.7: leading digit in --tables → Exit 2") {
        val db = createSampleDatabase()
        try {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "json",
                        "--tables", "1users",
                    )
                )
            }
            ex.statusCode shouldBe 2
        } finally {
            Files.deleteIfExists(db)
        }
    }

    // ─── F34: --no-progress unterdrückt die ProgressSummary ──────

    test("F34: --no-progress suppresses the ProgressSummary on stderr") {
        val db = createSampleDatabase()
        // Wir capturen stderr, indem wir System.err umlenken — analog zu
        // captureStdout, aber für stderr.
        val originalErr = System.err
        val captured = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(captured, true, Charsets.UTF_8))
        try {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "--no-progress",
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "json",
                        "--tables", "users",
                    )
                )
            }
        } finally {
            System.setErr(originalErr)
            Files.deleteIfExists(db)
        }
        captured.toString(Charsets.UTF_8) shouldNotContain "Exported"
    }

    test("F34: ProgressSummary is shown by default (no --no-progress)") {
        val db = createSampleDatabase()
        val originalErr = System.err
        val captured = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(captured, true, Charsets.UTF_8))
        try {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "json",
                        "--tables", "users",
                    )
                )
            }
        } finally {
            System.setErr(originalErr)
            Files.deleteIfExists(db)
        }
        captured.toString(Charsets.UTF_8) shouldContain "Exported"
    }

    // ─── F37: --quiet unterdrückt auch Warnings ──────────────────

    /**
     * Erzeugt eine SQLite-DB mit einer Tabelle `metrics(id, value REAL)`,
     * deren `value`-Spalte eine IEEE-754-Infinity enthält. Beim Export wird
     * `ValueSerializer.serializeFloating()` daraus eine W202-Warnung
     * machen, weil Infinity in JSON/YAML nicht als Number darstellbar ist.
     *
     * Wir verwenden den SQL-Literal `1e1000`, weil
     * `PreparedStatement.setDouble(Double.POSITIVE_INFINITY)` von sqlite-jdbc
     * via `Double.toString()` zu einem TEXT-Wert "Infinity" konvertiert wird
     * und dann beim Lesen als String zurückkommt — keine W202.
     * SQLite parst `1e1000` dagegen als numerisches Literal und überlauft
     * direkt zu IEEE-754 Infinity, was beim Read-Path als `Double` ankommt.
     */
    fun createDatabaseWithInfinity(): Path {
        val db = Files.createTempFile("d-migrate-warn-test-", ".db")
        db.deleteIfExists()
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePathString()}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE metrics (id INTEGER PRIMARY KEY, value REAL)")
                // SQL-Literal überlauft zu Double.POSITIVE_INFINITY
                stmt.execute("INSERT INTO metrics (id, value) VALUES (1, 1e1000)")
            }
        }
        return db
    }

    test("F37: --quiet suppresses ValueSerializer warnings on stderr") {
        val db = createDatabaseWithInfinity()
        val originalErr = System.err
        val captured = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(captured, true, Charsets.UTF_8))
        try {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "--quiet",
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "json",
                        "--tables", "metrics",
                        "--output", Files.createTempFile("dmigrate-out-", ".json").toString(),
                    )
                )
            }
        } finally {
            System.setErr(originalErr)
            Files.deleteIfExists(db)
        }
        val stderr = captured.toString(Charsets.UTF_8)
        // cli-spec §1.3: --quiet = "Nur Fehler" — weder W201/W202 noch
        // ProgressSummary dürfen sichtbar sein.
        stderr shouldNotContain "W202"
        stderr shouldNotContain "Exported"
    }

    test("F37: without --quiet the W202 warning is visible on stderr") {
        val db = createDatabaseWithInfinity()
        val originalErr = System.err
        val captured = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(captured, true, Charsets.UTF_8))
        try {
            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "json",
                        "--tables", "metrics",
                        "--output", Files.createTempFile("dmigrate-out-", ".json").toString(),
                    )
                )
            }
        } finally {
            System.setErr(originalErr)
            Files.deleteIfExists(db)
        }
        val stderr = captured.toString(Charsets.UTF_8)
        stderr shouldContain "W202"
        stderr shouldContain "metrics.value"
    }

    // ─── F35: --csv-delimiter wird sauber auf Exit 2 gemappt ─────

    test("F35: --csv-delimiter '::' (multi-char) → Exit 2 (not raw IAE)") {
        val db = createSampleDatabase()
        try {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "csv",
                        "--tables", "users",
                        "--csv-delimiter", "::",
                    )
                )
            }
            ex.statusCode shouldBe 2
        } finally {
            Files.deleteIfExists(db)
        }
    }

    test("F35: --csv-delimiter '' (empty) → Exit 2") {
        val db = createSampleDatabase()
        try {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--format", "csv",
                        "--tables", "users",
                        "--csv-delimiter", "",
                    )
                )
            }
            ex.statusCode shouldBe 2
        } finally {
            Files.deleteIfExists(db)
        }
    }

    // ─── §6.15: --format ist Pflicht ─────────────────────────────

    test("§6.15: missing --format produces a Clikt usage error (Exit 2 via .main; .parse throws)") {
        val db = createSampleDatabase()
        try {
            // Clikt's parse() throws MissingOption (subclass of UsageError) — in
            // the .main() entry point that maps to Exit 2. Here we just assert
            // that it does throw an error of the right type. Subclasses of
            // CliktError carry the user-facing message; we don't pin the exact
            // wording because it varies between Clikt versions.
            shouldThrow<com.github.ajalt.clikt.core.CliktError> {
                cli().parse(
                    listOf(
                        "data", "export",
                        "--source", "sqlite:///${db.absolutePathString()}",
                        "--tables", "users",
                    )
                )
            }
        } finally {
            Files.deleteIfExists(db)
        }
    }
})
