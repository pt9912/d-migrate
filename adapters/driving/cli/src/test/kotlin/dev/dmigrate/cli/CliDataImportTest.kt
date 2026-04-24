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
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists

/**
 * CLI-E2E-Tests für `d-migrate data import`.
 *
 * Plan §4 Phase E Schritt 28 — Integration-Tests gegen SQLite (kein Container
 * nötig). Verifiziert den vollständigen Pfad CLI-Args → DataImportRunner →
 * StreamingImporter → SqliteDataWriter.
 *
 * Round-Trip-Tests exportieren zuerst aus einer Source-DB in eine Datei und
 * importieren diese dann in eine leere Target-DB, um den gesamten CLI-Pfad
 * abzudecken.
 */
class CliDataImportTest : FunSpec({

    fun cli() = DMigrate().subcommands(SchemaCommand(), DataCommand())

    fun createSampleDatabase(): Path {
        val db = Files.createTempFile("d-migrate-import-src-", ".db")
        db.deleteIfExists()
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePathString()}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
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

    fun createTargetDatabase(): Path {
        val db = Files.createTempFile("d-migrate-import-tgt-", ".db")
        db.deleteIfExists()
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePathString()}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
            }
        }
        return db
    }

    fun queryAll(db: Path, table: String): List<Map<String, Any?>> =
        JdbcTestHelper.queryAll("jdbc:sqlite:${db.absolutePathString()}", table)

    fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
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
        System.setErr(PrintStream(captured, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return captured.toString(Charsets.UTF_8)
    }

    fun exportToFile(sourceDb: Path, format: String, outFile: Path) {
        captureStdout {
            cli().parse(
                listOf(
                    "data", "export",
                    "--source", "sqlite:///${sourceDb.absolutePathString()}",
                    "--format", format,
                    "--tables", "users",
                    "--output", outFile.toString(),
                )
            )
        }
    }

    fun exportToDirectory(sourceDb: Path, format: String, outDir: Path) {
        captureStdout {
            cli().parse(
                listOf(
                    "data", "export",
                    "--source", "sqlite:///${sourceDb.absolutePathString()}",
                    "--format", format,
                    "--tables", "users",
                    "--output", outDir.toString(),
                    "--split-files",
                )
            )
        }
    }

    val expectedRows = listOf(
        mapOf("id" to 1, "name" to "alice"),
        mapOf("id" to 2, "name" to "bob"),
        mapOf("id" to 3, "name" to "charlie"),
    )

    beforeSpec {
        registerDrivers()
    }

    afterSpec {
        DatabaseDriverRegistry.clear()
    }

    // ─── Round-Trip: JSON ────────────────────────────────────────

    test("round-trip: JSON export → import → row equivalence") {
        val source = createSampleDatabase()
        val target = createTargetDatabase()
        val dataFile = Files.createTempFile("d-migrate-rt-", ".json")
        dataFile.deleteIfExists()
        try {
            exportToFile(source, "json", dataFile)

            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", "sqlite:///${target.absolutePathString()}",
                        "--source", dataFile.toString(),
                        "--format", "json",
                        "--table", "users",
                    )
                )
            }

            queryAll(target, "users") shouldContainExactlyInAnyOrder expectedRows
        } finally {
            Files.deleteIfExists(source)
            Files.deleteIfExists(target)
            Files.deleteIfExists(dataFile)
        }
    }

    // ─── Round-Trip: YAML ────────────────────────────────────────

    test("round-trip: YAML export → import → row equivalence") {
        val source = createSampleDatabase()
        val target = createTargetDatabase()
        val dataFile = Files.createTempFile("d-migrate-rt-", ".yaml")
        dataFile.deleteIfExists()
        try {
            exportToFile(source, "yaml", dataFile)

            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", "sqlite:///${target.absolutePathString()}",
                        "--source", dataFile.toString(),
                        "--format", "yaml",
                        "--table", "users",
                    )
                )
            }

            queryAll(target, "users") shouldContainExactlyInAnyOrder expectedRows
        } finally {
            Files.deleteIfExists(source)
            Files.deleteIfExists(target)
            Files.deleteIfExists(dataFile)
        }
    }

    // ─── Round-Trip: CSV ─────────────────────────────────────────

    test("round-trip: CSV export → import → row equivalence") {
        val source = createSampleDatabase()
        val target = createTargetDatabase()
        val dataFile = Files.createTempFile("d-migrate-rt-", ".csv")
        dataFile.deleteIfExists()
        try {
            exportToFile(source, "csv", dataFile)

            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", "sqlite:///${target.absolutePathString()}",
                        "--source", dataFile.toString(),
                        "--format", "csv",
                        "--table", "users",
                    )
                )
            }

            queryAll(target, "users") shouldContainExactlyInAnyOrder expectedRows
        } finally {
            Files.deleteIfExists(source)
            Files.deleteIfExists(target)
            Files.deleteIfExists(dataFile)
        }
    }

    // ─── --truncate ──────────────────────────────────────────────

    test("--truncate replaces pre-existing target data") {
        val source = createSampleDatabase()
        val target = createTargetDatabase()
        val dataFile = Files.createTempFile("d-migrate-trunc-", ".json")
        dataFile.deleteIfExists()

        // Seed target with pre-existing row
        DriverManager.getConnection("jdbc:sqlite:${target.absolutePathString()}").use { conn ->
            conn.prepareStatement("INSERT INTO users (id, name) VALUES (?, ?)").use { ps ->
                ps.setInt(1, 10)
                ps.setString(2, "pre-existing")
                ps.executeUpdate()
            }
        }

        try {
            exportToFile(source, "json", dataFile)

            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", "sqlite:///${target.absolutePathString()}",
                        "--source", dataFile.toString(),
                        "--format", "json",
                        "--table", "users",
                        "--truncate",
                    )
                )
            }

            val rows = queryAll(target, "users")
            rows shouldContainExactlyInAnyOrder expectedRows
        } finally {
            Files.deleteIfExists(source)
            Files.deleteIfExists(target)
            Files.deleteIfExists(dataFile)
        }
    }

    // ─── --on-conflict update ────────────────────────────────────

    test("--on-conflict update upserts existing rows and inserts new ones") {
        val source = createSampleDatabase()
        val target = createTargetDatabase()
        val dataFile = Files.createTempFile("d-migrate-upsert-", ".json")
        dataFile.deleteIfExists()

        // Seed target with stale versions of id=1 and id=2
        DriverManager.getConnection("jdbc:sqlite:${target.absolutePathString()}").use { conn ->
            conn.prepareStatement("INSERT INTO users (id, name) VALUES (?, ?)").use { ps ->
                for ((id, name) in listOf(1 to "OLD_ALICE", 2 to "OLD_BOB")) {
                    ps.setInt(1, id)
                    ps.setString(2, name)
                    ps.executeUpdate()
                }
            }
        }

        try {
            exportToFile(source, "json", dataFile)

            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", "sqlite:///${target.absolutePathString()}",
                        "--source", dataFile.toString(),
                        "--format", "json",
                        "--table", "users",
                        "--on-conflict", "update",
                    )
                )
            }

            val rows = queryAll(target, "users")
            rows shouldContainExactlyInAnyOrder expectedRows
        } finally {
            Files.deleteIfExists(source)
            Files.deleteIfExists(target)
            Files.deleteIfExists(dataFile)
        }
    }

    // ─── --trigger-mode disable → Exit 2 ─────────────────────────

    test("--trigger-mode disable on SQLite → Exit 2") {
        val target = createTargetDatabase()
        val dataFile = Files.createTempFile("d-migrate-trigger-", ".json")
        Files.writeString(dataFile, """[{"id":1,"name":"test"}]""")
        try {
            val stderr = captureStderr {
                val ex = shouldThrow<ProgramResult> {
                    cli().parse(
                        listOf(
                            "data", "import",
                            "--target", "sqlite:///${target.absolutePathString()}",
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
            Files.deleteIfExists(target)
            Files.deleteIfExists(dataFile)
        }
    }

    // ─── Directory Import ────────────────────────────────────────

    test("directory import: split-file export → directory import → row equivalence") {
        val source = createSampleDatabase()
        val target = createTargetDatabase()
        val exportDir = Files.createTempDirectory("d-migrate-dir-export-")
        try {
            exportToDirectory(source, "json", exportDir)

            shouldNotThrowAny {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", "sqlite:///${target.absolutePathString()}",
                        "--source", exportDir.toString(),
                        "--format", "json",
                    )
                )
            }

            queryAll(target, "users") shouldContainExactlyInAnyOrder expectedRows
        } finally {
            Files.deleteIfExists(source)
            Files.deleteIfExists(target)
            Files.walk(exportDir).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
})
